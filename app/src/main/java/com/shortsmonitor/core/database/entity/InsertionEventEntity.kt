package com.shortsmonitor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shortsmonitor.core.model.AutoVerdict
import com.shortsmonitor.core.model.InsertionSource
import com.shortsmonitor.core.model.UserVerdict

/**
 * 중간 삽입 의심 이벤트 (구현 계획: `insertion_event`)
 *
 * v5에서 네트워크 시퀀스 기반 판정을 추가했다.
 * - [source]: 근거 출처 (DOM=보조 분석, NETWORK=주 분석)
 * - [networkBeforeSequenceId]/[networkAfterSequenceId]: 네트워크 시퀀스 기준 변경 전/후
 * - [strengthenedByJson]: 확정에 사용된 강화 증거 목록 (player 요청, reel_item_watch, DOM 활성)
 *
 * 네트워크 증거 없이 DOM만으로는 확정(CONFIRMED)하지 않는다.
 */
@Entity(
    tableName = "insertion_event",
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
data class InsertionEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "new_video_id")
    val newVideoId: String,
    @ColumnInfo(name = "prev_video_id")
    val prevVideoId: String? = null,
    @ColumnInfo(name = "next_video_id")
    val nextVideoId: String? = null,
    @ColumnInfo(name = "before_snapshot_id")
    val beforeSnapshotId: Long? = null,
    @ColumnInfo(name = "after_snapshot_id")
    val afterSnapshotId: Long? = null,
    @ColumnInfo(name = "detected_at")
    val detectedAt: Long,
    @ColumnInfo(name = "auto_verdict")
    val autoVerdict: AutoVerdict,
    @ColumnInfo(name = "user_verdict")
    val userVerdict: UserVerdict,
    @ColumnInfo(name = "user_memo")
    val userMemo: String? = null,
    /** 판정 근거 JSON (H단계: 확정 조건·안정화 여부 + v5: 네트워크 증거). v3에서 추가. */
    @ColumnInfo(name = "evidence_json")
    val evidenceJson: String? = null,
    /** 근거 출처 (v5). 기본값 DOM. */
    @ColumnInfo(name = "source", defaultValue = "DOM")
    val source: InsertionSource = InsertionSource.DOM,
    /** 네트워크 시퀀스 기준 변경 전 시퀀스 식별값 (v5). */
    @ColumnInfo(name = "network_before_sequence_id")
    val networkBeforeSequenceId: Long? = null,
    /** 네트워크 시퀀스 기준 변경 후 시퀀스 식별값 (v5). */
    @ColumnInfo(name = "network_after_sequence_id")
    val networkAfterSequenceId: Long? = null,
    /** 확정에 사용된 강화 증거 JSON 배열 (v5). 예: ["player_request","dom_active"]. */
    @ColumnInfo(name = "strengthened_by_json")
    val strengthenedByJson: String? = null,
)
