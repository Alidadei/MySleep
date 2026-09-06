package org.fossify.clock.helpers

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Curated "sleep picks" provider - the customization/extension point for the
 * featured recommendations list.
 *
 * Owner customization: edit `app/src/main/assets/relax_picks.json`
 * (plain list of {title, url}); it replaces the built-in defaults entirely.
 *
 * Future extension: swap this repository for a remote implementation
 * (user-submitted recommendations + effectiveness ratings aggregated server
 * side) - the UI only depends on [getPicks] returning [RelaxItem]s.
 */
object PicksRepository {

    private var cached: List<RelaxItem>? = null

    fun getPicks(context: Context): List<RelaxItem> {
        cached?.let { return it }

        val parsed = try {
            val json = context.assets.open(PICKS_ASSET).bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<RelaxItem>>() {}.type
            Gson().fromJson<List<RelaxItem>>(json, type)
                ?.filter { it.title.isNotBlank() && it.url.isNotBlank() }
                ?.map { it.copy(isCustom = false, isLocal = false) }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val result = parsed.ifEmpty { RelaxStore.getBuiltIns() }
        cached = result
        return result
    }

    private const val PICKS_ASSET = "relax_picks.json"
}
