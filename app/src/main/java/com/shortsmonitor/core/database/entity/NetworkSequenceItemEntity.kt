package com.shortsmonitor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shortsmonitor.core.model.SequenceEntryKind

/**
 * 네트워크 시퀀스 항목 (v5: `network_sequence_item`).
 *
 * 시퀀스 내부의 각 엔트리를 나타낸다. 영상 엔트리와 비영상 엔트리(안내 카드 등)를
 * 구분한다. 실행 파라미터(playerParams)·continuation·추적 파라미터는 원문을 저장하지 않고
 * 존재 여부와 안전 해시만 저장한다.
 */
@Entity(
    tableName = "network_sequence_item",
    foreignKeys = [
        ForeignKey(
            entity = NetworkSequenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sequence_id"]),
    ],
)
data class NetworkSequenceItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "sequence_id")
    val sequenceId: Long,
    /** 시퀀스 내 위치 (0부터). */
    @ColumnInfo(name = "position")
    val position: Int,
    /** 영상 식별값. 비영상 엔트리는 null일 수 있다. */
    @ColumnInfo(name = "video_id")
    val videoId: String? = null,
    @ColumnInfo(name = "entry_kind")
    val entryKind: SequenceEntryKind,
    /** 비영상 엔트리 세부 종류 (예: reel_non_video_content). */
    @ColumnInfo(name = "non_video_kind")
    val nonVideoKind: String? = null,
    /** 현재(첫 번째) 영상 여부. */
    @ColumnInfo(name = "is_current")
    val isCurrent: Boolean,
    /** 실행 파라미터 존재 여부. */
    @ColumnInfo(name = "has_player_params")
    val hasPlayerParams: Boolean,
    /** softRefreshContinuation 존재 여부. */
    @ColumnInfo(name = "has_continuation")
    val hasContinuation: Boolean,
    /** 추적 파라미터 안전 해시. 원문 아님. */
    @ColumnInfo(name = "tracking_hash")
    val trackingHash: String? = null,
    /** 실행 파라미터 안전 해시. 원문 아님. */
    @ColumnInfo(name = "player_params_hash")
    val playerParamsHash: String? = null,
    /** continuation 안전 해시. 원문 아님. */
    @ColumnInfo(name = "continuation_hash")
    val continuationHash: String? = null,
)
