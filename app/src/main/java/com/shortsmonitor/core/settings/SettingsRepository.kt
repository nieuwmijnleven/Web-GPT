package com.shortsmonitor.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "shorts_monitor_settings",
)

/**
 * DataStore 기반 앱 설정 저장소.
 * 앱 재실행 후에도 설정값이 유지되는 것이 목적이다.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    /** 온보딩 완료 여부 (Stage C에서 사용) */
    val onboardingCompleted: Flow<Boolean> = dataStore.data.map { it[Keys.ONBOARDING_COMPLETED] ?: false }

    /** 관찰 설정: 목록 스냅샷 저장 여부 */
    val saveListSnapshots: Flow<Boolean> = dataStore.data.map { it[Keys.SAVE_LIST_SNAPSHOTS] ?: true }

    suspend fun setOnboardingCompleted(value: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = value }
    }

    suspend fun setSaveListSnapshots(value: Boolean) {
        dataStore.edit { it[Keys.SAVE_LIST_SNAPSHOTS] = value }
    }

    companion object {
        fun create(context: Context): SettingsRepository =
            SettingsRepository(context.applicationContext.settingsDataStore)
    }

    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val SAVE_LIST_SNAPSHOTS = booleanPreferencesKey("save_list_snapshots")
    }
}
