package me.rxue.mhceqa

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object DriveSyncManager {

    private const val TAG = "DriveSyncManager"

    const val PREFS_NAME             = "asset_scanner_prefs"
    const val KEY_WEBHOOK_URL        = "drive_webhook_url"
    const val KEY_CUSTOM_FOLDER_ID   = "drive_custom_folder_id"
    const val KEY_AUTO_SYNC_ENABLED  = "drive_auto_sync_enabled"
    const val KEY_PENDING_SYNC_FILE  = "drive_pending_sync_file"
    const val KEY_LAST_SYNC_STATUS   = "drive_last_sync_status"
    const val KEY_LAST_SYNC_TIME     = "drive_last_sync_time"

    // Source asset list cloud push/update
    const val KEY_SOURCE_FOLDER_ID       = "drive_source_folder_id"
    const val DEFAULT_SOURCE_FOLDER_ID   = "1TM9jjPbC6bE8pJHxkM7w9o5Jq03pf02W"
    const val KEY_SOURCE_LAST_MODIFIED   = "drive_source_last_modified"
    const val KEY_SOURCE_AUTO_UPDATE     = "drive_source_auto_update"
    const val KEY_SOURCE_LAST_CHECK_TIME = "drive_source_last_check_time"

    data class SyncResult(
        val success: Boolean,
        val message: String,
        val overwritten: Boolean = false
    )

    fun getWebhookUrl(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WEBHOOK_URL, "") ?: ""

    fun saveWebhookUrl(context: Context, url: String) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_WEBHOOK_URL, url.trim())
            .apply()

    fun getCustomFolderId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_FOLDER_ID, "") ?: ""

    fun saveCustomFolderId(context: Context, folderId: String) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CUSTOM_FOLDER_ID, folderId.trim())
            .apply()

    fun isAutoSyncEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SYNC_ENABLED, true)

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_SYNC_ENABLED, enabled)
            .apply()

    fun getSourceFolderId(context: Context): String {
        val s = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE_FOLDER_ID, "") ?: ""
        return if (s.isBlank()) DEFAULT_SOURCE_FOLDER_ID else s.trim()
    }

    fun saveSourceFolderId(context: Context, folderId: String) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SOURCE_FOLDER_ID, folderId.trim())
            .apply()

    fun isSourceAutoUpdateEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SOURCE_AUTO_UPDATE, false)

    fun setSourceAutoUpdateEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SOURCE_AUTO_UPDATE, enabled)
            .apply()

    fun getSourceLastModified(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_SOURCE_LAST_MODIFIED, 0L)

    fun setSourceLastModified(context: Context, timestamp: Long) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SOURCE_LAST_MODIFIED, timestamp)
            .apply()

    fun getSourceLastCheckTime(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE_LAST_CHECK_TIME, "Never") ?: "Never"

    fun saveSourceLastCheckTime(context: Context, timeStr: String) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SOURCE_LAST_CHECK_TIME, timeStr)
            .apply()

    fun getPendingSyncFile(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_SYNC_FILE, null)

    fun setPendingSyncFile(context: Context, fileName: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (fileName != null) {
            prefs.putString(KEY_PENDING_SYNC_FILE, fileName)
        } else {
            prefs.remove(KEY_PENDING_SYNC_FILE)
        }
        prefs.apply()
    }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private const val PREF_SYNCED_HASH_PREFIX = "drive_synced_hash_"

    fun computeSha256(content: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun getLastSyncedHash(context: Context, fileName: String): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_SYNCED_HASH_PREFIX + fileName, null)

    fun saveLastSyncedHash(context: Context, fileName: String, hash: String) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_SYNCED_HASH_PREFIX + fileName, hash)
            .apply()

    fun isCsvEmptyOrHeaderOnly(content: String): Boolean {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.size <= 1
    }

    /**
     * Uploads the specified CSV content to the configured Google Apps Script Webhook.
     * Automatically retries up to 3 times on transient network failures.
     */
    suspend fun syncCsv(
        context: Context,
        fileName: String,
        fileContent: String,
        scannerNumber: String,
        folderId: String? = null
    ): SyncResult = withContext(Dispatchers.IO) {
        val webhookUrl = getWebhookUrl(context)
        if (webhookUrl.isBlank()) {
            return@withContext SyncResult(
                success = false,
                message = "Google Drive Webhook URL not configured. Set it in Settings."
            )
        }

        if (!isOnline(context)) {
            setPendingSyncFile(context, fileName)
            return@withContext SyncResult(
                success = false,
                message = "Device is offline. File queued to sync automatically when online."
            )
        }

        val payload = JSONObject().apply {
            put("scannerNumber", scannerNumber)
            put("fileName", fileName)
            put("fileContent", fileContent)
            val overrideFolder = folderId?.takeIf { it.isNotBlank() } ?: getCustomFolderId(context)
            if (overrideFolder.isNotBlank()) {
                put("folderId", overrideFolder)
            }
        }

        var lastResult = SyncResult(success = false, message = "Sync failed")
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            try {
                val result = postJsonToGoogleAppsScript(webhookUrl, payload.toString())
                if (result.success) {
                    setPendingSyncFile(context, null)
                    val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putString(KEY_LAST_SYNC_STATUS, "Synced at $timeStr")
                        .putString(KEY_LAST_SYNC_TIME, timeStr)
                        .apply()
                    return@withContext result
                }
                lastResult = result
                Log.w(TAG, "Sync attempt $attempt/$maxAttempts for $fileName failed: ${result.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Sync attempt $attempt/$maxAttempts for $fileName exception", e)
                lastResult = SyncResult(success = false, message = "Network error: ${e.localizedMessage ?: "Unknown"}")
            }
            if (attempt < maxAttempts) {
                kotlinx.coroutines.delay(attempt * 1500L)
            }
        }

        setPendingSyncFile(context, fileName)
        lastResult
    }

    data class BatchSyncResult(
        val totalFiles: Int,
        val successCount: Int,
        val failedCount: Int,
        val bypassedCount: Int = 0,
        val message: String
    )

    /**
     * Finds all local CSV files in the output folder, checks if they have been modified,
     * bypasses unmodified files, uploads new/modified files with retries,
     * and on successful upload moves the local copy to the 'Archive' folder.
     */
    suspend fun syncAndArchiveAllFiles(
        context: Context,
        csvManager: CsvManager,
        scannerNumber: String,
        folderId: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): BatchSyncResult = withContext(Dispatchers.IO) {
        val webhookUrl = getWebhookUrl(context)
        if (webhookUrl.isBlank()) {
            return@withContext BatchSyncResult(
                totalFiles = 0,
                successCount = 0,
                failedCount = 0,
                message = "Google Drive Webhook URL not configured. Set it in Settings."
            )
        }

        onProgress?.invoke("Connecting to Google Drive...")

        // Ensure paired files (e.g. qa_...csv and qa_..._NEW.csv, or io_...csv and io_..._NEW.csv)
        // exist and are present in the output folder
        csvManager.ensurePairedOutputFiles()

        val pendingFiles = csvManager.getAllPendingOutputCsvFiles()
        if (pendingFiles.isEmpty()) {
            return@withContext BatchSyncResult(
                totalFiles = 0,
                successCount = 0,
                failedCount = 0,
                message = "No CSV files found to sync in folder."
            )
        }

        if (!isOnline(context)) {
            setPendingSyncFile(context, "batch_${pendingFiles.size}")
            return@withContext BatchSyncResult(
                totalFiles = pendingFiles.size,
                successCount = 0,
                failedCount = pendingFiles.size,
                message = "Offline: ${pendingFiles.size} file(s) queued for sync when online."
            )
        }

        // Group files into pairs based on their session/date base name:
        // e.g. "1_qa_26-10-2026.csv" and "1_qa_26-10-2026_NEW.csv" form a single pair.
        val pairGroups = mutableMapOf<String, MutableList<androidx.documentfile.provider.DocumentFile>>()
        for (fileDoc in pendingFiles) {
            val name = fileDoc.name ?: continue
            val baseKey = when {
                name.endsWith("_NEW.csv", ignoreCase = true) -> name.substring(0, name.length - 8)
                name.endsWith(".csv", ignoreCase = true) -> name.substring(0, name.length - 4)
                else -> name
            }
            pairGroups.getOrPut(baseKey) { mutableListOf() }.add(fileDoc)
        }

        var successCount = 0
        var failedCount = 0
        var bypassedCount = 0

        val filesToUpload = mutableListOf<Triple<androidx.documentfile.provider.DocumentFile, String, String>>()

        for ((pairKey, filesInPair) in pairGroups) {
            // Sort so base file is uploaded first, then NEW file:
            // [qa_...csv, qa_..._NEW.csv]
            val sortedPairFiles = filesInPair.sortedBy { if (it.name?.endsWith("_NEW.csv", ignoreCase = true) == true) 1 else 0 }

            val fileData = sortedPairFiles.mapNotNull { doc ->
                val fileName = doc.name ?: return@mapNotNull null
                val content = try {
                    context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    } ?: ""
                } catch (e: Exception) {
                    Log.e(TAG, "Failed reading $fileName", e)
                    null
                }
                if (content == null) null
                else Triple(doc, fileName, content)
            }

            if (fileData.size != sortedPairFiles.size) {
                failedCount += (sortedPairFiles.size - fileData.size)
                continue
            }

            // Check if all files in the pair are unmodified since the last sync
            val allUnmodified = fileData.all { (_, fileName, content) ->
                val currentHash = computeSha256(content)
                val lastSyncedHash = getLastSyncedHash(context, fileName)
                lastSyncedHash != null && lastSyncedHash == currentHash
            }

            if (allUnmodified) {
                Log.i(TAG, "Pair $pairKey is unmodified since last sync. Bypassing upload.")
                bypassedCount += fileData.size
                fileData.forEach { (doc, _, _) ->
                    csvManager.archiveFile(doc)
                }
                continue
            }

            // If any file in the pair was modified, queue both files for upload
            filesToUpload.addAll(fileData)
        }

        val totalToUpload = filesToUpload.size
        var uploadIndex = 0

        for ((doc, fileName, content) in filesToUpload) {
            uploadIndex++
            onProgress?.invoke("Uploading records ($uploadIndex/$totalToUpload)...")
            Log.i(TAG, "Uploading ($uploadIndex/$totalToUpload) $fileName to Google Drive")

            val currentHash = computeSha256(content)
            val result = syncCsv(
                context = context,
                fileName = fileName,
                fileContent = content,
                scannerNumber = scannerNumber,
                folderId = folderId
            )

            if (result.success) {
                saveLastSyncedHash(context, fileName, currentHash)
                csvManager.archiveFile(doc)
                successCount++
            } else {
                failedCount++
            }
        }

        if (successCount > 0 && failedCount == 0) {
            setPendingSyncFile(context, null)
        }

        val summaryMessage = when {
            successCount > 0 && failedCount == 0 -> {
                val bypassedMsg = if (bypassedCount > 0) " ($bypassedCount up to date)" else ""
                "✓ Synced & archived $successCount file(s) in Google Drive$bypassedMsg"
            }
            successCount == 0 && failedCount == 0 && bypassedCount > 0 -> {
                "✓ All $bypassedCount file(s) up to date — sync bypassed"
            }
            successCount > 0 && failedCount > 0 -> {
                "Synced $successCount file(s), $failedCount failed"
            }
            else -> {
                "✗ Sync failed for $failedCount file(s)"
            }
        }

        BatchSyncResult(
            totalFiles = pendingFiles.size,
            successCount = successCount,
            failedCount = failedCount,
            bypassedCount = bypassedCount,
            message = summaryMessage
        )
    }

    sealed class SourceAssetUpdateResult {
        data class Updated(val fileName: String, val lastUpdated: Long, val assetCount: Int) : SourceAssetUpdateResult()
        object UpToDate : SourceAssetUpdateResult()
        data class Failed(val error: String) : SourceAssetUpdateResult()
        object Offline : SourceAssetUpdateResult()
        object NoWebhook : SourceAssetUpdateResult()
    }

    /**
     * Checks if Google Drive has an updated source asset CSV in [folderId].
     * If updated or [force] is true, downloads the new file, saves it locally, and reloads it.
     */
    suspend fun checkAndUpdateSourceAssetList(
        context: Context,
        csvManager: CsvManager,
        force: Boolean = false
    ): SourceAssetUpdateResult = withContext(Dispatchers.IO) {
        val webhookUrl = getWebhookUrl(context)
        if (webhookUrl.isBlank()) return@withContext SourceAssetUpdateResult.NoWebhook
        if (!isOnline(context)) return@withContext SourceAssetUpdateResult.Offline

        val folderId = getSourceFolderId(context)
        val localLastModified = getSourceLastModified(context)

        try {
            if (!force) {
                val checkPayload = JSONObject().apply {
                    put("action", "checkSourceAsset")
                    put("folderId", folderId)
                }
                val (code, checkResp) = executeAppsScriptJson(webhookUrl, checkPayload.toString())
                if (code !in 200..299) {
                    return@withContext SourceAssetUpdateResult.Failed("Server HTTP $code: $checkResp")
                }
                val checkJson = JSONObject(checkResp)
                if (!checkJson.optString("status").equals("success", ignoreCase = true)) {
                    val err = checkJson.optString("message", "Unknown error")
                    return@withContext SourceAssetUpdateResult.Failed(err)
                }

                val driveLastUpdated = checkJson.optLong("lastUpdated", 0L)
                val fileName = checkJson.optString("fileName", "Source Asset List.csv")

                if (localLastModified > 0 && driveLastUpdated <= localLastModified) {
                    val timeStr = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                    saveSourceLastCheckTime(context, timeStr)
                    return@withContext SourceAssetUpdateResult.UpToDate
                }
            }

            // Download file content
            val fetchPayload = JSONObject().apply {
                put("action", "getSourceAsset")
                put("folderId", folderId)
            }
            val (code, fetchResp) = executeAppsScriptJson(webhookUrl, fetchPayload.toString())
            if (code !in 200..299) {
                return@withContext SourceAssetUpdateResult.Failed("Server HTTP $code: $fetchResp")
            }
            val fetchJson = JSONObject(fetchResp)
            if (!fetchJson.optString("status").equals("success", ignoreCase = true)) {
                val err = fetchJson.optString("message", "Unknown error")
                return@withContext SourceAssetUpdateResult.Failed(err)
            }

            val fileName = fetchJson.optString("fileName", "Source Asset List.csv")
            val lastUpdated = fetchJson.optLong("lastUpdated", System.currentTimeMillis())
            val fileContent = fetchJson.optString("fileContent", "")

            if (fileContent.isBlank()) {
                return@withContext SourceAssetUpdateResult.Failed("Downloaded file is empty")
            }

            // Save to local device file
            val savedUri = csvManager.saveSourceAssetContent(fileName, fileContent)
                ?: return@withContext SourceAssetUpdateResult.Failed("Failed to write updated asset list to storage")

            val (_, count) = csvManager.loadLookupMap(savedUri)

            val timeStr = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            saveSourceLastCheckTime(context, timeStr)
            setSourceLastModified(context, lastUpdated)

            SourceAssetUpdateResult.Updated(
                fileName = fileName,
                lastUpdated = lastUpdated,
                assetCount = count
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkAndUpdateSourceAssetList error", e)
            SourceAssetUpdateResult.Failed(e.localizedMessage ?: "Network or parsing error")
        }
    }

    /**
     * Sends an HTTP POST with JSON body to Google Apps Script Web App.
     * Follows the 302/303 redirect loop to script.googleusercontent.com with GET to retrieve the JSON response.
     */
    fun executeAppsScriptJson(targetUrl: String, jsonBody: String): Pair<Int, String> {
        var currentUrl = targetUrl
        var redirects = 0
        val maxRedirects = 5

        while (redirects < maxRedirects) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 45000
                if (redirects == 0) {
                    requestMethod = "POST"
                    doOutput = true
                    doInput = true
                    instanceFollowRedirects = false
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Accept", "application/json")
                } else {
                    requestMethod = "GET"
                    doInput = true
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                }
            }

            if (redirects == 0) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(jsonBody)
                    writer.flush()
                }
            }

            val code = conn.responseCode

            // Handle HTTP 302, 303, 307, 308 redirects from Google Apps Script
            if (code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 || code == 308
            ) {
                val newLocation = conn.getHeaderField("Location")
                conn.disconnect()
                if (newLocation.isNullOrBlank()) {
                    return Pair(code, "Redirect received with empty Location header")
                }
                currentUrl = newLocation
                redirects++
            } else {
                val reader = if (code in 200..299) {
                    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                } else {
                    BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream, Charsets.UTF_8))
                }
                val responseText = reader.use { it.readText() }
                conn.disconnect()
                return Pair(code, responseText)
            }
        }
        return Pair(400, "Too many redirects following Google Apps Script")
    }

    private fun postJsonToGoogleAppsScript(targetUrl: String, jsonBody: String): SyncResult {
        val (responseCode, responseText) = try {
            executeAppsScriptJson(targetUrl, jsonBody)
        } catch (e: Exception) {
            return SyncResult(success = false, message = "Network error: ${e.localizedMessage ?: "Unknown"}")
        }

        return try {
            val json = JSONObject(responseText)
            val status = json.optString("status", "")
            if (status.equals("success", ignoreCase = true) || status.equals("ok", ignoreCase = true)) {
                val overwritten = json.optBoolean("overwritten", false)
                val fileName = json.optString("fileName", "")
                val folderName = json.optString("folderName", "")
                val action = if (overwritten) "updated" else "uploaded"
                val dest = if (folderName.isNotBlank()) " in '$folderName'" else ""
                val msg = if (fileName.isNotBlank()) "✓ Drive: $fileName $action$dest" else "✓ Google Drive sync complete ($action)"
                SyncResult(
                    success = true,
                    message = msg,
                    overwritten = overwritten
                )
            } else {
                val errorMsg = json.optString("message", "Unknown script error")
                SyncResult(success = false, message = "Google Drive error: $errorMsg")
            }
        } catch (_: Exception) {
            val preview = responseText.take(120).replace("\n", " ").trim()
            if (preview.contains("DOCTYPE html", ignoreCase = true) || preview.contains("<html", ignoreCase = true)) {
                SyncResult(success = false, message = "Google Drive Webhook returned HTML error page instead of JSON")
            } else {
                SyncResult(success = false, message = "HTTP $responseCode: $preview")
            }
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Starts listening for network connectivity changes.
     * When device goes online and a pending sync file exists, automatically triggers sync!
     */
    fun startNetworkMonitoring(context: Context, onAutoSyncTriggered: (fileName: String) -> Unit) {
        if (networkCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val pendingFile = getPendingSyncFile(context)
                if (!pendingFile.isNullOrBlank() && isAutoSyncEnabled(context)) {
                    Log.i(TAG, "Network restored. Triggering auto-sync for $pendingFile")
                    onAutoSyncTriggered(pendingFile)
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback", e)
        }
    }

    fun stopNetworkMonitoring(context: Context) {
        val cb = networkCallback ?: return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        networkCallback = null
    }
}
