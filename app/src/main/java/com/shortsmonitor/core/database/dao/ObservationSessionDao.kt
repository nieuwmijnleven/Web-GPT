package com.shortsmonitor.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shortsmonitor.core.database.entity.ObservationSessionEntity
import com.shortsmonitor.core.model.SessionEndReason
import com.shortsmonitor.core.model.SessionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ObservationSessionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: ObservationSessionEntity): Long

    @Query("SELECT * FROM observation_session WHERE id = :id")
    suspend fun getById(id: Long): ObservationSessionEntity?

    @Query("SELECT * FROM observation_session ORDER BY started_at DESC")
    fun observeAll(): Flow<List<ObservationSessionEntity>>

    @Query("SELECT * FROM observation_session WHERE status = 'ACTIVE' ORDER BY started_at DESC LIMIT 1")
    suspend fun getActive(): ObservationSessionEntity?

    @Query("SELECT COUNT(*) FROM observation_session")
    suspend fun count(): Int

    @Query(
        "UPDATE observation_session SET status = :status, ended_at = :endedAt, end_reason = :endReason " +
            "WHERE id = :id",
    )
    suspend fun updateStatus(
        id: Long,
        status: SessionStatus,
        endedAt: Long?,
        endReason: SessionEndReason?,
    )
}
