package com.shortsmonitor.core.observer

import com.shortsmonitor.core.model.NetworkRequestKind
import com.shortsmonitor.core.model.SequenceEntryKind
import com.shortsmonitor.core.model.SequenceParseStatus
import com.shortsmonitor.core.model.SnapshotChangeReason
import com.shortsmonitor.core.model.UrlChangeType
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

/** 네트워크 시퀀스 항목 (JS 파서가 추출한 최소 정보). */
data class NetworkSequenceItemInfo(
    val position: Int,
    val videoId: String,
    val entryKind: SequenceEntryKind,
    val nonVideoKind: String,
    val isCurrent: Boolean,
    val hasPlayerParams: Boolean,
    val hasContinuation: Boolean,
    val trackingHash: String,
    val playerParamsHash: String,
)

/**
 * JavaScript 관찰기 → 네이티브 메시지.
 * 모든 메시지는 JSON 문자열로 수신되며 [ObserverMessage.parse]로 파싱한다.
 * 형식이 맞지 않거나 알 수 없는 메시지는 파싱 단계에서 걸러진다.
 * 브리지 메시지는 신뢰된 명령이 아니라 데이터로만 취급한다.
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
        val urlChangeType: UrlChangeType? = null,
    ) : ObserverMessage()

    /** 활성 쇼츠 변경 */
    data class ActiveShortChanged(
        override val seq: Int,
        override val ts: Long,
        val short: ShortInfo,
        val index: Int,
        val count: Int,
        /** 활성 영상 판정 신뢰도 (0.0~1.0). -1이면 신뢰도 정보 없음. */
        val confidence: Float = -1f,
        /** 활성 판정에 사용된 신호 목록 (민감정보 없음). */
        val signals: List<String> = emptyList(),
    ) : ObserverMessage()

    /** 목록 스냅샷 */
    data class ListSnapshot(
        override val seq: Int,
        override val ts: Long,
        val revision: Int,
        val reason: SnapshotChangeReason,
        val url: String,
        val shorts: List<ShortInfo>,
        /** 선택자별 적중 수와 중복 제거 통계 (진단용). */
        val selectorStats: String? = null,
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
        /** 현재 DOM 목록 안정 식별 키 해시. 스냅샷이 생략된 상태의 목록 변화 감지용. */
        val listHash: String = "",
    ) : ObserverMessage()

    // ===== 네트워크 관찰 메시지 =====

    /** 네트워크 관찰기 설치 완료 (문서 시작 스크립트). */
    data class NetworkObserverReady(
        override val seq: Int,
        override val ts: Long,
        val pageUrl: String,
        val installedAt: Long,
        val observerVersion: String,
        val parserVersion: String,
        val fetchWrapped: Boolean,
        val xhrWrapped: Boolean,
    ) : ObserverMessage()

    /** `reel_watch_sequence` 요청 관찰. */
    data class NetworkSequenceRequest(
        override val seq: Int,
        override val ts: Long,
        val pageUrl: String,
        val correlationId: String,
        val requestKind: NetworkRequestKind,
        val currentVideoId: String,
        /** sequenceParams에서 디코딩한 영상 식별값 순서 (원문 아님). */
        val sequenceVideoIds: List<String>,
        val sequenceParamsDecoded: Boolean,
        val sequenceParamsHash: String,
        val sequenceParamsLength: Int,
        val sequenceParamsError: String,
        val continuationHash: String,
        val requestContextHash: String,
        val clientName: String,
        val clientVersion: String,
        val bodyStructureHash: String,
        val warnings: List<String>,
    ) : ObserverMessage()

    /** `reel_watch_sequence` 응답 분석. */
    data class NetworkSequenceResponse(
        override val seq: Int,
        override val ts: Long,
        val pageUrl: String,
        val correlationId: String,
        val currentVideoId: String,
        val sequenceHash: String,
        val continuationHash: String,
        val trackingHash: String,
        val responseContextHash: String,
        val parserVersion: String,
        val parseStatus: SequenceParseStatus,
        val detectedShape: String,
        val warnings: List<String>,
        val items: List<NetworkSequenceItemInfo>,
    ) : ObserverMessage()

    /** 개별 영상 요청 (`player` 또는 `reel_item_watch`) 관찰. */
    data class NetworkVideoRequest(
        override val seq: Int,
        override val ts: Long,
        val pageUrl: String,
        val correlationId: String,
        val requestKind: NetworkRequestKind,
        val videoId: String,
    ) : ObserverMessage()

    /** 네트워크 파싱 경고. */
    data class NetworkParseWarning(
        override val seq: Int,
        override val ts: Long,
        val pageUrl: String,
        val code: String,
        val message: String,
    ) : ObserverMessage()

    /** 네트워크 관찰기 상태 진단 (하트비트 주기). */
    data class NetworkObserverStatus(
        override val seq: Int,
        override val ts: Long,
        val pageUrl: String,
        val firstSequenceRequestAt: Long,
        val lastSequenceRequestAt: Long,
        val lastSequenceResponseAt: Long,
        val lastSequenceVideoCount: Int,
        val lastSequenceParseStatus: SequenceParseStatus,
        val missedInitialPossible: Boolean,
        val lastRequestVideoId: String,
        val warningCount: Int,
        val domVideoCount: Int,
        val domListHash: String,
    ) : ObserverMessage()

    companion object {
        private const val TYPE_READY = "observer_ready"
        private const val TYPE_PAGE_INFO = "page_info"
        private const val TYPE_ACTIVE_CHANGED = "active_short_changed"
        private const val TYPE_SNAPSHOT = "list_snapshot"
        private const val TYPE_DOM_REBUILT = "dom_rebuilt"
        private const val TYPE_ERROR = "observer_error"
        private const val TYPE_HEARTBEAT = "heartbeat"

        private const val TYPE_NETWORK_READY = "network_observer_ready"
        private const val TYPE_NETWORK_SEQUENCE_REQUEST = "network_sequence_request"
        private const val TYPE_NETWORK_SEQUENCE_RESPONSE = "network_sequence_response"
        private const val TYPE_NETWORK_VIDEO_REQUEST = "network_video_request"
        private const val TYPE_NETWORK_WARNING = "network_parse_warning"
        private const val TYPE_NETWORK_STATUS = "network_observer_status"

        /** 허용 메시지 타입 집합. 이 목록에 없는 타입은 무시한다. */
        val ALLOWED_TYPES: Set<String> = setOf(
            TYPE_READY,
            TYPE_PAGE_INFO,
            TYPE_ACTIVE_CHANGED,
            TYPE_SNAPSHOT,
            TYPE_DOM_REBUILT,
            TYPE_ERROR,
            TYPE_HEARTBEAT,
            TYPE_NETWORK_READY,
            TYPE_NETWORK_SEQUENCE_REQUEST,
            TYPE_NETWORK_SEQUENCE_RESPONSE,
            TYPE_NETWORK_VIDEO_REQUEST,
            TYPE_NETWORK_WARNING,
            TYPE_NETWORK_STATUS,
        )

        /** 단일 목록에 허용되는 최대 항목 수 (JS가 더 보내도 잘라낸다). */
        const val MAX_LIST_ITEMS = 200

        /** 문자열 필드 최대 길이. 넘으면 잘라낸다. */
        const val MAX_STRING_FIELD = 2_000

        /**
         * JSON 문자열을 [ObserverMessage]로 파싱한다.
         * 형식이 잘못되었거나 알 수 없는 유형이면 null을 반환한다.
         * 필드 누락은 기본값으로 대체해 기존 메시지 처리를 중단하지 않는다.
         */
        fun parse(json: String): ObserverMessage? {
            if (json.length > ObserverBridge.MAX_MESSAGE_SIZE) return null
            val root = try {
                JSONObject(json)
            } catch (e: JSONException) {
                return null
            }
            val type = root.optString("type")
            if (type.isEmpty() || type !in ALLOWED_TYPES) return null
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
                    urlChangeType = urlChangeTypeFrom(data.optString("urlChangeType")),
                )

                TYPE_ACTIVE_CHANGED -> {
                    val shortJson = data.optJSONObject("short") ?: return null
                    ActiveShortChanged(
                        seq = seq, ts = ts,
                        short = shortFrom(shortJson),
                        index = data.optInt("index", -1),
                        count = data.optInt("count", 0),
                        confidence = if (data.has("confidence")) {
                            data.optDouble("confidence", -1.0).toFloat()
                        } else {
                            -1f
                        },
                        signals = stringList(data.optJSONArray("signals")),
                    )
                }

                TYPE_SNAPSHOT -> {
                    val shortsArray = data.optJSONArray("shorts") ?: JSONArray()
                    val shorts = buildList {
                        for (i in 0 until shortsArray.length()) {
                            if (size >= MAX_LIST_ITEMS) break
                            shortsArray.optJSONObject(i)?.let { add(shortFrom(it)) }
                        }
                    }
                    ListSnapshot(
                        seq = seq, ts = ts,
                        revision = data.optInt("revision", 0),
                        reason = reasonFrom(data.optString("reason")),
                        url = data.optString("url"),
                        shorts = shorts,
                        selectorStats = data.optString("selectorStats").ifBlank { null },
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
                    listHash = data.optString("listHash"),
                )

                TYPE_NETWORK_READY -> NetworkObserverReady(
                    seq = seq, ts = ts,
                    pageUrl = data.optString("pageUrl"),
                    installedAt = data.optLong("installedAt", ts),
                    observerVersion = data.optString("observerVersion"),
                    parserVersion = data.optString("parserVersion"),
                    fetchWrapped = data.optBoolean("fetchWrapped", false),
                    xhrWrapped = data.optBoolean("xhrWrapped", false),
                )

                TYPE_NETWORK_SEQUENCE_REQUEST -> NetworkSequenceRequest(
                    seq = seq, ts = ts,
                    pageUrl = data.optString("pageUrl"),
                    correlationId = data.optString("correlationId"),
                    requestKind = requestKindFrom(data.optString("requestKind")),
                    currentVideoId = data.optString("currentVideoId"),
                    sequenceVideoIds = stringList(data.optJSONArray("sequenceVideoIds")),
                    sequenceParamsDecoded = data.optBoolean("sequenceParamsDecoded", false),
                    sequenceParamsHash = data.optString("sequenceParamsHash"),
                    sequenceParamsLength = data.optInt("sequenceParamsLength", 0),
                    sequenceParamsError = data.optString("sequenceParamsError"),
                    continuationHash = data.optString("continuationHash"),
                    requestContextHash = data.optString("requestContextHash"),
                    clientName = data.optString("clientName"),
                    clientVersion = data.optString("clientVersion"),
                    bodyStructureHash = data.optString("bodyStructureHash"),
                    warnings = stringList(data.optJSONArray("warnings")),
                )

                TYPE_NETWORK_SEQUENCE_RESPONSE -> {
                    val itemsArray = data.optJSONArray("items") ?: JSONArray()
                    val items = buildList {
                        for (i in 0 until itemsArray.length()) {
                            if (size >= MAX_LIST_ITEMS) break
                            itemsArray.optJSONObject(i)?.let { add(itemInfoFrom(it)) }
                        }
                    }
                    NetworkSequenceResponse(
                        seq = seq, ts = ts,
                        pageUrl = data.optString("pageUrl"),
                        correlationId = data.optString("correlationId"),
                        currentVideoId = data.optString("currentVideoId"),
                        sequenceHash = data.optString("sequenceHash"),
                        continuationHash = data.optString("continuationHash"),
                        trackingHash = data.optString("trackingHash"),
                        responseContextHash = data.optString("responseContextHash"),
                        parserVersion = data.optString("parserVersion"),
                        parseStatus = parseStatusFrom(data.optString("parseStatus")),
                        detectedShape = data.optString("detectedShape"),
                        warnings = stringList(data.optJSONArray("warnings")),
                        items = items,
                    )
                }

                TYPE_NETWORK_VIDEO_REQUEST -> NetworkVideoRequest(
                    seq = seq, ts = ts,
                    pageUrl = data.optString("pageUrl"),
                    correlationId = data.optString("correlationId"),
                    requestKind = requestKindFrom(data.optString("requestKind")),
                    videoId = data.optString("videoId"),
                )

                TYPE_NETWORK_WARNING -> NetworkParseWarning(
                    seq = seq, ts = ts,
                    pageUrl = data.optString("pageUrl"),
                    code = data.optString("code"),
                    message = data.optString("message"),
                )

                TYPE_NETWORK_STATUS -> NetworkObserverStatus(
                    seq = seq, ts = ts,
                    pageUrl = data.optString("pageUrl"),
                    firstSequenceRequestAt = data.optLong("firstSequenceRequestAt", 0L),
                    lastSequenceRequestAt = data.optLong("lastSequenceRequestAt", 0L),
                    lastSequenceResponseAt = data.optLong("lastSequenceResponseAt", 0L),
                    lastSequenceVideoCount = data.optInt("lastSequenceVideoCount", 0),
                    lastSequenceParseStatus = parseStatusFrom(data.optString("lastSequenceParseStatus")),
                    missedInitialPossible = data.optBoolean("missedInitialPossible", false),
                    lastRequestVideoId = data.optString("lastRequestVideoId"),
                    warningCount = data.optInt("warningCount", 0),
                    domVideoCount = data.optInt("domVideoCount", 0),
                    domListHash = data.optString("domListHash"),
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

        private fun itemInfoFrom(json: JSONObject): NetworkSequenceItemInfo = NetworkSequenceItemInfo(
            position = json.optInt("position", 0),
            videoId = json.optString("videoId"),
            entryKind = entryKindFrom(json.optString("entryKind")),
            nonVideoKind = json.optString("nonVideoKind"),
            isCurrent = json.optBoolean("isCurrent", false),
            hasPlayerParams = json.optBoolean("hasPlayerParams", false),
            hasContinuation = json.optBoolean("hasContinuation", false),
            trackingHash = json.optString("trackingHash"),
            playerParamsHash = json.optString("playerParamsHash"),
        )

        private fun identitySourceFrom(value: String): ShortIdentitySource = when (value) {
            "video_id" -> ShortIdentitySource.VIDEO_ID
            "url" -> ShortIdentitySource.URL
            "data_attr" -> ShortIdentitySource.DATA_ATTR
            "thumbnail" -> ShortIdentitySource.THUMBNAIL
            else -> ShortIdentitySource.HASH
        }

        private fun requestKindFrom(value: String): NetworkRequestKind = when (value) {
            "reel_watch_sequence" -> NetworkRequestKind.REEL_WATCH_SEQUENCE
            "reel_item_watch" -> NetworkRequestKind.REEL_ITEM_WATCH
            "player" -> NetworkRequestKind.PLAYER
            else -> NetworkRequestKind.OTHER
        }

        private fun parseStatusFrom(value: String): SequenceParseStatus = when (value) {
            "parsed" -> SequenceParseStatus.PARSED
            "partial" -> SequenceParseStatus.PARTIAL
            "failed" -> SequenceParseStatus.FAILED
            "unsupported" -> SequenceParseStatus.UNSUPPORTED
            else -> SequenceParseStatus.NONE
        }

        private fun entryKindFrom(value: String): SequenceEntryKind = when (value) {
            "non_video" -> SequenceEntryKind.NON_VIDEO
            else -> SequenceEntryKind.VIDEO
        }

        private fun urlChangeTypeFrom(value: String): UrlChangeType? = when (value) {
            "same_sequence_active_change" -> UrlChangeType.SAME_SEQUENCE_ACTIVE_CHANGE
            "new_context" -> UrlChangeType.NEW_CONTEXT
            "full_reload" -> UrlChangeType.FULL_RELOAD
            "external" -> UrlChangeType.EXTERNAL
            else -> null
        }

        private fun stringList(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            val result = mutableListOf<String>()
            for (i in 0 until array.length()) {
                if (result.size >= MAX_LIST_ITEMS) break
                result.add(array.optString(i).take(MAX_STRING_FIELD))
            }
            return result
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
