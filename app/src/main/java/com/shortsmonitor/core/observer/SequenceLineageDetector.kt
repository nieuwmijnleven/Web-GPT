package com.shortsmonitor.core.observer

import com.shortsmonitor.core.model.EntryContext
import com.shortsmonitor.core.model.SequenceLineageRelation

/**
 * 시퀀스 계보 판정기.
 *
 * 서로 다른 시퀀스가 같은 추천 흐름의 갱신인지(SAME_FLOW), 완전히 새로운 탐색
 * 결과인지(NEW_CONTEXT), 판정 불가(UNKNOWN)인지 구분한다.
 *
 * 판정 신호 (조합):
 * - 동일 관찰 세션 (호출 측 보장)
 * - 동일 또는 연속된 현재 영상 (이전 시퀀스의 현재 영상이 신규 시퀀스 목록에 있거나 그 반대)
 * - 이전 시퀀스와 신규 시퀀스의 공통 구간 (연속 공통 부분열)
 * - sequenceParams 해시 관계 (동일하면 강한 동일 흐름 신호)
 * - continuation 해시 관계
 * - 페이지 진입 컨텍스트 (검색·홈·채널 등)
 * - 새로고침·프로필 변경·세션 초기화 (호출 측이 [afterReset]로 전달)
 *
 * 계보가 불명확하면 SAME_FLOW로 확정하지 않고 UNKNOWN으로 보류한다.
 * 판정 결과와 근거는 [LineageResult]로 반환해 저장·내보내기에 포함한다.
 */
object SequenceLineageDetector {

    /** 계보 판정 입력 (한 시퀀스의 요약). */
    data class LineageInput(
        /** 시퀀스의 현재(첫 번째) 영상 식별값. */
        val currentVideoId: String?,
        /** 시퀀스의 영상 식별값 순서. */
        val videoIds: List<String>,
        /** sequenceParams 안전 해시 (요청 본문 기준). */
        val sequenceParamsHash: String? = null,
        /** 응답 continuation 안전 해시. */
        val continuationHash: String? = null,
        /** 페이지 진입 컨텍스트. */
        val entryContext: EntryContext = EntryContext.OTHER,
    )

    /** 계보 판정 결과. */
    data class LineageResult(
        val relation: SequenceLineageRelation,
        /** 공통 연속 구간 최대 길이. */
        val commonRunLength: Int,
        /** 현재 영상 연속성(같거나 서로 목록에 포함). */
        val currentVideoContinuity: Boolean,
        /** sequenceParams 해시 동일 여부. */
        val sequenceParamsEqual: Boolean,
        /** continuation 해시 동일 여부. */
        val continuationEqual: Boolean,
        /** 진입 컨텍스트 변경 여부. */
        val contextChanged: Boolean,
        /** 초기화·프로필 변경·새로고침 직후 여부. */
        val afterReset: Boolean,
        /** 판정에 사용한 신호 요약 (민감정보 없음). */
        val signals: Map<String, Any>,
    ) {
        /**
         * 내보내기·저장용 신호 JSON.
         * org.json에 의존하지 않는 순수 문자열 인코딩으로, 일반 JVM 단위 테스트에서도 사용할 수 있다.
         * 값은 Int/Boolean/String만 포함한다 (민감정보 없음).
         */
        fun signalsJson(): String = buildString {
            append('{')
            signals.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(',')
                append('"').append(escapeJson(key)).append('"').append(':')
                append(jsonValue(value))
            }
            append('}')
        }
    }

    private fun jsonValue(value: Any): String = when (value) {
        is String -> "\"" + escapeJson(value) + "\""
        is Boolean -> value.toString()
        is Int -> value.toString()
        else -> "\"" + escapeJson(value.toString()) + "\""
    }

    private fun escapeJson(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    /**
     * [previous]와 [next]의 계보를 판정한다.
     *
     * @param afterReset 새로고침·프로필 변경·세션 초기화 등으로 기준이 바뀐 직후면 true.
     */
    fun decide(previous: LineageInput?, next: LineageInput, afterReset: Boolean = false): LineageResult {
        if (previous == null) {
            return LineageResult(
                relation = SequenceLineageRelation.NONE,
                commonRunLength = 0,
                currentVideoContinuity = false,
                sequenceParamsEqual = false,
                continuationEqual = false,
                contextChanged = false,
                afterReset = afterReset,
                signals = emptyMap(),
            )
        }

        val commonRun = longestCommonContiguousRun(previous.videoIds, next.videoIds)
        val continuity = currentVideoContinuity(previous, next)
        val seqParamsEqual = previous.sequenceParamsHash != null &&
            previous.sequenceParamsHash == next.sequenceParamsHash
        val continuationEqual = previous.continuationHash != null &&
            previous.continuationHash == next.continuationHash
        val contextChanged = previous.entryContext != next.entryContext

        val relation = when {
            // 새로고침·프로필 변경·세션 초기화 직후: 완전히 새 기준.
            afterReset -> SequenceLineageRelation.NEW_CONTEXT

            // sequenceParams 해시가 같으면 같은 흐름으로 강하게 확정.
            seqParamsEqual -> SequenceLineageRelation.SAME_FLOW

            // 같은 컨텍스트에서 연속성이나 공통 구간이 있으면 같은 흐름.
            !contextChanged && (continuity || commonRun >= 1) ->
                SequenceLineageRelation.SAME_FLOW

            // 컨텍스트가 바뀌었지만 공통 구간이 있으면 판정 보류 (겹치는 탐색 결과).
            contextChanged && commonRun >= 1 -> SequenceLineageRelation.UNKNOWN

            // 연속성도 공통 구간도 없으면 완전히 새로운 결과.
            !continuity && commonRun == 0 -> SequenceLineageRelation.NEW_CONTEXT

            else -> SequenceLineageRelation.UNKNOWN
        }

        val signals = mapOf(
            "commonRunLength" to commonRun,
            "currentVideoContinuity" to continuity,
            "sequenceParamsEqual" to seqParamsEqual,
            "continuationEqual" to continuationEqual,
            "contextChanged" to contextChanged,
            "afterReset" to afterReset,
            "prevEntryContext" to previous.entryContext.name,
            "nextEntryContext" to next.entryContext.name,
        )
        return LineageResult(
            relation = relation,
            commonRunLength = commonRun,
            currentVideoContinuity = continuity,
            sequenceParamsEqual = seqParamsEqual,
            continuationEqual = continuationEqual,
            contextChanged = contextChanged,
            afterReset = afterReset,
            signals = signals,
        )
    }

    private fun currentVideoContinuity(previous: LineageInput, next: LineageInput): Boolean {
        val prevCurrent = previous.currentVideoId
        val nextCurrent = next.currentVideoId
        if (prevCurrent.isNullOrBlank() || nextCurrent.isNullOrBlank()) {
            return false
        }
        if (prevCurrent == nextCurrent) return true
        // 다음 시퀀스의 현재 영상이 이전 시퀀스 목록에 있거나, 이전 현재 영상이 다음 목록에 있으면 연속.
        if (previous.videoIds.contains(nextCurrent)) return true
        if (next.videoIds.contains(prevCurrent)) return true
        return false
    }

    /**
     * 두 목록의 최장 연속 공통 부분열 길이.
     * 순서가 유지되는 인접 공통 구간만 인정한다.
     */
    internal fun longestCommonContiguousRun(a: List<String>, b: List<String>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val indexB = b.withIndex().associate { it.value to it.index }
        var best = 0
        for (i in a.indices) {
            val startB = indexB[a[i]] ?: continue
            var run = 1
            var j = i + 1
            var k = startB + 1
            while (j < a.size && k < b.size && a[j] == b[k]) {
                run++
                j++
                k++
            }
            if (run > best) best = run
        }
        return best
    }
}
