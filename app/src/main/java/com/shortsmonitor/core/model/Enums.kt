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

/** 중간 삽입 의심 자동 판정 */
enum class AutoVerdict {
    CANDIDATE,
    CONFIRMED,
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
