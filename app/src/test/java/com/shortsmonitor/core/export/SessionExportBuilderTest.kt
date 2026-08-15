package com.shortsmonitor.core.export

import com.shortsmonitor.core.database.entity.BrowserProfileEntity
import com.shortsmonitor.core.database.entity.ExposureEventEntity
import com.shortsmonitor.core.database.entity.InsertionEventEntity
import com.shortsmonitor.core.database.entity.ListSnapshotEntity
import com.shortsmonitor.core.database.entity.ObservedShortEntity
import com.shortsmonitor.core.database.entity.ObservationSessionEntity
import com.shortsmonitor.core.model.AutoVerdict
import com.shortsmonitor.core.model.ProfileTemplateType
import com.shortsmonitor.core.model.SessionEndReason
import com.shortsmonitor.core.model.SessionStatus
import com.shortsmonitor.core.model.ShortIdentityStatus
import com.shortsmonitor.core.model.SnapshotChangeReason
import com.shortsmonitor.core.model.UserVerdict
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** JSON 직렬화 검증을 위해 Robolectric을 사용한다. */
@RunWith(RobolectricTestRunner::class)
class SessionExportBuilderTest {

    private val session = ObservationSessionEntity(
        id = 1,
        sessionId = "session-1",
        name = "테스트 세션",
        status = SessionStatus.COMPLETED,
        startedAt = 1_700_000_000_000,
        endedAt = 1_700_000_360_000,
        startUrl = "https://m.youtube.com/shorts",
        endReason = SessionEndReason.USER_FINISHED,
        appVersion = "1.0.0",
        webViewInfo = "Android WebView 123",
    )

    private val profile = BrowserProfileEntity(
        id = 2,
        name = "프로필 A",
        templateType = ProfileTemplateType.ANDROID,
        userAgent = "Mozilla/5.0 (Linux; Android 14) Chrome/126.0",
        language = "ko-KR",
        timezone = "Asia/Seoul",
        screenOverride = "412x915",
        hardwareOverride = null,
        touchOverride = true,
        createdAt = 1_600_000_000_000,
        lastUsedAt = 1_700_000_000_000,
    )

    private val short = ObservedShortEntity(
        id = 3,
        sessionId = 1,
        videoId = "abc123",
        videoUrl = "https://m.youtube.com/shorts/abc123",
        title = "영상 제목",
        channelName = "채널",
        thumbnailUrl = "https://i.ytimg.com/vi/abc123/hqdefault.jpg",
        identityStatus = ShortIdentityStatus.RELIABLE,
        firstSeenAt = 1_700_000_000_000,
        lastSeenAt = 1_700_000_360_000,
        activatedAt = 1_700_000_100_000,
        prevVideoId = null,
        nextVideoId = null,
    )

    private val exposure = ExposureEventEntity(
        id = 4,
        sessionId = 1,
        videoId = "abc123",
        exposedAt = 1_700_000_100_000,
        exposedUntil = 1_700_000_200_000,
        exposureOrder = 1,
    )

    private val snapshot = ListSnapshotEntity(
        id = 5,
        sessionId = 1,
        createdAt = 1_700_000_200_000,
        currentUrl = "https://m.youtube.com/shorts/abc123",
        activeVideoId = "abc123",
        videoIdsJson = """["abc123","def456"]""",
        changeReason = SnapshotChangeReason.SESSION_RESET,
        domRevision = 2,
    )

    private val event = InsertionEventEntity(
        id = 6,
        sessionId = 1,
        newVideoId = "xyz789",
        prevVideoId = "abc123",
        nextVideoId = "def456",
        beforeSnapshotId = 4,
        afterSnapshotId = 5,
        detectedAt = 1_700_000_300_000,
        autoVerdict = AutoVerdict.CONFIRMED,
        userVerdict = UserVerdict.SUSPECTED,
        userMemo = "메모",
        evidenceJson = """{"adjacent":true}""",
    )

    private fun data(): SessionExportData = SessionExportData(
        session = session,
        profile = profile,
        shorts = listOf(short),
        exposures = listOf(exposure),
        snapshots = listOf(snapshot),
        events = listOf(event),
    )

    @Test
    fun `buildJson includes all sections`() {
        val json = JSONObject(SessionExportBuilder.buildJson(data(), appVersion = "1.0.0", exportedAt = 123L))

        assertEquals("shorts monitor", json.getString("app"))
        assertEquals("1.0.0", json.getString("version"))
        assertEquals(123L, json.getLong("exportedAt"))

        val sessionJson = json.getJSONObject("session")
        assertEquals("session-1", sessionJson.getString("sessionId"))
        assertEquals("COMPLETED", sessionJson.getString("status"))
        assertEquals("USER_FINISHED", sessionJson.getString("endReason"))

        val profileJson = json.getJSONObject("profile")
        assertEquals("프로필 A", profileJson.getString("name"))
        assertEquals("ANDROID", profileJson.getString("templateType"))
        assertEquals(true, profileJson.getBoolean("touchOverride"))
        assertFalse(profileJson.has("hardwareOverride"))

        assertEquals(1, json.getJSONArray("shorts").length())
        val shortJson = json.getJSONArray("shorts").getJSONObject(0)
        assertEquals("abc123", shortJson.getString("videoId"))
        assertEquals("RELIABLE", shortJson.getString("identityStatus"))

        assertEquals(1, json.getJSONArray("exposures").length())
        assertEquals(1, json.getJSONArray("snapshots").length())
        assertEquals(1, json.getJSONArray("events").length())
        val eventJson = json.getJSONArray("events").getJSONObject(0)
        assertEquals("xyz789", eventJson.getString("newVideoId"))
        assertEquals("CONFIRMED", eventJson.getString("autoVerdict"))
        assertEquals("SUSPECTED", eventJson.getString("userVerdict"))
        assertEquals("메모", eventJson.getString("userMemo"))
        assertTrue(eventJson.getJSONObject("evidence").getBoolean("adjacent"))
    }

    @Test
    fun `buildJson history derives from snapshot change reasons`() {
        val json = JSONObject(SessionExportBuilder.buildJson(data(), appVersion = "1.0.0", exportedAt = 123L))
        val history = json.getJSONObject("history")

        assertEquals(0, history.getJSONArray("profileChanges").length())
        assertEquals(1, history.getJSONArray("resets").length())
    }

    @Test
    fun `buildAllJson wraps multiple sessions`() {
        val json = JSONObject(
            SessionExportBuilder.buildAllJson(
                sessions = listOf(data(), data()),
                appVersion = "1.0.0",
                exportedAt = 123L,
            ),
        )
        assertEquals(2, json.getJSONArray("sessions").length())
        assertEquals("session-1", json.getJSONArray("sessions").getJSONObject(1).getJSONObject("session").getString("sessionId"))
    }

    @Test
    fun `buildCsvFiles returns four purpose-separated files`() {
        val files = SessionExportBuilder.buildCsvFiles(data())

        assertEquals(4, files.size)
        assertEquals("shorts_monitor_session.csv", files[0].fileName)
        assertEquals("shorts_monitor_shorts.csv", files[1].fileName)
        assertEquals("shorts_monitor_exposures.csv", files[2].fileName)
        assertEquals("shorts_monitor_events.csv", files[3].fileName)
    }

    @Test
    fun `session csv has header and one row`() {
        val csv = SessionExportBuilder.buildCsvFiles(data())[0].content
        val lines = csv.trimEnd().split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("session_id,name,status"))
        assertTrue(lines[1].contains("session-1"))
        assertTrue(lines[1].contains("테스트 세션"))
    }

    @Test
    fun `shorts csv escapes commas and quotes`() {
        val withComma = short.copy(title = "제목, 포함")
        val data = data().copy(shorts = listOf(withComma))
        val csv = SessionExportBuilder.buildCsvFiles(data)[1].content
        assertTrue(csv.contains("\"제목, 포함\""))
    }

    @Test
    fun `events csv includes verdicts and memo`() {
        val csv = SessionExportBuilder.buildCsvFiles(data())[3].content
        assertTrue(csv.contains("xyz789"))
        assertTrue(csv.contains("SUSPECTED"))
        assertTrue(csv.contains("메모"))
    }

    @Test
    fun `buildCsvFilesAll concatenates rows across sessions`() {
        val files = SessionExportBuilder.buildCsvFilesAll(listOf(data(), data()))

        assertEquals(4, files.size)
        // 세션 CSV: 헤더 1줄 + 세션 2줄
        assertEquals(3, files[0].content.trimEnd().split("\n").size)
        // 쇼츠 CSV: 헤더 1줄 + 쇼츠 2줄 (세션마다 1개)
        assertEquals(3, files[1].content.trimEnd().split("\n").size)
        // 노출 CSV: 헤더 1줄 + 노출 2줄
        assertEquals(3, files[2].content.trimEnd().split("\n").size)
        // 이벤트 CSV: 헤더 1줄 + 이벤트 2줄
        assertEquals(3, files[3].content.trimEnd().split("\n").size)
    }

    @Test
    fun `csvEscape wraps only when needed`() {
        assertEquals("plain", SessionExportBuilder.csvEscape("plain"))
        assertEquals("\"a,b\"", SessionExportBuilder.csvEscape("a,b"))
        assertEquals("\"a\"\"b\"", SessionExportBuilder.csvEscape("a\"b"))
    }

    @Test
    fun `json omits profile when absent but keeps events array`() {
        val noProfile = data().copy(profile = null)
        val json = JSONObject(SessionExportBuilder.buildJson(noProfile, appVersion = "1.0.0"))
        assertFalse(json.has("profile"))
        assertEquals(1, json.getJSONArray("events").length())
    }

    @Test
    fun `snapshot videoIds parsed from json`() {
        val json = JSONObject(SessionExportBuilder.buildJson(data(), appVersion = "1.0.0"))
        val snapshotJson = json.getJSONArray("snapshots").getJSONObject(0)
        val ids = snapshotJson.getJSONArray("videoIds")
        assertEquals(2, ids.length())
        assertEquals("abc123", ids.getString(0))
        assertEquals("def456", ids.getString(1))
    }

}
