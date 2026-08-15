package com.shortsmonitor.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shortsmonitor.core.database.entity.ObservedShortEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ObservedShortDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ObservedShortEntity): Long

    @Query("SELECT * FROM observed_short WHERE session_id = :sessionId ORDER BY first_seen_at ASC")
    fun observeBySession(sessionId: Long): Flow<List<ObservedShortEntity>>

    /** 내보내기용 일회성 조회. */
    @Query("SELECT * FROM observed_short WHERE session_id = :sessionId ORDER BY first_seen_at ASC")
    suspend fun getBySession(sessionId: Long): List<ObservedShortEntity>

    /** 모든 세션의 관찰 쇼츠 (세션 목록 검색·집계용). */
    @Query("SELECT * FROM observed_short ORDER BY session_id ASC, first_seen_at ASC")
    fun observeAll(): Flow<List<ObservedShortEntity>>

    @Query("SELECT * FROM observed_short WHERE session_id = :sessionId AND video_id = :videoId LIMIT 1")
    suspend fun getByVideoId(sessionId: Long, videoId: String): ObservedShortEntity?

    @Query("SELECT COUNT(*) FROM observed_short WHERE session_id = :sessionId")
    suspend fun countBySession(sessionId: Long): Int

    /** 이미 발견된 쇼츠의 마지막 관찰 시각·메타데이터·앞뒤 영상을 갱신한다. */
    @Query(
        "UPDATE observed_short SET " +
            "last_seen_at = :lastSeenAt, " +
            "title = :title, " +
            "channel_name = :channelName, " +
            "thumbnail_url = :thumbnailUrl, " +
            "prev_video_id = :prevVideoId, " +
            "next_video_id = :nextVideoId " +
            "WHERE session_id = :sessionId AND video_id = :videoId",
    )
    suspend fun updateSeen(
        sessionId: Long,
        videoId: String,
        lastSeenAt: Long,
        title: String?,
        channelName: String?,
        thumbnailUrl: String?,
        prevVideoId: String?,
        nextVideoId: String?,
    )

    /** 실제 노출(활성화)된 쇼츠의 활성화 시각을 기록한다. */
    @Query(
        "UPDATE observed_short SET activated_at = :activatedAt, last_seen_at = :activatedAt " +
            "WHERE session_id = :sessionId AND video_id = :videoId",
    )
    suspend fun markActivated(sessionId: Long, videoId: String, activatedAt: Long)
}
