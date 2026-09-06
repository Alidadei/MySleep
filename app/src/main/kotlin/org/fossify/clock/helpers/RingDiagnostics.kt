package org.fossify.clock.helpers

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight ring-path diagnostic log, surfaced in the vibration self-test
 * screen. Records what actually happened at ring time so failures can be
 * pinpointed on real devices (which OEM ROM layer swallowed what).
 */
object RingDiagnostics {

    private const val PREFS = "ring_diagnostics"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 8

    fun log(context: Context, line: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val type = object : TypeToken<MutableList<String>>() {}.type
            val entries: MutableList<String> = prefs.getString(KEY, null)
                ?.let { Gson().fromJson<MutableList<String>>(it, type) }
                ?: mutableListOf()

            val timestamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            entries.add(0, "$timestamp  $line")
            while (entries.size > MAX_ENTRIES) {
                entries.removeAt(entries.size - 1)
            }

            prefs.edit().putString(KEY, Gson().toJson(entries)).apply()
        } catch (e: Exception) {
        }
    }

    fun getLog(context: Context): List<String> {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val type = object : TypeToken<MutableList<String>>() {}.type
            prefs.getString(KEY, null)
                ?.let { Gson().fromJson<MutableList<String>>(it, type) }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
