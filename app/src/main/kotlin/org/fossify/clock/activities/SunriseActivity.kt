package org.fossify.clock.activities

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import org.fossify.clock.databinding.ActivitySunriseBinding
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.isOreoMr1Plus

/**
 * The visual sunrise: shown when the sunrise fade-in starts, ramps the window
 * brightness and the scene colors from deep night to warm dawn over the whole
 * sunrise window. Tapping anywhere only dismisses the screen - the flashlight
 * keeps running in [org.fossify.clock.services.SunriseService] until the alarm.
 */
class SunriseActivity : SimpleActivity() {

    companion object {
        private const val NIGHT_COLOR = 0xFF0B0C1E.toInt()
        private const val DAWN_INDIGO = 0xFF2B2C5E.toInt()
        private const val DAWN_GOLD = 0xFFB98A4A.toInt()
        private const val DAWN_LIGHT = 0xFFF3E4C2.toInt()
    }

    private val binding by viewBinding(ActivitySunriseBinding::inflate)
    private var rampAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        showOverLockscreen()

        val durationMinutes = intent.getIntExtra(
            org.fossify.clock.helpers.SUNRISE_DURATION_MIN,
            org.fossify.clock.services.SunriseService.DEFAULT_SUNRISE_MINUTES
        ).coerceAtLeast(1)

        binding.sunriseRoot.onGlobalLayout {
            startRamp(durationMinutes * 60_000L)
        }

        binding.sunriseRoot.setOnClickListener {
            finish()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun startRamp(durationMs: Long) {
        // screen brightness from almost off to full - the real "dim to bright"
        val brightnessAnimator = ValueAnimator.ofFloat(0.01f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(0.8f)
            addUpdateListener { animator ->
                window.attributes = window.attributes.apply {
                    screenBrightness = animator.animatedValue as Float
                }
            }
        }

        // night -> dawn scene colors
        rampAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(0.8f)
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                val skyColor = when {
                    progress < 0.5f -> ArgbEvaluator().evaluate(
                        progress / 0.5f, NIGHT_COLOR, DAWN_INDIGO
                    ) as Int

                    progress < 0.85f -> ArgbEvaluator().evaluate(
                        (progress - 0.5f) / 0.35f, DAWN_INDIGO, DAWN_GOLD
                    ) as Int

                    else -> ArgbEvaluator().evaluate(
                        (progress - 0.85f) / 0.15f, DAWN_GOLD, DAWN_LIGHT
                    ) as Int
                }

                binding.sunriseSky.setBackgroundColor(skyColor)
                binding.sunriseMoon.alpha = (1f - progress * 0.6f)
                binding.sunriseHint.setTextColor(
                    if (progress < 0.5f) Color.WHITE else Color.DKGRAY
                )
            }
            start()
        }
        brightnessAnimator.start()
    }

    private fun showOverLockscreen() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        if (isOreoMr1Plus()) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rampAnimator?.cancel()
    }
}
