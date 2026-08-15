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

    /** 내보내기용 일회성 전체 조회. */
    @Query("SELECT * FROM browser_profile ORDER BY created_at DESC")
    suspend fun getAll(): List<BrowserProfileEntity>

    @Query("SELECT * FROM browser_profile WHERE id = :id")
    suspend fun getById(id: Long): BrowserProfileEntity?

    @Query("SELECT * FROM browser_profile WHERE id = :id")
    fun observeById(id: Long): Flow<BrowserProfileEntity?>

    @Query("UPDATE browser_profile SET last_used_at = :lastUsedAt WHERE id = :id")
    suspend fun updateLastUsed(id: Long, lastUsedAt: Long)

    /** 전체 프로필 삭제 (O단계 전체 데이터 삭제). 삭제된 프로필 수를 반환한다. */
    @Query("DELETE FROM browser_profile")
    suspend fun deleteAll(): Int
}
