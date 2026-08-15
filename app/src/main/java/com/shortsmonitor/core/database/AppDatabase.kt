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
import com.shortsmonitor.core.database.dao.VerdictHistoryDao
import com.shortsmonitor.core.database.entity.BrowserProfileEntity
import com.shortsmonitor.core.database.entity.ExposureEventEntity
import com.shortsmonitor.core.database.entity.InsertionEventEntity
import com.shortsmonitor.core.database.entity.ListSnapshotEntity
import com.shortsmonitor.core.database.entity.ObservedShortEntity
import com.shortsmonitor.core.database.entity.ObservationSessionEntity
import com.shortsmonitor.core.database.entity.VerdictHistoryEntity

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
        VerdictHistoryEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun observationSessionDao(): ObservationSessionDao

    abstract fun observedShortDao(): ObservedShortDao

    abstract fun exposureEventDao(): ExposureEventDao

    abstract fun listSnapshotDao(): ListSnapshotDao

    abstract fun insertionEventDao(): InsertionEventDao

    abstract fun browserProfileDao(): BrowserProfileDao

    abstract fun verdictHistoryDao(): VerdictHistoryDao

    companion object {
        const val NAME = "shorts_monitor.db"

        /** v1 → v2: observed_short에 활성화 시각·이전/다음 영상 컬럼 추가 (G단계 저장 정보). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE observed_short ADD COLUMN activated_at INTEGER")
                db.execSQL("ALTER TABLE observed_short ADD COLUMN prev_video_id TEXT")
                db.execSQL("ALTER TABLE observed_short ADD COLUMN next_video_id TEXT")
            }
        }

        /** v2 → v3: insertion_event에 판정 근거 JSON 컬럼 추가 (H단계). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE insertion_event ADD COLUMN evidence_json TEXT")
            }
        }

        /** v3 → v4: 사용자 판정 변경 이력 테이블 추가 (K단계). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `verdict_history` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`event_id` INTEGER NOT NULL, " +
                        "`user_verdict` TEXT NOT NULL, " +
                        "`user_memo` TEXT, " +
                        "`changed_at` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`event_id`) REFERENCES `insertion_event`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_verdict_history_event_id` ON `verdict_history` (`event_id`)")
            }
        }

        /** 버전별 마이그레이션 목록. 버전 1은 초기 스키마이므로 비어 있다. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
