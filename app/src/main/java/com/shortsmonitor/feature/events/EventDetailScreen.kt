package com.shortsmonitor.feature.events

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.shortsmonitor.core.database.entity.InsertionEventEntity
import com.shortsmonitor.core.database.entity.ObservedShortEntity
import com.shortsmonitor.core.design.StatusError
import com.shortsmonitor.core.design.StatusNormal
import com.shortsmonitor.core.design.StatusPending
import com.shortsmonitor.core.design.StatusSuspected
import com.shortsmonitor.core.design.StatusUserConfirmed
import com.shortsmonitor.core.design.components.EmptyState
import com.shortsmonitor.core.design.components.ErrorState
import com.shortsmonitor.core.design.components.LoadingState
import com.shortsmonitor.core.design.components.ShortsMonitorTopBar
import com.shortsmonitor.core.design.components.StatusChip
import com.shortsmonitor.core.design.components.WarningCard
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.model.AutoVerdict
import com.shortsmonitor.core.model.InsertionEvidence
import com.shortsmonitor.core.model.UserVerdict
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface EventDetailLoadState {
    data object Loading : EventDetailLoadState

    data object Error : EventDetailLoadState

    data class Content(
        val event: InsertionEventEntity,
        val shortsById: Map<String, ObservedShortEntity>,
        val beforeVideoIds: List<String>,
        val afterVideoIds: List<String>,
        val profileName: String?,
    ) : EventDetailLoadState
}

/**
 * 의심 이벤트 상세 화면 (K단계).
 *
 * - 상단: 경고 카드와 대상 영상 정보 (제목·채널·주소·발견 시각·발견 위치·사용 프로필)
 * - 목록 비교: 변경 전후 스냅샷을 나란히 비교하고 신규 항목을 경고색으로 강조
 * - 판정 근거: [InsertionEvidence] 8개 조건을 체크 형태로 표시
 * - 사용자 입력: 의심 유지·정상 변화·오탐·판단 보류와 메모 (자동 저장)
 *
 * 원본 자동 판정과 사용자 판정은 분리해 표시한다. 서버 동기화나 영구 감사 로그가
 * 없으므로 '글로벌 무결성 지표' 같은 표현은 사용하지 않는다.
 */
@Composable
fun EventDetailScreen(
    eventId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val database = remember {
        (context.applicationContext as ShortsMonitorApplication).database
    }
    val scope = rememberCoroutineScope()
    var retryKey by remember { mutableIntStateOf(0) }
    // 메모 자동 저장: 최초 로드 값을 초기값으로 쓰고, 이후 입력만 저장 대상으로 본다.
    var memoText by rememberSaveable { mutableStateOf("") }
    var memoInitialized by remember { mutableStateOf(false) }
    var memoSaved by remember { mutableStateOf(true) }

    val loadState by produceState<EventDetailLoadState>(
        initialValue = EventDetailLoadState.Loading,
        key1 = retryKey,
    ) {
        try {
            database.insertionEventDao().observeById(eventId).collect { event ->
                if (event == null) {
                    value = EventDetailLoadState.Error
                    return@collect
                }
                val shorts = database.observedShortDao().observeBySession(event.sessionId).first()
                val before = event.beforeSnapshotId?.let {
                    database.listSnapshotDao().observeById(it).first()
                }
                val after = event.afterSnapshotId?.let {
                    database.listSnapshotDao().observeById(it).first()
                }
                val profiles = database.browserProfileDao().observeAll().first()
                value = EventDetailLoadState.Content(
                    event = event,
                    shortsById = shorts.associateBy { it.videoId },
                    beforeVideoIds = before?.let { parseVideoIds(it.videoIdsJson) }.orEmpty(),
                    afterVideoIds = after?.let { parseVideoIds(it.videoIdsJson) }.orEmpty(),
                    profileName = profiles.maxByOrNull { it.lastUsedAt ?: 0L }?.name,
                )
            }
        } catch (e: Exception) {
            ShortsLog.e("Event detail: failed to load", e)
            value = EventDetailLoadState.Error
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        when (val state = loadState) {
            EventDetailLoadState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
            EventDetailLoadState.Error -> ErrorState(
                message = stringResource(R.string.event_detail_error_message),
                onRetry = { retryKey++ },
                modifier = Modifier.fillMaxSize(),
            )
            is EventDetailLoadState.Content -> {
                val content = state
                ShortsMonitorTopBar(
                    title = stringResource(R.string.event_detail_title),
                    onBack = onBack,
                )

                // 메모 초기값을 한 번만 적용한다 (이후 DB 갱신이 입력을 덮어쓰지 않도록).
                LaunchedEffect(content.event.userMemo) {
                    if (!memoInitialized) {
                        memoText = content.event.userMemo.orEmpty()
                        memoInitialized = true
                        memoSaved = true
                    }
                }
                // 입력 후 600ms 지연 저장 (자동 저장).
                LaunchedEffect(memoText) {
                    if (!memoInitialized) return@LaunchedEffect
                    memoSaved = false
                    delay(600)
                    database.insertionEventDao().updateUserMemo(content.event.id, memoText.trim().ifBlank { null })
                    memoSaved = true
                }

                EventDetailContent(
                    content = content,
                    memoText = memoText,
                    onMemoChange = { memoText = it },
                    memoSaved = memoSaved,
                    onVerdict = { verdict ->
                        scope.launch {
                            database.insertionEventDao().updateUserVerdict(content.event.id, verdict)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun EventDetailContent(
    content: EventDetailLoadState.Content,
    memoText: String,
    onMemoChange: (String) -> Unit,
    memoSaved: Boolean,
    onVerdict: (UserVerdict) -> Unit,
    modifier: Modifier = Modifier,
) {
    val event = content.event
    val newShort = content.shortsById[event.newVideoId]
    val position = content.afterVideoIds.indexOf(event.newVideoId).takeIf { it >= 0 }?.plus(1)
    val evidence = InsertionEvidence.fromJson(event.evidenceJson)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            WarningCard(
                title = stringResource(R.string.observe_alert_title),
                message = newShort?.title ?: event.newVideoId,
            )
        }

        item {
            SectionHeader(stringResource(R.string.event_detail_video_info))
        }
        item {
            VideoInfoCard(
                event = event,
                title = newShort?.title,
                channel = newShort?.channelName,
                url = newShort?.videoUrl,
                position = position,
                profileName = content.profileName,
            )
        }

        item {
            SectionHeader(stringResource(R.string.event_detail_list_comparison))
        }
        item {
            ListComparisonCard(
                beforeVideoIds = content.beforeVideoIds,
                afterVideoIds = content.afterVideoIds,
                newVideoId = event.newVideoId,
                shortsById = content.shortsById,
            )
        }

        item {
            SectionHeader(stringResource(R.string.event_detail_evidence))
        }
        item {
            EvidenceCard(evidence = evidence)
        }

        item {
            SectionHeader(stringResource(R.string.event_detail_auto_verdict))
        }
        item {
            AutoVerdictCard(autoVerdict = event.autoVerdict)
        }

        item {
            SectionHeader(stringResource(R.string.event_detail_user_verdict))
        }
        item {
            VerdictSelector(
                selected = event.userVerdict,
                onSelect = onVerdict,
            )
        }

        item {
            SectionHeader(stringResource(R.string.event_detail_memo_label))
        }
        item {
            OutlinedTextField(
                value = memoText,
                onValueChange = onMemoChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(text = stringResource(R.string.event_detail_memo_hint))
                },
                minLines = 2,
                maxLines = 5,
            )
        }
        item {
            Text(
                text = stringResource(
                    if (memoSaved) R.string.event_detail_memo_saved else R.string.event_detail_memo_saving,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Spacer(Modifier.height(8.dp))
        }
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

/** 대상 영상 정보 카드. 영상 제목·채널·주소·발견 시각·발견 위치·사용 프로필을 표시한다. */
@Composable
private fun VideoInfoCard(
    event: InsertionEventEntity,
    title: String?,
    channel: String?,
    url: String?,
    position: Int?,
    profileName: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            InfoRow(
                label = stringResource(R.string.event_detail_title_label),
                value = title ?: event.newVideoId,
            )
            InfoRow(
                label = stringResource(R.string.event_detail_channel_label),
                value = channel ?: "-",
            )
            InfoRow(
                label = stringResource(R.string.event_detail_url_label),
                value = url ?: "-",
            )
            InfoRow(
                label = stringResource(R.string.event_detail_detected_at),
                value = formatTimestamp(event.detectedAt),
            )
            InfoRow(
                label = stringResource(R.string.event_detail_position),
                value = position?.toString() ?: "-",
            )
            InfoRow(
                label = stringResource(R.string.event_detail_profile),
                value = profileName ?: "-",
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 변경 전후 목록 비교 카드.
 * 이전 목록(A, B)과 현재 목록(A, X, B)을 나란히 표시하고,
 * 신규 항목 X는 경고색으로 강조하며 앞뒤 기존 항목과 함께 그룹으로 보여준다.
 */
@Composable
private fun ListComparisonCard(
    beforeVideoIds: List<String>,
    afterVideoIds: List<String>,
    newVideoId: String,
    shortsById: Map<String, ObservedShortEntity>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row {
                ComparisonColumn(
                    title = stringResource(R.string.event_detail_before_list),
                    videoIds = beforeVideoIds,
                    shortsById = shortsById,
                    highlightId = null,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                ComparisonColumn(
                    title = stringResource(R.string.event_detail_after_list),
                    videoIds = afterVideoIds,
                    shortsById = shortsById,
                    highlightId = newVideoId,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ComparisonColumn(
    title: String,
    videoIds: List<String>,
    shortsById: Map<String, ObservedShortEntity>,
    highlightId: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (videoIds.isEmpty()) {
            Text(
                text = "-",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            videoIds.forEachIndexed { index, videoId ->
                val isNew = videoId == highlightId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    shape = MaterialTheme.shapes.small,
                    color = if (isNew) {
                        StatusSuspected.copy(alpha = 0.14f)
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    },
                    border = if (isNew) {
                        BorderStroke(1.dp, StatusSuspected.copy(alpha = 0.5f))
                    } else {
                        null
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isNew) {
                                StatusSuspected
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = shortsById[videoId]?.title ?: videoId,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isNew) {
                                StatusSuspected
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 판정 근거 카드. [InsertionEvidence]의 8개 조건을 충족/불충족으로 표시한다. */
@Composable
private fun EvidenceCard(
    evidence: InsertionEvidence,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            EvidenceRow(
                label = stringResource(R.string.evidence_not_in_previous_list),
                passed = evidence.notInPreviousList,
            )
            EvidenceRow(
                label = stringResource(R.string.evidence_appeared_at_middle),
                passed = evidence.appearedAtMiddle,
            )
            EvidenceRow(
                label = stringResource(R.string.evidence_front_back_maintained),
                passed = evidence.frontBackMaintained,
            )
            EvidenceRow(
                label = stringResource(R.string.evidence_stabilized),
                passed = evidence.stabilized,
            )
            EvidenceRow(
                label = stringResource(R.string.evidence_not_full_reload),
                passed = evidence.notFullReload,
            )
            EvidenceRow(
                label = stringResource(R.string.evidence_not_after_profile_change),
                passed = evidence.notAfterProfileChange,
            )
            EvidenceRow(
                label = stringResource(R.string.evidence_not_after_session_reset),
                passed = evidence.notAfterSessionReset,
            )
            EvidenceRow(
                label = stringResource(R.string.evidence_not_after_dom_rebuild),
                passed = evidence.notAfterDomRebuild,
            )
        }
    }
}

@Composable
private fun EvidenceRow(
    label: String,
    passed: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (passed) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = null,
            tint = if (passed) StatusNormal else StatusError,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 원본 자동 판정 표시 (사용자 판정과 분리). */
@Composable
private fun AutoVerdictCard(
    autoVerdict: AutoVerdict,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(
                label = stringResource(
                    when (autoVerdict) {
                        AutoVerdict.CANDIDATE -> R.string.auto_verdict_candidate
                        AutoVerdict.CONFIRMED -> R.string.auto_verdict_confirmed
                        AutoVerdict.INVALIDATED -> R.string.auto_verdict_invalidated
                    },
                ),
                statusColor = when (autoVerdict) {
                    AutoVerdict.CANDIDATE -> StatusPending
                    AutoVerdict.CONFIRMED -> StatusSuspected
                    AutoVerdict.INVALIDATED -> StatusError
                },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.event_detail_auto_verdict_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 사용자 판정 선택. 의심 유지·정상 변화·오탐·판단 보류 중 하나를 선택한다. */
@Composable
private fun VerdictSelector(
    selected: UserVerdict,
    onSelect: (UserVerdict) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(UserVerdict.entries) { verdict ->
                FilterChip(
                    selected = selected == verdict,
                    onClick = { onSelect(verdict) },
                    label = {
                        Text(text = stringResource(userVerdictLabel(verdict)))
                    },
                    leadingIcon = if (selected == verdict) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private fun userVerdictLabel(verdict: UserVerdict): Int = when (verdict) {
    UserVerdict.PENDING -> R.string.verdict_pending
    UserVerdict.SUSPECTED -> R.string.verdict_suspected
    UserVerdict.NORMAL_CHANGE -> R.string.verdict_normal_change
    UserVerdict.FALSE_POSITIVE -> R.string.verdict_false_positive
}

private fun parseVideoIds(json: String): List<String> = runCatching {
    val array = JSONArray(json)
    buildList { for (i in 0 until array.length()) add(array.getString(i)) }
}.getOrDefault(emptyList())

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(timestamp))
