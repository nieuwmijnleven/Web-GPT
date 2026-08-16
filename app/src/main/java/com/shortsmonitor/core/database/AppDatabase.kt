package com.shortsmonitor.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.shortsmonitor.core.database.dao.BrowserProfileDao
import com.shortsmonitor.core.database.dao.ExposureEventDao
import com.shortsmonitor.core.database.dao.InsertionEventDao
import com.shortsmonitor.core.database.dao.ListSnapshotDao
import com.shortsmonitor.core.database.dao.NetworkObserverStateDao
import com.shortsmonitor.core.database.dao.NetworkSequenceDao
import com.shortsmonitor.core.database.dao.NetworkSequenceItemDao
import com.shortsmonitor.core.database.dao.NetworkVideoRequestDao
import com.shortsmonitor.core.database.dao.ObservedShortDao
import com.shortsmonitor.core.database.dao.ObservationSessionDao
import com.shortsmonitor.core.database.dao.SequenceLineageDao
import com.shortsmonitor.core.database.dao.VerdictHistoryDao
import com.shortsmonitor.core.database.entity.BrowserProfileEntity
import com.shortsmonitor.core.database.entity.ExposureEventEntity
import com.shortsmonitor.core.database.entity.InsertionEventEntity
import com.shortsmonitor.core.database.entity.ListSnapshotEntity
import com.shortsmonitor.core.database.entity.NetworkObserverStateEntity
import com.shortsmonitor.core.database.entity.NetworkSequenceEntity
import com.shortsmonitor.core.database.entity.NetworkSequenceItemEntity
import com.shortsmonitor.core.database.entity.NetworkVideoRequestEntity
import com.shortsmonitor.core.database.entity.ObservedShortEntity
import com.shortsmonitor.core.database.entity.ObservationSessionEntity
import com.shortsmonitor.core.database.entity.SequenceLineageEntity
import com.shortsmonitor.core.database.entity.VerdictHistoryEntity

/**
 * shorts monitor 관찰 기록 데이터베이스.
 * 스키마 변경 시 마이그레이션을 [ALL_MIGRATIONS]에 추가한다.
 *
 * v5: 네트워크 시퀀스 분석 추가.
 * - DOM 목록 스냅샷(list_snapshot)과 별도로 서버 시퀀스(network_sequence)를 저장한다.
 * - 시퀀스 항목·개별 영상 요청·계보·관찰기 상태 테이블을 추가한다.
 * - insertion_event에 근거 출처(source)·네트워크 시퀀스·강화 증거 컬럼을 추가한다.
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
        NetworkSequenceEntity::class,
        NetworkSequenceItemEntity::class,
        NetworkVideoRequestEntity::class,
        SequenceLineageEntity::class,
        NetworkObserverStateEntity::class,
    ],
    version = 5,
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

    abstract fun networkSequenceDao(): NetworkSequenceDao

    abstract fun networkSequenceItemDao(): NetworkSequenceItemDao

    abstract fun networkVideoRequestDao(): NetworkVideoRequestDao

    abstract fun sequenceLineageDao(): SequenceLineageDao

    abstract fun networkObserverStateDao(): NetworkObserverStateDao

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

        /**
         * v4 → v5: 네트워크 시퀀스 분석.
         * 파괴적 마이그레이션 없이 새 테이블과 새 컬럼만 추가한다.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `network_sequence` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`session_id` INTEGER NOT NULL, " +
                        "`correlation_id` TEXT, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`page_url` TEXT, " +
                        "`current_video_id` TEXT, " +
                        "`entry_context` TEXT NOT NULL, " +
                        "`sequence_hash` TEXT, " +
                        "`continuation_hash` TEXT, " +
                        "`parser_version` TEXT, " +
                        "`parse_status` TEXT NOT NULL, " +
                        "`warnings_json` TEXT, " +
                        "`lineage_id` INTEGER, " +
                        "FOREIGN KEY(`session_id`) REFERENCES `observation_session`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_sequence_session_id` ON `network_sequence` (`session_id`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `network_sequence_item` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sequence_id` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`video_id` TEXT, " +
                        "`entry_kind` TEXT NOT NULL, " +
                        "`non_video_kind` TEXT, " +
                        "`is_current` INTEGER NOT NULL, " +
                        "`has_player_params` INTEGER NOT NULL, " +
                        "`has_continuation` INTEGER NOT NULL, " +
                        "`tracking_hash` TEXT, " +
                        "`player_params_hash` TEXT, " +
                        "`continuation_hash` TEXT, " +
                        "FOREIGN KEY(`sequence_id`) REFERENCES `network_sequence`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_sequence_item_sequence_id` ON `network_sequence_item` (`sequence_id`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `network_video_request` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`session_id` INTEGER NOT NULL, " +
                        "`video_id` TEXT, " +
                        "`request_kind` TEXT NOT NULL, " +
                        "`requested_at` INTEGER NOT NULL, " +
                        "`page_url` TEXT, " +
                        "`sequence_id` INTEGER, " +
                        "`expected_position` INTEGER, " +
                        "`request_order` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`session_id`) REFERENCES `observation_session`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`sequence_id`) REFERENCES `network_sequence`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE SET NULL )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_video_request_session_id` ON `network_video_request` (`session_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_video_request_video_id` ON `network_video_request` (`video_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_video_request_sequence_id` ON `network_video_request` (`sequence_id`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sequence_lineage` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`session_id` INTEGER NOT NULL, " +
                        "`from_sequence_id` INTEGER NOT NULL, " +
                        "`to_sequence_id` INTEGER NOT NULL, " +
                        "`relation` TEXT NOT NULL, " +
                        "`signals_json` TEXT, " +
                        "`decided_at` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`session_id`) REFERENCES `observation_session`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sequence_lineage_session_id` ON `sequence_lineage` (`session_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sequence_lineage_from_sequence_id` ON `sequence_lineage` (`from_sequence_id`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `network_observer_state` (" +
                        "`session_id` INTEGER NOT NULL, " +
                        "`installed_at` INTEGER, " +
                        "`document_start_supported` INTEGER NOT NULL, " +
                        "`missed_initial_possible` INTEGER NOT NULL, " +
                        "`restricted` INTEGER NOT NULL, " +
                        "`first_request_at` INTEGER, " +
                        "`last_sequence_request_at` INTEGER, " +
                        "`last_sequence_response_at` INTEGER, " +
                        "`last_sequence_video_count` INTEGER NOT NULL, " +
                        "`last_parse_status` TEXT NOT NULL, " +
                        "`current_lineage` TEXT, " +
                        "`warnings_json` TEXT, " +
                        "PRIMARY KEY(`session_id`), " +
                        "FOREIGN KEY(`session_id`) REFERENCES `observation_session`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )

                // 기존 DOM 기반 이벤트는 기본값 'DOM'으로 유지된다.
                db.execSQL("ALTER TABLE insertion_event ADD COLUMN source TEXT NOT NULL DEFAULT 'DOM'")
                db.execSQL("ALTER TABLE insertion_event ADD COLUMN network_before_sequence_id INTEGER")
                db.execSQL("ALTER TABLE insertion_event ADD COLUMN network_after_sequence_id INTEGER")
                db.execSQL("ALTER TABLE insertion_event ADD COLUMN strengthened_by_json TEXT")
            }
        }

        /** 버전별 마이그레이션 목록. 버전 1은 초기 스키마이므로 비어 있다. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
        )
    }
}
