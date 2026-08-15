package com.shortsmonitor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shortsmonitor.core.model.UserVerdict

/**
 * 사용자 판정 변경 이력 (K단계: '사용자 판정 변경 이력이 저장됨').
 *
 * 의심 이벤트의 사용자 판정이 바뀔 때마다 한 행을 추가한다.
 * 이벤트가 삭제되면(FK CASCADE) 함께 정리된다.
 */
@Entity(
    tableName = "verdict_history",
    foreignKeys = [
        ForeignKey(
            entity = InsertionEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["event_id"]),
    ],
)
data class VerdictHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "event_id")
    val eventId: Long,
    @ColumnInfo(name = "user_verdict")
    val userVerdict: UserVerdict,
    /** 변경 시점의 사용자 메모 스냅샷. */
    @ColumnInfo(name = "user_memo")
    val userMemo: String? = null,
    @ColumnInfo(name = "changed_at")
    val changedAt: Long,
)
