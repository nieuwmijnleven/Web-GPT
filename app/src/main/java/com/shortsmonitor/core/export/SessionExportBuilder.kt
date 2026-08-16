package com.shortsmonitor.core.export

import com.shortsmonitor.core.database.entity.BrowserProfileEntity
import com.shortsmonitor.core.database.entity.ExposureEventEntity
import com.shortsmonitor.core.database.entity.InsertionEventEntity
import com.shortsmonitor.core.database.entity.ListSnapshotEntity
import com.shortsmonitor.core.database.entity.NetworkObserverStateEntity
import com.shortsmonitor.core.database.entity.NetworkSequenceEntity
import com.shortsmonitor.core.database.entity.NetworkSequenceItemEntity
import com.shortsmonitor.core.database.entity.NetworkVideoRequestEntity
import com.shortsmonitor.core.database.entity.ObservedShortEntity
import com.shortsmonitor.core.database.entity.ObservationSessionEntity
import com.shortsmonitor.core.database.entity.SequenceLineageEntity
import com.shortsmonitor.core.model.SnapshotChangeReason
import org.json.JSONArray
import org.json.JSONObject

/**
 * 세션 내보내기 데이터 묶음.
 * JSON 내보내기(N단계)에 포함되는 모든 항목을 담는다.
 */
data class SessionExportData(
    val session: ObservationSessionEntity,
    /** 사용 프로필 (세션에 직접 연결되지 않으므로 내보내기 시점의 활성 프로필을 사용한다). */
    val profile: BrowserProfileEntity? = null,
    val shorts: List<ObservedShortEntity> = emptyList(),
    val exposures: List<ExposureEventEntity> = emptyList(),
    val snapshots: List<ListSnapshotEntity> = emptyList(),
    val events: List<InsertionEventEntity> = emptyList(),
    // ===== v5 네트워크 시퀀스 분석 =====
    val networkSequences: List<NetworkSequenceEntity> = emptyList(),
    val sequenceItems: List<NetworkSequenceItemEntity> = emptyList(),
    val videoRequests: List<NetworkVideoRequestEntity> = emptyList(),
    val lineages: List<SequenceLineageEntity> = emptyList(),
    val observerState: NetworkObserverStateEntity? = null,
)

/**
 * 세션 기록 JSON/CSV 직렬화기 (N단계).
 *
 * JSON에는 세션 정보·사용 프로필·실제 노출 순서·누적 발견 목록·목록 스냅샷·
 * 의심 이벤트·사용자 판정·사용자 메모·프로필/초기화 이력을 포함한다.
 *
 * CSV는 목적별로 세션·쇼츠·노출 이벤트·의심 이벤트 파일로 구분한다.
 *
 * 제외 정보: 쿠키 원문, 인증 토큰, 로그인 자격증명, 전체 HTML, 전체 캐시, 영상 파일.
 * (이 앱은 이 값들을 저장하지 않으므로 내보내기에 포함되지 않는다.)
 */
object SessionExportBuilder {

    /** JSON 내보내기 파일명. */
    fun jsonFileName(sessionId: Long): String = "shorts_monitor_session_$sessionId.json"

    /** 전체 세션 JSON 내보내기 파일명. */
    fun allJsonFileName(): String = "shorts_monitor_all_sessions.json"

    /** CSV 내보내기 파일명 4종. */
    fun csvFileNames(): List<String> = listOf(
        "shorts_monitor_session.csv",
        "shorts_monitor_shorts.csv",
        "shorts_monitor_exposures.csv",
        "shorts_monitor_events.csv",
    )

    /** 한 세션을 JSON 문자열로 직렬화한다. */
    fun buildJson(
        data: SessionExportData,
        appVersion: String,
        exportedAt: Long = System.currentTimeMillis(),
    ): String {
        val root = sessionRoot(data, appVersion, exportedAt)
        return root.toString(2)
    }

    /** 전체 세션을 하나의 JSON으로 직렬화한다 (관찰 홈 '로그 내보내기'). */
    fun buildAllJson(
        sessions: List<SessionExportData>,
        appVersion: String,
        exportedAt: Long = System.currentTimeMillis(),
    ): String {
        val root = JSONObject()
        root.put("app", "shorts monitor")
        root.put("version", appVersion)
        root.put("exportedAt", exportedAt)
        val array = JSONArray()
        sessions.forEach { data -> array.put(sessionRoot(data, appVersion, exportedAt)) }
        root.put("sessions", array)
        return root.toString(2)
    }

    private fun sessionRoot(
        data: SessionExportData,
        appVersion: String,
        exportedAt: Long,
    ): JSONObject {
        val root = JSONObject()
        root.put("app", "shorts monitor")
        root.put("version", appVersion)
        root.put("exportedAt", exportedAt)
        root.put("session", sessionJson(data.session))
        data.profile?.let { root.put("profile", profileJson(it)) }
        root.put("shorts", JSONArray().apply {
            data.shorts.forEach { put(shortJson(it)) }
        })
        root.put("exposures", JSONArray().apply {
            data.exposures.forEach { put(exposureJson(it)) }
        })
        root.put("snapshots", JSONArray().apply {
            data.snapshots.forEach { put(snapshotJson(it)) }
        })
        root.put("events", JSONArray().apply {
            data.events.forEach { put(eventJson(it)) }
        })
        // v5: 네트워크 시퀀스 분석 데이터 (민감 원문 없음).
        root.put("networkSequences", JSONArray().apply {
            data.networkSequences.forEach { put(networkSequenceJson(it)) }
        })
        root.put("sequenceItems", JSONArray().apply {
            data.sequenceItems.forEach { put(sequenceItemJson(it)) }
        })
        root.put("videoRequests", JSONArray().apply {
            data.videoRequests.forEach { put(videoRequestJson(it)) }
        })
        root.put("lineages", JSONArray().apply {
            data.lineages.forEach { put(lineageJson(it)) }
        })
        data.observerState?.let { root.put("observerState", observerStateJson(it)) }
        root.put("history", historyJson(data.snapshots))
        return root
    }

    /** 한 세션을 목적별 CSV 4종으로 직렬화한다. */
    fun buildCsvFiles(data: SessionExportData): List<CsvFile> = buildCsvFilesAll(listOf(data))

    /** 여러 세션을 목적별 CSV 4종으로 직렬화한다 (O단계 데이터 설정 'CSV 내보내기'). */
    fun buildCsvFilesAll(all: List<SessionExportData>): List<CsvFile> = listOf(
        CsvFile(csvFileNames()[0], sessionCsv(all)),
        CsvFile(csvFileNames()[1], shortsCsv(all)),
        CsvFile(csvFileNames()[2], exposuresCsv(all)),
        CsvFile(csvFileNames()[3], eventsCsv(all)),
    )

    /** CSV 파일 (파일명 + 내용). */
    data class CsvFile(
        val fileName: String,
        val content: String,
    )

    // --- JSON 항목 ---

    private fun sessionJson(session: ObservationSessionEntity): JSONObject = JSONObject()
        .put("id", session.id)
        .put("sessionId", session.sessionId)
        .put("name", session.name)
        .put("status", session.status.name)
        .put("startedAt", session.startedAt)
        .putOpt("endedAt", session.endedAt)
        .putOpt("startUrl", session.startUrl)
        .putOpt("endReason", session.endReason?.name)
        .putOpt("appVersion", session.appVersion)
        .putOpt("webViewInfo", session.webViewInfo)

    private fun profileJson(profile: BrowserProfileEntity): JSONObject = JSONObject()
        .put("id", profile.id)
        .put("name", profile.name)
        .put("templateType", profile.templateType.name)
        .put("userAgent", profile.userAgent)
        .put("language", profile.language)
        .put("timezone", profile.timezone)
        .putOpt("screenOverride", profile.screenOverride)
        .putOpt("hardwareOverride", profile.hardwareOverride)
        .putOpt("touchOverride", profile.touchOverride)
        .put("createdAt", profile.createdAt)
        .putOpt("lastUsedAt", profile.lastUsedAt)

    private fun shortJson(short: ObservedShortEntity): JSONObject = JSONObject()
        .put("id", short.id)
        .put("videoId", short.videoId)
        .putOpt("videoUrl", short.videoUrl)
        .putOpt("title", short.title)
        .putOpt("channelName", short.channelName)
        .putOpt("thumbnailUrl", short.thumbnailUrl)
        .put("identityStatus", short.identityStatus.name)
        .put("firstSeenAt", short.firstSeenAt)
        .put("lastSeenAt", short.lastSeenAt)
        .putOpt("activatedAt", short.activatedAt)
        .putOpt("prevVideoId", short.prevVideoId)
        .putOpt("nextVideoId", short.nextVideoId)

    private fun exposureJson(exposure: ExposureEventEntity): JSONObject = JSONObject()
        .put("id", exposure.id)
        .put("videoId", exposure.videoId)
        .put("exposedAt", exposure.exposedAt)
        .putOpt("exposedUntil", exposure.exposedUntil)
        .put("exposureOrder", exposure.exposureOrder)

    private fun snapshotJson(snapshot: ListSnapshotEntity): JSONObject {
        val videoIds = runCatching {
            val array = JSONArray(snapshot.videoIdsJson)
            JSONArray().apply { for (i in 0 until array.length()) put(array.getString(i)) }
        }.getOrDefault(JSONArray())
        return JSONObject()
            .put("id", snapshot.id)
            .put("createdAt", snapshot.createdAt)
            .putOpt("currentUrl", snapshot.currentUrl)
            .putOpt("activeVideoId", snapshot.activeVideoId)
            .put("videoIds", videoIds)
            .put("changeReason", snapshot.changeReason.name)
            .put("domRevision", snapshot.domRevision)
    }

    private fun eventJson(event: InsertionEventEntity): JSONObject = JSONObject()
        .put("id", event.id)
        .put("newVideoId", event.newVideoId)
        .putOpt("prevVideoId", event.prevVideoId)
        .putOpt("nextVideoId", event.nextVideoId)
        .putOpt("beforeSnapshotId", event.beforeSnapshotId)
        .putOpt("afterSnapshotId", event.afterSnapshotId)
        .put("detectedAt", event.detectedAt)
        .put("autoVerdict", event.autoVerdict.name)
        .put("userVerdict", event.userVerdict.name)
        .putOpt("userMemo", event.userMemo)
        .putOpt("evidence", event.evidenceJson?.let { runCatching { JSONObject(it) }.getOrNull() })
        // v5: 근거 출처·네트워크 시퀀스·강화 증거.
        .put("source", event.source.name)
        .putOpt("networkBeforeSequenceId", event.networkBeforeSequenceId)
        .putOpt("networkAfterSequenceId", event.networkAfterSequenceId)
        .putOpt("strengthenedBy", event.strengthenedByJson?.let { runCatching { JSONArray(it) }.getOrNull() })

    /** 네트워크 시퀀스 JSON. 민감 원문(continuation·추적 파라미터)은 해시만 포함한다. */
    private fun networkSequenceJson(sequence: NetworkSequenceEntity): JSONObject = JSONObject()
        .put("id", sequence.id)
        .putOpt("correlationId", sequence.correlationId)
        .put("createdAt", sequence.createdAt)
        .putOpt("pageUrl", sequence.pageUrl)
        .putOpt("currentVideoId", sequence.currentVideoId)
        .put("entryContext", sequence.entryContext.name)
        .putOpt("sequenceHash", sequence.sequenceHash)
        .putOpt("continuationHash", sequence.continuationHash)
        .putOpt("parserVersion", sequence.parserVersion)
        .put("parseStatus", sequence.parseStatus.name)
        .putOpt("warnings", sequence.warningsJson?.let { runCatching { JSONArray(it) }.getOrNull() })
        .putOpt("lineageId", sequence.lineageId)

    private fun sequenceItemJson(item: NetworkSequenceItemEntity): JSONObject = JSONObject()
        .put("sequenceId", item.sequenceId)
        .put("position", item.position)
        .putOpt("videoId", item.videoId)
        .put("entryKind", item.entryKind.name)
        .putOpt("nonVideoKind", item.nonVideoKind)
        .put("isCurrent", item.isCurrent)
        .put("hasPlayerParams", item.hasPlayerParams)
        .put("hasContinuation", item.hasContinuation)
        .putOpt("trackingHash", item.trackingHash)
        .putOpt("playerParamsHash", item.playerParamsHash)

    private fun videoRequestJson(request: NetworkVideoRequestEntity): JSONObject = JSONObject()
        .put("id", request.id)
        .putOpt("videoId", request.videoId)
        .put("requestKind", request.requestKind.name)
        .put("requestedAt", request.requestedAt)
        .putOpt("pageUrl", request.pageUrl)
        .putOpt("sequenceId", request.sequenceId)
        .putOpt("expectedPosition", request.expectedPosition)
        .put("requestOrder", request.requestOrder)

    private fun lineageJson(lineage: SequenceLineageEntity): JSONObject = JSONObject()
        .put("id", lineage.id)
        .put("fromSequenceId", lineage.fromSequenceId)
        .put("toSequenceId", lineage.toSequenceId)
        .put("relation", lineage.relation.name)
        .putOpt("signals", lineage.signalsJson?.let { runCatching { JSONObject(it) }.getOrNull() })
        .put("decidedAt", lineage.decidedAt)

    private fun observerStateJson(state: NetworkObserverStateEntity): JSONObject = JSONObject()
        .putOpt("installedAt", state.installedAt)
        .put("documentStartSupported", state.documentStartSupported)
        .put("missedInitialPossible", state.missedInitialPossible)
        .put("restricted", state.restricted)
        .putOpt("firstRequestAt", state.firstRequestAt)
        .putOpt("lastSequenceRequestAt", state.lastSequenceRequestAt)
        .putOpt("lastSequenceResponseAt", state.lastSequenceResponseAt)
        .put("lastSequenceVideoCount", state.lastSequenceVideoCount)
        .put("lastParseStatus", state.lastParseStatus.name)
        .putOpt("currentLineage", state.currentLineage)
        .putOpt("warnings", state.warningsJson?.let { runCatching { JSONArray(it) }.getOrNull() })

    /** 프로필 변경·초기화 이력. 스냅샷의 변경 사유에서 파생한다. */
    private fun historyJson(snapshots: List<ListSnapshotEntity>): JSONObject {
        val profileChanges = JSONArray()
        val resets = JSONArray()
        snapshots.forEach { snapshot ->
            when (snapshot.changeReason) {
                SnapshotChangeReason.PROFILE_CHANGED ->
                    profileChanges.put(JSONObject().put("at", snapshot.createdAt))
                SnapshotChangeReason.SESSION_RESET ->
                    resets.put(JSONObject().put("at", snapshot.createdAt))
                else -> Unit
            }
        }
        return JSONObject()
            .put("profileChanges", profileChanges)
            .put("resets", resets)
    }

    // --- CSV ---

    private fun sessionCsv(all: List<SessionExportData>): String {
        val sb = StringBuilder()
        sb.appendLine("session_id,name,status,started_at,ended_at,start_url,end_reason,app_version,webview_info")
        all.forEach { data ->
            val s = data.session
            sb.appendLine(
                listOf(
                    s.sessionId, s.name, s.status.name, s.startedAt.toString(),
                    s.endedAt?.toString().orEmpty(), s.startUrl.orEmpty(),
                    s.endReason?.name.orEmpty(), s.appVersion.orEmpty(), s.webViewInfo.orEmpty(),
                ).joinToString(",") { csvEscape(it) },
            )
        }
        return sb.toString()
    }

    private fun shortsCsv(all: List<SessionExportData>): String {
        val sb = StringBuilder()
        sb.appendLine("session_id,video_id,video_url,title,channel_name,thumbnail_url,identity_status,first_seen_at,last_seen_at,activated_at,prev_video_id,next_video_id")
        all.forEach { data ->
            data.shorts.forEach { short ->
                sb.appendLine(
                    listOf(
                        data.session.sessionId, short.videoId, short.videoUrl.orEmpty(),
                        short.title.orEmpty(), short.channelName.orEmpty(), short.thumbnailUrl.orEmpty(),
                        short.identityStatus.name, short.firstSeenAt.toString(), short.lastSeenAt.toString(),
                        short.activatedAt?.toString().orEmpty(), short.prevVideoId.orEmpty(), short.nextVideoId.orEmpty(),
                    ).joinToString(",") { csvEscape(it) },
                )
            }
        }
        return sb.toString()
    }

    private fun exposuresCsv(all: List<SessionExportData>): String {
        val sb = StringBuilder()
        sb.appendLine("session_id,video_id,exposed_at,exposed_until,exposure_order")
        all.forEach { data ->
            data.exposures.forEach { exposure ->
                sb.appendLine(
                    listOf(
                        data.session.sessionId, exposure.videoId, exposure.exposedAt.toString(),
                        exposure.exposedUntil?.toString().orEmpty(), exposure.exposureOrder.toString(),
                    ).joinToString(",") { csvEscape(it) },
                )
            }
        }
        return sb.toString()
    }

    private fun eventsCsv(all: List<SessionExportData>): String {
        val sb = StringBuilder()
        sb.appendLine("session_id,new_video_id,prev_video_id,next_video_id,detected_at,auto_verdict,user_verdict,user_memo")
        all.forEach { data ->
            data.events.forEach { event ->
                sb.appendLine(
                    listOf(
                        data.session.sessionId, event.newVideoId, event.prevVideoId.orEmpty(),
                        event.nextVideoId.orEmpty(), event.detectedAt.toString(),
                        event.autoVerdict.name, event.userVerdict.name, event.userMemo.orEmpty(),
                    ).joinToString(",") { csvEscape(it) },
                )
            }
        }
        return sb.toString()
    }

    /** CSV 필드 이스케이프: 쉼표·따옴표·줄바꿈이 있으면 따옴표로 감싼다. */
    internal fun csvEscape(value: String): String {
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            return "\"" + value.replace("\"", "\"\"") + "\""
        }
        return value
    }

    /** org.json의 putOpt 동작: null이면 키를 생략한다. */
    private fun JSONObject.putOpt(key: String, value: Any?): JSONObject {
        if (value != null) put(key, value)
        return this
    }
}
