package com.shortsmonitor.core.observer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortsObserverScriptTest {

    @Test
    fun placeholders_are_replaced() {
        val script = ShortsObserverScript.script

        assertFalse("guard flag placeholder left", script.contains("${'$'}GUARD_FLAG"))
        assertFalse("restart placeholder left", script.contains("${'$'}RESTART_FUNCTION"))
        assertFalse("observer version placeholder left", script.contains("${'$'}OBSERVER_VERSION"))
        assertFalse("adapter version placeholder left", script.contains("${'$'}ADAPTER_VERSION"))
        assertFalse("bridge name placeholder left", script.contains("${'$'}BRIDGE_OBJECT_NAME"))
        assertFalse("heartbeat placeholder left", script.contains("${'$'}HEARTBEAT_INTERVAL"))
        assertFalse("debounce placeholder left", script.contains("${'$'}SNAPSHOT_DEBOUNCE"))
    }

    @Test
    fun contains_core_elements() {
        val script = ShortsObserverScript.script

        assertTrue(script.contains("MutationObserver"))
        assertTrue(script.contains(ShortsObserverScript.BRIDGE_OBJECT_NAME))
        assertTrue(script.contains(ShortsObserverScript.GUARD_FLAG))
        assertTrue(script.contains(ShortsObserverScript.RESTART_FUNCTION))
        assertTrue(script.contains("list_snapshot"))
        assertTrue(script.contains("active_short_changed"))
        assertTrue(script.contains("heartbeat"))
        assertTrue(script.contains("observer_error"))
        assertTrue(script.contains("dom_rebuilt"))
    }

    @Test
    fun adapter_methods_are_present() {
        val script = ShortsObserverScript.script

        ShortsDomAdapter.METHODS.forEach { method ->
            assertTrue("adapter method missing: $method", script.contains("$method: function"))
        }
    }
}
