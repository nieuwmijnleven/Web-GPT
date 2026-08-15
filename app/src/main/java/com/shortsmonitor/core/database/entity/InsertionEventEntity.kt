package com.shortsmonitor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shortsmonitor.core.model.AutoVerdict
import com.shortsmonitor.core.model.UserVerdict

/**
 * 중간 삽입 의심 이벤트 (구현 계획: `insertion_event`)
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
)
