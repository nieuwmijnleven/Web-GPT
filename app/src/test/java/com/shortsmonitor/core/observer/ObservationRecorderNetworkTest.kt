package com.shortsmonitor.core.observer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shortsmonitor.core.database.AppDatabase
import com.shortsmonitor.core.model.AutoVerdict
import com.shortsmonitor.core.model.EntryContext
import com.shortsmonitor.core.model.InsertionSource
import com.shortsmonitor.core.model.NetworkRequestKind
import com.shortsmonitor.core.model.SequenceEntryKind
import com.shortsmonitor.core.model.SequenceLineageRelation
import com.shortsmonitor.core.model.SequenceParseStatus
import com.shortsmonitor.core.model.SnapshotChangeReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ObservationRecorderNetworkTest {

    private lateinit var database: AppDatabase
    private lateinit var recorder: ObservationRecorder
    private var sessionId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        ObserverDiagnostics.reset()
        ObserverDiagnostics.documentStartSupported = true
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        recorder = ObservationRecorder(
            observedShortDao = database.observedShortDao(),
            exposureEventDao = database.exposureEventDao(),
            listSnapshotDao = database.listSnapshotDao(),
            insertionEventDao = database.insertionEventDao(),
            networkSequenceDao = database.networkSequenceDao(),
            networkSequenceItemDao = database.networkSequenceItemDao(),
            networkVideoRequestDao = database.networkVideoRequestDao(),
            sequenceLineageDao = database.sequenceLineageDao(),
            networkObserverStateDao = database.networkObserverStateDao(),
        )
        sessionId = database.observationSessionDao().insert(
            com.shortsmonitor.core.database.entity.ObservationSessionEntity(
                sessionId = "network-test-session",
                name = "네트워크 테스트 세션",
                status = com.shortsmonitor.core.model.SessionStatus.ACTIVE,
                startedAt = 1_000L,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
        ObserverDiagnostics.reset()
    }

    private fun item(videoId: String, position: Int, isCurrent: Boolean = position == 0) =
        NetworkSequenceItemInfo(
            position = position,
            videoId = videoId,
            entryKind = SequenceEntryKind.VIDEO,
            nonVideoKind = "",
            isCurrent = isCurrent,
            hasPlayerParams = true,
            hasContinuation = true,
            trackingHash = "track-$videoId",
            playerParamsHash = "pp-$videoId",
        )

    private fun sequenceResponse(
        ts: Long,
        vararg videoIds: String,
        correlationId: String = "corr-$ts",
    ) = ObserverMessage.NetworkSequenceResponse(
        seq = 1,
        ts = ts,
        pageUrl = "https://m.youtube.com/shorts/${videoIds.firstOrNull() ?: "a"}",
        correlationId = correlationId,
        currentVideoId = videoIds.firstOrNull() ?: "",
        sequenceHash = "seq-hash-$ts",
        continuationHash = "cont-$ts",
        trackingHash = "track-$ts",
        responseContextHash = "ctx-$ts",
        parserVersion = ShortsObserverScript.NETWORK_PARSER_VERSION,
        parseStatus = SequenceParseStatus.PARSED,
        detectedShape = "root_entries",
        warnings = emptyList(),
        items = videoIds.mapIndexed { index, id -> item(id, index) },
    )

    private fun videoRequest(ts: Long, videoId: String, kind: NetworkRequestKind = NetworkRequestKind.PLAYER) =
        ObserverMessage.NetworkVideoRequest(
            seq = 1,
            ts = ts,
            pageUrl = "https://m.youtube.com/shorts",
            correlationId = "r-$ts",
            requestKind = kind,
            videoId = videoId,
        )

    private val videoA = "aaa111bbb22"
    private val videoB = "ccc333ddd44"
    private val videoC = "eee555fff66"
    private val videoX = "xxx999yyy00"

    @Test
    fun sequenceResponse_storesSequenceAndItemsSeparatelyFromDom() = runBlocking {
        recorder.record(sessionId, sequenceResponse(ts = 100L, videoA, videoB, videoC))

        val sequences = database.networkSequenceDao().getBySession(sessionId)
        assertEquals(1, sequences.size)
        assertEquals(EntryContext.SHORTS_VIDEO, sequences[0].entryContext)
        assertEquals(videoA, sequences[0].currentVideoId)
        assertEquals(SequenceParseStatus.PARSED, sequences[0].parseStatus)

        val items = database.networkSequenceItemDao().getBySequence(sequences[0].id)
        assertEquals(3, items.size)
        assertEquals(videoB, items[1].videoId)
        assertEquals(1, items[1].position)
        assertTrue(items[0].isCurrent)

        // DOM 목록 스냅샷과 별도 저장된다.
        assertEquals(0, database.listSnapshotDao().getBySession(sessionId).size)
    }

    @Test
    fun secondSequence_recordsLineage() = runBlocking {
        recorder.record(sessionId, sequenceResponse(ts = 100L, videoA, videoB))
        recorder.record(sessionId, sequenceResponse(ts = 200L, videoA, videoB, videoC))

        val lineages = database.sequenceLineageDao().getBySession(sessionId)
        assertEquals(1, lineages.size)
        assertEquals(SequenceLineageRelation.SAME_FLOW, lineages[0].relation)
        assertNotNull(lineages[0].signalsJson)
    }

    @Test
    fun videoRequests_areStoredWithExpectedPosition() = runBlocking {
        recorder.record(sessionId, sequenceResponse(ts = 100L, videoA, videoB, videoC))
        recorder.record(sessionId, videoRequest(ts = 200L, videoB, NetworkRequestKind.REEL_ITEM_WATCH))
        recorder.record(sessionId, videoRequest(ts = 300L, videoC))

        val requests = database.networkVideoRequestDao().getBySession(sessionId)
        assertEquals(2, requests.size)
        assertEquals(1, requests[0].requestOrder)
        assertEquals(1, requests[0].expectedPosition) // videoB는 0-based 위치 1
        assertEquals(2, requests[1].requestOrder)
        assertEquals(2, requests[1].expectedPosition) // videoC는 0-based 위치 2
    }

    @Test
    fun networkInsertion_candidateThenConfirmed_withPlayerRequest() = runBlocking {
        // 기준 시퀀스
        recorder.record(sessionId, sequenceResponse(ts = 100L, videoA, videoB))
        // A→X→B: 후보 등록
        recorder.record(sessionId, sequenceResponse(ts = 200L, videoA, videoX, videoB))
        var events = database.insertionEventDao().getBySession(sessionId)
        assertEquals(1, events.size)
        assertEquals(AutoVerdict.CANDIDATE, events[0].autoVerdict)
        assertEquals(InsertionSource.NETWORK, events[0].source)
        assertEquals(videoX, events[0].newVideoId)

        // 후속 시퀀스에서 관계 유지
        recorder.record(sessionId, sequenceResponse(ts = 300L, videoA, videoX, videoB))
        events = database.insertionEventDao().getBySession(sessionId)
        // 아직 강화 증거가 없으므로 후보로 유지된다.
        assertEquals(AutoVerdict.CANDIDATE, events[0].autoVerdict)

        // 해당 영상의 player 요청 → 확정
        recorder.record(sessionId, videoRequest(ts = 400L, videoX))
        events = database.insertionEventDao().getBySession(sessionId)
        assertEquals(1, events.size)
        assertEquals(AutoVerdict.CONFIRMED, events[0].autoVerdict)
        assertNotNull(events[0].evidenceJson)
        assertNotNull(events[0].strengthenedByJson)
        assertTrue(events[0].strengthenedByJson!!.contains("player_request"))
    }

    @Test
    fun networkInsertion_endAddition_isNotRegistered() = runBlocking {
        recorder.record(sessionId, sequenceResponse(ts = 100L, videoA, videoB))
        recorder.record(sessionId, sequenceResponse(ts = 200L, videoA, videoB, videoX))
        recorder.record(sessionId, videoRequest(ts = 300L, videoX))

        assertEquals(0, database.insertionEventDao().getBySession(sessionId).size)
    }

    @Test
    fun observerState_isStoredWithRestrictedFlagWhenInitialMissed() = runBlocking {
        // 문서 시작 주입 미지원 상태를 재현한다.
        ObserverDiagnostics.documentStartSupported = false
        recorder.record(sessionId, sequenceResponse(ts = 100L, "a", "b"))

        val state = database.networkObserverStateDao().getBySession(sessionId)
        assertNotNull(state)
        assertTrue(state!!.missedInitialPossible)
        assertTrue(state.restricted)
    }

    @Test
    fun domStabilization_isTimeBased() = runBlocking {
        // DOM 스냅샷으로 후보를 등록한다.
        recorder.record(
            sessionId,
            ObserverMessage.ListSnapshot(
                seq = 1, ts = 100L, revision = 1,
                reason = SnapshotChangeReason.INITIAL,
                url = "https://m.youtube.com/shorts",
                shorts = listOf(
                    short("a"),
                    short("b"),
                ),
            ),
        )
        recorder.record(
            sessionId,
            ObserverMessage.ListSnapshot(
                seq = 2, ts = 200L, revision = 2,
                reason = SnapshotChangeReason.ITEM_ADDED,
                url = "https://m.youtube.com/shorts",
                shorts = listOf(
                    short("a"),
                    short("x"),
                    short("b"),
                ),
            ),
        )
        assertEquals(0, database.insertionEventDao().getBySession(sessionId).size)

        // 최소 대기 시간 전에는 확정하지 않는다.
        recorder.stabilizeDomCandidates(sessionId, now = 250L)
        assertEquals(0, database.insertionEventDao().getBySession(sessionId).size)

        // 최소 대기 시간(3초)이 지나면 마지막 목록으로 안정화를 재확인해 확정한다.
        recorder.stabilizeDomCandidates(sessionId, now = 4_000L)
        val events = database.insertionEventDao().getBySession(sessionId)
        assertEquals(1, events.size)
        assertEquals(AutoVerdict.CONFIRMED, events[0].autoVerdict)
    }

    private fun short(videoId: String) = ShortInfo(
        videoId = videoId,
        url = "https://youtube.com/shorts/$videoId",
        title = "제목 $videoId",
        channel = "채널",
        thumbnail = "https://i.ytimg.com/$videoId.jpg",
        identitySource = ShortIdentitySource.VIDEO_ID,
        identityKey = videoId,
    )
}
