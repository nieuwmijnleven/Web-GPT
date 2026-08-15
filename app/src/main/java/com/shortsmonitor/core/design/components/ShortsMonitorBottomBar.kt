package com.shortsmonitor.core.design.components

import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow

/** 글꼴 확대 판정 기준 배율. 이 이상이면 아이콘과 선택된 메뉴명만 표시한다. */
private const val LARGE_FONT_SCALE = 1.3f

/** 하단 내비게이션 항목 데이터. */
data class ShortsMonitorBottomBarItem(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

/**
 * shorts monitor 공통 하단 바.
 * 관찰 / 기록 / 이벤트 / 프로필 / 설정 구조를 유지한다.
 * 좁은 화면과 큰 글꼴에서 텍스트가 겹치지 않도록,
 * 글꼴 확대 시에는 아이콘과 선택된 메뉴명만 표시하는 적응형 방식을 적용한다.
 */
@Composable
fun ShortsMonitorBottomBar(
    items: List<ShortsMonitorBottomBarItem>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    val showAllLabels = fontScale <= LARGE_FONT_SCALE
    NavigationBar(modifier = modifier) {
        items.forEach { item ->
            val selected = currentRoute == item.route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(item.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.icon,
                        contentDescription = stringResource(item.labelRes),
                    )
                },
                label = {
                    if (showAllLabels || selected) {
                        Text(
                            text = stringResource(item.labelRes),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        }
    }
}
