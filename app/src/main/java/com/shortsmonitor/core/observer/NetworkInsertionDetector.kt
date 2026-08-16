package com.shortsmonitor.core.observer

import com.shortsmonitor.core.model.AutoVerdict
import com.shortsmonitor.core.model.InsertionEvidence
import com.shortsmonitor.core.model.NetworkRequestKind
import com.shortsmonitor.core.model.SequenceLineageRelation

/**
 * 네트워크 시퀀스 전용 중간 삽입 탐지기.
 *
 * DOM 목록 스냅샷 비교([InsertionDetector])와 분리해, 서버가 전달한 시퀀스
 * (network_sequence)를 기준으로 중간 삽입을 판정한다.
 *
 * 후보 조건 (모두 충족):
 * - 이전·신규 시퀀스가 같은 계보(SAME_FLOW)로 판정됨
 * - 신규 영상이 이전 시퀀스에 없음
 * - 신규 영상 앞뒤의 기존 영상이 이전 시퀀스에서 인접함
 * - 신규 영상이 시퀀스 끝 단순 추가가 아님
 * - 신규 시퀀스에서 앞·신규·뒤 순서가 유지됨
 * - 전체 새로고침·프로필 변경·세션 초기화·검색 컨텍스트 변경 직후가 아님 (계보가 NEW_CONTEXT면 기준 교체)
 *
 * 확정 조건: 같은 계보의 후속 시퀀스에서 관계가 유지되고(안정화) + 다음 증거 중 하나 이상.
 * - 해당 신규 영상에 대한 `player` 요청
 * - 해당 신규 영상에 대한 `reel_item_watch` 요청
 * - DOM에서 실제 활성 영상으로 관찰
 *
 * 계보가 불명확(UNKNOWN)이거나 네트워크 데이터가 없으면 확정하지 않는다.
 */
class NetworkInsertionDetector {

    /** 후보 키: 신규 영상과 앞뒤 영상의 조합. */
    data class CandidateKey(
        val newVideoId: String,
        val prevVideoId: String?,
        val nextVideoId: String?,
    )

    /** 후보 상태. */
    data class PendingCandidate(
        val key: CandidateKey,
        val beforeSequenceId: Long?,
        val afterSequenceId: Long?,
        val detectedAt: Long,
        var observedCount: Int,
        var strengthenedByPlayer: Boolean = false,
        var strengthenedByReelItemWatch: Boolean = false,
        var strengthenedByDomActive: Boolean = false,
    )

    /** 네트워크 탐지 결과 (기록기가 DB 행으로 반영한다). */
    sealed class Outcome {
        /** 새 후보 등록. */
        data class Registered(
            val key: CandidateKey,
            val beforeSequenceId: Long?,
            val afterSequenceId: Long?,
            val detectedAt: Long,
        ) : Outcome()

        /** 후보 확정 (안정화 + 강화 증거). */
        data class Confirmed(
            val key: CandidateKey,
            val afterSequenceId: Long?,
            val evidence: InsertionEvidence,
        ) : Outcome()

        /** 후보 무효화 (관계 소멸 등). */
        data class Invalidated(
            val key: CandidateKey,
            val reason: String,
        ) : Outcome()

        /**
         * 계보 판정 불가(UNKNOWN)로 확정할 수 없는 중간 삽입 관찰.
         * 보류(UNKNOWN) 상태로 저장하고 자동 확정하지 않는다.
         */
        data class Unknown(
            val key: CandidateKey,
            val beforeSequenceId: Long?,
            val afterSequenceId: Long?,
            val detectedAt: Long,
        ) : Outcome()
    }

    private var previousVideoIds: List<String>? = null
    private var previousSequenceId: Long? = null
    private var previousLineage: SequenceLineageRelation = SequenceLineageRelation.NONE
    private val pending = mutableMapOf<CandidateKey, PendingCandidate>()
    private val confirmedKeys = mutableSetOf<CandidateKey>()

    /**
     * 삽입 분석 제한 여부.
     * 초기 시퀀스를 놓친 세션(문서 시작 주입 미지원, 관찰기 설치 전 요청 등)은
     * 확정 조건을 충족해도 확정하지 않는다. 후보 등록은 허용하되 신뢰도를 낮춘다.
     */
    private var restricted = false

    /** 대기 중인 후보가 있는지 (진단·안정화 주기 판단용). */
    val hasPendingCandidates: Boolean get() = pending.isNotEmpty()

    /** 삽입 분석 제한 상태를 설정한다. 제한 중에는 [tryConfirm]이 확정을 내지 않는다. */
    fun setRestricted(restricted: Boolean) {
        this.restricted = restricted
    }

    /** 기준이 교체된 뒤 첫 시퀀스는 비교하지 않는다. */
    fun resetBaseline() {
        previousVideoIds = null
        previousSequenceId = null
        previousLineage = SequenceLineageRelation.NONE
        pending.clear()
    }

    /**
     * 새 네트워크 시퀀스를 처리한다.
     *
     * @param videoIds 시퀀스의 영상 식별값 순서 (비영상 항목 제외).
     * @param lineage 이전 시퀀스와의 계보 판정 결과.
     */
    fun processSequence(
        sequenceId: Long,
        videoIds: List<String>,
        lineage: SequenceLineageRelation,
        ts: Long,
    ): List<Outcome> {
        val outcomes = mutableListOf<Outcome>()

        // 계보가 불명확하거나 새 컨텍스트면 비교하지 않고 기준을 교체한다.
        if (lineage == SequenceLineageRelation.NEW_CONTEXT ||
            lineage == SequenceLineageRelation.UNKNOWN ||
            lineage == SequenceLineageRelation.NONE
        ) {
            if (lineage == SequenceLineageRelation.NEW_CONTEXT) {
                // 새 탐색 컨텍스트: 이전 흐름의 후보는 무효화한다.
                pending.forEach { (key, _) ->
                    outcomes += Outcome.Invalidated(key, "new_context")
                }
                pending.clear()
            } else if (lineage == SequenceLineageRelation.UNKNOWN) {
                // 계보 판정 불가: 확정하지 않고 보류(UNKNOWN)로 기록한다.
                val previous = previousVideoIds
                if (previous != null) {
                    val beforeId = previousSequenceId
                    detectCandidates(previous, videoIds).forEach { key ->
                        outcomes += Outcome.Unknown(
                            key = key,
                            beforeSequenceId = beforeId,
                            afterSequenceId = sequenceId,
                            detectedAt = ts,
                        )
                    }
                }
            }
            // 기준을 교체한다 (계보 불명확 상태에서 비교로 확정하지 않는다).
            previousVideoIds = videoIds
            previousSequenceId = sequenceId
            previousLineage = lineage
            return outcomes
        }

        val previous = previousVideoIds
        val beforeSequenceId = previousSequenceId
        previousVideoIds = videoIds
        previousSequenceId = sequenceId
        previousLineage = lineage

        if (previous == null) return outcomes

        // 1) 새 후보 등록
        val newlyRegistered = mutableSetOf<CandidateKey>()
        for (key in detectCandidates(previous, videoIds)) {
            if (key in confirmedKeys || pending.containsKey(key)) continue
            pending[key] = PendingCandidate(
                key = key,
                beforeSequenceId = beforeSequenceId,
                afterSequenceId = sequenceId,
                detectedAt = ts,
                observedCount = 1,
            )
            newlyRegistered += key
            outcomes += Outcome.Registered(
                key = key,
                beforeSequenceId = beforeSequenceId,
                afterSequenceId = sequenceId,
                detectedAt = ts,
            )
        }

        // 2) 기존 후보 안정화 (같은 계보의 후속 시퀀스에서 관계 유지 확인)
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val key = entry.key
            val candidate = entry.value
            if (key in newlyRegistered) continue
            if (!isRelationMaintained(videoIds, key)) {
                iterator.remove()
                outcomes += Outcome.Invalidated(key, "relation_broken")
                continue
            }
            candidate.observedCount++
            val confirmed = tryConfirm(candidate, sequenceId)
            if (confirmed != null) {
                iterator.remove()
                confirmedKeys += key
                outcomes += confirmed
            }
        }
        return outcomes
    }

    /** `player` 또는 `reel_item_watch` 요청으로 후보를 강화한다. */
    fun strengthenByVideoRequest(videoId: String, kind: NetworkRequestKind): List<Outcome> {
        if (videoId.isBlank()) return emptyList()
        val outcomes = mutableListOf<Outcome>()
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val candidate = entry.value
            if (candidate.key.newVideoId != videoId) continue
            if (kind == NetworkRequestKind.PLAYER) {
                candidate.strengthenedByPlayer = true
            } else if (kind == NetworkRequestKind.REEL_ITEM_WATCH) {
                candidate.strengthenedByReelItemWatch = true
            }
            val confirmed = tryConfirm(candidate, null)
            if (confirmed != null) {
                iterator.remove()
                confirmedKeys += candidate.key
                outcomes += confirmed
            }
        }
        return outcomes
    }

    /** DOM에서 실제 활성 영상으로 관찰되어 후보를 강화한다. */
    fun strengthenByActiveExposure(videoId: String): List<Outcome> {
        if (videoId.isBlank()) return emptyList()
        val outcomes = mutableListOf<Outcome>()
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val candidate = entry.value
            if (candidate.key.newVideoId != videoId) continue
            candidate.strengthenedByDomActive = true
            val confirmed = tryConfirm(candidate, null)
            if (confirmed != null) {
                iterator.remove()
                confirmedKeys += candidate.key
                outcomes += confirmed
            }
        }
        return outcomes
    }

    /**
     * 확정 조건: 같은 계보의 후속 시퀀스에서 관계가 2회 이상 유지되고,
     * 강화 증거(player 요청·reel_item_watch 요청·DOM 활성 관찰)가 하나 이상 있을 때만 확정한다.
     */
    private fun tryConfirm(candidate: PendingCandidate, afterSequenceId: Long?): Outcome.Confirmed? {
        // 초기 시퀀스 누락 등 삽입 분석이 제한된 세션에서는 확정하지 않는다.
        if (restricted) return null
        val strengthened = candidate.strengthenedByPlayer ||
            candidate.strengthenedByReelItemWatch ||
            candidate.strengthenedByDomActive
        if (candidate.observedCount >= STABILIZE_COUNT && strengthened) {
            return Outcome.Confirmed(
                key = candidate.key,
                afterSequenceId = afterSequenceId ?: candidate.afterSequenceId,
                evidence = InsertionEvidence.networkConfirmed(
                    sameLineageFlow = true,
                    orderMaintained = true,
                    strengthenedByPlayerRequest = candidate.strengthenedByPlayer,
                    strengthenedByReelItemWatch = candidate.strengthenedByReelItemWatch,
                    strengthenedByDomActive = candidate.strengthenedByDomActive,
                ),
            )
        }
        return null
    }

    /** 이전 시퀀스와 신규 시퀀스를 비교해 중간 삽입 후보를 찾는다. */
    private fun detectCandidates(previous: List<String>, current: List<String>): List<CandidateKey> {
        if (previous.isEmpty() || current.isEmpty()) return emptyList()
        val previousSet = previous.toSet()
        val previousIndex = previous.withIndex().associate { it.value to it.index }
        val result = mutableListOf<CandidateKey>()

        for (i in current.indices) {
            val x = current[i]
            if (x in previousSet) continue

            // X 앞쪽에서 가장 가까운 기존 항목 A
            var a: String? = null
            for (j in i - 1 downTo 0) {
                if (current[j] in previousSet) {
                    a = current[j]
                    break
                }
            }
            // X 뒤쪽에서 가장 가까운 기존 항목 B (없으면 목록 끝 추가 → 제외)
            var b: String? = null
            for (j in i + 1 until current.size) {
                if (current[j] in previousSet) {
                    b = current[j]
                    break
                }
            }
            if (a == null || b == null) continue

            // 이전 목록에서 A와 B가 인접해야 한다
            val aIndex = previousIndex[a] ?: continue
            val bIndex = previousIndex[b] ?: continue
            if (bIndex != aIndex + 1) continue

            result += CandidateKey(newVideoId = x, prevVideoId = a, nextVideoId = b)
        }
        return result
    }

    /** 현재 시퀀스에서 X가 A와 B 사이에 유지되는지 확인한다. */
    private fun isRelationMaintained(current: List<String>, key: CandidateKey): Boolean {
        val a = key.prevVideoId ?: return false
        val b = key.nextVideoId ?: return false
        val indexX = current.indexOf(key.newVideoId)
        val indexA = current.indexOf(a)
        val indexB = current.indexOf(b)
        if (indexX < 0 || indexA < 0 || indexB < 0) return false
        return indexA < indexX && indexX < indexB
    }

    companion object {
        /** 후보 확정에 필요한 동일 계보 연속 관찰 횟수. */
        const val STABILIZE_COUNT = 2
    }
}
