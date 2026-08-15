package com.shortsmonitor.core.observer

/**
 * 관찰기 진단 상태 저장소 (O단계 WebView 진단).
 *
 * [ObserverBridge]가 관찰기 메시지를 받을 때마다 이 저장소를 갱신한다.
 * WebView 진단 화면은 여기에서 관찰기 상태·마지막 상태 확인 시각·마지막 DOM 오류를 읽는다.
 * 진단 정보에 민감한 쿠키나 인증값은 포함되지 않는다.
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

    /** 관찰기 메시지를 기록한다. */
    fun record(message: ObserverMessage) {
        lastMessageAtMs = message.ts
        if (message is ObserverMessage.Heartbeat) {
            lastHeartbeatAtMs = message.ts
        }
        if (message is ObserverMessage.ObserverError) {
            lastDomError = message.code + (message.message?.let { ": $it" } ?: "")
        }
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
    }
}
