package org.fossify.clock.activities

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.view.WindowManager
import org.fossify.clock.R
import org.fossify.clock.databinding.ActivityTimerAlarmBinding
import org.fossify.clock.extensions.hideNotification
import org.fossify.clock.extensions.timerDb
import org.fossify.clock.helpers.RingOverlayHelper
import org.fossify.clock.helpers.TIMER_ID
import org.fossify.clock.helpers.TorchHelper
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isOreoMr1Plus

/**
 * Full-screen ring page for timers: shown over the lock screen when a
 * light-alarm timer expires. Closing it stops the vibration and the
 * flashlight.
 */
class TimerAlarmActivity : SimpleActivity() {

    private lateinit var binding: ActivityTimerAlarmBinding
    private var timerId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RingOverlayHelper.RingUiTracker.ringingActivityShowing = true
        RingOverlayHelper.dismiss(this)
        binding = ActivityTimerAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showOverLockscreen()

        timerId = intent.getIntExtra(TIMER_ID, -1)
        ensureBackgroundThread {
            val label = if (timerId != -1) {
                try {
                    timerDb.getTimer(timerId)?.label
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            runOnUiThread {
                if (!isDestroyed) {
                    binding.timerAlarmLabel.text =
                        label?.ifEmpty { getString(R.string.timer) } ?: getString(R.string.timer)
                }
            }
        }

        binding.timerAlarmClose.setOnClickListener {
            stopEffects()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        RingOverlayHelper.RingUiTracker.ringingActivityShowing = false
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

    private fun stopEffects() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                manager.defaultVibrator.cancel()
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                vibrator.cancel()
            }
        } catch (e: Exception) {
        }
        TorchHelper.setTorch(this, false)
        if (timerId != -1) {
            hideNotification(timerId)
        }
    }

    companion object {
        fun launch(context: Context, timerId: Int) {
            try {
                context.startActivity(
                    Intent(context, TimerAlarmActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(TIMER_ID, timerId)
                    }
                )
            } catch (e: Exception) {
            }
        }
    }
}
