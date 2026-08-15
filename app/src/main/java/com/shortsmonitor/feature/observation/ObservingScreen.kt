package com.shortsmonitor.feature.observation

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.webview.ShortsWebView
import com.shortsmonitor.core.webview.ShortsWebViewController

private const val YOUTUBE_SHORTS_URL = "https://m.youtube.com/shorts"

/**
 * E단계 최소 쇼츠 관찰 화면.
 * WebView에서 유튜브 모바일 웹 쇼츠를 표시한다.
 * 관찰 중 UI(상태 영역·알림 카드·활성 세션 패널)는 I단계에서 구현한다.
 */
@Composable
fun ObservingScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller = remember { ShortsWebViewController(context) }

    // WebView 히스토리가 있으면 WebView 안에서 뒤로 가고, 없으면 화면을 닫는다.
    BackHandler {
        if (!controller.goBack()) onBack()
    }

    ShortsWebView(
        controller = controller,
        startUrl = YOUTUBE_SHORTS_URL,
        modifier = modifier.fillMaxSize(),
        onExternalNavigation = { url ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.onFailure { error ->
                ShortsLog.w("Failed to open external URL: $url", error)
            }
        },
    )
}
