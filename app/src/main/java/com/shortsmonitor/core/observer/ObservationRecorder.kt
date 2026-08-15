package com.shortsmonitor.core.observer

import com.shortsmonitor.core.database.dao.ExposureEventDao
import com.shortsmonitor.core.database.dao.ListSnapshotDao
import com.shortsmonitor.core.database.dao.ObservedShortDao
import com.shortsmonitor.core.database.entity.ExposureEventEntity
import com.shortsmonitor.core.database.entity.ListSnapshotEntity
import com.shortsmonitor.core.database.entity.ObservedShortEntity
import com.shortsmonitor.core.logging.ShortsLog
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
) {

    /** 스냅샷에 반영할 마지막 활성 영상. 페이지 정보·활성 변경 메시지로 갱신된다. */
    private var lastActiveVideoId: String? = null

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
        val resolved = snapshot.shorts.map { short -> short to ShortIdentity.resolve(short) }
        resolved.forEachIndexed { index, (short, identity) ->
            val (videoId, status) = identity
            val prevVideoId = resolved.getOrNull(index - 1)?.second?.videoId
            val nextVideoId = resolved.getOrNull(index + 1)?.second?.videoId
            if (observedShortDao.getByVideoId(sessionId, videoId) == null) {
                observedShortDao.insert(
                    ObservedShortEntity(
                        sessionId = sessionId,
                        videoId = videoId,
                        videoUrl = short.url.ifBlank { null },
                        title = short.title.ifBlank { null },
                        channelName = short.channel.ifBlank { null },
                        thumbnailUrl = short.thumbnail.ifBlank { null },
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
                    title = short.title.ifBlank { null },
                    channelName = short.channel.ifBlank { null },
                    thumbnailUrl = short.thumbnail.ifBlank { null },
                    prevVideoId = prevVideoId,
                    nextVideoId = nextVideoId,
                )
            }
        }

        val videoIdsJson = JSONArray()
        resolved.forEach { (_, identity) -> videoIdsJson.put(identity.videoId) }
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
        ShortsLog.d("Recorded snapshot: session=$sessionId shorts=${resolved.size} reason=${snapshot.reason}")
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
