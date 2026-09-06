package org.fossify.clock.helpers

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.hideNotification
import org.fossify.commons.helpers.isOreoPlus

/**
 * Last-resort ring UI: a full-screen overlay window drawn with the
 * "display over other apps" permission, bypassing activity starts entirely.
 * HyperOS silently swallows background startActivity() calls even when the
 * overlay permission is granted (the MIUI "后台弹出界面" op is separate), so
 * when the real ring page never arrives, this fallback still gives the user
 * a visible, tappable ringing screen - above the lock screen too.
 */
object RingOverlayHelper {

    private var currentView: View? = null

    fun isShowing() = currentView != null

    /** Marker set by the real ring activities so the fallback knows to stay away. */
    object RingUiTracker {
        @Volatile
        var ringingActivityShowing = false
    }

    /**
     * Schedules the fallback check: if no ring activity made it to the
     * foreground within [delayMs] of the startActivity attempt, draw the
     * overlay instead.
     */
    fun scheduleFallback(context: Context, delayMs: Long = 2000L, show: (Context) -> Unit) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (!RingUiTracker.ringingActivityShowing && !isShowing()) {
                    show(appContext)
                }
            } catch (e: Exception) {
            }
        }, delayMs)
    }

    fun showAlarmOverlay(context: Context, alarm: org.fossify.clock.models.Alarm) {
        val label = alarm.label.ifEmpty { context.getString(org.fossify.commons.R.string.alarm) }
        val timeText = try {
            context.getFormattedTimeText()
        } catch (e: Exception) {
            ""
        }
        show(
            context,
            title = label,
            subtitle = timeText.ifEmpty { "闹钟响铃中" },
            hint = "响铃页被系统拦截，这是兜底界面。建议：设置 → 一键开启所需权限，开启“后台弹出界面”和“锁屏显示”",
            showSnooze = true,
            snoozeMinutes = context.config.snoozeTime,
            onStop = {
                try {
                    TorchHelper.setTorch(context, false)
                    context.alarmController.stopAlarm(alarm.id)
                    RingDiagnostics.log(context, "悬浮窗兜底：闹钟已停止")
                } catch (e: Exception) {
                }
            },
            onSnooze = {
                try {
                    TorchHelper.setTorch(context, false)
                    context.alarmController.snoozeAlarm(alarm.id, context.config.snoozeTime)
                    RingDiagnostics.log(context, "悬浮窗兜底：已贪睡 ${context.config.snoozeTime} 分钟")
                } catch (e: Exception) {
                }
            }
        )
    }

    fun showTimerOverlay(context: Context, timerId: Int, label: String?) {
        show(
            context,
            title = label?.ifEmpty { null } ?: context.getString(org.fossify.clock.R.string.timer),
            subtitle = "计时结束",
            hint = "响铃页被系统拦截，这是兜底界面。建议：设置 → 一键开启所需权限，开启“后台弹出界面”和“锁屏显示”",
            showSnooze = false,
            onStop = {
                try {
                    stopVibration(context)
                    TorchHelper.setTorch(context, false)
                    if (timerId != -1) {
                        context.hideNotification(timerId)
                    }
                    RingDiagnostics.log(context, "悬浮窗兜底：计时器已停止")
                } catch (e: Exception) {
                }
            }
        )
    }

    fun dismiss(context: Context) {
        val appContext = context.applicationContext
        runOnMain {
            val view = currentView ?: return@runOnMain
            currentView = null
            try {
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
            } catch (e: Exception) {
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post { block() }
        }
    }

    private fun stopVibration(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                manager.defaultVibrator.cancel()
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                vibrator.cancel()
            }
        } catch (e: Exception) {
        }
    }

    private fun Context.getFormattedTimeText(): String {
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = now.get(java.util.Calendar.MINUTE)
        return String.format("%02d:%02d", hour, minute)
    }

    private fun show(
        context: Context,
        title: String,
        subtitle: String,
        hint: String,
        showSnooze: Boolean,
        snoozeMinutes: Int = 0,
        onStop: () -> Unit,
        onSnooze: (() -> Unit)? = null
    ) {
        runOnMain {
            if (currentView != null) {
                return@runOnMain
            }
            if (!android.provider.Settings.canDrawOverlays(context)) {
                RingDiagnostics.log(context, "兜底失败：悬浮窗权限未开启")
                return@runOnMain
            }

            try {
                val density = context.resources.displayMetrics.density
                fun dp(v: Int) = (v * density + 0.5f).toInt()
                fun sp(v: Int) = (v * context.resources.displayMetrics.scaledDensity + 0.5f).toInt()

                val root = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(0xF20D0F1A.toInt())
                    setClickable(true)
                    setOnTouchListener { _, _ -> true }
                }

                val titleView = TextView(context).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 26f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(dp(24), 0, dp(24), 0)
                }
                val subtitleView = TextView(context).apply {
                    text = subtitle
                    setTextColor(0xB3FFFFFF.toInt())
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(dp(24), dp(6), dp(24), 0)
                }

                val stopButton = TextView(context).apply {
                    text = "停止"
                    setTextColor(0xFF241E0E.toInt())
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(0xFFE7C97F.toInt())
                    }
                }
                val stopParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)).apply {
                    setMargins(dp(48), 0, dp(48), 0)
                }
                stopButton.layoutParams = stopParams
                stopButton.setOnClickListener {
                    dismiss(context)
                    onStop()
                }

                root.addView(titleView)
                root.addView(subtitleView)
                root.addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(44)) })
                root.addView(stopButton)

                if (showSnooze && onSnooze != null) {
                    root.addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(18)) })
                    val snoozeButton = TextView(context).apply {
                        text = "贪睡 $snoozeMinutes 分钟"
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        gravity = Gravity.CENTER
                        setPadding(dp(28), dp(10), dp(28), dp(10))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(18).toFloat()
                            setColor(0x33FFFFFF)
                        }
                    }
                    snoozeButton.setOnClickListener {
                        dismiss(context)
                        onSnooze()
                    }
                    root.addView(snoozeButton)
                }

                root.addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(30)) })
                root.addView(
                    TextView(context).apply {
                        text = hint
                        setTextColor(0x80FFFFFF.toInt())
                        textSize = 12f
                        gravity = Gravity.CENTER
                        setPadding(dp(32), 0, dp(32), 0)
                    }
                )

                val params = WindowManager.LayoutParams().apply {
                    type = if (isOreoPlus()) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    format = PixelFormat.TRANSLUCENT
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                }

                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.addView(root, params)
                currentView = root
                RingDiagnostics.log(context, "悬浮窗兜底响铃界面已显示")
            } catch (e: Exception) {
                RingDiagnostics.log(context, "悬浮窗兜底异常: ${e.message}")
            }
        }
    }
}
