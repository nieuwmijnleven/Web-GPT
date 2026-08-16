package com.shortsmonitor.core.observer

import com.shortsmonitor.core.model.NetworkRequestKind
import com.shortsmonitor.core.model.SequenceLineageRelation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 네트워크 시퀀스 전용 중간 삽입 탐지 단위 테스트.
 *
 * 실제 중간 삽입 / 단순 끝 추가 / 비영상 안내 카드 / 초기 시퀀스 누락 /
 * 후속 시퀀스에서 관계 유지 / 후보 소멸 / player 요청으로 강화 / DOM 활성 노출로 강화.
 *
 * 네트워크 데이터가 없거나 계보가 불명확하면 확정하지 않는다.
 */
class NetworkInsertionDetectorTest {

    private val detector = NetworkInsertionDetector()

    /** 첫 시퀀스(계보 NONE)로 기준을 세운다. */
    private fun baseline(vararg ids: String) {
        detector.processSequence(
            sequenceId = 1L,
            videoIds = ids.toList(),
            lineage = SequenceLineageRelation.NONE,
            ts = 1_000L,
        )
    }

    private fun seq(id: Long, vararg ids: String, lineage: SequenceLineageRelation = SequenceLineageRelation.SAME_FLOW): List<NetworkInsertionDetector.Outcome> =
        detector.processSequence(
            sequenceId = id,
            videoIds = ids.toList(),
            lineage = lineage,
            ts = id * 1_000L,
        )

    private fun registeredKeys(outcomes: List<NetworkInsertionDetector.Outcome>): Set<String> =
        outcomes.filterIsInstance<NetworkInsertionDetector.Outcome.Registered>()
            .map { it.key.newVideoId }.toSet()

    private fun confirmedKeys(outcomes: List<NetworkInsertionDetector.Outcome>): Set<String> =
        outcomes.filterIsInstance<NetworkInsertionDetector.Outcome.Confirmed>()
            .map { it.key.newVideoId }.toSet()

    @Test
    fun middleInsertion_registersCandidate_thenConfirmsWithRelationAndPlayerRequest() {
        baseline("a", "b")

        // A→X→B: 후보 등록 (아직 확정 아님)
        val registered = seq(2L, "a", "x", "b")
        assertEquals(setOf("x"), registeredKeys(registered))
        assertTrue(confirmedKeys(registered).isEmpty())

        // 같은 계보 후속 시퀀스에서 관계 유지 → 안정화 1회 (아직 강화 증거 없음)
        val followUp = seq(3L, "a", "x", "b")
        assertTrue(confirmedKeys(followUp).isEmpty())

        // 해당 영상의 player 요청 → 확정
        val confirmed = detector.strengthenByVideoRequest("x", NetworkRequestKind.PLAYER)
        assertEquals(setOf("x"), confirmedKeys(confirmed))
        val evidence = (confirmed[0] as NetworkInsertionDetector.Outcome.Confirmed).evidence
        assertTrue(evidence.strengthenedByPlayerRequest)
        assertTrue(evidence.stabilized)
        assertTrue(evidence.sameLineageFlow)
    }

    @Test
    fun relationMaintainedInFollowUp_alone_doesNotConfirm() {
        baseline("a", "b")
        seq(2L, "a", "x", "b")
        // 후속 시퀀스에서 관계 유지되지만 강화 증거가 없으면 후보로 남는다.
        val followUp = seq(3L, "a", "x", "b")
        val secondFollowUp = seq(4L, "a", "x", "b")
        assertTrue(confirmedKeys(followUp).isEmpty())
        assertTrue(confirmedKeys(secondFollowUp).isEmpty())
        assertTrue(detector.hasPendingCandidates)
    }

    @Test
    fun reelItemWatchRequest_strengthensCandidate() {
        baseline("a", "b")
        seq(2L, "a", "x", "b")
        seq(3L, "a", "x", "b")
        val confirmed = detector.strengthenByVideoRequest("x", NetworkRequestKind.REEL_ITEM_WATCH)
        assertEquals(setOf("x"), confirmedKeys(confirmed))
        val evidence = (confirmed[0] as NetworkInsertionDetector.Outcome.Confirmed).evidence
        assertTrue(evidence.strengthenedByReelItemWatch)
    }

    @Test
    fun domActiveExposure_strengthensCandidate() {
        baseline("a", "b")
        seq(2L, "a", "x", "b")
        seq(3L, "a", "x", "b")
        val confirmed = detector.strengthenByActiveExposure("x")
        assertEquals(setOf("x"), confirmedKeys(confirmed))
        val evidence = (confirmed[0] as NetworkInsertionDetector.Outcome.Confirmed).evidence
        assertTrue(evidence.strengthenedByDomActive)
    }

    @Test
    fun endOfSequenceAddition_isNotDetected() {
        baseline("a", "b")
        val outcomes = seq(2L, "a", "b", "x")
        assertTrue(registeredKeys(outcomes).isEmpty())
    }

    @Test
    fun nonVideoCardInsertion_isNotDetectedAsInsertion() {
        // 비영상 안내 카드는 영상 목록(videoIds)에서 제외되므로 탐지하지 않는다.
        baseline("a", "b")
        val outcomes = seq(2L, "a", "b")
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun initialSequenceMissed_neverConfirms() {
        // 기준 없이 들어온 첫 시퀀스는 비교하지 않는다.
        val first = detector.processSequence(
            sequenceId = 1L,
            videoIds = listOf("a", "x", "b"),
            lineage = SequenceLineageRelation.NONE,
            ts = 1_000L,
        )
        assertTrue(first.isEmpty())
        val second = detector.processSequence(
            sequenceId = 2L,
            videoIds = listOf("a", "x", "b"),
            lineage = SequenceLineageRelation.NONE,
            ts = 2_000L,
        )
        assertTrue(second.isEmpty())
    }

    @Test
    fun candidateDropsWhenRelationBreaks() {
        baseline("a", "b")
        seq(2L, "a", "x", "b")
        // X가 사라지면 관계가 깨져 후보가 무효화된다.
        val outcomes = seq(3L, "a", "b")
        val invalidated = outcomes.filterIsInstance<NetworkInsertionDetector.Outcome.Invalidated>()
        assertEquals(1, invalidated.size)
        assertEquals("x", invalidated[0].key.newVideoId)
        assertFalse(detector.hasPendingCandidates)
    }

    @Test
    fun newContext_invalidatesCandidates() {
        baseline("a", "b")
        seq(2L, "a", "x", "b")
        val outcomes = seq(3L, "z", "y", lineage = SequenceLineageRelation.NEW_CONTEXT)
        val invalidated = outcomes.filterIsInstance<NetworkInsertionDetector.Outcome.Invalidated>()
        assertEquals(1, invalidated.size)
        assertEquals("x", invalidated[0].key.newVideoId)
    }

    @Test
    fun unknownLineage_producesPendingUnknownOutcome() {
        baseline("a", "b")
        // 계보 판정 불가: 확정하지 않고 보류(UNKNOWN)로 기록한다.
        val outcomes = seq(2L, "a", "x", "b", lineage = SequenceLineageRelation.UNKNOWN)
        val unknown = outcomes.filterIsInstance<NetworkInsertionDetector.Outcome.Unknown>()
        assertEquals(1, unknown.size)
        assertEquals("x", unknown[0].key.newVideoId)
        assertTrue(confirmedKeys(outcomes).isEmpty())
    }

    @Test
    fun domDelayedRendering_doesNotAffectNetworkDetection() {
        // DOM 지연 렌더링/가상 스크롤 노드 제거 등은 네트워크 시퀀스에 반영되지 않으므로
        // 네트워크 탐지기는 아무것도 만들지 않는다.
        baseline("a", "b")
        assertTrue(seq(2L, "a", "b").isEmpty())
        assertTrue(seq(3L, "a", "b").isEmpty())
        assertFalse(detector.hasPendingCandidates)
    }

    @Test
    fun multipleInsertions_areTrackedIndependently() {
        baseline("a", "b")
        val registered = seq(2L, "a", "x", "y", "b")
        assertEquals(setOf("x", "y"), registeredKeys(registered))
        seq(3L, "a", "x", "y", "b")
        val confirmed = detector.strengthenByVideoRequest("y", NetworkRequestKind.PLAYER)
        assertEquals(setOf("y"), confirmedKeys(confirmed))
    }

    @Test
    fun duplicateEvents_areNotConfirmedTwice() {
        baseline("a", "b")
        seq(2L, "a", "x", "b")
        seq(3L, "a", "x", "b")
        detector.strengthenByVideoRequest("x", NetworkRequestKind.PLAYER)
        // 같은 키는 이후 다시 확정하지 않는다.
        seq(4L, "a", "x", "b")
        val later = detector.strengthenByVideoRequest("x", NetworkRequestKind.REEL_ITEM_WATCH)
        assertTrue(confirmedKeys(later).isEmpty())
    }
}
