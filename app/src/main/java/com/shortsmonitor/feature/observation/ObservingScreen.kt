package com.shortsmonitor.feature.observation

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shortsmonitor.app.R
import com.shortsmonitor.app.ShortsMonitorApplication
import com.shortsmonitor.core.database.entity.InsertionEventEntity
import com.shortsmonitor.core.database.entity.ObservedShortEntity
import com.shortsmonitor.core.design.StatusActive
import com.shortsmonitor.core.design.StatusPending
import com.shortsmonitor.core.design.StatusSuspected
import com.shortsmonitor.core.design.components.ConfirmationSheet
import com.shortsmonitor.core.design.components.OutlinedActionButton
import com.shortsmonitor.core.design.components.PrimaryActionButton
import com.shortsmonitor.core.design.components.StatusChip
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.model.SessionEndReason
import com.shortsmonitor.core.model.SessionStatus
import com.shortsmonitor.core.observer.ObservationRecorder
import com.shortsmonitor.core.observer.ObserverBridge
import com.shortsmonitor.core.observer.ObserverWatchdog
import com.shortsmonitor.core.webview.ShortsWebView
import com.shortsmonitor.core.webview.ShortsWebViewController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val YOUTUBE_SHORTS_URL = "https://m.youtube.com/shorts"

/** 관찰 중 화면의 상태 요약. */
private data class ObservingUiState(
    val shortsCount: Int = 0,
    val eventCount: Int = 0,
    val profileName: String? = null,
    val activeVideo: ObservedShortEntity? = null,
    val activeOrder: Int? = null,
    val lastChangedAt: Long? = null,
    val latestEvent: InsertionEventEntity? = null,
    val latestEventVideo: ObservedShortEntity? = null,
)

/**
 * 쇼츠 관찰 중 화면 (I단계).
 *
 * WebView 위에 네이티브 Compose UI를 겹쳐 표시한다. WebView 내부 HTML로
 * 제어 패널을 만들지 않는다.
 *
 * - 상단 상태 영역: 관찰 상태·현재 프로필·관찰 쇼츠 수·의심 이벤트 수·관찰 종료
 * - 중간 삽입 알림 카드: 신규 영상 제목·채널·감지 시각, 자세히 보기·나중에 확인
 * - 하단 활성 세션 패널: 현재 영상·순서·마지막 변화 시각, 로그 열기·일시 중지·초기화
 *
 * 알림을 닫아도 기록은 데이터베이스에 유지된다. 시스템 뒤로 가기는 패널부터 닫는다.
 */
@Composable
fun ObservingScreen(
    sessionId: Long,
    onBack: () -> Unit,
    onOpenEvent: (Long) -> Unit,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember {
        (context.applicationContext as ShortsMonitorApplication).database
    }
    val controller = remember { ShortsWebViewController(context) }
    val recorder = remember {
        ObservationRecorder(
            observedShortDao = database.observedShortDao(),
            exposureEventDao = database.exposureEventDao(),
            listSnapshotDao = database.listSnapshotDao(),
            insertionEventDao = database.insertionEventDao(),
        )
    }

    // 관찰 일시 중지: 켜져 있으면 관찰기 메시지를 기록하지 않는다. WebView 재생은 유지된다.
    var paused by remember { mutableStateOf(false) }
    var panelExpanded by rememberSaveable { mutableStateOf(true) }
    // 닫은 알림의 이벤트 식별자. 닫아도 기록은 데이터베이스에 남는다.
    var dismissedEventId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showResetSheet by remember { mutableStateOf(false) }

    val observerBridge = remember {
        ObserverBridge { message ->
            if (!paused) {
                ShortsLog.d("Observer message received: ${message::class.simpleName}")
                scope.launch { recorder.record(sessionId, message) }
            }
        }
    }
    controller.observerBridge = observerBridge

    val uiState by produceState<ObservingUiState>(
        initialValue = ObservingUiState(),
        key1 = sessionId,
    ) {
        try {
            combine(
                database.observedShortDao().observeBySession(sessionId),
                database.insertionEventDao().observeBySession(sessionId),
                database.exposureEventDao().observeBySession(sessionId),
                database.listSnapshotDao().observeBySession(sessionId),
                database.browserProfileDao().observeAll(),
            ) { shorts, events, exposures, snapshots, profiles ->
                val shortsById = shorts.associateBy { it.videoId }
                // 아직 종료되지 않은 노출이 현재 활성 영상이다.
                val activeExposure = exposures.lastOrNull { it.exposedUntil == null }
                val latestEvent = events.maxByOrNull { it.detectedAt }
                ObservingUiState(
                    shortsCount = shorts.size,
                    eventCount = events.size,
                    profileName = profiles.maxByOrNull { it.lastUsedAt ?: 0L }?.name,
                    activeVideo = activeExposure?.let { shortsById[it.videoId] },
                    activeOrder = activeExposure?.exposureOrder,
                    lastChangedAt = snapshots.lastOrNull()?.createdAt,
                    latestEvent = latestEvent,
                    latestEventVideo = latestEvent?.let { shortsById[it.newVideoId] },
                )
            }.collect { value = it }
        } catch (e: Exception) {
            ShortsLog.e("Observing: failed to load session state", e)
        }
    }

    // 관찰기 하트비트 감시: 일정 시간 하트비트가 없으면 중단으로 보고 재시작한다.
    LaunchedEffect(controller) {
        while (true) {
            delay(ObserverWatchdog.CHECK_INTERVAL_MS)
            if (controller.hasWebView && !observerBridge.isObserverAlive()) {
                ShortsLog.w("Observer heartbeat lost; restarting observer")
                controller.restartObserver()
            }
        }
    }

    // 시스템 뒤로 가기: 펼쳐진 패널부터 닫고, WebView 히스토리로 뒤로 간 뒤, 없으면 화면을 닫는다.
    BackHandler {
        when {
            panelExpanded -> panelExpanded = false
            !controller.goBack() -> onBack()
        }
    }

    val endObservation: () -> Unit = {
        scope.launch {
            database.observationSessionDao().updateStatus(
                id = sessionId,
                status = SessionStatus.COMPLETED,
                endedAt = System.currentTimeMillis(),
                endReason = SessionEndReason.USER_FINISHED,
            )
        }
        onBack()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        ShortsWebView(
            controller = controller,
            startUrl = YOUTUBE_SHORTS_URL,
            modifier = Modifier.fillMaxSize(),
            onExternalNavigation = { url ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }.onFailure { error ->
                    ShortsLog.w("Failed to open external URL: $url", error)
                }
            },
        )

        // 상단 상태 영역 + 중간 삽입 알림 카드
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        ) {
            ObservingStatusBar(
                paused = paused,
                profileName = uiState.profileName
                    ?: stringResource(R.string.home_current_profile_default),
                shortsCount = uiState.shortsCount,
                eventCount = uiState.eventCount,
                onEnd = endObservation,
            )

            val latestEvent = uiState.latestEvent
            if (latestEvent != null && latestEvent.id != dismissedEventId) {
                InsertionAlertCard(
                    title = uiState.latestEventVideo?.title
                        ?: latestEvent.newVideoId,
                    channel = uiState.latestEventVideo?.channelName.orEmpty(),
                    detectedAt = latestEvent.detectedAt,
                    onDetail = {
                        dismissedEventId = latestEvent.id
                        onOpenEvent(latestEvent.id)
                    },
                    onLater = { dismissedEventId = latestEvent.id },
                )
            }
        }

        // 하단 활성 세션 패널 (접기/펼치기 지원)
        ActiveSessionPanel(
            expanded = panelExpanded,
            paused = paused,
            activeVideo = uiState.activeVideo,
            activeOrder = uiState.activeOrder,
            lastChangedAt = uiState.lastChangedAt,
            maxHeight = maxHeight * 0.45f,
            onToggle = { panelExpanded = !panelExpanded },
            onOpenLog = onOpenLog,
            onPauseToggle = { paused = !paused },
            onReset = { showResetSheet = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )
    }

    if (showResetSheet) {
        ConfirmationSheet(
            visible = true,
            title = stringResource(R.string.reset_session_title),
            message = stringResource(R.string.reset_session_message),
            confirmLabel = stringResource(R.string.reset_confirm),
            dismissLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = { showResetSheet = false },
            onDismiss = { showResetSheet = false },
        )
    }
}

/** 상단 상태 영역. 관찰 상태·현재 프로필·관찰 쇼츠 수·의심 이벤트 수·관찰 종료를 표시한다. */
@Composable
private fun ObservingStatusBar(
    paused: Boolean,
    profileName: String,
    shortsCount: Int,
    eventCount: Int,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    label = stringResource(
                        if (paused) R.string.observe_status_paused else R.string.home_status_observing,
                    ),
                    statusColor = if (paused) StatusPending else StatusActive,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.home_current_profile_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = profileName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedActionButton(
                    text = stringResource(R.string.home_end_session),
                    onClick = onEnd,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricValue(
                    label = stringResource(R.string.observe_metric_shorts),
                    value = shortsCount.toString(),
                )
                MetricValue(
                    label = stringResource(R.string.observe_metric_events),
                    value = eventCount.toString(),
                )
            }
        }
    }
}

/** 상단 바 안의 소형 지표. */
@Composable
private fun MetricValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** 중간 삽입 의심 알림 카드. 닫아도 기록은 데이터베이스에 유지된다. */
@Composable
private fun InsertionAlertCard(
    title: String,
    channel: String,
    detectedAt: Long,
    onDetail: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = StatusSuspected.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, StatusSuspected.copy(alpha = 0.5f)),
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = StatusSuspected,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.observe_alert_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = StatusSuspected,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = channel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatTimestamp(detectedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedActionButton(
                    text = stringResource(R.string.observe_alert_later),
                    onClick = onLater,
                )
                PrimaryActionButton(
                    text = stringResource(R.string.observe_alert_detail),
                    onClick = onDetail,
                )
            }
        }
    }
}

/**
 * 하단 활성 세션 패널.
 * 펼치면 현재 영상·순서·마지막 변화 시각과 액션(로그 열기·일시 중지·초기화)을 표시하고,
 * 접으면 영상 제목만 남긴다. 최대 높이는 화면 크기에 비례해 조정된다.
 */
@Composable
private fun ActiveSessionPanel(
    expanded: Boolean,
    paused: Boolean,
    activeVideo: ObservedShortEntity?,
    activeOrder: Int?,
    lastChangedAt: Long?,
    maxHeight: androidx.compose.ui.unit.Dp,
    onToggle: () -> Unit,
    onOpenLog: () -> Unit,
    onPauseToggle: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeVideo?.title ?: stringResource(R.string.observe_no_active_video),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (activeVideo != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = activeVideo.channelName.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.Filled.KeyboardArrowUp
                    },
                    contentDescription = stringResource(
                        if (expanded) R.string.observe_panel_collapse else R.string.observe_panel_expand,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    MetricValue(
                        label = stringResource(R.string.observe_panel_video_id),
                        value = activeVideo?.videoId ?: "-",
                        modifier = Modifier.weight(1f),
                    )
                    MetricValue(
                        label = stringResource(R.string.observe_panel_order),
                        value = activeOrder?.toString() ?: "-",
                    )
                    MetricValue(
                        label = stringResource(R.string.observe_panel_last_change),
                        value = lastChangedAt?.let(::formatTimestamp) ?: "-",
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedActionButton(
                        text = stringResource(R.string.observe_panel_open_log),
                        onClick = onOpenLog,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedActionButton(
                        text = stringResource(
                            if (paused) R.string.observe_panel_resume else R.string.observe_panel_pause,
                        ),
                        onClick = onPauseToggle,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedActionButton(
                        text = stringResource(R.string.observe_panel_reset),
                        onClick = onReset,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(timestamp))
