package com.shortsmonitor.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsRepositoryTest {

    @Test
    fun defaults_are_applied_when_no_value_stored() = withTempDataStore { repo ->
        assertFalse(repo.onboardingCompleted.first())
        assertTrue(repo.saveListSnapshots.first())
    }

    @Test
    fun values_persist_across_restart() = runBlocking {
        val file = File.createTempFile("shorts-monitor-settings", ".preferences_pb")
        try {
            // 첫 실행: 값 저장 후 인스턴스 종료
            val first = newDataStore(file)
            SettingsRepository(first.store).apply {
                setOnboardingCompleted(true)
                setSaveListSnapshots(false)
            }
            first.scope.cancel()

            // 재실행: 같은 파일로 새 저장소를 열어 값이 유지되는지 확인
            val second = newDataStore(file)
            SettingsRepository(second.store).apply {
                assertTrue(onboardingCompleted.first())
                assertFalse(saveListSnapshots.first())
            }
            second.scope.cancel()
        } finally {
            file.delete()
        }
    }

    private fun withTempDataStore(block: suspend (SettingsRepository) -> Unit) = runBlocking {
        val file = File.createTempFile("shorts-monitor-settings", ".preferences_pb")
        try {
            val holder = newDataStore(file)
            block(SettingsRepository(holder.store))
            holder.scope.cancel()
        } finally {
            file.delete()
        }
    }

    private fun newDataStore(file: File): DataStoreHolder {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return DataStoreHolder(store, scope)
    }

    private data class DataStoreHolder(
        val store: DataStore<Preferences>,
        val scope: CoroutineScope,
    )
}
