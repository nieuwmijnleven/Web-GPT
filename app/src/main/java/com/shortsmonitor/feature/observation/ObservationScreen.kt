package com.shortsmonitor.feature.observation

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shortsmonitor.app.BuildConfig
import com.shortsmonitor.app.R
import com.shortsmonitor.app.ShortsMonitorApplication
import com.shortsmonitor.core.database.entity.ObservationSessionEntity
import com.shortsmonitor.core.design.StatusActive
import com.shortsmonitor.core.design.StatusError
import com.shortsmonitor.core.design.StatusNormal
import com.shortsmonitor.core.design.StatusPending
import com.shortsmonitor.core.design.components.ConfirmationSheet
import com.shortsmonitor.core.design.components.EmptyState
import com.shortsmonitor.core.design.components.ErrorState
import com.shortsmonitor.core.design.components.LoadingState
import com.shortsmonitor.core.design.components.MetricCard
import com.shortsmonitor.core.design.components.OutlinedActionButton
import com.shortsmonitor.core.design.components.PrimaryActionButton
import com.shortsmonitor.core.design.components.SessionCard
import com.shortsmonitor.core.design.components.StatusChip
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.model.SessionEndReason
import com.shortsmonitor.core.model.SessionStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 최근 세션 카드 표시 개수. */
private const val RECENT_SESSION_LIMIT = 5

/**
 * D단계 관찰 홈 화면.
 * 데이터베이스 값을 반영하며, 관찰 시작·종료·이어보기와 빠른 작업을 제공한다.
 * WebView 관찰 컨테이너(I단계) 적용 전이므로 시작/이어보기는 세션 상태만 변경한다.
 */
@Composable
fun ObservationScreen(
    onNavigateToSessions: () -> Unit,
    onNavigateToEvents: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val database = remember {
        (context.applicationContext as ShortsMonitorApplication).database
    }
    val scope = rememberCoroutineScope()
    var retryKey by remember { mutableIntStateOf(0) }
    var showResetSheet by remember { mutableStateOf(false) }

    val uiState by produceState<ObservationHomeUiState>(
        initialValue = ObservationHomeUiState.Loading,
        key1 = retryKey,
    ) {
        try {
            combine(
                database.observationSessionDao().observeAll(),
                database.insertionEventDao().observeAll(),
            ) { sessions, events ->
                val recent = sessions.take(RECENT_SESSION_LIMIT)
                ObservationHomeUiState.Content(
                    sessions = sessions,
                    activeSession = sessions.firstOrNull { it.status == SessionStatus.ACTIVE },
                    interruptedSession = sessions.firstOrNull { it.status == SessionStatus.INTERRUPTED },
                    recentEventCount = events.size,
                    shortsCountBySession = recent.associate { session ->
                        session.id to database.observedShortDao().countBySession(session.id)
                    },
                    eventsBySession = events.groupBy { it.sessionId }.mapValues { it.value.size },
                )
            }.collect { value = it }
        } catch (e: Exception) {
            ShortsLog.e("Observation home: failed to load", e)
            value = ObservationHomeUiState.Error
        }
    }

    when (val state = uiState) {
        ObservationHomeUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
        ObservationHomeUiState.Error -> ErrorState(
            message = stringResource(R.string.home_error_message),
            onRetry = { retryKey++ },
        )
        is ObservationHomeUiState.Content -> {
            val startObservation: () -> Unit = {
                scope.launch {
                    database.observationSessionDao().insert(
                        ObservationSessionEntity(
                            sessionId = UUID.randomUUID().toString(),
                            name = context.getString(
                                R.string.session_name_format,
                                state.sessions.size + 1,
                            ),
                            status = SessionStatus.ACTIVE,
                            startedAt = System.currentTimeMillis(),
                            appVersion = BuildConfig.VERSION_NAME,
                        ),
                    )
                }
            }
            val endSession: (ObservationSessionEntity) -> Unit = { session ->
                scope.launch {
                    database.observationSessionDao().updateStatus(
                        id = session.id,
                        status = SessionStatus.COMPLETED,
                        endedAt = System.currentTimeMillis(),
                        endReason = SessionEndReason.USER_FINISHED,
                    )
                }
            }
            val resumeSession: (ObservationSessionEntity) -> Unit = { session ->
                scope.launch {
                    database.observationSessionDao().updateStatus(
                        id = session.id,
                        status = SessionStatus.ACTIVE,
                        endedAt = null,
                        endReason = null,
                    )
                }
            }
            ObservationHomeContent(
                state = state,
                onStart = startObservation,
                onEnd = endSession,
                onResume = resumeSession,
                onSessionClick = onNavigateToSessions,
                onNavigateToEvents = onNavigateToEvents,
                onNavigateToProfiles = onNavigateToProfiles,
                onResetClick = { showResetSheet = true },
                modifier = modifier,
            )
        }
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

private sealed interface ObservationHomeUiState {
    data object Loading : ObservationHomeUiState

    data object Error : ObservationHomeUiState

    data class Content(
        val sessions: List<ObservationSessionEntity>,
        val activeSession: ObservationSessionEntity?,
        val interruptedSession: ObservationSessionEntity?,
        val recentEventCount: Int,
        val shortsCountBySession: Map<Long, Int>,
        val eventsBySession: Map<Long, Int>,
    ) : ObservationHomeUiState
}

@Composable
private fun ObservationHomeContent(
    state: ObservationHomeUiState.Content,
    onStart: () -> Unit,
    onEnd: (ObservationSessionEntity) -> Unit,
    onResume: (ObservationSessionEntity) -> Unit,
    onSessionClick: () -> Unit,
    onNavigateToEvents: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onResetClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastObservedAt = state.sessions.maxOfOrNull { it.startedAt }
    val observing = state.activeSession != null

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatusSummaryCard(
                observing = observing,
                lastObservedAt = lastObservedAt,
                totalSessionCount = state.sessions.size,
                recentEventCount = state.recentEventCount,
            )
        }

        item {
            PrimaryActionButton(
                text = stringResource(R.string.home_start_observation),
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        state.activeSession?.let { session ->
            item {
                ActiveSessionCard(
                    session = session,
                    onEnd = { onEnd(session) },
                )
            }
        }

        state.interruptedSession?.let { session ->
            item {
                InterruptedSessionCard(
                    session = session,
                    onResume = { onResume(session) },
                    onEnd = { onEnd(session) },
                )
            }
        }

        item {
            QuickActionsRow(
                onNavigateToProfiles = onNavigateToProfiles,
                onReset = onResetClick,
                onNavigateToEvents = onNavigateToEvents,
            )
        }

        item {
            Text(
                text = stringResource(R.string.home_recent_sessions),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (state.sessions.isEmpty()) {
            item {
                EmptyState(title = stringResource(R.string.home_no_sessions))
            }
        } else {
            items(state.sessions.take(RECENT_SESSION_LIMIT), key = { it.id }) { session ->
                SessionCard(
                    title = session.name,
                    statusColor = sessionStatusColor(session.status),
                    statusLabel = stringResource(sessionStatusLabel(session.status)),
                    subtitle = formatTimestamp(session.startedAt),
                    metrics = listOf(
                        stringResource(R.string.session_metric_shorts) to
                            state.shortsCountBySession[session.id].orZero(),
                        stringResource(R.string.session_metric_events) to
                            state.eventsBySession[session.id].orZero(),
                    ),
                    onClick = onSessionClick,
                )
            }
        }
    }
}

@Composable
private fun StatusSummaryCard(
    observing: Boolean,
    lastObservedAt: Long?,
    totalSessionCount: Int,
    recentEventCount: Int,
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
                        if (observing) R.string.home_status_observing else R.string.home_status_idle,
                    ),
                    statusColor = if (observing) StatusActive else StatusPending,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.home_current_profile_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.home_current_profile_default),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(
                    label = stringResource(R.string.home_last_observed),
                    value = lastObservedAt?.let(::formatTimestamp) ?: "-",
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    label = stringResource(R.string.home_total_sessions),
                    value = totalSessionCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    label = stringResource(R.string.home_recent_events),
                    value = recentEventCount.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ActiveSessionCard(
    session: ObservationSessionEntity,
    onEnd: () -> Unit,
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_active_session_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedActionButton(
                text = stringResource(R.string.home_end_session),
                onClick = onEnd,
            )
        }
    }
}

@Composable
private fun InterruptedSessionCard(
    session: ObservationSessionEntity,
    onResume: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.home_interrupted_session_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = session.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryActionButton(
                    text = stringResource(R.string.home_resume_session),
                    onClick = onResume,
                    modifier = Modifier.weight(1f),
                )
                OutlinedActionButton(
                    text = stringResource(R.string.home_end_session),
                    onClick = onEnd,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun QuickActionsRow(
    onNavigateToProfiles: () -> Unit,
    onReset: () -> Unit,
    onNavigateToEvents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickAction(
            icon = Icons.Outlined.Person,
            label = stringResource(R.string.home_action_profiles),
            onClick = onNavigateToProfiles,
            modifier = Modifier.weight(1f),
        )
        QuickAction(
            icon = Icons.Outlined.Refresh,
            label = stringResource(R.string.home_action_reset),
            onClick = onReset,
            modifier = Modifier.weight(1f),
        )
        QuickAction(
            icon = Icons.Outlined.Warning,
            label = stringResource(R.string.home_action_events),
            onClick = onNavigateToEvents,
            modifier = Modifier.weight(1f),
        )
        // 로그 내보내기는 N단계에서 실제 동작을 연결한다.
        QuickAction(
            icon = Icons.Outlined.Share,
            label = stringResource(R.string.home_action_export),
            onClick = {},
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun sessionStatusColor(status: SessionStatus): Color = when (status) {
    SessionStatus.ACTIVE -> StatusActive
    SessionStatus.COMPLETED -> StatusNormal
    SessionStatus.INTERRUPTED -> StatusPending
    SessionStatus.ERROR -> StatusError
}

private fun sessionStatusLabel(status: SessionStatus): Int = when (status) {
    SessionStatus.ACTIVE -> R.string.home_status_observing
    SessionStatus.COMPLETED -> R.string.status_completed
    SessionStatus.INTERRUPTED -> R.string.status_interrupted
    SessionStatus.ERROR -> R.string.status_error
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun Int?.orZero(): String = (this ?: 0).toString()
