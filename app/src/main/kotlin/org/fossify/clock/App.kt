package org.fossify.clock

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.getOpenTimerTabIntent
import org.fossify.clock.extensions.getTimerNotification
import org.fossify.clock.extensions.hideNotification
import org.fossify.clock.extensions.timerHelper
import org.fossify.clock.models.TimerEvent
import org.fossify.clock.models.TimerState
import org.fossify.clock.extensions.cancelTimerFinish
import org.fossify.clock.extensions.scheduleTimerFinish
import org.fossify.clock.helpers.ScreenWaker
import org.fossify.clock.helpers.TorchHelper
import org.fossify.clock.services.TimerStopService
import org.fossify.clock.services.startTimerService
import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.showErrorToast
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class App : FossifyApp(), LifecycleObserver {

    private var countDownTimers = mutableMapOf<Int, CountDownTimer>()

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        EventBus.getDefault().register(this)
    }

    override fun onTerminate() {
        EventBus.getDefault().unregister(this)
        super.onTerminate()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    private fun onAppBackgrounded() {
        timerHelper.getTimers { timers ->
            if (timers.any { it.state is TimerState.Running }) {
                startTimerService(this)
            }
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    private fun onAppForegrounded() {
        EventBus.getDefault().post(TimerStopService)
        timerHelper.getTimers { timers ->
            val runningTimers = timers.filter { it.state is TimerState.Running }
            runningTimers.forEach { timer ->
                if (countDownTimers[timer.id] == null) {
                    EventBus.getDefault().post(
                        TimerEvent.Start(
                            timerId = timer.id!!,
                            duration = (timer.state as TimerState.Running).tick
                        )
                    )
                }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Reset) {
        updateTimerState(event.timerId, TimerState.Idle)
        countDownTimers[event.timerId]?.cancel()
        cancelTimerFinish(event.timerId)
        TorchHelper.setTorch(this, false)
        stopAlarmClassVibration()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Delete) {
        countDownTimers[event.timerId]?.cancel()
        timerHelper.deleteTimer(event.timerId) {
            EventBus.getDefault().post(TimerEvent.Refresh)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Start) {
        val countDownTimer = object : CountDownTimer(event.duration, 1000) {
            override fun onTick(tick: Long) {
                updateTimerState(event.timerId, TimerState.Running(event.duration, tick))
            }

            override fun onFinish() {
                EventBus.getDefault().post(TimerEvent.Finish(event.timerId, event.duration))
                EventBus.getDefault().post(TimerStopService)
            }
        }.start()
        countDownTimers[event.timerId] = countDownTimer
        scheduleTimerFinish(event.timerId, event.duration)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Finish) {
        cancelTimerFinish(event.timerId)
        timerHelper.getTimer(event.timerId) { timer ->
            val pendingIntent = getOpenTimerTabIntent(event.timerId)
            val notification = getTimerNotification(timer, pendingIntent)

            try {
                notificationManager.notify(event.timerId, notification)
            } catch (e: Exception) {
                showErrorToast(e)
            }

            updateTimerState(event.timerId, TimerState.Finished)

            // light-alarm timers wake the user with the flashlight
            if (timer.lightAlarm && TorchHelper.hasFlash(this)) {
                TorchHelper.setTorch(this, true)
                ScreenWaker.wake(this, "org.fossify.clock:timer_light")
            }
            if (timer.vibrate) {
                startAlarmClassVibration()
            }
            org.fossify.clock.activities.TimerAlarmActivity.launch(this, event.timerId)
            Handler(Looper.getMainLooper()).postDelayed({
                hideNotification(event.timerId)
                TorchHelper.setTorch(this, false)
                stopAlarmClassVibration()
            }, config.timerMaxReminderSecs * 1000L)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Pause) {
        cancelTimerFinish(event.timerId)
        timerHelper.getTimer(event.timerId) { timer ->
            updateTimerState(
                event.timerId,
                TimerState.Paused(event.duration, (timer.state as TimerState.Running).tick)
            )
            countDownTimers[event.timerId]?.cancel()
        }
    }

    /** Alarm-class vibration (USAGE_ALARM) - the tier HyperOS does not suppress. */
    private fun startAlarmClassVibration() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                manager.vibrate(
                    android.os.CombinedVibration.createParallel(
                        VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0)
                    ),
                    android.os.VibrationAttributes.Builder()
                        .setUsage(android.os.VibrationAttributes.USAGE_ALARM)
                        .build()
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0))
            }
        } catch (e: Exception) {
        }
    }

    private fun stopAlarmClassVibration() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                manager.defaultVibrator.cancel()
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                vibrator.cancel()
            }
        } catch (e: Exception) {
        }
    }

    private fun updateTimerState(timerId: Int, state: TimerState) {
        timerHelper.getTimer(timerId) { timer ->
            val newTimer = timer.copy(state = state)
            if (newTimer.oneShot && state is TimerState.Idle) {
                timerHelper.deleteTimer(newTimer.id!!) {
                    EventBus.getDefault().post(TimerEvent.Refresh)
                }
            } else {
                timerHelper.insertOrUpdateTimer(newTimer) {
                    EventBus.getDefault().post(TimerEvent.Refresh)
                }
            }
        }
    }
}
