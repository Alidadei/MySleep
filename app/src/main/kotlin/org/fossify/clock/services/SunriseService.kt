package org.fossify.clock.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
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
 * Keeps the flashlight steadily on during the minutes before the alarm rings.
 * The smooth "dark to bright" sunrise itself comes from [org.fossify.clock.activities.SunriseActivity],
 * which ramps the screen brightness (the LED has no dimming levels - modulating it
 * would only produce visible flicker).
 */
class SunriseService : Service() {

    companion object {
        const val ACTION_START_SUNRISE = "org.fossify.clock.START_SUNRISE"
        const val ACTION_STOP_SUNRISE = "org.fossify.clock.STOP_SUNRISE"

        const val DEFAULT_SUNRISE_MINUTES = 10
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var holdJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SUNRISE -> {
                stopHold()
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
                startHold(durationMinutes)
            }
        }

        return START_STICKY
    }

    /** Holds the torch steadily on for the whole sunrise window. */
    private fun startHold(durationMinutes: Int) {
        holdJob?.cancel()
        holdJob = scope.launch {
            acquireWakeLock(durationMinutes)

            TorchHelper.setTorch(this@SunriseService, true)
            val endAt = System.currentTimeMillis() + durationMinutes * 60_000L
            while (isActive && System.currentTimeMillis() < endAt) {
                if (!TorchHelper.setTorch(this@SunriseService, true)) {
                    // flash unit got busy (camera in use) - nothing to hold anymore
                    break
                }
                delay(2000)
            }

            TorchHelper.setTorch(this@SunriseService, false)
            releaseWakeLock()
            if (isActive) {
                stopSelf()
            }
        }
    }

    private fun stopHold() {
        holdJob?.cancel()
        holdJob = null
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
        stopHold()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}

private const val WAKELOCK_TAG = "org.fossify.clock:sunrise"
