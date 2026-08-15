package com.shortsmonitor.feature.events

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.shortsmonitor.app.R
import com.shortsmonitor.app.ui.PlaceholderScreen

/**
 * 의심 이벤트 (Stage K에서 목록·상세·사용자 판정으로 구현)
 */
@Composable
fun EventsScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        titleRes = R.string.screen_events_title,
        descriptionRes = R.string.placeholder_events,
        modifier = modifier,
    )
}
