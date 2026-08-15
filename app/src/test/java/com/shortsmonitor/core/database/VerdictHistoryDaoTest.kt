package com.shortsmonitor.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shortsmonitor.core.database.entity.InsertionEventEntity
import com.shortsmonitor.core.database.entity.ObservationSessionEntity
import com.shortsmonitor.core.database.entity.VerdictHistoryEntity
import com.shortsmonitor.core.model.AutoVerdict
import com.shortsmonitor.core.model.SessionStatus
import com.shortsmonitor.core.model.UserVerdict
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 사용자 판정 변경 이력(K단계) DAO 통합 테스트.
 * 판정 변경 시 이력이 쌓이고, 이벤트 삭제 시 이력도 함께 정리되는지 검증한다.
 */
@RunWith(RobolectricTestRunner::class)
class VerdictHistoryDaoTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun insertEvent(): Long = runBlocking {
        val sessionId = database.observationSessionDao().insert(
            ObservationSessionEntity(
                sessionId = "history-test",
                name = "이력 테스트",
                status = SessionStatus.COMPLETED,
                startedAt = 1_000L,
            ),
        )
        database.insertionEventDao().insert(
            InsertionEventEntity(
                sessionId = sessionId,
                newVideoId = "x",
                prevVideoId = "a",
                nextVideoId = "b",
                detectedAt = 2_000L,
                autoVerdict = AutoVerdict.CONFIRMED,
                userVerdict = UserVerdict.PENDING,
            ),
        )
    }

    @Test
    fun verdict_changes_are_recorded_in_order() = runBlocking {
        val eventId = insertEvent()
        val dao = database.verdictHistoryDao()

        dao.insert(VerdictHistoryEntity(eventId = eventId, userVerdict = UserVerdict.SUSPECTED, changedAt = 3_000L))
        dao.insert(VerdictHistoryEntity(eventId = eventId, userVerdict = UserVerdict.FALSE_POSITIVE, userMemo = "오탐", changedAt = 4_000L))

        val history = dao.observeByEvent(eventId).first()
        // 최신순으로 반환된다.
        assertEquals(2, history.size)
        assertEquals(UserVerdict.FALSE_POSITIVE, history[0].userVerdict)
        assertEquals("오탐", history[0].userMemo)
        assertEquals(UserVerdict.SUSPECTED, history[1].userVerdict)
    }

    @Test
    fun history_is_deleted_with_event() = runBlocking {
        val eventId = insertEvent()
        val dao = database.verdictHistoryDao()
        dao.insert(VerdictHistoryEntity(eventId = eventId, userVerdict = UserVerdict.SUSPECTED, changedAt = 3_000L))

        assertEquals(1, dao.observeByEvent(eventId).first().size)

        // 세션을 삭제하면 이벤트 → 이력이 FK CASCADE로 함께 삭제된다.
        val sessionId = database.insertionEventDao().observeById(eventId).first()!!.sessionId
        database.observationSessionDao().deleteById(sessionId)
        assertTrue(dao.observeByEvent(eventId).first().isEmpty())
    }
}
