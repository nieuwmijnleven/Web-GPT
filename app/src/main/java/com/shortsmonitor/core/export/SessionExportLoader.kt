package com.shortsmonitor.core.export

import com.shortsmonitor.core.database.AppDatabase
import com.shortsmonitor.core.database.entity.BrowserProfileEntity
import com.shortsmonitor.core.database.entity.ObservationSessionEntity

/**
 * 세션 내보내기 데이터 로더 (N단계).
 *
 * [SessionExportBuilder]가 직렬화할 데이터를 DB에서 조회한다.
 * 프로필은 세션에 직접 연결되지 않으므로 내보내기 시점의 활성 프로필
 * (마지막 사용 시각이 가장 최근인 프로필)을 사용한다.
 */
object SessionExportLoader {

    /** 한 세션의 내보내기 데이터를 조회한다. 세션이 없으면 null. */
    suspend fun loadForSession(database: AppDatabase, sessionId: Long): SessionExportData? {
        val session = database.observationSessionDao().getById(sessionId) ?: return null
        return build(database, session, activeProfile(database))
    }

    /** 전체 세션의 내보내기 데이터를 조회한다 (관찰 홈 '로그 내보내기'). */
    suspend fun loadAll(database: AppDatabase): List<SessionExportData> {
        val profile = activeProfile(database)
        return database.observationSessionDao().getAll().map { session ->
            build(database, session, profile)
        }
    }

    private suspend fun activeProfile(database: AppDatabase): BrowserProfileEntity? =
        database.browserProfileDao().getAll().maxByOrNull { it.lastUsedAt ?: 0L }

    private suspend fun build(
        database: AppDatabase,
        session: ObservationSessionEntity,
        profile: BrowserProfileEntity?,
    ): SessionExportData = SessionExportData(
        session = session,
        profile = profile,
        shorts = database.observedShortDao().getBySession(session.id),
        exposures = database.exposureEventDao().getBySession(session.id),
        snapshots = database.listSnapshotDao().getBySession(session.id),
        events = database.insertionEventDao().getBySession(session.id),
    )
}
