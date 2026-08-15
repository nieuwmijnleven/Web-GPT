package com.shortsmonitor.core.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebViewNavigationPolicyTest {

    @Test
    fun allowsYouTubeHttpsUrls() {
        assertTrue(WebViewNavigationPolicy.isAllowedTopLevelUrl("https://www.youtube.com/"))
        assertTrue(WebViewNavigationPolicy.isAllowedTopLevelUrl("https://m.youtube.com/shorts"))
        assertTrue(WebViewNavigationPolicy.isAllowedTopLevelUrl("https://music.youtube.com/"))
        assertTrue(WebViewNavigationPolicy.isAllowedTopLevelUrl("https://youtu.be/abc123"))
    }

    @Test
    fun blocksHttpEvenForYouTube() {
        assertFalse(WebViewNavigationPolicy.isAllowedTopLevelUrl("http://www.youtube.com/"))
        assertFalse(WebViewNavigationPolicy.isAllowedTopLevelUrl("http://m.youtube.com/shorts"))
    }

    @Test
    fun blocksExternalHosts() {
        assertFalse(WebViewNavigationPolicy.isAllowedTopLevelUrl("https://www.google.com/"))
        assertFalse(WebViewNavigationPolicy.isAllowedTopLevelUrl("https://example.com/"))
        assertFalse(WebViewNavigationPolicy.isAllowedTopLevelUrl("https://youtube.com.evil.com/"))
    }

    @Test
    fun blocksNonHttpSchemes() {
        assertFalse(WebViewNavigationPolicy.isAllowedTopLevelUrl("file:///etc/passwd"))
        assertFalse(WebViewNavigationPolicy.isAllowedTopLevelUrl("javascript:alert(1)"))
        assertFalse(WebViewNavigationPolicy.isAllowedTopLevelUrl(""))
    }
}
