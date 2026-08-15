package com.shortsmonitor.core.observer

import com.shortsmonitor.core.model.ShortIdentityStatus

/**
 * 쇼츠 식별 로직.
 *
 * JavaScript 관찰기가 식별 우선순위(영상 식별값 > 주소 > DOM 데이터 속성 >
 * 썸네일 > 해시)에 따라 계산한 [ShortInfo.identitySource]를 기준으로,
 * 영상 식별값이 확보된 경우에만 [ShortIdentityStatus.RELIABLE]로 저장하고
 * 그 외에는 임시 식별값([temporaryId])으로 [ShortIdentityStatus.TEMPORARY]로 기록한다.
 *
 * 임시 식별값은 식별 키의 해시 기반이므로 같은 항목이면 세션 동안 안정적으로
 * 중복 제거된다.
 */
object ShortIdentity {

    private const val TEMPORARY_PREFIX = "tmp_"
    private const val EMPTY_KEY_ID = "empty"

    data class Resolved(
        val videoId: String,
        val status: ShortIdentityStatus,
    )

    fun resolve(short: ShortInfo): Resolved =
        if (short.identitySource == ShortIdentitySource.VIDEO_ID && short.videoId.isNotBlank()) {
            Resolved(short.videoId, ShortIdentityStatus.RELIABLE)
        } else {
            Resolved(temporaryId(short.identityKey), ShortIdentityStatus.TEMPORARY)
        }

    /** 식별 실패 항목의 임시 식별값. 같은 입력이면 항상 같은 값을 반환한다. */
    fun temporaryId(identityKey: String): String {
        if (identityKey.isBlank()) return "$TEMPORARY_PREFIX$EMPTY_KEY_ID"
        var hash = 5381
        for (c in identityKey) {
            hash = ((hash shl 5) + hash + c.code) and Int.MAX_VALUE
        }
        return TEMPORARY_PREFIX + hash.toString(16)
    }
}
