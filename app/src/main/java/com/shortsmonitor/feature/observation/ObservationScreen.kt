package com.shortsmonitor.feature.observation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.shortsmonitor.app.R
import com.shortsmonitor.app.ui.PlaceholderScreen

/**
 * 관찰 홈 (Stage D에서 상태 요약 카드·최근 세션 등으로 구현)
 */
@Composable
fun ObservationScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        titleRes = R.string.screen_observation_title,
        descriptionRes = R.string.placeholder_observation,
        modifier = modifier,
    )
}
