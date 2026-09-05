package org.fossify.clock.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import org.fossify.clock.R
import org.fossify.clock.databinding.ActivityRelaxBinding
import org.fossify.clock.helpers.RelaxItem
import org.fossify.clock.helpers.RelaxStore
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon

/**
 * Bedtime relax favorites: curated sleep-friendly content plus user-added links
 * (novels, blogs, music) and local files (ebooks, audio). Web items open in the
 * browser, local files open in whatever app handles them.
 */
class RelaxActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityRelaxBinding::inflate)
    private val textColor by lazy { getProperTextColor() }

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                handlePickedLocalFile(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupTopAppBar(binding.relaxAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.root)

        binding.relaxAddFavorite.setOnClickListener {
            showAddChoiceDialog()
        }

        showItems()
    }

    private fun showItems() {
        binding.relaxHolder.removeAllViews()

        addSectionLabel(getString(R.string.relax_picks_label))
        RelaxStore.getBuiltIns().forEach { item ->
            addItemRow(item, deletable = false)
        }

        val customItems = RelaxStore.getCustomItems(this)
        addSectionLabel(getString(R.string.relax_custom_label))
        binding.relaxEmptyCustom.beGoneIf(customItems.isNotEmpty())
        customItems.forEach { item ->
            addItemRow(item, deletable = true)
        }

        updateTextColors(binding.relaxHolder)
    }

    private fun addSectionLabel(text: String) {
        val label = LayoutInflater.from(this).inflate(
            R.layout.item_relax_section, binding.relaxHolder, false
        ) as org.fossify.commons.views.MyTextView
        label.text = text
        binding.relaxHolder.addView(label)
    }

    private fun addItemRow(item: RelaxItem, deletable: Boolean) {
        val row = LayoutInflater.from(this).inflate(
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
                getAlertDialogBuilder()
                    .setMessage(R.string.relax_delete_confirm)
                    .setPositiveButton(R.string.relax_remove) { _, _ ->
                        RelaxStore.removeCustomItem(this, item.id)
                        showItems()
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

        getAlertDialogBuilder()
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
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // some providers do not hand out persistable grants, the permission
            // then only lasts until reboot
        }

        val title = queryDisplayName(uri) ?: getString(R.string.relax_local_label)
        RelaxStore.addCustomItem(this, title, uri.toString(), isLocal = true)
        showItems()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(
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
                    setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_VIEW, item.url.toUri())
            }
            startActivity(intent)
        } catch (e: Exception) {
            toast(R.string.relax_invalid_url)
        }
    }

    private fun showAddFavoriteDialog() {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(resources.displayMetrics.widthPixels / 10, 40, resources.displayMetrics.widthPixels / 10, 0)
        }

        val titleInput = EditText(this).apply {
            hint = getString(R.string.relax_title_hint)
            setTextColor(textColor)
        }
        val urlInput = EditText(this).apply {
            hint = getString(R.string.relax_url_hint)
            setTextColor(textColor)
        }

        holder.addView(titleInput)
        holder.addView(urlInput)

        val dialog = getAlertDialogBuilder()
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
                    this,
                    titleInput.text.toString().trim(),
                    RelaxStore.normalizeUrl(urlInput.text.toString())
                )
                dialog.dismiss()
                showItems()
            }
        }

        dialog.setView(holder)
        dialog.show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            toast(R.string.relax_invalid_url)
        }
    }
}
