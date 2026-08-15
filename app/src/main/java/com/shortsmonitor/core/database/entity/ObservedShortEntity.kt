package com.shortsmonitor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shortsmonitor.core.model.ShortIdentityStatus

/**
 * 관찰된 쇼츠 항목 (구현 계획: `observed_short`)
 * 같은 세션에서 같은 영상은 영상 식별값 기준으로 중복 저장하지 않는다.
 */
@Entity(
    tableName = "observed_short",
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
        Index(value = ["session_id", "video_id"], unique = true),
    ],
)
data class ObservedShortEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "video_id")
    val videoId: String,
    @ColumnInfo(name = "video_url")
    val videoUrl: String? = null,
    @ColumnInfo(name = "title")
    val title: String? = null,
    @ColumnInfo(name = "channel_name")
    val channelName: String? = null,
    @ColumnInfo(name = "thumbnail_url")
    val thumbnailUrl: String? = null,
    @ColumnInfo(name = "identity_status")
    val identityStatus: ShortIdentityStatus,
    @ColumnInfo(name = "first_seen_at")
    val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long,
    /** 활성화(실제 노출) 시각. v2에서 추가. */
    @ColumnInfo(name = "activated_at")
    val activatedAt: Long? = null,
    /** 마지막 관찰 시점의 이전 영상 식별값. v2에서 추가. */
    @ColumnInfo(name = "prev_video_id")
    val prevVideoId: String? = null,
    /** 마지막 관찰 시점의 다음 영상 식별값. v2에서 추가. */
    @ColumnInfo(name = "next_video_id")
    val nextVideoId: String? = null,
)
