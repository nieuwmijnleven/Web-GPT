package com.shortsmonitor.core.profile

import com.shortsmonitor.core.model.ProfileTemplateType
import kotlin.random.Random

/**
 * 템플릿 기반 브라우저 테스트 프로필 생성기 (L단계).
 *
 * 각 속성을 완전히 독립적으로 무작위 생성하지 않는다. 호환 가능한 템플릿을 먼저
 * 선택하고 그 범위 안에서 값을 생성하므로, 무작위 프로필이 내부 일관성을 가진다.
 * (예: 소형 Android 모바일에는 작은 화면·낮은 사양, 태블릿에는 큰 화면·높은 사양)
 *
 * 이 기능은 WebView에서 노출되는 일부 값을 변경하는 것이며 실제 IP 주소나
 * 물리적 하드웨어를 변경하는 것이 아니다.
 */
object ProfileGenerator {

    /** 생성된 프로필 값. 이름은 화면에서 리소스 라벨과 번호로 조합한다. */
    data class GeneratedProfile(
        val templateType: ProfileTemplateType,
        val userAgent: String,
        val language: String,
        val timezone: String,
        val screenOverride: String,
        val hardwareOverride: String,
        val touchOverride: Boolean,
    )

    /** 템플릿별 호환 범위. */
    private data class TemplateSpec(
        val type: ProfileTemplateType,
        val mobile: Boolean,
        val deviceModels: List<String>,
        val screenWidthRange: IntRange,
        val screenHeightRange: IntRange,
        val cpuCoresRange: IntRange,
        val memoryRange: IntRange,
    )

    private val SPECS: Map<ProfileTemplateType, TemplateSpec> = mapOf(
        ProfileTemplateType.SMALL_ANDROID to TemplateSpec(
            type = ProfileTemplateType.SMALL_ANDROID,
            mobile = true,
            deviceModels = listOf("SM-A037", "SM-A127", "M2101K7AG", "2201117TG", "SM-A135"),
            screenWidthRange = 320..360,
            screenHeightRange = 568..800,
            cpuCoresRange = 4..4,
            memoryRange = 2..4,
        ),
        ProfileTemplateType.ANDROID to TemplateSpec(
            type = ProfileTemplateType.ANDROID,
            mobile = true,
            deviceModels = listOf("SM-A536", "SM-A546", "Pixel 6a", "Pixel 7", "2201123G"),
            screenWidthRange = 360..412,
            screenHeightRange = 740..915,
            cpuCoresRange = 4..8,
            memoryRange = 4..8,
        ),
        ProfileTemplateType.LARGE_ANDROID to TemplateSpec(
            type = ProfileTemplateType.LARGE_ANDROID,
            mobile = true,
            deviceModels = listOf("SM-S911", "SM-S918", "Pixel 7 Pro", "2210132G"),
            screenWidthRange = 412..480,
            screenHeightRange = 892..1080,
            cpuCoresRange = 8..8,
            memoryRange = 8..12,
        ),
        ProfileTemplateType.ANDROID_TABLET to TemplateSpec(
            type = ProfileTemplateType.ANDROID_TABLET,
            mobile = false,
            deviceModels = listOf("SM-X700", "SM-T870", "SM-X906", "Pixel Tablet"),
            screenWidthRange = 600..800,
            screenHeightRange = 960..1280,
            cpuCoresRange = 8..8,
            memoryRange = 6..12,
        ),
    )

    private val ANDROID_VERSIONS = listOf("11", "12", "13", "14", "15")
    private val CHROME_VERSIONS = listOf(
        "119.0.0.0", "120.0.0.0", "121.0.0.0", "122.0.0.0", "123.0.0.0",
        "124.0.0.0", "125.0.0.0", "126.0.0.0", "127.0.0.0", "128.0.0.0",
    )
    private val BUILD_IDS = listOf(
        "TP1A.220624.014", "TQ3A.230805.001", "AP2A.240805.005", "UP1A.231005.007",
    )
    private val LANGUAGES = listOf(
        "ko-KR", "en-US", "ja-JP", "en-GB", "zh-CN", "fr-FR", "de-DE", "es-ES", "vi-VN", "id-ID",
    )
    private val TIMEZONES = listOf(
        "Asia/Seoul", "Asia/Tokyo", "Asia/Shanghai", "America/New_York",
        "Europe/London", "Europe/Paris", "Australia/Sydney",
    )

    /** 지정된 템플릿 범위 안에서 무작위 프로필을 생성한다. */
    fun generate(
        templateType: ProfileTemplateType,
        random: Random = Random.Default,
    ): GeneratedProfile {
        val spec = SPECS[templateType]
            ?: error("Unknown profile template: $templateType")
        val model = spec.deviceModels.random(random)
        val android = ANDROID_VERSIONS.random(random)
        val chrome = CHROME_VERSIONS.random(random)
        val build = BUILD_IDS.random(random)
        val width = spec.screenWidthRange.random(random)
        val height = spec.screenHeightRange.random(random)
        val cores = spec.cpuCoresRange.random(random)
        val memory = spec.memoryRange.random(random)
        return GeneratedProfile(
            templateType = templateType,
            userAgent = buildUserAgent(
                model = model,
                androidVersion = android,
                chromeVersion = chrome,
                buildId = build,
                mobile = spec.mobile,
            ),
            language = LANGUAGES.random(random),
            timezone = TIMEZONES.random(random),
            screenOverride = "${width}x$height",
            hardwareOverride = "${cores}코어 · ${memory}GB",
            touchOverride = true,
        )
    }

    /** 템플릿 하나를 무작위로 선택한다. */
    fun randomTemplate(random: Random = Random.Default): ProfileTemplateType =
        ProfileTemplateType.entries.random(random)

    /**
     * 템플릿 호환 범위 안에서 User-Agent 문자열을 생성한다.
     * 모바일은 "Mobile" 토큰이 포함되고, 태블릿은 포함되지 않는다.
     */
    private fun buildUserAgent(
        model: String,
        androidVersion: String,
        chromeVersion: String,
        buildId: String,
        mobile: Boolean,
    ): String {
        val mobileToken = if (mobile) " Mobile" else ""
        return "Mozilla/5.0 (Linux; Android $androidVersion; $model Build/$buildId) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion" +
            "$mobileToken Safari/537.36"
    }
}
