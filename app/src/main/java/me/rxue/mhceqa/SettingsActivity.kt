package me.rxue.mhceqa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.rxue.mhceqa.ui.screens.SettingsScreen
import me.rxue.mhceqa.ui.theme.MonashCeqaTheme

/**
 * Full-page settings screen using Jetpack Compose.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS                 = "asset_scanner_prefs"
        private const val KEY_FONT_SIZE         = "log_font_size"
        private const val KEY_SNACKBAR_DURATION = "snackbar_duration"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var csvManager: CsvManager
    private var currentFolderText by mutableStateOf("")
    private var currentSourceName by mutableStateOf("")
    private var sourceCsvChanged = false

    private val pickSourceCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val fileName = getFileNameFromUri(uri)
            csvManager.saveSourceUri(uri, fileName)
            currentSourceName = fileName
            sourceCsvChanged = true
        }
    }

    private val pickOutputFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            csvManager.saveOutputFolderUri(uri)
            currentFolderText = csvManager.outputFolderPath
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            } catch (_: Exception) {}
            try {
                val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(this, uri)
                val docName = doc?.name
                if (!docName.isNullOrBlank()) return docName
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment ?: "Source Asset List.csv"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs      = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        csvManager = CsvManager(this)
        currentFolderText = csvManager.outputFolderPath
        currentSourceName = csvManager.loadSavedSourceName()
            ?: csvManager.loadSavedSourceUri()?.let { getFileNameFromUri(it) }
            ?: "Source Asset List.csv"

        val savedFontSize = prefs.getFloat(KEY_FONT_SIZE, ScanLogAdapter.FONT_SIZE_MEDIUM)
        val savedDuration = prefs.getInt(KEY_SNACKBAR_DURATION, 5000)
        val savedWebhookUrl = DriveSyncManager.getWebhookUrl(this)
        val savedFolderId = DriveSyncManager.getCustomFolderId(this)
        val savedAutoSync = DriveSyncManager.isAutoSyncEnabled(this)

        val savedSourceFolderId = DriveSyncManager.getSourceFolderId(this)
        val savedSourceAutoUpdate = DriveSyncManager.isSourceAutoUpdateEnabled(this)
        val sourceLastCheckTime = DriveSyncManager.getSourceLastCheckTime(this)

        setContent {
            MonashCeqaTheme {
                SettingsScreen(
                    initialScannerName = csvManager.scannerName,
                    currentFolderPath = currentFolderText,
                    currentSourceFileName = currentSourceName,
                    initialFontSize = savedFontSize,
                    initialSnackbarDurationMs = savedDuration,
                    initialWebhookUrl = savedWebhookUrl,
                    initialFolderId = savedFolderId,
                    initialAutoSync = savedAutoSync,
                    initialSourceFolderId = savedSourceFolderId,
                    initialSourceAutoUpdate = savedSourceAutoUpdate,
                    sourceLastCheckTime = sourceLastCheckTime,
                    onCheckSourceUpdate = { folderId, onComplete ->
                        DriveSyncManager.saveSourceFolderId(this@SettingsActivity, folderId)
                        lifecycleScope.launch {
                            when (val result = DriveSyncManager.checkAndUpdateSourceAssetList(this@SettingsActivity, csvManager, force = true)) {
                                is DriveSyncManager.SourceAssetUpdateResult.Updated -> {
                                    currentSourceName = result.fileName
                                    sourceCsvChanged = true
                                    onComplete("✓ Updated: ${result.fileName} (${result.assetCount} assets)")
                                }
                                is DriveSyncManager.SourceAssetUpdateResult.UpToDate -> {
                                    onComplete("✓ Asset list is already up to date")
                                }
                                is DriveSyncManager.SourceAssetUpdateResult.Failed -> {
                                    onComplete("Error: ${result.error}")
                                }
                                is DriveSyncManager.SourceAssetUpdateResult.Offline -> {
                                    onComplete("Device is offline")
                                }
                                is DriveSyncManager.SourceAssetUpdateResult.NoWebhook -> {
                                    onComplete("Please configure Webhook URL first")
                                }
                            }
                        }
                    },
                    onPickSourceCsv = { pickSourceCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) },
                    onChangeFolder = { pickOutputFolderLauncher.launch(null) },
                    onSaveSettings = { newScannerName, newFontSize, newDurationMs, newWebhook, newFolderId, newAutoSync, newSourceFolderId, newSourceAutoUpdate ->
                        csvManager.scannerName = newScannerName
                        prefs.edit()
                            .putFloat(KEY_FONT_SIZE, newFontSize)
                            .putInt(KEY_SNACKBAR_DURATION, newDurationMs)
                            .apply()
                        DriveSyncManager.saveWebhookUrl(this, newWebhook)
                        DriveSyncManager.saveCustomFolderId(this, newFolderId)
                        DriveSyncManager.setAutoSyncEnabled(this, newAutoSync)
                        DriveSyncManager.saveSourceFolderId(this, newSourceFolderId)
                        DriveSyncManager.setSourceAutoUpdateEnabled(this, newSourceAutoUpdate)

                        val resultIntent = Intent().apply {
                            putExtra("extra_source_csv_changed", sourceCsvChanged)
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    onBack = {
                        if (sourceCsvChanged) {
                            val resultIntent = Intent().apply {
                                putExtra("extra_source_csv_changed", true)
                            }
                            setResult(Activity.RESULT_OK, resultIntent)
                        } else {
                            setResult(Activity.RESULT_CANCELED)
                        }
                        finish()
                    }
                )
            }
        }
    }
}

