package com.shortsmonitor.feature.events

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.shortsmonitor.core.design.StatusPending
import com.shortsmonitor.core.design.StatusSuspected
import com.shortsmonitor.core.design.StatusUserConfirmed
import com.shortsmonitor.core.design.components.EmptyState
import com.shortsmonitor.core.design.components.ErrorState
import com.shortsmonitor.core.design.components.LoadingState
import com.shortsmonitor.core.design.components.StatusChip
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.model.AutoVerdict
import com.shortsmonitor.core.model.UserVerdict
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface EventsUiState {
    data object Loading : EventsUiState

    data object Error : EventsUiState

    data class Content(
        val events: List<InsertionEventEntity>,
        val shortsBySessionAndVideo: Map<Long, Map<String, ObservedShortEntity>>,
    ) : EventsUiState
}

/**
 * 의심 이벤트 목록 화면 (K단계).
 * 모든 의심 이벤트를 최신순으로 표시하고, 카드 선택 시 상세 화면으로 이동한다.
 * 카드에는 신규 영상 제목·채널·감지 시각과 자동/사용자 판정 상태를 함께 표시한다.
 */
@Composable
fun EventsScreen(
    onOpenEvent: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val database = remember {
        (context.applicationContext as ShortsMonitorApplication).database
    }
    var retryKey by remember { mutableIntStateOf(0) }

    val uiState by produceState<EventsUiState>(
        initialValue = EventsUiState.Loading,
        key1 = retryKey,
    ) {
        try {
            combine(
                database.insertionEventDao().observeAll(),
                database.observedShortDao().observeAll(),
            ) { events, shorts ->
                EventsUiState.Content(
                    events = events,
                    shortsBySessionAndVideo = shorts.groupBy { it.sessionId }
                        .mapValues { (_, list) -> list.associateBy { it.videoId } },
                )
            }.collect { value = it }
        } catch (e: Exception) {
            ShortsLog.e("Events: failed to load", e)
            value = EventsUiState.Error
        }
    }

    when (val state = uiState) {
        EventsUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
        EventsUiState.Error -> ErrorState(
            message = stringResource(R.string.events_error_message),
            onRetry = { retryKey++ },
        )
        is EventsUiState.Content -> {
            if (state.events.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.events_empty_title),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.events, key = { it.id }) { event ->
                        val video = state.shortsBySessionAndVideo[event.sessionId]
                            ?.get(event.newVideoId)
                        EventListCard(
                            event = event,
                            title = video?.title ?: event.newVideoId,
                            channel = video?.channelName.orEmpty(),
                            onClick = { onOpenEvent(event.id) },
                        )
                    }
                }
            }
        }
    }
}

/** 의심 이벤트 목록의 한 항목 카드. 자동 판정과 사용자 판정 상태를 색상·아이콘·텍스트로 함께 표시한다. */
@Composable
private fun EventListCard(
    event: InsertionEventEntity,
    title: String,
    channel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val autoLabel = stringResource(autoVerdictLabel(event.autoVerdict))
    val autoColor = autoVerdictColor(event.autoVerdict)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, autoColor.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    label = autoLabel,
                    statusColor = autoColor,
                )
                Spacer(Modifier.width(8.dp))
                if (event.userVerdict != UserVerdict.PENDING) {
                    StatusChip(
                        label = stringResource(userVerdictLabel(event.userVerdict)),
                        statusColor = userVerdictColor(event.userVerdict),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatTimestamp(event.detectedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            if (channel.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = channel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun autoVerdictColor(verdict: AutoVerdict) = when (verdict) {
    AutoVerdict.CANDIDATE -> StatusPending
    AutoVerdict.CONFIRMED -> StatusSuspected
    AutoVerdict.INVALIDATED -> StatusError
}

private fun autoVerdictLabel(verdict: AutoVerdict): Int = when (verdict) {
    AutoVerdict.CANDIDATE -> R.string.auto_verdict_candidate
    AutoVerdict.CONFIRMED -> R.string.auto_verdict_confirmed
    AutoVerdict.INVALIDATED -> R.string.auto_verdict_invalidated
}

private fun userVerdictColor(verdict: UserVerdict) = when (verdict) {
    UserVerdict.PENDING -> StatusPending
    UserVerdict.SUSPECTED -> StatusSuspected
    UserVerdict.NORMAL_CHANGE -> com.shortsmonitor.core.design.StatusNormal
    UserVerdict.FALSE_POSITIVE -> StatusUserConfirmed
}

private fun userVerdictLabel(verdict: UserVerdict): Int = when (verdict) {
    UserVerdict.PENDING -> R.string.verdict_pending
    UserVerdict.SUSPECTED -> R.string.verdict_suspected
    UserVerdict.NORMAL_CHANGE -> R.string.verdict_normal_change
    UserVerdict.FALSE_POSITIVE -> R.string.verdict_false_positive
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(timestamp))
