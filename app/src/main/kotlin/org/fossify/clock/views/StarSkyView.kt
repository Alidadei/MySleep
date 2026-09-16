package org.fossify.clock.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 整页天空 —— 复刻网站 page-sky 双层设计：
 * 夜：深空底（藕荷紫同宗径向渐变）+ 银河带两层 + 星云三团 + 尘埃星 170 +
 *     四层呼吸星（微小/中/光晕/十字芒，慢正弦呼吸灯式明暗，固定种子 20260910 可复现）+ 流星（5–11s 一颗，三成双流星）；
 * 昼：个人站同款暖渐变 + 太阳光晕。
 * 两层透明度随 TimeTheme.t 交叉淡化（夜星 opacity=1-t，昼空 opacity=t）。
 * 动画帧率克制（闪烁用 sin 相位，不逐帧重建），t≥0.97 时完全跳过夜空绘制（省电，同网站）。
 */
class StarSkyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var nightLayer: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val twinks = mutableListOf<Twinkle>()
    private val shooters = mutableListOf<Shooter>()
    private var seed = 20260910L
    private var lastFrame = 0L
    private var shootTimer = 4f + Random.nextFloat() * 6f

    private class Twinkle(
        val x: Float, val y: Float, val size: Float, val base: Float,
        val speed: Float, val phase: Float, val col: String,
        val small: Boolean = false, val glow: Boolean = false, val spikes: Boolean = false
    )

    private class Shooter(
        var x: Float, var y: Float, val vx: Float, val vy: Float,
        var life: Float, val decay: Float, val len: Float
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        buildNightLayer(w, h)
    }

    /** 网站固定种子 20260910 的可复现随机数（lev16807） */
    private fun rand(): Float {
        seed = (seed * 16807L) % 2147483647L
        return (seed - 1) / 2147483646f
    }

    private fun buildNightLayer(w: Int, h: Int) {
        seed = 20260910L
        twinks.clear()
        shooters.clear()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val g = Canvas(bmp)

        // 深空底：调紫不调蓝，与藕荷紫夜同宗
        val bgPaint = Paint().apply {
            shader = RadialGradient(
                w * 0.5f, h * 0.32f, Math.max(w, h) * 0.85f,
                intArrayOf(Color.parseColor("#251c3a"), Color.parseColor("#1c1530"), Color.parseColor("#120e20")),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
        }
        g.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // 银河带（微弱两层，-0.5rad）
        g.save()
        g.translate(w * 0.42f, h * 0.34f)
        g.rotate(-28.6f)
        for (l in 0..1) {
            val radius = w * (0.28f + l * 0.1f)
            val a = 0.06f - l * 0.02f
            val mg = RadialGradient(
                0f, 0f, radius,
                intArrayOf(
                    Color.argb((a * 255).toInt(), 155, 145, 205),
                    Color.argb((a * 0.5f * 255).toInt(), 120, 115, 180),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            g.save()
            g.scale(1f, 0.55f)
            g.translate(0f, radius * 0.45f / 0.55f * 0f)
            paint.shader = mg
            val rw = w * 1.2f
            val rh = h * (0.1f + l * 0.06f)
            g.drawRect(-rw / 2, -rh / 2, rw / 2, rh / 2, paint)
            g.restore()
        }
        g.restore()

        // 星云三团（藕荷紫系）
        val nebulae = arrayOf(
            arrayOf(0.16f, 0.3f, 0.2f, intArrayOf(130, 95, 190), 0.055f),
            arrayOf(0.8f, 0.24f, 0.16f, intArrayOf(165, 120, 190), 0.05f),
            arrayOf(0.55f, 0.68f, 0.18f, intArrayOf(100, 105, 175), 0.04f)
        )
        nebulae.forEach { n ->
            val cx = w * (n[0] as Float)
            val cy = h * (n[1] as Float)
            val radius = Math.min(w, h) * (n[2] as Float)
            val col = n[3] as IntArray
            val a = n[4] as Float
            g.save()
            g.translate(cx, cy)
            g.scale(1f, 0.55f)
            paint.shader = RadialGradient(
                0f, 0f, radius,
                Color.argb((a * 255).toInt(), col[0], col[1], col[2]),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            g.drawCircle(0f, 0f, radius, paint)
            g.restore()
        }
        paint.shader = null

        // 尘埃星 170（静态层）——网站 fillRect(x,y,w,h) 是 0.2–0.7px 微尘
        for (i in 0 until 170) {
            paint.color = Color.argb(((0.08f + rand() * 0.25f) * 255f).toInt().coerceIn(0, 255), 205, 196, 235)
            val dx = rand() * w
            val dy = rand() * h
            val size = 0.2f + rand() * 0.5f
            g.drawRect(dx, dy, dx + size, dy + size, paint)
        }

        // 闪烁星四层
        val starCols = arrayOf(
            intArrayOf(255, 240, 200), intArrayOf(200, 220, 255), intArrayOf(255, 200, 150),
            intArrayOf(180, 200, 255), intArrayOf(255, 255, 230)
        )
        val medCols = arrayOf("200,215,255", "255,245,220", "180,200,255", "255,220,180")
        fun pickCol(): IntArray = starCols[(rand() * starCols.size).toInt()]

        // 呼吸节奏：角速度 0.3–1.2 rad/s → 单星呼吸周期 5–20s，慢到像睡眠呼吸
        repeat(60) {
            twinks.add(
                Twinkle(
                    rand() * w, rand() * h, 0.5f + rand() * 0.8f, 0.25f + rand() * 0.35f,
                    0.4f + rand() * 0.8f, rand() * Math.PI.toFloat() * 2,
                    pickCol().joinToString(","), small = true
                )
            )
        }
        repeat(18) {
            twinks.add(
                Twinkle(
                    rand() * w, rand() * h, 1f + rand() * 1.1f, 0.35f + rand() * 0.4f,
                    0.35f + rand() * 0.75f, rand() * Math.PI.toFloat() * 2,
                    medCols[(rand() * medCols.size).toInt()]
                )
            )
        }
        repeat(8) {
            twinks.add(
                Twinkle(
                    rand() * w, rand() * h, 1.2f + rand() * 1.6f, 0.85f,
                    0.3f + rand() * 0.6f, rand() * Math.PI.toFloat() * 2,
                    pickCol().joinToString(","), glow = true
                )
            )
        }
        repeat(2) {
            twinks.add(
                Twinkle(
                    w * 0.08f + rand() * w * 0.84f, h * 0.08f + rand() * h * 0.6f,
                    1.8f + rand() * 1.8f, 0.9f, 0.25f + rand() * 0.5f,
                    rand() * Math.PI.toFloat() * 2, pickCol().joinToString(","), spikes = true
                )
            )
        }
        nightLayer = bmp
    }

    /** 供宿主在帧循环/onResume 里驱动重绘（闪烁与流星） */
    fun tick() {
        lastFrame = System.nanoTime()
        if (org.fossify.clock.helpers.TimeTheme.current().skyVisible) {
            invalidate()
        }
    }

    private var running = false

    /** 自驱动 ~24fps 动画循环（闪烁星与流星；助眠场景刻意低帧率） */
    fun start() {
        if (running) return
        running = true
        postInvalidateDelayed(16)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame > 0L) ((now - lastFrame) / 1e9).toFloat().coerceAtMost(0.1f) else 0f
        lastFrame = now
        drawSky(canvas, dt)
        if (running) {
            postInvalidateDelayed(42)   // ~24fps：夜里半睡半醒扫一眼，动效不应当被察觉
        }
    }

    private fun drawSky(canvas: Canvas, dt: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val t = org.fossify.clock.helpers.TimeTheme.current().t

        // 昼空：暖渐变 + 太阳光晕，opacity = t
        if (t > 0.03f) {
            paint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    Color.parseColor("#f5e6d3"), Color.parseColor("#f0d4c0"),
                    Color.parseColor("#e8c8d8"), Color.parseColor("#d4c0e8"), Color.parseColor("#cfc5ec")
                ),
                floatArrayOf(0f, 0.25f, 0.55f, 0.8f, 1f), Shader.TileMode.CLAMP
            )
            paint.alpha = (t * 255).toInt()
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = RadialGradient(
                w * 0.8f, h * 0.08f, 340f,
                Color.argb((0.95f * t * 255).toInt(), 255, 244, 214),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        // 夜空：静态层 + 闪烁星 + 流星，opacity = 1-t
        if (t < 0.97f) {
            val nightAlpha = ((1f - t) * 255).toInt()
            nightLayer?.let {
                paint.shader = null
                paint.alpha = nightAlpha
                canvas.drawBitmap(it, 0f, 0f, paint)
            }
            drawTwinkles(canvas, w, h, nightAlpha, dt)
        }
    }

    private fun drawTwinkles(canvas: Canvas, w: Float, h: Float, nightAlpha: Int, dt: Float) {
        val timeSec = lastFrame / 1000f
        for (s in twinks) {
            // 呼吸灯式明暗：纯正弦慢呼吸，不熄灭只涨落（小星 55%–100%，大星 30%–100%）
            val breathe = 0.5f + 0.5f * sin(timeSec * s.speed + s.phase)
            val alpha = (if (s.small) s.base * (0.55f + 0.45f * breathe) else s.base * (0.3f + 0.7f * breathe)) *
                (nightAlpha / 255f)
            val col = s.col.split(",").map { it.toInt() }
            val argb = Color.argb((alpha * 255).toInt().coerceIn(0, 255), col[0], col[1], col[2])

            if (s.spikes) {
                val radius = s.size * 8
                paint.shader = RadialGradient(
                    s.x, s.y, radius,
                    Color.argb((0.2f * breathe * 255).toInt(), col[0], col[1], col[2]),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP
                )
                canvas.drawRect(s.x - radius, s.y - radius, s.x + radius, s.y + radius, paint)
                paint.shader = null
                paint.color = argb
                canvas.drawCircle(s.x, s.y, s.size, paint)
                paint.color = Color.argb((0.35f * breathe * 255).toInt(), col[0], col[1], col[2])
                paint.strokeWidth = 0.8f
                val len = s.size * 6
                canvas.drawLine(s.x - len, s.y, s.x + len, s.y, paint)
                canvas.drawLine(s.x, s.y - len, s.x, s.y + len, paint)
            } else if (s.glow) {
                val radius = s.size * 5
                paint.shader = RadialGradient(
                    s.x, s.y, radius,
                    Color.argb((0.22f * breathe * 255).toInt(), col[0], col[1], col[2]),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP
                )
                canvas.drawRect(s.x - radius, s.y - radius, s.x + radius, s.y + radius, paint)
                paint.shader = null
                paint.color = argb
                canvas.drawCircle(s.x, s.y, s.size, paint)
            } else {
                paint.color = argb
                canvas.drawCircle(s.x, s.y, s.size, paint)
            }
        }

        // 流星：温和常客（5–11s 一颗，三成概率同波双流星）
        paint.shader = null
        if (dt > 0) {
            shootTimer -= dt
            if (shootTimer <= 0) {
                shootTimer = 5f + Random.nextFloat() * 6f
                val count = if (Random.nextFloat() < 0.3f) 2 else 1
                repeat(count) {
                    val angle = 0.3f + Random.nextFloat() * 0.5f
                    val speed = 240f + Random.nextFloat() * 200f
                    shooters.add(
                        Shooter(
                            w * 0.1f + Random.nextFloat() * w * 0.65f, Random.nextFloat() * h * 0.35f,
                            cos(angle) * speed, sin(angle) * speed,
                            1f, 0.55f + Random.nextFloat() * 0.3f, 34f + Random.nextFloat() * 46f
                        )
                    )
                }
            }
            for (k in shooters.indices.reversed()) {
                val s = shooters[k]
                s.x += s.vx * dt
                s.y += s.vy * dt
                s.life -= s.decay * dt
                if (s.life <= 0) {
                    shooters.removeAt(k)
                    continue
                }
                val tail = Math.min(s.len, s.life * 90f)
                val speed = hypot(s.vx.toDouble(), s.vy.toDouble()).toFloat()
                val nx = s.x - s.vx / speed * tail
                val ny = s.y - s.vy / speed * tail
                paint.shader = LinearGradient(
                    s.x, s.y, nx, ny,
                    Color.argb((0.7f * s.life * 255).toInt(), 220, 225, 255),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP
                )
                paint.strokeWidth = 1.4f
                canvas.drawLine(s.x, s.y, nx, ny, paint)
                paint.shader = null
            }
        }
    }
}
