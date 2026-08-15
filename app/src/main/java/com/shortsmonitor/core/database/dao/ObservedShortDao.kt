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

    @Query("SELECT * FROM observed_short WHERE session_id = :sessionId AND video_id = :videoId LIMIT 1")
    suspend fun getByVideoId(sessionId: Long, videoId: String): ObservedShortEntity?

    @Query("SELECT COUNT(*) FROM observed_short WHERE session_id = :sessionId")
    suspend fun countBySession(sessionId: Long): Int
}
