package com.shortsmonitor.core.webview

import android.net.Uri

/**
 * WebView 최상위 탐색 허용 정책.
 * 유튜브 관련 HTTPS 주소로 제한하고, 그 외 주소(비 HTTPS 포함)는
 * WebView 밖에서 처리할 외부 탐색으로 분류한다.
 */
object WebViewNavigationPolicy {

    private val ALLOWED_HOSTS = setOf("youtube.com", "youtu.be")

    /** 최상위 탐색을 WebView 내부에서 허용할지 여부. */
    fun isAllowedTopLevelUrl(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (uri.scheme != "https") return false
        val host = uri.host ?: return false
        return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }
}
