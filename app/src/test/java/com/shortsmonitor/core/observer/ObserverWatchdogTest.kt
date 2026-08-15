package com.shortsmonitor.core.observer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserverWatchdogTest {

    @Test
    fun alive_when_heartbeat_within_timeout() {
        assertTrue(ObserverWatchdog.isAlive(lastHeartbeatAtMs = 1_000L, nowMs = 5_000L, timeoutMs = 15_000L))
    }

    @Test
    fun stale_when_heartbeat_older_than_timeout() {
        assertFalse(ObserverWatchdog.isAlive(lastHeartbeatAtMs = 1_000L, nowMs = 17_000L, timeoutMs = 15_000L))
    }

    @Test
    fun exactly_at_timeout_is_alive() {
        assertTrue(ObserverWatchdog.isAlive(lastHeartbeatAtMs = 1_000L, nowMs = 16_000L, timeoutMs = 15_000L))
    }

    @Test
    fun never_received_heartbeat_is_not_alive() {
        assertFalse(ObserverWatchdog.isAlive(lastHeartbeatAtMs = 0L, nowMs = 5_000L))
    }
}
