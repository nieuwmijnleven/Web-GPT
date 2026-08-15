package com.shortsmonitor.core.reset

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.shortsmonitor.core.logging.ShortsLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 쿠키·사이트 데이터 초기화에서 삭제 대상 항목. */
enum class ResetItem {
    COOKIES,
    WEB_STORAGE,
    CACHE,
    HISTORY,
    FORM_DATA,
}

/**
 * 쿠키와 사이트 데이터 초기화 실행기 (M단계).
 *
 * 삭제 대상:
 * - 쿠키·로그인 상태 ([CookieManager])
 * - Local/Session Storage, Web SQL ([WebStorage])
 * - WebView 캐시·탐색 기록·폼 데이터 (WebView 인스턴스가 있을 때)
 *
 * 유지 대상: shorts monitor 관찰 기록, 사용자 판정, 사용자 메모, 저장된 프로필,
 * 내보낸 파일 — 이 실행기는 WebView 사이트 데이터만 정리하므로 자동으로 유지된다.
 *
 * 항목별 실패를 수집해 [ResetResult]로 반환하고, 실행 중 중복 호출을 차단한다.
 */
class SessionResetter(
    /**
     * 쿠키 제거 구현. 기본값은 [CookieManager]를 사용한다.
     * 테스트에서 중복 실행 차단을 결정적으로 검증하기 위해 주입할 수 있다.
     */
    private val removeCookiesImpl: suspend () -> Boolean = ::defaultRemoveCookies,
) {

    /** 초기화 결과. 실패 항목이 있으면 화면에 명시한다. */
    data class ResetResult(
        val succeeded: List<ResetItem> = emptyList(),
        val failed: List<ResetItem> = emptyList(),
        /** 실행 중이어서 건너뛰었는지 여부 (중복 실행 차단). */
        val skippedWhileRunning: Boolean = false,
    ) {
        val ok: Boolean get() = failed.isEmpty() && !skippedWhileRunning
    }

    private var inProgress = false

    fun isInProgress(): Boolean = inProgress

    /**
     * 사이트 데이터를 초기화한다.
     * @param webView WebView 캐시·히스토리·폼 정리에 사용. null이면 쿠키·웹 저장소만 정리한다.
     */
    suspend fun reset(webView: WebView?): ResetResult {
        if (inProgress) {
            ShortsLog.w("Session reset skipped: already running")
            return ResetResult(skippedWhileRunning = true)
        }
        inProgress = true
        try {
            val succeeded = mutableListOf<ResetItem>()
            val failed = mutableListOf<ResetItem>()

            // 1) 쿠키·로그인 상태 제거 (비동기 콜백으로 완료 확인)
            if (removeCookiesImpl()) {
                succeeded += ResetItem.COOKIES
            } else {
                failed += ResetItem.COOKIES
            }

            // 2) Web Storage (Local/Session Storage, Web SQL) 제거
            if (runCatching { WebStorage.getInstance().deleteAllData() }.isSuccess) {
                succeeded += ResetItem.WEB_STORAGE
            } else {
                failed += ResetItem.WEB_STORAGE
            }

            // 3) WebView 캐시·탐색 기록·폼 데이터 제거 (인스턴스가 있을 때)
            val view = webView
            if (view != null) {
                if (runCatching { view.clearCache(true) }.isSuccess) {
                    succeeded += ResetItem.CACHE
                } else {
                    failed += ResetItem.CACHE
                }
                if (runCatching { view.clearHistory() }.isSuccess) {
                    succeeded += ResetItem.HISTORY
                } else {
                    failed += ResetItem.HISTORY
                }
                if (runCatching { view.clearFormData() }.isSuccess) {
                    succeeded += ResetItem.FORM_DATA
                } else {
                    failed += ResetItem.FORM_DATA
                }
            }

            ShortsLog.d(
                "Session reset done: succeeded=${succeeded.size} failed=${failed.size}",
            )
            return ResetResult(succeeded = succeeded, failed = failed)
        } finally {
            inProgress = false
        }
    }

    companion object {
        suspend fun defaultRemoveCookies(): Boolean = suspendCancellableCoroutine { cont ->
            try {
                val manager = CookieManager.getInstance()
                manager.removeAllCookies { done ->
                    manager.flush()
                    cont.resume(done)
                }
            } catch (e: Exception) {
                ShortsLog.w("Session reset: cookie removal failed", e)
                cont.resume(false)
            }
        }
    }
}
