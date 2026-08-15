package com.shortsmonitor.core.observer

import com.shortsmonitor.core.database.dao.ExposureEventDao
import com.shortsmonitor.core.database.dao.InsertionEventDao
import com.shortsmonitor.core.database.dao.ListSnapshotDao
import com.shortsmonitor.core.database.dao.ObservedShortDao
import com.shortsmonitor.core.database.entity.ExposureEventEntity
import com.shortsmonitor.core.database.entity.InsertionEventEntity
import com.shortsmonitor.core.database.entity.ListSnapshotEntity
import com.shortsmonitor.core.database.entity.ObservedShortEntity
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.model.AutoVerdict
import com.shortsmonitor.core.model.SnapshotChangeReason
import com.shortsmonitor.core.model.UserVerdict
import org.json.JSONArray

/**
 * JavaScript 관찰기 메시지를 세션 기록으로 저장하는 기록기.
 *
 * 세 가지 기록을 구분해 저장한다.
 * - 누적 발견 목록: [ObservedShortEntity] (같은 영상은 영상 식별값 기준으로 중복 저장하지 않음)
 * - 현재 DOM 목록: [ListSnapshotEntity] (현재 WebView 문서에서 관찰 가능한 쇼츠 순서)
 * - 실제 노출 순서: [ExposureEventEntity] (사용자가 실제로 활성화해 본 쇼츠, 재노출 시 새 이벤트)
 *
 * 식별 실패 항목은 [ShortIdentity.temporaryId]로 임시 식별값을 부여해 기록한다.
 */
class ObservationRecorder(
    private val observedShortDao: ObservedShortDao,
    private val exposureEventDao: ExposureEventDao,
    private val listSnapshotDao: ListSnapshotDao,
    private val insertionEventDao: InsertionEventDao,
    private val insertionDetector: InsertionDetector = InsertionDetector(),
    /** 관찰 설정(O단계) 공급자. 메시지를 처리할 때마다 현재 값을 읽어 즉시 반영한다. */
    private val settings: () -> ObservationSettings = { ObservationSettings() },
) {

    /** 스냅샷에 반영할 마지막 활성 영상. 페이지 정보·활성 변경 메시지로 갱신된다. */
    private var lastActiveVideoId: String? = null

    /** 마지막 목록 스냅샷의 영상 식별값 순서. 프로필 변경 기록(L단계)에 사용한다. */
    private var lastVideoIds: List<String> = emptyList()

    /** 관찰기 메시지를 세션 기록으로 저장한다. */
    suspend fun record(sessionId: Long, message: ObserverMessage) {
        when (message) {
            is ObserverMessage.PageInfo -> {
                if (message.activeVideoId.isNotBlank()) {
                    lastActiveVideoId = message.activeVideoId
                }
            }

            is ObserverMessage.ActiveShortChanged -> recordExposure(sessionId, message)
            is ObserverMessage.ListSnapshot -> recordSnapshot(sessionId, message)
            // 준비 완료·DOM 재구성·관찰 오류·하트비트는 별도 저장 대상이 아니다.
            else -> Unit
        }
    }

    private suspend fun recordSnapshot(sessionId: Long, snapshot: ObserverMessage.ListSnapshot) {
        val currentSettings = settings()
        val resolved = snapshot.shorts.map { short -> short to ShortIdentity.resolve(short) }
        resolved.forEachIndexed { index, (short, identity) ->
            val (videoId, status) = identity
            val prevVideoId = resolved.getOrNull(index - 1)?.second?.videoId
            val nextVideoId = resolved.getOrNull(index + 1)?.second?.videoId
            // 메타데이터·썸네일 저장 설정(O단계)을 반영한다.
            val title = if (currentSettings.saveMetadata) short.title.ifBlank { null } else null
            val channelName = if (currentSettings.saveMetadata) short.channel.ifBlank { null } else null
            val thumbnailUrl = if (currentSettings.saveThumbnails) short.thumbnail.ifBlank { null } else null
            if (observedShortDao.getByVideoId(sessionId, videoId) == null) {
                observedShortDao.insert(
                    ObservedShortEntity(
                        sessionId = sessionId,
                        videoId = videoId,
                        videoUrl = short.url.ifBlank { null },
                        title = title,
                        channelName = channelName,
                        thumbnailUrl = thumbnailUrl,
                        identityStatus = status,
                        firstSeenAt = snapshot.ts,
                        lastSeenAt = snapshot.ts,
                        prevVideoId = prevVideoId,
                        nextVideoId = nextVideoId,
                    ),
                )
            } else {
                observedShortDao.updateSeen(
                    sessionId = sessionId,
                    videoId = videoId,
                    lastSeenAt = snapshot.ts,
                    title = title,
                    channelName = channelName,
                    thumbnailUrl = thumbnailUrl,
                    prevVideoId = prevVideoId,
                    nextVideoId = nextVideoId,
                )
            }
        }

        val videoIdsJson = JSONArray()
        resolved.forEach { (_, identity) -> videoIdsJson.put(identity.videoId) }
        lastVideoIds = resolved.map { it.second.videoId }
        // 목록 스냅샷 저장 설정(O단계): 꺼져 있으면 저장하지 않고 탐지에는 null 스냅샷 식별자를 넘긴다.
        val snapshotId: Long? = if (currentSettings.saveListSnapshots) {
            listSnapshotDao.insert(
                ListSnapshotEntity(
                    sessionId = sessionId,
                    createdAt = snapshot.ts,
                    currentUrl = snapshot.url.ifBlank { null },
                    activeVideoId = lastActiveVideoId,
                    videoIdsJson = videoIdsJson.toString(),
                    changeReason = snapshot.reason,
                    domRevision = snapshot.revision.toLong(),
                ),
            )
        } else {
            null
        }
        recordInsertions(
            sessionId,
            resolved.map { it.second.videoId },
            snapshot.reason,
            snapshotId,
            snapshot.ts,
            stabilize = currentSettings.stabilizeCandidates,
        )
        ShortsLog.d("Recorded snapshot: session=$sessionId shorts=${resolved.size} reason=${snapshot.reason}")
    }

    /** 스냅샷을 중간 삽입 탐지 엔진에 전달하고 확정된 의심 이벤트를 저장한다. */
    private suspend fun recordInsertions(
        sessionId: Long,
        videoIds: List<String>,
        reason: SnapshotChangeReason,
        snapshotId: Long?,
        ts: Long,
        stabilize: Boolean = true,
    ) {
        val detected = insertionDetector.process(
            sessionId = sessionId,
            videoIds = videoIds,
            reason = reason,
            snapshotId = snapshotId,
            ts = ts,
            stabilize = stabilize,
        )
        detected.forEach { insertion ->
            insertionEventDao.insert(
                InsertionEventEntity(
                    sessionId = insertion.sessionId,
                    newVideoId = insertion.newVideoId,
                    prevVideoId = insertion.prevVideoId,
                    nextVideoId = insertion.nextVideoId,
                    beforeSnapshotId = insertion.beforeSnapshotId,
                    afterSnapshotId = insertion.afterSnapshotId,
                    detectedAt = insertion.detectedAt,
                    autoVerdict = AutoVerdict.CONFIRMED,
                    userVerdict = UserVerdict.PENDING,
                    evidenceJson = insertion.evidence.toJson(),
                ),
            )
        }
        if (detected.isNotEmpty()) {
            ShortsLog.d("Recorded insertions: session=$sessionId count=${detected.size}")
        }
    }

    /**
     * 브라우저 테스트 프로필 변경(L단계)을 기록한다.
     * 현재 목록을 스냅샷으로 저장하고 PROFILE_CHANGED 사유로 탐지 엔진의
     * 기준 목록을 교체해, 프로필 변경 직전과 직후 목록을 서로 비교하지 않는다.
     */
    suspend fun recordProfileChange(sessionId: Long, ts: Long) {
        val json = JSONArray()
        lastVideoIds.forEach { json.put(it) }
        val snapshotId = listSnapshotDao.insert(
            ListSnapshotEntity(
                sessionId = sessionId,
                createdAt = ts,
                videoIdsJson = json.toString(),
                changeReason = SnapshotChangeReason.PROFILE_CHANGED,
            ),
        )
        // PROFILE_CHANGED는 기준 교체 사유이므로 비교 없이 기준 목록만 바뀐다.
        insertionDetector.process(
            sessionId = sessionId,
            videoIds = lastVideoIds,
            reason = SnapshotChangeReason.PROFILE_CHANGED,
            snapshotId = snapshotId,
            ts = ts,
        )
        ShortsLog.d("Recorded profile change: session=$sessionId")
    }

    /**
     * 세션·사이트 데이터 초기화(M단계)를 기록한다.
     * 현재 목록을 스냅샷으로 저장하고 SESSION_RESET 사유로 탐지 엔진의
     * 기준 목록을 교체해, 초기화 직전과 직후 목록을 서로 비교하지 않는다.
     */
    suspend fun recordReset(sessionId: Long, ts: Long) {
        val json = JSONArray()
        lastVideoIds.forEach { json.put(it) }
        val snapshotId = listSnapshotDao.insert(
            ListSnapshotEntity(
                sessionId = sessionId,
                createdAt = ts,
                videoIdsJson = json.toString(),
                changeReason = SnapshotChangeReason.SESSION_RESET,
            ),
        )
        // SESSION_RESET는 기준 교체 사유이므로 비교 없이 기준 목록만 바뀐다.
        insertionDetector.process(
            sessionId = sessionId,
            videoIds = lastVideoIds,
            reason = SnapshotChangeReason.SESSION_RESET,
            snapshotId = snapshotId,
            ts = ts,
        )
        ShortsLog.d("Recorded session reset: session=$sessionId")
    }

    private suspend fun recordExposure(sessionId: Long, message: ObserverMessage.ActiveShortChanged) {
        val (videoId, _) = ShortIdentity.resolve(message.short)
        // 이전 노출을 종료하고 새 노출 이벤트를 생성한다. 같은 영상이 다시 노출되면 새 이벤트가 된다.
        exposureEventDao.closeOpenExposures(sessionId, message.ts)
        val order = exposureEventDao.countBySession(sessionId) + 1
        exposureEventDao.insert(
            ExposureEventEntity(
                sessionId = sessionId,
                videoId = videoId,
                exposedAt = message.ts,
                exposureOrder = order,
            ),
        )
        observedShortDao.markActivated(sessionId, videoId, message.ts)
        lastActiveVideoId = videoId
        ShortsLog.d("Recorded exposure: session=$sessionId video=$videoId order=$order")
    }
}
