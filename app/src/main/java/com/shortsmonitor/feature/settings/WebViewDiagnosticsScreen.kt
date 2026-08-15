package com.shortsmonitor.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewFeature
import com.shortsmonitor.app.R
import com.shortsmonitor.app.ShortsMonitorApplication
import com.shortsmonitor.core.design.components.OutlinedActionButton
import com.shortsmonitor.core.design.components.ShortsMonitorTopBar
import com.shortsmonitor.core.observer.ObserverDiagnostics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** WebView 진단 항목. */
private data class DiagnosticsItem(
    val label: String,
    val value: String,
)

/**
 * WebView 진단 화면 (O단계).
 *
 * WebView 제공 패키지·버전·지원 기능·현재 User-Agent·현재 프로필·관찰기 상태·
 * 마지막 상태 확인 시각·마지막 DOM 오류를 표시하고, 진단 정보를 클립보드로 복사할 수 있다.
 * 민감한 쿠키나 인증값은 포함되지 않는다.
 */
@Composable
fun WebViewDiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val database = remember {
        (context.applicationContext as ShortsMonitorApplication).database
    }
    var copied by remember { mutableStateOf(false) }

    val profiles by database.browserProfileDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val items = remember(profiles) {
        buildDiagnostics(context, profiles.maxByOrNull { it.lastUsedAt ?: 0L }?.name)
    }

    Column(modifier = modifier.fillMaxSize()) {
        ShortsMonitorTopBar(
            title = stringResource(R.string.diagnostics_title),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items.size) { index ->
                DiagnosticsRow(label = items[index].label, value = items[index].value)
            }
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedActionButton(
                    text = stringResource(R.string.diagnostics_copy),
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("shorts monitor diagnostics", items.joinToString("\n") { "${it.label}: ${it.value}" }),
                        )
                        copied = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (copied) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.diagnostics_copied),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun DiagnosticsRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun buildDiagnostics(context: Context, activeProfileName: String?): List<DiagnosticsItem> {
    val unknown = context.getString(R.string.diagnostics_unknown)
    val none = context.getString(R.string.diagnostics_none)
    val webViewPackage = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()

    val supportedFeatures = listOf(
        WebViewFeature.USER_AGENT_METADATA,
        WebViewFeature.WEB_MESSAGE_LISTENER,
        WebViewFeature.DOCUMENT_START_SCRIPT,
    ).map { feature ->
        val supported = runCatching { WebViewFeature.isFeatureSupported(feature) }.getOrDefault(false)
        "$feature=" + if (supported) "yes" else "no"
    }.joinToString(", ")

    val defaultUserAgent = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault(unknown)

    val lastHeartbeat = ObserverDiagnostics.lastHeartbeatAtMs
    val observerStatus = when {
        ObserverDiagnostics.isAlive() -> context.getString(R.string.diagnostics_observer_alive)
        lastHeartbeat <= 0L -> context.getString(R.string.diagnostics_observer_none)
        else -> context.getString(R.string.diagnostics_observer_stopped)
    }

    return listOf(
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_webview_package),
            value = webViewPackage?.packageName ?: unknown,
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_webview_version),
            value = runCatching {
                context.packageManager.getPackageInfo(webViewPackage?.packageName ?: "", 0).versionName
            }.getOrNull() ?: unknown,
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_supported_features),
            value = supportedFeatures,
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_current_ua),
            value = defaultUserAgent,
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_active_profile),
            value = activeProfileName ?: none,
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_observer_status),
            value = observerStatus,
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_last_heartbeat),
            value = if (lastHeartbeat <= 0L) {
                none
            } else {
                SimpleDateFormat("M/d HH:mm:ss", Locale.getDefault()).format(Date(lastHeartbeat))
            },
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_last_dom_error),
            value = ObserverDiagnostics.lastDomError ?: none,
        ),
    )
}
