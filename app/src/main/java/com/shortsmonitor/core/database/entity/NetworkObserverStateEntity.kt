package com.shortsmonitor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.shortsmonitor.core.model.SequenceParseStatus

/**
 * 네트워크 관찰기 상태 (v5: `network_observer_state`). 세션당 1행.
 *
 * 관찰기 설치 시각·문서 시작 주입 지원 여부·첫 요청 시각·초기 시퀀스 누락 가능성 등을
 * 기록한다. 초기 시퀀스를 놓친 세션은 [missedInitialPossible]이 true가 되고
 * 중간 삽입을 확정하지 않는다(신뢰도 제한).
 */
@Entity(
    tableName = "network_observer_state",
    foreignKeys = [
        ForeignKey(
            entity = ObservationSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class NetworkObserverStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "installed_at")
    val installedAt: Long? = null,
    @ColumnInfo(name = "document_start_supported")
    val documentStartSupported: Boolean,
    /** 관찰기 설치 전에 요청이 발생했을 가능성. */
    @ColumnInfo(name = "missed_initial_possible")
    val missedInitialPossible: Boolean,
    /** 초기 시퀀스를 놓쳐 삽입 분석 신뢰도가 제한된 세션 여부. */
    @ColumnInfo(name = "restricted")
    val restricted: Boolean,
    @ColumnInfo(name = "first_request_at")
    val firstRequestAt: Long? = null,
    @ColumnInfo(name = "last_sequence_request_at")
    val lastSequenceRequestAt: Long? = null,
    @ColumnInfo(name = "last_sequence_response_at")
    val lastSequenceResponseAt: Long? = null,
    @ColumnInfo(name = "last_sequence_video_count")
    val lastSequenceVideoCount: Int = 0,
    @ColumnInfo(name = "last_parse_status")
    val lastParseStatus: SequenceParseStatus = SequenceParseStatus.NONE,
    @ColumnInfo(name = "current_lineage")
    val currentLineage: String? = null,
    @ColumnInfo(name = "warnings_json")
    val warningsJson: String? = null,
)
