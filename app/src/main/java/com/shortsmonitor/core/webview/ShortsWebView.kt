package com.shortsmonitor.core.webview

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shortsmonitor.app.R
import com.shortsmonitor.core.design.components.OutlinedActionButton

/**
 * WebView를 AndroidView로 감싼 shorts monitor 공통 컨테이너.
 * WebView 인스턴스는 Compose 재구성마다 다시 만들지 않으며,
 * 컨트롤러([ShortsWebViewController])가 생명주기를 관리한다.
 * 회전 시 WebView 상태는 rememberSaveable에 보존되어 복원된다.
 */
@Composable
fun ShortsWebView(
    controller: ShortsWebViewController,
    startUrl: String,
    modifier: Modifier = Modifier,
    onExternalNavigation: ((String) -> Unit)? = null,
) {
    val savedState = rememberSaveable { mutableStateOf<Bundle?>(null) }

    controller.onExternalNavigation = onExternalNavigation

    DisposableEffect(controller) {
        onDispose {
            val bundle = savedState.value ?: Bundle()
            controller.saveState(bundle)
            savedState.value = bundle
            controller.destroy()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                controller.createWebView(startUrl, savedState.value)
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (controller.rendererGone) {
            RendererGoneOverlay(
                onRecover = { controller.recoverFromRendererGone() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun RendererGoneOverlay(
    onRecover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.webview_crash_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.webview_crash_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedActionButton(
                text = stringResource(R.string.webview_crash_recover),
                onClick = onRecover,
            )
        }
    }
}
