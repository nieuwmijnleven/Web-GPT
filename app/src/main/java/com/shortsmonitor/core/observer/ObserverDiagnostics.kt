package com.shortsmonitor.core.observer

import com.shortsmonitor.core.model.ObserverErrorCode
import com.shortsmonitor.core.model.SequenceLineageRelation
import com.shortsmonitor.core.model.SequenceParseStatus

/**
 * 관찰기 진단 상태 저장소 (O단계 WebView 진단 + 네트워크 관찰 진단).
 *
 * [ObserverBridge]가 관찰기 메시지를 받을 때마다 이 저장소를 갱신한다.
 * WebView 진단 화면은 여기에서 관찰기 상태·네트워크 관찰 상태를 읽는다.
 * 진단 정보에 민감한 쿠키·토큰·continuation 원문은 포함되지 않는다.
 */
object ObserverDiagnostics {

    /** 마지막 하트비트 수신 시각 (epoch ms). 0이면 아직 없음. */
    var lastHeartbeatAtMs: Long = 0L
        private set

    /** 마지막 관찰기 메시지 수신 시각 (epoch ms). 0이면 아직 없음. */
    var lastMessageAtMs: Long = 0L
        private set

    /** 마지막 DOM 분석 오류 (관찰기 오류 메시지의 요약). 없으면 null. */
    var lastDomError: String? = null
        private set

    // ===== 네트워크 관찰 상태 =====

    /** 문서 시작 스크립트 주입 지원 여부. ObserverBridge가 설정한다. */
    var documentStartSupported: Boolean = false

    /** 네트워크 관찰기 설치 시각 (epoch ms). 0이면 아직 없음. ObserverBridge가 설정한다. */
    var networkObserverInstalledAtMs: Long = 0L

    /** 첫 시퀀스 요청 관찰 시각 (epoch ms). 0이면 아직 없음. */
    var firstSequenceRequestAtMs: Long = 0L
        private set

    /** 마지막 시퀀스 요청 관찰 시각 (epoch ms). 0이면 아직 없음. */
    var lastSequenceRequestAtMs: Long = 0L
        private set

    /** 마지막 시퀀스 응답 분석 시각 (epoch ms). 0이면 아직 없음. */
    var lastSequenceResponseAtMs: Long = 0L
        private set

    /** 마지막 시퀀스 영상 수. */
    var lastSequenceVideoCount: Int = 0
        private set

    /** 마지막 시퀀스 파싱 상태. */
    var lastSequenceParseStatus: SequenceParseStatus = SequenceParseStatus.NONE
        private set

    /** 현재 시퀀스 계보 (네이티브가 갱신). */
    var currentLineage: SequenceLineageRelation = SequenceLineageRelation.NONE
        private set

    /** 초기 시퀀스 누락 가능성 (문서 시작 스크립트 미지원 등). ObserverBridge가 설정한다. */
    var missedInitialPossible: Boolean = false

    /** 마지막 개별 영상 요청의 영상 식별값. */
    var lastNetworkRequestVideoId: String = ""
        private set

    /** 마지막 DOM 목록 영상 수 (하트비트). */
    var lastDomVideoCount: Int = 0
        private set

    /** 마지막 DOM 목록 해시 (하트비트). */
    var lastDomListHash: String = ""
        private set

    /** 최근 파싱 경고 (최대 20개, 코드 요약만). */
    val recentParseWarnings: List<String> = mutableListOf()

    /** 최근 오류 코드 (마지막 1개). */
    var lastErrorCode: ObserverErrorCode? = null
        private set

    /** 마지막 선택자 적중 통계 (JSON 문자열, 진단용). */
    var lastSelectorStats: String? = null
        private set

    /** 마지막 활성 영상 판정 신뢰도. */
    var lastActiveConfidence: Float = -1f
        private set

    /** 관찰기 메시지를 기록한다. */
    fun record(message: ObserverMessage) {
        lastMessageAtMs = message.ts
        when (message) {
            is ObserverMessage.Heartbeat -> {
                lastHeartbeatAtMs = message.ts
                lastDomVideoCount = message.shortCount
                if (message.listHash.isNotBlank()) lastDomListHash = message.listHash
            }

            is ObserverMessage.ObserverError -> {
                lastDomError = message.code + (message.message?.let { ": $it" } ?: "")
            }

            is ObserverMessage.ListSnapshot -> {
                if (message.selectorStats != null) lastSelectorStats = message.selectorStats
            }

            is ObserverMessage.ActiveShortChanged -> {
                lastActiveConfidence = message.confidence
            }

            is ObserverMessage.NetworkObserverReady -> {
                networkObserverInstalledAtMs = message.installedAt
            }

            is ObserverMessage.NetworkSequenceRequest -> {
                if (firstSequenceRequestAtMs == 0L) firstSequenceRequestAtMs = message.ts
                lastSequenceRequestAtMs = message.ts
            }

            is ObserverMessage.NetworkSequenceResponse -> {
                lastSequenceResponseAtMs = message.ts
                lastSequenceVideoCount = message.items.size
                lastSequenceParseStatus = message.parseStatus
            }

            is ObserverMessage.NetworkVideoRequest -> {
                if (message.videoId.isNotBlank()) lastNetworkRequestVideoId = message.videoId
            }

            is ObserverMessage.NetworkObserverStatus -> {
                if (message.firstSequenceRequestAt > 0L) firstSequenceRequestAtMs = message.firstSequenceRequestAt
                if (message.lastSequenceRequestAt > 0L) lastSequenceRequestAtMs = message.lastSequenceRequestAt
                if (message.lastSequenceResponseAt > 0L) lastSequenceResponseAtMs = message.lastSequenceResponseAt
                lastSequenceVideoCount = message.lastSequenceVideoCount
                lastSequenceParseStatus = message.lastSequenceParseStatus
                missedInitialPossible = missedInitialPossible || message.missedInitialPossible
                if (message.lastRequestVideoId.isNotBlank()) lastNetworkRequestVideoId = message.lastRequestVideoId
                if (message.domVideoCount > 0) lastDomVideoCount = message.domVideoCount
                if (message.domListHash.isNotBlank()) lastDomListHash = message.domListHash
            }

            is ObserverMessage.NetworkParseWarning -> {
                (recentParseWarnings as MutableList).add(message.code)
                val list = recentParseWarnings as MutableList
                while (list.size > 20) list.removeAt(0)
            }

            else -> Unit
        }
    }

    /** 네이티브 계층에서 발생한 오류 코드를 기록한다. */
    fun setError(code: ObserverErrorCode) {
        lastErrorCode = code
    }

    /** 네이티브가 계보 판정 결과를 반영한다. */
    fun updateLineage(relation: SequenceLineageRelation) {
        currentLineage = relation
    }

    /** 관찰기가 살아있는지 판단한다. */
    fun isAlive(
        nowMs: Long = System.currentTimeMillis(),
        timeoutMs: Long = ObserverWatchdog.DEFAULT_TIMEOUT_MS,
    ): Boolean = ObserverWatchdog.isAlive(lastHeartbeatAtMs, nowMs, timeoutMs)

    /** 진단 상태를 초기값으로 되돌린다. 테스트에서 사용한다. */
    fun reset() {
        lastHeartbeatAtMs = 0L
        lastMessageAtMs = 0L
        lastDomError = null
        documentStartSupported = false
        networkObserverInstalledAtMs = 0L
        firstSequenceRequestAtMs = 0L
        lastSequenceRequestAtMs = 0L
        lastSequenceResponseAtMs = 0L
        lastSequenceVideoCount = 0
        lastSequenceParseStatus = SequenceParseStatus.NONE
        currentLineage = SequenceLineageRelation.NONE
        missedInitialPossible = false
        lastNetworkRequestVideoId = ""
        lastDomVideoCount = 0
        lastDomListHash = ""
        (recentParseWarnings as MutableList).clear()
        lastErrorCode = null
        lastSelectorStats = null
        lastActiveConfidence = -1f
    }
}
