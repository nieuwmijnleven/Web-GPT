package com.shortsmonitor.core.observer

import com.shortsmonitor.core.model.EntryContext
import com.shortsmonitor.core.model.SequenceLineageRelation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시퀀스 계보 판정 단위 테스트.
 *
 * 동일 시퀀스 유지 / 현재 영상만 변경 / 시퀀스 끝 추가 / 중간 신규 영상 추가 /
 * 기존 영상 제거 / 순서 변경 / 완전히 새로운 검색 결과 / 전체 새로고침 /
 * 프로필 변경 / 세션 초기화 / 계보 판단 불가.
 */
class SequenceLineageDetectorTest {

    private fun input(
        current: String?,
        videos: List<String>,
        context: EntryContext = EntryContext.SHORTS_VIDEO,
        sequenceParamsHash: String? = null,
        continuationHash: String? = null,
    ) = SequenceLineageDetector.LineageInput(
        currentVideoId = current,
        videoIds = videos,
        sequenceParamsHash = sequenceParamsHash,
        continuationHash = continuationHash,
        entryContext = context,
    )

    @Test
    fun firstSequence_hasNoLineage() {
        val result = SequenceLineageDetector.decide(null, input("a", listOf("a", "b", "c")))
        assertEquals(SequenceLineageRelation.NONE, result.relation)
    }

    @Test
    fun sameSequenceMaintained_isSameFlow() {
        val prev = input("a", listOf("a", "b", "c"))
        val next = input("a", listOf("a", "b", "c"))
        assertEquals(SequenceLineageRelation.SAME_FLOW, SequenceLineageDetector.decide(prev, next).relation)
    }

    @Test
    fun currentVideoOnlyChanged_isSameFlow() {
        // 같은 목록에서 현재 영상만 b로 바뀐 경우 (같은 흐름의 활성 이동).
        val prev = input("a", listOf("a", "b", "c"))
        val next = input("b", listOf("a", "b", "c"))
        assertEquals(SequenceLineageRelation.SAME_FLOW, SequenceLineageDetector.decide(prev, next).relation)
    }

    @Test
    fun endOfSequenceAddition_isSameFlow() {
        val prev = input("a", listOf("a", "b", "c"))
        val next = input("a", listOf("a", "b", "c", "d"))
        assertEquals(SequenceLineageRelation.SAME_FLOW, SequenceLineageDetector.decide(prev, next).relation)
    }

    @Test
    fun middleInsertion_isSameFlow() {
        val prev = input("a", listOf("a", "b"))
        val next = input("a", listOf("a", "x", "b"))
        assertEquals(SequenceLineageRelation.SAME_FLOW, SequenceLineageDetector.decide(prev, next).relation)
    }

    @Test
    fun removal_isSameFlow() {
        val prev = input("a", listOf("a", "b", "c"))
        val next = input("a", listOf("a", "c"))
        assertEquals(SequenceLineageRelation.SAME_FLOW, SequenceLineageDetector.decide(prev, next).relation)
    }

    @Test
    fun orderChange_isSameFlow() {
        val prev = input("a", listOf("a", "b", "c"))
        val next = input("a", listOf("a", "c", "b"))
        assertEquals(SequenceLineageRelation.SAME_FLOW, SequenceLineageDetector.decide(prev, next).relation)
    }

    @Test
    fun completelyNewSearchResult_isNewContext() {
        // 공통 구간도 연속성도 없는 완전히 다른 결과.
        val prev = input("a", listOf("a", "b", "c"), EntryContext.SEARCH_RESULT)
        val next = input("z", listOf("z", "y", "x"), EntryContext.SEARCH_RESULT)
        assertEquals(SequenceLineageRelation.NEW_CONTEXT, SequenceLineageDetector.decide(prev, next).relation)
    }

    @Test
    fun fullReload_isNewContext() {
        val prev = input("a", listOf("a", "b", "c"))
        val next = input("a", listOf("a", "b", "c"))
        assertEquals(
            SequenceLineageRelation.NEW_CONTEXT,
            SequenceLineageDetector.decide(prev, next, afterReset = true).relation,
        )
    }

    @Test
    fun profileChange_isNewContext() {
        val prev = input("a", listOf("a", "b", "c"))
        val next = input("a", listOf("a", "b", "c"))
        assertEquals(
            SequenceLineageRelation.NEW_CONTEXT,
            SequenceLineageDetector.decide(prev, next, afterReset = true).relation,
        )
    }

    @Test
    fun sessionReset_isNewContext() {
        val prev = input("a", listOf("a", "b", "c"))
        val next = input("a", listOf("a", "b", "c"))
        assertEquals(
            SequenceLineageRelation.NEW_CONTEXT,
            SequenceLineageDetector.decide(prev, next, afterReset = true).relation,
        )
    }

    @Test
    fun overlappingButContextChanged_isIndeterminate() {
        // 공통 구간이 있지만 진입 컨텍스트가 바뀐 경우: 판정 보류.
        val prev = input("a", listOf("a", "b", "c"), EntryContext.SEARCH_RESULT)
        val next = input("b", listOf("b", "c", "d"), EntryContext.SHORTS_VIDEO)
        val result = SequenceLineageDetector.decide(prev, next)
        assertEquals(SequenceLineageRelation.UNKNOWN, result.relation)
    }

    @Test
    fun sameSequenceParamsHash_isSameFlow() {
        val prev = input("a", listOf("a", "b"), sequenceParamsHash = "h1")
        val next = input("a", listOf("a", "b"), sequenceParamsHash = "h1")
        assertEquals(SequenceLineageRelation.SAME_FLOW, SequenceLineageDetector.decide(prev, next).relation)
    }

    @Test
    fun signalsJson_isSafeAndIncludesEvidence() {
        val prev = input("a", listOf("a", "b"), context = EntryContext.SEARCH_RESULT)
        val next = input("b", listOf("b", "c"), context = EntryContext.SHORTS_VIDEO)
        val result = SequenceLineageDetector.decide(prev, next)
        val signals = result.signalsJson()
        assertTrue(signals.contains("commonRunLength"))
        assertTrue(signals.contains("currentVideoContinuity"))
        assertTrue(signals.contains("contextChanged"))
        assertTrue(signals.contains("afterReset"))
    }
}
