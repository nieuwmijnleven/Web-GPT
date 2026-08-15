package com.shortsmonitor.core.reset

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 쿠키·사이트 데이터 초기화 실행기 테스트 (M단계).
 * 항목별 실패 수집과 중복 실행 차단을 검증한다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SessionResetterTest {

    @Test
    fun `reset without webview clears cookies and web storage`() = runTest {
        val resetter = SessionResetter()
        val result = resetter.reset(webView = null)

        assertTrue("cookies should succeed", ResetItem.COOKIES in result.succeeded)
        assertTrue("web storage should succeed", ResetItem.WEB_STORAGE in result.succeeded)
        assertTrue("result should be ok", result.ok)
        assertFalse("should not be skipped", result.skippedWhileRunning)
    }

    @Test
    fun `reset while running is skipped`() = runTest {
        // 쿠키 제거가 완료되기 전까지 첫 호출을 붙잡아 중복 실행 차단을 결정적으로 검증한다.
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resetter = SessionResetter(
            removeCookiesImpl = {
                started.complete(Unit)
                release.await()
                true
            },
        )

        val first = async { resetter.reset(webView = null) }
        started.await()
        val second = resetter.reset(webView = null)
        release.complete(Unit)
        val firstResult = first.await()

        assertFalse("first should not be skipped", firstResult.skippedWhileRunning)
        assertTrue("second should be skipped while running", second.skippedWhileRunning)
        assertFalse("skipped result should not be ok", second.ok)
        assertTrue(firstResult.ok)
    }

    @Test
    fun `resetter reports not in progress after completion`() = runTest {
        val resetter = SessionResetter()
        assertFalse(resetter.isInProgress())
        resetter.reset(webView = null)
        assertFalse("should be idle after completion", resetter.isInProgress())
    }

    @Test
    fun `result ok is false when there are failed items`() {
        val result = SessionResetter.ResetResult(
            succeeded = listOf(ResetItem.COOKIES),
            failed = listOf(ResetItem.CACHE),
        )
        assertFalse(result.ok)
        assertEquals(listOf(ResetItem.CACHE), result.failed)
    }
}
