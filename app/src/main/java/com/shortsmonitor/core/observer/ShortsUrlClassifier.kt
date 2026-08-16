package com.shortsmonitor.core.observer

import com.shortsmonitor.core.model.EntryContext

/**
 * 페이지 진입 컨텍스트 분류기.
 *
 * JavaScript 관찰기가 보내는 URL 변경 유형(같은 시퀀스 내 활성 영상 변경 vs 새 탐색)은
 * JS에서 판단하고, 여기서는 URL 문자열로 세부 진입 컨텍스트(검색·홈·채널·Shorts 영상)를
 * 분류한다. 네트워크 시퀀스의 [EntryContext] 저장에 사용한다.
 */
object ShortsUrlClassifier {

    /**
     * URL을 진입 컨텍스트로 분류한다.
     * 우선순위: 채널 Shorts > 검색 > Shorts 영상 > Shorts 홈 > 기타.
     */
    fun classify(url: String?): EntryContext {
        val value = url ?: return EntryContext.OTHER
        val path = pathOf(value)
        // 채널 Shorts 목록 (/@channel/shorts)
        if (Regex("^/@[^/]+/shorts(/.*)?$").containsMatchIn(path)) {
            return EntryContext.CHANNEL_SHORTS
        }
        // 검색 결과
        if (path.startsWith("/search")) {
            return EntryContext.SEARCH_RESULT
        }
        // Shorts 영상 주소 (/shorts/<videoId>)
        if (Regex("^/shorts/[A-Za-z0-9_-]{6,}").containsMatchIn(path)) {
            return EntryContext.SHORTS_VIDEO
        }
        // Shorts 홈 (/shorts 또는 /shorts/)
        if (path == "/shorts" || path == "/shorts/") {
            return EntryContext.SHORTS_HOME
        }
        return EntryContext.OTHER
    }

    private fun pathOf(url: String): String {
        return try {
            val schemeEnd = url.indexOf("://")
            val afterScheme = if (schemeEnd >= 0) url.substring(schemeEnd + 3) else url
            val pathStart = afterScheme.indexOf('/')
            if (pathStart < 0) "/" else {
                val path = afterScheme.substring(pathStart)
                val query = path.indexOf('?')
                if (query >= 0) path.substring(0, query) else path
            }
        } catch (e: Exception) {
            "/"
        }
    }
}
