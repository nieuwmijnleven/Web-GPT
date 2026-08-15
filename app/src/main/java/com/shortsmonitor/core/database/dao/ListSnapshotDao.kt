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

    /** 세션 초기화(SESSION_RESET) 기록이 있는 세션 식별자 목록. J단계 필터·상세에서 사용한다. */
    @Query("SELECT DISTINCT session_id FROM list_snapshot WHERE change_reason = 'SESSION_RESET'")
    fun observeSessionIdsWithReset(): Flow<List<Long>>

    /** 특정 스냅샷 하나를 조회한다. 의심 이벤트 상세(K단계)의 변경 전후 비교에 사용한다. */
    @Query("SELECT * FROM list_snapshot WHERE id = :id")
    fun observeById(id: Long): Flow<ListSnapshotEntity?>
}
