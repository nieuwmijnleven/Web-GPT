package com.shortsmonitor.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shortsmonitor.core.database.entity.NetworkSequenceItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkSequenceItemDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: NetworkSequenceItemEntity): Long

    @Query("SELECT * FROM network_sequence_item WHERE sequence_id = :sequenceId ORDER BY position ASC")
    fun observeBySequence(sequenceId: Long): Flow<List<NetworkSequenceItemEntity>>

    /** 내보내기용 일회성 조회. */
    @Query("SELECT * FROM network_sequence_item WHERE sequence_id = :sequenceId ORDER BY position ASC")
    suspend fun getBySequence(sequenceId: Long): List<NetworkSequenceItemEntity>
}
