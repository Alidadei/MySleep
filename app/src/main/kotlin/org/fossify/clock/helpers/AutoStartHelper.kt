package org.fossify.clock.helpers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Locale

/**
 * Autostart settings page, component-resolved per aggressive OEM. These pages
 * have no public state to query, so we can only deep-link into them.
 */
object AutoStartHelper {

    fun getIntent(context: Context): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.getDefault())
        val component = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )

            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )

            manufacturer.contains("oppo") || manufacturer.contains("realme") ->
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )

            manufacturer.contains("vivo") || manufacturer.contains("iqoo") ->
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )

            manufacturer.contains("meizu") ->
                ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")

            else -> null
        }

        return component?.let {
            Intent().apply {
                this.component = it
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
