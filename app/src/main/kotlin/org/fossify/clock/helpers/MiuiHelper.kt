package org.fossify.clock.helpers

import android.content.Context
import android.os.Build

/**
 * MIUI/HyperOS gate their own background-start restrictions behind MIUI-only
 * app ops that exist on top of the standard overlay/full-screen-intent
 * permissions. HyperOS silently swallows background startActivity() calls
 * unless the "后台弹出界面" op is granted, and hides overlays on the lock
 * screen unless "锁屏显示" is granted - neither state is visible through the
 * standard Android APIs, so we read the raw op codes.
 */
object MiuiHelper {

    const val OP_AUTO_START = 10018
    const val OP_BACKGROUND_START = 10020
    const val OP_LOCKSCREEN_DISPLAY = 10021

    fun isMiui(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: return false
        return manufacturer in setOf("xiaomi", "redmi", "poco", "blackshark")
    }

    /** "ON" / "OFF" / "默认" / raw mode; null when the op doesn't exist (non-MIUI ROMs). */
    fun opState(context: Context, op: Int): String? {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val check = android.app.AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val result = check.invoke(
                appOps, op, context.applicationInfo.uid, context.packageName
            ) as Int
            when (result) {
                android.app.AppOpsManager.MODE_ALLOWED -> "ON"
                android.app.AppOpsManager.MODE_IGNORED -> "OFF"
                android.app.AppOpsManager.MODE_ERRORED -> "OFF(errored)"
                android.app.AppOpsManager.MODE_DEFAULT -> "默认"
                else -> result.toString()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Deep link into the MIUI per-app permission editor (其他权限 page). */
    fun getPermissionEditorIntent(context: Context): android.content.Intent? {
        return try {
            android.content.Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", context.packageName)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            null
        }
    }
}
