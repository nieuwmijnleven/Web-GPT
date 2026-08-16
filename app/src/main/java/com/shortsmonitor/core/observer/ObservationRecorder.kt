package com.shortsmonitor.core.observer

import com.shortsmonitor.core.database.dao.ExposureEventDao
import com.shortsmonitor.core.database.dao.InsertionEventDao
import com.shortsmonitor.core.database.dao.ListSnapshotDao
import com.shortsmonitor.core.database.dao.NetworkObserverStateDao
import com.shortsmonitor.core.database.dao.NetworkSequenceDao
import com.shortsmonitor.core.database.dao.NetworkSequenceItemDao
import com.shortsmonitor.core.database.dao.NetworkVideoRequestDao
import com.shortsmonitor.core.database.dao.ObservedShortDao
import com.shortsmonitor.core.database.dao.SequenceLineageDao
import com.shortsmonitor.core.database.entity.ExposureEventEntity
import com.shortsmonitor.core.database.entity.InsertionEventEntity
import com.shortsmonitor.core.database.entity.ListSnapshotEntity
import com.shortsmonitor.core.database.entity.NetworkObserverStateEntity
import com.shortsmonitor.core.database.entity.NetworkSequenceEntity
import com.shortsmonitor.core.database.entity.NetworkSequenceItemEntity
import com.shortsmonitor.core.database.entity.NetworkVideoRequestEntity
import com.shortsmonitor.core.database.entity.ObservedShortEntity
import com.shortsmonitor.core.database.entity.SequenceLineageEntity
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.model.AutoVerdict
import com.shortsmonitor.core.model.EntryContext
import com.shortsmonitor.core.model.InsertionEvidence
import com.shortsmonitor.core.model.InsertionSource
import com.shortsmonitor.core.model.SequenceLineageRelation
import com.shortsmonitor.core.model.SequenceParseStatus
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
 * v5 네트워크 시퀀스 분석:
 * - 네트워크 시퀀스([NetworkSequenceEntity]/[NetworkSequenceItemEntity])는 DOM 목록과 별도로 저장한다.
 * - 시퀀스 계보([SequenceLineageDetector])를 판정하고, 중간 삽입 판정은 네트워크 시퀀스 비교
 *   ([NetworkInsertionDetector])를 기준으로 수행한다. DOM 정보는 노출·메타데이터·강화 증거로만 사용한다.
 * - 네트워크 데이터가 없거나 계보가 불명확하면 확정하지 않고 CANDIDATE/UNKNOWN(보류)로 저장한다.
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
    /** v5 네트워크 시퀀스 DAO. null이면 네트워크 기록을 저장하지 않는다(레거시 DOM 경로). */
    private val networkSequenceDao: NetworkSequenceDao? = null,
    private val networkSequenceItemDao: NetworkSequenceItemDao? = null,
    private val networkVideoRequestDao: NetworkVideoRequestDao? = null,
    private val sequenceLineageDao: SequenceLineageDao? = null,
    private val networkObserverStateDao: NetworkObserverStateDao? = null,
) {

    /** 스냅샷에 반영할 마지막 활성 영상. 페이지 정보·활성 변경 메시지로 갱신된다. */
    private var lastActiveVideoId: String? = null

    /** 마지막 목록 스냅샷의 영상 식별값 순서. 프로필 변경 기록(L단계)에 사용한다. */
    private var lastVideoIds: List<String> = emptyList()

    /** 마지막 DOM 목록 스냅샷 시각. 네이티브 시간 기반 안정화의 최소 대기 시간 계산에 사용한다. */
    private var lastDomSnapshotAt: Long = 0L

    // ===== 네트워크 시퀀스 상태 =====

    private val networkDetector = NetworkInsertionDetector()

    private var lastSequenceParamsHash: String? = null
    private var lastSequenceId: Long? = null
    private var lastSequenceVideoPositions: Map<String, Int> = emptyMap()
    private var lastObserverState: NetworkObserverStateEntity? = null

    /** 이전 시퀀스 요약 (계보 판정 입력). */
    private var prevSequence: SequenceLineageDetector.LineageInput? = null
    private var prevSequenceId: Long? = null

    /** 프로필 변경·세션 초기화·새로고침 직후 표시. 다음 시퀀스의 계보 판정에 전달된다. */
    private var contextResetPending: Boolean = false

    /** 관찰기 메시지를 세션 기록으로 저장한다. */
    suspend fun record(sessionId: Long, message: ObserverMessage) {
        when (message) {
            is ObserverMessage.PageInfo -> {
                if (message.activeVideoId.isNotBlank()) {
                    lastActiveVideoId = message.activeVideoId
                }
            }

            is ObserverMessage.ActiveShortChanged -> {
                recordExposure(sessionId, message)
                // DOM에서 실제 활성 영상으로 관찰: 네트워크 후보 강화 증거로 사용한다.
                val (videoId, _) = ShortIdentity.resolve(message.short)
                handleNetworkOutcomes(sessionId, networkDetector.strengthenByActiveExposure(videoId))
            }

            is ObserverMessage.ListSnapshot -> recordSnapshot(sessionId, message)
            is ObserverMessage.NetworkObserverReady -> recordNetworkObserverReady(sessionId, message)
            is ObserverMessage.NetworkSequenceRequest -> recordNetworkSequenceRequest(sessionId, message)
            is ObserverMessage.NetworkSequenceResponse -> recordNetworkSequenceResponse(sessionId, message)
            is ObserverMessage.NetworkVideoRequest -> recordNetworkVideoRequest(sessionId, message)
            is ObserverMessage.NetworkParseWarning -> recordNetworkWarning(sessionId, message)
            is ObserverMessage.NetworkObserverStatus -> recordNetworkObserverStatus(sessionId, message)
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
        lastDomSnapshotAt = snapshot.ts
        // 전체 새로고침·탐색 컨텍스트 변경은 네트워크 시퀀스 계보의 기준 교체 신호다.
        if (snapshot.reason == SnapshotChangeReason.FULL_RELOAD ||
            snapshot.reason == SnapshotChangeReason.NAVIGATION
        ) {
            contextResetPending = true
        }
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

    /** DOM 스냅샷을 중간 삽입 탐지 엔진에 전달하고 확정된 의심 이벤트를 저장한다. */
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
     * 네이티브 시간 기반 DOM 안정화 확인.
     *
     * 기존 문제: 신규 항목이 발견된 스냅샷에서 후보를 등록하지만, 이후 목록 키가 같으면
     * JavaScript가 스냅샷을 다시 보내지 않아 안정화 확인이 실행되지 않을 수 있다.
     * 네이티브 계층이 주기적으로 이 메서드를 호출해, 후보가 일정 시간 이상 유지되면
     * 마지막 목록으로 안정화를 재확인한다. 후보가 없거나 최소 대기 시간이 지나지 않았으면
     * 아무것도 하지 않아 불필요한 저장·메시지를 피한다.
     */
    suspend fun stabilizeDomCandidates(sessionId: Long, now: Long = System.currentTimeMillis()) {
        val lastCandidateAt = insertionDetector.lastPendingCandidateAt ?: return
        if (now - lastCandidateAt < MIN_DOM_STABILIZE_AGE_MS) return
        val detected = insertionDetector.stabilizePending(sessionId, now)
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
            ShortsLog.d("Stabilized DOM candidates: session=$sessionId count=${detected.size}")
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
        contextResetPending = true
        networkDetector.resetBaseline()
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
        contextResetPending = true
        networkDetector.resetBaseline()
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

    // ============================================================
    // 네트워크 시퀀스 기록
    // ============================================================

    private suspend fun recordNetworkObserverReady(sessionId: Long, message: ObserverMessage.NetworkObserverReady) {
        val state = lastObserverState ?: NetworkObserverStateEntity(
            sessionId = sessionId,
            installedAt = message.installedAt,
            documentStartSupported = ObserverDiagnostics.documentStartSupported,
            missedInitialPossible = false,
            restricted = false,
        )
        lastObserverState = state.copy(
            installedAt = message.installedAt,
            documentStartSupported = ObserverDiagnostics.documentStartSupported,
        )
        upsertObserverState(sessionId)
    }

    private suspend fun recordNetworkSequenceRequest(sessionId: Long, message: ObserverMessage.NetworkSequenceRequest) {
        // 요청의 sequenceParams 해시를 보관해 응답 시 계보 판정에 사용한다.
        lastSequenceParamsHash = message.sequenceParamsHash.ifBlank { null }
        lastObserverState = (lastObserverState ?: freshState(sessionId)).copy(
            firstRequestAt = message.ts,
            lastSequenceRequestAt = message.ts,
        )
        upsertObserverState(sessionId)
    }

    private suspend fun recordNetworkSequenceResponse(sessionId: Long, message: ObserverMessage.NetworkSequenceResponse) {
        val seqDao = networkSequenceDao ?: return
        val itemDao = networkSequenceItemDao ?: return

        val entryContext = ShortsUrlClassifier.classify(message.pageUrl)
        val videoIds = message.items
            .filter { it.entryKind == com.shortsmonitor.core.model.SequenceEntryKind.VIDEO && it.videoId.isNotBlank() }
            .map { it.videoId }
        val currentVideoId = message.items.firstOrNull {
            it.entryKind == com.shortsmonitor.core.model.SequenceEntryKind.VIDEO && it.videoId.isNotBlank()
        }?.videoId ?: message.currentVideoId.ifBlank { null }

        val sequenceId = seqDao.insert(
            NetworkSequenceEntity(
                sessionId = sessionId,
                correlationId = message.correlationId.ifBlank { null },
                createdAt = message.ts,
                pageUrl = message.pageUrl.ifBlank { null },
                currentVideoId = currentVideoId,
                entryContext = entryContext,
                sequenceHash = message.sequenceHash.ifBlank { null },
                continuationHash = message.continuationHash.ifBlank { null },
                parserVersion = message.parserVersion.ifBlank { null },
                parseStatus = message.parseStatus,
                warningsJson = stringArrayJson(message.warnings),
                lineageId = null,
            ),
        )
        message.items.forEach { item ->
            itemDao.insert(
                NetworkSequenceItemEntity(
                    sequenceId = sequenceId,
                    position = item.position,
                    videoId = item.videoId.ifBlank { null },
                    entryKind = item.entryKind,
                    nonVideoKind = item.nonVideoKind.ifBlank { null },
                    isCurrent = item.isCurrent,
                    hasPlayerParams = item.hasPlayerParams,
                    hasContinuation = item.hasContinuation,
                    trackingHash = item.trackingHash.ifBlank { null },
                    playerParamsHash = item.playerParamsHash.ifBlank { null },
                    continuationHash = null,
                ),
            )
        }
        lastSequenceId = sequenceId
        lastSequenceVideoPositions = videoIds.withIndex().associate { it.value to it.index }

        // 계보 판정
        val nextInput = SequenceLineageDetector.LineageInput(
            currentVideoId = currentVideoId,
            videoIds = videoIds,
            sequenceParamsHash = lastSequenceParamsHash,
            continuationHash = message.continuationHash.ifBlank { null },
            entryContext = entryContext,
        )
        val result = SequenceLineageDetector.decide(prevSequence, nextInput, afterReset = contextResetPending)
        contextResetPending = false
        ObserverDiagnostics.updateLineage(result.relation)

        val prevId = prevSequenceId
        prevSequence = nextInput
        prevSequenceId = sequenceId

        if (result.relation != SequenceLineageRelation.NONE && prevId != null && sequenceLineageDao != null) {
            val lineageId = sequenceLineageDao.insert(
                SequenceLineageEntity(
                    sessionId = sessionId,
                    fromSequenceId = prevId,
                    toSequenceId = sequenceId,
                    relation = result.relation,
                    signalsJson = result.signalsJson(),
                    decidedAt = message.ts,
                ),
            )
            seqDao.updateLineageId(sequenceId, lineageId)
        }

        // 네트워크 시퀀스 기준 중간 삽입 판정
        handleNetworkOutcomes(sessionId, networkDetector.processSequence(sequenceId, videoIds, result.relation, message.ts))

        lastObserverState = (lastObserverState ?: freshState(sessionId)).copy(
            firstRequestAt = lastObserverState?.firstRequestAt ?: message.ts,
            lastSequenceResponseAt = message.ts,
            lastSequenceVideoCount = message.items.size,
            lastParseStatus = message.parseStatus,
            currentLineage = result.relation.name,
            warningsJson = mergeWarnings(lastObserverState?.warningsJson, message.warnings),
        )
        upsertObserverState(sessionId)
        ShortsLog.d("Recorded network sequence: session=$sessionId videos=${videoIds.size} status=${message.parseStatus} lineage=${result.relation}")
    }

    private suspend fun recordNetworkVideoRequest(sessionId: Long, message: ObserverMessage.NetworkVideoRequest) {
        val dao = networkVideoRequestDao ?: return
        val order = dao.countBySession(sessionId) + 1
        val expectedPosition = message.videoId?.let { lastSequenceVideoPositions[it] }
        dao.insert(
            NetworkVideoRequestEntity(
                sessionId = sessionId,
                videoId = message.videoId.ifBlank { null },
                requestKind = message.requestKind,
                requestedAt = message.ts,
                pageUrl = message.pageUrl.ifBlank { null },
                sequenceId = lastSequenceId,
                expectedPosition = expectedPosition,
                requestOrder = order,
            ),
        )
        // 해당 영상의 실제 요청: 네트워크 후보 강화 증거.
        handleNetworkOutcomes(sessionId, networkDetector.strengthenByVideoRequest(message.videoId, message.requestKind))
    }

    private suspend fun recordNetworkWarning(sessionId: Long, message: ObserverMessage.NetworkParseWarning) {
        lastObserverState = (lastObserverState ?: freshState(sessionId)).copy(
            warningsJson = mergeWarnings(lastObserverState?.warningsJson, listOf(message.code)),
        )
        upsertObserverState(sessionId)
    }

    private suspend fun recordNetworkObserverStatus(sessionId: Long, message: ObserverMessage.NetworkObserverStatus) {
        lastObserverState = (lastObserverState ?: freshState(sessionId)).copy(
            firstRequestAt = if (message.firstSequenceRequestAt > 0L) message.firstSequenceRequestAt else lastObserverState?.firstRequestAt,
            lastSequenceRequestAt = if (message.lastSequenceRequestAt > 0L) message.lastSequenceRequestAt else lastObserverState?.lastSequenceRequestAt,
            lastSequenceResponseAt = if (message.lastSequenceResponseAt > 0L) message.lastSequenceResponseAt else lastObserverState?.lastSequenceResponseAt,
            lastSequenceVideoCount = message.lastSequenceVideoCount,
            lastParseStatus = message.lastSequenceParseStatus,
            missedInitialPossible = message.missedInitialPossible,
        )
        upsertObserverState(sessionId)
    }

    /** 네트워크 탐지 결과(후보 등록·확정·무효화·보류)를 DB 행으로 반영한다. */
    private suspend fun handleNetworkOutcomes(sessionId: Long, outcomes: List<NetworkInsertionDetector.Outcome>) {
        outcomes.forEach { outcome ->
            when (outcome) {
                is NetworkInsertionDetector.Outcome.Registered -> {
                    insertionEventDao.insert(
                        InsertionEventEntity(
                            sessionId = sessionId,
                            newVideoId = outcome.key.newVideoId,
                            prevVideoId = outcome.key.prevVideoId,
                            nextVideoId = outcome.key.nextVideoId,
                            detectedAt = outcome.detectedAt,
                            autoVerdict = AutoVerdict.CANDIDATE,
                            userVerdict = UserVerdict.PENDING,
                            evidenceJson = InsertionEvidence.networkCandidate().toJson(),
                            source = InsertionSource.NETWORK,
                            networkBeforeSequenceId = outcome.beforeSequenceId,
                            networkAfterSequenceId = outcome.afterSequenceId,
                        ),
                    )
                }

                is NetworkInsertionDetector.Outcome.Confirmed -> {
                    updateNetworkEventRow(
                        sessionId,
                        outcome.key.newVideoId,
                        AutoVerdict.CONFIRMED,
                        outcome.evidence,
                        outcome.afterSequenceId,
                    )
                }

                is NetworkInsertionDetector.Outcome.Invalidated -> {
                    updateNetworkEventRow(
                        sessionId,
                        outcome.key.newVideoId,
                        AutoVerdict.INVALIDATED,
                        null,
                        null,
                        reason = outcome.reason,
                    )
                }

                is NetworkInsertionDetector.Outcome.Unknown -> {
                    val existing = insertionEventDao.findNetworkCandidate(sessionId, outcome.key.newVideoId)
                    if (existing == null) {
                        insertionEventDao.insert(
                            InsertionEventEntity(
                                sessionId = sessionId,
                                newVideoId = outcome.key.newVideoId,
                                prevVideoId = outcome.key.prevVideoId,
                                nextVideoId = outcome.key.nextVideoId,
                                detectedAt = outcome.detectedAt,
                                autoVerdict = AutoVerdict.UNKNOWN,
                                userVerdict = UserVerdict.PENDING,
                                evidenceJson = InsertionEvidence.networkCandidate().toJson(),
                                source = InsertionSource.NETWORK,
                                networkBeforeSequenceId = outcome.beforeSequenceId,
                                networkAfterSequenceId = outcome.afterSequenceId,
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun updateNetworkEventRow(
        sessionId: Long,
        newVideoId: String,
        verdict: AutoVerdict,
        evidence: InsertionEvidence?,
        afterSequenceId: Long?,
        reason: String? = null,
    ) {
        val row = insertionEventDao.findNetworkCandidate(sessionId, newVideoId) ?: return
        val evidenceJson = evidence?.toJson()
        insertionEventDao.updateNetworkOutcome(
            id = row.id,
            autoVerdict = verdict,
            evidenceJson = evidenceJson,
            beforeSequenceId = row.networkBeforeSequenceId,
            afterSequenceId = afterSequenceId ?: row.networkAfterSequenceId,
            strengthenedByJson = evidence?.strengthenedJson(),
        )
    }

    private suspend fun upsertObserverState(sessionId: Long) {
        val dao = networkObserverStateDao ?: return
        val state = lastObserverState ?: freshState(sessionId)
        val missed = state.missedInitialPossible ||
            !ObserverDiagnostics.documentStartSupported ||
            (
                state.installedAt != null && state.firstRequestAt != null &&
                    state.firstRequestAt - state.installedAt > MISSED_INITIAL_THRESHOLD_MS
                )
        lastObserverState = state.copy(
            missedInitialPossible = missed,
            restricted = missed || state.lastSequenceVideoCount == 0,
        )
        dao.upsert(lastObserverState!!)
    }

    private fun freshState(sessionId: Long): NetworkObserverStateEntity = NetworkObserverStateEntity(
        sessionId = sessionId,
        documentStartSupported = ObserverDiagnostics.documentStartSupported,
        missedInitialPossible = false,
        restricted = false,
    )

    private fun stringArrayJson(values: List<String>): String? {
        if (values.isEmpty()) return null
        val array = JSONArray()
        values.forEach { array.put(it) }
        return array.toString()
    }

    private fun mergeWarnings(existing: String?, newWarnings: List<String>): String? {
        val list = mutableListOf<String>()
        runCatching {
            if (!existing.isNullOrBlank()) {
                val array = JSONArray(existing)
                for (i in 0 until array.length()) list.add(array.getString(i))
            }
        }
        newWarnings.forEach { if (it.isNotBlank() && list.size < MAX_STORED_WARNINGS) list.add(it) }
        if (list.isEmpty()) return null
        val array = JSONArray()
        list.forEach { array.put(it) }
        return array.toString()
    }

    companion object {
        /** DOM 후보 안정화 확인 최소 대기 시간. 이보다 빨리 확정하지 않는다. */
        const val MIN_DOM_STABILIZE_AGE_MS = 3_000L

        /** 설치 시각과 첫 요청 시각의 차이가 이 값보다 크면 초기 요청을 놓쳤을 가능성이 있다. */
        const val MISSED_INITIAL_THRESHOLD_MS = 2_000L

        /** 관찰기 상태에 보관하는 경고 최대 개수. */
        const val MAX_STORED_WARNINGS = 50
    }
}
