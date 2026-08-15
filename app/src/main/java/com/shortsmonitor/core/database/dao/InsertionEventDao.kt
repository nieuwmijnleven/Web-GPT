package com.shortsmonitor.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shortsmonitor.core.database.entity.InsertionEventEntity
import com.shortsmonitor.core.model.UserVerdict
import kotlinx.coroutines.flow.Flow

@Dao
interface InsertionEventDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: InsertionEventEntity): Long

    @Query("SELECT * FROM insertion_event WHERE session_id = :sessionId ORDER BY detected_at DESC")
    fun observeBySession(sessionId: Long): Flow<List<InsertionEventEntity>>

    @Query("SELECT * FROM insertion_event ORDER BY detected_at DESC")
    fun observeAll(): Flow<List<InsertionEventEntity>>

    @Query("UPDATE insertion_event SET user_verdict = :userVerdict, user_memo = :userMemo WHERE id = :id")
    suspend fun updateVerdict(id: Long, userVerdict: UserVerdict, userMemo: String?)
}
