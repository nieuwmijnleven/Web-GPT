package com.shortsmonitor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 실제 노출 이벤트 (구현 계획: `exposure_event`)
 * 사용자가 실제로 활성화해 본 쇼츠의 순서. 같은 영상이 다시 노출되면 새 이벤트를 생성한다.
 */
@Entity(
    tableName = "exposure_event",
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
        Index(value = ["video_id"]),
    ],
)
data class ExposureEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "profile_segment_id")
    val profileSegmentId: Long? = null,
    @ColumnInfo(name = "video_id")
    val videoId: String,
    @ColumnInfo(name = "exposed_at")
    val exposedAt: Long,
    @ColumnInfo(name = "exposed_until")
    val exposedUntil: Long? = null,
    @ColumnInfo(name = "exposure_order")
    val exposureOrder: Int,
)
