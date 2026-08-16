package com.shortsmonitor.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shortsmonitor.core.database.entity.NetworkVideoRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkVideoRequestDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(request: NetworkVideoRequestEntity): Long

    @Query("SELECT * FROM network_video_request WHERE session_id = :sessionId ORDER BY request_order ASC")
    fun observeBySession(sessionId: Long): Flow<List<NetworkVideoRequestEntity>>

    /** 내보내기용 일회성 조회. */
    @Query("SELECT * FROM network_video_request WHERE session_id = :sessionId ORDER BY request_order ASC")
    suspend fun getBySession(sessionId: Long): List<NetworkVideoRequestEntity>

    @Query("SELECT COUNT(*) FROM network_video_request WHERE session_id = :sessionId")
    suspend fun countBySession(sessionId: Long): Int

    /** 세션의 마지막 요청 (실제 요청 순서 계산용). */
    @Query("SELECT * FROM network_video_request WHERE session_id = :sessionId ORDER BY request_order DESC LIMIT 1")
    suspend fun getLatestBySession(sessionId: Long): NetworkVideoRequestEntity?
}
