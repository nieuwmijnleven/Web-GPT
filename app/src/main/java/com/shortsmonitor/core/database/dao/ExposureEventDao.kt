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

    /** 내보내기용 일회성 조회. */
    @Query("SELECT * FROM exposure_event WHERE session_id = :sessionId ORDER BY exposure_order ASC")
    suspend fun getBySession(sessionId: Long): List<ExposureEventEntity>

    /** 아직 종료되지 않은 노출 이벤트의 노출 종료 시각을 기록한다. */
    @Query(
        "UPDATE exposure_event SET exposed_until = :until " +
            "WHERE session_id = :sessionId AND exposed_until IS NULL",
    )
    suspend fun closeOpenExposures(sessionId: Long, until: Long)

    @Query("SELECT COUNT(*) FROM exposure_event WHERE session_id = :sessionId")
    suspend fun countBySession(sessionId: Long): Int
}
