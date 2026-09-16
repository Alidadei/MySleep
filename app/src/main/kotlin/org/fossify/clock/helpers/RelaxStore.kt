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
    var type: String? = null,
)
data class RelaxItem(
    val id: Long,
    val title: String,
    val url: String,
    val isCustom: Boolean = false,
    val isLocal: Boolean = false,
    val type: String? = null,
    val sample: Boolean = false,
    /** Sample rating placeholder until real user ratings land; rendered with
     *  community_rating_fmt so it localizes ("★ 4.7 · 215 ratings" / "次评价"). */
    val sampleRatingAvg: Double? = null,
    val sampleRatingCount: Int? = null,
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

    /**
     * Cross-platform dedup key, aligned verbatim with the website's urlKey()
     * (cyberSleepCommunity/index.html): normalizeUrl → host minus "www."
     * (lowercased, default port dropped) → path minus trailing slashes →
     * keep query, drop fragment. Unparseable input degrades to trimmed
     * lowercase original, same as the JS catch branch.
     */
    fun urlKey(url: String): String {
        val normalized = normalizeUrl(url)
        return try {
            val uri = normalized.toUri()
            if (uri.host.isNullOrBlank()) return normalized.trim().lowercase()
            var host = uri.host!!.lowercase().removePrefix("www.")
            val port = uri.port
            val defaultPort = when (uri.scheme?.lowercase()) {
                "https" -> 443
                "http" -> 80
                else -> -1
            }
            if (port != -1 && port != defaultPort) {
                host += ":$port"
            }
            val path = (uri.path ?: "").replace(Regex("/+$"), "")
            val encodedQuery: String? = uri.encodedQuery
            val query = if (encodedQuery != null) "?$encodedQuery" else ""
            host + path + query
        } catch (e: Exception) {
            normalized.trim().lowercase()
        }
    }

    /** True when a favorite with the same urlKey already exists. */
    fun isUrlFavorited(context: Context, url: String): Boolean {
        val key = urlKey(url)
        return getCustomItems(context).any { urlKey(it.url) == key }
    }

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

    fun updateCustomItem(context: Context, id: Long, title: String, url: String) {
        val items = getCustomItems(context)
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            val old = items[index]
            items[index] = old.copy(title = title, url = url)
            saveItems(context, items)
        }
    }

    /** Merge imported favorites (same ids or same urlKeys are skipped). Returns added count. */
    fun mergeCustomItems(context: Context, imported: List<RelaxItem>): Int {
        val items = getCustomItems(context)
        val knownIds = items.map { it.id }.toSet()
        val knownUrlKeys = items.map { urlKey(it.url) }.toSet()
        val fresh = imported.filter {
            it.title.isNotBlank() && it.url.isNotBlank() &&
                it.id !in knownIds && urlKey(it.url) !in knownUrlKeys
        }
        if (fresh.isNotEmpty()) {
            saveItems(context, items + fresh)
        }
        return fresh.size
    }

    /** Merge imported community picks (same ids or same urlKeys skipped). Returns added count. */
    fun mergeCommunityPicks(context: Context, imported: List<CommunityPick>): Int {
        val picks = getCommunityPicks(context)
        val knownIds = picks.map { it.id }.toSet()
        val knownUrlKeys = picks.map { urlKey(it.url) }.toSet()
        val fresh = imported.filter {
            it.title.isNotBlank() && it.url.isNotBlank() &&
                it.id !in knownIds && urlKey(it.url) !in knownUrlKeys
        }
        if (fresh.isNotEmpty()) {
            saveCommunityPicks(context, picks + fresh)
        }
        return fresh.size
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

    fun addCommunityPick(context: Context, title: String, url: String, type: String? = null) {
        val picks = getCommunityPicks(context)
        picks.add(
            CommunityPick(
                id = System.currentTimeMillis(),
                title = title,
                url = url,
                ratings = mutableListOf(),
                addedAt = System.currentTimeMillis(),
                type = type
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
