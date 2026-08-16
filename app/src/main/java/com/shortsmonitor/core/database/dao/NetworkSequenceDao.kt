package com.shortsmonitor.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shortsmonitor.core.database.entity.NetworkSequenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkSequenceDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(sequence: NetworkSequenceEntity): Long

    @Query("SELECT * FROM network_sequence WHERE id = :id")
    suspend fun getById(id: Long): NetworkSequenceEntity?

    @Query("SELECT * FROM network_sequence WHERE session_id = :sessionId ORDER BY created_at ASC")
    fun observeBySession(sessionId: Long): Flow<List<NetworkSequenceEntity>>

    /** 내보내기용 일회성 조회. */
    @Query("SELECT * FROM network_sequence WHERE session_id = :sessionId ORDER BY created_at ASC")
    suspend fun getBySession(sessionId: Long): List<NetworkSequenceEntity>

    /** 세션의 마지막 시퀀스 (계보 판정 기준). */
    @Query("SELECT * FROM network_sequence WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestBySession(sessionId: Long): NetworkSequenceEntity?

    /** 계보 판정 후 시퀀스에 계보 행 식별값을 반영한다. */
    @Query("UPDATE network_sequence SET lineage_id = :lineageId WHERE id = :id")
    suspend fun updateLineageId(id: Long, lineageId: Long?)
}
