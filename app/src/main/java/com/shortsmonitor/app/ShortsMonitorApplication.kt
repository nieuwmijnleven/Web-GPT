package com.shortsmonitor.app

import android.app.Application
import com.shortsmonitor.core.database.AppDatabase
import com.shortsmonitor.core.database.DatabaseProvider
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.settings.SettingsRepository

/**
 * 앱 전역 의존성(DB, 설정 저장소)을 노출하는 Application.
 * Stage A에서는 수동 주입(ServiceLocator) 방식으로 최소 구성만 제공한다.
 */
class ShortsMonitorApplication : Application() {

    val database: AppDatabase by lazy { DatabaseProvider.get(this) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository.create(this) }

    override fun onCreate() {
        super.onCreate()
        ShortsLog.d("Application started")
    }
}
