package com.shortsmonitor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shortsmonitor.core.model.SnapshotChangeReason

/**
 * 목록 스냅샷 (구현 계획: `list_snapshot`)
 * 현재 DOM에서 관찰 가능한 쇼츠 순서. 유튜브 서버 전체 추천 목록이 아니다.
 */
@Entity(
    tableName = "list_snapshot",
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
data class ListSnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "profile_segment_id")
    val profileSegmentId: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "current_url")
    val currentUrl: String? = null,
    @ColumnInfo(name = "active_video_id")
    val activeVideoId: String? = null,
    @ColumnInfo(name = "video_ids_json")
    val videoIdsJson: String,
    @ColumnInfo(name = "change_reason")
    val changeReason: SnapshotChangeReason,
    @ColumnInfo(name = "dom_revision")
    val domRevision: Long = 0,
)
