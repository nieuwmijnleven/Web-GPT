package com.shortsmonitor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shortsmonitor.core.model.SequenceLineageRelation

/**
 * 시퀀스 계보 판정 (v5: `sequence_lineage`).
 *
 * 서로 다른 시퀀스가 같은 추천 흐름의 갱신(SAME_FLOW)인지, 완전히 새로운 탐색
 * 결과(NEW_CONTEXT)인지, 판정 불가(UNKNOWN)인지 기록한다.
 * 계보가 불명확하면 같은 흐름으로 확정하지 않는다.
 * 판정 신호([signalsJson])는 민감정보 없이 요약 신호만 저장한다.
 */
@Entity(
    tableName = "sequence_lineage",
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
        Index(value = ["from_sequence_id"]),
    ],
)
data class SequenceLineageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "from_sequence_id")
    val fromSequenceId: Long,
    @ColumnInfo(name = "to_sequence_id")
    val toSequenceId: Long,
    @ColumnInfo(name = "relation")
    val relation: SequenceLineageRelation,
    /** 판정 신호 JSON 문자열 (공통 구간 길이·현재 영상 연속성 등). */
    @ColumnInfo(name = "signals_json")
    val signalsJson: String? = null,
    @ColumnInfo(name = "decided_at")
    val decidedAt: Long,
)
