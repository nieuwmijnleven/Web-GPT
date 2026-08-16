package com.shortsmonitor.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shortsmonitor.core.database.entity.NetworkObserverStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkObserverStateDao {

    /** 세션당 1행이므로 REPLACE로 갱신한다. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: NetworkObserverStateEntity)

    @Query("SELECT * FROM network_observer_state WHERE session_id = :sessionId")
    fun observeBySession(sessionId: Long): Flow<NetworkObserverStateEntity?>

    @Query("SELECT * FROM network_observer_state WHERE session_id = :sessionId")
    suspend fun getBySession(sessionId: Long): NetworkObserverStateEntity?

    /** 가장 최근에 갱신된 상태 1행 (진단 화면용). */
    @Query("SELECT * FROM network_observer_state ORDER BY last_sequence_response_at DESC LIMIT 1")
    suspend fun getLatest(): NetworkObserverStateEntity?
}
