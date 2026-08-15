package com.shortsmonitor.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shortsmonitor.core.database.entity.VerdictHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VerdictHistoryDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(history: VerdictHistoryEntity): Long

    /** 특정 의심 이벤트의 판정 변경 이력 (최신순). */
    @Query("SELECT * FROM verdict_history WHERE event_id = :eventId ORDER BY changed_at DESC")
    fun observeByEvent(eventId: Long): Flow<List<VerdictHistoryEntity>>
}
