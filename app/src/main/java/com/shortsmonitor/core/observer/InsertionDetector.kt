package com.shortsmonitor.core.observer

import com.shortsmonitor.core.model.InsertionEvidence
import com.shortsmonitor.core.model.SnapshotChangeReason

/**
 * 중간 삽입 의심 탐지 엔진 (H단계).
 *
 * 이전 목록과 현재 목록을 비교해 다음 조건을 모두 만족하면 신규 항목을 후보로 등록하고,
 * 다음 스냅샷에서도 신규 항목과 앞뒤 관계가 유지될 때 의심 이벤트로 확정한다.
 *
 * - 이전 목록에서 앞(A)과 뒤(B)가 인접함
 * - X가 이전 목록에 없음
 * - 현재 목록에서 A와 B가 유지됨
 * - X가 목록 끝이 아닌 중간에 있음
 * - 전체 페이지 재로드·프로필 변경·세션 초기화·DOM 전체 재구성 직후가 아님
 *
 * 목록 끝 추가는 탐지하지 않으며, 리셋 사유의 스냅샷은 기준 목록만 교체한다.
 * 확정된 키(신규·앞·뒤 영상)는 반복 생성하지 않는다.
 */
class InsertionDetector {

    /** 탐지된 중간 삽입 의심 (확정). */
    data class DetectedInsertion(
        val sessionId: Long,
        val newVideoId: String,
        val prevVideoId: String?,
        val nextVideoId: String?,
        val beforeSnapshotId: Long?,
        val afterSnapshotId: Long,
        val detectedAt: Long,
        val evidence: InsertionEvidence,
    )

    /** 후보 키: 신규 영상과 앞뒤 영상의 조합. */
    private data class CandidateKey(
        val newVideoId: String,
        val prevVideoId: String?,
        val nextVideoId: String?,
    )

    /** 안정화 대기 중인 후보. */
    private data class PendingCandidate(
        val key: CandidateKey,
        val beforeSnapshotId: Long?,
        val afterSnapshotId: Long,
        val detectedAt: Long,
        val observedCount: Int,
    )

    private var previousList: List<String>? = null
    private var previousSnapshotId: Long? = null
    private val pendingCandidates = mutableMapOf<CandidateKey, PendingCandidate>()
    private val confirmedKeys = mutableSetOf<CandidateKey>()

    /**
     * 새 목록 스냅샷을 처리하고 이번 스냅샷에서 확정된 의심 이벤트를 반환한다.
     *
     * @param videoIds 현재 스냅샷의 영상 식별값 순서
     * @param reason   이번 스냅샷의 변경 사유
     * @param snapshotId 현재 스냅샷의 데이터베이스 식별자
     * @param ts       현재 스냅샷 생성 시각
     */
    fun process(
        sessionId: Long,
        videoIds: List<String>,
        reason: SnapshotChangeReason,
        snapshotId: Long,
        ts: Long,
    ): List<DetectedInsertion> {
        // 기준이 바뀌는 스냅샷: 비교하지 않고 기준 목록만 교체한다.
        if (reason in RESET_REASONS) {
            pendingCandidates.clear()
            previousList = videoIds
            previousSnapshotId = snapshotId
            return emptyList()
        }

        val previous = previousList
        val beforeSnapshotId = previousSnapshotId
        previousList = videoIds
        previousSnapshotId = snapshotId

        // 기준 목록이 없으면(첫 비교 스냅샷) 후보를 만들 수 없다.
        if (previous == null) return emptyList()

        val events = mutableListOf<DetectedInsertion>()

        // 1) 이번 스냅샷에서 새로 발견된 후보 등록 (다음 스냅샷에서 안정화 확인)
        val newlyRegistered = mutableSetOf<CandidateKey>()
        for (key in detectCandidates(previous, videoIds)) {
            if (key in confirmedKeys || pendingCandidates.containsKey(key)) continue
            pendingCandidates[key] = PendingCandidate(
                key = key,
                beforeSnapshotId = beforeSnapshotId,
                afterSnapshotId = snapshotId,
                detectedAt = ts,
                observedCount = 1,
            )
            newlyRegistered += key
        }

        // 2) 이전에 등록된 후보의 안정화 확인: 신규 항목과 앞뒤 관계가 유지되면 확정
        val iterator = pendingCandidates.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val key = entry.key
            val candidate = entry.value
            if (key in newlyRegistered) continue
            if (!isRelationMaintained(videoIds, key)) {
                // 앞뒤 관계가 깨졌으면 후보 폐기
                iterator.remove()
                continue
            }
            val observed = candidate.observedCount + 1
            if (observed >= STABILIZE_COUNT) {
                iterator.remove()
                confirmedKeys += key
                events += DetectedInsertion(
                    sessionId = sessionId,
                    newVideoId = key.newVideoId,
                    prevVideoId = key.prevVideoId,
                    nextVideoId = key.nextVideoId,
                    beforeSnapshotId = candidate.beforeSnapshotId,
                    afterSnapshotId = candidate.afterSnapshotId,
                    detectedAt = candidate.detectedAt,
                    evidence = InsertionEvidence.confirmed(),
                )
            } else {
                entry.setValue(candidate.copy(observedCount = observed))
            }
        }

        return events
    }

    /**
     * 이전 목록과 현재 목록을 비교해 중간 삽입 후보를 찾는다.
     * X가 이전 목록에 없고, X 앞뒤의 가장 가까운 기존 항목 A·B가
     * 이전 목록에서 인접했으며, X가 목록 끝이 아닌 경우에만 후보로 등록한다.
     */
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

    /** 현재 목록에서 X가 A와 B 사이에 유지되는지 확인한다. */
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
        /** 후보 확정에 필요한 연속 관찰 횟수. */
        const val STABILIZE_COUNT = 2

        /** 비교를 건너뛰고 기준 목록만 교체하는 사유. */
        val RESET_REASONS: Set<SnapshotChangeReason> = setOf(
            SnapshotChangeReason.INITIAL,
            SnapshotChangeReason.FULL_RELOAD,
            SnapshotChangeReason.PROFILE_CHANGED,
            SnapshotChangeReason.SESSION_RESET,
            SnapshotChangeReason.DOM_REBUILT,
            SnapshotChangeReason.NAVIGATION,
        )
    }
}
