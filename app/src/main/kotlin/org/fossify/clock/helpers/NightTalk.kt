package org.fossify.clock.helpers

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * "寻找张怀民" (Finding Zhang Huaimin, from Su Shi's 记承天寺夜游 - "怀民亦未寝"):
 * a nighttime companionship corner. Everything is local for now:
 * - SleepProfile: nickname + insomnia type + optional birth date, the seed of
 *   future same-type sleeper matching
 * - Night notes ("随记树洞"): timestamped journal entries, the future sync
 *   payload for matching with other awake souls
 * - Daily night talk line: one gentle line per day from the corpus
 */
object NightTalk {

    data class SleepProfile(
        var nickname: String = "",
        var insomniaType: String? = null,
        var birthYear: Int = 0,
        var birthMonth: Int = 0,
        var birthDay: Int = 0,
        // 研究画像字段（与网站 user_profiles 契约同键值：60s/70s/...、male/female、bachelor/...）
        var ageGroup: String = "prefer_not",
        var gender: String = "prefer_not",
        var education: String = "prefer_not"
    )

    data class NightNote(
        val id: Long,
        val text: String,
        val atMillis: Long,
        val hourLabel: String
    )

    private const val PREFS = "night_talk"
    private const val KEY_PROFILE = "profile_json"
    private const val KEY_NOTES = "notes_json"
    private const val KEY_UID = "anon_uid"

    // ---- profile ----

    fun getProfile(context: Context): SleepProfile {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROFILE, null) ?: return defaultProfile()
        return try {
            Gson().fromJson(json, SleepProfile::class.java) ?: defaultProfile()
        } catch (e: Exception) {
            defaultProfile()
        }
    }

    fun saveProfile(context: Context, profile: SleepProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILE, Gson().toJson(profile))
            .apply()
    }

    /** 匿名设备标识（网站同款格式：u + base36 时间戳 + 随机尾缀），首次生成后固定，画像 upsert 主键 */
    fun getUid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_UID, null)?.let { return it }
        val alphabet = ('a'..'z') + ('0'..'9')
        val uid = "u" + java.lang.Long.toString(System.currentTimeMillis(), 36) +
            (1..8).map { alphabet.random() }.joinToString("")
        prefs.edit().putString(KEY_UID, uid).apply()
        return uid
    }

    private fun defaultProfile(): SleepProfile =
        SleepProfile(nickname = "未寝人${(1000..9999).random()}")

    // ---- notes (随记树洞) ----

    fun getNotes(context: Context): MutableList<NightNote> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NOTES, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<NightNote>>() {}.type
            Gson().fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun addNote(context: Context, text: String): NightNote {
        val note = NightNote(
            id = System.currentTimeMillis(),
            text = text.trim(),
            atMillis = System.currentTimeMillis(),
            hourLabel = currentHourLabel()
        )
        val notes = getNotes(context)
        notes.add(0, note)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NOTES, Gson().toJson(notes))
            .apply()
        return note
    }

    fun removeNote(context: Context, id: Long) {
        val notes = getNotes(context)
        notes.removeAll { it.id == id }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NOTES, Gson().toJson(notes))
            .apply()
    }

    /** Merge imported notes (same ids skipped). Returns added count. */
    fun mergeNotes(context: Context, imported: List<NightNote>): Int {
        val notes = getNotes(context)
        val knownIds = notes.map { it.id }.toSet()
        val fresh = imported.filter { it.text.isNotBlank() && it.id !in knownIds }
        if (fresh.isNotEmpty()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_NOTES, Gson().toJson(fresh + notes))
                .apply()
        }
        return fresh.size
    }

    // ---- 时辰 ----

    /** Traditional Chinese double-hour (时辰) label for the current time. */
    fun currentHourLabel(): String {
        val branchIndex = (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) + 1) / 2
        val branches = listOf(
            "子时", "丑时", "寅时", "卯时", "辰时", "巳时",
            "午时", "未时", "申时", "酉时", "戌时", "亥时"
        )
        return branches[branchIndex % 12]
    }

    // ---- daily night talk ----

    private val corpus = listOf(
        "元丰六年十月十二日，夜解衣欲睡，月色入户，欣然起行。",
        "何夜无月？何处无竹柏？但少闲人如吾两人者耳。",
        "睡不着的夜，不是你的错——月亮也醒着。",
        "把明天的事交给明天的自己，今晚只负责呼吸。",
        "焦虑像涨潮，你越挣扎它越高；躺平漂着，潮会自己退。",
        "兴奋是身体在放烟花，看一会儿，烟花会自己熄。",
        "身体累了脑子还在跑？想象自己在给每个念头盖章：收到，明早处理。",
        "窗外有车声，就当是城市在替你数羊。",
        "深夜是一个人最诚实的时刻，别怕它。",
        "闭上眼，世界并没有走远，它只是在等明天见你。",
        "今晚睡不着的人不止你一个，张怀民也没睡。",
        "把手机放下吧，你找的人，正在梦里等你。",
        "呼吸放慢一倍，心跳会跟着降半度。",
        "夜是一天的括号，合上了，才算写完。",
        "别数羊了，数一数今天值得谢的三件小事。",
        "月光不生产焦虑，它只是照见了它。",
        "辗转反侧时，右侧卧、左膝微屈，是古人也用的睡姿。",
        "此刻睡不着，也许是身体还想多陪你一会儿。",
        "晚安不是客套，是一个温柔的仪式。",
        "盖好被子，被窝是深夜里最小的安全区。"
    )

    /** Deterministic per calendar day, so everyone sees the same line. */
    fun dailyLine(): String {
        val dayIndex = (System.currentTimeMillis() / 86_400_000L).toInt()
        return corpus[((dayIndex % corpus.size) + corpus.size) % corpus.size]
    }

    fun formatDate(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
