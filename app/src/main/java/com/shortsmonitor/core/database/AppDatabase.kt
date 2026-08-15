package com.shortsmonitor.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.shortsmonitor.core.database.dao.BrowserProfileDao
import com.shortsmonitor.core.database.dao.ExposureEventDao
import com.shortsmonitor.core.database.dao.InsertionEventDao
import com.shortsmonitor.core.database.dao.ListSnapshotDao
import com.shortsmonitor.core.database.dao.ObservedShortDao
import com.shortsmonitor.core.database.dao.ObservationSessionDao
import com.shortsmonitor.core.database.entity.BrowserProfileEntity
import com.shortsmonitor.core.database.entity.ExposureEventEntity
import com.shortsmonitor.core.database.entity.InsertionEventEntity
import com.shortsmonitor.core.database.entity.ListSnapshotEntity
import com.shortsmonitor.core.database.entity.ObservedShortEntity
import com.shortsmonitor.core.database.entity.ObservationSessionEntity

/**
 * shorts monitor 관찰 기록 데이터베이스.
 * 스키마 변경 시 마이그레이션을 [ALL_MIGRATIONS]에 추가한다.
 */
@Database(
    entities = [
        ObservationSessionEntity::class,
        ObservedShortEntity::class,
        ExposureEventEntity::class,
        ListSnapshotEntity::class,
        InsertionEventEntity::class,
        BrowserProfileEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun observationSessionDao(): ObservationSessionDao

    abstract fun observedShortDao(): ObservedShortDao

    abstract fun exposureEventDao(): ExposureEventDao

    abstract fun listSnapshotDao(): ListSnapshotDao

    abstract fun insertionEventDao(): InsertionEventDao

    abstract fun browserProfileDao(): BrowserProfileDao

    companion object {
        const val NAME = "shorts_monitor.db"

        /** 버전별 마이그레이션 목록. 버전 1은 초기 스키마이므로 비어 있다. */
        val ALL_MIGRATIONS: Array<Migration> = emptyArray()
    }
}
