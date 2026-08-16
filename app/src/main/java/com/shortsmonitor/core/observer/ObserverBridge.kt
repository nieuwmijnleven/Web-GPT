package com.shortsmonitor.core.observer

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.model.ObserverErrorCode

/**
 * JavaScript 관찰기와 네이티브 사이의 통신 브리지.
 *
 * - [attach]는 페이지를 열기 전에 `addWebMessageListener`로 통신 객체를 등록하고
 *   허용 출처([ALLOWED_ORIGIN_RULES])를 제한한다. 잘못된 출처에서 보낸 메시지는
 *   WebView 계층에서 차단된다.
 * - 지원되는 환경에서는 문서 시작 시점 스크립트([ShortsObserverScript.script])를
 *   등록하고, 지원되지 않으면 페이지 로드 완료 후 주입한다.
 * - 하트비트로 관찰기 중단을 감지하고([ObserverWatchdog]) [restartObserver]로 재시작한다.
 *
 * 보안: 브리지 메시지는 신뢰된 명령이 아니라 데이터로만 파싱한다.
 * 메시지 크기·항목 수·문자열 길이 제한을 적용하고, 허용된 메시지 타입만 처리한다.
 * 메시지는 메인 프레임에서 온 경우만 처리한다.
 */
class ObserverBridge(private val onMessage: (ObserverMessage) -> Unit) {

    /** 마지막 하트비트 수신 시각 (epoch ms). attach 시점으로 초기화된다. */
    var lastHeartbeatAtMs: Long = System.currentTimeMillis()
        private set

    /** 마지막 하트비트의 스냅샷 리비전. */
    var lastHeartbeatRevision: Int = -1
        private set

    /** 문서 시작 시점 스크립트가 등록되었는지 여부. */
    var documentStartInjected: Boolean = false
        private set

    /** 네트워크 관찰기 설치 상태 (문서 시작 주입 여부와 무관하게 네이티브 기준 값). */
    var networkObserverInstalledAtMs: Long = 0L
        private set

    private var attachedWebView: WebView? = null

    /**
     * [webView]에 관찰기 스크립트와 메시지 리스너를 등록한다.
     * loadUrl 이전에 호출해야 한다. 같은 WebView에 두 번 등록하지 않는다.
     */
    fun attach(webView: WebView) {
        if (attachedWebView === webView) return
        detach()
        attachedWebView = webView
        lastHeartbeatAtMs = System.currentTimeMillis()
        networkObserverInstalledAtMs = System.currentTimeMillis()
        ObserverDiagnostics.networkObserverInstalledAtMs = networkObserverInstalledAtMs

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching {
                WebViewCompat.addWebMessageListener(
                    webView,
                    ShortsObserverScript.BRIDGE_OBJECT_NAME,
                    ALLOWED_ORIGIN_RULES,
                    object : WebViewCompat.WebMessageListener {
                        override fun onPostMessage(
                            view: WebView,
                            message: WebMessageCompat,
                            sourceOrigin: Uri,
                            isMainFrame: Boolean,
                            jsReplyProxy: JavaScriptReplyProxy,
                        ) {
                            if (!isMainFrame) return
                            val data = message.data ?: return
                            handleMessage(data)
                        }
                    },
                )
            }.onFailure { error ->
                ShortsLog.w("Failed to register observer message listener", error)
                ObserverDiagnostics.setError(ObserverErrorCode.NETWORK_OBSERVER_INSTALL_FAILED)
            }
        } else {
            ShortsLog.w("WEB_MESSAGE_LISTENER not supported; observer messages disabled")
            ObserverDiagnostics.setError(ObserverErrorCode.NETWORK_OBSERVER_INSTALL_FAILED)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    ShortsObserverScript.script,
                    ALLOWED_ORIGIN_RULES,
                )
                documentStartInjected = true
                ObserverDiagnostics.documentStartSupported = true
            }.onFailure { error ->
                ShortsLog.w("Failed to register document start script", error)
                ObserverDiagnostics.documentStartSupported = false
                ObserverDiagnostics.setError(ObserverErrorCode.NETWORK_OBSERVER_INSTALL_FAILED)
            }
        } else {
            ShortsLog.w("DOCUMENT_START_SCRIPT not supported; fallback injection on page load")
            ObserverDiagnostics.documentStartSupported = false
            // 문서 시작 스크립트 미지원: 초기 요청을 놓칠 가능성이 있다.
            ObserverDiagnostics.missedInitialPossible = true
            ObserverDiagnostics.setError(ObserverErrorCode.INITIAL_REQUEST_MISSED)
        }
    }

    /** 페이지 로드 완료 후 스크립트를 주입한다. 문서 시작 스크립트가 없을 때만 사용한다. */
    fun injectScript(webView: WebView) {
        if (documentStartInjected) return
        runCatching {
            webView.evaluateJavascript(ShortsObserverScript.script, null)
        }.onFailure { error ->
            ShortsLog.w("Failed to inject observer script", error)
        }
    }

    /** 네이티브가 받은 JSON 메시지를 파싱해 전달한다. 형식이 틀린 메시지는 무시한다. */
    fun handleMessage(json: String) {
        // 메시지 최대 크기 제한: 초과분은 데이터로 처리하지 않는다.
        if (json.length > MAX_MESSAGE_SIZE) {
            ShortsLog.w("Observer message rejected: size exceeded")
            ObserverDiagnostics.setError(ObserverErrorCode.MESSAGE_SIZE_EXCEEDED)
            return
        }
        val message = ObserverMessage.parse(json)
        if (message == null) {
            // 허용 타입이 아니거나 형식 오류. 로그에는 본문 내용을 남기지 않는다.
            ShortsLog.w("Ignored invalid observer message")
            return
        }
        if (message is ObserverMessage.Heartbeat) {
            lastHeartbeatAtMs = message.ts
            lastHeartbeatRevision = message.revision
        }
        // WebView 진단 화면(O단계)용 진단 상태를 갱신한다.
        ObserverDiagnostics.record(message)
        ShortsLog.d("Observer message: ${message::class.simpleName} (seq=${message.seq})")
        onMessage(message)
    }

    /** 관찰기가 살아있는지 판단한다. */
    fun isObserverAlive(
        nowMs: Long = System.currentTimeMillis(),
        timeoutMs: Long = ObserverWatchdog.DEFAULT_TIMEOUT_MS,
    ): Boolean = ObserverWatchdog.isAlive(lastHeartbeatAtMs, nowMs, timeoutMs)

    /** 관찰기 중단 시 JS 관찰기를 재시작한다. */
    fun restartObserver(webView: WebView) {
        runCatching {
            webView.evaluateJavascript(
                "if (window.${ShortsObserverScript.RESTART_FUNCTION}) window.${ShortsObserverScript.RESTART_FUNCTION}();",
                null,
            )
        }.onFailure { error ->
            ShortsLog.w("Failed to restart observer", error)
        }
    }

    fun detach() {
        attachedWebView = null
    }

    companion object {
        /** 브리지 메시지 최대 크기 (문자). 네트워크 응답 본문은 보내지 않으므로 여유 있게 잡는다. */
        const val MAX_MESSAGE_SIZE = 256 * 1024

        /**
         * 관찰기 메시지를 허용하는 출처 규칙.
         * 유튜브 HTTPS만 허용하며, 그 외 출처(비 HTTPS 포함)의 메시지는 무시된다.
         */
        val ALLOWED_ORIGIN_RULES: Set<String> = setOf(
            "https://youtube.com",
            "https://*.youtube.com",
            "https://youtu.be",
            "https://*.youtu.be",
        )
    }
}
