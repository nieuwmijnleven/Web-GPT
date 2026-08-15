package com.shortsmonitor.core.observer

/**
 * 유튜브 DOM 어댑터 메타데이터.
 *
 * 실제 어댑터 구현은 JavaScript 관찰기([ShortsObserverScript]) 내부의
 * `ShortsDomAdapter` 객체로 존재한다. 유튜브 선택자는 화면 로직에 직접
 * 작성하지 않으며, 선택자가 변경될 경우 JS 스크립트의 어댑터 블록만
 * 교체하면 된다. 이 객체는 어댑터 버전과 계약을 네이티브에서 추적하는 용도다.
 */
object ShortsDomAdapter {

    const val NAME = "ShortsDomAdapter"

    /** JS 어댑터 버전과 일치해야 한다. */
    const val VERSION = "1.0.0"

    /** JS 어댑터가 제공해야 하는 메서드 계약. */
    val METHODS = listOf(
        "findFeedContainer",
        "findShortItems",
        "extractVideoId",
        "extractVideoUrl",
        "extractTitle",
        "extractChannel",
        "extractThumbnail",
        "detectActiveItem",
    )
}
