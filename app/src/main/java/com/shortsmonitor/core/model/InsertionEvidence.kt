package com.shortsmonitor.core.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 중간 삽입 의심 판정 근거 (H단계 + 네트워크 시퀀스 분석).
 *
 * 기존 DOM 기반 확정 이벤트는 모든 값이 참인 [confirmed]로 생성한다.
 * 네트워크 시퀀스 기반 이벤트는 [networkCandidate]/[networkConfirmed]로 생성하며,
 * 확정은 동일 계보 후속 시퀀스의 관계 유지와 함께 아래 증거 중 하나 이상을 요구한다.
 * - 해당 신규 영상에 대한 `player` 요청 발생
 * - 해당 신규 영상에 대한 `reel_item_watch` 요청 발생
 * - DOM에서 실제 활성 영상으로 관찰
 */
data class InsertionEvidence(
    val notInPreviousList: Boolean,
    val appearedAtMiddle: Boolean,
    val frontBackMaintained: Boolean,
    val stabilized: Boolean,
    val notFullReload: Boolean,
    val notAfterProfileChange: Boolean,
    val notAfterSessionReset: Boolean,
    val notAfterDomRebuild: Boolean,
    /** 근거 출처: DOM 목록(보조) 또는 네트워크 시퀀스(주 분석). */
    val source: InsertionSource = InsertionSource.DOM,
    /** 네트워크 시퀀스 계보가 동일 흐름(SAME_FLOW)으로 판정됨. */
    val sameLineageFlow: Boolean = false,
    /** 시퀀스 끝 단순 추가가 아님. */
    val notEndAddition: Boolean = true,
    /** 신규 시퀀스에서 앞·신규·뒤 순서가 유지됨. */
    val orderMaintained: Boolean = false,
    /** 검색 컨텍스트 변경 직후가 아님. */
    val notAfterSearchChange: Boolean = true,
    /** 해당 영상의 `player` 요청으로 강화됨. */
    val strengthenedByPlayerRequest: Boolean = false,
    /** 해당 영상의 `reel_item_watch` 요청으로 강화됨. */
    val strengthenedByReelItemWatch: Boolean = false,
    /** DOM에서 실제 활성 영상으로 관찰되어 강화됨. */
    val strengthenedByDomActive: Boolean = false,
) {

    fun toJson(): String {
        val strengthened = JSONArray()
        if (strengthenedByPlayerRequest) strengthened.put(KEY_STRENGTHENED_PLAYER)
        if (strengthenedByReelItemWatch) strengthened.put(KEY_STRENGTHENED_REEL_WATCH)
        if (strengthenedByDomActive) strengthened.put(KEY_STRENGTHENED_DOM_ACTIVE)
        return JSONObject()
            .put(KEY_NOT_IN_PREVIOUS_LIST, notInPreviousList)
            .put(KEY_APPEARED_AT_MIDDLE, appearedAtMiddle)
            .put(KEY_FRONT_BACK_MAINTAINED, frontBackMaintained)
            .put(KEY_STABILIZED, stabilized)
            .put(KEY_NOT_FULL_RELOAD, notFullReload)
            .put(KEY_NOT_AFTER_PROFILE_CHANGE, notAfterProfileChange)
            .put(KEY_NOT_AFTER_SESSION_RESET, notAfterSessionReset)
            .put(KEY_NOT_AFTER_DOM_REBUILD, notAfterDomRebuild)
            .put(KEY_SOURCE, source.name)
            .put(KEY_SAME_LINEAGE_FLOW, sameLineageFlow)
            .put(KEY_NOT_END_ADDITION, notEndAddition)
            .put(KEY_ORDER_MAINTAINED, orderMaintained)
            .put(KEY_NOT_AFTER_SEARCH_CHANGE, notAfterSearchChange)
            .put(KEY_STRENGTHENED, strengthened)
            .toString()
    }

    /** 이 판정 근거가 사용자가 볼 수 있는 강화 증거 목록 (JSON 배열 문자열). */
    fun strengthenedJson(): String {
        val array = JSONArray()
        if (strengthenedByPlayerRequest) array.put(KEY_STRENGTHENED_PLAYER)
        if (strengthenedByReelItemWatch) array.put(KEY_STRENGTHENED_REEL_WATCH)
        if (strengthenedByDomActive) array.put(KEY_STRENGTHENED_DOM_ACTIVE)
        return array.toString()
    }

    companion object {
        const val KEY_NOT_IN_PREVIOUS_LIST = "notInPreviousList"
        const val KEY_APPEARED_AT_MIDDLE = "appearedAtMiddle"
        const val KEY_FRONT_BACK_MAINTAINED = "frontBackMaintained"
        const val KEY_STABILIZED = "stabilized"
        const val KEY_NOT_FULL_RELOAD = "notFullReload"
        const val KEY_NOT_AFTER_PROFILE_CHANGE = "notAfterProfileChange"
        const val KEY_NOT_AFTER_SESSION_RESET = "notAfterSessionReset"
        const val KEY_NOT_AFTER_DOM_REBUILD = "notAfterDomRebuild"
        const val KEY_SOURCE = "source"
        const val KEY_SAME_LINEAGE_FLOW = "sameLineageFlow"
        const val KEY_NOT_END_ADDITION = "notEndAddition"
        const val KEY_ORDER_MAINTAINED = "orderMaintained"
        const val KEY_NOT_AFTER_SEARCH_CHANGE = "notAfterSearchChange"
        const val KEY_STRENGTHENED = "strengthenedBy"
        const val KEY_STRENGTHENED_PLAYER = "player_request"
        const val KEY_STRENGTHENED_REEL_WATCH = "reel_item_watch"
        const val KEY_STRENGTHENED_DOM_ACTIVE = "dom_active"

        /** 후보 안정화로 확정된 DOM 기반 이벤트의 판정 근거. 모든 기본 조건을 통과했다. */
        fun confirmed(): InsertionEvidence = InsertionEvidence(
            notInPreviousList = true,
            appearedAtMiddle = true,
            frontBackMaintained = true,
            stabilized = true,
            notFullReload = true,
            notAfterProfileChange = true,
            notAfterSessionReset = true,
            notAfterDomRebuild = true,
        )

        /** 네트워크 시퀀스 후보 등록 시 판정 근거 (아직 확정 아님). */
        fun networkCandidate(): InsertionEvidence = InsertionEvidence(
            notInPreviousList = true,
            appearedAtMiddle = true,
            frontBackMaintained = true,
            stabilized = false,
            notFullReload = true,
            notAfterProfileChange = true,
            notAfterSessionReset = true,
            notAfterDomRebuild = true,
            source = InsertionSource.NETWORK,
            sameLineageFlow = true,
            notEndAddition = true,
            orderMaintained = true,
        )

        /** 네트워크 시퀀스 기반 확정 판정 근거. [strengthened] 신호 중 하나 이상이 있어야 한다. */
        fun networkConfirmed(
            sameLineageFlow: Boolean = true,
            orderMaintained: Boolean = true,
            strengthenedByPlayerRequest: Boolean = false,
            strengthenedByReelItemWatch: Boolean = false,
            strengthenedByDomActive: Boolean = false,
        ): InsertionEvidence = InsertionEvidence(
            notInPreviousList = true,
            appearedAtMiddle = true,
            frontBackMaintained = true,
            stabilized = true,
            notFullReload = true,
            notAfterProfileChange = true,
            notAfterSessionReset = true,
            notAfterDomRebuild = true,
            source = InsertionSource.NETWORK,
            sameLineageFlow = sameLineageFlow,
            notEndAddition = true,
            orderMaintained = orderMaintained,
            strengthenedByPlayerRequest = strengthenedByPlayerRequest,
            strengthenedByReelItemWatch = strengthenedByReelItemWatch,
            strengthenedByDomActive = strengthenedByDomActive,
        )

        fun fromJson(json: String?): InsertionEvidence {
            if (json.isNullOrBlank()) return confirmed()
            val obj = try {
                JSONObject(json)
            } catch (e: JSONException) {
                return confirmed()
            }
            val strengthened = runCatching { obj.optJSONArray(KEY_STRENGTHENED) }.getOrNull()
            val strengthenedValues = buildSet {
                if (strengthened != null) {
                    for (i in 0 until strengthened.length()) {
                        add(strengthened.optString(i))
                    }
                }
            }
            return InsertionEvidence(
                notInPreviousList = obj.optBoolean(KEY_NOT_IN_PREVIOUS_LIST, true),
                appearedAtMiddle = obj.optBoolean(KEY_APPEARED_AT_MIDDLE, true),
                frontBackMaintained = obj.optBoolean(KEY_FRONT_BACK_MAINTAINED, true),
                stabilized = obj.optBoolean(KEY_STABILIZED, true),
                notFullReload = obj.optBoolean(KEY_NOT_FULL_RELOAD, true),
                notAfterProfileChange = obj.optBoolean(KEY_NOT_AFTER_PROFILE_CHANGE, true),
                notAfterSessionReset = obj.optBoolean(KEY_NOT_AFTER_SESSION_RESET, true),
                notAfterDomRebuild = obj.optBoolean(KEY_NOT_AFTER_DOM_REBUILD, true),
                source = runCatching {
                    InsertionSource.valueOf(obj.optString(KEY_SOURCE, InsertionSource.DOM.name))
                }.getOrDefault(InsertionSource.DOM),
                sameLineageFlow = obj.optBoolean(KEY_SAME_LINEAGE_FLOW, false),
                notEndAddition = obj.optBoolean(KEY_NOT_END_ADDITION, true),
                orderMaintained = obj.optBoolean(KEY_ORDER_MAINTAINED, false),
                notAfterSearchChange = obj.optBoolean(KEY_NOT_AFTER_SEARCH_CHANGE, true),
                strengthenedByPlayerRequest = KEY_STRENGTHENED_PLAYER in strengthenedValues,
                strengthenedByReelItemWatch = KEY_STRENGTHENED_REEL_WATCH in strengthenedValues,
                strengthenedByDomActive = KEY_STRENGTHENED_DOM_ACTIVE in strengthenedValues,
            )
        }
    }
}
