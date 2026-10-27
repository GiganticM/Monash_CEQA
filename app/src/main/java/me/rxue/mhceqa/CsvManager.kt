package me.rxue.mhceqa

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import me.rxue.mhceqa.models.Asset
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
        // Found assets: pure asset numbers and departments for easy column copy
        val OUTPUT_HEADERS = arrayOf("asset_number", "department", "timestamp", "row_type")
        val OUTPUT_HEADERS_IO = arrayOf("asset_number", "department", "timestamp", "row_type", "condition_score")

        // New assets: complete equipment registration columns
        val NEW_OUTPUT_HEADERS = arrayOf("asset_number", "department", "make", "model", "serial_number", "timestamp", "user_initials")
        val NEW_OUTPUT_HEADERS_IO = arrayOf("asset_number", "department", "make", "model", "serial_number", "timestamp", "user_initials", "condition_score")

        private const val PREFS_NAME          = "asset_scanner_prefs"
        private const val KEY_SOURCE_URI      = "source_uri"
        private const val KEY_SOURCE_NAME     = "source_file_name"
        private const val KEY_OUTPUT_FOLDER   = "output_folder_uri"
        private const val KEY_SCANNER_NAME    = "scanner_name"

        private val DATE_FORMAT = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss",   Locale.getDefault())

        fun normalizeTimestamp(ts: String): String {
            val clean = ts.trim()
            if (clean.isEmpty()) return "00:00:00"
            val parts = clean.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val s = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return "%02d:%02d:%02d".format(h, m, s)
        }

        /**
         * Builds the output filename.
         * If [scannerPrefix] is non-blank (e.g. "1") and [sessionType] is "IO":
         * "1_io_26-02-2026.csv", otherwise "io_26-02-2026.csv".
         * If [isNewAssets] is true:
         * "1_io_26-02-2026_NEW.csv".
         */
        fun fileNameForDate(
            date: Date = Date(),
            scannerPrefix: String = "",
            sessionType: String = SessionActivity.SESSION_TYPE_QA,
            isNewAssets: Boolean = false
        ): String {
            val prefix = if (sessionType.equals(SessionActivity.SESSION_TYPE_IO, ignoreCase = true)) "io" else "qa"
            val suffix = if (isNewAssets) "_NEW.csv" else ".csv"
            val datePart = "${prefix}_${DATE_FORMAT.format(date)}$suffix"
            return if (scannerPrefix.isBlank()) datePart
                   else "${scannerPrefix.trim()}_$datePart"
        }
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Scanner name

    /** e.g. "1" - used as a filename prefix. Blank means no prefix. */
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

    // Session type & Output file

    val sessionType: String
        get() = prefs.getString(SessionActivity.KEY_SESSION_TYPE, SessionActivity.SESSION_TYPE_QA) ?: SessionActivity.SESSION_TYPE_QA

    /** The filename for today's found assets (e.g. 1_qa_23-10-2026.csv). */
    val todayFileName: String
        get() = fileNameForDate(scannerPrefix = scannerName, sessionType = sessionType, isNewAssets = false)

    /** The filename for today's newly registered assets (e.g. 1_qa_23-10-2026_NEW.csv). */
    val todayNewFileName: String
        get() = fileNameForDate(scannerPrefix = scannerName, sessionType = sessionType, isNewAssets = true)

    /**
     * Returns a [DocumentFile] for today's output, in the custom folder if one
     * has been set, otherwise in the app's default external files directory.
     * If today's file was previously archived, it will read from the Archive folder
     * or restore it to the main folder if new scans are added.
     */
    private fun getOutputDocumentFile(create: Boolean = false, isNewAssets: Boolean = false): DocumentFile? {
        val targetName = if (isNewAssets) todayNewFileName else todayFileName
        val folderUri = loadOutputFolderUri()
        return if (folderUri != null) {
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return null
            val existing = folder.findFile(targetName)
            if (existing != null) return existing

            val archiveFolder = folder.findFile("Archive")
            val archivedToday = archiveFolder?.findFile(targetName)

            if (create) {
                if (archivedToday != null) {
                    val restored = folder.createFile("text/csv", targetName)
                    if (restored != null) {
                        try {
                            context.contentResolver.openInputStream(archivedToday.uri)?.use { inStream ->
                                context.contentResolver.openOutputStream(restored.uri, "wt")?.use { outStream ->
                                    inStream.copyTo(outStream)
                                }
                            }
                            archivedToday.delete()
                            return restored
                        } catch (_: Exception) {}
                    }
                }
                folder.createFile("text/csv", targetName)
            } else {
                archivedToday
            }
        } else {
            val dir = context.getExternalFilesDir(null)
            val file = File(dir, targetName)
            if (file.exists()) return DocumentFile.fromFile(file)

            val archiveFile = File(File(dir, "Archive"), targetName)
            if (create) {
                if (archiveFile.exists()) {
                    archiveFile.copyTo(file, overwrite = true)
                    archiveFile.delete()
                    return DocumentFile.fromFile(file)
                }
                file.parentFile?.mkdirs()
                file.createNewFile()
                DocumentFile.fromFile(file)
            } else {
                if (archiveFile.exists()) DocumentFile.fromFile(archiveFile)
                else null
            }
        }
    }

    /**
     * Locates or creates the 'Archive' subdirectory under the configured output folder.
     */
    fun getOrCreateArchiveFolder(): DocumentFile? {
        val folderUri = loadOutputFolderUri()
        return if (folderUri != null) {
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return null
            folder.findFile("Archive") ?: folder.createDirectory("Archive")
        } else {
            val dir = File(context.getExternalFilesDir(null), "Archive")
            if (!dir.exists()) dir.mkdirs()
            DocumentFile.fromFile(dir)
        }
    }

    /**
     * Lists all pending output CSV files in the root output folder (excluding the source CSV and Archive folder).
     */
    fun getAllPendingOutputCsvFiles(): List<DocumentFile> {
        val folderUri = loadOutputFolderUri()
        val sourceName = loadSavedSourceName() ?: ""
        return if (folderUri != null) {
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()
            folder.listFiles().filter { doc ->
                doc.isFile &&
                doc.name?.endsWith(".csv", ignoreCase = true) == true &&
                !doc.name.equals(sourceName, ignoreCase = true) &&
                !doc.name.equals("Archive", ignoreCase = true)
            }
        } else {
            val dir = context.getExternalFilesDir(null) ?: return emptyList()
            dir.listFiles()?.filter { file ->
                file.isFile &&
                file.name.endsWith(".csv", ignoreCase = true) &&
                !file.name.equals(sourceName, ignoreCase = true)
            }?.map { DocumentFile.fromFile(it) } ?: emptyList()
        }
    }

    /**
     * If a NEW file already exists (e.g. captured earlier in the day or stored in Archive),
     * ensures it is restored to the main folder alongside its base file so they sync together.
     * If no NEW file exists, it will NOT create one.
     */
    fun ensurePairedOutputFiles() {
        val todayNew = getOutputDocumentFile(create = false, isNewAssets = true)
        if (todayNew != null) {
            getOutputDocumentFile(create = true, isNewAssets = true)
        }

        val pending = getAllPendingOutputCsvFiles()
        val folderUri = loadOutputFolderUri()
        if (folderUri != null) {
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return
            val archiveFolder = folder.findFile("Archive")

            for (doc in pending) {
                val name = doc.name ?: continue
                val partnerName = if (name.endsWith("_NEW.csv", ignoreCase = true)) {
                    name.substring(0, name.length - 8) + ".csv"
                } else if (name.endsWith(".csv", ignoreCase = true)) {
                    name.substring(0, name.length - 4) + "_NEW.csv"
                } else continue

                if (pending.any { it.name.equals(partnerName, ignoreCase = true) }) continue

                // Only restore from Archive if it actually exists! Do not create a non-existent partner.
                val archivedPartner = archiveFolder?.findFile(partnerName)
                if (archivedPartner != null) {
                    val restored = folder.createFile("text/csv", partnerName)
                    if (restored != null) {
                        try {
                            context.contentResolver.openInputStream(archivedPartner.uri)?.use { inStream ->
                                context.contentResolver.openOutputStream(restored.uri, "wt")?.use { outStream ->
                                    inStream.copyTo(outStream)
                                }
                            }
                            archivedPartner.delete()
                        } catch (e: Exception) {
                            Log.e("CsvManager", "Failed to restore partner $partnerName from Archive", e)
                        }
                    }
                }
            }
        } else {
            val dir = context.getExternalFilesDir(null) ?: return
            val archiveDir = File(dir, "Archive")

            for (doc in pending) {
                val name = doc.name ?: continue
                val partnerName = if (name.endsWith("_NEW.csv", ignoreCase = true)) {
                    name.substring(0, name.length - 8) + ".csv"
                } else if (name.endsWith(".csv", ignoreCase = true)) {
                    name.substring(0, name.length - 4) + "_NEW.csv"
                } else continue

                if (pending.any { it.name.equals(partnerName, ignoreCase = true) }) continue

                val partnerFile = File(dir, partnerName)
                val archivedFile = File(archiveDir, partnerName)

                // Only restore from Archive if it actually exists! Do not create a non-existent partner.
                if (archivedFile.exists()) {
                    archivedFile.copyTo(partnerFile, overwrite = true)
                    archivedFile.delete()
                }
            }
        }
    }

    /**
     * Moves a successfully synced CSV document to the 'Archive' subfolder.
     */
    fun archiveFile(sourceDoc: DocumentFile): Boolean {
        val archiveFolder = getOrCreateArchiveFolder() ?: return false
        val fileName = sourceDoc.name ?: return false

        // Remove any old copy in Archive before saving the new one
        archiveFolder.findFile(fileName)?.delete()

        val destDoc = archiveFolder.createFile("text/csv", fileName) ?: return false
        var success = false
        try {
            context.contentResolver.openInputStream(sourceDoc.uri)?.use { inStream ->
                context.contentResolver.openOutputStream(destDoc.uri, "wt")?.use { outStream ->
                    inStream.copyTo(outStream)
                    success = true
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CsvManager", "Failed to archive file $fileName", e)
            destDoc.delete()
            return false
        }

        if (success) {
            sourceDoc.delete()
            return true
        }
        return false
    }

    /**
     * Reads the current output CSV file text content and returns (fileName, content).
     */
    fun readCurrentOutputFileText(): Pair<String, String>? {
        val doc = getOutputDocumentFile(create = false) ?: return null
        val content = context.contentResolver.openInputStream(doc.uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        } ?: return null
        val name = doc.name ?: todayFileName
        return Pair(name, content)
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

    val outputFolderPath: String
        get() {
            val folderUri = loadOutputFolderUri()
            return if (folderUri != null) {
                val folder = DocumentFile.fromTreeUri(context, folderUri)
                folder?.name ?: folderUri.lastPathSegment ?: "Custom Folder"
            } else {
                context.getExternalFilesDir(null)?.absolutePath ?: "App Storage"
            }
        }

    // URI Persistence (source CSV)

    fun saveSourceUri(uri: Uri, fileName: String? = null) {
        SourceAssetRepository.invalidate()
        prefs.edit {
            putString(KEY_SOURCE_URI, uri.toString())
            if (!fileName.isNullOrBlank()) {
                putString(KEY_SOURCE_NAME, fileName)
            }
        }
    }

    fun loadSavedSourceUri(): Uri? =
        prefs.getString(KEY_SOURCE_URI, null)?.toUri()

    fun loadSavedSourceName(): String? =
        prefs.getString(KEY_SOURCE_NAME, null)

    fun clearSourceUri() {
        SourceAssetRepository.invalidate()
        prefs.edit {
            remove(KEY_SOURCE_URI)
            remove(KEY_SOURCE_NAME)
        }
    }

    /**
     * Writes updated source asset CSV content to disk.
     * Tries: 1. Existing saved source_uri via contentResolver.
     *        2. /sdcard/Source Asset List/<fileName>
     *        3. App external files directory.
     */
    fun saveSourceAssetContent(fileName: String, content: String): Uri? {
        val savedUri = loadSavedSourceUri()
        if (savedUri != null) {
            try {
                context.contentResolver.openOutputStream(savedUri, "wt")?.use { os ->
                    os.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
                }
                saveSourceUri(savedUri, fileName)
                return savedUri
            } catch (e: Exception) {
                android.util.Log.w("CsvManager", "Failed to overwrite savedUri via contentResolver", e)
            }
        }

        try {
            val sdcardDir = File("/sdcard/Source Asset List")
            if (sdcardDir.exists() || sdcardDir.mkdirs()) {
                val file = File(sdcardDir, fileName)
                file.writeText(content, Charsets.UTF_8)
                val fileUri = Uri.fromFile(file)
                saveSourceUri(fileUri, fileName)
                return fileUri
            }
        } catch (e: Exception) {
            android.util.Log.w("CsvManager", "Failed to write to /sdcard/Source Asset List", e)
        }

        try {
            val appDir = File(context.getExternalFilesDir(null), "SourceAssetList")
            appDir.mkdirs()
            val file = File(appDir, fileName)
            file.writeText(content, Charsets.UTF_8)
            val fileUri = Uri.fromFile(file)
            saveSourceUri(fileUri, fileName)
            return fileUri
        } catch (e: Exception) {
            android.util.Log.e("CsvManager", "Failed to write source asset file", e)
        }
        return null
    }

    // Source CSV Loading

    fun loadLookupMap(uri: Uri): Pair<Map<String, List<SourceRecord>>, Int> {
        return kotlinx.coroutines.runBlocking {
            SourceAssetRepository.getOrLoad(context, uri)
        }
    }

    // Output CSV

    fun getOutputHeaders(isNewAssets: Boolean): Array<String> {
        val isIo = sessionType.equals(SessionActivity.SESSION_TYPE_IO, ignoreCase = true)
        return if (isNewAssets) {
            if (isIo) NEW_OUTPUT_HEADERS_IO else NEW_OUTPUT_HEADERS
        } else {
            if (isIo) OUTPUT_HEADERS_IO else OUTPUT_HEADERS
        }
    }

    /**
     * Appends a blank separator row (if the file already has prior content),
     * then a session header row. Also creates the file and writes the CSV header
     * if this is the first session of the day - file creation is intentionally
     * deferred until a session actually starts.
     */
    fun appendSessionHeader(initials: String, location: String) {
        val timestamp = TIME_FORMAT.format(Date())
        val isIo = sessionType.equals(SessionActivity.SESSION_TYPE_IO, ignoreCase = true)

        // Create file and write CSV header if it doesn't exist yet
        val doc = getOutputDocumentFile(create = true, isNewAssets = false) ?: return
        val isEmpty = context.contentResolver.openInputStream(doc.uri)?.use {
            it.available() == 0
        } ?: true
        if (isEmpty) {
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { os ->
                CSVWriter(OutputStreamWriter(os)).use { it.writeNext(getOutputHeaders(isNewAssets = false)) }
            }
        }

        // Check if file already has content beyond the header row
        val hasContent = context.contentResolver.openInputStream(doc.uri)?.use { inputStream ->
            CSVReader(InputStreamReader(inputStream)).use { reader ->
                var count = 0
                while (reader.readNext() != null) count++
                count > 1
            }
        } ?: false

        context.contentResolver.openOutputStream(doc.uri, "wa")?.use { os ->
            CSVWriter(OutputStreamWriter(os)).use { writer ->
                val emptyRow = if (isIo) arrayOf("", "", "", "", "") else arrayOf("", "", "", "")
                val sessionRow = if (isIo) arrayOf(initials, location, timestamp, "session", "") else arrayOf(initials, location, timestamp, "session")
                if (hasContent) writer.writeNext(emptyRow)
                writer.writeNext(sessionRow)
            }
        }
    }

    fun appendAsset(asset: Asset): Asset {
        val timestamp = TIME_FORMAT.format(Date())
        val saved = asset.copy(timestamp = timestamp)
        val isIo = sessionType.equals(SessionActivity.SESSION_TYPE_IO, ignoreCase = true)

        if (saved.isNew) {
            val doc = getOutputDocumentFile(create = true, isNewAssets = true) ?: return saved
            val isEmpty = context.contentResolver.openInputStream(doc.uri)?.use {
                it.available() == 0
            } ?: true
            if (isEmpty) {
                context.contentResolver.openOutputStream(doc.uri, "wt")?.use { os ->
                    CSVWriter(OutputStreamWriter(os)).use { it.writeNext(getOutputHeaders(isNewAssets = true)) }
                }
            }

            context.contentResolver.openOutputStream(doc.uri, "wa")?.use { os ->
                CSVWriter(OutputStreamWriter(os)).use { writer ->
                    val row = if (isIo) {
                        arrayOf(
                            saved.assetNumber,
                            saved.department,
                            saved.make,
                            saved.model,
                            saved.serial,
                            saved.timestamp,
                            saved.userInitials,
                            saved.conditionScore
                        )
                    } else {
                        arrayOf(
                            saved.assetNumber,
                            saved.department,
                            saved.make,
                            saved.model,
                            saved.serial,
                            saved.timestamp,
                            saved.userInitials
                        )
                    }
                    writer.writeNext(row)
                }
            }
        } else {
            val doc = getOutputDocumentFile(create = true, isNewAssets = false) ?: return saved
            val isEmpty = context.contentResolver.openInputStream(doc.uri)?.use {
                it.available() == 0
            } ?: true
            if (isEmpty) {
                context.contentResolver.openOutputStream(doc.uri, "wt")?.use { os ->
                    CSVWriter(OutputStreamWriter(os)).use { it.writeNext(getOutputHeaders(isNewAssets = false)) }
                }
            }

            context.contentResolver.openOutputStream(doc.uri, "wa")?.use { os ->
                CSVWriter(OutputStreamWriter(os)).use { writer ->
                    val row = if (isIo) {
                        arrayOf(
                            saved.assetNumber,
                            saved.department,
                            saved.timestamp,
                            "found",
                            saved.conditionScore
                        )
                    } else {
                        arrayOf(
                            saved.assetNumber,
                            saved.department,
                            saved.timestamp,
                            "found"
                        )
                    }
                    writer.writeNext(row)
                }
            }
        }
        return saved
    }

    fun readSavedAssets(): List<Asset> {
        val assets = mutableListOf<Asset>()
        var currentSessionUser = ""

        // 1. Read main/found assets file
        val foundDoc = getOutputDocumentFile(create = false, isNewAssets = false)
        if (foundDoc != null) {
            context.contentResolver.openInputStream(foundDoc.uri)?.use { inputStream ->
                CSVReader(InputStreamReader(inputStream)).use { reader ->
                    var line: Array<String>?
                    var isHeader = true
                    while (reader.readNext().also { line = it } != null) {
                        if (isHeader) { isHeader = false; continue }
                        val row = line ?: continue
                        if (row.all { it.isBlank() }) continue

                        val isSessionHeader = row.any { it.equals("session", ignoreCase = true) }
                        if (isSessionHeader) {
                            currentSessionUser = row.getOrElse(0) { "" }
                            val loc = row.getOrElse(1) { "" }
                            val time = if (row.size <= 4) row.getOrElse(2) { "" } else row.getOrElse(5) { "" }
                            assets.add(Asset(
                                assetNumber     = currentSessionUser,
                                department      = loc,
                                timestamp       = time,
                                isSessionHeader = true,
                                userInitials    = currentSessionUser
                            ))
                        } else {
                            val isFoundRow = row.getOrElse(3) { "" }.equals("found", ignoreCase = true)
                            val isOldFormatNew = !isFoundRow && row.size > 4 && (row.getOrElse(2) { "" }.isNotBlank() || row.getOrElse(3) { "" }.isNotBlank())
                            val assetNum = row.getOrElse(0) { "" }.uppercase()
                            val dept = row.getOrElse(1) { "" }
                            val time = if (isFoundRow || row.size <= 4) row.getOrElse(2) { "" } else row.getOrElse(5) { "" }
                            val score = if (isFoundRow) row.getOrElse(4) { "" } else ""

                            assets.add(Asset(
                                assetNumber    = assetNum,
                                department     = dept,
                                make           = if (isOldFormatNew) row.getOrElse(2) { "" } else "",
                                model          = if (isOldFormatNew) row.getOrElse(3) { "" } else "",
                                serial         = if (isOldFormatNew) row.getOrElse(4) { "" } else "",
                                timestamp      = time,
                                isNew          = isOldFormatNew,
                                userInitials   = currentSessionUser,
                                conditionScore = score
                            ))
                        }
                    }
                }
            }
        }

        // 2. Read new assets file (if exists)
        val newDoc = getOutputDocumentFile(create = false, isNewAssets = true)
        if (newDoc != null) {
            context.contentResolver.openInputStream(newDoc.uri)?.use { inputStream ->
                CSVReader(InputStreamReader(inputStream)).use { reader ->
                    var line: Array<String>?
                    var isHeader = true
                    while (reader.readNext().also { line = it } != null) {
                        if (isHeader) { isHeader = false; continue }
                        val row = line ?: continue
                        if (row.all { it.isBlank() }) continue

                        val assetNum = row.getOrElse(0) { "" }.uppercase()
                        val dept = row.getOrElse(1) { "" }
                        val make = row.getOrElse(2) { "" }
                        val model = row.getOrElse(3) { "" }
                        val serial = row.getOrElse(4) { "" }
                        val time = row.getOrElse(5) { "" }
                        val initials = row.getOrElse(6) { currentSessionUser }
                        val score = row.getOrElse(7) { "" }

                        assets.add(Asset(
                            assetNumber    = assetNum,
                            department     = dept,
                            make           = make,
                            model          = model,
                            serial         = serial,
                            timestamp      = time,
                            isNew          = true,
                            userInitials   = initials,
                            conditionScore = score
                        ))
                    }
                }
            }
        }

        // Sort chronologically by timestamp so that assets from both Found and New
        // CSV files are merged in true time sequence rather than grouped by file.
        assets.sortBy { normalizeTimestamp(it.timestamp) }

        return assets
    }

    /**
     * Updates the condition score for an existing asset in the output CSV file (Found or New).
     * Rewrites the row with the new condition score column value.
     */
    fun updateAssetConditionScore(assetNumber: String, score: String): Boolean {
        val target = assetNumber.trim()
        if (target.isEmpty()) return false

        // 1. Try Found assets file first
        val foundDoc = getOutputDocumentFile(create = false, isNewAssets = false)
        if (foundDoc != null) {
            val rows = mutableListOf<Array<String>>()
            var found = false
            context.contentResolver.openInputStream(foundDoc.uri)?.use { stream ->
                CSVReader(InputStreamReader(stream)).use { reader ->
                    var line: Array<String>?
                    while (reader.readNext().also { line = it } != null) {
                        val row = line ?: continue
                        if (!found && row.isNotEmpty() && !row.any { it.equals("session", ignoreCase = true) } && row[0].equals(target, ignoreCase = true)) {
                            found = true
                            // Format: [assetNumber, department, timestamp, "found", conditionScore]
                            val newRow = when {
                                row.size >= 5 -> row.copyOf().also { it[4] = score }
                                row.size == 4 -> arrayOf(row[0], row[1], row[2], row[3], score)
                                else -> row + score
                            }
                            rows.add(newRow)
                        } else {
                            rows.add(row)
                        }
                    }
                }
            }
            if (found) {
                val doc = getOutputDocumentFile(create = true, isNewAssets = false) ?: return false
                context.contentResolver.openOutputStream(doc.uri, "wt")?.use { os ->
                    CSVWriter(OutputStreamWriter(os)).use { writer ->
                        rows.forEach { writer.writeNext(it) }
                    }
                }
                return true
            }
        }

        // 2. Try New assets file
        val newDoc = getOutputDocumentFile(create = false, isNewAssets = true)
        if (newDoc != null) {
            val rows = mutableListOf<Array<String>>()
            var found = false
            context.contentResolver.openInputStream(newDoc.uri)?.use { stream ->
                CSVReader(InputStreamReader(stream)).use { reader ->
                    var line: Array<String>?
                    while (reader.readNext().also { line = it } != null) {
                        val row = line ?: continue
                        if (!found && row.isNotEmpty() && row[0].equals(target, ignoreCase = true)) {
                            found = true
                            // Format: [assetNumber, department, make, model, serial, timestamp, initials, conditionScore]
                            val newRow = when {
                                row.size >= 8 -> row.copyOf().also { it[7] = score }
                                row.size == 7 -> arrayOf(row[0], row[1], row[2], row[3], row[4], row[5], row[6], score)
                                else -> row + score
                            }
                            rows.add(newRow)
                        } else {
                            rows.add(row)
                        }
                    }
                }
            }
            if (found) {
                val doc = getOutputDocumentFile(create = true, isNewAssets = true) ?: return false
                context.contentResolver.openOutputStream(doc.uri, "wt")?.use { os ->
                    CSVWriter(OutputStreamWriter(os)).use { writer ->
                        rows.forEach { writer.writeNext(it) }
                    }
                }
                return true
            }
        }

        return false
    }

    fun isAlreadyLogged(assetNumber: String): Boolean =
        readSavedAssets().any { it.assetNumber.equals(assetNumber, ignoreCase = true) }

    /**
     * Rewrites the output CSV omitting any rows whose asset_number is in [assetNumbers].
     * Cleans both Found and New files.
     */
    fun deleteAssets(assetNumbers: Set<String>) {
        val lower = assetNumbers.map { it.lowercase() }.toSet()

        fun filterFile(isNew: Boolean) {
            val doc = getOutputDocumentFile(create = false, isNewAssets = isNew) ?: return
            val kept = mutableListOf<Array<String>>()
            context.contentResolver.openInputStream(doc.uri)?.use { inputStream ->
                CSVReader(InputStreamReader(inputStream)).use { reader ->
                    var isHeader = true
                    var line: Array<String>?
                    while (reader.readNext().also { line = it } != null) {
                        val row = line ?: continue
                        if (isHeader) {
                            kept.add(row)
                            isHeader = false
                            continue
                        }
                        val rowAsset = row.getOrElse(0) { "" }.trim().lowercase()
                        if (rowAsset !in lower) kept.add(row)
                    }
                }
            }
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { os ->
                CSVWriter(OutputStreamWriter(os)).use { writer ->
                    kept.forEach { writer.writeNext(it) }
                }
            }
        }

        filterFile(isNew = false)
        filterFile(isNew = true)
    }

}
