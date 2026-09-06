package org.fossify.clock.helpers

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.net.toUri

/**
 * Bedtime relax favorites: curated picks (via [PicksRepository]) plus anything
 * the user adds themselves, and community recommendations with effectiveness
 * ratings. Community entries live in shared preferences as JSON - when the
 * backend lands, this store is the swap point for server sync.
 */

/**
 * A user-submitted recommendation other users can try and rate (1-5, how well
 * it helped them fall asleep). Aggregated by average rating.
 */
data class CommunityPick(
    val id: Long,
    val title: String,
    val url: String,
    var ratings: MutableList<Int>? = null,
    var addedAt: Long = 0,
)
data class RelaxItem(
    val id: Long,
    val title: String,
    val url: String,
    val isCustom: Boolean = false,
    val isLocal: Boolean = false,
)

object RelaxStore {

    private const val PREFS_NAME = "relax_favorites"
    private const val KEY_CUSTOM_ITEMS = "custom_items_json"
    private const val KEY_COMMUNITY_PICKS = "community_picks_json"

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

    fun addCustomItem(context: Context, title: String, url: String, isLocal: Boolean = false) {
        val items = getCustomItems(context)
        items.add(
            RelaxItem(
                id = System.currentTimeMillis(),
                title = title,
                url = url,
                isCustom = true,
                isLocal = isLocal
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

    // ---- community recommendations ----

    fun getCommunityPicks(context: Context): MutableList<CommunityPick> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COMMUNITY_PICKS, null) ?: return mutableListOf()

        return try {
            val type = object : TypeToken<MutableList<CommunityPick>>() {}.type
            Gson().fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun addCommunityPick(context: Context, title: String, url: String) {
        val picks = getCommunityPicks(context)
        picks.add(
            CommunityPick(
                id = System.currentTimeMillis(),
                title = title,
                url = url,
                ratings = mutableListOf(),
                addedAt = System.currentTimeMillis()
            )
        )
        saveCommunityPicks(context, picks)
    }

    fun rateCommunityPick(context: Context, id: Long, rating: Int) {
        val picks = getCommunityPicks(context)
        picks.firstOrNull { it.id == id }?.let { pick ->
            val ratings = pick.ratings ?: mutableListOf<Int>().also { pick.ratings = it }
            ratings.add(rating.coerceIn(1, 5))
            saveCommunityPicks(context, picks)
        }
    }

    private fun saveCommunityPicks(context: Context, picks: List<CommunityPick>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COMMUNITY_PICKS, Gson().toJson(picks))
            .apply()
    }
}
