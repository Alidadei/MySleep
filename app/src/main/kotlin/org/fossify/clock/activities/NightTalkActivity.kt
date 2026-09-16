package org.fossify.clock.activities

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import org.fossify.clock.R
import org.fossify.clock.databinding.ActivityNightTalkBinding
import org.fossify.clock.helpers.InsomniaTypes
import org.fossify.clock.helpers.NightTalk
import org.fossify.clock.helpers.RelaxDataIO
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * "寻找张怀民" - the nighttime companionship corner (承天寺 hub card opens
 * this). Local-first: sleep profile, the daily night-talk line and the
 * night journal ("随记树洞"). Same-type sleeper matching and chat arrive with
 * the cloud backend.
 */
class NightTalkActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityNightTalkBinding::inflate)

    private val notesImporter =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                handleImport(uri)
            }
        }

    private val notesExporter =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                handleExport(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        applyNightTheme()

        binding.nightTalkHour.text = getString(
            R.string.night_talk_hour_fmt, NightTalk.currentHourLabel()
        )
        binding.nightTalkLine.text = NightTalk.dailyLine()

        binding.nightTalkEditProfile.setOnClickListener { showProfileDialog() }
        binding.nightTalkAddNote.setOnClickListener { showAddNoteDialog() }
        binding.nightTalkImport.setOnClickListener {
            notesImporter.launch(arrayOf("application/json", "text/*", "*/*"))
        }
        binding.nightTalkExport.setOnClickListener {
            notesExporter.launch(RelaxDataIO.defaultFileName(RelaxDataIO.KIND_NOTES))
        }

        refreshProfile()
        refreshNotes()
    }

    private fun handleExport(uri: android.net.Uri) {
        toast(
            if (RelaxDataIO.writeToUri(RelaxDataIO.KIND_NOTES, this, uri)) {
                R.string.relax_export_ok
            } else {
                R.string.relax_import_failed
            }
        )
    }

    private fun handleImport(uri: android.net.Uri) {
        ensureBackgroundThread {
            val added = RelaxDataIO.mergeFromUri(RelaxDataIO.KIND_NOTES, this, uri)
            runOnUiThread {
                when {
                    added < 0 -> toast(R.string.relax_import_bad)
                    added == 0 -> toast(R.string.relax_import_none)
                    else -> {
                        toast(getString(R.string.relax_import_ok, added))
                        refreshNotes()
                    }
                }
            }
        }
    }

    /** 时辰主题（与网站同步：夜藕荷紫×昼琥珀棕）+ 手写体 */
    private fun applyNightTheme() {
        val theme = org.fossify.clock.helpers.TimeTheme.current()
        findViewById<android.view.ViewGroup>(android.R.id.content).setBackgroundColor(theme.bg)
        binding.nightTalkHour.setTextColor(theme.accent)
        binding.nightTalkLine.setTextColor(theme.ink)
        binding.nightTalkProfile.setTextColor(theme.ink)
        binding.nightTalkNotesEmpty.setTextColor(theme.sub)
        binding.nightTalkDailyLabel.setTextColor(theme.sub)
        binding.nightTalkProfileLabelText.setTextColor(theme.sub)
        binding.nightTalkNotesLabelText.setTextColor(theme.sub)
        binding.nightTalkMatchingHint.setTextColor(theme.sub)

        val hand = try {
            android.graphics.Typeface.createFromAsset(assets, "fonts/ma_shan_zheng.ttf")
        } catch (e: Exception) {
            android.graphics.Typeface.SERIF
        }
        binding.nightTalkHour.typeface = hand
        binding.nightTalkLine.typeface = hand
    }

    private fun refreshProfile() {
        val profile = NightTalk.getProfile(this)
        val typeLabel = InsomniaTypes.labelKey(this, profile.insomniaType)
        val birth = if (profile.birthYear > 0) {
            " · 生辰 ${profile.birthYear}-${profile.birthMonth}-${profile.birthDay}"
        } else {
            ""
        }
        binding.nightTalkProfile.text =
            "${profile.nickname} · ${typeLabel.ifEmpty { getString(R.string.night_talk_type_unset) }}$birth"
    }

    private fun refreshNotes() {
        val holder = binding.nightTalkNotesHolder
        holder.removeAllViews()
        val notes = NightTalk.getNotes(this)
        binding.nightTalkNotesEmpty.beVisibleIf(notes.isEmpty())

        notes.forEach { note ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_relax, holder, false) as LinearLayout
            row.findViewById<org.fossify.commons.views.MyTextView>(R.id.relax_item_title).text =
                note.text
            row.findViewById<org.fossify.commons.views.MyTextView>(R.id.relax_item_url).text =
                "${NightTalk.formatDate(note.atMillis)} · ${note.hourLabel}"
            row.setOnLongClickListener {
                getAlertDialogBuilder()
                    .setMessage(R.string.night_talk_note_delete_confirm)
                    .setPositiveButton(R.string.relax_remove) { _, _ ->
                        NightTalk.removeNote(this, note.id)
                        refreshNotes()
                    }
                    .setNegativeButton(org.fossify.commons.R.string.cancel, null)
                    .show()
                true
            }
            holder.addView(row)
        }
    }

    /** 夜话自己的编辑画像：昵称 + 失眠四型 + 生辰（本地匹配字段；研究画像归 hub 的填写用户信息卡） */
    private fun showProfileDialog() {
        val profile = NightTalk.getProfile(this)
        val pad = resources.displayMetrics.widthPixels / 12

        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 40, pad, 0)
        }
        val nicknameInput = EditText(this).apply {
            hint = getString(R.string.night_talk_nickname_hint)
            setText(profile.nickname)
        }
        val birthInput = EditText(this).apply {
            hint = getString(R.string.night_talk_birth_hint)
            if (profile.birthYear > 0) {
                setText("${profile.birthYear}-${profile.birthMonth}-${profile.birthDay}")
            }
        }
        holder.addView(nicknameInput)
        holder.addView(birthInput)

        val typeLabels = InsomniaTypes.types.map { getString(it.labelRes) }.toTypedArray()
        val currentTypeIndex = InsomniaTypes.types
            .indexOfFirst { it.key == profile.insomniaType }
            .coerceAtLeast(-1)
        var selectedType = if (currentTypeIndex >= 0) currentTypeIndex else -1

        getAlertDialogBuilder()
            .setTitle(R.string.night_talk_edit_profile)
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
                    this,
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
                        ageGroup = profile.ageGroup,
                        gender = profile.gender,
                        education = profile.education
                    )
                )
                // 昵称变了就同步云端画像（研究字段原样带上），失败静默
                val uid = NightTalk.getUid(this)
                val ageGroup = profile.ageGroup
                val gender = profile.gender
                val education = profile.education
                Thread {
                    try {
                        org.fossify.clock.helpers.CommunityRemoteStore
                            .upsertProfile(uid, nickname, ageGroup, gender, education)
                    } catch (e: Exception) {
                    }
                }.start()
                refreshProfile()
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun showAddNoteDialog() {
        val pad = resources.displayMetrics.widthPixels / 12
        val input = EditText(this).apply {
            hint = getString(R.string.night_talk_note_hint)
            minLines = 3
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 40, pad, 0)
        }
        holder.addView(input)

        val dialog = getAlertDialogBuilder()
            .setTitle(R.string.night_talk_add_note)
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            okButton.isEnabled = false
            input.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    okButton.isEnabled = !s.isNullOrBlank()
                }

                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) = Unit
            })
            okButton.setOnClickListener {
                NightTalk.addNote(this, input.text.toString())
                dialog.dismiss()
                refreshNotes()
            }
        }

        dialog.setView(holder)
        dialog.show()
    }
}
