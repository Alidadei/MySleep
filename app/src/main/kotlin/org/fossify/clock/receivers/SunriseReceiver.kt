package org.fossify.clock.receivers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.fossify.clock.R
import org.fossify.clock.activities.SunriseActivity
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.goAsync
import org.fossify.commons.extensions.notificationManager
import org.fossify.clock.helpers.ALARM_ID
import org.fossify.clock.helpers.SUNRISE_DURATION_MIN
import org.fossify.clock.helpers.SUNRISE_NOTIFICATION_CHANNEL_ID
import org.fossify.clock.helpers.SUNRISE_SCREEN_NOTIFICATION_ID
import org.fossify.clock.services.SunriseService

/**
 * Fired by an exact alarm some minutes before the alarm ring: starts the
 * [SunriseService] (steady flashlight) and shows a full-screen intent with the
 * [SunriseActivity] sunrise scene (screen ramping from dark to bright).
 */
class SunriseReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(ALARM_ID, -1)
        if (id == -1) {
            return
        }

        goAsync {
            val alarm = context.dbHelper.getAlarmWithId(id) ?: return@goAsync
            if (!alarm.enableTorch && !alarm.lightOnly) {
                return@goAsync
            }

            val serviceIntent = Intent(context, SunriseService::class.java).apply {
                action = SunriseService.ACTION_START_SUNRISE
                putExtra(ALARM_ID, alarm.id)
                putExtra(SUNRISE_DURATION_MIN, alarm.sunriseMinutes)
            }
            context.startForegroundService(serviceIntent)

            showSunriseScreen(context, alarm.id, alarm.sunriseMinutes, alarm.label)
        }
    }

    private fun showSunriseScreen(
        context: Context,
        alarmId: Int,
        sunriseMinutes: Int,
        label: String,
    ) {
        val channelId = SUNRISE_NOTIFICATION_CHANNEL_ID
        val channel = NotificationChannel(
            channelId,
            context.getString(R.string.sunrise_fade_in),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)
            setBypassDnd(true)
        }
        context.notificationManager.createNotificationChannel(channel)

        val contentIntent = Intent(context, SunriseActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SUNRISE_DURATION_MIN, sunriseMinutes)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            alarmId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = label.ifEmpty { context.getString(R.string.sunrise_fade_in) }
        val notification: Notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.sunrise_service_note))
            .setSmallIcon(R.drawable.ic_sunrise_vector)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .build()

        context.notificationManager.notify(SUNRISE_SCREEN_NOTIFICATION_ID, notification)
    }
}
