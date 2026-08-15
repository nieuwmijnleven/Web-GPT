package com.shortsmonitor.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shortsmonitor.core.database.entity.ExposureEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExposureEventDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: ExposureEventEntity): Long

    @Query("SELECT * FROM exposure_event WHERE session_id = :sessionId ORDER BY exposure_order ASC")
    fun observeBySession(sessionId: Long): Flow<List<ExposureEventEntity>>
}
