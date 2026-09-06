package org.fossify.clock.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.timerDb
import org.fossify.clock.extensions.goAsync
import org.fossify.clock.helpers.RingDiagnostics
import org.fossify.clock.helpers.TIMER_ID
import org.fossify.clock.models.TimerState
import org.greenrobot.eventbus.EventBus
import org.fossify.clock.models.TimerEvent

/**
 * Exact-alarm backup for timer expiration: fires even when the countdown
 * service gets killed by aggressive OEM background management, so the timer
 * still rings (notification + light alarm effects through the Finish event).
 */
class TimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val timerId = intent.getIntExtra(TIMER_ID, -1)
        if (timerId == -1) {
            return
        }

        goAsync {
            val timer = context.timerDb.getTimer(timerId)
            val running = timer?.state is TimerState.Running
            RingDiagnostics.log(context, "计时器兜底闹钟触发 running=$running")
            if (running) {
                EventBus.getDefault().post(TimerEvent.Finish(timerId, 0))
            }
        }
    }
}
