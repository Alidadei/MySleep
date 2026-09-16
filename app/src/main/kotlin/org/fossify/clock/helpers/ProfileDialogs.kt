package org.fossify.clock.helpers

import android.app.Activity
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.fossify.clock.R
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.toast

/**
 * 「填写用户信息」卡片 —— 逐字复刻网站（sleeping pill）的「完善画像」卡：
 * 昵称（选填）+ 年龄 / 性别 / 学历 三行点选，附研究用途声明，保存画像后
 * 按网站 SupabaseStore.saveProfile 同款契约 upsert 到 user_profiles。
 * 失眠类型与生辰不在此卡（网站契约没有），归夜话随记页的编辑画像弹窗。
 * 点击弹窗外部空白即关闭（AlertDialog 默认行为）。
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
            hint = activity.getString(R.string.profile_nick_ph)
            setText(profile.nickname)
        }
        holder.addView(nicknameInput)

        // 三行点选：键值契约与网站一致（60s…00s、male/female、bachelor…，未填 prefer_not）
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
                val hit = options.firstOrNull { it.first == get() }
                row.text = "${activity.getString(labelRes)}：" +
                    activity.getString(hit?.second ?: R.string.profile_prefer_not)
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

        activity.getAlertDialogBuilder()
            .setTitle(R.string.profile_fill_title)
            .setView(holder)
            .setPositiveButton(R.string.profile_save) { _, _ ->
                val nickname = nicknameInput.text.toString().trim()
                    .ifEmpty { profile.nickname }
                // 只覆盖画像字段，夜话匹配用的失眠类型/生辰原样保留
                NightTalk.saveProfile(
                    activity,
                    profile.copy(
                        nickname = nickname,
                        ageGroup = ageGroup,
                        gender = gender,
                        education = education
                    )
                )

                // 云端画像（网站 user_profiles 契约）；阻塞网络，放后台线程，失败静默
                val uid = NightTalk.getUid(activity)
                Thread {
                    try {
                        CommunityRemoteStore.upsertProfile(uid, nickname, ageGroup, gender, education)
                    } catch (e: Exception) {
                    }
                }.start()

                activity.toast(R.string.profile_saved)
                onSaved()
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }
}
