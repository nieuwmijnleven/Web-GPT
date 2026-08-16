package com.shortsmonitor.core.export

import com.shortsmonitor.core.database.AppDatabase
import com.shortsmonitor.core.model.InsertionEvidence
import org.json.JSONArray
import org.json.JSONObject

/**
 * 실제 검증용 네트워크 진단 모드 (개발 빌드 전용).
 *
 * WebView 진단 화면에서 호출하며, 관찰된 네트워크 요청·시퀀스·계보·활성 영상·파싱 경고·
 * 삽입 후보 상태를 민감정보 없이 JSON 파일로 내보낸다.
 *
 * 내보내지 않는 정보: 쿠키, 인증 헤더, 요청 본문 원문, continuation 원문, 방문자 식별값,
 * 추적 파라미터 원문. 저장된 데이터 자체가 해시·존재 여부·식별값뿐이므로 파일에도 원문이 없다.
 */
object NetworkDiagnosticsExporter {

    /** 내보내기 파일 이름. */
    const val FILE_NAME = "shorts_monitor_network_diagnostics.json"

    /**
     * [sessionId] 세션의 네트워크 진단 데이터를 JSON 문자열로 만든다.
     * DB에 저장된 안전한 값(해시·식별값·존재 여부)만 사용한다.
     */
    suspend fun export(database: AppDatabase, sessionId: Long): String {
        val root = JSONObject()
        root.put("app", "shorts monitor")
        root.put("format", "network-diagnostics")
        root.put("exportedAt", System.currentTimeMillis())

        root.put("observerState", observerState(database, sessionId))
        root.put(
            "sequences",
            JSONArray().apply {
                database.networkSequenceDao().getBySession(sessionId).forEach { sequence ->
                    put(sequenceJson(database, sequence.id, sequence))
                }
            },
        )
        root.put(
            "videoRequests",
            JSONArray().apply {
                database.networkVideoRequestDao().getBySession(sessionId).forEach { request ->
                    put(
                        JSONObject()
                            .put("order", request.requestOrder)
                            .put("requestKind", request.requestKind.name)
                            .put("requestedAt", request.requestedAt)
                            .putOpt("videoId", request.videoId)
                            .putOpt("expectedPosition", request.expectedPosition),
                    )
                }
            },
        )
        root.put(
            "lineages",
            JSONArray().apply {
                database.sequenceLineageDao().getBySession(sessionId).forEach { lineage ->
                    put(
                        JSONObject()
                            .put("fromSequenceId", lineage.fromSequenceId)
                            .put("toSequenceId", lineage.toSequenceId)
                            .put("relation", lineage.relation.name)
                            .putOpt("signalsJson", lineage.signalsJson?.let { runCatching { JSONObject(it) }.getOrNull() })
                            .put("decidedAt", lineage.decidedAt),
                    )
                }
            },
        )
        root.put(
            "insertionCandidates",
            JSONArray().apply {
                database.insertionEventDao().getBySession(sessionId).forEach { event ->
                    put(
                        JSONObject()
                            .put("newVideoId", event.newVideoId)
                            .putOpt("prevVideoId", event.prevVideoId)
                            .putOpt("nextVideoId", event.nextVideoId)
                            .put("autoVerdict", event.autoVerdict.name)
                            .put("source", event.source.name)
                            .put("detectedAt", event.detectedAt)
                            .putOpt("strengthenedBy", event.strengthenedByJson?.let { runCatching { JSONArray(it) }.getOrNull() })
                            .putOpt(
                                "evidence",
                                event.evidenceJson?.let {
                                    runCatching {
                                        val evidence = InsertionEvidence.fromJson(it)
                                        JSONObject()
                                            .put("sameLineageFlow", evidence.sameLineageFlow)
                                            .put("stabilized", evidence.stabilized)
                                            .put("strengthenedByPlayerRequest", evidence.strengthenedByPlayerRequest)
                                            .put("strengthenedByReelItemWatch", evidence.strengthenedByReelItemWatch)
                                            .put("strengthenedByDomActive", evidence.strengthenedByDomActive)
                                    }.getOrNull()
                                },
                            ),
                    )
                }
            },
        )
        return root.toString(2)
    }

    private suspend fun observerState(database: AppDatabase, sessionId: Long): JSONObject? {
        val state = database.networkObserverStateDao().getBySession(sessionId) ?: return null
        return JSONObject()
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
    }

    private suspend fun sequenceJson(
        database: AppDatabase,
        sequenceId: Long,
        sequence: com.shortsmonitor.core.database.entity.NetworkSequenceEntity,
    ): JSONObject {
        val items = JSONArray().apply {
            database.networkSequenceItemDao().getBySequence(sequenceId).forEach { item ->
                put(
                    JSONObject()
                        .put("position", item.position)
                        .putOpt("videoId", item.videoId)
                        .put("entryKind", item.entryKind.name)
                        .putOpt("nonVideoKind", item.nonVideoKind)
                        .put("isCurrent", item.isCurrent)
                        .put("hasPlayerParams", item.hasPlayerParams)
                        .put("hasContinuation", item.hasContinuation),
                )
            }
        }
        return JSONObject()
            .put("createdAt", sequence.createdAt)
            .putOpt("currentVideoId", sequence.currentVideoId)
            .put("entryContext", sequence.entryContext.name)
            .putOpt("sequenceHash", sequence.sequenceHash)
            .put("parseStatus", sequence.parseStatus.name)
            .putOpt("warnings", sequence.warningsJson?.let { runCatching { JSONArray(it) }.getOrNull() })
            .putOpt("lineageId", sequence.lineageId)
            .put("items", items)
    }

    /** org.json의 putOpt 동작: null이면 키를 생략한다. */
    private fun JSONObject.putOpt(key: String, value: Any?): JSONObject {
        if (value != null) put(key, value)
        return this
    }
}
