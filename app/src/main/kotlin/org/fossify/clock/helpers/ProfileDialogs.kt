package org.fossify.clock.helpers

import android.app.Activity
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.fossify.clock.R
import org.fossify.commons.extensions.getAlertDialogBuilder

/**
 * 「填写用户信息」卡片（夜话随记 / 助眠 hub 共用），复刻网站画像卡：
 * - APP 匹配字段：昵称 + 失眠四型单选 + 生辰（仅本地，夜话/张怀民用）
 * - 研究画像字段：年龄 / 性别 / 学历（选值契约同网站：60s…、male/female、bachelor…）
 * - 附「推荐填写（仅供个人数据研究）」声明
 * 点击弹窗外部空白即关闭；保存写本地 SleepProfile，并按网站 SupabaseStore.saveProfile
 * 同款契约 upsert 到 user_profiles（后台线程，失败静默）。
 */
object ProfileDialogs {

    private const val PREFER_NOT = "prefer_not"

    fun show(activity: Activity, onSaved: () -> Unit = {}) {
        val profile = NightTalk.getProfile(activity)
        val pad = activity.resources.displayMetrics.widthPixels / 12

        val holder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 24, pad, 0)
        }

        holder.addView(TextView(activity).apply {
            text = activity.getString(R.string.profile_research_note)
            textSize = 12f
            alpha = 0.75f
        })

        val nicknameInput = EditText(activity).apply {
            hint = activity.getString(R.string.night_talk_nickname_hint)
            setText(profile.nickname)
        }
        val birthInput = EditText(activity).apply {
            hint = activity.getString(R.string.night_talk_birth_hint)
            if (profile.birthYear > 0) {
                setText("${profile.birthYear}-${profile.birthMonth}-${profile.birthDay}")
            }
        }
        holder.addView(nicknameInput)
        holder.addView(birthInput)

        // 研究画像三行：点行弹单选（选项/键值与网站一致），选择即时回显
        val ageOptions = listOf(
            PREFER_NOT to R.string.profile_prefer_not,
            "60s" to R.string.profile_age_60s, "70s" to R.string.profile_age_70s,
            "80s" to R.string.profile_age_80s, "90s" to R.string.profile_age_90s,
            "00s" to R.string.profile_age_00s
        )
        val genderOptions = listOf(
            PREFER_NOT to R.string.profile_prefer_not,
            "male" to R.string.profile_gender_male, "female" to R.string.profile_gender_female
        )
        val eduOptions = listOf(
            PREFER_NOT to R.string.profile_prefer_not,
            "bachelor" to R.string.profile_edu_bachelor, "master" to R.string.profile_edu_master,
            "phd" to R.string.profile_edu_phd, "other" to R.string.profile_edu_other
        )

        var ageGroup = if (profile.ageGroup.isNotEmpty()) profile.ageGroup else PREFER_NOT
        var gender = if (profile.gender.isNotEmpty()) profile.gender else PREFER_NOT
        var education = if (profile.education.isNotEmpty()) profile.education else PREFER_NOT

        fun addRow(labelRes: Int, options: List<Pair<String, Int>>, get: () -> String, set: (String) -> Unit) {
            val row = TextView(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                textSize = 15f
                setPadding(0, 36, 0, 36)
            }
            fun refresh() {
                row.text = "${activity.getString(labelRes)}：${labelOf(activity, options, get())}"
            }
            row.setOnClickListener {
                val labels = options.map { activity.getString(it.second) }.toTypedArray()
                val checked = options.indexOfFirst { it.first == get() }
                activity.getAlertDialogBuilder()
                    .setTitle(labelRes)
                    .setSingleChoiceItems(labels, checked) { d, which ->
                        set(options[which].first)
                        refresh()
                        d.dismiss()
                    }
                    .setNegativeButton(org.fossify.commons.R.string.cancel, null)
                    .show()
            }
            refresh()
            holder.addView(row)
        }

        addRow(R.string.profile_age_label, ageOptions, { ageGroup }, { ageGroup = it })
        addRow(R.string.profile_gender_label, genderOptions, { gender }, { gender = it })
        addRow(R.string.profile_edu_label, eduOptions, { education }, { education = it })

        val typeLabels = InsomniaTypes.types.map { activity.getString(it.labelRes) }.toTypedArray()
        val currentTypeIndex = InsomniaTypes.types
            .indexOfFirst { it.key == profile.insomniaType }
        var selectedType = if (currentTypeIndex >= 0) currentTypeIndex else -1

        activity.getAlertDialogBuilder()
            .setTitle(R.string.profile_fill_title)
            .setSingleChoiceItems(typeLabels, selectedType) { _, which ->
                selectedType = which
            }
            .setView(holder)
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                val birthParts = birthInput.text.toString().trim().split("-")
                var y = 0
                var m = 0
                var d = 0
                if (birthParts.size == 3) {
                    y = birthParts[0].toIntOrNull() ?: 0
                    m = birthParts[1].toIntOrNull() ?: 0
                    d = birthParts[2].toIntOrNull() ?: 0
                }
                val nickname = nicknameInput.text.toString().trim()
                    .ifEmpty { profile.nickname }
                NightTalk.saveProfile(
                    activity,
                    NightTalk.SleepProfile(
                        nickname = nickname,
                        insomniaType = if (selectedType >= 0) {
                            InsomniaTypes.types[selectedType].key
                        } else {
                            profile.insomniaType
                        },
                        birthYear = y,
                        birthMonth = m,
                        birthDay = d,
                        ageGroup = ageGroup,
                        gender = gender,
                        education = education
                    )
                )

                // 云端画像（网站 user_profiles 契约）；阻塞网络，放后台线程，失败静默
                val uid = NightTalk.getUid(activity)
                val cloud = Runnable {
                    try {
                        CommunityRemoteStore.upsertProfile(uid, nickname, ageGroup, gender, education)
                    } catch (e: Exception) {
                    }
                }
                Thread(cloud).start()

                onSaved()
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun labelOf(activity: Activity, options: List<Pair<String, Int>>, key: String): String {
        val hit = options.firstOrNull { it.first == key }
        return activity.getString(hit?.second ?: R.string.profile_prefer_not)
    }
}
