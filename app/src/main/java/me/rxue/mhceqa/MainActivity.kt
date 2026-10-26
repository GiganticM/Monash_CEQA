package me.rxue.mhceqa

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rxue.mhceqa.models.Asset
import me.rxue.mhceqa.ui.components.BtState
import me.rxue.mhceqa.ui.components.ConditionScoreDialog
import me.rxue.mhceqa.ui.components.CustomSnackbarVisuals
import me.rxue.mhceqa.ui.screens.MainScannerScreen
import me.rxue.mhceqa.ui.screens.matchesUser
import me.rxue.mhceqa.ui.theme.MonashCeqaTheme

class MainActivity : AppCompatActivity() {

    private lateinit var csvManager: CsvManager
    private lateinit var btManager: BluetoothSppManager

    private val scanLog = mutableStateListOf<Asset>()
    private var lookupMap: Map<String, List<CsvManager.SourceRecord>> = emptyMap()

    // Compose Reactive States
    private var btState by mutableStateOf(BtState.DISCONNECTED)
    private var btDeviceName by mutableStateOf<String?>(null)
    private var sourceCsvName by mutableStateOf("")
    private var csvItemCount by mutableIntStateOf(0)
    private var outputPath by mutableStateOf("")
    private var fontSizeSp by mutableFloatStateOf(14f)
    private var userInitials by mutableStateOf("")
    private var userLocation by mutableStateOf("")
    private var sessionType by mutableStateOf(SessionActivity.SESSION_TYPE_QA)
    private val isIoSession get() = sessionType == SessionActivity.SESSION_TYPE_IO
    private var searchInput by mutableStateOf("")
    private var lastScannedAssetNumber by mutableStateOf("")
    private var lastScannedRoutinePeriod by mutableIntStateOf(0)
    private var selectedTab by mutableIntStateOf(0)
    private val isScanTabActive get() = (selectedTab == 0)
    private var conditionScorePrompt by mutableStateOf<Pair<String, Asset>?>(null)

    private val snackbarHostState = SnackbarHostState()
    private val focusRequester = FocusRequester()

    private var connectJob: Job? = null
    private var reconnectJob: Job? = null

    private val scanBuffer = StringBuilder()
    private var manualCsvPickInProgress = false
    private var initialReconnectAttempted = false

    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val ourMac = btManager.lastDevice?.address ?: btManager.lastKnownMac
            if (device?.address != ourMac) return
            btManager.disconnect()
            btState = BtState.DISCONNECTED
            btDeviceName = null
            startAutoReconnect()
        }
    }

    private var lastScannedBarcode: String = ""
    private var lastScanTime: Long = 0L

    private val barcodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val barcode = extractBarcodeFromIntent(intent)
            if (!barcode.isNullOrBlank()) {
                handleBarcodeScan(barcode)
            }
        }
    }

    companion object {
        private const val PREFS                 = "asset_scanner_prefs"
        private const val KEY_FONT_SIZE         = "log_font_size"
        private const val KEY_LAST_DEVICE       = "last_bt_device_address"
        private const val KEY_WAS_CONNECTED     = "bt_was_connected_on_pause"
        private const val KEY_SNACKBAR_DURATION = "snackbar_duration"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    // ─── Launchers ────────────────────────────────────────────────────────────

    private val requestBtPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) showDevicePicker()
        else Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_LONG).show()
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) checkPermissionsAndShowPicker()
        else Toast.makeText(this, "Bluetooth was not enabled", Toast.LENGTH_SHORT).show()
    }

    private val pickCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val fileName = getFileNameFromUri(uri)
            csvManager.saveSourceUri(uri, fileName)
            loadSourceCsv(uri, fileName)
        }
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            fontSizeSp = prefs.getFloat(KEY_FONT_SIZE, ScanLogAdapter.FONT_SIZE_MEDIUM)
            outputPath = csvManager.outputFilePath
            val sourceChanged = result.data?.getBooleanExtra("extra_source_csv_changed", false) == true
            if (sourceChanged) {
                csvManager.loadSavedSourceUri()?.let { savedUri ->
                    val savedName = csvManager.loadSavedSourceName() ?: getFileNameFromUri(savedUri)
                    loadSourceCsv(savedUri, savedName)
                }
            }
            showSnackbar("Settings saved")
        }
    }

    private val addAssetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val assetNumber = data.getStringExtra(AddAssetActivity.EXTRA_ASSET_NUMBER) ?: ""
            val conditionScore = data.getStringExtra(AddAssetActivity.EXTRA_CONDITION_SCORE) ?: ""
            val asset = Asset(
                assetNumber    = assetNumber,
                department     = data.getStringExtra(AddAssetActivity.EXTRA_DEPARTMENT) ?: "",
                make           = data.getStringExtra(AddAssetActivity.EXTRA_MAKE) ?: "",
                model          = data.getStringExtra(AddAssetActivity.EXTRA_MODEL) ?: "",
                serial         = data.getStringExtra(AddAssetActivity.EXTRA_SERIAL) ?: "",
                isNew          = true,
                userInitials   = userInitials,
                conditionScore = conditionScore
            )
            saveAsset(asset)
            sendViaBluetooth(assetNumber)
            showFoundBanner(assetNumber, alreadyLogged = false, isNew = true)
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!SessionActivity.isSessionActive(this)) {
            startActivity(Intent(this, SessionActivity::class.java))
            finish()
            return
        }

        csvManager = CsvManager(this)
        btManager = BluetoothSppManager(this)
        btManager.onDisconnected = {
            runOnUiThread {
                btState = BtState.DISCONNECTED
                btDeviceName = null
                startAutoReconnect()
            }
        }

        fontSizeSp = prefs.getFloat(KEY_FONT_SIZE, ScanLogAdapter.FONT_SIZE_MEDIUM)
        outputPath = csvManager.outputFilePath
        userInitials = prefs.getString(SessionActivity.KEY_USER_INITIALS, "") ?: ""
        userLocation = prefs.getString(SessionActivity.KEY_QA_LOCATION, "") ?: ""
        sessionType = prefs.getString(SessionActivity.KEY_SESSION_TYPE, SessionActivity.SESSION_TYPE_QA) ?: SessionActivity.SESSION_TYPE_QA

        setupScanLog()
        configureScannerService()
        checkSourceAssetUpdateSilently()

        setContent {
            MonashCeqaTheme {
                MainScannerScreen(
                    scanLog = scanLog,
                    btState = btState,
                    btDeviceName = btDeviceName,
                    sourceCsvName = sourceCsvName,
                    csvItemCount = csvItemCount,
                    outputPath = outputPath,
                    fontSizeSp = fontSizeSp,
                    snackbarHostState = snackbarHostState,
                    focusRequester = focusRequester,
                    selectedTab = selectedTab,
                    onTabSelected = { tab ->
                        selectedTab = tab
                        searchInput = ""
                        scanBuffer.clear()
                    },
                    lastScannedAssetNumber = lastScannedAssetNumber,
                    lastScannedRoutinePeriod = lastScannedRoutinePeriod,
                    isIoSession = isIoSession,
                    searchInput = searchInput,
                    onSearchInputChange = {
                        searchInput = it
                    },
                    userInitials = userInitials,
                    userLocation = userLocation,
                    onSearchSubmitted = { query ->
                        performSearch(query)
                    },
                    onConnectBt = { checkPermissionsAndShowPicker() },
                    onDisconnectBt = { disconnectBluetooth() },
                    onPickCsv = {
                        manualCsvPickInProgress = true
                        pickCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
                    },
                    onOpenSettings = {
                        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
                    },
                    onOpenAbout = { showAboutDialog() },
                    onEndSession = { showEndSessionDialog() },
                    onSyncDrive = { triggerDriveSync(manual = true) },
                    onUpdateSourceCsv = { triggerSourceAssetUpdate(manual = true) }
                )

                conditionScorePrompt?.let { (assetNumber, existing) ->
                    ConditionScoreDialog(
                        assetNumber = assetNumber,
                        initialScore = existing.conditionScore.ifEmpty { "0" },
                        onDismiss = { conditionScorePrompt = null },
                        onSave = { score ->
                            conditionScorePrompt = null
                            saveConditionScore(assetNumber, score)
                        }
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::btManager.isInitialized)
            prefs.edit().putBoolean(KEY_WAS_CONNECTED, btManager.isConnected).apply()
        try {
            unregisterReceiver(aclReceiver)
        } catch (_: IllegalArgumentException) {}
        try {
            unregisterReceiver(barcodeReceiver)
        } catch (_: IllegalArgumentException) {}
    }

    override fun onResume() {
        super.onResume()
        if (!::btManager.isInitialized) return

        val savedDate = prefs.getString(SessionActivity.KEY_SESSION_DATE, null)
        if (savedDate != null && savedDate != SessionActivity.todayDateString()) {
            prefs.edit()
                .putBoolean(SessionActivity.KEY_SESSION_ACTIVE, false)
                .remove(SessionActivity.KEY_USER_INITIALS)
                .remove(SessionActivity.KEY_QA_LOCATION)
                .remove(SessionActivity.KEY_SESSION_DATE)
                .remove(SessionActivity.KEY_SESSION_TYPE)
                .apply()
            connectJob?.cancel()
            reconnectJob?.cancel()
            btManager.cancelConnect()
            btManager.disconnect()
            val intent = Intent(this, SessionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            finish()
            return
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(aclReceiver, filter, RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(aclReceiver, filter)

        val barcodeFilter = BarcodeHelper.createBarcodeIntentFilter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(barcodeReceiver, barcodeFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(barcodeReceiver, barcodeFilter)
        }

        configureScannerService()

        userInitials = prefs.getString(SessionActivity.KEY_USER_INITIALS, "") ?: ""
        userLocation = prefs.getString(SessionActivity.KEY_QA_LOCATION, "") ?: ""
        sessionType = prefs.getString(SessionActivity.KEY_SESSION_TYPE, SessionActivity.SESSION_TYPE_QA) ?: SessionActivity.SESSION_TYPE_QA

        if (lookupMap.isEmpty() && !manualCsvPickInProgress) {
            csvManager.loadSavedSourceUri()?.let { savedUri ->
                val savedName = csvManager.loadSavedSourceName() ?: getFileNameFromUri(savedUri)
                loadSourceCsv(savedUri, savedName)
            }
        } else if (lookupMap.isNotEmpty()) {
            reResolveAssets()
        }
        manualCsvPickInProgress = false

        if (!isIoSession && !initialReconnectAttempted) {
            initialReconnectAttempted = true
            val wasConnected = prefs.getBoolean(KEY_WAS_CONNECTED, false)
            if (wasConnected) {
                val mac = btManager.lastKnownMac
                if (mac != null) {
                    @Suppress("MissingPermission")
                    val device = btManager.getPairedDevices().find { it.address == mac }
                    if (device != null) connectToDevice(device, silent = true)
                }
            }
        }

        DriveSyncManager.startNetworkMonitoring(this) {
            triggerDriveSync(manual = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DriveSyncManager.stopNetworkMonitoring(this)
        if (!::btManager.isInitialized) return
        connectJob?.cancel()
        reconnectJob?.cancel()
        btManager.cancelConnect()
        btManager.disconnect()
    }

    private fun isEnterKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            code == KeyEvent.KEYCODE_DPAD_CENTER) {
            return true
        }
        val unicode = event.unicodeChar
        return unicode == 10 || unicode == 13
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isEnterKey(event)) {
            if (event.action == KeyEvent.ACTION_DOWN && isScanTabActive) {
                val query = searchInput.trim().uppercase()
                if (query.isNotEmpty()) {
                    if (lookupMap.isEmpty()) {
                        showSnackbar("Please load a source CSV file first", Color(0xFFE65100))
                    } else {
                        performSearch(query)
                    }
                }
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun handleBarcodeScan(barcode: String) {
        val now = System.currentTimeMillis()
        if (barcode == lastScannedBarcode && (now - lastScanTime) < 300L) {
            return
        }
        lastScannedBarcode = barcode
        lastScanTime = now

        // Automatic search strictly only on SCAN screen without populating the search box
        if (isScanTabActive) {
            val query = barcode.trim().uppercase()
            if (lookupMap.isEmpty()) {
                showSnackbar("Please load a source CSV file first", Color(0xFFE65100))
            } else {
                performSearch(query)
            }
        }
    }

    private fun extractBarcodeFromIntent(intent: Intent): String? =
        BarcodeHelper.extractBarcodeFromIntent(intent)

    private fun configureScannerService() {
        Thread {
            try {
                val clazz = Class.forName("android.bld.ScanManager")
                val methodGet = clazz.getMethod("getDefaultInstance")
                val instance = methodGet.invoke(null)
                if (instance != null) {
                    val methodSetDisableSame = clazz.getMethod("setDisableSameBarcode", Boolean::class.javaPrimitiveType)
                    methodSetDisableSame.invoke(instance, false)
                }
            } catch (_: Throwable) {}

            try {
                val intent = Intent("android.bld.scan.action.PARAM_SETTINGS")
                intent.putExtra("scan_disable_same_barcode", false)
                sendBroadcast(intent)
            } catch (_: Throwable) {}
        }.start()
    }

    // ─── Setup & CSV ──────────────────────────────────────────────────────────

    private fun setupScanLog() {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                if (prefs.getBoolean(SessionActivity.KEY_NEEDS_SESSION_HEADER, false)) {
                    csvManager.appendSessionHeader(userInitials, userLocation)
                    prefs.edit().putBoolean(SessionActivity.KEY_NEEDS_SESSION_HEADER, false).apply()
                }
                csvManager.readSavedAssets()
            }
            scanLog.clear()
            scanLog.addAll(saved)
            reResolveAssets()
            updateLastScannedDisplay()
        }
    }

    private fun loadSourceCsv(uri: Uri, fileName: String? = null) {
        val resolvedName = fileName ?: getFileNameFromUri(uri)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { csvManager.loadLookupMap(uri) }
            }
            result.onSuccess { (map, count) ->
                lookupMap = map
                sourceCsvName = resolvedName
                csvItemCount = count
                reResolveAssets()
                showSnackbar("✓ Loaded $count assets from CSV")
            }.onFailure {
                lookupMap = emptyMap()
                sourceCsvName = "Failed to load CSV"
                csvItemCount = 0
                csvManager.clearSourceUri()
                showSnackbar("✗ Failed to load CSV file!", Color(0xFFC62828))
            }
        }
    }

    // ─── Search & Asset Handling ──────────────────────────────────────────────

    private val assetGeniusPattern = Regex("[NX]\\d{8}$")

    private fun extractAssetNumber(raw: String): String {
        if (raw.length == 35) {
            val match = assetGeniusPattern.find(raw)
            if (match != null) return match.value
        }
        return raw
    }

    private fun performSearch(rawQuery: String) {
        val query = extractAssetNumber(rawQuery)
        val matches = lookupMap[query.lowercase()]

        when {
            matches != null -> {
                val distinctAssets = matches.distinctBy { it.assetNumber.lowercase() }
                if (distinctAssets.size > 5) {
                    AlertDialog.Builder(this)
                        .setTitle("Too many assets found")
                        .setMessage("There are too many assets associated with this scan. Please double check.")
                        .setPositiveButton("OK", null)
                        .show()
                } else if (distinctAssets.size == 1) {
                    resolveAndSave(distinctAssets.first())
                } else {
                    showAssetPickerDialog(query, distinctAssets)
                }
            }
            else -> {
                val existing = scanLog.firstOrNull { it.assetNumber.equals(query, ignoreCase = true) }
                    ?: csvManager.readSavedAssets().firstOrNull { it.assetNumber.equals(query, ignoreCase = true) }
                if (existing != null) {
                    sendViaBluetooth(query)
                    if (isIoSession) {
                        promptConditionScoreDialog(query, existing)
                    } else {
                        val match = lookupMap[query.lowercase()]?.firstOrNull()
                        val period = match?.routinePeriod ?: existing.routinePeriod
                        showFoundBanner(
                            assetNumber = query,
                            alreadyLogged = true,
                            routinePeriod = period,
                            loggedBy = existing.userInitials,
                            loggedAt = existing.timestamp
                        )
                    }
                } else {
                    val intent = Intent(this, AddAssetActivity::class.java).apply {
                        putExtra(AddAssetActivity.EXTRA_ASSET_NUMBER, query)
                        putExtra(AddAssetActivity.EXTRA_IS_IO, isIoSession)
                    }
                    addAssetLauncher.launch(intent)
                }
            }
        }
    }

    private fun showAssetPickerDialog(
        scannedValue: String,
        matches: List<CsvManager.SourceRecord>
    ) {
        val displayItems = matches.map { record ->
            val dept = if (record.department.isNotBlank()) "  -  ${record.department}" else ""
            "${record.assetNumber}$dept"
        }.toTypedArray()

        var selectedIndex = 0

        AlertDialog.Builder(this)
            .setTitle("Multiple assets match \"$scannedValue\"!\nPlease select the correct one:")
            .setSingleChoiceItems(displayItems, selectedIndex) { _, index ->
                selectedIndex = index
            }
            .setPositiveButton("OK") { _, _ -> resolveAndSave(matches[selectedIndex]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resolveAndSave(record: CsvManager.SourceRecord) {
        sendViaBluetooth(record.assetNumber)
        val existing = scanLog.firstOrNull { it.assetNumber.equals(record.assetNumber, ignoreCase = true) }
            ?: csvManager.readSavedAssets().firstOrNull { it.assetNumber.equals(record.assetNumber, ignoreCase = true) }
        if (existing != null) {
            if (isIoSession) {
                promptConditionScoreDialog(record.assetNumber, existing)
            } else {
                showFoundBanner(
                    assetNumber = record.assetNumber,
                    alreadyLogged = true,
                    routinePeriod = record.routinePeriod,
                    loggedBy = existing.userInitials,
                    loggedAt = existing.timestamp
                )
            }
        } else {
            val asset = Asset(
                assetNumber   = record.assetNumber,
                department    = record.department,
                isNew         = false,
                routinePeriod = record.routinePeriod,
                userInitials  = userInitials
            )
            saveAsset(asset)
            showFoundBanner(record.assetNumber, alreadyLogged = false, routinePeriod = record.routinePeriod)
        }
    }

    private fun promptConditionScoreDialog(assetNumber: String, existing: Asset) {
        conditionScorePrompt = Pair(assetNumber, existing)
    }

    private fun saveConditionScore(assetNumber: String, score: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val updated = csvManager.updateAssetConditionScore(assetNumber, score)
            withContext(Dispatchers.Main) {
                if (updated) {
                    val idx = scanLog.indexOfFirst { it.assetNumber.equals(assetNumber, ignoreCase = true) }
                    if (idx != -1) {
                        scanLog[idx] = scanLog[idx].copy(conditionScore = score)
                    }
                    showSnackbar("✓ Score ($score) saved for '$assetNumber'", Color(0xFF2E7D32))
                } else {
                    showSnackbar("⚠ Could not save score for '$assetNumber'", Color(0xFFE65100))
                }
            }
        }
    }

    private fun saveAsset(asset: Asset) {
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = csvManager.appendAsset(asset)
            withContext(Dispatchers.Main) {
                scanLog.add(saved)
                outputPath = csvManager.outputFilePath
                updateLastScannedDisplay()
            }
        }
    }

    // ─── Bluetooth ────────────────────────────────────────────────────────────

    private fun sendViaBluetooth(text: String) {
        if (isIoSession) return
        if (!btManager.isConnected) return
        lifecycleScope.launch(Dispatchers.IO) {
            var sent = btManager.sendString(text)
            if (!sent) {
                val lastDevice = btManager.lastDevice
                if (lastDevice != null) {
                    val result = btManager.connect(lastDevice)
                    if (result is BluetoothSppManager.ConnectionResult.Success) {
                        sent = btManager.sendString(text)
                    }
                }
            }
            if (!sent) {
                withContext(Dispatchers.Main) {
                    showSnackbar("⚠ Bluetooth connection failed")
                    btState = BtState.DISCONNECTED
                    btDeviceName = null
                }
            }
        }
    }

    private fun checkPermissionsAndShowPicker() {
        if (isIoSession) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                requestBtPermissions.launch(needed.toTypedArray())
                return
            }
        }
        showDevicePicker()
    }

    @Suppress("MissingPermission")
    private fun showDevicePicker() {
        val devices: List<BluetoothDevice> = btManager.getPairedDevices()
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No Paired Devices")
                .setMessage("No paired Bluetooth devices found.\n\nGo to Android Settings → Bluetooth, pair your device first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val displayNames = devices.map { "${it.name ?: "Unknown"} (${it.address})" }
        AlertDialog.Builder(this)
            .setTitle("Select Bluetooth Scanner/Device")
            .setAdapter(
                ArrayAdapter(this, android.R.layout.simple_list_item_1, displayNames)
            ) { _, index ->
                connectToDevice(devices[index])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startAutoReconnect() {
        if (isIoSession) return
        if (reconnectJob?.isActive == true) return
        val mac = btManager.lastKnownMac ?: return
        btState = BtState.RECONNECTING
        reconnectJob = lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                if (btManager.isConnected) break
                val device = btManager.getPairedDevices().find { it.address == mac } ?: break
                val result = withContext(Dispatchers.IO) { btManager.connect(device) }
                when (result) {
                    is BluetoothSppManager.ConnectionResult.Success -> {
                        btState = BtState.CONNECTED
                        btDeviceName = result.deviceName
                        showSnackbar("✓ Reconnected to ${result.deviceName}")
                        break
                    }
                    else -> {}
                }
            }
            reconnectJob = null
        }
    }

    private fun connectToDevice(device: BluetoothDevice, silent: Boolean = false) {
        connectJob?.cancel()
        btManager.cancelConnect()
        btState = BtState.RECONNECTING

        @Suppress("MissingPermission")
        val name = device.name ?: device.address
        btDeviceName = name

        connectJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { btManager.connect(device) }
            connectJob = null
            when (result) {
                is BluetoothSppManager.ConnectionResult.Success -> {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    btState = BtState.CONNECTED
                    btDeviceName = result.deviceName
                    showSnackbar("✓ Connected to ${result.deviceName}")
                }
                is BluetoothSppManager.ConnectionResult.Failure -> {
                    btState = BtState.DISCONNECTED
                    btDeviceName = null
                    if (!silent) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Connection Failed")
                            .setMessage("${result.error}\n\nMake sure the device is powered on.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
                is BluetoothSppManager.ConnectionResult.Cancelled -> {
                    btState = BtState.DISCONNECTED
                    btDeviceName = null
                }
            }
        }
    }

    private fun disconnectBluetooth() {
        connectJob?.cancel()
        reconnectJob?.cancel()
        reconnectJob = null
        btManager.cancelConnect()
        btManager.disconnect()
        btManager.clearLastDevice()
        prefs.edit().remove(KEY_LAST_DEVICE).apply()
        btState = BtState.DISCONNECTED
        btDeviceName = null
        showSnackbar("Bluetooth disconnected")
    }

    private var snackbarJob: Job? = null

    private fun showCustomSnackbar(
        message: String,
        containerColor: Color,
        stickerText: String? = null,
        stickerColor: Color? = null,
        durationMsOverride: Long? = null,
        isSystemNotification: Boolean = false
    ) {
        val durationMs = durationMsOverride ?: prefs.getInt(KEY_SNACKBAR_DURATION, 5000).toLong()
        snackbarJob?.cancel()
        snackbarJob = lifecycleScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val visuals = CustomSnackbarVisuals(
                message = message,
                duration = androidx.compose.material3.SnackbarDuration.Indefinite,
                containerColor = containerColor,
                contentColor = Color.White,
                stickerText = stickerText,
                stickerColor = stickerColor,
                durationMs = durationMs,
                isSystemNotification = isSystemNotification
            )
            val showJob = launch {
                snackbarHostState.showSnackbar(visuals)
            }
            kotlinx.coroutines.delay(durationMs)
            snackbarHostState.currentSnackbarData?.dismiss()
            showJob.cancel()
        }
    }

    private fun showSnackbar(message: String, containerColor: Color = Color(0xFF323232)) {
        showCustomSnackbar(
            message = message,
            containerColor = containerColor,
            durationMsOverride = 1800L,
            isSystemNotification = true
        )
    }

    private fun showFoundBanner(
        assetNumber: String,
        alreadyLogged: Boolean,
        routinePeriod: Int = 0,
        isNew: Boolean = false,
        loggedBy: String = "",
        loggedAt: String = ""
    ) {
        val (stickerText, stickerColor) = when (routinePeriod) {
            12   -> "[YELLOW STICKER (12mths)]" to Color(0xFFFFF176)
            24   -> "[WHITE STICKER (24mths)]"  to Color(0xFFFFFFFF)
            60   -> "[GREEN STICKER (60mths)]"  to Color(0xFFB9F6CA)
            else -> null to null
        }

        lastScannedAssetNumber = assetNumber
        lastScannedRoutinePeriod = routinePeriod

        val baseMessage = when {
            isNew -> "✓ NEW - '$assetNumber' added successfully"
            alreadyLogged -> {
                val details = listOf(
                    if (loggedBy.isNotBlank()) "by $loggedBy" else "",
                    if (loggedAt.isNotBlank()) "at $loggedAt" else ""
                ).filter { it.isNotBlank() }.joinToString(" ")
                if (details.isNotBlank()) "⚠ '$assetNumber' already logged $details"
                else "⚠ FOUND - '$assetNumber' already logged"
            }
            else -> "✓ FOUND - '$assetNumber' logged successfully"
        }

        val bgColor = if (alreadyLogged) Color(0xFFE65100) else Color(0xFF2E7D32)

        showCustomSnackbar(
            message = baseMessage,
            containerColor = bgColor,
            stickerText = stickerText,
            stickerColor = stickerColor
        )
    }

    /**
     * Iterates through the scan log and matches assets against the loaded source CSV (lookupMap),
     * restoring their QA routine periods (routinePeriod: 12, 24, 60mths) and missing department values.
     * This ensures assets restored from the output CSV display their sticker color dots in the LOG tab.
     */
    private fun reResolveAssets() {
        if (lookupMap.isEmpty()) return
        scanLog.forEachIndexed { index, asset ->
            if (!asset.isSessionHeader) {
                val match = lookupMap[asset.assetNumber.lowercase()]?.firstOrNull()
                if (match != null) {
                    val needsDept = asset.department.isEmpty() && match.department.isNotEmpty()
                    val needsPeriod = asset.routinePeriod == 0 && match.routinePeriod != 0
                    if (needsDept || needsPeriod) {
                        scanLog[index] = asset.copy(
                            department = if (needsDept) match.department else asset.department,
                            routinePeriod = if (needsPeriod) match.routinePeriod else asset.routinePeriod
                        )
                    }
                }
            }
        }
        updateLastScannedDisplay()
    }

    private fun updateLastScannedDisplay() {
        val lastAsset = scanLog.lastOrNull { !it.isSessionHeader && matchesUser(it.userInitials, userInitials) }
        if (lastAsset != null) {
            lastScannedAssetNumber = lastAsset.assetNumber
            lastScannedRoutinePeriod = lastAsset.routinePeriod
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
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to query displayName from contentResolver", e)
            }
            try {
                val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(this, uri)
                val docName = doc?.name
                if (!docName.isNullOrBlank()) return docName
            } catch (_: Exception) {}
        }
        val last = uri.lastPathSegment?.substringAfterLast('/')
        return if (!last.isNullOrBlank() && !last.startsWith("msf:")) last else "Source CSV"
    }

    private fun showEndSessionDialog() {
        AlertDialog.Builder(this)
            .setTitle(if (isIoSession) "End IO Session?" else "End QA Session?")
            .setMessage(if (isIoSession) "Are you sure you want to end the current IO session?" else "Are you sure you want to end the current QA session?")
            .setPositiveButton("End Session") { _, _ ->
                performEndSessionSync()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performEndSessionSync() {
        val autoSync = DriveSyncManager.isAutoSyncEnabled(this)
        val webhookUrl = DriveSyncManager.getWebhookUrl(this)
        if (autoSync && webhookUrl.isNotBlank()) {
            @Suppress("DEPRECATION")
            val progressDialog = android.app.ProgressDialog(this).apply {
                setMessage("Syncing to Google Drive & archiving...")
                setCancelable(false)
                show()
            }
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    DriveSyncManager.syncAndArchiveAllFiles(
                        context = this@MainActivity,
                        csvManager = csvManager,
                        scannerNumber = csvManager.scannerName
                    )
                }
                try {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                } catch (_: Exception) {}

                if (result.failedCount > 0) {
                    // Sync failed: prompt user with immediate retry option
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Google Drive Sync Incomplete")
                        .setMessage("${result.message}.\n\nPlease check your Wi-Fi connection.\n\nWould you like to retry the sync, or exit without syncing?")
                        .setPositiveButton("Retry Sync") { _, _ ->
                            performEndSessionSync()
                        }
                        .setNegativeButton("Exit Without Sync") { _, _ ->
                            finishSessionAndExit()
                        }
                        .setNeutralButton("Cancel", null)
                        .show()
                } else {
                    finishSessionAndExit()
                }
            }
        } else {
            finishSessionAndExit()
        }
    }

    private fun finishSessionAndExit() {
        prefs.edit()
            .putBoolean(SessionActivity.KEY_SESSION_ACTIVE, false)
            .remove(SessionActivity.KEY_USER_INITIALS)
            .remove(SessionActivity.KEY_QA_LOCATION)
            .remove(SessionActivity.KEY_SESSION_DATE)
            .remove(SessionActivity.KEY_SESSION_TYPE)
            .apply()
        connectJob?.cancel()
        reconnectJob?.cancel()
        btManager.cancelConnect()
        btManager.disconnect()
        val intent = Intent(this, SessionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun triggerDriveSync(manual: Boolean = false) {
        val webhookUrl = DriveSyncManager.getWebhookUrl(this)
        if (webhookUrl.isBlank()) {
            if (manual) {
                showSnackbar("Please configure Google Drive Webhook URL in Settings", Color(0xFFE65100))
            }
            return
        }

        lifecycleScope.launch {
            if (manual) {
                showSnackbar("☁️ Syncing CSV files to Google Drive...")
            }

            val result = DriveSyncManager.syncAndArchiveAllFiles(
                context = this@MainActivity,
                csvManager = csvManager,
                scannerNumber = csvManager.scannerName
            )

            outputPath = csvManager.outputFilePath

            if (result.totalFiles == 0) {
                if (manual) {
                    showSnackbar("No CSV files found in folder to sync", Color(0xFFE65100))
                }
            } else if (result.failedCount > 0) {
                if (manual) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Sync Incomplete")
                        .setMessage("${result.message}.\n\nPlease check your Wi-Fi network connection and retry.")
                        .setPositiveButton("Retry") { _, _ ->
                            triggerDriveSync(manual = true)
                        }
                        .setNegativeButton("Dismiss", null)
                        .show()
                } else {
                    showSnackbar(result.message, Color(0xFFE65100))
                }
            } else {
                val color = Color(0xFF2E7D32)
                showSnackbar(result.message, color)
            }
        }
    }

    private fun checkSourceAssetUpdateSilently() {
        if (!DriveSyncManager.isSourceAutoUpdateEnabled(this)) return
        lifecycleScope.launch {
            val result = DriveSyncManager.checkAndUpdateSourceAssetList(this@MainActivity, csvManager, force = false)
            if (result is DriveSyncManager.SourceAssetUpdateResult.Updated) {
                sourceCsvName = result.fileName
                csvItemCount = result.assetCount
                csvManager.loadSavedSourceUri()?.let { uri ->
                    loadSourceCsv(uri, result.fileName)
                }
                showSnackbar("✓ Asset list updated from Google Drive (${result.assetCount} assets)", Color(0xFF2E7D32))
            }
        }
    }

    private fun triggerSourceAssetUpdate(manual: Boolean = true) {
        val webhookUrl = DriveSyncManager.getWebhookUrl(this)
        if (webhookUrl.isBlank()) {
            if (manual) showSnackbar("Please configure Webhook URL in Settings", Color(0xFFE65100))
            return
        }
        if (manual) showSnackbar("☁️ Checking Google Drive for updated asset list...")
        lifecycleScope.launch {
            when (val result = DriveSyncManager.checkAndUpdateSourceAssetList(this@MainActivity, csvManager, force = manual)) {
                is DriveSyncManager.SourceAssetUpdateResult.Updated -> {
                    sourceCsvName = result.fileName
                    csvItemCount = result.assetCount
                    csvManager.loadSavedSourceUri()?.let { uri ->
                        loadSourceCsv(uri, result.fileName)
                    }
                    showSnackbar("✓ Updated: ${result.fileName} (${result.assetCount} assets)", Color(0xFF2E7D32))
                }
                is DriveSyncManager.SourceAssetUpdateResult.UpToDate -> {
                    if (manual) showSnackbar("✓ Asset list is already up to date", Color(0xFF2E7D32))
                }
                is DriveSyncManager.SourceAssetUpdateResult.Failed -> {
                    if (manual) showSnackbar("Update failed: ${result.error}", Color(0xFFE65100))
                }
                is DriveSyncManager.SourceAssetUpdateResult.Offline -> {
                    if (manual) showSnackbar("Device is offline", Color(0xFFE65100))
                }
                is DriveSyncManager.SourceAssetUpdateResult.NoWebhook -> {
                    if (manual) showSnackbar("Please configure Webhook URL in Settings", Color(0xFFE65100))
                }
            }
        }
    }

    private fun showAboutDialog() {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        else
            @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0)

        AlertDialog.Builder(this)
            .setTitle("About ${getString(R.string.app_name)}")
            .setMessage("Version: ${packageInfo.versionName}\n\nBuilt by: Rui Xue")
            .setPositiveButton("OK", null)
            .show()
    }
}
