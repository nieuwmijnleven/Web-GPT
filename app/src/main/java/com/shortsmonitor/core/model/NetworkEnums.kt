package com.shortsmonitor.core.model

/** 네트워크 요청 종류. Shorts 시퀀스·영상 관련 요청만 관찰 대상이다. */
enum class NetworkRequestKind {
    /** `reel_watch_sequence` — Shorts 추천 시퀀스 요청/응답. */
    REEL_WATCH_SEQUENCE,

    /** `reel_item_watch` — 개별 영상 실행 정보 요청. */
    REEL_ITEM_WATCH,

    /** `player` — 영상 재생 요청. */
    PLAYER,

    /** 그 외 관찰 대상이 아닌 요청. */
    OTHER,
}

/** 시퀀스 응답 파싱 상태. */
enum class SequenceParseStatus {
    /** 정상 파싱됨. */
    PARSED,

    /** 일부만 파싱됨 (일부 항목 누락·중복 등). */
    PARTIAL,

    /** 파싱 실패 (JSON 오류 등). */
    FAILED,

    /** 예상하지 못한 응답 구조. */
    UNSUPPORTED,

    /** 아직 응답 없음. */
    NONE,
}

/** 시퀀스 항목 종류. */
enum class SequenceEntryKind {
    /** 영상 항목. */
    VIDEO,

    /** 비영상 항목 (안내 카드 등). */
    NON_VIDEO,
}

/** 시퀀스 계보 관계. */
enum class SequenceLineageRelation {
    /** 같은 추천 흐름의 갱신. */
    SAME_FLOW,

    /** 완전히 새로운 탐색 컨텍스트. */
    NEW_CONTEXT,

    /** 판정 불가/보류. */
    UNKNOWN,

    /** 아직 계보 없음 (첫 시퀀스). */
    NONE,
}

/** 삽입 이벤트의 근거 출처. */
enum class InsertionSource {
    /** DOM 목록 스냅샷 기준 (보조 분석). */
    DOM,

    /** 네트워크 시퀀스 기준 (주 분석). */
    NETWORK,
}

/**
 * JavaScript 관찰기가 판단한 URL 변경 유형.
 * 같은 시퀀스 안에서 활성 영상 주소만 바뀐 경우 기준 목록을 초기화하지 않는다.
 */
enum class UrlChangeType {
    /** 같은 Shorts 시퀀스 안에서 활성 영상만 변경. 기준 유지. */
    SAME_SEQUENCE_ACTIVE_CHANGE,

    /** 새 탐색 컨텍스트 (검색 진입, 홈 진입, 채널, 다른 화면 등). 기준 교체. */
    NEW_CONTEXT,

    /** 전체 페이지 재로드. 기준 교체. */
    FULL_RELOAD,

    /** 유튜브 외부 페이지 이동. 기준 교체. */
    EXTERNAL,
}

/** 페이지 진입 컨텍스트 (네이티브가 URL에서 분류). */
enum class EntryContext {
    /** Shorts 영상 주소 (/shorts/<videoId>). */
    SHORTS_VIDEO,

    /** Shorts 홈 (/shorts). */
    SHORTS_HOME,

    /** 검색 결과에서 진입 (/search). */
    SEARCH_RESULT,

    /** 채널 Shorts 목록 (/@channel/shorts). */
    CHANNEL_SHORTS,

    /** 그 외. */
    OTHER,
}

/**
 * 관찰·분석 오류 코드.
 * 사용자 화면에는 기술 예외 대신 이 코드에 대응하는 이해 가능한 상태 설명을 보여준다.
 */
enum class ObserverErrorCode {
    /** 네트워크 관찰기 설치 실패. */
    NETWORK_OBSERVER_INSTALL_FAILED,

    /** 초기 시퀀스 요청을 관찰기 설치 전에 놓쳤을 가능성. */
    INITIAL_REQUEST_MISSED,

    /** 요청 본문 파싱 실패. */
    REQUEST_BODY_PARSE_FAILED,

    /** 응답 JSON 파싱 실패. */
    RESPONSE_JSON_PARSE_FAILED,

    /** 응답에서 영상 식별값을 찾지 못함. */
    SEQUENCE_NO_VIDEO_IDS,

    /** 예상하지 못한 시퀀스 응답 구조. */
    SEQUENCE_STRUCTURE_UNSUPPORTED,

    /** 브리지 메시지 크기 초과. */
    MESSAGE_SIZE_EXCEEDED,

    /** 시퀀스 계보 판정 불가. */
    LINEAGE_INDETERMINATE,

    /** DOM과 네트워크 시퀀스 불일치. */
    DOM_NETWORK_MISMATCH,

    /** 활성 영상 판정 불가. */
    ACTIVE_VIDEO_INDETERMINATE,
}
