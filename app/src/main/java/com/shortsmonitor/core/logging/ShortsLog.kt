package com.shortsmonitor.core.logging

import android.util.Log
import com.shortsmonitor.app.BuildConfig

/**
 * 앱 공통 로그 수집 구조.
 * 릴리스 빌드에서는 기본적으로 비활성화되며 [enabled]로 제어할 수 있다.
 * 이후 단계에서 진단 화면용 로그 저장 구조로 확장한다.
 */
object ShortsLog {

    const val TAG = "ShortsMonitor"

    var enabled: Boolean = BuildConfig.DEBUG

    fun d(message: String) = log(Log.DEBUG, message)

    fun i(message: String) = log(Log.INFO, message)

    fun w(message: String, throwable: Throwable? = null) = log(Log.WARN, message, throwable)

    fun e(message: String, throwable: Throwable? = null) = log(Log.ERROR, message, throwable)

    private fun log(level: Int, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        when (level) {
            Log.DEBUG -> Log.d(TAG, message)
            Log.INFO -> Log.i(TAG, message)
            Log.WARN -> if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
            else -> if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
        }
    }
}
