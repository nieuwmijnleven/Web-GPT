package com.shortsmonitor.core.observer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shortsmonitor.core.database.AppDatabase
import com.shortsmonitor.core.model.AutoVerdict
import com.shortsmonitor.core.model.InsertionEvidence
import com.shortsmonitor.core.model.ShortIdentityStatus
import com.shortsmonitor.core.model.SnapshotChangeReason
import com.shortsmonitor.core.model.UserVerdict
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ObservationRecorderTest {

    private lateinit var database: AppDatabase
    private lateinit var recorder: ObservationRecorder
    private var sessionId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        recorder = ObservationRecorder(
            observedShortDao = database.observedShortDao(),
            exposureEventDao = database.exposureEventDao(),
            listSnapshotDao = database.listSnapshotDao(),
            insertionEventDao = database.insertionEventDao(),
        )
        sessionId = database.observationSessionDao().insert(
            com.shortsmonitor.core.database.entity.ObservationSessionEntity(
                sessionId = "test-session",
                name = "테스트 세션",
                status = com.shortsmonitor.core.model.SessionStatus.ACTIVE,
                startedAt = 1_000L,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun short(
        videoId: String,
        title: String = "제목 $videoId",
        identitySource: ShortIdentitySource = ShortIdentitySource.VIDEO_ID,
    ) = ShortInfo(
        videoId = videoId,
        url = "https://youtube.com/shorts/$videoId",
        title = title,
        channel = "채널",
        thumbnail = "https://i.ytimg.com/$videoId.jpg",
        identitySource = identitySource,
        identityKey = videoId,
    )

    private fun snapshot(
        ts: Long,
        shorts: List<ShortInfo>,
        reason: SnapshotChangeReason = SnapshotChangeReason.ITEM_ADDED,
        revision: Int = 1,
    ) = ObserverMessage.ListSnapshot(
        seq = 1,
        ts = ts,
        revision = revision,
        reason = reason,
        url = "https://m.youtube.com/shorts",
        shorts = shorts,
    )

    @Test
    fun listSnapshot_records_shorts_and_snapshot() = runBlocking {
        recorder.record(sessionId, snapshot(ts = 100L, shorts = listOf(short("a"), short("b"))))

        val shorts = database.observedShortDao().observeBySession(sessionId).first()
        assertEquals(2, shorts.size)
        val first = shorts.first { it.videoId == "a" }
        assertEquals(100L, first.firstSeenAt)
        assertEquals(100L, first.lastSeenAt)
        assertEquals("b", first.nextVideoId)
        assertTrue(first.prevVideoId == null)

        val snapshots = database.listSnapshotDao().observeBySession(sessionId).first()
        assertEquals(1, snapshots.size)
        assertEquals(SnapshotChangeReason.ITEM_ADDED, snapshots[0].changeReason)
        val ids = JSONArray(snapshots[0].videoIdsJson)
        assertEquals("a", ids.getString(0))
        assertEquals("b", ids.getString(1))
    }

    @Test
    fun same_video_re_render_is_not_duplicated() = runBlocking {
        recorder.record(sessionId, snapshot(ts = 100L, shorts = listOf(short("a"), short("b"))))
        // 같은 영상이 재렌더링된 두 번째 스냅샷 (제목만 변경)
        recorder.record(
            sessionId,
            snapshot(
                ts = 200L,
                shorts = listOf(short("a", title = "변경된 제목"), short("b")),
                reason = SnapshotChangeReason.ORDER_CHANGED,
                revision = 2,
            ),
        )

        val shorts = database.observedShortDao().observeBySession(sessionId).first()
        assertEquals("중복 저장되지 않아야 함", 2, shorts.size)
        val a = shorts.first { it.videoId == "a" }
        assertEquals("first_seen_at 보존", 100L, a.firstSeenAt)
        assertEquals("last_seen_at 갱신", 200L, a.lastSeenAt)
        assertEquals("변경된 제목", a.title)

        val snapshots = database.listSnapshotDao().observeBySession(sessionId).first()
        assertEquals(2, snapshots.size)
    }

    @Test
    fun active_short_creates_exposure_event_in_order() = runBlocking {
        recorder.record(
            sessionId,
            ObserverMessage.ActiveShortChanged(seq = 1, ts = 100L, short = short("a"), index = 0, count = 2),
        )
        recorder.record(
            sessionId,
            ObserverMessage.ActiveShortChanged(seq = 2, ts = 300L, short = short("b"), index = 1, count = 2),
        )

        val exposures = database.exposureEventDao().observeBySession(sessionId).first()
        assertEquals(2, exposures.size)
        assertEquals("a", exposures[0].videoId)
        assertEquals(1, exposures[0].exposureOrder)
        assertEquals("b", exposures[1].videoId)
        assertEquals(2, exposures[1].exposureOrder)
        // 첫 노출은 두 번째 노출 시점에 종료된다.
        assertEquals(300L, exposures[0].exposedUntil)
        assertTrue(exposures[1].exposedUntil == null)
    }

    @Test
    fun same_video_re_exposed_creates_new_event() = runBlocking {
        recorder.record(
            sessionId,
            ObserverMessage.ActiveShortChanged(seq = 1, ts = 100L, short = short("a"), index = 0, count = 1),
        )
        recorder.record(
            sessionId,
            ObserverMessage.ActiveShortChanged(seq = 2, ts = 200L, short = short("b"), index = 0, count = 2),
        )
        recorder.record(
            sessionId,
            ObserverMessage.ActiveShortChanged(seq = 3, ts = 300L, short = short("a"), index = 1, count = 2),
        )

        val exposures = database.exposureEventDao().observeBySession(sessionId).first()
        assertEquals("같은 영상 재노출은 새 이벤트", 3, exposures.size)
        assertEquals(3, exposures[2].exposureOrder)
        assertEquals("a", exposures[2].videoId)
    }

    @Test
    fun exposure_sets_activated_at_on_observed_short() = runBlocking {
        recorder.record(sessionId, snapshot(ts = 100L, shorts = listOf(short("a"))))
        recorder.record(
            sessionId,
            ObserverMessage.ActiveShortChanged(seq = 2, ts = 500L, short = short("a"), index = 0, count = 1),
        )

        val a = database.observedShortDao().getByVideoId(sessionId, "a")
        assertNotNull(a)
        assertEquals(500L, a!!.activatedAt)
    }

    @Test
    fun unidentifiable_short_is_recorded_with_temporary_id() = runBlocking {
        val unidentifiable = ShortInfo(
            videoId = "",
            url = "",
            title = "제목만 있음",
            channel = "채널",
            thumbnail = "",
            identitySource = ShortIdentitySource.HASH,
            identityKey = "title|channel|",
        )
        recorder.record(sessionId, snapshot(ts = 100L, shorts = listOf(unidentifiable)))

        val shorts = database.observedShortDao().observeBySession(sessionId).first()
        assertEquals(1, shorts.size)
        assertTrue(shorts[0].videoId.startsWith("tmp_"))
        assertEquals(ShortIdentityStatus.TEMPORARY, shorts[0].identityStatus)
    }

    @Test
    fun heartbeat_and_error_messages_are_ignored() = runBlocking {
        recorder.record(
            sessionId,
            ObserverMessage.Heartbeat(seq = 1, ts = 100L, revision = 0, shortCount = 0, activeVideoId = "", observerVersion = "1.0.0"),
        )
        recorder.record(
            sessionId,
            ObserverMessage.ObserverError(seq = 2, ts = 100L, code = "feed_not_found", message = "no feed"),
        )
        recorder.record(sessionId, ObserverMessage.DomRebuilt(seq = 3, ts = 100L, revision = 0))

        assertEquals(0, database.observedShortDao().countBySession(sessionId))
        assertEquals(0, database.listSnapshotDao().observeBySession(sessionId).first().size)
        assertEquals(0, database.exposureEventDao().countBySession(sessionId))
    }

    @Test
    fun stabilized_middle_insertion_creates_insertion_event() = runBlocking {
        // A→B 기준 목록 수립
        recorder.record(sessionId, snapshot(ts = 100L, shorts = listOf(short("a"), short("b")), reason = SnapshotChangeReason.INITIAL, revision = 1))
        // A→X→B: 후보 등록 (아직 확정되지 않음)
        recorder.record(
            sessionId,
            snapshot(ts = 200L, shorts = listOf(short("a"), short("x"), short("b")), reason = SnapshotChangeReason.ITEM_ADDED, revision = 2),
        )
        var events = database.insertionEventDao().observeBySession(sessionId).first()
        assertEquals("후보는 즉시 확정되지 않음", 0, events.size)

        // 같은 목록 유지 → 안정화 확정
        recorder.record(
            sessionId,
            snapshot(ts = 300L, shorts = listOf(short("a"), short("x"), short("b")), reason = SnapshotChangeReason.ITEM_ADDED, revision = 3),
        )
        events = database.insertionEventDao().observeBySession(sessionId).first()
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("x", event.newVideoId)
        assertEquals("a", event.prevVideoId)
        assertEquals("b", event.nextVideoId)
        assertEquals(AutoVerdict.CONFIRMED, event.autoVerdict)
        assertEquals(UserVerdict.PENDING, event.userVerdict)
        assertNotNull(event.evidenceJson)
        val evidence = InsertionEvidence.fromJson(event.evidenceJson)
        assertTrue(evidence.stabilized)
        assertTrue(evidence.notInPreviousList)
    }

    @Test
    fun end_of_list_addition_does_not_create_insertion_event() = runBlocking {
        recorder.record(sessionId, snapshot(ts = 100L, shorts = listOf(short("a"), short("b")), reason = SnapshotChangeReason.INITIAL, revision = 1))
        recorder.record(
            sessionId,
            snapshot(ts = 200L, shorts = listOf(short("a"), short("b"), short("x")), reason = SnapshotChangeReason.ITEM_ADDED, revision = 2),
        )
        recorder.record(
            sessionId,
            snapshot(ts = 300L, shorts = listOf(short("a"), short("b"), short("x")), reason = SnapshotChangeReason.ITEM_ADDED, revision = 3),
        )

        val events = database.insertionEventDao().observeBySession(sessionId).first()
        assertEquals("목록 끝 추가는 의심 이벤트가 아님", 0, events.size)
    }
}
