package org.fossify.clock.helpers

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * File-level import/export for relax data, shared by the favorites section,
 * the community picks section and the night-talk notes screen. Community
 * picks use the exact cyberSleepCommunity contract format (plain array), so
 * files move between the app and the website without conversion.
 */
object RelaxDataIO {

    const val KIND_FAVORITES = "favorites"
    const val KIND_COMMUNITY = "community"
    const val KIND_NOTES = "notes"

    fun defaultFileName(kind: String): String = when (kind) {
        KIND_FAVORITES -> "sleep_station_favorites.json"
        KIND_COMMUNITY -> "sleep_station_community_picks.json"
        else -> "sleep_station_night_notes.json"
    }

    fun toJson(kind: String, context: Context): String = when (kind) {
        KIND_FAVORITES -> Gson().toJson(RelaxStore.getCustomItems(context))
        KIND_COMMUNITY -> Gson().toJson(RelaxStore.getCommunityPicks(context))
        else -> Gson().toJson(NightTalk.getNotes(context))
    }

    /** Returns the number of merged entries; -1 when the file is not valid JSON for this kind. */
    fun mergeFromUri(kind: String, context: Context, uri: Uri): Int {
        val text = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        } ?: return -1

        return try {
            when (kind) {
                KIND_FAVORITES -> {
                    val type = object : TypeToken<List<RelaxItem>>() {}.type
                    val items = Gson().fromJson<List<RelaxItem>>(text, type) ?: return -1
                    RelaxStore.mergeCustomItems(context, items)
                }

                KIND_COMMUNITY -> {
                    val type = object : TypeToken<List<CommunityPick>>() {}.type
                    val picks = Gson().fromJson<List<CommunityPick>>(text, type) ?: return -1
                    RelaxStore.mergeCommunityPicks(context, picks)
                }

                else -> {
                    val type = object : TypeToken<List<NightTalk.NightNote>>() {}.type
                    val notes = Gson().fromJson<List<NightTalk.NightNote>>(text, type) ?: return -1
                    NightTalk.mergeNotes(context, notes)
                }
            }
        } catch (e: Exception) {
            -1
        }
    }

    fun writeToUri(kind: String, context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(toJson(kind, context).toByteArray(Charsets.UTF_8))
            } != null
        } catch (e: Exception) {
            false
        }
    }
}
