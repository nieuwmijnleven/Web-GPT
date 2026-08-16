package com.shortsmonitor.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shortsmonitor.core.database.entity.SequenceLineageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SequenceLineageDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(lineage: SequenceLineageEntity): Long

    @Query("SELECT * FROM sequence_lineage WHERE session_id = :sessionId ORDER BY decided_at ASC")
    fun observeBySession(sessionId: Long): Flow<List<SequenceLineageEntity>>

    /** 내보내기용 일회성 조회. */
    @Query("SELECT * FROM sequence_lineage WHERE session_id = :sessionId ORDER BY decided_at ASC")
    suspend fun getBySession(sessionId: Long): List<SequenceLineageEntity>
}
