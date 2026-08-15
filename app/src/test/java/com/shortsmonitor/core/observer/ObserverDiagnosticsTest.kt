package com.shortsmonitor.core.observer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ObserverDiagnosticsTest {

    @Before
    fun setUp() {
        // 싱글턴 상태가 테스트 간에 누수되지 않도록 초기화한다.
        ObserverDiagnostics.reset()
    }

    @Test
    fun record_heartbeat_updatesLastHeartbeatAndMessageTime() {
        val message = ObserverMessage.Heartbeat(
            seq = 1,
            ts = 5_000L,
            revision = 3,
            shortCount = 4,
            activeVideoId = "a",
            observerVersion = "1",
        )
        ObserverDiagnostics.record(message)
        assertEquals(5_000L, ObserverDiagnostics.lastHeartbeatAtMs)
        assertEquals(5_000L, ObserverDiagnostics.lastMessageAtMs)
    }

    @Test
    fun record_observerError_setsLastDomError() {
        ObserverDiagnostics.record(
            ObserverMessage.ObserverError(
                seq = 2,
                ts = 6_000L,
                code = "feed_not_found",
                message = "no feed container",
            ),
        )
        assertEquals("feed_not_found: no feed container", ObserverDiagnostics.lastDomError)
    }

    @Test
    fun isAlive_falseWithoutHeartbeat() {
        assertFalse(ObserverDiagnostics.isAlive(nowMs = 1_000L, timeoutMs = 15_000L))
    }

    @Test
    fun isAlive_trueWithinTimeout() {
        ObserverDiagnostics.record(
            ObserverMessage.Heartbeat(
                seq = 1,
                ts = 5_000L,
                revision = 1,
                shortCount = 4,
                activeVideoId = "a",
                observerVersion = "1",
            ),
        )
        assertTrue(ObserverDiagnostics.isAlive(nowMs = 6_000L, timeoutMs = 15_000L))
        assertFalse(ObserverDiagnostics.isAlive(nowMs = 30_000L, timeoutMs = 15_000L))
    }

    @Test
    fun isAlive_falseAfterTimeout() {
        ObserverDiagnostics.record(
            ObserverMessage.Heartbeat(
                seq = 1,
                ts = 5_000L,
                revision = 1,
                shortCount = 4,
                activeVideoId = "a",
                observerVersion = "1",
            ),
        )
        assertFalse(ObserverDiagnostics.isAlive(nowMs = 30_000L, timeoutMs = 15_000L))
    }
}
