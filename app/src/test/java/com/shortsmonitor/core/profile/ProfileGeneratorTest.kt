package com.shortsmonitor.core.profile

import com.shortsmonitor.core.model.ProfileTemplateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/**
 * 프로필 생성기 테스트 (L단계).
 * 무작위 프로필이 내부 일관성을 가지는지 검증한다.
 * 각 속성을 완전히 독립적으로 생성하지 않고 템플릿 범위 안에서 생성된다.
 */
class ProfileGeneratorTest {

    private val random = Random(42)

    @Test
    fun `generated profile has mobile token only for mobile templates`() {
        ProfileTemplateType.entries.forEach { template ->
            repeat(20) {
                val profile = ProfileGenerator.generate(template, random)
                val hasMobile = profile.userAgent.contains(" Mobile ")
                assertEquals(
                    "template=$template mobile token mismatch",
                    template != ProfileTemplateType.ANDROID_TABLET,
                    hasMobile,
                )
            }
        }
    }

    @Test
    fun `user agent contains model android and chrome version`() {
        repeat(50) {
            val profile = ProfileGenerator.generate(ProfileTemplateType.ANDROID, random)
            assertTrue(profile.userAgent.startsWith("Mozilla/5.0 (Linux; Android "))
            assertTrue(profile.userAgent.contains("Chrome/"))
            assertTrue(profile.userAgent.contains("AppleWebKit/537.36"))
            assertTrue(profile.userAgent.contains("Safari/537.36"))
        }
    }

    @Test
    fun `small android template stays in small screen and low specs`() {
        repeat(50) {
            val profile = ProfileGenerator.generate(ProfileTemplateType.SMALL_ANDROID, random)
            val (width, height) = profile.screenOverride.split("x").map { it.toInt() }
            assertTrue("width=$width out of small range", width in 320..360)
            assertTrue("height=$height out of small range", height in 568..800)
            assertTrue(profile.hardwareOverride.startsWith("4코어"))
        }
    }

    @Test
    fun `tablet template uses large screen and higher memory`() {
        repeat(50) {
            val profile = ProfileGenerator.generate(ProfileTemplateType.ANDROID_TABLET, random)
            val (width, height) = profile.screenOverride.split("x").map { it.toInt() }
            assertTrue("width=$width out of tablet range", width in 600..800)
            assertTrue("height=$height out of tablet range", height in 960..1280)
        }
    }

    @Test
    fun `generated language is a valid locale`() {
        repeat(50) {
            val profile = ProfileGenerator.generate(ProfileTemplateType.ANDROID, random)
            val locale = Locale.forLanguageTag(profile.language)
            assertFalse("invalid language=${profile.language}", locale.language.isBlank())
        }
    }

    @Test
    fun `random template returns a known template type`() {
        repeat(50) {
            assertTrue(ProfileGenerator.randomTemplate(random) in ProfileTemplateType.entries)
        }
    }

    @Test
    fun `touch override is enabled for generated profiles`() {
        ProfileTemplateType.entries.forEach { template ->
            repeat(5) {
                val profile = ProfileGenerator.generate(template, random)
                assertTrue("template=$template touch disabled", profile.touchOverride)
            }
        }
    }
}
