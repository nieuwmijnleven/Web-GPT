package com.shortsmonitor.feature.sessions

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.shortsmonitor.app.R
import com.shortsmonitor.app.ui.PlaceholderScreen

/**
 * 세션 기록 (Stage J에서 세션 목록·검색·필터로 구현)
 */
@Composable
fun SessionsScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        titleRes = R.string.screen_sessions_title,
        descriptionRes = R.string.placeholder_sessions,
        modifier = modifier,
    )
}
