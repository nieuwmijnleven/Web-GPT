package com.shortsmonitor.core.observer

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.shortsmonitor.core.logging.ShortsLog

/**
 * JavaScript 관찰기와 네이티브 사이의 통신 브리지.
 *
 * - [attach]는 페이지를 열기 전에 `addWebMessageListener`로 통신 객체를 등록하고
 *   허용 출처([ALLOWED_ORIGIN_RULES])를 제한한다. 잘못된 출처에서 보낸 메시지는
 *   WebView 계층에서 차단된다.
 * - 지원되는 환경에서는 문서 시작 시점 스크립트([ShortsObserverScript.script])를
 *   등록하고, 지원되지 않으면 페이지 로드 완료 후 주입한다.
 * - 하트비트로 관찰기 중단을 감지하고([ObserverWatchdog]) [restartObserver]로 재시작한다.
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
            }
        } else {
            ShortsLog.w("WEB_MESSAGE_LISTENER not supported; observer messages disabled")
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    ShortsObserverScript.script,
                    ALLOWED_ORIGIN_RULES,
                )
                documentStartInjected = true
            }.onFailure { error ->
                ShortsLog.w("Failed to register document start script", error)
            }
        } else {
            ShortsLog.w("DOCUMENT_START_SCRIPT not supported; fallback injection on page load")
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
        val message = ObserverMessage.parse(json)
        if (message == null) {
            ShortsLog.w("Ignored malformed observer message")
            return
        }
        if (message is ObserverMessage.Heartbeat) {
            lastHeartbeatAtMs = message.ts
            lastHeartbeatRevision = message.revision
        }
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
