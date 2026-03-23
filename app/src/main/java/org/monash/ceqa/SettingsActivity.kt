package org.monash.ceqa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import org.monash.ceqa.databinding.ActivitySettingsBinding

/**
 * Full-page settings screen. Saves directly to SharedPreferences on "Save".
 * Returns RESULT_OK so MainActivity knows to refresh font size and output path label.
 * Back arrow or device back navigates away WITHOUT saving.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS                 = "asset_scanner_prefs"
        private const val KEY_FONT_SIZE         = "log_font_size"
        private const val KEY_SNACKBAR_DURATION = "snackbar_duration"
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var csvManager: CsvManager

    // Folder picker - registered here so the live label updates correctly
    private val pickOutputFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            csvManager.saveOutputFolderUri(uri)
            binding.tvCurrentFolder.text = csvManager.outputFilePath
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs      = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        csvManager = CsvManager(this)

        // ── Toolbar ──────────────────────────────────────────────────────────
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // ── Scanner name ─────────────────────────────────────────────────────
        binding.etScannerName.setText(csvManager.scannerName)
        binding.etScannerName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val preview = s.toString().trim().lowercase().replace(" ", "_")
                val previewName = CsvManager.fileNameForDate(scannerPrefix = preview)
                val folderUri = csvManager.loadOutputFolderUri()
                val folderPart = if (folderUri != null) {
                    val folder = androidx.documentfile.provider.DocumentFile.fromTreeUri(this@SettingsActivity, folderUri)
                    folder?.name ?: folderUri.lastPathSegment ?: "?"
                } else {
                    java.io.File(getExternalFilesDir(null), "").absolutePath
                }
                binding.tvCurrentFolder.text = "$folderPart/$previewName"
            }
        })

        // ── Output folder ────────────────────────────────────────────────────
        binding.tvCurrentFolder.text = csvManager.outputFilePath
        binding.btnChangeFolder.setOnClickListener {
            pickOutputFolderLauncher.launch(null)
        }

        // ── Font size ────────────────────────────────────────────────────────
        val savedFontSize = prefs.getFloat(KEY_FONT_SIZE, ScanLogAdapter.FONT_SIZE_MEDIUM)
        when (savedFontSize) {
            ScanLogAdapter.FONT_SIZE_SMALL -> binding.rbFontSmall.isChecked = true
            ScanLogAdapter.FONT_SIZE_LARGE -> binding.rbFontLarge.isChecked = true
            else                           -> binding.rbFontMedium.isChecked = true
        }

        // ── Snackbar duration slider ─────────────────────────────────────────
        val currentSecs = prefs.getInt(KEY_SNACKBAR_DURATION, 5000) / 1000f
        binding.sliderSnackbarDuration.value = currentSecs.coerceIn(2f, 10f)
        binding.tvSnackbarDurationLabel.text = "${binding.sliderSnackbarDuration.value.toInt()}s"
        binding.sliderSnackbarDuration.addOnChangeListener { _, value, _ ->
            binding.tvSnackbarDurationLabel.text = "${value.toInt()}s"
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.settings_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save_settings -> { saveAndExit(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    private fun saveAndExit() {
        // Scanner name
        val newName = binding.etScannerName.text.toString()
            .trim().lowercase().replace(" ", "_")
        csvManager.scannerName = newName

        // Font size
        val newFontSize = when (binding.rgFontSize.checkedRadioButtonId) {
            R.id.rbFontSmall -> ScanLogAdapter.FONT_SIZE_SMALL
            R.id.rbFontLarge -> ScanLogAdapter.FONT_SIZE_LARGE
            else             -> ScanLogAdapter.FONT_SIZE_MEDIUM
        }
        prefs.edit().putFloat(KEY_FONT_SIZE, newFontSize).apply()

        // Snackbar duration
        val newDuration = (binding.sliderSnackbarDuration.value * 1000).toInt()
        prefs.edit().putInt(KEY_SNACKBAR_DURATION, newDuration).apply()

        setResult(Activity.RESULT_OK)
        finish()
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    /** Back arrow: discard changes, return to previous screen. */
    override fun onSupportNavigateUp(): Boolean {
        setResult(Activity.RESULT_CANCELED)
        finish()
        return true
    }
}
