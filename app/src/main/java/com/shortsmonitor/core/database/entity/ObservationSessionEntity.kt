package com.shortsmonitor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shortsmonitor.core.model.SessionEndReason
import com.shortsmonitor.core.model.SessionStatus

/**
 * 관찰 세션 (구현 계획: `observation_session`)
 */
@Entity(
    tableName = "observation_session",
)
data class ObservationSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "status")
    val status: SessionStatus,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "ended_at")
    val endedAt: Long? = null,
    @ColumnInfo(name = "start_url")
    val startUrl: String? = null,
    @ColumnInfo(name = "end_reason")
    val endReason: SessionEndReason? = null,
    @ColumnInfo(name = "app_version")
    val appVersion: String? = null,
    @ColumnInfo(name = "webview_info")
    val webViewInfo: String? = null,
)
