package org.fossify.clock

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.clock.helpers.NightTalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 画像（SleepProfile）本地存储回归：新增研究字段（ageGroup/gender/education）
 * 对旧 JSON 的兼容 + 匿名 uid 一次生成永久固定。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = App::class)
class NightTalkProfileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun profile_roundtrip_keepsResearchFields() {
        val profile = NightTalk.SleepProfile(
            nickname = "未寝人4242",
            insomniaType = "anxious",
            birthYear = 1999,
            birthMonth = 6,
            birthDay = 15,
            ageGroup = "90s",
            gender = "male",
            education = "bachelor"
        )
        NightTalk.saveProfile(context, profile)
        val loaded = NightTalk.getProfile(context)
        assertEquals("未寝人4242", loaded.nickname)
        assertEquals("anxious", loaded.insomniaType)
        assertEquals(1999, loaded.birthYear)
        assertEquals("90s", loaded.ageGroup)
        assertEquals("male", loaded.gender)
        assertEquals("bachelor", loaded.education)
    }

    @Test
    fun profile_defaultResearchFields_arePreferNot() {
        val profile = NightTalk.SleepProfile(nickname = "anon")
        assertEquals("prefer_not", profile.ageGroup)
        assertEquals("prefer_not", profile.gender)
        assertEquals("prefer_not", profile.education)
    }

    @Test
    fun uid_isStableAcrossCalls() {
        val first = NightTalk.getUid(context)
        assertTrue(first.startsWith("u") && first.length > 2)
        assertEquals(first, NightTalk.getUid(context))
    }
}
