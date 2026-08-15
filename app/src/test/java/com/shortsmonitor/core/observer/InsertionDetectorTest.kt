package com.shortsmonitor.core.observer

import com.shortsmonitor.core.model.InsertionEvidence
import com.shortsmonitor.core.model.SnapshotChangeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 중간 삽입 의심 탐지 엔진 단위 테스트.
 * 고정 목록 데이터(고정 HTML에서 추출한 영상 식별값 배열)를 순서대로 주입해 검증한다.
 * 판정 근거 JSON 검증을 위해 Robolectric을 사용한다.
 */
@RunWith(RobolectricTestRunner::class)
class InsertionDetectorTest {

    private val detector = InsertionDetector()

    private fun process(
        list: List<String>,
        reason: SnapshotChangeReason = SnapshotChangeReason.ITEM_ADDED,
        snapshotId: Long,
        ts: Long = 1_000L,
    ) = detector.process(
        sessionId = 1L,
        videoIds = list,
        reason = reason,
        snapshotId = snapshotId,
        ts = ts,
    )

    /** A→B 목록에서 A→X→B로 바뀌는 기본 중간 삽입 시나리오를 재현한다. */
    private fun runBasicInsertion(): List<InsertionDetector.DetectedInsertion> {
        process(listOf("a", "b"), SnapshotChangeReason.INITIAL, snapshotId = 1)
        process(listOf("a", "x", "b"), snapshotId = 2)
        return process(listOf("a", "x", "b"), snapshotId = 3)
    }

    @Test
    fun middleInsertion_isConfirmedAfterStabilization() {
        val events = runBasicInsertion()
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("x", event.newVideoId)
        assertEquals("a", event.prevVideoId)
        assertEquals("b", event.nextVideoId)
        // 변경 전 스냅샷(A→B)과 변경 후 스냅샷(A→X→B)이 기록된다.
        assertEquals(1L, event.beforeSnapshotId)
        assertEquals(2L, event.afterSnapshotId)
    }

    @Test
    fun candidate_isNotConfirmedOnFirstSnapshot() {
        process(listOf("a", "b"), SnapshotChangeReason.INITIAL, snapshotId = 1)
        val events = process(listOf("a", "x", "b"), snapshotId = 2)
        // 후보 발견 즉시 알리지 않는다 — 다음 스냅샷에서 안정화를 확인한다.
        assertTrue(events.isEmpty())
    }

    @Test
    fun endOfListAddition_isNotDetected() {
        process(listOf("a", "b"), SnapshotChangeReason.INITIAL, snapshotId = 1)
        // X가 목록 끝에 추가된 경우
        val first = process(listOf("a", "b", "x"), snapshotId = 2)
        val second = process(listOf("a", "b", "x"), snapshotId = 3)
        assertTrue(first.isEmpty())
        assertTrue(second.isEmpty())
    }

    @Test
    fun startOfListAddition_isNotDetected() {
        process(listOf("a", "b"), SnapshotChangeReason.INITIAL, snapshotId = 1)
        // X가 목록 앞에 추가된 경우 (앞 항목이 없으므로 중간 삽입이 아니다)
        val first = process(listOf("x", "a", "b"), snapshotId = 2)
        val second = process(listOf("x", "a", "b"), snapshotId = 3)
        assertTrue(first.isEmpty())
        assertTrue(second.isEmpty())
    }

    @Test
    fun candidate_isDroppedWhenRelationBreaks() {
        process(listOf("a", "b"), SnapshotChangeReason.INITIAL, snapshotId = 1)
        process(listOf("a", "x", "b"), snapshotId = 2)
        // X가 사라지면 앞뒤 관계가 유지되지 않으므로 후보를 폐기한다.
        val events = process(listOf("a", "b"), SnapshotChangeReason.ITEM_REMOVED, snapshotId = 3)
        assertTrue(events.isEmpty())
    }

    @Test
    fun domRebuild_isNotMistakenForInsertion() {
        process(listOf("a", "b"), SnapshotChangeReason.INITIAL, snapshotId = 1)
        // 후보 등록 후 DOM 전체 재구성이 오면 탐지하지 않는다.
        process(listOf("a", "x", "b"), snapshotId = 2)
        val events = process(listOf("a", "x", "b"), SnapshotChangeReason.DOM_REBUILT, snapshotId = 3)
        assertTrue(events.isEmpty())
    }

    @Test
    fun fullReload_resetsBaselineWithoutDetection() {
        process(listOf("a", "b"), SnapshotChangeReason.INITIAL, snapshotId = 1)
        // 전체 페이지 재로드: 기준 목록만 교체하고 탐지하지 않는다.
        val events = process(listOf("a", "x", "b"), SnapshotChangeReason.FULL_RELOAD, snapshotId = 2)
        assertTrue(events.isEmpty())
    }

    @Test
    fun profileChange_isNotDetected() {
        process(listOf("a", "b"), SnapshotChangeReason.INITIAL, snapshotId = 1)
        val events = process(listOf("a", "x", "b"), SnapshotChangeReason.PROFILE_CHANGED, snapshotId = 2)
        assertTrue(events.isEmpty())
    }

    @Test
    fun sessionReset_isNotDetected() {
        process(listOf("a", "b"), SnapshotChangeReason.INITIAL, snapshotId = 1)
        val events = process(listOf("a", "x", "b"), SnapshotChangeReason.SESSION_RESET, snapshotId = 2)
        assertTrue(events.isEmpty())
    }

    @Test
    fun duplicateEvent_isNotCreatedRepeatedly() {
        process(listOf("a", "b"), SnapshotChangeReason.INITIAL, snapshotId = 1)
        process(listOf("a", "x", "b"), snapshotId = 2)
        val first = process(listOf("a", "x", "b"), snapshotId = 3)
        assertEquals(1, first.size)
        // 동일 (신규·앞·뒤) 키는 이후 스냅샷에서 다시 생성하지 않는다.
        val second = process(listOf("a", "x", "b"), snapshotId = 4)
        assertTrue(second.isEmpty())
        val third = process(listOf("a", "x", "b"), snapshotId = 5)
        assertTrue(third.isEmpty())
    }

    @Test
    fun nonAdjacentFrontAndBack_isNotDetected() {
        // 이전 목록에서 A와 C가 인접하지 않으면 중간 삽입으로 보지 않는다.
        process(listOf("a", "c", "b"), SnapshotChangeReason.INITIAL, snapshotId = 1)
        val first = process(listOf("a", "x", "b"), SnapshotChangeReason.ITEM_ADDED, snapshotId = 2)
        val second = process(listOf("a", "x", "b"), snapshotId = 3)
        assertTrue(first.isEmpty())
        assertTrue(second.isEmpty())
    }

    @Test
    fun orderChangeWithoutNewItem_isNotDetected() {
        process(listOf("a", "b", "c"), SnapshotChangeReason.INITIAL, snapshotId = 1)
        val events = process(listOf("a", "c", "b"), SnapshotChangeReason.ORDER_CHANGED, snapshotId = 2)
        assertTrue(events.isEmpty())
    }

    @Test
    fun multipleNewItems_betweenAdjacent_areRegisteredSeparately() {
        process(listOf("a", "b"), SnapshotChangeReason.INITIAL, snapshotId = 1)
        process(listOf("a", "x", "y", "b"), snapshotId = 2)
        val events = process(listOf("a", "x", "y", "b"), snapshotId = 3)
        assertEquals(2, events.size)
        val keys = events.map { it.newVideoId }.toSet()
        assertTrue("x" in keys)
        assertTrue("y" in keys)
    }

    @Test
    fun confirmedEvent_carriesJudgmentEvidence() {
        val events = runBasicInsertion()
        val evidence = InsertionEvidence.fromJson(events[0].evidence.toJson())
        assertTrue(evidence.notInPreviousList)
        assertTrue(evidence.appearedAtMiddle)
        assertTrue(evidence.frontBackMaintained)
        assertTrue(evidence.stabilized)
        assertTrue(evidence.notFullReload)
        assertTrue(evidence.notAfterProfileChange)
        assertTrue(evidence.notAfterSessionReset)
        assertTrue(evidence.notAfterDomRebuild)
    }

    @Test
    fun insertionAfterStabilizedInsertion_isDetectedSeparately() {
        // A→B → A→X→B 확정 후, X와 B 사이에 Y가 새로 끼어들면 별도 이벤트가 된다.
        process(listOf("a", "b"), SnapshotChangeReason.INITIAL, snapshotId = 1)
        process(listOf("a", "x", "b"), snapshotId = 2)
        assertEquals(1, process(listOf("a", "x", "b"), snapshotId = 3).size)
        process(listOf("a", "x", "y", "b"), snapshotId = 4)
        val events = process(listOf("a", "x", "y", "b"), snapshotId = 5)
        assertEquals(1, events.size)
        assertEquals("y", events[0].newVideoId)
        assertEquals("x", events[0].prevVideoId)
        assertEquals("b", events[0].nextVideoId)
    }
}
