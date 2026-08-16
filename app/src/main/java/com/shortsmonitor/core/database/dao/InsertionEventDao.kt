package com.shortsmonitor.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shortsmonitor.core.database.entity.InsertionEventEntity
import com.shortsmonitor.core.model.AutoVerdict
import com.shortsmonitor.core.model.UserVerdict
import kotlinx.coroutines.flow.Flow

@Dao
interface InsertionEventDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: InsertionEventEntity): Long

    @Query("SELECT * FROM insertion_event WHERE session_id = :sessionId ORDER BY detected_at DESC")
    fun observeBySession(sessionId: Long): Flow<List<InsertionEventEntity>>

    /** 내보내기용 일회성 조회. */
    @Query("SELECT * FROM insertion_event WHERE session_id = :sessionId ORDER BY detected_at DESC")
    suspend fun getBySession(sessionId: Long): List<InsertionEventEntity>

    @Query("SELECT * FROM insertion_event ORDER BY detected_at DESC")
    fun observeAll(): Flow<List<InsertionEventEntity>>

    @Query("SELECT * FROM insertion_event WHERE id = :id")
    fun observeById(id: Long): Flow<InsertionEventEntity?>

    @Query("UPDATE insertion_event SET user_verdict = :userVerdict, user_memo = :userMemo WHERE id = :id")
    suspend fun updateVerdict(id: Long, userVerdict: UserVerdict, userMemo: String?)

    /** 사용자 판정만 변경한다. 메모는 그대로 둔다. */
    @Query("UPDATE insertion_event SET user_verdict = :userVerdict WHERE id = :id")
    suspend fun updateUserVerdict(id: Long, userVerdict: UserVerdict)

    /** 사용자 메모만 변경한다. 판정은 그대로 둔다. */
    @Query("UPDATE insertion_event SET user_memo = :userMemo WHERE id = :id")
    suspend fun updateUserMemo(id: Long, userMemo: String?)

    // ===== 네트워크 시퀀스 기반 후보/확정 관리 (v5) =====

    /** 아직 CANDIDATE 상태인 네트워크 후보를 찾는다. */
    @Query(
        "SELECT * FROM insertion_event WHERE session_id = :sessionId " +
            "AND new_video_id = :newVideoId AND source = 'NETWORK' " +
            "AND auto_verdict IN ('CANDIDATE', 'UNKNOWN') " +
            "ORDER BY detected_at DESC LIMIT 1",
    )
    suspend fun findNetworkCandidate(sessionId: Long, newVideoId: String): InsertionEventEntity?

    /** 네트워크 후보의 자동 판정·근거·시퀀스·강화 증거를 갱신한다. */
    @Query(
        "UPDATE insertion_event SET auto_verdict = :autoVerdict, evidence_json = :evidenceJson, " +
            "network_before_sequence_id = :beforeSequenceId, " +
            "network_after_sequence_id = :afterSequenceId, " +
            "strengthened_by_json = :strengthenedByJson " +
            "WHERE id = :id",
    )
    suspend fun updateNetworkOutcome(
        id: Long,
        autoVerdict: AutoVerdict,
        evidenceJson: String?,
        beforeSequenceId: Long?,
        afterSequenceId: Long?,
        strengthenedByJson: String?,
    )

    /** 자동 판정별 이벤트 수 (세션 상세·진단용). */
    @Query(
        "SELECT auto_verdict AS verdict, COUNT(*) AS cnt FROM insertion_event " +
            "WHERE session_id = :sessionId GROUP BY auto_verdict",
    )
    suspend fun countByVerdict(sessionId: Long): List<VerdictCount>

    /** 자동 판정별 이벤트 수 집계 결과. */
    data class VerdictCount(
        val verdict: String,
        val cnt: Long,
    )
}

