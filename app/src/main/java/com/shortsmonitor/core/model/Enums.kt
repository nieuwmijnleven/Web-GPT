package com.shortsmonitor.core.model

/** 관찰 세션 상태 */
enum class SessionStatus {
    ACTIVE,
    COMPLETED,
    INTERRUPTED,
    ERROR,
}

/** 세션 종료 사유 */
enum class SessionEndReason {
    USER_FINISHED,
    NAVIGATED_AWAY,
    ERROR,
    APP_INTERRUPTED,
}

/** 쇼츠 식별 신뢰 상태 (식별 실패 시 임시 식별값 사용) */
enum class ShortIdentityStatus {
    RELIABLE,
    TEMPORARY,
}

/** 목록 스냅샷 변경 사유 */
enum class SnapshotChangeReason {
    INITIAL,
    ITEM_ADDED,
    ITEM_REMOVED,
    ORDER_CHANGED,
    ACTIVE_CHANGED,
    DOM_REBUILT,
    NAVIGATION,
    PROFILE_CHANGED,
    SESSION_RESET,
    FULL_RELOAD,
}

/**
 * 중간 삽입 의심 자동 판정.
 * - CANDIDATE: 후보 (네트워크 증거로 확정 전)
 * - CONFIRMED: 확정 (네트워크 증거 조합으로 확정)
 * - UNKNOWN: 보류 (네트워크 데이터 없음 또는 계보 불명확 — 자동 확정하지 않음)
 * - INVALIDATED: 무효화 (관계 소멸 등)
 */
enum class AutoVerdict {
    CANDIDATE,
    CONFIRMED,
    UNKNOWN,
    INVALIDATED,
}

/** 사용자 판정 */
enum class UserVerdict {
    PENDING,
    SUSPECTED,
    NORMAL_CHANGE,
    FALSE_POSITIVE,
}

/** 브라우저 테스트 프로필 템플릿 유형 */
enum class ProfileTemplateType {
    SMALL_ANDROID,
    ANDROID,
    LARGE_ANDROID,
    ANDROID_TABLET,
}
