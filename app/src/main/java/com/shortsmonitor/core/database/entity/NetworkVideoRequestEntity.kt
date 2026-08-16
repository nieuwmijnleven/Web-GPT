package com.shortsmonitor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shortsmonitor.core.model.NetworkRequestKind

/**
 * 개별 영상 네트워크 관찰 (v5: `network_video_request`).
 *
 * `player` 또는 `reel_item_watch` 요청이 발생한 사실을 저장한다.
 * 요청 순서([requestOrder])는 시퀀스 응답의 예상 위치와 대조해
 * '서버가 전달한 순서'와 '실제 요청 순서'가 일치하는지 확인하는 데 사용한다.
 */
@Entity(
    tableName = "network_video_request",
    foreignKeys = [
        ForeignKey(
            entity = ObservationSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = NetworkSequenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["video_id"]),
        Index(value = ["sequence_id"]),
    ],
)
data class NetworkVideoRequestEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "video_id")
    val videoId: String? = null,
    @ColumnInfo(name = "request_kind")
    val requestKind: NetworkRequestKind,
    @ColumnInfo(name = "requested_at")
    val requestedAt: Long,
    @ColumnInfo(name = "page_url")
    val pageUrl: String? = null,
    /** 이 요청이 속한 것으로 추정되는 시퀀스 식별값. */
    @ColumnInfo(name = "sequence_id")
    val sequenceId: Long? = null,
    /** 시퀀스 내 예상 위치 (영상 식별값으로 대조). null이면 알 수 없음. */
    @ColumnInfo(name = "expected_position")
    val expectedPosition: Int? = null,
    /** 세션 내 실제 요청 순서 (1부터). */
    @ColumnInfo(name = "request_order")
    val requestOrder: Int,
)
