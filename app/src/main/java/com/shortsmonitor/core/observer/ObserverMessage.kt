package com.shortsmonitor.core.observer

import com.shortsmonitor.core.model.SnapshotChangeReason
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 쇼츠 항목의 안정적 식별 출처.
 * G단계 식별 우선순위(영상 식별값 > 주소 > DOM 데이터 속성 > 썸네일 > 해시)와 동일하다.
 */
enum class ShortIdentitySource {
    VIDEO_ID,
    URL,
    DATA_ATTR,
    THUMBNAIL,
    HASH,
}

/** JavaScript 관찰기가 추출한 쇼츠 항목 정보. */
data class ShortInfo(
    val videoId: String,
    val url: String,
    val title: String,
    val channel: String,
    val thumbnail: String,
    val identitySource: ShortIdentitySource,
    val identityKey: String,
)

/**
 * JavaScript 관찰기 → 네이티브 메시지.
 * 모든 메시지는 JSON 문자열로 수신되며 [ObserverMessage.parse]로 파싱한다.
 * 형식이 맞지 않거나 알 수 없는 메시지는 파싱 단계에서 걸러진다.
 */
sealed class ObserverMessage {
    abstract val seq: Int
    abstract val ts: Long

    /** 관찰기 준비 완료 */
    data class ObserverReady(
        override val seq: Int,
        override val ts: Long,
        val observerVersion: String,
        val adapterVersion: String,
        val url: String,
        val title: String,
    ) : ObserverMessage()

    /** 현재 페이지 정보 */
    data class PageInfo(
        override val seq: Int,
        override val ts: Long,
        val url: String,
        val title: String,
        val activeVideoId: String,
    ) : ObserverMessage()

    /** 활성 쇼츠 변경 */
    data class ActiveShortChanged(
        override val seq: Int,
        override val ts: Long,
        val short: ShortInfo,
        val index: Int,
        val count: Int,
    ) : ObserverMessage()

    /** 목록 스냅샷 */
    data class ListSnapshot(
        override val seq: Int,
        override val ts: Long,
        val revision: Int,
        val reason: SnapshotChangeReason,
        val url: String,
        val shorts: List<ShortInfo>,
    ) : ObserverMessage()

    /** 쇼츠 컨테이너 재생성 */
    data class DomRebuilt(
        override val seq: Int,
        override val ts: Long,
        val revision: Int,
    ) : ObserverMessage()

    /** 관찰 오류 */
    data class ObserverError(
        override val seq: Int,
        override val ts: Long,
        val code: String,
        val message: String,
    ) : ObserverMessage()

    /** 상태 확인 신호 (하트비트) */
    data class Heartbeat(
        override val seq: Int,
        override val ts: Long,
        val revision: Int,
        val shortCount: Int,
        val activeVideoId: String,
        val observerVersion: String,
    ) : ObserverMessage()

    companion object {
        private const val TYPE_READY = "observer_ready"
        private const val TYPE_PAGE_INFO = "page_info"
        private const val TYPE_ACTIVE_CHANGED = "active_short_changed"
        private const val TYPE_SNAPSHOT = "list_snapshot"
        private const val TYPE_DOM_REBUILT = "dom_rebuilt"
        private const val TYPE_ERROR = "observer_error"
        private const val TYPE_HEARTBEAT = "heartbeat"

        /**
         * JSON 문자열을 [ObserverMessage]로 파싱한다.
         * 형식이 잘못되었거나 알 수 없는 유형이면 null을 반환한다.
         */
        fun parse(json: String): ObserverMessage? {
            val root = try {
                JSONObject(json)
            } catch (e: JSONException) {
                return null
            }
            val type = root.optString("type")
            if (type.isEmpty()) return null
            val seq = root.optInt("seq", 0)
            val ts = root.optLong("ts", 0L)
            val data = root.optJSONObject("data") ?: JSONObject()
            return when (type) {
                TYPE_READY -> ObserverReady(
                    seq = seq, ts = ts,
                    observerVersion = data.optString("observerVersion"),
                    adapterVersion = data.optString("adapterVersion"),
                    url = data.optString("url"),
                    title = data.optString("title"),
                )

                TYPE_PAGE_INFO -> PageInfo(
                    seq = seq, ts = ts,
                    url = data.optString("url"),
                    title = data.optString("title"),
                    activeVideoId = data.optString("activeVideoId"),
                )

                TYPE_ACTIVE_CHANGED -> {
                    val shortJson = data.optJSONObject("short") ?: return null
                    ActiveShortChanged(
                        seq = seq, ts = ts,
                        short = shortFrom(shortJson),
                        index = data.optInt("index", -1),
                        count = data.optInt("count", 0),
                    )
                }

                TYPE_SNAPSHOT -> {
                    val shortsArray = data.optJSONArray("shorts") ?: JSONArray()
                    val shorts = buildList {
                        for (i in 0 until shortsArray.length()) {
                            shortsArray.optJSONObject(i)?.let { add(shortFrom(it)) }
                        }
                    }
                    ListSnapshot(
                        seq = seq, ts = ts,
                        revision = data.optInt("revision", 0),
                        reason = reasonFrom(data.optString("reason")),
                        url = data.optString("url"),
                        shorts = shorts,
                    )
                }

                TYPE_DOM_REBUILT -> DomRebuilt(
                    seq = seq, ts = ts,
                    revision = data.optInt("revision", 0),
                )

                TYPE_ERROR -> ObserverError(
                    seq = seq, ts = ts,
                    code = data.optString("code"),
                    message = data.optString("message"),
                )

                TYPE_HEARTBEAT -> Heartbeat(
                    seq = seq, ts = ts,
                    revision = data.optInt("revision", 0),
                    shortCount = data.optInt("shortCount", 0),
                    activeVideoId = data.optString("activeVideoId"),
                    observerVersion = data.optString("observerVersion"),
                )

                else -> null
            }
        }

        private fun shortFrom(json: JSONObject): ShortInfo = ShortInfo(
            videoId = json.optString("videoId"),
            url = json.optString("url"),
            title = json.optString("title"),
            channel = json.optString("channel"),
            thumbnail = json.optString("thumbnail"),
            identitySource = identitySourceFrom(json.optString("identitySource")),
            identityKey = json.optString("identityKey"),
        )

        private fun identitySourceFrom(value: String): ShortIdentitySource = when (value) {
            "video_id" -> ShortIdentitySource.VIDEO_ID
            "url" -> ShortIdentitySource.URL
            "data_attr" -> ShortIdentitySource.DATA_ATTR
            "thumbnail" -> ShortIdentitySource.THUMBNAIL
            else -> ShortIdentitySource.HASH
        }

        /**
         * 스냅샷 변경 사유 문자열을 [SnapshotChangeReason]으로 매핑한다.
         * 알 수 없는 사유는 보수적으로 DOM_REBUILT로 분류해
         * 이후 단계(H)의 중간 삽입 탐지가 오탐을 만들지 않도록 한다.
         */
        private fun reasonFrom(value: String): SnapshotChangeReason = when (value) {
            "initial" -> SnapshotChangeReason.INITIAL
            "item_added" -> SnapshotChangeReason.ITEM_ADDED
            "item_removed" -> SnapshotChangeReason.ITEM_REMOVED
            "order_changed" -> SnapshotChangeReason.ORDER_CHANGED
            "active_changed" -> SnapshotChangeReason.ACTIVE_CHANGED
            "dom_rebuilt" -> SnapshotChangeReason.DOM_REBUILT
            "navigation" -> SnapshotChangeReason.NAVIGATION
            "full_reload" -> SnapshotChangeReason.FULL_RELOAD
            else -> SnapshotChangeReason.DOM_REBUILT
        }
    }
}
