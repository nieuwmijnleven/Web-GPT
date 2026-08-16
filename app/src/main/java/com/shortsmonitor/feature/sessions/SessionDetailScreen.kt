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
import com.shortsmonitor.core.database.entity.NetworkObserverStateEntity
import com.shortsmonitor.core.database.entity.NetworkSequenceEntity
import com.shortsmonitor.core.database.entity.NetworkSequenceItemEntity
import com.shortsmonitor.core.database.entity.NetworkVideoRequestEntity
import com.shortsmonitor.core.database.entity.ObservedShortEntity
import com.shortsmonitor.core.database.entity.ObservationSessionEntity
import com.shortsmonitor.core.database.entity.SequenceLineageEntity
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
import com.shortsmonitor.core.model.AutoVerdict
import com.shortsmonitor.core.model.SessionStatus
import com.shortsmonitor.core.model.ShortsError
import com.shortsmonitor.core.model.SnapshotChangeReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    // v5: 네트워크 시퀀스 분석.
    val networkSequences: List<NetworkSequenceEntity> = emptyList(),
    val sequenceItems: Map<Long, List<NetworkSequenceItemEntity>> = emptyMap(),
    val videoRequests: List<NetworkVideoRequestEntity> = emptyList(),
    val lineages: List<SequenceLineageEntity> = emptyList(),
    val observerState: NetworkObserverStateEntity? = null,
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
                listOf(
                    database.observationSessionDao().observeById(sessionId),
                    database.observedShortDao().observeBySession(sessionId),
                    database.exposureEventDao().observeBySession(sessionId),
                    database.listSnapshotDao().observeBySession(sessionId),
                    database.insertionEventDao().observeBySession(sessionId),
                    database.networkSequenceDao().observeBySession(sessionId),
                    database.networkVideoRequestDao().observeBySession(sessionId),
                    database.sequenceLineageDao().observeBySession(sessionId),
                    database.networkObserverStateDao().observeBySession(sessionId),
                ),
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                SessionDetailUiState(
                    session = values[0] as ObservationSessionEntity?,
                    shorts = values[1] as List<ObservedShortEntity>,
                    exposures = values[2] as List<ExposureEventEntity>,
                    snapshots = values[3] as List<ListSnapshotEntity>,
                    events = values[4] as List<InsertionEventEntity>,
                    networkSequences = values[5] as List<NetworkSequenceEntity>,
                    sequenceItems = (values[5] as List<NetworkSequenceEntity>).associate {
                        it.id to database.networkSequenceItemDao().observeBySequence(it.id).first()
                    },
                    videoRequests = values[6] as List<NetworkVideoRequestEntity>,
                    lineages = values[7] as List<SequenceLineageEntity>,
                    observerState = values[8] as NetworkObserverStateEntity?,
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
    val parseIssueInitialMissed = stringResource(R.string.session_detail_initial_missed)
    val parseIssueRestricted = stringResource(R.string.session_detail_restricted)

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
            SectionHeader(stringResource(R.string.session_detail_network_sequences))
        }
        item {
            Text(
                text = stringResource(R.string.session_detail_network_sequences_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ui.networkSequences.isEmpty()) {
            item {
                EmptyState(title = stringResource(R.string.session_detail_no_network_sequences))
            }
        } else {
            items(ui.networkSequences, key = { it.id }) { sequence ->
                NetworkSequenceCard(
                    sequence = sequence,
                    items = ui.sequenceItems[sequence.id].orEmpty(),
                    shortsById = shortsById,
                    lineage = ui.lineages.firstOrNull { it.toSequenceId == sequence.id },
                )
            }
        }

        item {
            SectionHeader(stringResource(R.string.session_detail_network_requests))
        }
        item {
            Text(
                text = stringResource(R.string.session_detail_network_requests_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ui.videoRequests.isEmpty()) {
            item {
                EmptyState(title = stringResource(R.string.session_detail_no_network_requests))
            }
        } else {
            items(ui.videoRequests, key = { it.id }) { request ->
                VideoRequestRow(request = request, title = request.videoId?.let { shortsById[it]?.title })
            }
        }

        item {
            SectionHeader(stringResource(R.string.session_detail_observer_state))
        }
        val observerState = ui.observerState
        if (observerState == null) {
            item {
                EmptyState(title = stringResource(R.string.session_detail_no_observer_state))
            }
        } else {
            item {
                ObserverStateCard(state = observerState)
            }
        }

        item {
            SectionHeader(stringResource(R.string.session_detail_parse_failed))
        }
        val parseIssues = buildList {
            ui.networkSequences.filter { it.parseStatus != com.shortsmonitor.core.model.SequenceParseStatus.PARSED }.forEach {
                add("${formatTimestamp(it.createdAt)}: ${it.parseStatus.name} ${it.warningsJson.orEmpty()}")
            }
            if (observerState?.missedInitialPossible == true) {
                add(parseIssueInitialMissed)
            }
            if (observerState?.restricted == true) {
                add(parseIssueRestricted)
            }
        }
        if (parseIssues.isEmpty()) {
            item {
                EmptyState(title = stringResource(R.string.session_detail_no_parse_issues))
            }
        } else {
            items(parseIssues) { issue ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Text(
                        text = issue,
                        style = MaterialTheme.typography.bodySmall,
                        color = com.shortsmonitor.core.design.StatusError,
                        modifier = Modifier.padding(12.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 삽입 후보 / 확정 삽입 / 판정 보류 / 기타를 구분해 표시한다.
        val candidates = ui.events.filter { it.autoVerdict == AutoVerdict.CANDIDATE }
        val confirmed = ui.events.filter { it.autoVerdict == AutoVerdict.CONFIRMED }
        val pending = ui.events.filter { it.autoVerdict == AutoVerdict.UNKNOWN }
        val other = ui.events.filter {
            it.autoVerdict != AutoVerdict.CANDIDATE &&
                it.autoVerdict != AutoVerdict.CONFIRMED &&
                it.autoVerdict != AutoVerdict.UNKNOWN
        }

        item {
            SectionHeader(stringResource(R.string.session_detail_events_candidates))
        }
        if (candidates.isEmpty()) {
            item {
                EmptyState(title = stringResource(R.string.session_detail_no_candidates))
            }
        } else {
            items(candidates, key = { it.id }) { event ->
                EventRow(event = event, title = shortsById[event.newVideoId]?.title)
            }
        }

        item {
            SectionHeader(stringResource(R.string.session_detail_events_confirmed))
        }
        if (confirmed.isEmpty()) {
            item {
                EmptyState(title = stringResource(R.string.session_detail_no_confirmed))
            }
        } else {
            items(confirmed, key = { it.id }) { event ->
                EventRow(event = event, title = shortsById[event.newVideoId]?.title)
            }
        }

        item {
            SectionHeader(stringResource(R.string.session_detail_events_pending))
        }
        if (pending.isEmpty()) {
            item {
                EmptyState(title = stringResource(R.string.session_detail_no_pending))
            }
        } else {
            items(pending, key = { it.id }) { event ->
                EventRow(event = event, title = shortsById[event.newVideoId]?.title)
            }
        }

        item {
            SectionHeader(stringResource(R.string.session_detail_events_other))
        }
        if (other.isEmpty()) {
            item {
                EmptyState(title = stringResource(R.string.session_detail_no_other_events))
            }
        } else {
            items(other, key = { it.id }) { event ->
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

/** 서버가 전달한 시퀀스 카드. 영상 순서·파싱 상태·계보를 표시한다. */
@Composable
private fun NetworkSequenceCard(
    sequence: NetworkSequenceEntity,
    items: List<NetworkSequenceItemEntity>,
    shortsById: Map<String, ObservedShortEntity>,
    lineage: SequenceLineageEntity?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTimestamp(sequence.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                StatusChip(
                    label = lineage?.relation?.name ?: sequence.parseStatus.name,
                    statusColor = if (lineage?.relation == com.shortsmonitor.core.model.SequenceLineageRelation.SAME_FLOW) {
                        com.shortsmonitor.core.design.StatusNormal
                    } else {
                        com.shortsmonitor.core.design.StatusPending
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.session_detail_sequence_meta,
                    sequence.parseStatus.name,
                    sequence.entryContext.name,
                    items.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (items.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                items.forEach { item ->
                    val title = item.videoId?.let { shortsById[it]?.title } ?: "(비영상: ${item.nonVideoKind ?: "?"})"
                    Text(
                        text = "${item.position + 1}. $title",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 실제 영상 요청 순서 한 줄. 요청 종류·예상 위치와 대조 결과를 표시한다. */
@Composable
private fun VideoRequestRow(
    request: NetworkVideoRequestEntity,
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
                text = request.requestOrder.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.padding(start = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title ?: request.videoId ?: "-",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${request.requestKind.name} · 예상 위치 ${request.expectedPosition?.plus(1) ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 네트워크 관찰 상태 카드. 설치·누락·제한 상태를 표시한다. */
@Composable
private fun ObserverStateCard(
    state: NetworkObserverStateEntity,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (state.restricted) {
                Text(
                    text = stringResource(R.string.session_detail_restricted),
                    style = MaterialTheme.typography.labelLarge,
                    color = com.shortsmonitor.core.design.StatusError,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (state.missedInitialPossible) {
                Text(
                    text = stringResource(R.string.session_detail_initial_missed),
                    style = MaterialTheme.typography.bodySmall,
                    color = com.shortsmonitor.core.design.StatusSuspected,
                )
                Spacer(Modifier.height(4.dp))
            }
            SummaryLine(
                label = stringResource(R.string.diagnostics_doc_start),
                value = if (state.documentStartSupported) "예" else "아니오",
            )
            SummaryLine(
                label = stringResource(R.string.diagnostics_last_sequence_videos),
                value = state.lastSequenceVideoCount.toString(),
            )
            SummaryLine(
                label = stringResource(R.string.diagnostics_sequence_parse_status),
                value = state.lastParseStatus.name,
            )
            if (!state.warningsJson.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.session_detail_warnings) + ": " + state.warningsJson,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatDuration(session: ObservationSessionEntity): String {
    val end = session.endedAt ?: return "-"
    val minutes = ((end - session.startedAt) / 60_000L).toInt()
    if (minutes < 60) return minutes.toString() + "분"
    return "${minutes / 60}시간 ${minutes % 60}분"
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(timestamp))
