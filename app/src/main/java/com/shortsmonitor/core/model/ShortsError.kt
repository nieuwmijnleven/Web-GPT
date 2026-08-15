package com.shortsmonitor.core.model

/**
 * 앱 공통 오류 모델.
 * DB, 저장소, WebView, 관찰기, 내보내기 등 모든 계층의 실패를 단일 타입으로 표현한다.
 * 화면에서는 이 타입으로 오류 상태를 표시하고 로그로 기록한다.
 */
sealed class ShortsError(
    open val message: String,
    open val cause: Throwable? = null,
) {
    class Database(
        val operation: String,
        message: String,
        cause: Throwable? = null,
    ) : ShortsError(message, cause)

    class Storage(message: String, cause: Throwable? = null) : ShortsError(message, cause)

    class WebView(message: String, cause: Throwable? = null) : ShortsError(message, cause)

    class Observer(message: String, cause: Throwable? = null) : ShortsError(message, cause)

    class Export(message: String, cause: Throwable? = null) : ShortsError(message, cause)

    class Unknown(message: String, cause: Throwable? = null) : ShortsError(message, cause)

    override fun toString(): String = "${this::class.simpleName}: $message"
}
