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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.shortsmonitor.core.database.entity.BrowserProfileEntity
import com.shortsmonitor.core.notification.NotificationHelper
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
import com.shortsmonitor.core.observer.ObservationSettings
import com.shortsmonitor.core.observer.ObserverBridge
import com.shortsmonitor.core.observer.ObserverMessage
import com.shortsmonitor.core.observer.ObserverWatchdog
import com.shortsmonitor.core.profile.ProfileGenerator
import com.shortsmonitor.core.reset.ResetItem
import com.shortsmonitor.core.reset.SessionResetter
import com.shortsmonitor.core.webview.ShortsWebView
import com.shortsmonitor.core.webview.ShortsWebViewController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val YOUTUBE_SHORTS_URL = "https://m.youtube.com/shorts"

private const val YOUTUBE_HOME_URL = "https://m.youtube.com"

/** 네이티브 시간 기반 DOM 후보 안정화 확인 주기. */
private const val DOM_STABILIZE_CHECK_INTERVAL_MS = 2_000L

/** 관찰 중 화면의 상태 요약. */
private data class ObservingUiState(
    val shortsCount: Int = 0,
    val eventCount: Int = 0,
    val profileName: String? = null,
    val profiles: List<BrowserProfileEntity> = emptyList(),
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
    val settingsRepository = remember {
        (context.applicationContext as ShortsMonitorApplication).settingsRepository
    }

    // 관찰 설정(O단계): 메시지 처리마다 현재 값을 읽어 즉시 반영한다.
    var observationSettings by remember { mutableStateOf(ObservationSettings()) }
    LaunchedEffect(settingsRepository) {
        combine(
            settingsRepository.saveListSnapshots,
            settingsRepository.stabilizeCandidates,
            settingsRepository.saveMetadata,
            settingsRepository.saveThumbnails,
        ) { snapshots, stabilize, metadata, thumbnails ->
            ObservationSettings(snapshots, stabilize, metadata, thumbnails)
        }.collect { observationSettings = it }
    }
    // 알림 설정(O단계): 배너·시스템 알림·진동·오류/이벤트 알림 여부.
    var bannerEnabled by remember { mutableStateOf(true) }
    var systemNotificationsEnabled by remember { mutableStateOf(false) }
    var vibrationEnabled by remember { mutableStateOf(true) }
    var errorNotificationsEnabled by remember { mutableStateOf(true) }
    var eventNotificationsEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(settingsRepository) {
        combine(
            settingsRepository.inAppBanner,
            settingsRepository.systemNotifications,
            settingsRepository.vibration,
            settingsRepository.errorNotifications,
            settingsRepository.suspectedEventNotifications,
        ) { banner, system, vibration, errors, events ->
            bannerEnabled = banner
            systemNotificationsEnabled = system
            vibrationEnabled = vibration
            errorNotificationsEnabled = errors
            eventNotificationsEnabled = events
        }.collect { }
    }

    val recorder = remember {
        ObservationRecorder(
            observedShortDao = database.observedShortDao(),
            exposureEventDao = database.exposureEventDao(),
            listSnapshotDao = database.listSnapshotDao(),
            insertionEventDao = database.insertionEventDao(),
            settings = { observationSettings },
            // v5: 네트워크 시퀀스 분석 DAO.
            networkSequenceDao = database.networkSequenceDao(),
            networkSequenceItemDao = database.networkSequenceItemDao(),
            networkVideoRequestDao = database.networkVideoRequestDao(),
            sequenceLineageDao = database.sequenceLineageDao(),
            networkObserverStateDao = database.networkObserverStateDao(),
        )
    }

    // 관찰 일시 중지: 켜져 있으면 관찰기 메시지를 기록하지 않는다. WebView 재생은 유지된다.
    var paused by remember { mutableStateOf(false) }
    var panelExpanded by rememberSaveable { mutableStateOf(true) }
    // 닫은 알림의 이벤트 식별자. 닫아도 기록은 데이터베이스에 남는다.
    var dismissedEventId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showResetSheet by remember { mutableStateOf(false) }
    var showProfileSheet by remember { mutableStateOf(false) }
    // 초기화 옵션과 결과. 초기화 중에는 중복 실행을 차단한다.
    var resetKeepProfile by remember { mutableStateOf(true) }
    var resetOpenHome by remember { mutableStateOf(false) }
    var resetInProgress by remember { mutableStateOf(false) }
    var resetResult by remember { mutableStateOf<SessionResetter.ResetResult?>(null) }
    val sessionResetter = remember { SessionResetter() }

    val observerBridge = remember {
        ObserverBridge { message ->
            // 관찰 오류 알림(O단계 알림 설정): 관찰 오류 메시지 수신 시 시스템 알림을 전송한다.
            if (message is ObserverMessage.ObserverError) {
                NotificationHelper.notifyObserverError(
                    context = context,
                    text = message.message ?: message.code,
                    systemEnabled = systemNotificationsEnabled,
                    errorEnabled = errorNotificationsEnabled,
                    vibrate = vibrationEnabled,
                )
            }
            if (!paused) {
                ShortsLog.d("Observer message received: ${message::class.simpleName}")
                scope.launch { recorder.record(sessionId, message) }
            }
        }
    }
    controller.observerBridge = observerBridge

    // 프로필 변경·세션 초기화 시 WebView를 새로 생성하기 위한 재생성 키.
    var webViewRecreateKey by remember { mutableIntStateOf(0) }
    // 세션 초기화 후 새 WebView가 로드할 주소.
    var webViewStartUrl by remember { mutableStateOf(YOUTUBE_SHORTS_URL) }

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
                    profiles = profiles,
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

    // 의심 이벤트 시스템 알림(O단계 알림 설정): 새 확정 이벤트가 나타나면 한 번만 전송한다.
    var notifiedEventId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(uiState.latestEvent?.id) {
        val event = uiState.latestEvent ?: return@LaunchedEffect
        if (event.id != notifiedEventId) {
            notifiedEventId = event.id
            NotificationHelper.notifySuspectedEvent(
                context = context,
                title = context.getString(R.string.notification_event_title),
                text = context.getString(
                    R.string.notification_event_text,
                    uiState.latestEventVideo?.title ?: event.newVideoId,
                ),
                systemEnabled = systemNotificationsEnabled,
                eventEnabled = eventNotificationsEnabled,
                vibrate = vibrationEnabled,
            )
        }
    }

    // WebView 생성 시 적용할 활성 프로필을 컨트롤러에 전달한다.
    controller.activeProfile = uiState.profiles.maxByOrNull { it.lastUsedAt ?: 0L }

    val applyProfile: (BrowserProfileEntity) -> Unit = { profile ->
        scope.launch {
            database.browserProfileDao().updateLastUsed(profile.id, System.currentTimeMillis())
            // 현재 목록을 저장하고 탐지 기준을 교체한다 (직전/직후 목록은 비교하지 않음).
            recorder.recordProfileChange(sessionId, System.currentTimeMillis())
        }
        controller.activeProfile = profile
        // WebView 로딩 중지 → 기존 WebView 제거 → 새 WebView 생성 → 기존 주소 재로드.
        webViewRecreateKey++
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

    // 네이티브 시간 기반 DOM 후보 안정화: JavaScript가 같은 목록 키로 스냅샷을
    // 다시 보내지 않아도, 후보가 일정 시간 유지되면 마지막 목록으로 안정화를 재확인한다.
    LaunchedEffect(sessionId) {
        while (true) {
            delay(DOM_STABILIZE_CHECK_INTERVAL_MS)
            if (!paused) {
                recorder.stabilizeDomCandidates(sessionId)
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
            startUrl = webViewStartUrl,
            modifier = Modifier.fillMaxSize(),
            recreateKey = webViewRecreateKey,
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
            if (bannerEnabled && latestEvent != null && latestEvent.id != dismissedEventId) {
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
            onChangeProfile = { showProfileSheet = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )
    }

    if (showProfileSheet) {
        ProfilePickerSheet(
            profiles = uiState.profiles,
            onSelect = applyProfile,
            onDismiss = { showProfileSheet = false },
        )
    }

    if (showResetSheet) {
        ResetOptionsSheet(
            keepProfile = resetKeepProfile,
            openHome = resetOpenHome,
            inProgress = resetInProgress,
            onKeepProfileChange = { resetKeepProfile = it },
            onOpenHomeChange = { resetOpenHome = it },
            onConfirm = {
                if (!resetInProgress) {
                    showResetSheet = false
                    resetInProgress = true
                    scope.launch {
                        try {
                            // 1) 현재 목록 저장 + 초기화 이벤트 저장 (비교 기준 교체)
                            recorder.recordReset(sessionId, System.currentTimeMillis())
                            // 2) 새 프로필 생성 옵션
                            if (!resetKeepProfile) {
                                val template = ProfileGenerator.randomTemplate()
                                val generated = ProfileGenerator.generate(template)
                                val existing = database.browserProfileDao().observeAll().first()
                                val profile = BrowserProfileEntity(
                                    name = context.getString(
                                        R.string.profiles_name_format,
                                        context.getString(templateLabelRes(template)),
                                        existing.size + 1,
                                    ),
                                    templateType = template,
                                    userAgent = generated.userAgent,
                                    language = generated.language,
                                    timezone = generated.timezone,
                                    screenOverride = generated.screenOverride,
                                    hardwareOverride = generated.hardwareOverride,
                                    touchOverride = generated.touchOverride,
                                    createdAt = System.currentTimeMillis(),
                                )
                                val id = database.browserProfileDao().insert(profile)
                                database.browserProfileDao().updateLastUsed(
                                    id,
                                    System.currentTimeMillis(),
                                )
                                controller.activeProfile = profile.copy(id = id)
                            }
                            // 3) WebView 로딩 중지 → 사이트 데이터 정리 → 새 WebView 생성
                            controller.stopLoading()
                            val result = sessionResetter.reset(controller.webViewForReset())
                            // 4) 새 WebView가 로드할 주소를 옵션에 따라 정한다.
                            webViewStartUrl = if (resetOpenHome) YOUTUBE_HOME_URL else YOUTUBE_SHORTS_URL
                            // 5) 재생성 키 증가 → 기존 WebView 폐기·새 WebView 생성·주소 재로드
                            webViewRecreateKey++
                            resetResult = result
                        } finally {
                            resetInProgress = false
                        }
                    }
                }
            },
            onDismiss = { showResetSheet = false },
        )
    }

    resetResult?.let { result ->
        ResetResultDialog(
            result = result,
            onDismiss = { resetResult = null },
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
    onChangeProfile: () -> Unit,
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
                        text = stringResource(R.string.observe_panel_change_profile),
                        onClick = onChangeProfile,
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

/** 프로필 템플릿 이름 리소스. 초기화 시 새 프로필 생성(L단계·M단계)에 사용한다. */
private fun templateLabelRes(template: com.shortsmonitor.core.model.ProfileTemplateType): Int =
    when (template) {
        com.shortsmonitor.core.model.ProfileTemplateType.SMALL_ANDROID -> R.string.profile_template_small
        com.shortsmonitor.core.model.ProfileTemplateType.ANDROID -> R.string.profile_template_android
        com.shortsmonitor.core.model.ProfileTemplateType.LARGE_ANDROID -> R.string.profile_template_large
        com.shortsmonitor.core.model.ProfileTemplateType.ANDROID_TABLET -> R.string.profile_template_tablet
    }

/**
 * 세션 및 사이트 데이터 초기화 옵션 시트 (M단계).
 * 삭제 대상(쿠키·로그인 상태·웹 저장소·캐시·탐색 기록·폼 데이터)을 안내하고
 * 프로필 유지/새 프로필 생성, 초기화 후 쇼츠/홈 열기를 선택한다.
 * 초기화 진행 중에는 중복 실행을 차단한다.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ResetOptionsSheet(
    keepProfile: Boolean,
    openHome: Boolean,
    inProgress: Boolean,
    onKeepProfileChange: (Boolean) -> Unit,
    onOpenHomeChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.reset_session_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.reset_session_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.reset_option_profile),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResetOptionChip(
                    label = stringResource(R.string.reset_option_keep_profile),
                    selected = keepProfile,
                    onClick = { onKeepProfileChange(true) },
                    modifier = Modifier.weight(1f),
                )
                ResetOptionChip(
                    label = stringResource(R.string.reset_option_new_profile),
                    selected = !keepProfile,
                    onClick = { onKeepProfileChange(false) },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.reset_option_after),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResetOptionChip(
                    label = stringResource(R.string.reset_option_shorts),
                    selected = !openHome,
                    onClick = { onOpenHomeChange(false) },
                    modifier = Modifier.weight(1f),
                )
                ResetOptionChip(
                    label = stringResource(R.string.reset_option_home),
                    selected = openHome,
                    onClick = { onOpenHomeChange(true) },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(20.dp))
            PrimaryActionButton(
                text = stringResource(
                    if (inProgress) R.string.reset_in_progress else R.string.reset_confirm,
                ),
                onClick = onConfirm,
                enabled = !inProgress,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                enabled = !inProgress,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ResetOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
    )
}

/** 초기화 결과 다이얼로그. 실패 항목이 있으면 명시한다. */
@Composable
private fun ResetResultDialog(
    result: SessionResetter.ResetResult,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(text = stringResource(R.string.reset_result_title))
        },
        text = {
            Column {
                Text(
                    text = stringResource(
                        if (result.ok) {
                            R.string.reset_result_success
                        } else if (result.skippedWhileRunning) {
                            R.string.reset_result_in_progress
                        } else {
                            R.string.reset_result_partial
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (result.failed.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.reset_failed_items),
                        style = MaterialTheme.typography.labelLarge,
                        color = com.shortsmonitor.core.design.StatusError,
                    )
                    result.failed.forEach { item ->
                        Text(
                            text = "• " + stringResource(resetItemLabel(item)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_confirm))
            }
        },
    )
}

private fun resetItemLabel(item: ResetItem): Int = when (item) {
    ResetItem.COOKIES -> R.string.reset_item_cookies
    ResetItem.WEB_STORAGE -> R.string.reset_item_web_storage
    ResetItem.CACHE -> R.string.reset_item_cache
    ResetItem.HISTORY -> R.string.reset_item_history
    ResetItem.FORM_DATA -> R.string.reset_item_form_data
}

/**
 * 관찰 중 프로필 변경 선택 시트 (L단계).
 * 프로필을 선택하면 WebView가 새로 생성되고 현재 주소를 다시 로드한다.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ProfilePickerSheet(
    profiles: List<BrowserProfileEntity>,
    onSelect: (BrowserProfileEntity) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.observe_profile_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.observe_profile_sheet_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            if (profiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.observe_profile_sheet_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(profile) },
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = profile.screenOverride ?: profile.language,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(timestamp))
