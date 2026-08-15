package com.shortsmonitor.core.observer

/**
 * JavaScript 관찰기 하트비트 감시 로직.
 * 관찰기가 주기적으로 보내는 하트비트가 일정 시간 동안 없으면
 * 관찰기가 중단된 것으로 판단해 재시작한다.
 */
object ObserverWatchdog {

    /** 하트비트가 이 시간 이상 없으면 관찰기 중단으로 판단한다. */
    const val DEFAULT_TIMEOUT_MS = 15_000L

    /** 네이티브가 관찰기 상태를 확인하는 주기. */
    const val CHECK_INTERVAL_MS = 5_000L

    /**
     * 관찰기가 살아있는지 판단한다.
     * [lastHeartbeatAtMs]가 0이면 아직 하트비트를 받지 못한 상태로 중단으로 본다.
     */
    fun isAlive(lastHeartbeatAtMs: Long, nowMs: Long, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        if (lastHeartbeatAtMs <= 0L) return false
        return nowMs - lastHeartbeatAtMs <= timeoutMs
    }
}
