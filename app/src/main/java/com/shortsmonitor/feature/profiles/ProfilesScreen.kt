package com.shortsmonitor.feature.profiles

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.shortsmonitor.app.R
import com.shortsmonitor.app.ui.PlaceholderScreen

/**
 * 브라우저 테스트 프로필 (Stage L에서 카드형 목록·무작위 생성으로 구현)
 */
@Composable
fun ProfilesScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        titleRes = R.string.screen_profiles_title,
        descriptionRes = R.string.placeholder_profiles,
        modifier = modifier,
    )
}
