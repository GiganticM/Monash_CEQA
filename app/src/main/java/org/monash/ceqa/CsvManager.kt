package org.monash.ceqa

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import org.monash.ceqa.models.Asset
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CsvManager(private val context: Context) {

    // ─── Source record ────────────────────────────────────────────────────────

    data class SourceRecord(
        val assetNumber:   String,
        val department:    String,
        val routinePeriod: Int = 0   // 0 = blank/unknown, 6, 12, 24, or 60
    )

    // ─── Constants ────────────────────────────────────────────────────────────

    companion object {
        // location removed, department is unified
        val OUTPUT_HEADERS = arrayOf("asset_number", "department", "make", "model", "serial_number", "timestamp", "user_initials", "qa_location")

        private const val PREFS_NAME          = "asset_scanner_prefs"
        private const val KEY_SOURCE_URI      = "source_uri"
        private const val KEY_OUTPUT_FOLDER   = "output_folder_uri"
        private const val KEY_SCANNER_NAME    = "scanner_name"

        private val DATE_FORMAT = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss",   Locale.getDefault())

        /**
         * Builds the output filename.
         * If [scannerPrefix] is non-blank (e.g. "scanner_1") the result is
         * "scanner_1_qa_assets_26-02-2026.csv", otherwise "qa_assets_26-02-2026.csv".
         */
        fun fileNameForDate(date: Date = Date(), scannerPrefix: String = ""): String {
            val datePart = "qa_assets_${DATE_FORMAT.format(date)}.csv"
            return if (scannerPrefix.isBlank()) datePart
                   else "${scannerPrefix.trim()}_$datePart"
        }
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Scanner name

    /** e.g. "scanner_1" - used as a filename prefix. Blank means no prefix. */
    var scannerName: String
        get()      = prefs.getString(KEY_SCANNER_NAME, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SCANNER_NAME, value) }

    // Output folder

    /** Persists a user-selected output folder URI (from OpenDocumentTree). */
    fun saveOutputFolderUri(uri: Uri) {
        prefs.edit { putString(KEY_OUTPUT_FOLDER, uri.toString()) }
    }

    fun loadOutputFolderUri(): Uri? {
        val s = prefs.getString(KEY_OUTPUT_FOLDER, null) ?: return null
        return s.toUri()
    }

    fun clearOutputFolderUri() {
        prefs.edit { remove(KEY_OUTPUT_FOLDER) }
    }

    // Output file

    /** The filename for today (with scanner prefix if set). */
    private val todayFileName: String
        get() = fileNameForDate(scannerPrefix = scannerName)

    /**
     * Returns a [DocumentFile] for today's output, in the custom folder if one
     * has been set, otherwise in the app's default external files' directory.
     */
    private fun getOutputDocumentFile(create: Boolean = false): DocumentFile? {
        val folderUri = loadOutputFolderUri()
        return if (folderUri != null) {
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return null
            folder.findFile(todayFileName)
                ?: if (create) folder.createFile("text/csv", todayFileName) else null
        } else {
            // Fall back to app external files dir
            val file = File(context.getExternalFilesDir(null), todayFileName)
            if (create && !file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            DocumentFile.fromFile(file)
        }
    }

    // Human-readable output path string for the footer label
    val outputFilePath: String
        get() {
            val folderUri = loadOutputFolderUri()
            return if (folderUri != null) {
                // Show the folder path + filename
                val folder = DocumentFile.fromTreeUri(context, folderUri)
                val folderName = folder?.name ?: folderUri.lastPathSegment ?: "?"
                "$folderName/$todayFileName"
            } else {
                File(context.getExternalFilesDir(null), todayFileName).absolutePath
            }
        }

    // URI Persistence (source CSV)

    fun saveSourceUri(uri: Uri) =
        prefs.edit { putString(KEY_SOURCE_URI, uri.toString()) }

    fun loadSavedSourceUri(): Uri? =
        prefs.getString(KEY_SOURCE_URI, null)?.toUri()

    fun clearSourceUri() =
        prefs.edit { remove(KEY_SOURCE_URI) }

    // Source CSV Loading

    fun loadLookupMap(uri: Uri): Pair<Map<String, List<SourceRecord>>, Int> {
        val map = mutableMapOf<String, MutableList<SourceRecord>>()
        var assetCount = 0

        fun addToMap(key: String, record: SourceRecord) {
            if (key.isNotEmpty()) map.getOrPut(key.lowercase()) { mutableListOf() }.add(record)
        }

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            CSVReader(InputStreamReader(inputStream)).use { reader ->
                var line: Array<String>?
                var isFirstRow = true
                while (reader.readNext().also { line = it } != null) {
                    if (isFirstRow) { isFirstRow = false; continue }
                    val row = line ?: continue

                    val assetNumber   = row.getOrElse(0) { "" }.trim()
                    val serial        = row.getOrElse(1) { "" }.trim()
                    val department    = row.getOrElse(2) { "" }.trim()
                    val barcode       = row.getOrElse(3) { "" }.trim()
                    val routinePeriod = row.getOrElse(4) { "" }.trim().toIntOrNull() ?: 0

                    if (assetNumber.isEmpty()) continue
                    assetCount++

                    val record = SourceRecord(assetNumber, department, routinePeriod)
                    addToMap(assetNumber, record)
                    addToMap(serial,      record)
                    addToMap(barcode,     record)
                }
            }
        }
        return Pair(map, assetCount)
    }

    // Output CSV

    fun ensureOutputFileReady() {
        val doc = getOutputDocumentFile(create = true) ?: return
        // Write header only if the file is empty / newly created
        val isEmpty = context.contentResolver.openInputStream(doc.uri)?.use {
            it.available() == 0
        } ?: true

        if (isEmpty) {
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { os ->
                CSVWriter(OutputStreamWriter(os)).use { it.writeNext(OUTPUT_HEADERS) }
            }
        }
    }

    fun appendAsset(asset: Asset): Asset {
        val timestamp = TIME_FORMAT.format(Date())
        val saved = asset.copy(timestamp = timestamp)

        val doc = getOutputDocumentFile(create = true) ?: return saved

        context.contentResolver.openOutputStream(doc.uri, "wa")?.use { os ->
            CSVWriter(OutputStreamWriter(os)).use { writer ->
                writer.writeNext(arrayOf(
                    saved.assetNumber,
                    saved.department,
                    saved.make,
                    saved.model,
                    saved.serial,
                    saved.timestamp,
                    saved.userInitials,
                    saved.qaLocation
                ))
            }
        }
        return saved
    }

    fun readSavedAssets(): List<Asset> {
        val doc = getOutputDocumentFile(create = false) ?: return emptyList()
        val assets = mutableListOf<Asset>()
        context.contentResolver.openInputStream(doc.uri)?.use { inputStream ->
            CSVReader(InputStreamReader(inputStream)).use { reader ->
                var line: Array<String>?
                var isHeader = true
                while (reader.readNext().also { line = it } != null) {
                    if (isHeader) { isHeader = false; continue }
                    val row = line ?: continue
                    assets.add(Asset(
                        assetNumber  = row.getOrElse(0) { "" }.uppercase(),
                        department   = row.getOrElse(1) { "" },
                        make         = row.getOrElse(2) { "" },
                        model        = row.getOrElse(3) { "" },
                        serial       = row.getOrElse(4) { "" },
                        timestamp    = row.getOrElse(5) { "" },
                        isNew        = row.getOrElse(2) { "" }.isNotBlank() || row.getOrElse(3) { "" }.isNotBlank(),
                        userInitials = row.getOrElse(6) { "" },
                        qaLocation   = row.getOrElse(7) { "" }
                    ))
                }
            }
        }
        return assets
    }

    fun isAlreadyLogged(assetNumber: String): Boolean =
        readSavedAssets().any { it.assetNumber.equals(assetNumber, ignoreCase = true) }

    /**
     * Rewrites the output CSV omitting any rows whose asset_number is in [assetNumbers].
     * The header row is always preserved.
     */
    fun deleteAssets(assetNumbers: Set<String>) {
        val doc = getOutputDocumentFile(create = false) ?: return
        val lower = assetNumbers.map { it.lowercase() }.toSet()

        // Read all rows first
        val kept = mutableListOf<Array<String>>()
        context.contentResolver.openInputStream(doc.uri)?.use { inputStream ->
            CSVReader(InputStreamReader(inputStream)).use { reader ->
                var isHeader = true
                var line: Array<String>?
                while (reader.readNext().also { line = it } != null) {
                    val row = line ?: continue
                    if (isHeader) {
                        kept.add(row)   // always keep header
                        isHeader = false
                        continue
                    }
                    val rowAsset = row.getOrElse(0) { "" }.trim().lowercase()
                    if (rowAsset !in lower) kept.add(row)
                }
            }
        }

        // Rewrite entire file
        context.contentResolver.openOutputStream(doc.uri, "wt")?.use { os ->
            CSVWriter(OutputStreamWriter(os)).use { writer ->
                kept.forEach { writer.writeNext(it) }
            }
        }
    }

}
