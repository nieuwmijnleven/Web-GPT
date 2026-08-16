package com.shortsmonitor.core.observer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * JavaScript 관찰기 고정 데이터 테스트 (P단계).
 *
 * 유튜브 실페이지에 의존하지 않고 고정 HTML 문자열을 가짜 DOM에 주입해
 * 관찰기가 내보내는 메시지를 검증한다. 영상 식별값은 유튜브와 같은 11자리를 사용한다.
 *
 * 테스트 상황: 최초 목록 생성 / 목록 끝 추가 / 목록 중간 추가 / 항목 제거 /
 * 항목 순서 변경 / 같은 항목 재렌더링 / 컨테이너 전체 재생성 / 활성 영상 변경.
 */
@RunWith(RobolectricTestRunner::class)
class ShortsObserverFixedHtmlTest {

    // 유튜브 영상 식별값 형식(11자리)을 따르는 고정 식별값.
    private val videoA = "aaa111bbb22"
    private val videoB = "ccc333ddd44"
    private val videoC = "eee555fff66"
    private val videoX = "xxx999yyy00"

    /** 유튜브 모바일 웹과 유사한 고정 쇼츠 항목 HTML. */
    private fun item(
        id: String,
        title: String = "영상 $id",
        active: Boolean = false,
    ): String =
        "<ytm-shorts-video-renderer data-video-id=\"$id\"${if (active) " is-active" else ""}>" +
            "<a href=\"https://m.youtube.com/shorts/$id\">" +
            "<ytm-shorts-lockup-view-model__title>$title</ytm-shorts-lockup-view-model__title>" +
            "<div class=\"channel-name\">채널 $id</div>" +
            "<img class=\"yt-core-image\" src=\"https://i.ytimg.com/$id.jpg\">" +
            "</a>" +
            "</ytm-shorts-video-renderer>"

    private fun JSONArray.snapshotMessages(): List<JSONObject> = buildList {
        for (i in 0 until length()) {
            val message = getJSONObject(i)
            if (message.getString("type") == "list_snapshot") add(message)
        }
    }

    private fun JSONObject.snapshotReason(): String = getJSONObject("data").getString("reason")

    private fun JSONObject.videoIds(): List<String> {
        val shorts = getJSONObject("data").getJSONArray("shorts")
        return buildList {
            for (i in 0 until shorts.length()) {
                add(shorts.getJSONObject(i).getString("videoId"))
            }
        }
    }

    /** 초기 목록(A, B)을 만들고 관찰기를 로드한다. */
    private fun ShortsObserverHarness.Session.observeInitial() {
        setItems(item(videoA) + item(videoB))
        loadObserver()
    }

    @Test
    fun initialList_publishesInitialSnapshotWithExtractedValues() {
        ShortsObserverHarness.newSession().use { h ->
            h.setItems(item(videoA, active = true) + item(videoB))
            h.loadObserver()

            val messages = h.messages()
            // 준비 완료·페이지 정보·초기 목록 스냅샷이 순서대로 발행된다.
            assertTrue(messages.toString().contains("observer_ready"))
            assertTrue(messages.toString().contains("page_info"))

            val snapshots = messages.snapshotMessages()
            assertEquals(1, snapshots.size)
            assertEquals("initial", snapshots[0].snapshotReason())
            assertEquals(listOf(videoA, videoB), snapshots[0].videoIds())

            // 고정 HTML에서 추출한 값 검증.
            val first = snapshots[0].getJSONObject("data").getJSONArray("shorts").getJSONObject(0)
            assertEquals(videoA, first.getString("videoId"))
            assertEquals("https://m.youtube.com/shorts/$videoA", first.getString("url"))
            assertEquals("영상 $videoA", first.getString("title"))
            assertEquals("채널 $videoA", first.getString("channel"))
            assertEquals("https://i.ytimg.com/$videoA.jpg", first.getString("thumbnail"))
            assertEquals("video_id", first.getString("identitySource"))
        }
    }

    @Test
    fun endOfListAddition_publishesItemAdded() {
        ShortsObserverHarness.newSession().use { h ->
            h.observeInitial()
            h.clearMessages()

            h.setItems(item(videoA) + item(videoB) + item(videoX))
            h.flushTimers(500)

            val snapshots = h.messages().snapshotMessages()
            assertEquals(1, snapshots.size)
            assertEquals("item_added", snapshots[0].snapshotReason())
            assertEquals(listOf(videoA, videoB, videoX), snapshots[0].videoIds())
        }
    }

    @Test
    fun middleInsertion_publishesItemAddedInPosition() {
        ShortsObserverHarness.newSession().use { h ->
            h.observeInitial()
            h.clearMessages()

            h.setItems(item(videoA) + item(videoX) + item(videoB))
            h.flushTimers(500)

            val snapshots = h.messages().snapshotMessages()
            assertEquals(1, snapshots.size)
            assertEquals("item_added", snapshots[0].snapshotReason())
            assertEquals(listOf(videoA, videoX, videoB), snapshots[0].videoIds())
        }
    }

    @Test
    fun itemRemoval_publishesItemRemoved() {
        ShortsObserverHarness.newSession().use { h ->
            h.setItems(item(videoA) + item(videoB) + item(videoC))
            h.loadObserver()
            h.clearMessages()

            h.setItems(item(videoA) + item(videoC))
            h.flushTimers(500)

            val snapshots = h.messages().snapshotMessages()
            assertEquals(1, snapshots.size)
            assertEquals("item_removed", snapshots[0].snapshotReason())
            assertEquals(listOf(videoA, videoC), snapshots[0].videoIds())
        }
    }

    @Test
    fun orderChange_publishesOrderChanged() {
        ShortsObserverHarness.newSession().use { h ->
            h.setItems(item(videoA) + item(videoB) + item(videoC))
            h.loadObserver()
            h.clearMessages()

            h.setItems(item(videoA) + item(videoC) + item(videoB))
            h.flushTimers(500)

            val snapshots = h.messages().snapshotMessages()
            assertEquals(1, snapshots.size)
            assertEquals("order_changed", snapshots[0].snapshotReason())
            assertEquals(listOf(videoA, videoC, videoB), snapshots[0].videoIds())
        }
    }

    @Test
    fun sameItemReRender_doesNotPublishDuplicateSnapshot() {
        ShortsObserverHarness.newSession().use { h ->
            h.observeInitial()
            h.clearMessages()

            // 같은 영상(같은 안정 식별 키)이 제목만 바뀌어 재렌더링된 경우.
            h.setItems(item(videoA, title = "변경된 제목") + item(videoB))
            h.flushTimers(500)

            // 중복 스냅샷을 발행하지 않는다.
            assertTrue(h.messages().snapshotMessages().isEmpty())
        }
    }

    @Test
    fun containerRebuild_publishesDomRebuiltAndFreshBaseline() {
        ShortsObserverHarness.newSession().use { h ->
            h.observeInitial()
            h.clearMessages()

            // 컨테이너 자체가 새 요소로 재생성된 경우 (1초 주기의 컨테이너 감시로 감지).
            h.rebuildFeed(item(videoA) + item(videoB))
            h.flushTimers(1500)
            h.flushTimers(500)

            val messages = h.messages()
            assertTrue(messages.toString().contains("dom_rebuilt"))
            val snapshots = messages.snapshotMessages()
            assertEquals(1, snapshots.size)
            // 재생성 직후에는 기준 목록을 새로 세운다.
            assertEquals("initial", snapshots[0].snapshotReason())
            assertEquals(listOf(videoA, videoB), snapshots[0].videoIds())
        }
    }

    @Test
    fun activeVideoChange_publishesActiveShortChanged() {
        ShortsObserverHarness.newSession().use { h ->
            h.setItems(item(videoA, active = true) + item(videoB))
            h.loadObserver()
            h.clearMessages()

            h.setActive(1)
            h.flushTimers(300)

            val messages = h.messages()
            val activeChanged = buildList {
                for (i in 0 until messages.length()) {
                    val m = messages.getJSONObject(i)
                    if (m.getString("type") == "active_short_changed") add(m)
                }
            }
            assertEquals(1, activeChanged.size)
            val data = activeChanged[0].getJSONObject("data")
            assertEquals(videoB, data.getJSONObject("short").getString("videoId"))
            assertEquals(1, data.getInt("index"))
            assertEquals(2, data.getInt("count"))
        }
    }

    @Test
    fun activeWithoutVideoId_publishesActiveShortChangedWithIdentityKey() {
        // 영상 식별값을 추출할 수 없는 DOM에서도 활성 영상은 발행되어야 한다.
        // (is-active 등으로 활성 항목은 감지되지만 videoId가 없는 경우)
        ShortsObserverHarness.newSession().use { h ->
            h.setItems(
                "<ytm-shorts-video-renderer is-active>" +
                    "<ytm-shorts-lockup-view-model__title>제목</ytm-shorts-lockup-view-model__title>" +
                    "</ytm-shorts-video-renderer>" +
                    "<ytm-shorts-video-renderer>" +
                    "<ytm-shorts-lockup-view-model__title>제목2</ytm-shorts-lockup-view-model__title>" +
                    "</ytm-shorts-video-renderer>",
            )
            h.loadObserver()
            h.flushTimers(300)

            val messages = h.messages()
            val activeChanged = buildList {
                for (i in 0 until messages.length()) {
                    val m = messages.getJSONObject(i)
                    if (m.getString("type") == "active_short_changed") add(m)
                }
            }
            // 영상 식별값이 없어도 identityKey 기반으로 활성 변경이 발행된다.
            assertTrue(activeChanged.isNotEmpty())
            val data = activeChanged[0].getJSONObject("data")
            val short = data.getJSONObject("short")
            assertEquals("", short.getString("videoId"))
            assertTrue(short.getString("identityKey").isNotEmpty())
            assertEquals(0, data.getInt("index"))
        }
    }
}
