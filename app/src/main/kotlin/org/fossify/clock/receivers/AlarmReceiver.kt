package org.fossify.clock.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.delay
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.goAsync
import org.fossify.clock.extensions.hideNotification
import org.fossify.clock.helpers.ALARM_ID
import org.fossify.clock.helpers.AlarmNotificationHelper
import org.fossify.clock.helpers.RingDiagnostics
import org.fossify.clock.helpers.ScreenWaker
import org.fossify.clock.helpers.UPCOMING_ALARM_NOTIFICATION_ID
import org.fossify.commons.helpers.SILENT

private const val WAKELOCK_TAG = "org.fossify.clock:alarm_screen"

/**
 * Receiver responsible for sounding alarms. It is also responsible for hiding the
 * upcoming alarm notification and scheduling the next occurrence.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(ALARM_ID, -1)
        if (id == -1) return

        cancelUpcomingAlarmNotification(context)
        goAsync {
            val alarm = context.dbHelper.getAlarmWithId(id)
            RingDiagnostics.log(
                context,
                "响铃广播到达 vibrate=${alarm?.vibrate} hasSound=${alarm?.soundUri != SILENT}"
            )

            val notificationManager = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as android.app.NotificationManager
            if (notificationManager.isNotificationPolicyAccessGranted &&
                notificationManager.currentInterruptionFilter !=
                android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            ) {
                org.fossify.clock.services.AlarmService.previousInterruptionFilter =
                    notificationManager.currentInterruptionFilter
                notificationManager.setInterruptionFilter(
                    android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                )
                RingDiagnostics.log(context, "检测到勿扰，已临时切回正常模式")
            }

            if (alarm != null) {
                // system-driven ring straight from the receiver: sound loops and
                // the channel vibrates even if the foreground service is blocked
                AlarmNotificationHelper(context).postAlertNotification(alarm)
                ScreenWaker.wake(context, WAKELOCK_TAG)
                try {
                    context.startActivity(
                        Intent(context, org.fossify.clock.activities.AlarmActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(org.fossify.clock.helpers.ALARM_ID, alarm.id)
                        }
                    )
                    RingDiagnostics.log(context, "已尝试直接拉起响铃页")
                } catch (e: Exception) {
                    RingDiagnostics.log(context, "直接拉起响铃页异常: ${e.message}")
                }
                if (alarm.vibrate) {
                    emergencyVibrate(context)
                }
            }

            context.alarmController.onAlarmTriggered(id)
        }
    }

    /** Fallback loop inside the receiver window, in case the service is blocked. */
    private suspend fun emergencyVibrate(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val manager = context.getSystemService(
                    Context.VIBRATOR_MANAGER_SERVICE
                ) as VibratorManager
                val attrs = android.os.VibrationAttributes.Builder()
                    .setUsage(android.os.VibrationAttributes.USAGE_ALARM)
                    .build()
                RingDiagnostics.log(context, "应急振动已调用（闹钟级）")
                repeat(4) {
                    manager.vibrate(
                        CombinedVibration.createParallel(
                            VibrationEffect.createWaveform(longArrayOf(0, 400, 400), 0)
                        ),
                        attrs
                    )
                    delay(1000)
                }
            } else {
                @Suppress("DEPRECATION")
                val vibrator =
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                RingDiagnostics.log(context, "应急振动已调用")
                repeat(6) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 400), 0))
                    delay(1000)
                }
            }
        } catch (e: Exception) {
            RingDiagnostics.log(context, "应急振动异常: ${e.message}")
        }
    }

    private fun cancelUpcomingAlarmNotification(context: Context) {
        context.hideNotification(UPCOMING_ALARM_NOTIFICATION_ID)
    }
}
