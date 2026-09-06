package org.fossify.clock.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import org.fossify.clock.R
import org.fossify.clock.activities.SleepReportActivity
import org.fossify.clock.databinding.FragmentRelaxBinding
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.requiredActivity
import org.fossify.clock.helpers.RelaxItem
import org.fossify.clock.helpers.RelaxStore
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.ensureBackgroundThread
import kotlin.math.roundToInt

/**
 * The bedtime tab: a launcher hub with three entry cards (favorites, curated
 * picks, sleep report). Favorites and picks expand in place; the report opens
 * its own screen.
 */
class RelaxFragment : Fragment() {

    private enum class Section { FAVORITES, PICKS }

    private lateinit var binding: FragmentRelaxBinding
    private var currentSection = Section.FAVORITES

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                handlePickedLocalFile(uri)
            }
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showHub()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentRelaxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        val accent = requireContext().getColor(R.color.color_accent)
        binding.cardFavoritesIcon.applyColorFilter(accent)
        binding.cardPicksIcon.applyColorFilter(accent)
        binding.cardReportIcon.applyColorFilter(accent)

        binding.cardFavorites.setOnClickListener { showSection(Section.FAVORITES) }
        binding.cardPicks.setOnClickListener { showSection(Section.PICKS) }
        binding.cardReport.setOnClickListener {
            startActivity(Intent(requireContext(), SleepReportActivity::class.java))
        }

        binding.relaxBack.setOnClickListener { showHub() }
        binding.relaxAddFavorite.setOnClickListener { showAddChoiceDialog() }

        showHub()
        refreshReportSubtitle()
    }

    override fun onResume() {
        super.onResume()
        refreshReportSubtitle()
        if (binding.relaxSection.visibility == View.VISIBLE) {
            populateSection(currentSection)
        }
    }

    private fun showHub() {
        backCallback.isEnabled = false
        binding.relaxHub.beVisible()
        binding.relaxSection.beGone()
    }

    private fun showSection(section: Section) {
        currentSection = section
        backCallback.isEnabled = true
        binding.relaxHub.beGone()
        binding.relaxSection.beVisible()

        binding.relaxSectionTitle.setText(
            if (section == Section.FAVORITES) R.string.relax_custom_label else R.string.relax_picks_label
        )
        populateSection(section)
        requireActivity().updateTextColors(binding.relaxSection)
    }

    private fun populateSection(section: Section) {
        binding.relaxHolder.removeAllViews()

        if (section == Section.PICKS) {
            RelaxStore.getBuiltIns().forEach { item ->
                addItemRow(item, deletable = false)
            }
            binding.relaxEmptyCustom.beGone()
            binding.relaxAddFavorite.beGone()
            return
        }

        val customItems = RelaxStore.getCustomItems(requireContext())
        customItems.forEach { item ->
            addItemRow(item, deletable = true)
        }
        binding.relaxEmptyCustom.beVisibleIf(customItems.isEmpty())
        binding.relaxAddFavorite.beVisible()
    }

    private fun refreshReportSubtitle() {
        ensureBackgroundThread {
            val records = try {
                requiredActivity.dbHelper.getRecentSleepRecords(30)
            } catch (e: Exception) {
                return@ensureBackgroundThread
            }
            activity?.runOnUiThread {
                if (!isAdded) {
                    return@runOnUiThread
                }

                binding.reportSubtitle.text = if (records.isEmpty()) {
                    getString(R.string.report_subtitle_empty)
                } else {
                    val oversleepMinutes = records.map {
                        ((it.stoppedAtMillis - it.ringAtMillis) / 60000L).coerceAtLeast(0L)
                    }
                    val avgOversleep =
                        (oversleepMinutes.sum().toFloat() / oversleepMinutes.size).roundToInt()
                    val onTimePercent =
                        (oversleepMinutes.count { it <= 5 } * 100f / records.size).roundToInt()
                    getString(
                        R.string.sleep_report_summary_fmt,
                        records.size,
                        onTimePercent,
                        avgOversleep
                    )
                }
            }
        }
    }

    private fun addItemRow(item: RelaxItem, deletable: Boolean) {
        val row = LayoutInflater.from(requireContext()).inflate(
            R.layout.item_relax, binding.relaxHolder, false
        ) as LinearLayout

        row.findViewById<org.fossify.commons.views.MyTextView>(R.id.relax_item_title)
            .text = item.title
        row.findViewById<org.fossify.commons.views.MyTextView>(R.id.relax_item_url)
            .text = if (item.isLocal) {
                getString(R.string.relax_local_label)
            } else {
                item.url
            }

        row.setOnClickListener {
            openItem(item)
        }

        if (deletable) {
            row.setOnLongClickListener {
                requireActivity().getAlertDialogBuilder()
                    .setMessage(R.string.relax_delete_confirm)
                    .setPositiveButton(R.string.relax_remove) { _, _ ->
                        RelaxStore.removeCustomItem(requireContext(), item.id)
                        populateSection(Section.FAVORITES)
                    }
                    .setNegativeButton(org.fossify.commons.R.string.cancel, null)
                    .show()
                true
            }
        }

        binding.relaxHolder.addView(row)
    }

    private fun showAddChoiceDialog() {
        val options = arrayOf(
            getString(R.string.relax_add_web),
            getString(R.string.relax_add_local)
        )

        requireActivity().getAlertDialogBuilder()
            .setTitle(R.string.relax_add_favorite)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddFavoriteDialog()
                    1 -> filePicker.launch(arrayOf("text/*", "audio/*", "application/epub+zip"))
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun handlePickedLocalFile(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // some providers do not hand out persistable grants,
            // the permission then only lasts until reboot
        }

        val title = queryDisplayName(uri) ?: getString(R.string.relax_local_label)
        RelaxStore.addCustomItem(requireContext(), title, uri.toString(), isLocal = true)
        populateSection(Section.FAVORITES)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            requireContext().contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun openItem(item: RelaxItem) {
        try {
            val intent = if (item.isLocal) {
                val uri = Uri.parse(item.url)
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, requireContext().contentResolver.getType(uri) ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_VIEW, item.url.toUri())
            }
            startActivity(intent)
        } catch (e: Exception) {
            requireContext().toast(R.string.relax_invalid_url)
        }
    }

    private fun showAddFavoriteDialog() {
        val holder = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                resources.displayMetrics.widthPixels / 10, 40,
                resources.displayMetrics.widthPixels / 10, 0
            )
        }

        val titleInput = EditText(requireContext()).apply {
            hint = getString(R.string.relax_title_hint)
        }
        val urlInput = EditText(requireContext()).apply {
            hint = getString(R.string.relax_url_hint)
        }

        holder.addView(titleInput)
        holder.addView(urlInput)

        val dialog = requireActivity().getAlertDialogBuilder()
            .setTitle(R.string.relax_add_favorite)
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            okButton.isEnabled = false

            val inputWatcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    okButton.isEnabled = titleInput.text.isNotBlank() &&
                        RelaxStore.isValidUrl(urlInput.text.toString())
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            }
            titleInput.addTextChangedListener(inputWatcher)
            urlInput.addTextChangedListener(inputWatcher)

            okButton.setOnClickListener {
                RelaxStore.addCustomItem(
                    requireContext(),
                    titleInput.text.toString().trim(),
                    RelaxStore.normalizeUrl(urlInput.text.toString())
                )
                dialog.dismiss()
                populateSection(Section.FAVORITES)
            }
        }

        dialog.setView(holder)
        dialog.show()
    }
}
