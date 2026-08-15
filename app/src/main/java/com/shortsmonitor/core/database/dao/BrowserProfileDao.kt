package com.shortsmonitor.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shortsmonitor.core.database.entity.BrowserProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowserProfileDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: BrowserProfileEntity): Long

    @Query("SELECT * FROM browser_profile ORDER BY created_at DESC")
    fun observeAll(): Flow<List<BrowserProfileEntity>>

    @Query("SELECT * FROM browser_profile WHERE id = :id")
    suspend fun getById(id: Long): BrowserProfileEntity?

    @Query("UPDATE browser_profile SET last_used_at = :lastUsedAt WHERE id = :id")
    suspend fun updateLastUsed(id: Long, lastUsedAt: Long)
}
