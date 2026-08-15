package com.shortsmonitor.core.observer

import com.shortsmonitor.core.model.SnapshotChangeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ObserverMessageTest {

    @Test
    fun parsesObserverReady() {
        val message = ObserverMessage.parse(
            """
            {"type":"observer_ready","seq":1,"ts":1700000000123,
             "data":{"observerVersion":"1.0.0","adapterVersion":"1.0.0",
                     "url":"https://m.youtube.com/shorts","title":"Shorts"}}
            """.trimIndent(),
        )

        assertTrue(message is ObserverMessage.ObserverReady)
        message as ObserverMessage.ObserverReady
        assertEquals(1, message.seq)
        assertEquals(1700000000123L, message.ts)
        assertEquals("1.0.0", message.observerVersion)
        assertEquals("1.0.0", message.adapterVersion)
        assertEquals("https://m.youtube.com/shorts", message.url)
        assertEquals("Shorts", message.title)
    }

    @Test
    fun parsesPageInfo() {
        val message = ObserverMessage.parse(
            """{"type":"page_info","seq":2,"ts":1,
                "data":{"url":"https://m.youtube.com/shorts/abc123","title":"T","activeVideoId":"abc123"}}""",
        )

        assertTrue(message is ObserverMessage.PageInfo)
        message as ObserverMessage.PageInfo
        assertEquals("abc123", message.activeVideoId)
        assertEquals("https://m.youtube.com/shorts/abc123", message.url)
    }

    @Test
    fun parsesActiveShortChanged() {
        val message = ObserverMessage.parse(
            """
            {"type":"active_short_changed","seq":3,"ts":2,
             "data":{"index":1,"count":3,
                     "short":{"videoId":"vid123","url":"https://youtube.com/shorts/vid123",
                              "title":"Hello","channel":"Chan","thumbnail":"https://i.ytimg.com/x.jpg",
                              "identitySource":"video_id","identityKey":"vid123"}}}
            """.trimIndent(),
        )

        assertTrue(message is ObserverMessage.ActiveShortChanged)
        message as ObserverMessage.ActiveShortChanged
        assertEquals(1, message.index)
        assertEquals(3, message.count)
        assertEquals("vid123", message.short.videoId)
        assertEquals("Hello", message.short.title)
        assertEquals(ShortIdentitySource.VIDEO_ID, message.short.identitySource)
    }

    @Test
    fun parsesListSnapshot() {
        val message = ObserverMessage.parse(
            """
            {"type":"list_snapshot","seq":4,"ts":3,
             "data":{"revision":2,"reason":"item_added","url":"https://m.youtube.com/shorts",
                     "shorts":[
                       {"videoId":"a","url":"u1","title":"A","channel":"c","thumbnail":"t",
                        "identitySource":"video_id","identityKey":"a"},
                       {"videoId":"","url":"","title":"B","channel":"c","thumbnail":"t",
                        "identitySource":"hash","identityKey":"h1"}
                     ]}}
            """.trimIndent(),
        )

        assertTrue(message is ObserverMessage.ListSnapshot)
        message as ObserverMessage.ListSnapshot
        assertEquals(2, message.revision)
        assertEquals(SnapshotChangeReason.ITEM_ADDED, message.reason)
        assertEquals(2, message.shorts.size)
        assertEquals("a", message.shorts[0].videoId)
        assertEquals(ShortIdentitySource.HASH, message.shorts[1].identitySource)
        assertEquals("h1", message.shorts[1].identityKey)
    }

    @Test
    fun mapsAllKnownSnapshotReasons() {
        fun reasonFor(value: String) = (
            ObserverMessage.parse(
                """{"type":"list_snapshot","seq":1,"ts":1,"data":{"revision":1,"reason":"$value","url":"u","shorts":[]}}""",
            ) as ObserverMessage.ListSnapshot
            ).reason

        assertEquals(SnapshotChangeReason.INITIAL, reasonFor("initial"))
        assertEquals(SnapshotChangeReason.ITEM_ADDED, reasonFor("item_added"))
        assertEquals(SnapshotChangeReason.ITEM_REMOVED, reasonFor("item_removed"))
        assertEquals(SnapshotChangeReason.ORDER_CHANGED, reasonFor("order_changed"))
        assertEquals(SnapshotChangeReason.ACTIVE_CHANGED, reasonFor("active_changed"))
        assertEquals(SnapshotChangeReason.DOM_REBUILT, reasonFor("dom_rebuilt"))
        assertEquals(SnapshotChangeReason.NAVIGATION, reasonFor("navigation"))
        assertEquals(SnapshotChangeReason.FULL_RELOAD, reasonFor("full_reload"))
    }

    @Test
    fun unknownReasonMapsToDomRebuilt() {
        val message = ObserverMessage.parse(
            """{"type":"list_snapshot","seq":1,"ts":1,"data":{"revision":1,"reason":"weird","url":"u","shorts":[]}}""",
        ) as ObserverMessage.ListSnapshot

        assertEquals(SnapshotChangeReason.DOM_REBUILT, message.reason)
    }

    @Test
    fun parsesDomRebuilt() {
        val message = ObserverMessage.parse(
            """{"type":"dom_rebuilt","seq":5,"ts":4,"data":{"revision":3}}""",
        )

        assertTrue(message is ObserverMessage.DomRebuilt)
        message as ObserverMessage.DomRebuilt
        assertEquals(3, message.revision)
    }

    @Test
    fun parsesObserverError() {
        val message = ObserverMessage.parse(
            """{"type":"observer_error","seq":6,"ts":5,
                "data":{"code":"feed_not_found","message":"shorts feed container not found"}}""",
        )

        assertTrue(message is ObserverMessage.ObserverError)
        message as ObserverMessage.ObserverError
        assertEquals("feed_not_found", message.code)
        assertEquals("shorts feed container not found", message.message)
    }

    @Test
    fun parsesHeartbeat() {
        val message = ObserverMessage.parse(
            """{"type":"heartbeat","seq":7,"ts":1700000006000,
                "data":{"revision":4,"shortCount":5,"activeVideoId":"v1","observerVersion":"1.0.0"}}""",
        )

        assertTrue(message is ObserverMessage.Heartbeat)
        message as ObserverMessage.Heartbeat
        assertEquals(4, message.revision)
        assertEquals(5, message.shortCount)
        assertEquals("v1", message.activeVideoId)
        assertEquals("1.0.0", message.observerVersion)
    }

    @Test
    fun rejectsMalformedJson() {
        assertNull(ObserverMessage.parse("not json"))
        assertNull(ObserverMessage.parse(""))
        assertNull(ObserverMessage.parse("{broken"))
    }

    @Test
    fun rejectsUnknownType() {
        assertNull(ObserverMessage.parse("""{"type":"something_else","seq":1,"ts":1,"data":{}}"""))
    }

    @Test
    fun rejectsMissingType() {
        assertNull(ObserverMessage.parse("""{"seq":1,"ts":1,"data":{}}"""))
    }

    @Test
    fun rejectsActiveChangedWithoutShort() {
        assertNull(
            ObserverMessage.parse(
                """{"type":"active_short_changed","seq":1,"ts":1,"data":{"index":0,"count":1}}""",
            ),
        )
    }
}
