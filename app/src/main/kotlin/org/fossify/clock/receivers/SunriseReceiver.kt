package org.fossify.clock.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.goAsync
import org.fossify.clock.helpers.ALARM_ID
import org.fossify.clock.helpers.SUNRISE_DURATION_MIN
import org.fossify.clock.services.SunriseService

/**
 * Fired by an exact alarm some minutes before the alarm ring, starts the
 * [SunriseService] which fades the flashlight in.
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
        }
    }
}
