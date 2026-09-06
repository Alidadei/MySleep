package org.fossify.clock.services

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.dbHelper
import org.fossify.commons.extensions.notificationManager
import org.fossify.clock.helpers.ALARM_ALERT_NOTIFICATION_ID
import org.fossify.clock.helpers.ALARM_ID
import org.fossify.clock.helpers.ALARM_NOTIFICATION_ID
import org.fossify.clock.helpers.AlarmNotificationHelper
import org.fossify.clock.helpers.TorchHelper
import org.fossify.clock.models.Alarm
import org.fossify.commons.helpers.SILENT
import org.fossify.commons.helpers.ensureBackgroundThread
import kotlin.time.Duration.Companion.seconds

/**
 * Service responsible for ringing alarms: light, sound and vibration.
 *
 * Ring engine: the alarm sound and the initial vibration are carried by a
 * dedicated notification channel (system-driven, looping via FLAG_INSISTENT).
 * OEM power management (MIUI/HyperOS and friends) suppresses media playback and
 * vibration issued from app processes, but it never blocks its own notification
 * infrastructure. The in-process vibrator only adds the immediate kick.
 */
class AlarmService : Service() {

    companion object {
        private const val VIBRATION_PATTERN_TIMING = 500L
        private const val TORCH_ASSERT_INTERVAL_MS = 2000L

        const val ACTION_START_ALARM = "org.fossify.clock.START_ALARM"
        const val ACTION_STOP_ALARM = "org.fossify.clock.STOP_ALARM"
    }

    private var activeAlarm: Alarm? = null
    private var vibrator: Vibrator? = null

    private lateinit var notificationHelper: AlarmNotificationHelper

    private val autoDismissHandler = Handler(Looper.getMainLooper())
    private val torchAssertHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        notificationHelper = AlarmNotificationHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_ALARM
        val alarmId = intent?.getIntExtra(ALARM_ID, -1) ?: -1
        val newAlarm = applicationContext.dbHelper.getAlarmWithId(alarmId)
        if (alarmId == -1 || newAlarm == null) {
            stopSelfIfIdle()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START_ALARM -> startNewAlarm(newAlarm)
            ACTION_STOP_ALARM -> stopActiveAlarm(alarmId)
            else -> throw IllegalArgumentException("Unknown action: $action")
        }

        return START_STICKY
    }

    private fun startNewAlarm(newAlarm: Alarm) {
        startForeground(
            ALARM_NOTIFICATION_ID,
            notificationHelper.buildActiveAlarmNotification(newAlarm)
        )

        val currentAlarm = activeAlarm
        activeAlarm = newAlarm

        if (currentAlarm?.id == newAlarm.id) {
            // No action needed, same alarm
            return
        }

        val replaceActiveAlarm = currentAlarm != null
        if (replaceActiveAlarm) {
            stopRingAndCleanup()
            notificationHelper.postReplacedAlarmNotification(currentAlarm!!)
            alarmController.stopAlarm(currentAlarm.id)
        }

        startAlarmEffects(newAlarm)
        startAutoDismiss(config.alarmMaxReminderSecs)

        ensureBackgroundThread {
            applicationContext.dbHelper.openSleepRecord(newAlarm)
        }
    }

    private fun stopActiveAlarm(alarmIdToStop: Int) {
        if (activeAlarm?.id == alarmIdToStop) {
            stopSelf()
        } else {
            stopSelfIfIdle()
        }
    }

    private fun startAlarmEffects(alarm: Alarm) {
        // light wake-up: turn the flashlight on for the whole ring, even in silent mode
        if (alarm.usesLightWake()) {
            startTorchWithRetry()
        }

        // system-driven ring: the alert notification loops the alarm sound and
        // vibrates through its channel, immune to OEM background restrictions
        if (!alarm.lightOnly || alarm.vibrate) {
            notificationHelper.postAlertNotification(alarm)
        }

        if (alarm.lightOnly) {
            // light-only alarm: wake the user with light instead of sound
            return
        }

        if (alarm.vibrate) {
            startVibration()
        }
    }

    /**
     * The flash unit can be momentarily unavailable (camera in use, system
     * toggling), so keep asserting the torch while the alarm rings.
     */
    private fun startTorchWithRetry() {
        torchAssertHandler.removeCallbacksAndMessages(null)
        val assertRunnable = object : Runnable {
            override fun run() {
                TorchHelper.setTorch(this@AlarmService, true)
                torchAssertHandler.postDelayed(this, TORCH_ASSERT_INTERVAL_MS)
            }
        }
        TorchHelper.setTorch(this, true)
        torchAssertHandler.postDelayed(assertRunnable, TORCH_ASSERT_INTERVAL_MS)
    }

    /** Immediate in-process vibration kick on top of the channel vibration. */
    private fun startVibration() {
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }

            this.vibrator = vibrator
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, VIBRATION_PATTERN_TIMING, VIBRATION_PATTERN_TIMING), 0
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startAutoDismiss(durationSecs: Int) {
        val alarmId = activeAlarm?.id ?: return
        autoDismissHandler.postDelayed({
            val missedAlarm = activeAlarm
            if (missedAlarm?.id == alarmId) {
                notificationHelper.postMissedAlarmNotification(missedAlarm)
                alarmController.stopAlarm(alarmId)
            }
        }, durationSecs.seconds.inWholeMilliseconds)
    }

    private fun stopRingAndCleanup() {
        notificationManager.cancel(ALARM_ALERT_NOTIFICATION_ID)
        vibrator?.cancel()
        vibrator = null
        TorchHelper.setTorch(this, false)

        // Clear any scheduled auto-dismiss or torch assert messages
        autoDismissHandler.removeCallbacksAndMessages(null)
        torchAssertHandler.removeCallbacksAndMessages(null)
    }

    private fun stopSelfIfIdle() {
        if (activeAlarm == null) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopRingAndCleanup()
    }

    override fun onBind(intent: Intent?) = null
}
