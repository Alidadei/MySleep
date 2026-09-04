package org.fossify.clock.helpers

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.net.toUri

/**
 * Bedtime relax favorites: a small built-in curated list ("AI picks") plus anything the
 * user adds themselves. Custom entries live in shared preferences as JSON.
 */
data class RelaxItem(
    val id: Long,
    val title: String,
    val url: String,
    val isCustom: Boolean = false,
)

object RelaxStore {

    private const val PREFS_NAME = "relax_favorites"
    private const val KEY_CUSTOM_ITEMS = "custom_items_json"

    fun getBuiltIns(): List<RelaxItem> = listOf(
        RelaxItem(
            id = -1,
            title = "myNoise · 自然声景",
            url = "https://mynoise.net/"
        ),
        RelaxItem(
            id = -2,
            title = "Noisli · 雨声白噪音混合",
            url = "https://www.noisli.com/"
        ),
        RelaxItem(
            id = -3,
            title = "LibriVox · 中文公版有声书",
            url = "https://librivox.org/search?primary_key_language=chinese"
        ),
        RelaxItem(
            id = -4,
            title = "雨声助眠 · 视频搜索",
            url = "https://www.youtube.com/results?search_query=rain+sounds+for+sleeping+8+hours"
        ),
        RelaxItem(
            id = -5,
            title = "睡前故事 · 视频搜索",
            url = "https://www.youtube.com/results?search_query=%E7%9D%A1%E5%89%8D%E6%95%85%E4%BA%8B"
        ),
    )

    fun getCustomItems(context: Context): MutableList<RelaxItem> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_ITEMS, null) ?: return mutableListOf()

        return try {
            val type = object : TypeToken<MutableList<RelaxItem>>() {}.type
            Gson().fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun addCustomItem(context: Context, title: String, url: String) {
        val items = getCustomItems(context)
        items.add(
            RelaxItem(
                id = System.currentTimeMillis(),
                title = title,
                url = url,
                isCustom = true
            )
        )
        saveItems(context, items)
    }

    fun removeCustomItem(context: Context, id: Long) {
        val items = getCustomItems(context)
        items.removeAll { it.id == id }
        saveItems(context, items)
    }

    private fun saveItems(context: Context, items: List<RelaxItem>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_ITEMS, Gson().toJson(items))
            .apply()
    }

    fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    fun isValidUrl(url: String): Boolean = try {
        normalizeUrl(url).toUri().host != null
    } catch (e: Exception) {
        false
    }
}
