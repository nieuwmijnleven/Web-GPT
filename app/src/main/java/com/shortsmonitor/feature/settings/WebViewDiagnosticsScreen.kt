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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewFeature
import com.shortsmonitor.app.BuildConfig
import com.shortsmonitor.app.R
import com.shortsmonitor.app.ShortsMonitorApplication
import com.shortsmonitor.core.design.components.OutlinedActionButton
import com.shortsmonitor.core.design.components.ShortsMonitorTopBar
import com.shortsmonitor.core.model.SequenceLineageRelation
import com.shortsmonitor.core.model.SequenceParseStatus
import com.shortsmonitor.core.observer.ObserverDiagnostics
import com.shortsmonitor.core.export.ExportFileWriter
import com.shortsmonitor.core.export.NetworkDiagnosticsExporter
import com.shortsmonitor.core.logging.ShortsLog
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    val database = remember {
        (context.applicationContext as ShortsMonitorApplication).database
    }
    var copied by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    var pendingExportSessionId by remember { mutableStateOf<Long?>(null) }
    // 개발 빌드 진단 모드: 저장 위치 선택 후 내보내기.
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null && pendingExportSessionId != null) {
            scope.launch {
                exportResult = try {
                    val content = NetworkDiagnosticsExporter.export(database, pendingExportSessionId!!)
                    val error = ExportFileWriter.write(context, uri, content)
                    pendingExportSessionId = null
                    if (error == null) {
                        context.getString(R.string.diagnostics_export_done)
                    } else {
                        context.getString(R.string.diagnostics_export_failed)
                    }
                } catch (e: Exception) {
                    ShortsLog.e("Diagnostics export failed", e)
                    context.getString(R.string.diagnostics_export_failed)
                }
            }
        }
    }

    val profiles by database.browserProfileDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // 활성(최근) 세션의 네트워크 관찰 상태·이벤트 집계 (진단용).
    var dbState by remember { mutableStateOf("") }
    var pendingCount by remember { mutableStateOf(0L) }
    var confirmedCount by remember { mutableStateOf(0L) }
    LaunchedEffect(database) {
        val state = database.networkObserverStateDao().getLatest()
        val sessionId = state?.sessionId
        dbState = state?.let { st ->
            buildList {
                add("installed=" + (st.installedAt ?: 0L))
                add("firstRequest=" + (st.firstRequestAt ?: 0L))
                add("restricted=" + st.restricted)
            }.joinToString(", ")
        } ?: ""
        if (sessionId != null) {
            val counts = database.insertionEventDao().countByVerdict(sessionId)
            pendingCount = counts.firstOrNull { it.verdict == "CANDIDATE" }?.cnt ?: 0L
            confirmedCount = counts.firstOrNull { it.verdict == "CONFIRMED" }?.cnt ?: 0L
        }
    }

    val items = remember(profiles, dbState, pendingCount, confirmedCount) {
        buildDiagnostics(
            context = context,
            activeProfileName = profiles.maxByOrNull { it.lastUsedAt ?: 0L }?.name,
            dbState = dbState,
            pendingCount = pendingCount,
            confirmedCount = confirmedCount,
        )
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
                // 개발 빌드 전용 진단 모드: 실제 검증용 네트워크 진단 파일 내보내기.
                if (BuildConfig.DEBUG) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedActionButton(
                        text = stringResource(R.string.diagnostics_export_debug),
                        onClick = {
                            exportResult = null
                            scope.launch {
                                val state = database.networkObserverStateDao().getLatest()
                                if (state == null) {
                                    exportResult = context.getString(R.string.diagnostics_export_no_session)
                                } else {
                                    pendingExportSessionId = state.sessionId
                                    exportLauncher.launch(NetworkDiagnosticsExporter.FILE_NAME)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    exportResult?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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

private fun buildDiagnostics(
    context: Context,
    activeProfileName: String?,
    dbState: String,
    pendingCount: Long,
    confirmedCount: Long,
): List<DiagnosticsItem> {
    val unknown = context.getString(R.string.diagnostics_unknown)
    val none = context.getString(R.string.diagnostics_none)
    val yes = context.getString(R.string.diagnostics_yes)
    val no = context.getString(R.string.diagnostics_no)
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

    fun time(value: Long): String = if (value <= 0L) {
        none
    } else {
        SimpleDateFormat("M/d HH:mm:ss", Locale.getDefault()).format(Date(value))
    }

    val networkStatus = when {
        ObserverDiagnostics.networkObserverInstalledAtMs <= 0L ->
            context.getString(R.string.diagnostics_observer_none)
        ObserverDiagnostics.missedInitialPossible ->
            context.getString(R.string.network_observer_limited)
        else -> context.getString(R.string.network_observer_ready)
    }
    val parseStatus = when (ObserverDiagnostics.lastSequenceParseStatus) {
        SequenceParseStatus.PARSED -> context.getString(R.string.parse_status_parsed)
        SequenceParseStatus.PARTIAL -> context.getString(R.string.parse_status_partial)
        SequenceParseStatus.FAILED -> context.getString(R.string.parse_status_failed)
        SequenceParseStatus.UNSUPPORTED -> context.getString(R.string.parse_status_unsupported)
        SequenceParseStatus.NONE -> context.getString(R.string.parse_status_none)
    }
    val lineage = when (ObserverDiagnostics.currentLineage) {
        SequenceLineageRelation.SAME_FLOW -> context.getString(R.string.lineage_same_flow)
        SequenceLineageRelation.NEW_CONTEXT -> context.getString(R.string.lineage_new_context)
        SequenceLineageRelation.UNKNOWN -> context.getString(R.string.lineage_unknown)
        SequenceLineageRelation.NONE -> context.getString(R.string.lineage_none)
    }
    val mismatch = (ObserverDiagnostics.lastDomVideoCount - ObserverDiagnostics.lastSequenceVideoCount)
        .coerceAtLeast(0)

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
            value = time(lastHeartbeat),
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_doc_start),
            value = if (ObserverDiagnostics.documentStartSupported) yes else no,
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_network_observer),
            value = networkStatus,
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_network_installed_at),
            value = time(ObserverDiagnostics.networkObserverInstalledAtMs),
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_first_sequence_request),
            value = time(ObserverDiagnostics.firstSequenceRequestAtMs),
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_last_sequence_request),
            value = time(ObserverDiagnostics.lastSequenceRequestAtMs),
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_last_sequence_response),
            value = time(ObserverDiagnostics.lastSequenceResponseAtMs),
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_last_sequence_videos),
            value = ObserverDiagnostics.lastSequenceVideoCount.toString(),
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_sequence_parse_status),
            value = parseStatus,
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_current_lineage),
            value = lineage,
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_initial_missed),
            value = if (ObserverDiagnostics.missedInitialPossible) yes else no,
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_dom_videos),
            value = ObserverDiagnostics.lastDomVideoCount.toString(),
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_network_videos),
            value = ObserverDiagnostics.lastSequenceVideoCount.toString(),
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_dom_network_mismatch),
            value = mismatch.toString(),
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_active_video),
            value = if (ObserverDiagnostics.lastDomListHash.isBlank()) {
                none
            } else {
                ObserverDiagnostics.lastNetworkRequestVideoId.ifBlank { "(DOM)" }
            },
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_recent_request_video),
            value = ObserverDiagnostics.lastNetworkRequestVideoId.ifBlank { none },
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_pending_candidates),
            value = pendingCount.toString(),
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_confirmed_events),
            value = confirmedCount.toString(),
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_parse_warnings),
            value = ObserverDiagnostics.recentParseWarnings.joinToString(", ").ifBlank { none },
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_selector_stats),
            value = ObserverDiagnostics.lastSelectorStats ?: none,
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_last_dom_error),
            value = ObserverDiagnostics.lastDomError ?: none,
        ),
        DiagnosticsItem(
            label = context.getString(R.string.diagnostics_restricted),
            value = dbState.ifBlank { none },
        ),
    )
}

