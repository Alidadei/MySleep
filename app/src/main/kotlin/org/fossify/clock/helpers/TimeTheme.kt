package org.fossify.clock.helpers

import android.graphics.Color

/**
 * 时辰主题引擎 —— 逐字复刻 cyberSleepCommunity/index.html 的主题脚本：
 * 夜=藕荷紫夜（站主 2026-09-10 配色图），昼=琥珀棕×雾杏粉；
 * 5–8 点 smoothstep 渐亮、17–20 点渐暗，其余恒夜/恒昼；
 * ink/sub 按背景相对亮度在窄窗口内翻转，避免过渡期的仲灰盲区。
 *
 * @property t 昼度 [0,1]，0=纯夜 1=纯昼
 */
data class TimeTheme(
    val t: Float,
    val bg: Int,
    val card: Int,
    val input: Int,
    val line: Int,
    val ink: Int,
    val sub: Int,
    val accent: Int,
    val act: Int,
    val ok: Int,
    val bad: Int,
    val accent2: Int,
    val link: Int,
    /** 品牌标题色（网站 --grey，恒近白） */
    val grey: Int,
) {
    /** 星空画布可见度：昼间（t≥0.97）星空淡出 */
    val skyVisible: Boolean get() = t < 0.97f

    companion object {

        private val NIGHT = mapOf(
            "bg" to intArrayOf(28, 22, 38), "card" to intArrayOf(39, 32, 51),
            "input" to intArrayOf(30, 24, 41), "line" to intArrayOf(64, 54, 82),
            "ink" to intArrayOf(236, 228, 225), "sub" to intArrayOf(168, 151, 176),
            "accent" to intArrayOf(184, 155, 191), "act" to intArrayOf(125, 97, 153),
            "ok" to intArrayOf(165, 201, 174), "bad" to intArrayOf(217, 143, 143),
            "accent2" to intArrayOf(220, 195, 226), "link" to intArrayOf(201, 179, 214),
            "nodeorb" to intArrayOf(255, 255, 255), "grey" to intArrayOf(250, 246, 240)
        )

        private val DAY = mapOf(
            "bg" to intArrayOf(244, 235, 228), "card" to intArrayOf(236, 225, 216),
            "input" to intArrayOf(250, 245, 240), "line" to intArrayOf(217, 200, 188),
            "ink" to intArrayOf(80, 59, 44), "sub" to intArrayOf(94, 75, 59),
            "accent" to intArrayOf(122, 90, 69), "act" to intArrayOf(109, 83, 66),
            "ok" to intArrayOf(95, 138, 82), "bad" to intArrayOf(179, 90, 74),
            "accent2" to intArrayOf(224, 191, 174), "link" to intArrayOf(138, 95, 58),
            "nodeorb" to intArrayOf(255, 196, 110), "grey" to intArrayOf(250, 246, 240)
        )

        private fun smooth(x: Float): Float =
            when {
                x <= 0f -> 0f
                x >= 1f -> 1f
                else -> x * x * (3f - 2f * x)
            }

        /** 昼度：与网站 dayT() 同一时刻表 */
        fun dayT(hour: Float = java.util.Calendar.getInstance().let {
            it.get(java.util.Calendar.HOUR_OF_DAY) + it.get(java.util.Calendar.MINUTE) / 60f
        }): Float = when {
            hour >= 8f && hour < 17f -> 1f
            hour >= 5f && hour < 8f -> smooth((hour - 5f) / 3f)
            hour >= 17f && hour < 20f -> 1f - smooth((hour - 17f) / 3f)
            else -> 0f
        }

        /** sRGB 通道 → 线性亮度（网站 lin()） */
        private fun lin(v: Float): Float {
            val c = v / 255f
            return if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055) / 1.055).toDouble(), 2.4).toFloat()
        }

        private fun lerp(k: String, t: Float): Int {
            val n = NIGHT.getValue(k)
            val d = DAY.getValue(k)
            val r = (n[0] + (d[0] - n[0]) * t).toInt()
            val g = (n[1] + (d[1] - n[1]) * t).toInt()
            val b = (n[2] + (d[2] - n[2]) * t).toInt()
            return Color.rgb(r, g, b)
        }

        fun current(): TimeTheme {
            val t = dayT()

            // 背景相对亮度 → ink/sub 翻转窗口（网站 apply() 内的防仲灰盲区逻辑）
            val bgR = (NIGHT.getValue("bg")[0] + (DAY.getValue("bg")[0] - NIGHT.getValue("bg")[0]) * t)
            val bgG = (NIGHT.getValue("bg")[1] + (DAY.getValue("bg")[1] - NIGHT.getValue("bg")[1]) * t)
            val bgB = (NIGHT.getValue("bg")[2] + (DAY.getValue("bg")[2] - NIGHT.getValue("bg")[2]) * t)
            val bgL = 0.2126f * lin(bgR) + 0.7152f * lin(bgG) + 0.0722f * lin(bgB)
            val flipInk = smooth((bgL - 0.235f) / 0.02f)
            val flipSub = smooth((bgL - 0.17f) / 0.02f)

            fun channel(k: String): Int = when (k) {
                "ink" -> lerp(k, flipInk)
                "sub" -> lerp(k, flipSub)
                else -> lerp(k, t)
            }

            return TimeTheme(
                t = t,
                bg = channel("bg"),
                card = channel("card"),
                input = channel("input"),
                line = channel("line"),
                ink = channel("ink"),
                sub = channel("sub"),
                accent = channel("accent"),
                act = channel("act"),
                ok = channel("ok"),
                bad = channel("bad"),
                accent2 = channel("accent2"),
                link = channel("link"),
                grey = channel("grey")
            )
        }
    }
}
