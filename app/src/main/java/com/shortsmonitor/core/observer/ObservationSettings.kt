package com.shortsmonitor.core.observer

/**
 * 관찰 설정 (O단계).
 *
 * [ObservationRecorder]가 메시지마다 [settings] 람다로 현재 값을 읽어
 * 다음 관찰 메시지부터 즉시 반영한다.
 *
 * - [saveListSnapshots]: false면 현재 DOM 목록 스냅샷을 저장하지 않는다. 탐지는 계속 동작한다.
 * - [stabilizeCandidates]: false면 후보 등록 즉시 의심 이벤트로 확정한다.
 * - [saveMetadata]: false면 제목·채널명을 저장하지 않는다.
 * - [saveThumbnails]: false면 썸네일 주소를 저장하지 않는다.
 */
data class ObservationSettings(
    val saveListSnapshots: Boolean = true,
    val stabilizeCandidates: Boolean = true,
    val saveMetadata: Boolean = true,
    val saveThumbnails: Boolean = true,
)
