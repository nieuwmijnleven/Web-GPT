package com.shortsmonitor.core.webview

import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shortsmonitor.app.BuildConfig
import com.shortsmonitor.core.logging.ShortsLog

/**
 * WebView 생명주기와 설정을 관리하는 전용 컨트롤러.
 * Compose 재구성과 무관하게 WebView 인스턴스를 유지하고,
 * 회전 시 상태 저장·복원, 렌더러 종료 복구, 뒤로 가기를 담당한다.
 *
 * WebView는 완전한 브라우저와 동일하지 않으므로 일반 Chrome의 모든 특성을
 * 재현한다고 전제하지 않는다.
 */
class ShortsWebViewController(private val context: Context) {

    /** 렌더러 프로세스 종료 여부. true면 복구 UI를 표시한다. */
    var rendererGone by mutableStateOf(false)
        private set

    /** 유튜브 외 주소의 최상위 탐색 시 호출된다. */
    var onExternalNavigation: ((String) -> Unit)? = null

    private var webView: WebView? = null

    /**
     * WebView를 생성하고 초기 설정을 적용한다.
     * [savedState]가 있으면 상태를 복원하고, 없으면 [startUrl]을 로드한다.
     */
    fun createWebView(startUrl: String, savedState: Bundle?): WebView {
        webView?.destroy()
        val created = WebView(context)
        applySettings(created.settings)
        created.setWebViewClient(createWebViewClient())
        created.webChromeClient = createWebChromeClient()
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        // restoreState는 복원된 WebBackForwardList를 반환하며, 복원 실패 시 null이다.
        val restored = savedState != null && created.restoreState(savedState) != null
        if (!restored) created.loadUrl(startUrl)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(created, true)
        }
        webView = created
        return created
    }

    fun loadUrl(url: String) {
        webView?.loadUrl(url)
    }

    fun reload() {
        webView?.reload()
    }

    /** 렌더러 종료 후 페이지를 다시 불러와 복구한다. */
    fun recoverFromRendererGone() {
        rendererGone = false
        webView?.reload()
    }

    /** WebView 히스토리로 뒤로 가고, 처리했으면 true를 반환한다. */
    fun goBack(): Boolean {
        val wv = webView ?: return false
        return if (wv.canGoBack()) {
            wv.goBack()
            true
        } else {
            false
        }
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    /** 회전 등 화면 종료 시 WebView 상태를 저장한다. */
    fun saveState(state: Bundle) {
        webView?.saveState(state)
    }

    fun destroy() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
    }

    private fun applySettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // 안전하지 않은 외부 주소는 WebViewNavigationPolicy에서 차단한다.
    }

    private fun createWebViewClient(): WebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            return if (WebViewNavigationPolicy.isAllowedTopLevelUrl(url)) {
                false
            } else {
                ShortsLog.i("External top-level navigation blocked in WebView: $url")
                onExternalNavigation?.invoke(url)
                true
            }
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            ShortsLog.w("WebView renderer process gone (crash=${detail.didCrash()})")
            rendererGone = true
            return true
        }
    }

    private fun createWebChromeClient(): WebChromeClient = object : WebChromeClient() {
        // 미디어 재생 등 WebChromeClient 필요 기능을 위해 최소 구현을 유지한다.
    }
}
