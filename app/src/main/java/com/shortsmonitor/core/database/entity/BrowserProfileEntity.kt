package com.shortsmonitor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.shortsmonitor.core.model.ProfileTemplateType

/**
 * 브라우저 테스트 프로필 (구현 계획: `browser_profile`)
 * WebView 노출값을 바꾸는 프로필로, IP나 실제 하드웨어를 변경하는 것이 아니다.
 */
@Entity(
    tableName = "browser_profile",
)
data class BrowserProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "template_type")
    val templateType: ProfileTemplateType,
    @ColumnInfo(name = "user_agent")
    val userAgent: String,
    @ColumnInfo(name = "language")
    val language: String,
    @ColumnInfo(name = "timezone")
    val timezone: String,
    @ColumnInfo(name = "screen_override")
    val screenOverride: String? = null,
    @ColumnInfo(name = "hardware_override")
    val hardwareOverride: String? = null,
    @ColumnInfo(name = "touch_override")
    val touchOverride: Boolean? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long? = null,
)
