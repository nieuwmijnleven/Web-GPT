package com.shortsmonitor.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "shorts_monitor_settings",
)

/** 기록 보존 정책 (O단계 데이터 설정). */ enum class RetentionPolicy(val key: String) {
    ALL("ALL"),
    THIRTY_DAYS("THIRTY_DAYS"),
    SEVEN_DAYS("SEVEN_DAYS"),
    ;

    companion object {
        fun fromKey(key: String?): RetentionPolicy =
            entries.firstOrNull { it.key == key } ?: ALL
    }
}

/**
 * DataStore 기반 앱 설정 저장소.
 * 앱 재실행 후에도 설정값이 유지되는 것이 목적이다.
 *
 * 관찰 설정·알림 설정·데이터 설정(O단계)을 제공한다.
 * 각 설정은 Flow로 노출되며, 변경 즉시 다음 관찰 메시지부터 반영된다.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    /** 온보딩 완료 여부 (Stage C에서 사용) */
    val onboardingCompleted: Flow<Boolean> = dataStore.data.map { it[Keys.ONBOARDING_COMPLETED] ?: false }

    // --- 관찰 설정 ---

    /** 관찰 설정: 목록 스냅샷 저장 여부 */
    val saveListSnapshots: Flow<Boolean> = dataStore.data.map { it[Keys.SAVE_LIST_SNAPSHOTS] ?: true }

    /** 관찰 설정: 의심 후보 안정화 (다음 스냅샷 확인 후 알림) */
    val stabilizeCandidates: Flow<Boolean> = dataStore.data.map { it[Keys.STABILIZE_CANDIDATES] ?: true }

    /** 관찰 설정: 메타데이터(제목·채널명) 저장 여부 */
    val saveMetadata: Flow<Boolean> = dataStore.data.map { it[Keys.SAVE_METADATA] ?: true }

    /** 관찰 설정: 썸네일 주소 저장 여부 */
    val saveThumbnails: Flow<Boolean> = dataStore.data.map { it[Keys.SAVE_THUMBNAILS] ?: true }

    // --- 알림 설정 ---

    /** 알림 설정: 앱 내부 배너 (관찰 중 알림 카드) */
    val inAppBanner: Flow<Boolean> = dataStore.data.map { it[Keys.IN_APP_BANNER] ?: true }

    /** 알림 설정: 시스템 알림 사용 여부 */
    val systemNotifications: Flow<Boolean> = dataStore.data.map { it[Keys.SYSTEM_NOTIFICATIONS] ?: false }

    /** 알림 설정: 진동 */
    val vibration: Flow<Boolean> = dataStore.data.map { it[Keys.VIBRATION] ?: true }

    /** 알림 설정: 오류 알림 */
    val errorNotifications: Flow<Boolean> = dataStore.data.map { it[Keys.ERROR_NOTIFICATIONS] ?: true }

    /** 알림 설정: 의심 이벤트 알림 */
    val suspectedEventNotifications: Flow<Boolean> = dataStore.data.map { it[Keys.EVENT_NOTIFICATIONS] ?: true }

    // --- 데이터 설정 ---

    /** 데이터 설정: 기록 보존 정책 */
    val retentionPolicy: Flow<RetentionPolicy> =
        dataStore.data.map { RetentionPolicy.fromKey(it[Keys.RETENTION_POLICY]) }

    suspend fun setOnboardingCompleted(value: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = value }
    }

    suspend fun setSaveListSnapshots(value: Boolean) {
        dataStore.edit { it[Keys.SAVE_LIST_SNAPSHOTS] = value }
    }

    suspend fun setStabilizeCandidates(value: Boolean) {
        dataStore.edit { it[Keys.STABILIZE_CANDIDATES] = value }
    }

    suspend fun setSaveMetadata(value: Boolean) {
        dataStore.edit { it[Keys.SAVE_METADATA] = value }
    }

    suspend fun setSaveThumbnails(value: Boolean) {
        dataStore.edit { it[Keys.SAVE_THUMBNAILS] = value }
    }

    suspend fun setInAppBanner(value: Boolean) {
        dataStore.edit { it[Keys.IN_APP_BANNER] = value }
    }

    suspend fun setSystemNotifications(value: Boolean) {
        dataStore.edit { it[Keys.SYSTEM_NOTIFICATIONS] = value }
    }

    suspend fun setVibration(value: Boolean) {
        dataStore.edit { it[Keys.VIBRATION] = value }
    }

    suspend fun setErrorNotifications(value: Boolean) {
        dataStore.edit { it[Keys.ERROR_NOTIFICATIONS] = value }
    }

    suspend fun setSuspectedEventNotifications(value: Boolean) {
        dataStore.edit { it[Keys.EVENT_NOTIFICATIONS] = value }
    }

    suspend fun setRetentionPolicy(value: RetentionPolicy) {
        dataStore.edit { it[Keys.RETENTION_POLICY] = value.key }
    }

    companion object {
        fun create(context: Context): SettingsRepository =
            SettingsRepository(context.applicationContext.settingsDataStore)
    }

    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val SAVE_LIST_SNAPSHOTS = booleanPreferencesKey("save_list_snapshots")
        val STABILIZE_CANDIDATES = booleanPreferencesKey("stabilize_candidates")
        val SAVE_METADATA = booleanPreferencesKey("save_metadata")
        val SAVE_THUMBNAILS = booleanPreferencesKey("save_thumbnails")
        val IN_APP_BANNER = booleanPreferencesKey("in_app_banner")
        val SYSTEM_NOTIFICATIONS = booleanPreferencesKey("system_notifications")
        val VIBRATION = booleanPreferencesKey("vibration")
        val ERROR_NOTIFICATIONS = booleanPreferencesKey("error_notifications")
        val EVENT_NOTIFICATIONS = booleanPreferencesKey("event_notifications")
        val RETENTION_POLICY = stringPreferencesKey("retention_policy")
    }
}
