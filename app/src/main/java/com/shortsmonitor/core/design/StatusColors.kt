package com.shortsmonitor.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 상태별 의미 색상 묶음.
 * 이후 단계에서 StatusChip, 세션/이벤트 카드의 왼쪽 색상 막대 등에 사용한다.
 */
@Immutable
data class StatusColors(
    val active: Color = StatusActive,
    val normal: Color = StatusNormal,
    val suspected: Color = StatusSuspected,
    val error: Color = StatusError,
    val userConfirmed: Color = StatusUserConfirmed,
    val pending: Color = StatusPending,
)

val LocalStatusColors = staticCompositionLocalOf { StatusColors() }
