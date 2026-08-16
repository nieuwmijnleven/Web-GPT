package com.shortsmonitor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shortsmonitor.core.model.EntryContext
import com.shortsmonitor.core.model.SequenceParseStatus

/**
 * 네트워크 시퀀스 (v5: `network_sequence`).
 *
 * 한 번의 `reel_watch_sequence` 응답(또는 해석 가능한 시퀀스 상태)을 나타낸다.
 * DOM 목록 스냅샷([ListSnapshotEntity])과 의미가 다르며 별도로 저장한다.
 * DOM 목록은 '화면에서 관찰 가능한 순서'이고, 이 테이블은 '서버가 전달한 추천 순서'다.
 *
 * 민감 원문(sequenceParams 원문, continuation 원문, 추적 파라미터)은 저장하지 않고
 * 해시·길이·존재 여부만 저장한다.
 */
@Entity(
    tableName = "network_sequence",
    foreignKeys = [
        ForeignKey(
            entity = ObservationSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["session_id"]),
    ],
)
data class NetworkSequenceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    /** 요청/응답 상관관계 식별값 (세션 내 임시 값). 민감한 요청 값은 사용하지 않는다. */
    @ColumnInfo(name = "correlation_id")
    val correlationId: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "page_url")
    val pageUrl: String? = null,
    /** 시퀀스의 현재(첫 번째) 영상 식별값. */
    @ColumnInfo(name = "current_video_id")
    val currentVideoId: String? = null,
    /** 진입 컨텍스트 (검색·홈·채널·Shorts 영상 등). */
    @ColumnInfo(name = "entry_context")
    val entryContext: EntryContext,
    /** 시퀀스 원문 해시 (영상 식별값 순서 기준). 원문 아님. */
    @ColumnInfo(name = "sequence_hash")
    val sequenceHash: String? = null,
    /** continuation 안전 해시. 원문 아님. */
    @ColumnInfo(name = "continuation_hash")
    val continuationHash: String? = null,
    @ColumnInfo(name = "parser_version")
    val parserVersion: String? = null,
    @ColumnInfo(name = "parse_status")
    val parseStatus: SequenceParseStatus,
    /** 파싱 경고 코드 목록 (JSON 배열 문자열). */
    @ColumnInfo(name = "warnings_json")
    val warningsJson: String? = null,
    /** 이 시퀀스의 계보 판정 행 식별값 ([SequenceLineageEntity]). */
    @ColumnInfo(name = "lineage_id")
    val lineageId: Long? = null,
)
