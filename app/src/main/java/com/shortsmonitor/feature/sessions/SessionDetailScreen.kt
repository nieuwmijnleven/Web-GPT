package com.shortsmonitor.feature.sessions

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
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shortsmonitor.app.BuildConfig
import com.shortsmonitor.app.R
import com.shortsmonitor.app.ShortsMonitorApplication
import com.shortsmonitor.core.database.entity.ExposureEventEntity
import com.shortsmonitor.core.database.entity.InsertionEventEntity
import com.shortsmonitor.core.database.entity.ListSnapshotEntity
import com.shortsmonitor.core.database.entity.ObservedShortEntity
import com.shortsmonitor.core.database.entity.ObservationSessionEntity
import com.shortsmonitor.core.design.StatusNormal
import com.shortsmonitor.core.design.components.ConfirmationSheet
import com.shortsmonitor.core.design.components.EmptyState
import com.shortsmonitor.core.design.components.ErrorState
import com.shortsmonitor.core.design.components.LoadingState
import com.shortsmonitor.core.design.components.MetricCard
import com.shortsmonitor.core.design.components.OutlinedActionButton
import com.shortsmonitor.core.design.components.ShortsMonitorTopBar
import com.shortsmonitor.core.design.components.StatusChip
import com.shortsmonitor.core.export.ExportFileWriter
import com.shortsmonitor.core.export.SessionExportBuilder
import com.shortsmonitor.core.export.SessionExportLoader
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.model.SessionStatus
import com.shortsmonitor.core.model.ShortsError
import com.shortsmonitor.core.model.SnapshotChangeReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 내보내기 형식 (N단계). */
private enum class ExportFormat { JSON, CSV }

/** 세션 상세 화면의 상태. */
private data class SessionDetailUiState(
    val session: ObservationSessionEntity? = null,
    val shorts: List<ObservedShortEntity> = emptyList(),
    val exposures: List<ExposureEventEntity> = emptyList(),
    val snapshots: List<ListSnapshotEntity> = emptyList(),
    val events: List<InsertionEventEntity> = emptyList(),
)

private sealed interface SessionDetailLoadState {
    data object Loading : SessionDetailLoadState

    data object Error : SessionDetailLoadState

    data class Content(val state: SessionDetailUiState) : SessionDetailLoadState
}

/**
 * 세션 상세 화면 (J단계).
 * 세션 요약·실제 노출 순서·현재 목록 스냅샷·의심 이벤트·프로필/초기화 이력을 표시하고,
 * 삭제 전 확인 절차를 거친다. 삭제된 세션의 하위 기록은 FK CASCADE로 함께 정리된다.
 */
@Composable
fun SessionDetailScreen(
    sessionId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val database = remember {
        (context.applicationContext as ShortsMonitorApplication).database
    }
    val scope = rememberCoroutineScope()
    var retryKey by remember { mutableIntStateOf(0) }
    var showDeleteSheet by remember { mutableStateOf(false) }

    // N단계 내보내기 상태.
    var showExportSheet by remember { mutableStateOf(false) }
    var exportInProgress by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<ShortsError.Export?>(null) }
    var pendingExportUri by remember { mutableStateOf<CompletableDeferred<android.net.Uri?>?>(null) }
    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        pendingExportUri?.complete(uri)
        pendingExportUri = null
    }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        pendingExportUri?.complete(uri)
        pendingExportUri = null
    }

    suspend fun pickExportUri(launcher: androidx.activity.result.ActivityResultLauncher<String>, name: String): android.net.Uri? {
        val deferred = CompletableDeferred<android.net.Uri?>()
        pendingExportUri = deferred
        launcher.launch(name)
        return deferred.await()
    }

    fun runExport(format: ExportFormat) {
        showExportSheet = false
        if (exportInProgress) return
        exportInProgress = true
        scope.launch {
            try {
                val data = SessionExportLoader.loadForSession(database, sessionId) ?: return@launch
                when (format) {
                    ExportFormat.JSON -> {
                        val content = SessionExportBuilder.buildJson(data, BuildConfig.VERSION_NAME)
                        val uri = pickExportUri(jsonLauncher, SessionExportBuilder.jsonFileName(sessionId))
                        if (uri != null) {
                            exportError = ExportFileWriter.write(context, uri, content)
                        }
                    }
                    ExportFormat.CSV -> {
                        val files = SessionExportBuilder.buildCsvFiles(data)
                        for (file in files) {
                            val uri = pickExportUri(csvLauncher, file.fileName) ?: break
                            exportError = ExportFileWriter.write(context, uri, file.content)
                            if (exportError != null) break
                        }
                    }
                }
            } catch (e: Exception) {
                ShortsLog.e("Session export failed", e)
                exportError = ShortsError.Export(e.message ?: "Export failed", e)
            } finally {
                exportInProgress = false
            }
        }
    }

    val loadState by produceState<SessionDetailLoadState>(
        initialValue = SessionDetailLoadState.Loading,
        key1 = retryKey,
    ) {
        try {
            combine(
                database.observationSessionDao().observeById(sessionId),
                database.observedShortDao().observeBySession(sessionId),
                database.exposureEventDao().observeBySession(sessionId),
                database.listSnapshotDao().observeBySession(sessionId),
                database.insertionEventDao().observeBySession(sessionId),
            ) { session, shorts, exposures, snapshots, events ->
                SessionDetailUiState(
                    session = session,
                    shorts = shorts,
                    exposures = exposures,
                    snapshots = snapshots,
                    events = events,
                )
            }.collect { ui ->
                value = if (ui.session == null) {
                    SessionDetailLoadState.Error
                } else {
                    SessionDetailLoadState.Content(ui)
                }
            }
        } catch (e: Exception) {
            ShortsLog.e("Session detail: failed to load", e)
            value = SessionDetailLoadState.Error
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        when (val state = loadState) {
            SessionDetailLoadState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
            SessionDetailLoadState.Error -> ErrorState(
                message = stringResource(R.string.session_detail_error_message),
                onRetry = { retryKey++ },
                modifier = Modifier.fillMaxSize(),
            )
            is SessionDetailLoadState.Content -> {
                val ui = state.state
                val session = ui.session ?: return@Column
                ShortsMonitorTopBar(
                    title = session.name,
                    onBack = onBack,
                )
                SessionDetailContent(
                    session = session,
                    ui = ui,
                    onExport = { showExportSheet = true },
                    exportInProgress = exportInProgress,
                    onDelete = { showDeleteSheet = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showExportSheet) {
        ExportFormatSheet(
            exportInProgress = exportInProgress,
            onSelectJson = { runExport(ExportFormat.JSON) },
            onSelectCsv = { runExport(ExportFormat.CSV) },
            onDismiss = { showExportSheet = false },
        )
    }

    exportError?.let { error ->
        AlertDialog(
            onDismissRequest = { exportError = null },
            title = { Text(text = stringResource(R.string.export_error_title)) },
            text = { Text(text = error.message ?: stringResource(R.string.export_error_message)) },
            confirmButton = {
                TextButton(onClick = { exportError = null }) {
                    Text(text = stringResource(R.string.action_confirm))
                }
            },
        )
    }

    if (showDeleteSheet) {
        ConfirmationSheet(
            visible = true,
            title = stringResource(R.string.session_delete_title),
            message = stringResource(R.string.session_delete_message),
            confirmLabel = stringResource(R.string.session_delete_confirm),
            dismissLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = {
                showDeleteSheet = false
                scope.launch {
                    database.observationSessionDao().deleteById(sessionId)
                    onBack()
                }
            },
            onDismiss = { showDeleteSheet = false },
        )
    }
}

@Composable
private fun SessionDetailContent(
    session: ObservationSessionEntity,
    ui: SessionDetailUiState,
    onExport: () -> Unit,
    exportInProgress: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shortsById = ui.shorts.associateBy { it.videoId }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SessionSummaryCard(
                session = session,
                ui = ui,
            )
        }

        item {
            SectionHeader(stringResource(R.string.session_detail_exposures))
        }
        if (ui.exposures.isEmpty()) {
            item {
                EmptyState(title = stringResource(R.string.session_detail_no_exposures))
            }
        } else {
            items(ui.exposures, key = { it.id }) { exposure ->
                ExposureRow(exposure = exposure, title = shortsById[exposure.videoId]?.title)
            }
        }

        item {
            SectionHeader(stringResource(R.string.session_detail_snapshot))
        }
        val latestSnapshot = ui.snapshots.lastOrNull()
        if (latestSnapshot == null) {
            item {
                EmptyState(title = stringResource(R.string.session_detail_no_snapshot))
            }
        } else {
            item {
                SnapshotCard(
                    snapshot = latestSnapshot,
                    shortsById = shortsById,
                )
            }
        }

        item {
            SectionHeader(stringResource(R.string.session_detail_events))
        }
        if (ui.events.isEmpty()) {
            item {
                EmptyState(title = stringResource(R.string.session_detail_no_events))
            }
        } else {
            items(ui.events, key = { it.id }) { event ->
                EventRow(event = event, title = shortsById[event.newVideoId]?.title)
            }
        }

        item {
            SectionHeader(stringResource(R.string.session_detail_profile_history))
        }
        val profileChanges = ui.snapshots.filter { it.changeReason == SnapshotChangeReason.PROFILE_CHANGED }
        if (profileChanges.isEmpty()) {
            item {
                EmptyState(title = stringResource(R.string.session_detail_no_profile_changes))
            }
        } else {
            items(profileChanges, key = { it.id }) { snapshot ->
                HistoryRow(
                    label = stringResource(R.string.session_detail_profile_changed),
                    time = snapshot.createdAt,
                )
            }
        }

        item {
            SectionHeader(stringResource(R.string.session_detail_reset_history))
        }
        val resets = ui.snapshots.filter { it.changeReason == SnapshotChangeReason.SESSION_RESET }
        if (resets.isEmpty()) {
            item {
                EmptyState(title = stringResource(R.string.session_detail_no_resets))
            }
        } else {
            items(resets, key = { it.id }) { snapshot ->
                HistoryRow(
                    label = stringResource(R.string.session_detail_reset_performed),
                    time = snapshot.createdAt,
                )
            }
        }

        item {
            SectionHeader(stringResource(R.string.session_detail_errors))
        }
        // 분석 오류는 아직 저장 구조가 없으므로 안내 문구만 표시한다.
        item {
            EmptyState(title = stringResource(R.string.session_detail_no_errors))
        }

        item {
            OutlinedActionButton(
                text = if (exportInProgress) {
                    stringResource(R.string.export_in_progress)
                } else {
                    stringResource(R.string.session_detail_export)
                },
                onClick = onExport,
                enabled = !exportInProgress,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            OutlinedActionButton(
                text = stringResource(R.string.session_delete),
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SessionSummaryCard(
    session: ObservationSessionEntity,
    ui: SessionDetailUiState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    label = stringResource(
                        when (session.status) {
                            SessionStatus.ACTIVE -> R.string.home_status_observing
                            SessionStatus.COMPLETED -> R.string.status_completed
                            SessionStatus.INTERRUPTED -> R.string.status_interrupted
                            SessionStatus.ERROR -> R.string.status_error
                        },
                    ),
                    statusColor = when (session.status) {
                        SessionStatus.ACTIVE -> com.shortsmonitor.core.design.StatusActive
                        SessionStatus.COMPLETED -> StatusNormal
                        SessionStatus.INTERRUPTED -> com.shortsmonitor.core.design.StatusPending
                        SessionStatus.ERROR -> com.shortsmonitor.core.design.StatusError
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(
                    label = stringResource(R.string.session_summary_shorts),
                    value = ui.shorts.size.toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    label = stringResource(R.string.session_summary_exposures),
                    value = ui.exposures.size.toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    label = stringResource(R.string.session_summary_events),
                    value = ui.events.size.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            SummaryLine(
                label = stringResource(R.string.session_summary_started),
                value = formatTimestamp(session.startedAt),
            )
            session.endedAt?.let {
                Spacer(Modifier.height(4.dp))
                SummaryLine(
                    label = stringResource(R.string.session_summary_ended),
                    value = formatTimestamp(it),
                )
            }
            Spacer(Modifier.height(4.dp))
            SummaryLine(
                label = stringResource(R.string.session_summary_duration),
                value = formatDuration(session),
            )
        }
    }
}

@Composable
private fun SummaryLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

@Composable
private fun ExposureRow(
    exposure: ExposureEventEntity,
    title: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = exposure.exposureOrder.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.padding(start = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title ?: exposure.videoId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = exposure.videoId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SnapshotCard(
    snapshot: ListSnapshotEntity,
    shortsById: Map<String, ObservedShortEntity>,
    modifier: Modifier = Modifier,
) {
    val videoIds = runCatching {
        val array = JSONArray(snapshot.videoIdsJson)
        buildList {
            for (i in 0 until array.length()) add(array.getString(i))
        }
    }.getOrDefault(emptyList())

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = formatTimestamp(snapshot.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (videoIds.isEmpty()) {
                Text(
                    text = stringResource(R.string.session_detail_no_snapshot_items),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                videoIds.forEachIndexed { index, videoId ->
                    Text(
                        text = "${index + 1}. ${shortsById[videoId]?.title ?: videoId}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun EventRow(
    event: InsertionEventEntity,
    title: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(
                label = stringResource(R.string.observe_alert_title),
                statusColor = com.shortsmonitor.core.design.StatusSuspected,
            )
            Spacer(Modifier.padding(start = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title ?: event.newVideoId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatTimestamp(event.detectedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    label: String,
    time: Long,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatTimestamp(time),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 내보내기 형식 선택 시트 (N단계). */
@Composable
private fun ExportFormatSheet(
    exportInProgress: Boolean,
    onSelectJson: () -> Unit,
    onSelectCsv: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(text = stringResource(R.string.export_format_title)) },
        text = {
            Column {
                TextButton(
                    onClick = onSelectJson,
                    enabled = !exportInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.export_format_json))
                }
                Text(
                    text = stringResource(R.string.export_format_json_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = onSelectCsv,
                    enabled = !exportInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.export_format_csv))
                }
                Text(
                    text = stringResource(R.string.export_format_csv_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !exportInProgress) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun formatDuration(session: ObservationSessionEntity): String {
    val end = session.endedAt ?: return "-"
    val minutes = ((end - session.startedAt) / 60_000L).toInt()
    if (minutes < 60) return minutes.toString() + "분"
    return "${minutes / 60}시간 ${minutes % 60}분"
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(timestamp))
