package com.shortsmonitor.feature.sessions

import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shortsmonitor.app.R
import com.shortsmonitor.app.ShortsMonitorApplication
import com.shortsmonitor.core.database.entity.ObservationSessionEntity
import com.shortsmonitor.core.design.StatusActive
import com.shortsmonitor.core.design.StatusError
import com.shortsmonitor.core.design.StatusNormal
import com.shortsmonitor.core.design.StatusPending
import com.shortsmonitor.core.design.components.EmptyState
import com.shortsmonitor.core.design.components.ErrorState
import com.shortsmonitor.core.design.components.LoadingState
import com.shortsmonitor.core.design.components.SessionCard
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.model.SessionStatus
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 세션 목록 필터. */
private enum class SessionFilter(@StringRes val labelRes: Int) {
    ALL(R.string.sessions_filter_all),
    HAS_EVENTS(R.string.sessions_filter_events),
    NORMAL(R.string.sessions_filter_normal),
    ERROR(R.string.sessions_filter_error),
    HAS_RESET(R.string.sessions_filter_reset),
}

/** 세션 목록의 한 항목 (집계 값 포함). */
private data class SessionListItem(
    val session: ObservationSessionEntity,
    val shortsCount: Int,
    val eventCount: Int,
    val hasReset: Boolean,
    val videoTitles: List<String>,
    val channelNames: List<String>,
)

private sealed interface SessionsUiState {
    data object Loading : SessionsUiState

    data object Error : SessionsUiState

    data class Content(
        val items: List<SessionListItem>,
        val profileName: String?,
    ) : SessionsUiState
}

/**
 * 세션 기록 화면 (J단계).
 * 세션 목록을 검색·필터와 함께 표시하고, 카드 선택 시 상세 화면으로 이동한다.
 * 대량 세션에서도 원활히 스크롤되도록 LazyColumn 키를 사용한다.
 */
@Composable
fun SessionsScreen(
    onOpenSession: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val database = remember {
        (context.applicationContext as ShortsMonitorApplication).database
    }
    var retryKey by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SessionFilter.ALL) }

    val uiState by produceState<SessionsUiState>(
        initialValue = SessionsUiState.Loading,
        key1 = retryKey,
    ) {
        try {
            combine(
                database.observationSessionDao().observeAll(),
                database.observedShortDao().observeAll(),
                database.insertionEventDao().observeAll(),
                database.listSnapshotDao().observeSessionIdsWithReset(),
                database.browserProfileDao().observeAll(),
            ) { sessions, shorts, events, resetSessionIds, profiles ->
                val shortsBySession = shorts.groupBy { it.sessionId }
                val eventsBySession = events.groupBy { it.sessionId }
                val resetSet = resetSessionIds.toSet()
                SessionsUiState.Content(
                    items = sessions.map { session ->
                        val sessionShorts = shortsBySession[session.id].orEmpty()
                        SessionListItem(
                            session = session,
                            shortsCount = sessionShorts.size,
                            eventCount = eventsBySession[session.id]?.size ?: 0,
                            hasReset = session.id in resetSet,
                            videoTitles = sessionShorts.mapNotNull { it.title }.filter { it.isNotBlank() },
                            channelNames = sessionShorts.mapNotNull { it.channelName }.filter { it.isNotBlank() },
                        )
                    },
                    profileName = profiles.maxByOrNull { it.lastUsedAt ?: 0L }?.name,
                )
            }.collect { value = it }
        } catch (e: Exception) {
            ShortsLog.e("Sessions: failed to load", e)
            value = SessionsUiState.Error
        }
    }

    when (val state = uiState) {
        SessionsUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
        SessionsUiState.Error -> ErrorState(
            message = stringResource(R.string.sessions_error_message),
            onRetry = { retryKey++ },
        )
        is SessionsUiState.Content -> {
            val filtered = state.items.filter { item ->
                matchesFilter(item, filter) && matchesQuery(
                    item = item,
                    query = query,
                    profileName = state.profileName,
                )
            }
            SessionsContent(
                items = filtered,
                query = query,
                onQueryChange = { query = it },
                filter = filter,
                onFilterChange = { filter = it },
                onOpenSession = onOpenSession,
                modifier = modifier,
            )
        }
    }
}

private fun matchesFilter(item: SessionListItem, filter: SessionFilter): Boolean = when (filter) {
    SessionFilter.ALL -> true
    SessionFilter.HAS_EVENTS -> item.eventCount > 0
    SessionFilter.NORMAL -> item.session.status == SessionStatus.COMPLETED && item.eventCount == 0
    SessionFilter.ERROR -> item.session.status == SessionStatus.ERROR
    SessionFilter.HAS_RESET -> item.hasReset
}

private fun matchesQuery(
    item: SessionListItem,
    query: String,
    profileName: String?,
): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    val lower = q.lowercase(Locale.getDefault())
    return item.session.name.contains(lower, ignoreCase = true) ||
        item.videoTitles.any { it.contains(lower, ignoreCase = true) } ||
        item.channelNames.any { it.contains(lower, ignoreCase = true) } ||
        profileName?.contains(lower, ignoreCase = true) == true
}

@Composable
private fun SessionsContent(
    items: List<SessionListItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: SessionFilter,
    onFilterChange: (SessionFilter) -> Unit,
    onOpenSession: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 검색 입력
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = {
                Text(text = stringResource(R.string.sessions_search_hint))
            },
            leadingIcon = {
                Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.sessions_search_clear),
                        )
                    }
                }
            },
            singleLine = true,
        )

        // 필터 칩
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SessionFilter.entries) { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { onFilterChange(f) },
                    label = { Text(text = stringResource(f.labelRes)) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (items.isEmpty()) {
            EmptyState(
                title = stringResource(
                    if (query.isBlank() && filter == SessionFilter.ALL) {
                        R.string.sessions_empty
                    } else {
                        R.string.sessions_no_match
                    },
                ),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.session.id }) { item ->
                    SessionCard(
                        title = item.session.name,
                        statusColor = sessionStatusColor(item.session.status),
                        statusLabel = stringResource(sessionStatusLabel(item.session.status)),
                        subtitle = formatTimestamp(item.session.startedAt),
                        metrics = listOf(
                            stringResource(R.string.session_metric_duration) to
                                formatDuration(item.session),
                            stringResource(R.string.session_metric_shorts) to
                                item.shortsCount.toString(),
                            stringResource(R.string.session_metric_events) to
                                item.eventCount.toString(),
                            stringResource(R.string.session_metric_export) to
                                stringResource(R.string.session_export_pending),
                        ),
                        onClick = { onOpenSession(item.session.id) },
                    )
                }
            }
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

private fun formatDuration(session: ObservationSessionEntity): String {
    val end = session.endedAt ?: return "-"
    val minutes = ((end - session.startedAt) / 60_000L).toInt()
    if (minutes < 60) return minutes.toString() + "분"
    return "${minutes / 60}시간 ${minutes % 60}분"
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(timestamp))
