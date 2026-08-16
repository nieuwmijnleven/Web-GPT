package com.shortsmonitor.core.observer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 네트워크 관찰기 WebView 테스트 (문서 시작 스크립트).
 *
 * 고정된 가짜 fetch/XHR 환경에서 관찰기가 `reel_watch_sequence` 요청/응답을
 * 관찰하되 원본 동작을 변경하지 않는지 검증한다.
 *
 * 주의: 고정 HTML/가짜 환경 테스트만으로 실제 YouTube 호환성이 증명되지는 않는다.
 * 실제 기기 WebView에서의 추가 확인이 필요하다.
 */
@RunWith(RobolectricTestRunner::class)
class ShortsObserverScriptNetworkTest {

    private val videoA = "aaa111bbb22"
    private val videoB = "ccc333ddd44"

    private val sequenceUrl = "https://m.youtube.com/youtubei/v1/reel/reel_watch_sequence?prettyPrint=false"
    private val playerUrl = "https://m.youtube.com/youtubei/v1/player?prettyPrint=false"

    private val responseText =
        """{"entries": [{"command": {"reelWatchEndpoint": {"videoId": "$videoA", "playerParams": "p", "softRefreshContinuation": "c"}}}]}"""

    private fun messages(h: ShortsObserverHarness.Session): List<JSONObject> = buildList {
        val array = h.messages()
        for (i in 0 until array.length()) add(array.getJSONObject(i))
    }

    private fun messagesOf(h: ShortsObserverHarness.Session, type: String): List<JSONObject> =
        messages(h).filter { it.getString("type") == type }

    @Test
    fun fetch_sequenceRequest_isObservedWithoutChangingOriginal() {
        ShortsObserverHarness.newSession().use { h ->
            h.setFetchResponse(responseText)
            h.loadObserver()
            h.clearMessages()

            val body = """{"context":{"client":{"clientName":"MWEB","clientVersion":"1.0"}},"sequenceParams":""}"""
            h.fetch(sequenceUrl, body)

            // 원본 fetch가 같은 인자로 호출됐다 (원본 동작 유지).
            val calls = h.fetchCalls()
            assertEquals(1, calls.length())
            assertEquals(sequenceUrl, calls.getJSONObject(0).getString("url"))
            assertEquals(body, calls.getJSONObject(0).getString("body"))

            // 원본 소비자에게 전달된 응답이 그대로다 (clone으로 분석, 원본 미소비).
            assertEquals("fakeResponse", h.fetchResultTag())

            // 요청 관찰 메시지가 발행됐다.
            val requests = messagesOf(h, "network_sequence_request")
            assertEquals(1, requests.size)
            val requestData = requests[0].getJSONObject("data")
            assertEquals("reel_watch_sequence", requestData.getString("requestKind"))
            assertEquals(sequenceUrl, requestData.getString("requestUrl"))
            assertTrue(requestData.getString("correlationId").isNotEmpty())

            // 응답 분석 메시지가 발행됐다.
            val responses = messagesOf(h, "network_sequence_response")
            assertEquals(1, responses.size)
            val responseData = responses[0].getJSONObject("data")
            assertEquals(videoA, responseData.getString("currentVideoId"))
            assertEquals("parsed", responseData.getString("parseStatus"))
        }
    }

    @Test
    fun fetch_returnsOriginalResponseToCaller() {
        ShortsObserverHarness.newSession().use { h ->
            h.setFetchResponse(responseText)
            h.loadObserver()
            h.clearMessages()

            h.fetch(sequenceUrl, "{}")

            // 원본 fetch의 반환 객체(응답)가 호출자에게 그대로 전달된다.
            // 분석은 clone()에서만 수행되므로 원본 Response를 소비하지 않는다.
            assertEquals("fakeResponse", h.fetchResultTag())
            // 요청·응답 분석 메시지는 정상 발행됐다.
            assertEquals(1, messagesOf(h, "network_sequence_request").size)
            assertEquals(1, messagesOf(h, "network_sequence_response").size)
        }
    }

    @Test
    fun xhr_sequenceRequest_isObservedWithoutChangingOriginal() {
        ShortsObserverHarness.newSession().use { h ->
            h.setXhrResponse(responseText)
            h.loadObserver()
            h.clearMessages()

            val body = """{"context":{},"sequenceParams":""}"""
            h.xhrSend(sequenceUrl, body)

            // 원본 XHR send가 같은 인자로 호출됐다.
            val sends = h.xhrSends()
            assertEquals(1, sends.length())
            assertEquals(sequenceUrl, sends.getJSONObject(0).getString("url"))
            assertEquals(body, sends.getJSONObject(0).getString("body"))

            val requests = messagesOf(h, "network_sequence_request")
            assertEquals(1, requests.size)
            val responses = messagesOf(h, "network_sequence_response")
            assertEquals(1, responses.size)
            assertEquals(videoA, responses[0].getJSONObject("data").getString("currentVideoId"))
        }
    }

    @Test
    fun videoRequest_isObservedAsNetworkVideoRequest() {
        ShortsObserverHarness.newSession().use { h ->
            h.loadObserver()
            h.clearMessages()

            val body = """{"context":{},"videoId":"$videoA"}"""
            h.fetch(playerUrl, body)

            val videoRequests = messagesOf(h, "network_video_request")
            assertEquals(1, videoRequests.size)
            val data = videoRequests[0].getJSONObject("data")
            assertEquals("player", data.getString("requestKind"))
            assertEquals(videoA, data.getString("videoId"))
            // 시퀀스 요청/응답 메시지는 없다.
            assertTrue(messagesOf(h, "network_sequence_request").isEmpty())
            assertTrue(messagesOf(h, "network_sequence_response").isEmpty())
        }
    }

    @Test
    fun unrelatedRequests_areIgnored() {
        ShortsObserverHarness.newSession().use { h ->
            h.loadObserver()
            h.clearMessages()

            h.fetch("https://m.youtube.com/some/other", "{}")
            h.xhrSend("https://m.youtube.com/watch?v=xyz", "{}")

            assertTrue(messagesOf(h, "network_sequence_request").isEmpty())
            assertTrue(messagesOf(h, "network_video_request").isEmpty())
            assertTrue(messagesOf(h, "network_sequence_response").isEmpty())
        }
    }

    @Test
    fun networkObserverReady_reportsInstallation() {
        ShortsObserverHarness.newSession().use { h ->
            h.loadObserver()
            val ready = messagesOf(h, "network_observer_ready")
            assertEquals(1, ready.size)
            val data = ready[0].getJSONObject("data")
            assertTrue(data.getBoolean("fetchWrapped"))
            assertTrue(data.getBoolean("xhrWrapped"))
            assertEquals(ShortsObserverScript.NETWORK_PARSER_VERSION, data.getString("parserVersion"))
            assertTrue(data.getLong("installedAt") > 0L)
        }
    }

    @Test
    fun networkParseWarning_isPostedOnBadResponse() {
        ShortsObserverHarness.newSession().use { h ->
            h.setFetchResponse("this is not json")
            h.loadObserver()
            h.clearMessages()

            h.fetch(sequenceUrl, "{}")

            val responses = messagesOf(h, "network_sequence_response")
            assertEquals(1, responses.size)
            assertEquals("failed", responses[0].getJSONObject("data").getString("parseStatus"))
            val warnings = messagesOf(h, "network_parse_warning")
            assertTrue(warnings.isNotEmpty())
            assertTrue(
                warnings.any {
                    it.getJSONObject("data").getString("code").contains("response_json_parse_failed")
                },
            )
        }
    }
}
