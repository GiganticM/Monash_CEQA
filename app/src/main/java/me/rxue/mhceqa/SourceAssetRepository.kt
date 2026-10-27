package me.rxue.mhceqa

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object SourceAssetRepository {
    private const val TAG = "SourceAssetRepository"

    @Volatile
    var cachedLookupMap: Map<String, List<CsvManager.SourceRecord>>? = null
        private set

    @Volatile
    var cachedItemCount: Int = 0
        private set

    @Volatile
    var cachedUri: Uri? = null
        private set

    @Volatile
    var cachedFileName: String? = null
        private set

    private val mutex = Mutex()
    private var loadingDeferred: Deferred<Pair<Map<String, List<CsvManager.SourceRecord>>, Int>>? = null

    val isLoaded: Boolean
        get() = cachedLookupMap != null

    /**
     * Starts preloading the source CSV in the background (e.g. from SessionActivity)
     * before the user even logs in.
     */
    fun preload(context: Context, uri: Uri, fileName: String? = null) {
        if (cachedLookupMap != null && cachedUri == uri) return
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                getOrLoad(appContext, uri, fileName)
            } catch (e: Exception) {
                Log.w(TAG, "Preload failed: ${e.message}")
            }
        }
    }

    /**
     * Returns the cached lookup map if available; otherwise parses the CSV and caches it.
     * Thread-safe and deduplicates concurrent load requests.
     */
    suspend fun getOrLoad(
        context: Context,
        uri: Uri,
        fileName: String? = null
    ): Pair<Map<String, List<CsvManager.SourceRecord>>, Int> {
        // Fast path: already loaded for this URI
        cachedLookupMap?.let { map ->
            if (cachedUri == uri) {
                return Pair(map, cachedItemCount)
            }
        }

        val appContext = context.applicationContext

        // Acquire lock to avoid multiple simultaneous parsing of the same large file
        val deferred = mutex.withLock {
            cachedLookupMap?.let { map ->
                if (cachedUri == uri) {
                    return Pair(map, cachedItemCount)
                }
            }
            if (loadingDeferred?.isActive == true && cachedUri == uri) {
                loadingDeferred!!
            } else {
                cachedUri = uri
                cachedFileName = fileName
                val newDeferred = CoroutineScope(Dispatchers.IO).async {
                    parseCsvFast(appContext, uri)
                }
                loadingDeferred = newDeferred
                newDeferred
            }
        }

        val result = deferred.await()
        mutex.withLock {
            cachedLookupMap = result.first
            cachedItemCount = result.second
            loadingDeferred = null
        }
        return result
    }

    /**
     * Clears the cache when the source file is updated or changed.
     */
    fun invalidate() {
        cachedLookupMap = null
        cachedItemCount = 0
        cachedUri = null
        cachedFileName = null
        loadingDeferred?.cancel()
        loadingDeferred = null
    }

    /**
     * High-speed, buffered CSV parser optimized for 40,000+ line reference files on Android.
     * Uses a large 64KB read buffer, string interning for departments, and single-pass tokenization.
     */
    private fun parseCsvFast(
        context: Context,
        uri: Uri
    ): Pair<Map<String, List<CsvManager.SourceRecord>>, Int> {
        val startTime = System.currentTimeMillis()
        val map = HashMap<String, MutableList<CsvManager.SourceRecord>>(50_000)
        val deptCache = HashMap<String, String>(256)
        var assetCount = 0

        fun addToMap(key: String, record: CsvManager.SourceRecord) {
            if (key.isNotEmpty()) {
                val lowerKey = key.lowercase()
                var list = map[lowerKey]
                if (list == null) {
                    list = ArrayList(2)
                    map[lowerKey] = list
                }
                list.add(record)
            }
        }

        context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 65536).use { reader ->
                var line: String? = reader.readLine() // Read header
                if (line == null) return Pair(emptyMap(), 0)

                val rowTokens = ArrayList<String>(8)
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: break
                    if (l.isEmpty()) continue

                    parseLine(l, rowTokens)
                    if (rowTokens.isEmpty()) continue

                    val assetNumber = rowTokens.getOrElse(0) { "" }.trim()
                    if (assetNumber.isEmpty()) continue

                    val serial = rowTokens.getOrElse(1) { "" }.trim()
                    val rawDept = rowTokens.getOrElse(2) { "" }.trim()
                    val barcode = rowTokens.getOrElse(3) { "" }.trim()
                    val routinePeriod = rowTokens.getOrElse(4) { "" }.trim().toIntOrNull() ?: 0

                    // Share String instances for department names across 40k+ records
                    val dept = if (rawDept.isNotEmpty()) deptCache.getOrPut(rawDept) { rawDept } else ""

                    assetCount++
                    val record = CsvManager.SourceRecord(assetNumber, dept, routinePeriod)
                    addToMap(assetNumber, record)
                    addToMap(serial, record)
                    addToMap(barcode, record)
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "Fast CSV parse complete in ${elapsed}ms: $assetCount assets indexed")
        return Pair(map, assetCount)
    }

    /**
     * Fast single-pass CSV line tokenizer that handles standard comma separation and quoted fields.
     */
    private fun parseLine(line: String, out: ArrayList<String>) {
        out.clear()
        val len = line.length
        var inQuotes = false
        val sb = StringBuilder(32)

        for (i in 0 until len) {
            val c = line[i]
            when {
                c == '"' -> {
                    inQuotes = !inQuotes
                }
                c == ',' && !inQuotes -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> {
                    sb.append(c)
                }
            }
        }
        out.add(sb.toString())
    }
}
