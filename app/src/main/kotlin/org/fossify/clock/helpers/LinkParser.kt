package org.fossify.clock.helpers

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * Parses pasted share text into (url, title): handles Bilibili-style share
 * strings ("【标题】 https://www.bilibili.com/video/BV...?params"), bare URLs
 * and page <title> fetching so users can add favorites by pasting only a
 * link. Titles are optional - the caller falls back to a host-based default.
 */
object LinkParser {

    private val urlRegex = Regex("https?://\\S+")
    private val titleRegexes = listOf(
        Regex("【(.+?)】"),
        Regex("「(.+?)」"),
        Regex("『(.+?)』"),
        Regex("《(.+?)》")
    )

    /** Known site suffixes stripped from <title> results. */
    private val titleSuffixes = listOf(
        "_哔哩哔哩_bilibili",
        "_哔哩哔哩",
        "_bilibili",
        " - YouTube",
        "_高清1080P在线观看平台",
        "- 虎牙直播",
        " - 腾讯视频",
        "_爱奇艺",
        " - 知乎"
    )

    data class ParsedLink(val url: String?, val title: String?)

    fun parse(text: String): ParsedLink {
        val raw = urlRegex.find(text)?.value
            ?.trimEnd('，', '。', '）', ')', ']', '】', ',', ';', '"')
        val shareTitle = titleRegexes.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }
        return ParsedLink(raw, shareTitle?.trim())
    }

    /** Fetches the page title on a background thread; null on any failure. */
    fun fetchTitleAsync(url: String, onResult: (String?) -> Unit) {
        val appContext = Handler(Looper.getMainLooper())
        Thread {
            val title = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                )
                val body = conn.inputStream.bufferedReader(Charset.forName("UTF-8"))
                    .use { it.readText() }
                    .take(80_000)
                conn.disconnect()
                Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    .find(body)?.groupValues?.get(1)?.trim()
            } catch (e: Exception) {
                null
            }
            appContext.post { onResult(cleanTitle(title)) }
        }.start()
    }

    private fun cleanTitle(title: String?): String? {
        if (title.isNullOrBlank()) return null
        var cleaned = title
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
        titleSuffixes.forEach { suffix ->
            if (cleaned.endsWith(suffix, ignoreCase = true)) {
                cleaned = cleaned.dropLast(suffix.length).trim()
            }
        }
        return cleaned.ifBlank { null }?.take(80)
    }
}
