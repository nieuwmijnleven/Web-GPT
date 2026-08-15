package com.shortsmonitor.core.observer

import com.shortsmonitor.core.model.ShortIdentityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortIdentityTest {

    private fun shortInfo(
        videoId: String = "",
        url: String = "",
        thumbnail: String = "",
        title: String = "T",
        channel: String = "C",
        identitySource: ShortIdentitySource,
        identityKey: String,
    ) = ShortInfo(
        videoId = videoId,
        url = url,
        title = title,
        channel = channel,
        thumbnail = thumbnail,
        identitySource = identitySource,
        identityKey = identityKey,
    )

    @Test
    fun videoId_is_reliable() {
        val resolved = ShortIdentity.resolve(
            shortInfo(videoId = "abc123", identitySource = ShortIdentitySource.VIDEO_ID, identityKey = "abc123"),
        )

        assertEquals("abc123", resolved.videoId)
        assertEquals(ShortIdentityStatus.RELIABLE, resolved.status)
    }

    @Test
    fun url_source_falls_back_to_temporary() {
        val resolved = ShortIdentity.resolve(
            shortInfo(
                url = "https://youtube.com/shorts/xyz",
                identitySource = ShortIdentitySource.URL,
                identityKey = "https://youtube.com/shorts/xyz",
            ),
        )

        assertTrue(resolved.videoId.startsWith("tmp_"))
        assertEquals(ShortIdentityStatus.TEMPORARY, resolved.status)
    }

    @Test
    fun hash_source_falls_back_to_temporary() {
        val resolved = ShortIdentity.resolve(
            shortInfo(identitySource = ShortIdentitySource.HASH, identityKey = "h1"),
        )

        assertTrue(resolved.videoId.startsWith("tmp_"))
        assertEquals(ShortIdentityStatus.TEMPORARY, resolved.status)
    }

    @Test
    fun temporaryId_is_stable_for_same_key() {
        assertEquals(
            ShortIdentity.temporaryId("https://example.com/thumb.jpg"),
            ShortIdentity.temporaryId("https://example.com/thumb.jpg"),
        )
    }

    @Test
    fun temporaryId_differs_for_different_keys() {
        assertNotEquals(
            ShortIdentity.temporaryId("key-a"),
            ShortIdentity.temporaryId("key-b"),
        )
    }

    @Test
    fun temporaryId_for_blank_key_is_stable() {
        assertEquals(ShortIdentity.temporaryId(""), ShortIdentity.temporaryId(" "))
    }
}
