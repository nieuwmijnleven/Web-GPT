package com.shortsmonitor.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Room 마이그레이션/스키마 테스트.
 * 버전 1 초기 스키마가 export된 스키마와 일치하는지 검증한다.
 * 이후 스키마 변경 시 v1 → vN 마이그레이션 테스트를 추가한다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {

    private val testDb = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName!!,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun validateInitialSchema_v1() {
        helper.createDatabase(testDb, 1).apply { close() }
        helper.runMigrationsAndValidate(testDb, 1, true, *AppDatabase.ALL_MIGRATIONS)
    }

    @Test
    fun migrateV1ToV2_addsObservedShortColumns() {
        helper.createDatabase(testDb, 1).apply { close() }
        helper.runMigrationsAndValidate(testDb, 2, true, *AppDatabase.ALL_MIGRATIONS)
    }

    @Test
    fun migrateToV3_addsInsertionEventEvidenceColumn() {
        helper.createDatabase(testDb, 2).apply { close() }
        helper.runMigrationsAndValidate(testDb, 3, true, *AppDatabase.ALL_MIGRATIONS)
    }

    @Test
    fun migrateToV4_addsVerdictHistoryTable() {
        helper.createDatabase(testDb, 3).apply { close() }
        helper.runMigrationsAndValidate(testDb, 4, true, *AppDatabase.ALL_MIGRATIONS)
    }

    @Test
    fun migrateToV5_addsNetworkObservationTables() {
        helper.createDatabase(testDb, 4).apply { close() }
        helper.runMigrationsAndValidate(testDb, 5, true, *AppDatabase.ALL_MIGRATIONS)
    }
}
