package com.shortsmonitor.core.profile

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.shortsmonitor.core.database.entity.BrowserProfileEntity
import com.shortsmonitor.core.logging.ShortsLog
import com.shortsmonitor.core.model.ProfileTemplateType

/** 프로필에서 WebView로 적용할 수 있는 항목. */
enum class ProfileApplyItem {
    USER_AGENT,
    USER_AGENT_METADATA,
    LANGUAGE,
    TIMEZONE,
    SCREEN,
    HARDWARE,
    TOUCH,
}

/**
 * 브라우저 테스트 프로필 적용기 (L단계).
 *
 * WebView에 프로필의 노출값을 적용한다. User-Agent는 항상 적용하고,
 * 지원되는 User-Agent 메타데이터(모델·모바일 여부)는 단말의 WebView 기능 검사
 * ([WebViewFeature.USER_AGENT_METADATA]) 결과에 따라 분기해 적용한다.
 *
 * 언어·시간대·화면 크기·CPU/메모리·터치 표현값은 공개 WebView API로 직접
 * 바꿀 수 없으므로 적용 결과에서 [ProfileApplyItem]으로 미지원을 명시한다.
 * IP나 실제 하드웨어를 변경하는 기능이 아니라는 표현만 사용한다.
 */
object ProfileApplier {

    /** 적용된 값과 지원되지 않아 적용하지 못한 값의 목록. */
    data class ApplyResult(
        val applied: List<ProfileApplyItem>,
        val unsupported: List<ProfileApplyItem>,
    ) {
        val allItems: List<ProfileApplyItem> = applied + unsupported
    }

    /**
     * WebView 없이 단말의 WebView 기능 상태만으로 적용·미지원 항목을 계산한다.
     * 상세 화면의 적용 결과 표시에 사용한다.
     */
    fun plan(profile: BrowserProfileEntity): ApplyResult {
        val applied = mutableListOf(ProfileApplyItem.USER_AGENT)
        val unsupported = mutableListOf<ProfileApplyItem>()
        val metadataSupported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
        }.getOrDefault(false)
        if (metadataSupported) {
            applied += ProfileApplyItem.USER_AGENT_METADATA
        } else {
            unsupported += ProfileApplyItem.USER_AGENT_METADATA
        }
        unsupported += listOf(
            ProfileApplyItem.LANGUAGE,
            ProfileApplyItem.TIMEZONE,
            ProfileApplyItem.SCREEN,
            ProfileApplyItem.HARDWARE,
            ProfileApplyItem.TOUCH,
        )
        return ApplyResult(applied = applied, unsupported = unsupported)
    }

    /**
     * WebView에 프로필을 적용한다.
     * @return 적용·미지원 항목 (화면에서 리소스 라벨로 변환)
     */
    fun apply(webView: WebView, profile: BrowserProfileEntity): ApplyResult {
        val result = plan(profile)

        // 1) User-Agent: 항상 적용
        webView.settings.userAgentString = profile.userAgent

        // 2) 지원되는 User-Agent 메타데이터: 기능 검사 후 적용
        if (ProfileApplyItem.USER_AGENT_METADATA in result.applied) {
            runCatching {
                WebSettingsCompat.setUserAgentMetadata(
                    webView.settings,
                    buildMetadata(profile),
                )
            }.onFailure { error ->
                ShortsLog.w("Profile: User-Agent metadata not applied", error)
            }
        }

        ShortsLog.d(
            "Profile applied: ${profile.name} applied=${result.applied.size} unsupported=${result.unsupported.size}",
        )
        return result
    }

    /**
     * WebView를 생성하기 전에 [WebSettings]에 적용할 수 있는 설정을 미리 반영한다.
     * User-Agent 문자열처럼 WebView 생성 시점에 필요한 값은 여기서 처리한다.
     */
    fun applyToSettings(settings: WebSettings, profile: BrowserProfileEntity) {
        settings.userAgentString = profile.userAgent
    }

    private fun buildMetadata(profile: BrowserProfileEntity): UserAgentMetadata {
        val builder = UserAgentMetadata.Builder()
            .setPlatform("Android")
            .setModel(modelFromUserAgent(profile.userAgent))
            .setMobile(profile.templateType != ProfileTemplateType.ANDROID_TABLET)
            .setBitness(64)
            .setWow64(false)
        return builder.build()
    }

    private fun modelFromUserAgent(userAgent: String): String {
        // "Linux; Android 13; SM-S911 Build/..." 형태에서 모델명을 추출한다.
        val marker = "; "
        val buildMarker = " Build/"
        val start = userAgent.indexOf(marker)
        val build = userAgent.indexOf(buildMarker)
        if (start < 0 || build <= start) return "Android"
        return userAgent.substring(start + marker.length, build).trim()
    }
}
