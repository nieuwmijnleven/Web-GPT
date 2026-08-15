package com.shortsmonitor.feature.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shortsmonitor.app.BuildConfig
import com.shortsmonitor.app.R
import com.shortsmonitor.app.ShortsMonitorApplication
import com.shortsmonitor.core.design.components.ConfirmationSheet
import com.shortsmonitor.core.design.components.OutlinedActionButton
import com.shortsmonitor.core.export.ExportFileWriter
import com.shortsmonitor.core.export.SessionExportBuilder
import com.shortsmonitor.core.export.SessionExportLoader
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.model.ShortsError
import com.shortsmonitor.core.notification.NotificationHelper
import com.shortsmonitor.core.settings.RetentionPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 설정 화면 (O단계).
 *
 * - 관찰 설정: 목록 스냅샷 저장·의심 후보 안정화·메타데이터 저장·썸네일 주소 저장
 * - 알림 설정: 앱 내부 배너·시스템 알림·진동·오류 알림·의심 이벤트 알림
 * - 데이터 설정: 기록 보존 정책·기간 초과 정리·세션 삭제·전체 데이터 삭제·JSON/CSV 내보내기
 * - WebView 진단: 진단 화면으로 이동
 *
 * 위험한 삭제 작업은 확인 절차를 거치며, 내보내기는 시스템 파일 선택기로 저장 위치를 고른다.
 */
@Composable
fun SettingsScreen(
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember {
        (context.applicationContext as ShortsMonitorApplication).settingsRepository
    }
    val database = remember {
        (context.applicationContext as ShortsMonitorApplication).database
    }
    val scope = rememberCoroutineScope()

    val saveSnapshots by repository.saveListSnapshots.collectAsStateWithLifecycle(initialValue = true)
    val stabilize by repository.stabilizeCandidates.collectAsStateWithLifecycle(initialValue = true)
    val saveMetadata by repository.saveMetadata.collectAsStateWithLifecycle(initialValue = true)
    val saveThumbnails by repository.saveThumbnails.collectAsStateWithLifecycle(initialValue = true)
    val inAppBanner by repository.inAppBanner.collectAsStateWithLifecycle(initialValue = true)
    val systemNotifications by repository.systemNotifications.collectAsStateWithLifecycle(initialValue = false)
    val vibration by repository.vibration.collectAsStateWithLifecycle(initialValue = true)
    val errorNotifications by repository.errorNotifications.collectAsStateWithLifecycle(initialValue = true)
    val eventNotifications by repository.suspectedEventNotifications.collectAsStateWithLifecycle(initialValue = true)
    val retentionPolicy by repository.retentionPolicy.collectAsStateWithLifecycle(initialValue = RetentionPolicy.ALL)

    // 위험한 삭제 작업 확인 시트 상태.
    var pendingDelete by remember { mutableStateOf<DeleteAction?>(null) }
    // 내보내기 상태.
    var exportInProgress by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<ShortsError.Export?>(null) }
    var pendingExportUri by remember { mutableStateOf<CompletableDeferred<android.net.Uri?>?>(null) }
    // 삭제 완료 안내.
    var deleteResult by remember { mutableStateOf<String?>(null) }

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
    // 시스템 알림 권한 요청 (Android 13+). 거절돼도 토글 값은 유지하고 전송 시 권한을 다시 확인한다.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 권한 결과는 NotificationHelper.canPostNotifications에서 다시 확인한다 */ }

    suspend fun pickExportUri(
        launcher: androidx.activity.result.ActivityResultLauncher<String>,
        name: String,
    ): android.net.Uri? {
        val deferred = CompletableDeferred<android.net.Uri?>()
        pendingExportUri = deferred
        launcher.launch(name)
        return deferred.await()
    }

    val runExportJson: () -> Unit = {
        if (!exportInProgress) {
            exportInProgress = true
            scope.launch {
                try {
                    val data = SessionExportLoader.loadAll(database)
                    val content = SessionExportBuilder.buildAllJson(data, BuildConfig.VERSION_NAME)
                    val uri = pickExportUri(jsonLauncher, SessionExportBuilder.allJsonFileName())
                    if (uri != null) {
                        exportError = ExportFileWriter.write(context, uri, content)
                    }
                } catch (e: Exception) {
                    ShortsLog.e("Settings export all JSON failed", e)
                    exportError = ShortsError.Export(e.message ?: "Export failed", e)
                } finally {
                    exportInProgress = false
                }
            }
        }
    }

    val runExportCsv: () -> Unit = {
        if (!exportInProgress) {
            exportInProgress = true
            scope.launch {
                try {
                    val data = SessionExportLoader.loadAll(database)
                    val files = SessionExportBuilder.buildCsvFilesAll(data)
                    for (file in files) {
                        val uri = pickExportUri(csvLauncher, file.fileName) ?: break
                        exportError = ExportFileWriter.write(context, uri, file.content)
                        if (exportError != null) break
                    }
                } catch (e: Exception) {
                    ShortsLog.e("Settings export all CSV failed", e)
                    exportError = ShortsError.Export(e.message ?: "Export failed", e)
                } finally {
                    exportInProgress = false
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsSectionHeader(stringResource(R.string.settings_section_observation)) }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_save_snapshots),
                description = stringResource(R.string.settings_save_snapshots_desc),
                checked = saveSnapshots,
                onCheckedChange = { scope.launch { repository.setSaveListSnapshots(it) } },
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_stabilize_candidates),
                description = stringResource(R.string.settings_stabilize_candidates_desc),
                checked = stabilize,
                onCheckedChange = { scope.launch { repository.setStabilizeCandidates(it) } },
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_save_metadata),
                description = stringResource(R.string.settings_save_metadata_desc),
                checked = saveMetadata,
                onCheckedChange = { scope.launch { repository.setSaveMetadata(it) } },
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_save_thumbnails),
                description = stringResource(R.string.settings_save_thumbnails_desc),
                checked = saveThumbnails,
                onCheckedChange = { scope.launch { repository.setSaveThumbnails(it) } },
            )
        }

        item { SettingsSectionHeader(stringResource(R.string.settings_section_notifications)) }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_in_app_banner),
                description = stringResource(R.string.settings_in_app_banner_desc),
                checked = inAppBanner,
                onCheckedChange = { scope.launch { repository.setInAppBanner(it) } },
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_system_notifications),
                description = stringResource(R.string.settings_system_notifications_desc),
                checked = systemNotifications,
                onCheckedChange = { enabled ->
                    scope.launch { repository.setSystemNotifications(enabled) }
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !NotificationHelper.canPostNotifications(context)
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_vibration),
                description = stringResource(R.string.settings_vibration_desc),
                checked = vibration,
                onCheckedChange = { scope.launch { repository.setVibration(it) } },
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_error_notifications),
                description = stringResource(R.string.settings_error_notifications_desc),
                checked = errorNotifications,
                onCheckedChange = { scope.launch { repository.setErrorNotifications(it) } },
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_event_notifications),
                description = stringResource(R.string.settings_event_notifications_desc),
                checked = eventNotifications,
                onCheckedChange = { scope.launch { repository.setSuspectedEventNotifications(it) } },
            )
        }

        item { SettingsSectionHeader(stringResource(R.string.settings_section_data)) }
        item {
            Column {
                Text(
                    text = stringResource(R.string.settings_retention_policy),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_retention_policy_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RetentionPolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = retentionPolicy == policy,
                            onClick = { scope.launch { repository.setRetentionPolicy(policy) } },
                            label = {
                                Text(
                                    text = stringResource(retentionPolicyLabel(policy)),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }
        item {
            OutlinedActionButton(
                text = stringResource(R.string.settings_cleanup_expired),
                onClick = { pendingDelete = DeleteAction.CLEANUP_EXPIRED },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedActionButton(
                text = stringResource(R.string.settings_delete_all_sessions),
                onClick = { pendingDelete = DeleteAction.DELETE_SESSIONS },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedActionButton(
                text = stringResource(R.string.settings_delete_all_data),
                onClick = { pendingDelete = DeleteAction.DELETE_ALL },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedActionButton(
                text = stringResource(
                    if (exportInProgress) R.string.export_in_progress else R.string.settings_export_json,
                ),
                onClick = runExportJson,
                enabled = !exportInProgress,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedActionButton(
                text = stringResource(
                    if (exportInProgress) R.string.export_in_progress else R.string.settings_export_csv,
                ),
                onClick = runExportCsv,
                enabled = !exportInProgress,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item { SettingsSectionHeader(stringResource(R.string.settings_section_diagnostics)) }
        item {
            OutlinedActionButton(
                text = stringResource(R.string.settings_open_diagnostics),
                onClick = onOpenDiagnostics,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedActionButton(
                text = stringResource(R.string.settings_view_onboarding),
                onClick = { scope.launch { repository.setOnboardingCompleted(false) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    // 위험한 삭제 작업 확인 시트.
    pendingDelete?.let { action ->
        ConfirmationSheet(
            visible = true,
            title = stringResource(action.titleRes),
            message = stringResource(action.messageRes),
            confirmLabel = stringResource(R.string.action_confirm),
            dismissLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    deleteResult = when (action) {
                        DeleteAction.CLEANUP_EXPIRED -> {
                            // '전체 보관'이면 정리 대상이 없다.
                            if (retentionPolicy == RetentionPolicy.ALL) {
                                context.getString(R.string.settings_cleanup_expired_none)
                            } else {
                                val days = when (retentionPolicy) {
                                    RetentionPolicy.ALL -> 0
                                    RetentionPolicy.THIRTY_DAYS -> 30L
                                    RetentionPolicy.SEVEN_DAYS -> 7L
                                }
                                val cutoff = System.currentTimeMillis() - days * 24 * 60 * 60 * 1000
                                val deleted = database.observationSessionDao().deleteOlderThan(cutoff)
                                context.getString(R.string.settings_cleanup_expired_result, deleted)
                            }
                        }
                        DeleteAction.DELETE_SESSIONS -> {
                            val deleted = database.observationSessionDao().deleteAll()
                            context.getString(R.string.settings_delete_sessions_result, deleted)
                        }
                        DeleteAction.DELETE_ALL -> {
                            val sessions = database.observationSessionDao().deleteAll()
                            val profiles = database.browserProfileDao().deleteAll()
                            context.getString(R.string.settings_delete_all_result, sessions, profiles)
                        }
                    }
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }

    // 삭제 완료 안내.
    deleteResult?.let { message ->
        AlertDialog(
            onDismissRequest = { deleteResult = null },
            title = { Text(text = stringResource(R.string.settings_delete_done)) },
            text = { Text(text = message) },
            confirmButton = {
                TextButton(onClick = { deleteResult = null }) {
                    Text(text = stringResource(R.string.action_confirm))
                }
            },
        )
    }

    // 내보내기 실패 안내.
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
}

/** 설정 화면의 위험한 삭제 작업 종류. */
private enum class DeleteAction(
    val titleRes: Int,
    val messageRes: Int,
) {
    CLEANUP_EXPIRED(R.string.settings_cleanup_expired_title, R.string.settings_cleanup_expired_message),
    DELETE_SESSIONS(R.string.settings_delete_all_sessions_title, R.string.settings_delete_all_sessions_message),
    DELETE_ALL(R.string.settings_delete_all_title, R.string.settings_delete_all_message),
}

@Composable
private fun SettingsSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

private fun retentionPolicyLabel(policy: RetentionPolicy): Int = when (policy) {
    RetentionPolicy.ALL -> R.string.settings_retention_all
    RetentionPolicy.THIRTY_DAYS -> R.string.settings_retention_30
    RetentionPolicy.SEVEN_DAYS -> R.string.settings_retention_7
}
