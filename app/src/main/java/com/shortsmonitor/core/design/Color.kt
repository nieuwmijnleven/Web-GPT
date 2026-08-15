package com.shortsmonitor.core.design

import androidx.compose.ui.graphics.Color

/**
 * 첨부 디자인(DESIGN.md)의 라이트 테마 색상 토큰.
 * 화면에서는 임의 색상을 직접 사용하지 않고 이 토큰을 통해서만 사용한다.
 */

// 브랜드 / 기본
val Primary = Color(0xFF006876)
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFF00BCD4)
val OnPrimaryContainer = Color(0xFF004650)

val Secondary = Color(0xFF9A25AE)
val OnSecondary = Color(0xFFFFFFFF)
val SecondaryContainer = Color(0xFFED76FD)
val OnSecondaryContainer = Color(0xFF69007A)

val Tertiary = Color(0xFF006E1C)
val OnTertiary = Color(0xFFFFFFFF)
val TertiaryContainer = Color(0xFF5DC05F)
val OnTertiaryContainer = Color(0xFF004B10)

val Error = Color(0xFFBA1A1A)
val OnError = Color(0xFFFFFFFF)
val ErrorContainer = Color(0xFFFFDAD6)
val OnErrorContainer = Color(0xFF93000A)

// 표면 계층
val Background = Color(0xFFFCF9F8)
val OnBackground = Color(0xFF1C1B1B)
val Surface = Color(0xFFFCF9F8)
val OnSurface = Color(0xFF1C1B1B)
val SurfaceVariant = Color(0xFFE5E2E1)
val OnSurfaceVariant = Color(0xFF3C494C)
val Outline = Color(0xFF6C797C)
val OutlineVariant = Color(0xFFBBC9CC)
val SurfaceContainerLowest = Color(0xFFFFFFFF)
val SurfaceContainerLow = Color(0xFFF6F3F2)
val SurfaceContainer = Color(0xFFF0EDEC)
val SurfaceContainerHigh = Color(0xFFEBE7E7)
val SurfaceContainerHighest = Color(0xFFE5E2E1)

/**
 * 상태 색상.
 * 구현 계획: 모든 상태는 색상 단독이 아니라 아이콘·텍스트와 함께 표시한다.
 */
val StatusActive = Color(0xFF00BCD4)        // 관찰 활성: 청록색
val StatusNormal = Color(0xFF4CAF50)        // 정상 완료: 녹색
val StatusSuspected = Color(0xFFFF9800)     // 중간 삽입 의심: 주황색
val StatusError = Color(0xFFF44336)         // 오류: 빨간색
val StatusUserConfirmed = Color(0xFF9C27B0) // 사용자 판정 완료: 보라색
val StatusPending = Color(0xFF9E9E9E)       // 판단 보류: 회색
