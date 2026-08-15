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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shortsmonitor.app.R
import com.shortsmonitor.app.ShortsMonitorApplication
import com.shortsmonitor.core.database.entity.BrowserProfileEntity
import com.shortsmonitor.core.design.components.EmptyState
import com.shortsmonitor.core.design.components.ErrorState
import com.shortsmonitor.core.design.components.LoadingState
import com.shortsmonitor.core.design.components.OutlinedActionButton
import com.shortsmonitor.core.design.components.PrimaryActionButton
import com.shortsmonitor.core.design.components.ProfileCard
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.model.ProfileTemplateType
import com.shortsmonitor.core.profile.ProfileGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface ProfilesUiState {
    data object Loading : ProfilesUiState

    data object Error : ProfilesUiState

    data class Content(
        val profiles: List<BrowserProfileEntity>,
        val activeProfileId: Long?,
    ) : ProfilesUiState
}

/**
 * 브라우저 테스트 프로필 목록 화면 (L단계).
 * 카드형 목록으로 프로필 이름·활성 상태·기기 유형·User-Agent 요약·화면 표현값·
 * 언어·시간대·마지막 사용 시각을 표시한다.
 *
 * - 무작위 생성: 호환 가능한 템플릿을 먼저 선택하고 범위 안에서 값을 생성한다.
 * - 적용: 활성 프로필로 설정한다 (WebView 노출값 변경).
 * - 복제: 현재 프로필을 복제한다.
 * - 카드 선택: 프로필 상세로 이동한다.
 */
@Composable
fun ProfilesScreen(
    onOpenProfile: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val database = remember {
        (context.applicationContext as ShortsMonitorApplication).database
    }
    val scope = rememberCoroutineScope()
    var retryKey by remember { mutableIntStateOf(0) }
    var generating by remember { mutableStateOf(false) }

    val uiState by produceState<ProfilesUiState>(
        initialValue = ProfilesUiState.Loading,
        key1 = retryKey,
    ) {
        try {
            database.browserProfileDao().observeAll().collect { profiles ->
                value = ProfilesUiState.Content(
                    profiles = profiles,
                    activeProfileId = profiles.maxByOrNull { it.lastUsedAt ?: 0L }?.id,
                )
            }
        } catch (e: Exception) {
            ShortsLog.e("Profiles: failed to load", e)
            value = ProfilesUiState.Error
        }
    }

    when (val state = uiState) {
        ProfilesUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
        ProfilesUiState.Error -> ErrorState(
            message = stringResource(R.string.profiles_error_message),
            onRetry = { retryKey++ },
        )
        is ProfilesUiState.Content -> {
            val generate: () -> Unit = {
                if (!generating) {
                    generating = true
                    scope.launch {
                        try {
                            val template = ProfileGenerator.randomTemplate()
                            val existing = database.browserProfileDao().observeAll().first()
                            val generated = ProfileGenerator.generate(template)
                            val name = context.getString(
                                R.string.profiles_name_format,
                                context.getString(templateLabel(template)),
                                existing.size + 1,
                            )
                            database.browserProfileDao().insert(
                                BrowserProfileEntity(
                                    name = name,
                                    templateType = template,
                                    userAgent = generated.userAgent,
                                    language = generated.language,
                                    timezone = generated.timezone,
                                    screenOverride = generated.screenOverride,
                                    hardwareOverride = generated.hardwareOverride,
                                    touchOverride = generated.touchOverride,
                                    createdAt = System.currentTimeMillis(),
                                ),
                            )
                        } catch (e: Exception) {
                            ShortsLog.e("Profiles: failed to generate", e)
                        } finally {
                            generating = false
                        }
                    }
                }
            }

            ProfilesContent(
                state = state,
                generating = generating,
                onGenerate = generate,
                onOpenProfile = onOpenProfile,
                onApply = { profile ->
                    scope.launch {
                        database.browserProfileDao().updateLastUsed(
                            profile.id,
                            System.currentTimeMillis(),
                        )
                    }
                },
                onDuplicate = { profile ->
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
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ProfilesContent(
    state: ProfilesUiState.Content,
    generating: Boolean,
    onGenerate: () -> Unit,
    onOpenProfile: (Long) -> Unit,
    onApply: (BrowserProfileEntity) -> Unit,
    onDuplicate: (BrowserProfileEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            PrimaryActionButton(
                text = stringResource(
                    if (generating) R.string.profiles_generating else R.string.profiles_generate,
                ),
                onClick = onGenerate,
                enabled = !generating,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.profiles_generate_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.profiles.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.profiles_empty_title),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.profiles, key = { it.id }) { profile ->
                    ProfileItem(
                        profile = profile,
                        active = profile.id == state.activeProfileId,
                        onClick = { onOpenProfile(profile.id) },
                        onApply = { onApply(profile) },
                        onDuplicate = { onDuplicate(profile) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileItem(
    profile: BrowserProfileEntity,
    active: Boolean,
    onClick: () -> Unit,
    onApply: () -> Unit,
    onDuplicate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ProfileCard(
            name = profile.name,
            active = active,
            summary = profile.userAgent,
            details = listOf(
                stringResource(R.string.profile_device_type) to
                    stringResource(templateLabel(profile.templateType)),
                stringResource(R.string.profile_screen) to
                    (profile.screenOverride ?: "-"),
                stringResource(R.string.profile_language) to profile.language,
                stringResource(R.string.profile_timezone) to profile.timezone,
                stringResource(R.string.profile_last_used) to
                    (profile.lastUsedAt?.let(::formatTimestamp) ?: "-"),
            ),
            onClick = onClick,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedActionButton(
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
}

private fun templateLabel(template: ProfileTemplateType): Int = when (template) {
    ProfileTemplateType.SMALL_ANDROID -> R.string.profile_template_small
    ProfileTemplateType.ANDROID -> R.string.profile_template_android
    ProfileTemplateType.LARGE_ANDROID -> R.string.profile_template_large
    ProfileTemplateType.ANDROID_TABLET -> R.string.profile_template_tablet
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(timestamp))
