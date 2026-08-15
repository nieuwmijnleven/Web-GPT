package com.shortsmonitor.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shortsmonitor.core.database.entity.ListSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ListSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(snapshot: ListSnapshotEntity): Long

    @Query("SELECT * FROM list_snapshot WHERE session_id = :sessionId ORDER BY created_at ASC")
    fun observeBySession(sessionId: Long): Flow<List<ListSnapshotEntity>>
}
