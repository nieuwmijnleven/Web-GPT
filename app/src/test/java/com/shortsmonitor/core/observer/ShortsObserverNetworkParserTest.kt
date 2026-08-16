package com.shortsmonitor.core.observer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 네트워크 파서 테스트 (v5).
 *
 * 고정된 안전한 JSON Fixture를 사용해 `reel_watch_sequence` 응답 분석과
 * sequenceParams/요청 본문 파싱을 검증한다. 실제 HAR의 쿠키·토큰·방문자 값은
 * Fixture에 사용하지 않는다. 영상 식별값은 유튜브 형식(11자)의 합성 값만 사용한다.
 *
 * 검증 항목: 정상 응답 / 영상·비영상 혼합 / 중첩 경로 변경 / 일부 필드 누락 /
 * 잘못된 JSON / 예상하지 못한 엔트리 / 중복 영상 식별값 / continuation 존재 여부 /
 * 대용량 응답 제한 / 민감 필드 제거.
 */
@RunWith(RobolectricTestRunner::class)
class ShortsObserverNetworkParserTest {

    private val videoA = "aaa111bbb22"
    private val videoB = "ccc333ddd44"
    private val videoC = "eee555fff66"
    private val videoX = "xxx999yyy00"

    /** 영상 엔트리 JSON. playerParams·softRefreshContinuation·trackingParams를 포함한다. */
    private fun videoEntry(
        videoId: String,
        withContinuation: Boolean = true,
        withPlayerParams: Boolean = true,
    ): String = """{
      "command": {
        "clickTrackingParams": "CLICK_$videoId",
        "commandMetadata": {"webCommandMetadata": {"url": "/shorts/$videoId"}},
        "reelWatchEndpoint": {
          "videoId": "$videoId",
          "playerParams": ${if (withPlayerParams) "\"PLAYER_$videoId\"" else "null"},
          "softRefreshContinuation": ${if (withContinuation) "\"CONT_$videoId\"" else "null"},
          "overlay": {"reelPlayerOverlayRenderer": {"trackingParams": "OVERLAY_$videoId"}}
        }
      },
      "trackingParams": "TRACK_$videoId"
    }"""

    /** 비영상 엔트리 JSON (reelNonVideoContentEndpoint). */
    private fun nonVideoEntry(): String = """{
      "command": {
        "clickTrackingParams": "CLICK_NONVIDEO",
        "reelNonVideoContentEndpoint": {"reelNonVideoContentRenderer": {"text": "guide"}}
      },
      "trackingParams": "TRACK_NONVIDEO"
    }"""

    private fun response(vararg entries: String): String =
        """{"entries": [${entries.joinToString(",")}], "responseContext": {"serviceTrackingParams": []}, "trackingParams": "TOP_TRACK"}"""

    private fun runParser(text: String): JSONObject {
        ShortsObserverHarness.newSession().use { h ->
            h.loadObserver()
            return JSONObject(h.parseSequenceResponse(text))
        }
    }

    @Test
    fun normalResponse_parsesItemsInOrder() {
        val result = runParser(response(videoEntry(videoA), videoEntry(videoB), nonVideoEntry()))

        assertEquals("parsed", result.getString("parseStatus"))
        assertEquals("root_entries", result.getString("detectedShape"))
        assertEquals(videoA, result.getString("currentVideoId"))

        val items = result.getJSONArray("items")
        assertEquals(3, items.length())

        val first = items.getJSONObject(0)
        assertEquals(0, first.getInt("position"))
        assertEquals(videoA, first.getString("videoId"))
        assertEquals("video", first.getString("entryKind"))
        assertTrue(first.getBoolean("isCurrent"))
        assertTrue(first.getBoolean("hasPlayerParams"))
        assertTrue(first.getBoolean("hasContinuation"))
        assertFalse(first.getString("trackingHash").isEmpty())
        assertFalse(first.getString("playerParamsHash").isEmpty())

        val third = items.getJSONObject(2)
        assertEquals("non_video", third.getString("entryKind"))
        assertEquals("reel_non_video_content", third.getString("nonVideoKind"))
        assertFalse(third.getBoolean("isCurrent"))
    }

    @Test
    fun nestedPathChange_isDetectedAndParsed() {
        // 응답 구조가 reelWatchSequenceRenderer.entries로 바뀐 경우에도 파싱한다.
        val text = """{"reelWatchSequenceRenderer": {"entries": [${videoEntry(videoA)}, ${videoEntry(videoB)}]}}"""
        val result = runParser(text)

        assertEquals("parsed", result.getString("parseStatus"))
        assertEquals("reelWatchSequenceRenderer_entries", result.getString("detectedShape"))
        assertEquals(2, result.getJSONArray("items").length())
        assertEquals(videoA, result.getJSONArray("items").getJSONObject(0).getString("videoId"))
        assertEquals(videoB, result.getJSONArray("items").getJSONObject(1).getString("videoId"))
    }

    @Test
    fun missingFields_areTolerated() {
        // 일부 엔트리에 videoId가 없는 경우에도 전체 파싱이 실패하지 않는다.
        val text = """{"entries": [${videoEntry(videoA)}, {"command": {"reelWatchEndpoint": {}}}, ${videoEntry(videoB)}]}"""
        val result = runParser(text)

        assertEquals(3, result.getJSONArray("items").length())
        assertTrue(result.getString("parseStatus") == "parsed" || result.getString("parseStatus") == "partial")
        assertEquals(videoA, result.getString("currentVideoId"))
    }

    @Test
    fun invalidJson_reportsFailureWithoutVideoIds() {
        val result = runParser("not json at all")

        assertEquals("failed", result.getString("parseStatus"))
        assertEquals(0, result.getJSONArray("items").length())
        assertTrue(result.getJSONArray("warnings").toString().contains("response_json_parse_failed"))
    }

    @Test
    fun unexpectedEntries_areSkippedOrClassified() {
        // command가 없는 엔트리와 알 수 없는 command 타입 엔트리.
        val text = """{"entries": [{"foo": "bar"}, ${videoEntry(videoA)}, {"command": {"unknownEndpoint": {}}}]}"""
        val result = runParser(text)

        // video 엔트리는 파싱되고, 알 수 없는 command는 비영상 항목으로 분류된다.
        assertEquals(videoA, result.getString("currentVideoId"))
        val kinds = buildList {
            val items = result.getJSONArray("items")
            for (i in 0 until items.length()) add(items.getJSONObject(i).getString("entryKind"))
        }
        assertTrue(kinds.contains("video"))
        assertTrue(kinds.contains("non_video"))
    }

    @Test
    fun duplicateVideoIds_areDeduplicatedWithWarning() {
        val text = """{"entries": [${videoEntry(videoA)}, ${videoEntry(videoA)}, ${videoEntry(videoB)}]}"""
        val result = runParser(text)

        val ids = buildList {
            val items = result.getJSONArray("items")
            for (i in 0 until items.length()) add(items.getJSONObject(i).getString("videoId"))
        }
        // 첫 항목만 유지되고 중복은 제거된다.
        assertEquals(2, ids.size)
        assertTrue(ids.contains(videoA))
        assertTrue(ids.contains(videoB))
        assertTrue(result.getJSONArray("warnings").toString().contains("duplicate_video_id"))
        assertEquals("partial", result.getString("parseStatus"))
    }

    @Test
    fun continuationPresence_isTrackedWithHashOnly() {
        val result = runParser(response(videoEntry(videoA, withContinuation = true), videoEntry(videoB, withContinuation = false)))

        val first = result.getJSONArray("items").getJSONObject(0)
        assertTrue(first.getBoolean("hasContinuation"))
        assertFalse(first.getString("continuationHash").isEmpty())
        val second = result.getJSONArray("items").getJSONObject(1)
        assertFalse(second.getBoolean("hasContinuation"))
        // 응답 수준 continuation 해시는 존재한다.
        assertFalse(result.getString("continuationHash").isEmpty())
    }

    @Test
    fun oversizedResponse_isRejectedWithLimitWarning() {
        val huge = response(videoEntry(videoA)) + "\",\"padding\":\"" + "x".repeat(2_100_000)
        val result = runParser(huge)

        assertEquals("failed", result.getString("parseStatus"))
        assertTrue(result.getJSONArray("warnings").toString().contains("response_too_large"))
        assertEquals(0, result.getJSONArray("items").length())
    }

    @Test
    fun sensitiveFields_areNotExported() {
        val result = runParser(
            """{"entries": [${videoEntry(videoA)}], "responseContext": {"someToken": "SENSITIVE_CONTEXT_TOKEN"}}""",
        )

        // 원문 값이 결과 어디에도 없어야 한다.
        assertFalse(result.toString().contains("SENSITIVE_CONTEXT_TOKEN"))
        assertFalse(result.toString().contains("PLAYER_$videoA"))
        assertFalse(result.toString().contains("CONT_$videoA"))
        assertFalse(result.toString().contains("TRACK_$videoA"))
        assertFalse(result.toString().contains("CLICK_$videoA"))
        // 대신 안전 해시만 존재한다.
        assertFalse(result.getString("trackingHash").isEmpty())
        assertFalse(result.getString("responseContextHash").isEmpty())
    }

    @Test
    fun sequenceParams_decodeExtractsVideoIdsInOrder() {
        val raw = sequenceParamsOf(videoA, videoB, videoC)
        ShortsObserverHarness.newSession().use { h ->
            h.loadObserver()
            val result = JSONObject(h.decodeSequenceParams(raw))
            assertTrue(result.getBoolean("decoded"))
            val ids = JSONArray(result.getJSONArray("videoIds").toString())
            assertEquals(3, ids.length())
            assertEquals(videoA, ids.getString(0))
            assertEquals(videoB, ids.getString(1))
            assertEquals(videoC, ids.getString(2))
            assertFalse(result.getString("hash").isEmpty())
        }
    }

    @Test
    fun sequenceParams_invalidBase64_reportsErrorWithoutRaw() {
        ShortsObserverHarness.newSession().use { h ->
            h.loadObserver()
            val result = JSONObject(h.decodeSequenceParams("!!!not-base64!!!"))
            assertFalse(result.getBoolean("decoded"))
            assertEquals(0, result.getJSONArray("videoIds").length())
            assertTrue(result.getString("error").isNotEmpty())
        }
    }

    @Test
    fun requestBody_sequence_parsesContextAndParamsSafely() {
        val body = """{"context": {"client": {"clientName": "WEB", "clientVersion": "2.99", "visitorData": "SECRET_VISITOR"}}, "sequenceParams": "${sequenceParamsOf(videoA, videoB)}"}"""
        ShortsObserverHarness.newSession().use { h ->
            h.loadObserver()
            val result = JSONObject(h.parseRequestBody("reel_watch_sequence", body))
            assertEquals(videoA, result.getString("currentVideoId"))
            assertEquals("WEB", result.getString("clientName"))
            assertEquals("2.99", result.getString("clientVersion"))
            assertFalse(result.getString("bodyStructureHash").isEmpty())
            assertFalse(result.getString("requestContextHash").isEmpty())
            // 민감 값이 결과에 노출되지 않는다.
            assertFalse(result.toString().contains("SECRET_VISITOR"))
        }
    }

    @Test
    fun requestBody_videoRequest_extractsVideoId() {
        val body = """{"context": {"client": {"clientName": "MWEB"}}, "videoId": "$videoX", "params": "SECRET_PARAMS"}"""
        ShortsObserverHarness.newSession().use { h ->
            h.loadObserver()
            val result = JSONObject(h.parseRequestBody("player", body))
            assertEquals(videoX, result.getString("videoId"))
            assertFalse(result.toString().contains("SECRET_PARAMS"))
        }
    }

    @Test
    fun requestBody_invalidJson_reportsWarning() {
        ShortsObserverHarness.newSession().use { h ->
            h.loadObserver()
            val result = JSONObject(h.parseRequestBody("player", "not json"))
            assertTrue(result.getJSONArray("warnings").toString().contains("request_body_parse_failed"))
            assertFalse(result.getString("bodyStructureHash").isEmpty())
        }
    }

    /** 11자 영상 식별값 순서를 protobuf 스타일(0x0a 0x0b + 11바이트)로 인코딩해 base64url로 만든다. */
    private fun sequenceParamsOf(vararg videoIds: String): String {
        val bytes = ArrayList<Int>()
        videoIds.forEach { id ->
            bytes.add(0x0a)
            bytes.add(0x0b)
            id.forEach { ch -> bytes.add(ch.code) }
        }
        val encoded = encodeBase64Url(bytes)
        return encoded
    }

    private fun encodeBase64Url(bytes: List<Int>): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder()
        var i = 0
        while (i + 2 < bytes.size || i < bytes.size) {
            val b0 = bytes.getOrElse(i) { 0 }
            val b1 = bytes.getOrElse(i + 1) { 0 }
            val b2 = bytes.getOrElse(i + 2) { 0 }
            sb.append(chars[(b0 shr 2) and 0x3f])
            sb.append(chars[((b0 and 0x03) shl 4) or ((b1 shr 4) and 0x0f)])
            if (i + 1 < bytes.size) {
                sb.append(chars[((b1 and 0x0f) shl 2) or ((b2 shr 6) and 0x03)])
            } else {
                sb.append('=')
            }
            if (i + 2 < bytes.size) {
                sb.append(chars[b2 and 0x3f])
            } else {
                sb.append('=')
            }
            i += 3
        }
        return sb.toString().replace('+', '-').replace('/', '_')
    }
}
