package org.fossify.clock.helpers

import android.content.Context
import android.os.PowerManager

/**
 * Forces the screen on (even over the lock screen) for a short window, so
 * alarm/timer notifications are seen immediately. Uses the deprecated
 * ACQUIRE_CAUSES_WAKEUP combo, which is still the reliable way to force
 * screen-on when OEM ROMs block activity launching from the background.
 */
object ScreenWaker {

    private const val TIMEOUT_MS = 30_000L

    fun wake(context: Context, tag: String) {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                tag
            )
            wakeLock.acquire(TIMEOUT_MS)
            RingDiagnostics.log(context, "亮屏已触发 (${tag.substringAfterLast('/')})")
        } catch (e: Exception) {
            RingDiagnostics.log(context, "亮屏异常: ${e.message}")
        }
    }
}
