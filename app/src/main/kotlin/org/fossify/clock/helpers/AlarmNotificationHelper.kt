package org.fossify.clock.helpers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import org.fossify.commons.helpers.SILENT
import org.fossify.clock.R
import org.fossify.clock.activities.AlarmActivity
import org.fossify.clock.extensions.getFormattedTime
import org.fossify.clock.extensions.getOpenAlarmTabIntent
import org.fossify.clock.extensions.getSnoozePendingIntent
import org.fossify.clock.extensions.getStopAlarmPendingIntent
import org.fossify.clock.models.Alarm
import org.fossify.commons.extensions.notificationManager

/**
 * Helper class to handle alarm notifications in the app.
 * This includes creating notification channels, building notifications for active alarms,
 * and posting notifications for missed or replaced alarms.
 */
class AlarmNotificationHelper(private val context: Context) {

    /**
     * Builds and returns the active alarm notification to be shown in the foreground service.
     */
    fun buildActiveAlarmNotification(alarm: Alarm): Notification {
        val channelId = ALARM_NOTIFICATION_CHANNEL_ID
        val channel = NotificationChannel(
            channelId,
            context.getString(org.fossify.commons.R.string.alarm),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setBypassDnd(true)
            setSound(null, null)
        }

        context.notificationManager.createNotificationChannel(channel)

        val contentTitle = alarm.label.ifEmpty {
            context.getString(org.fossify.commons.R.string.alarm)
        }

        val contentText = context.getFormattedTime(
            passedSeconds = alarm.timeInMinutes * 60,
            showSeconds = false,
            makeAmPmSmaller = false
        )

        val reminderIntent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ALARM_ID, alarm.id)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, alarm.id, reminderIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = context.getStopAlarmPendingIntent(alarm)
        val snoozeIntent = context.getSnoozePendingIntent(alarm)

        return NotificationCompat.Builder(context, channelId)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_alarm_vector)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
            .addAction(
                org.fossify.commons.R.drawable.ic_snooze_vector,
                context.getString(org.fossify.commons.R.string.snooze),
                snoozeIntent
            )
            .addAction(
                org.fossify.commons.R.drawable.ic_cross_vector,
                context.getString(org.fossify.commons.R.string.dismiss),
                dismissIntent
            )
            .setDeleteIntent(dismissIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingIntent, true)
            .build()
    }

    /**
     * Posts the system-driven alarm alert: the channel carries the alarm sound
     * (looped via FLAG_INSISTENT) and the vibration pattern, so ringing is
     * executed by the OS itself and survives OEM background restrictions.
     */
    fun postAlertNotification(alarm: Alarm) {
        val channelId = getOrCreateAlertChannel(alarm)
        val contentTitle = alarm.label.ifEmpty {
            context.getString(org.fossify.commons.R.string.alarm)
        }
        val contentText = context.getFormattedTime(
            passedSeconds = alarm.timeInMinutes * 60,
            showSeconds = false,
            makeAmPmSmaller = false
        )
        val reminderIntent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ALARM_ID, alarm.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, alarm.id, reminderIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_alarm_vector)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(
                org.fossify.commons.R.drawable.ic_snooze_vector,
                context.getString(org.fossify.commons.R.string.snooze),
                context.getSnoozePendingIntent(alarm)
            )
            .addAction(
                org.fossify.commons.R.drawable.ic_cross_vector,
                context.getString(org.fossify.commons.R.string.dismiss),
                context.getStopAlarmPendingIntent(alarm)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        RingDiagnostics.log(context, "系统警报通知已发布 vibrate=${alarm.vibrate} sound=${alarm.soundUri != SILENT}")
        context.notificationManager.notify(ALARM_ALERT_NOTIFICATION_ID, notification)
    }

    /** Fresh channel per sound/vibrate combo so updated params always apply. */
    private fun getOrCreateAlertChannel(alarm: Alarm): String {
        val hasSound = !alarm.lightOnly && alarm.soundUri != SILENT
        val channelId = "alarm_alert_${(alarm.soundUri to alarm.vibrate).hashCode()}"

        val channel = NotificationChannel(
            channelId,
            context.getString(org.fossify.commons.R.string.alarm),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setBypassDnd(true)
            if (hasSound) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(alarm.soundUri.toUri(), audioAttributes)
            } else {
                setSound(null, null)
            }
            if (alarm.vibrate) {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 500)
            } else {
                enableVibration(false)
            }
            enableLights(true)
        }

        context.notificationManager.createNotificationChannel(channel)
        return channelId
    }

    /**
     * Creates the missed alarm notification channel.
     */
    private fun createMissedAlarmNotificationChannel() {
        val channel = NotificationChannel(
            MISSED_ALARM_NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.missed_alarm),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setSound(null, null)
        }

        context.notificationManager.createNotificationChannel(channel)
    }

    /**
     * Posts a notification for a missed alarm (auto-dismissed).
     */
    fun postMissedAlarmNotification(missedAlarm: Alarm) {
        createMissedAlarmNotificationChannel()
        val replacedTime = context.getFormattedTime(
            passedSeconds = missedAlarm.timeInMinutes * 60,
            showSeconds = false,
            makeAmPmSmaller = false
        )
        val contentIntent = context.getOpenAlarmTabIntent()
        val notification = NotificationCompat.Builder(context, MISSED_ALARM_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.missed_alarm))
            .setContentText(context.getString(R.string.alarm_timed_out))
            .setContentIntent(contentIntent)
            .setSubText(replacedTime)
            .setSmallIcon(R.drawable.ic_alarm_off_vector)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setShowWhen(false)
            .setAutoCancel(true)
            .build()

        context.notificationManager.notify(
            MISSED_ALARM_NOTIFICATION_TAG,
            missedAlarm.id,
            notification
        )
    }

    /**
     * Posts a notification for a replaced alarm (when a new alarm starts while another is active).
     */
    fun postReplacedAlarmNotification(replacedAlarm: Alarm) {
        createMissedAlarmNotificationChannel()

        val replacedTime = context.getFormattedTime(
            passedSeconds = replacedAlarm.timeInMinutes * 60,
            showSeconds = false,
            makeAmPmSmaller = false
        )
        val contentIntent = context.getOpenAlarmTabIntent()
        val notification = NotificationCompat.Builder(context, MISSED_ALARM_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.missed_alarm))
            .setContentText(context.getString(R.string.replaced_by_another_alarm))
            .setContentIntent(contentIntent)
            .setSubText(replacedTime)
            .setSmallIcon(R.drawable.ic_alarm_off_vector)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setShowWhen(false)
            .setAutoCancel(true)
            .build()

        context.notificationManager.notify(
            MISSED_ALARM_NOTIFICATION_TAG,
            replacedAlarm.id,
            notification
        )
    }
}
