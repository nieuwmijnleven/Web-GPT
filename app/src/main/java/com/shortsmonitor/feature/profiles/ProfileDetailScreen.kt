package com.shortsmonitor.feature.profiles

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.shortsmonitor.app.R
import com.shortsmonitor.app.ShortsMonitorApplication
import com.shortsmonitor.core.database.entity.BrowserProfileEntity
import com.shortsmonitor.core.design.StatusNormal
import com.shortsmonitor.core.design.StatusPending
import com.shortsmonitor.core.design.components.EmptyState
import com.shortsmonitor.core.design.components.ErrorState
import com.shortsmonitor.core.design.components.LoadingState
import com.shortsmonitor.core.design.components.OutlinedActionButton
import com.shortsmonitor.core.design.components.PrimaryActionButton
import com.shortsmonitor.core.design.components.ShortsMonitorTopBar
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.model.ProfileTemplateType
import com.shortsmonitor.core.profile.ProfileApplyItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface ProfileDetailUiState {
    data object Loading : ProfileDetailUiState

    data object Error : ProfileDetailUiState

    data class Content(
        val profile: BrowserProfileEntity,
        val active: Boolean,
    ) : ProfileDetailUiState
}

/**
 * 브라우저 테스트 프로필 상세 화면 (L단계).
 * 프로필 전체 정보와 WebView 적용 결과(적용/미지원)를 표시하고,
 * 적용·복제 작업을 제공한다.
 *
 * 언어·시간대·화면 크기·CPU/메모리·터치처럼 공개 WebView API로 바꿀 수 없는
 * 값은 '지원되지 않음'으로 명시한다. IP나 실제 하드웨어 변경을 암시하지 않는다.
 */
@Composable
fun ProfileDetailScreen(
    profileId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val database = remember {
        (context.applicationContext as ShortsMonitorApplication).database
    }
    val scope = rememberCoroutineScope()
    var retryKey by remember { mutableIntStateOf(0) }

    val uiState by produceState<ProfileDetailUiState>(
        initialValue = ProfileDetailUiState.Loading,
        key1 = retryKey,
    ) {
        try {
            database.browserProfileDao().observeById(profileId).collect { profile ->
                if (profile == null) {
                    value = ProfileDetailUiState.Error
                    return@collect
                }
                val all = database.browserProfileDao().observeAll().first()
                value = ProfileDetailUiState.Content(
                    profile = profile,
                    active = all.maxByOrNull { it.lastUsedAt ?: 0L }?.id == profile.id,
                )
            }
        } catch (e: Exception) {
            ShortsLog.e("Profile detail: failed to load", e)
            value = ProfileDetailUiState.Error
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        when (val state = uiState) {
            ProfileDetailUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
            ProfileDetailUiState.Error -> ErrorState(
                message = stringResource(R.string.profiles_error_message),
                onRetry = { retryKey++ },
                modifier = Modifier.fillMaxSize(),
            )
            is ProfileDetailUiState.Content -> {
                val profile = state.profile
                ShortsMonitorTopBar(
                    title = profile.name,
                    onBack = onBack,
                )
                ProfileDetailContent(
                    profile = profile,
                    active = state.active,
                    onApply = {
                        scope.launch {
                            database.browserProfileDao().updateLastUsed(
                                profile.id,
                                System.currentTimeMillis(),
                            )
                        }
                    },
                    onDuplicate = {
                        scope.launch {
                            val count = database.browserProfileDao().observeAll().first().size
                            database.browserProfileDao().insert(
                                profile.copy(
                                    id = 0,
                                    name = context.getString(
                                        R.string.profiles_duplicate_name,
                                        profile.name,
                                        count + 1,
                                    ),
                                    createdAt = System.currentTimeMillis(),
                                    lastUsedAt = null,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ProfileDetailContent(
    profile: BrowserProfileEntity,
    active: Boolean,
    onApply: () -> Unit,
    onDuplicate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ProfileInfoCard(
                profile = profile,
                active = active,
            )
        }

        item {
            SectionHeader(stringResource(R.string.profile_apply_result_title))
        }
        item {
            ApplyResultCard(profile = profile)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryActionButton(
                    text = stringResource(R.string.profile_apply),
                    onClick = onApply,
                    modifier = Modifier.weight(1f),
                )
                OutlinedActionButton(
                    text = stringResource(R.string.profile_duplicate),
                    onClick = onDuplicate,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProfileInfoCard(
    profile: BrowserProfileEntity,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            InfoRow(
                label = stringResource(R.string.profile_device_type),
                value = stringResource(templateLabel(profile.templateType)),
            )
            InfoRow(
                label = stringResource(R.string.profile_ua),
                value = profile.userAgent,
            )
            InfoRow(
                label = stringResource(R.string.profile_language),
                value = profile.language,
            )
            InfoRow(
                label = stringResource(R.string.profile_timezone),
                value = profile.timezone,
            )
            InfoRow(
                label = stringResource(R.string.profile_screen),
                value = profile.screenOverride ?: "-",
            )
            InfoRow(
                label = stringResource(R.string.profile_hardware),
                value = profile.hardwareOverride ?: "-",
            )
            InfoRow(
                label = stringResource(R.string.profile_touch),
                value = if (profile.touchOverride == true) {
                    stringResource(R.string.profile_touch_supported)
                } else {
                    stringResource(R.string.profile_touch_unsupported)
                },
            )
            InfoRow(
                label = stringResource(R.string.profile_created_at),
                value = formatTimestamp(profile.createdAt),
            )
            InfoRow(
                label = stringResource(R.string.profile_last_used),
                value = profile.lastUsedAt?.let(::formatTimestamp) ?: "-",
            )
            InfoRow(
                label = stringResource(R.string.profile_active_label),
                value = if (active) {
                    stringResource(R.string.profile_active)
                } else {
                    stringResource(R.string.profile_inactive)
                },
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
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * WebView 적용 결과 카드.
 * User-Agent·메타데이터는 적용 여부를, 공개 API로 바꿀 수 없는 값은
 * '지원되지 않음'으로 명시한다.
 */
@Composable
private fun ApplyResultCard(
    profile: BrowserProfileEntity,
    modifier: Modifier = Modifier,
) {
    // 적용 결과는 화면 전환 시점에 다시 계산한다 (플랫폼 WebView 기능 상태 반영).
    val result = remember(profile.id) {
        com.shortsmonitor.core.profile.ProfileApplier.plan(profile)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (result.applied.isEmpty() && result.unsupported.isEmpty()) {
                EmptyState(title = stringResource(R.string.profile_apply_result_empty))
            } else {
                result.allItems.forEach { item ->
                    ApplyItemRow(
                        item = item,
                        supported = item in result.applied,
                    )
                }
            }
        }
    }
}

@Composable
private fun ApplyItemRow(
    item: ProfileApplyItem,
    supported: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (supported) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = null,
            tint = if (supported) StatusNormal else StatusPending,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(applyItemLabel(item)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(
                if (supported) R.string.profile_applied else R.string.profile_unsupported,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (supported) StatusNormal else StatusPending,
        )
    }
}

private fun templateLabel(template: ProfileTemplateType): Int = when (template) {
    ProfileTemplateType.SMALL_ANDROID -> R.string.profile_template_small
    ProfileTemplateType.ANDROID -> R.string.profile_template_android
    ProfileTemplateType.LARGE_ANDROID -> R.string.profile_template_large
    ProfileTemplateType.ANDROID_TABLET -> R.string.profile_template_tablet
}

private fun applyItemLabel(item: ProfileApplyItem): Int = when (item) {
    ProfileApplyItem.USER_AGENT -> R.string.profile_item_user_agent
    ProfileApplyItem.USER_AGENT_METADATA -> R.string.profile_item_user_agent_metadata
    ProfileApplyItem.LANGUAGE -> R.string.profile_item_language
    ProfileApplyItem.TIMEZONE -> R.string.profile_item_timezone
    ProfileApplyItem.SCREEN -> R.string.profile_item_screen
    ProfileApplyItem.HARDWARE -> R.string.profile_item_hardware
    ProfileApplyItem.TOUCH -> R.string.profile_item_touch
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

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(timestamp))
