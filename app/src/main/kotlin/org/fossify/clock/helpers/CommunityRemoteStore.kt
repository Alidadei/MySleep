package org.fossify.clock.helpers

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.Charset

/**
 * Cloud community store, aligned verbatim with the website's SupabaseStore
 * (cyberSleepCommunity/index.html): same project, same PostgREST paths, same
 * headers, same merge/rate semantics. The anon key is a public credential -
 * permissions are enforced by RLS on the server.
 *
 * Offline behavior: callers fall back to the local cache in RelaxStore when
 * any call fails; successful loads refresh that cache.
 */
object CommunityRemoteStore {

    private const val BASE = "https://ttvaedbukdwpvdtmodeo.supabase.co"
    private const val ANON_KEY = "sb_publishable_buDoQC3OdWau8DdtUY9moQ_YbK5UgSm"
    private const val REST = "$BASE/rest/v1/"
    private const val CONNECT_TIMEOUT = 8000
    private const val READ_TIMEOUT = 8000

    private val gson = Gson()

    private fun connection(path: String, method: String, body: String? = null): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(REST + path)).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.requestMethod = method
            conn.setRequestProperty("apikey", ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer $ANON_KEY")
            conn.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader(Charset.forName("UTF-8")).use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Cloud rows, deduped per website semantics (same url keeps the row with
     *  the highest recommend_count). Null when the network fails. */
    fun load(): List<CommunityPick>? {
        val path = "community_picks?select=id,title,url,type,ratings,addedAt,recommend_count" +
            "&order=addedAt.desc&limit=1000"
        val body = connection(path, "GET") ?: return null
        return try {
            val type = object : TypeToken<List<CommunityPick>>() {}.type
            val rows: List<CommunityPick> = gson.fromJson(body, type) ?: return emptyList()
            val seen = LinkedHashMap<String, CommunityPick>()
            rows.forEach { row ->
                val key = (row.url ?: "").lowercase()
                val exist = seen[key]
                if (exist == null || row.recommendCount > exist.recommendCount) {
                    seen[key] = row
                }
            }
            seen.values.toList()
        } catch (e: Exception) {
            null
        }
    }

    /** Submit: same url increments recommend_count, otherwise inserts. Mirrors
     *  the website's add() - dedup by exact url match server-side.
     *  Returns true on success (merged or inserted). */
    fun add(context: Context, title: String, url: String, type: String?): Boolean {
        val queryPath = "community_picks?url=eq.${enc(url)}&select=id,recommend_count"
        val queryBody = connection(queryPath, "GET") ?: return false
        val existing = try {
            val type2 = object : TypeToken<List<CommunityPick>>() {}.type
            gson.fromJson<List<CommunityPick>>(queryBody, type2)
        } catch (e: Exception) {
            null
        }

        return if (!existing.isNullOrEmpty()) {
            val next = (existing[0].recommendCount ?: 1) + 1
            connection(
                "community_picks?id=eq.${existing[0].id}", "PATCH",
                gson.toJson(mapOf("recommend_count" to next))
            ) != null
        } else {
            val row = CommunityPick(
                id = System.currentTimeMillis(),
                title = title,
                url = url,
                ratings = mutableListOf(),
                addedAt = System.currentTimeMillis(),
                type = type
            ).apply { recommendCount = 1 }
            val payload = gson.toJson(row).replace("\"recommendCount\"", "\"recommend_count\"")
            connection("community_picks", "POST", payload) != null
        }.also { ok ->
            if (ok) {
                // keep the local cache coherent for offline fallback
                RelaxStore.addCommunityPick(context, title, url, type)
            }
        }
    }

    /** Rating = read ratings back, append, PATCH whole array (contract §6). */
    fun rate(id: Long, score: Int): Boolean {
        val rowsBody = connection("community_picks?id=eq.$id&select=ratings", "GET") ?: return false
        val ratings = try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val rows: List<Map<String, Any>> = gson.fromJson(rowsBody, type) ?: return false
            @Suppress("UNCHECKED_CAST")
            (rows.firstOrNull()?.get("ratings") as? List<Double>)
                ?.map { it.toInt() }?.toMutableList()
        } catch (e: Exception) {
            null
        } ?: return false

        ratings.add(score.coerceIn(1, 5))
        return connection(
            "community_picks?id=eq.$id", "PATCH", gson.toJson(mapOf("ratings" to ratings))
        ) != null
    }
}
