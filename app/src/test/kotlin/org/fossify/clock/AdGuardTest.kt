package org.fossify.clock

import org.fossify.clock.helpers.AdGuard
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the community anti-ad gate.
 */
class AdGuardTest {

    private fun blocked(vararg texts: String): Boolean =
        AdGuard.check(*texts) is AdGuard.Verdict.Blocked

    @Test
    fun normalContent_passes() {
        assertTrue(!blocked("雨声白噪音很好用，听着入睡快"))
        assertTrue(!blocked("LibriVox 公版有声书"))
        assertTrue(!blocked(""))
    }

    @Test
    fun strongKeywords_areBlocked() {
        assertTrue(blocked("超好用的助眠曲，加微信 abc12345 领取"))
        assertTrue(blocked("低价出售二手降噪耳机"))
        assertTrue(blocked("兼职刷单，日结佣金"))
    }

    @Test
    fun contactPatterns_areBlocked() {
        assertTrue(blocked("睡不着可以vx: sleeper_88 交流"))
        assertTrue(blocked("进QQ群123456一起打卡"))
        assertTrue(blocked("联系 13812345678"))
        assertTrue(blocked("详情点 t.cn/A6xYz12"))
    }

    @Test
    fun singleWeakKeyword_passes() {
        // one weak hit alone is not enough evidence
        assertTrue(!blocked("这个平台的白噪音很全"))
    }

    @Test
    fun multipleWeakKeywords_areBlocked() {
        assertTrue(blocked("限时福利，点击下单"))
    }
}
