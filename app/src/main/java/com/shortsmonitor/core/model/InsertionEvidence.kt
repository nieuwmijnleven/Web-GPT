package com.shortsmonitor.core.model

import org.json.JSONException
import org.json.JSONObject

/**
 * 중간 삽입 의심 판정 근거 (H단계).
 *
 * 확정된 의심 이벤트가 어떤 조건을 통과해 기록됐는지 이벤트 상세에 저장한다.
 * 모든 값이 참일 때만 이벤트로 확정되므로, 확정 이벤트의 근거는 [confirmed]로 생성한다.
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
) {

    fun toJson(): String = JSONObject()
        .put(KEY_NOT_IN_PREVIOUS_LIST, notInPreviousList)
        .put(KEY_APPEARED_AT_MIDDLE, appearedAtMiddle)
        .put(KEY_FRONT_BACK_MAINTAINED, frontBackMaintained)
        .put(KEY_STABILIZED, stabilized)
        .put(KEY_NOT_FULL_RELOAD, notFullReload)
        .put(KEY_NOT_AFTER_PROFILE_CHANGE, notAfterProfileChange)
        .put(KEY_NOT_AFTER_SESSION_RESET, notAfterSessionReset)
        .put(KEY_NOT_AFTER_DOM_REBUILD, notAfterDomRebuild)
        .toString()

    companion object {
        const val KEY_NOT_IN_PREVIOUS_LIST = "notInPreviousList"
        const val KEY_APPEARED_AT_MIDDLE = "appearedAtMiddle"
        const val KEY_FRONT_BACK_MAINTAINED = "frontBackMaintained"
        const val KEY_STABILIZED = "stabilized"
        const val KEY_NOT_FULL_RELOAD = "notFullReload"
        const val KEY_NOT_AFTER_PROFILE_CHANGE = "notAfterProfileChange"
        const val KEY_NOT_AFTER_SESSION_RESET = "notAfterSessionReset"
        const val KEY_NOT_AFTER_DOM_REBUILD = "notAfterDomRebuild"

        /** 후보 안정화로 확정된 이벤트의 판정 근거. 모든 기본 조건을 통과했다. */
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

        fun fromJson(json: String?): InsertionEvidence {
            if (json.isNullOrBlank()) return confirmed()
            val obj = try {
                JSONObject(json)
            } catch (e: JSONException) {
                return confirmed()
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
            )
        }
    }
}
