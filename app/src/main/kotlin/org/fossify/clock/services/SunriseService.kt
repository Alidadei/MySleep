package org.fossify.clock.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fossify.clock.R
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.helpers.ALARM_ID
import org.fossify.clock.helpers.SUNRISE_NOTIFICATION_CHANNEL_ID
import org.fossify.clock.helpers.SUNRISE_NOTIFICATION_ID
import org.fossify.clock.helpers.SUNRISE_DURATION_MIN
import org.fossify.clock.helpers.TorchHelper
import org.fossify.commons.extensions.notificationManager

/**
 * Runs the "sunrise" fade-in: ramps the flash LED from near-dark to full brightness
 * during the minutes before the alarm rings. The LED cannot change brightness, so the
 * ramp is a duty-cycle PWM: the on-ratio within each cycle grows following an ease-in
 * curve, which reads to the eye as a slow sunrise.
 */
class SunriseService : Service() {

    companion object {
        const val ACTION_START_SUNRISE = "org.fossify.clock.START_SUNRISE"
        const val ACTION_STOP_SUNRISE = "org.fossify.clock.STOP_SUNRISE"

        const val DEFAULT_SUNRISE_MINUTES = 10

        private const val PWM_CYCLE_MS = 80L
        private const val MIN_DUTY = 0.04f
    }

    private val rampScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rampJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SUNRISE -> {
                stopRamp()
                stopSelf()
            }

            else -> {
                val alarmId = intent?.getIntExtra(ALARM_ID, -1) ?: -1
                val durationMinutes =
                    intent?.getIntExtra(SUNRISE_DURATION_MIN, DEFAULT_SUNRISE_MINUTES)
                        ?: DEFAULT_SUNRISE_MINUTES

                val alarm = dbHelper.getAlarmWithId(alarmId)
                if (alarm == null || !TorchHelper.hasFlash(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(SUNRISE_NOTIFICATION_ID, buildNotification(alarm.label))
                startRamp(durationMinutes)
            }
        }

        return START_STICKY
    }

    private fun startRamp(durationMinutes: Int) {
        rampJob?.cancel()
        rampJob = rampScope.launch {
            acquireWakeLock(durationMinutes)

            val totalMillis = durationMinutes * 60_000L
            val startRealtime = SystemClock.elapsedRealtime()

            while (isActive) {
                val progress =
                    (SystemClock.elapsedRealtime() - startRealtime).toFloat() / totalMillis
                if (progress >= 1f) {
                    break
                }

                val duty = MIN_DUTY + (1f - MIN_DUTY) * progress * progress
                val onMillis = (PWM_CYCLE_MS * duty).toLong().coerceIn(1L, PWM_CYCLE_MS - 1L)
                val offMillis = PWM_CYCLE_MS - onMillis

                if (!TorchHelper.setTorch(this@SunriseService, true)) {
                    // flash unit busy (e.g. camera in use) - give up quietly,
                    // the ringing screen still fades in its own light
                    break
                }
                delay(onMillis)
                TorchHelper.setTorch(this@SunriseService, false)
                delay(offMillis)
            }

            TorchHelper.setTorch(this@SunriseService, false)
            releaseWakeLock()
            if (isActive) {
                stopSelf()
            }
        }
    }

    private fun stopRamp() {
        rampJob?.cancel()
        rampJob = null
        TorchHelper.setTorch(this, false)
        releaseWakeLock()
    }

    private fun buildNotification(label: String): Notification {
        val channel = NotificationChannel(
            SUNRISE_NOTIFICATION_CHANNEL_ID,
            getString(R.string.sunrise_fade_in),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)

        val title = label.ifEmpty { getString(R.string.sunrise_fade_in) }
        return NotificationCompat.Builder(this, SUNRISE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string.sunrise_service_note))
            .setSmallIcon(R.drawable.ic_sunrise_vector)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock(durationMinutes: Int) {
        if (wakeLock?.isHeld == true) {
            return
        }

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            acquire((durationMinutes + 1) * 60_000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRamp()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}

private const val WAKELOCK_TAG = "org.fossify.clock:sunrise"
