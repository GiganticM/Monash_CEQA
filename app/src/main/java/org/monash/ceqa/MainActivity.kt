package org.monash.ceqa

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
import android.text.InputFilter
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monash.ceqa.databinding.ActivityMainBinding
import org.monash.ceqa.models.Asset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var csvManager: CsvManager
    private lateinit var btManager: BluetoothSppManager
    private lateinit var scanLogAdapter: ScanLogAdapter

    private val scanLog = mutableListOf<Asset>()
    private var lookupMap: Map<String, List<CsvManager.SourceRecord>> = emptyMap()

    /** Tracks the currently running connect coroutine so it can be cancelled immediately. */
    private var connectJob: Job? = null

    /** Always dismiss this before showing a new snackbar so they never overlap. */
    private var activeSnackbar: Snackbar? = null

    /** Holds a reference to the folder path label inside the settings dialog,
     *  so it can be updated live when the user picks a new folder. */
    private var activeSettingsFolderLabel: TextView? = null

    /** Guards the one-shot silent BT reconnect on process launch so it never
     *  re-triggers when returning from AddAssetActivity or other sub-screens. */
    private var initialReconnectAttempted = false

    /**
     * Listens for ACTION_ACL_DISCONNECTED - fires within ~1 second of the radio
     * link dropping, far faster than detecting it via a failed send.
     * Only reacts to the device we are actually connected to.
     */
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
            updateBtStatusIndicator()
            Log.i("MainActivity", "ACL disconnected: ${device?.address}")
        }
    }

    companion object {
        private const val PREFS = "asset_scanner_prefs"
        private const val KEY_FONT_SIZE = "log_font_size"
        private const val KEY_LAST_DEVICE = "last_bt_device_address"
        private const val KEY_WAS_CONNECTED = "bt_was_connected_on_pause"
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
            csvManager.saveSourceUri(uri)
            loadSourceCsv(uri, showProgress = true)
        }
    }

    /** Folder picker for custom output location. */
    private val pickOutputFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            csvManager.saveOutputFolderUri(uri)
            csvManager.ensureOutputFileReady()
            updateOutputPathLabel()
            // Live-update the folder label inside the settings dialog if it's open
            activeSettingsFolderLabel?.text = csvManager.outputFilePath
            Toast.makeText(this, "Output folder updated", Toast.LENGTH_SHORT).show()
        }
    }

    private val addAssetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val assetNumber = data.getStringExtra(AddAssetActivity.EXTRA_ASSET_NUMBER) ?: ""
            val asset = Asset(
                assetNumber = assetNumber,
                department = data.getStringExtra(AddAssetActivity.EXTRA_DEPARTMENT) ?: "",
                make = data.getStringExtra(AddAssetActivity.EXTRA_MAKE) ?: "",
                model = data.getStringExtra(AddAssetActivity.EXTRA_MODEL) ?: "",
                serial = data.getStringExtra(AddAssetActivity.EXTRA_SERIAL) ?: "",
                isNew = true
            )
            saveAsset(asset)
            sendViaBluetooth(assetNumber)
            showSnackbar(
                "✓ NEW - '$assetNumber' added successfully",
                android.R.color.holo_green_dark
            )
        }
        // Always restore focus after returning from Add Asset screen
        binding.etSearch.post { binding.etSearch.requestFocus() }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        csvManager = CsvManager(this)
        btManager = BluetoothSppManager(this)

        csvManager.ensureOutputFileReady()

        setupScanLog()
        setupTabs()
        setupClickListeners()
        updateOutputPathLabel()
        updateBtStatusIndicator()
        setupBtReconnectButton()

        // CSV and BT restore handled in onResume
    }

    override fun onPause() {
        super.onPause()
        prefs.edit().putBoolean(KEY_WAS_CONNECTED, btManager.isConnected).apply()
        try {
            unregisterReceiver(aclReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    override fun onResume() {
        super.onResume()

        // Register ACL disconnect receiver for rapid disconnection detection
        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(aclReceiver, filter, RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(aclReceiver, filter)

        // ── Restore source CSV if not already loaded ──────────────────────────
        // lookupMap is in-memory; if the process was not killed it's already
        // populated and we skip the reload entirely (no flicker, no status change).
        if (lookupMap.isEmpty()) {
            csvManager.loadSavedSourceUri()?.let { savedUri ->
                binding.tvCsvStatus.text = "Reloading saved csv file..."
                loadSourceCsv(savedUri)
            }
        }

        // ── Restore Bluetooth connection if not already connected ─────────────
        // If the process was killed, lastKnownMac gives us the persisted MAC so
        // we can reconnect silently without the user touching the BT menu.
        if (!initialReconnectAttempted) {
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
    }

    override fun onDestroy() {
        super.onDestroy()
        connectJob?.cancel()
        btManager.cancelConnect()
        btManager.disconnect()
    }

    // ─── Scan Log ─────────────────────────────────────────────────────────────

    private fun setupScanLog() {
        scanLog.addAll(csvManager.readSavedAssets())
        val savedFontSize = prefs.getFloat(KEY_FONT_SIZE, ScanLogAdapter.FONT_SIZE_LARGE)
        scanLogAdapter = ScanLogAdapter(scanLog, savedFontSize)

        binding.rvScanLog.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = scanLogAdapter
            setHasFixedSize(false)
        }

        updateTerminalHeader()
        updateEmptyState()
        if (scanLog.isNotEmpty()) binding.rvScanLog.scrollToPosition(scanLog.size - 1)
    }

    // ─── BT Reconnect button ──────────────────────────────────────────────────

    /**
     * The reconnect button appears when a last device is known but we are not currently
     * connected. Tapping it immediately reconnects to that device.
     */
    private fun setupBtReconnectButton() {
        binding.btnBtReconnect.setOnClickListener {
            val device = btManager.lastDevice ?: run {
                val mac = btManager.lastKnownMac ?: return@setOnClickListener
                @Suppress("MissingPermission")
                btManager.getPairedDevices().find { it.address == mac } ?: return@setOnClickListener
            }
            connectToDevice(device)
        }
        updateBtReconnectButton()
    }

    private fun updateBtReconnectButton() {
        val hasLastDevice = btManager.lastDevice != null || btManager.lastKnownMac != null
        val show = hasLastDevice && !btManager.isConnected
        binding.btnBtReconnect.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ─── Tabs ─────────────────────────────────────────────────────────────────

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> {
                        binding.tabContentScan.visibility = View.VISIBLE
                        binding.tabContentLog.visibility = View.GONE
                    }

                    1 -> {
                        binding.tabContentScan.visibility = View.GONE
                        binding.tabContentLog.visibility = View.VISIBLE
                        if (scanLog.isNotEmpty())
                            binding.rvScanLog.scrollToPosition(scanLog.size - 1)
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    // ─── Options menu ─────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_change_csv -> {
                pickCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
                true
            }

            R.id.menu_bt_connect -> {
                if (!btManager.isBluetoothAvailable)
                    Toast.makeText(
                        this,
                        "This device does not support Bluetooth",
                        Toast.LENGTH_LONG
                    ).show()
                else if (!btManager.isBluetoothEnabled)
                    enableBtLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
                else
                    checkPermissionsAndShowPicker()
                true
            }

            R.id.menu_bt_disconnect -> {
                connectJob?.cancel()
                btManager.cancelConnect()
                btManager.disconnect()
                btManager.clearLastDevice()
                prefs.edit().remove(KEY_LAST_DEVICE).apply()
                updateBtStatusIndicator()
                Toast.makeText(this, "Bluetooth disconnected", Toast.LENGTH_SHORT).show()
                true
            }

            R.id.menu_settings -> {
                showSettingsDialog()
                true
            }

            R.id.menu_about -> {
                showAboutDialog()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    // ─── Settings dialog ──────────────────────────────────────────────────────

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)

        // ── Scanner name ──
        val etScannerName = view.findViewById<EditText>(R.id.etScannerName)
        etScannerName.setText(csvManager.scannerName)
        etScannerName.hint = ""

        // ── Output folder ──
        val tvCurrentFolder = view.findViewById<TextView>(R.id.tvCurrentFolder)
        val btnChangeFolder = view.findViewById<Button>(R.id.btnChangeFolder)
        tvCurrentFolder.text = csvManager.outputFilePath
        activeSettingsFolderLabel = tvCurrentFolder
        btnChangeFolder.setOnClickListener {
            pickOutputFolderLauncher.launch(null)
        }

        // ── Font size radio ──
        val rgFontSize = view.findViewById<RadioGroup>(R.id.rgFontSize)
        val rbSmall = view.findViewById<RadioButton>(R.id.rbFontSmall)
        val rbMedium = view.findViewById<RadioButton>(R.id.rbFontMedium)
        val rbLarge = view.findViewById<RadioButton>(R.id.rbFontLarge)

        when (scanLogAdapter.fontSizeSp) {
            ScanLogAdapter.FONT_SIZE_SMALL -> rbSmall.isChecked = true
            ScanLogAdapter.FONT_SIZE_LARGE -> rbLarge.isChecked = true
            else -> rbMedium.isChecked = true
        }

        AlertDialog.Builder(this)
            .setTitle("⚙ Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val newName = etScannerName.text.toString()
                    .trim().lowercase().replace(" ", "_")
                csvManager.scannerName = newName

                val newFontSize = when (rgFontSize.checkedRadioButtonId) {
                    R.id.rbFontSmall -> ScanLogAdapter.FONT_SIZE_SMALL
                    R.id.rbFontLarge -> ScanLogAdapter.FONT_SIZE_LARGE
                    else -> ScanLogAdapter.FONT_SIZE_MEDIUM
                }
                prefs.edit().putFloat(KEY_FONT_SIZE, newFontSize).apply()
                scanLogAdapter.fontSizeSp = newFontSize
                scanLogAdapter.refreshFontSize()

                csvManager.ensureOutputFileReady()
                updateOutputPathLabel()
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { activeSettingsFolderLabel = null }
            .show()
    }

    private fun showAboutDialog() {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        else
            @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0)

        AlertDialog.Builder(this)
            .setTitle("ℹ️ About Monash CEQA")
            .setMessage("\nVersion: ${packageInfo.versionName}\n\nBuilt by: Rui Xue")
            .setPositiveButton("OK", null)
            .show()
    }

    // ─── Click listeners ──────────────────────────────────────────────────────

    private fun setupClickListeners() {
        // Upper case for input from soft and hardware keyboard
        binding.etSearch.filters = arrayOf(InputFilter.AllCaps())
        val triggerSearch = {
            val query = binding.etSearch.text.toString().trim()
            if (query.isEmpty()) {
                binding.etSearch.error = "Please enter or scan asset number"
            } else if (lookupMap.isEmpty()) {
                Toast.makeText(this, "Please load a source csv file first", Toast.LENGTH_SHORT)
                    .show()
            } else {
                binding.etSearch.error = null
                performSearch(query)
            }
            // Always clear text and restore focus after a search attempt
            binding.etSearch.setText("")
            binding.etSearch.post { binding.etSearch.requestFocus() }
        }

        binding.btnSearch.setOnClickListener { triggerSearch() }

        // Handle software Search / Done IME button
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                triggerSearch()
                true
            } else false
        }

        // Handle hardware keyboard / barcode scanner Enter key
        binding.etSearch.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_DOWN) triggerSearch()
                true  // consume Enter to prevent focus navigation
            } else false
        }
    }

    // ─── CSV Loading ──────────────────────────────────────────────────────────

    private fun loadSourceCsv(uri: Uri, showProgress: Boolean = false) {
        if (showProgress) {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvCsvStatus.text = "Loading csv file…"
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { csvManager.loadLookupMap(uri) }
            }
            binding.progressBar.visibility = View.GONE
            result.onSuccess { (map, assetCount) ->
                lookupMap = map
                binding.tvCsvStatus.text =
                    "✓ Loaded $assetCount assets"
                reResolveDepartments()
            }.onFailure {
                lookupMap = emptyMap()
                binding.tvCsvStatus.text = "✗ Failed to load csv file!"
                csvManager.clearSourceUri()
                Toast.makeText(this@MainActivity, "Error: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * After a CSV reload, iterates the scan log and fills in any missing department
     * values for FOUND assets that were restored from the output file (where department
     * is not persisted).
     */
    private fun reResolveDepartments() {
        if (lookupMap.isEmpty()) return
        var changed = false
        scanLog.forEachIndexed { index, asset ->
            if (!asset.isNew && asset.department.isEmpty()) {
                val match = lookupMap[asset.assetNumber.lowercase()]?.firstOrNull()
                if (match != null) {
                    scanLog[index] = asset.copy(department = match.department)
                    changed = true
                }
            }
        }
        if (changed) scanLogAdapter.notifyDataSetChanged()
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    /**
     * Strips barcode scanner padding from a 35-character Genius 3 Datamatrix input.
     * The asset number is always N or X followed by exactly 8 digits at the end.
     * If the pattern is not found the raw input is returned unchanged.
     */
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
                if (distinctAssets.size == 1) resolveAndSave(distinctAssets.first())
                else showAssetPickerDialog(query, distinctAssets)
            }

            else -> {
                // Not in source CSV - check if already logged as NEW today
                if (csvManager.isAlreadyLogged(query)) {
                    sendViaBluetooth(query)
                    showFoundBanner(query, alreadyLogged = true)
                } else {
                    val intent = Intent(this, AddAssetActivity::class.java).apply {
                        putExtra(AddAssetActivity.EXTRA_ASSET_NUMBER, query)
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

        val dialog = AlertDialog.Builder(this)
            .setTitle("Multiple assets match \"$scannedValue\"!\nPlease select the correct one:")
            .setSingleChoiceItems(displayItems, selectedIndex) { _, index ->
                selectedIndex = index
            }
            .setPositiveButton("OK") { _, _ -> resolveAndSave(matches[selectedIndex]) }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnDismissListener {
            binding.etSearch.post { binding.etSearch.requestFocus() }
        }
        dialog.show()
    }

    private fun resolveAndSave(record: CsvManager.SourceRecord) {
        sendViaBluetooth(record.assetNumber)
        if (csvManager.isAlreadyLogged(record.assetNumber)) {
            showFoundBanner(record.assetNumber, alreadyLogged = true)
        } else {
            val asset = Asset(
                assetNumber = record.assetNumber,
                department = record.department,
                isNew = false
            )
            saveAsset(asset)
            showFoundBanner(record.assetNumber, alreadyLogged = false)
        }
    }

    // ─── Save ─────────────────────────────────────────────────────────────────

    private fun saveAsset(asset: Asset) {
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = csvManager.appendAsset(asset)
            withContext(Dispatchers.Main) {
                scanLogAdapter.addItem(saved)
                binding.rvScanLog.scrollToPosition(scanLogAdapter.itemCount - 1)
                updateTerminalHeader()
                updateEmptyState()
                updateOutputPathLabel()
            }
        }
    }

    // ─── Bluetooth ────────────────────────────────────────────────────────────

    /**
     * Sends [text] to the connected SPP device.
     * On failure attempts a one-shot silent reconnect and retries.
     * Uses [showSnackbar] on final failure so it never overlaps a result banner.
     */
    private fun sendViaBluetooth(text: String) {
        if (!btManager.isConnected) return
        lifecycleScope.launch(Dispatchers.IO) {
            var sent = btManager.sendString(text)

            if (!sent) {
                // Device may have power-cycled - attempt silent reconnect once
                val lastDevice = btManager.lastDevice
                if (lastDevice != null) {
                    Log.i(
                        "MainActivity",
                        "BT send failed - attempting silent reconnect to ${lastDevice.name}"
                    )
                    val result = btManager.connect(lastDevice)
                    if (result is BluetoothSppManager.ConnectionResult.Success) {
                        sent = btManager.sendString(text)
                    }
                }
            }

            if (!sent) {
                withContext(Dispatchers.Main) {
                    showSnackbar(
                        "⚠ Bluetooth connection failed",
                        android.R.color.holo_orange_dark,
                        Snackbar.LENGTH_SHORT
                    )
                    updateBtStatusIndicator()
                    setupBtReconnectButton()
                }
            }
        }
    }

    private fun checkPermissionsAndShowPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ).filter {
                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                requestBtPermissions.launch(needed.toTypedArray()); return
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
                .setMessage("No paired Bluetooth devices found.\n\nGo to Android Settings → Bluetooth, pair your MEM first, then try again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val displayNames = devices.map { "${it.name ?: "Unknown"} (${it.address})" }
        AlertDialog.Builder(this)
            .setTitle("Select MEM")
            .setAdapter(
                ArrayAdapter(
                    this,
                    android.R.layout.simple_list_item_1,
                    displayNames
                )
            ) { _, index ->
                connectToDevice(devices[index])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectToDevice(device: BluetoothDevice, silent: Boolean = false) {
        // Cancel any in-progress attempt immediately before starting a new one
        connectJob?.cancel()
        btManager.cancelConnect()

        @Suppress("MissingPermission")
        val name = device.name ?: device.address
        binding.tvBtStatus.text = "Connecting to $name…"

        connectJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { btManager.connect(device) }
            connectJob = null
            when (result) {
                is BluetoothSppManager.ConnectionResult.Success -> {
                    updateBtStatusIndicator()
                    showSnackbar(
                        "✓ Connected to ${result.deviceName}",
                        android.R.color.holo_green_dark,
                        Snackbar.LENGTH_SHORT
                    )
                }

                is BluetoothSppManager.ConnectionResult.Failure -> {
                    updateBtStatusIndicator()
                    setupBtReconnectButton()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Connection Failed")
                        .setMessage("${result.error}\n\nMake sure the device is powered on and in range.")
                        .setPositiveButton("OK", null)
                        .show()
                }
                // Cancelled -- new attempt started or app closing; stay silent
                is BluetoothSppManager.ConnectionResult.Cancelled -> {
                    updateBtStatusIndicator()
                    setupBtReconnectButton()
                }
            }
        }
    }

    // ─── UI helpers ───────────────────────────────────────────────────────────

    /** Shows a snackbar, dismissing any currently visible one first so they never overlap. */
    private fun showSnackbar(message: String, colorRes: Int, duration: Int = Snackbar.LENGTH_LONG) {
        activeSnackbar?.dismiss()
        activeSnackbar = Snackbar.make(binding.root, message, duration).apply {
            setBackgroundTint(getColor(colorRes))
            setTextColor(getColor(android.R.color.white))
            show()
        }
    }

    private fun showFoundBanner(assetNumber: String, alreadyLogged: Boolean) {
        val message = if (alreadyLogged)
            "⚠ FOUND - '$assetNumber' already logged"
        else
            "✓ FOUND - '$assetNumber' logged successfully"
        showSnackbar(
            message,
            if (alreadyLogged) android.R.color.holo_orange_dark
            else android.R.color.holo_green_dark
        )
    }

    private fun updateBtStatusIndicator() {
        binding.tvBtStatus.text = if (btManager.isConnected) {
            val name = btManager.lastDevice?.let {
                @Suppress("MissingPermission") it.name
            } ?: "Unknown"
            "🔵 Bluetooth: Connected to $name"
        } else {
            val name = btManager.lastDevice?.let {
                @Suppress("MissingPermission") it.name
            } ?: btManager.lastKnownMac?.let { mac ->
                @Suppress("MissingPermission")
                btManager.getPairedDevices().find { it.address == mac }?.name ?: mac
            }
            if (name != null) "⚫ Bluetooth: Disconnected"
            else "⚫ Bluetooth: Not connected - use ⋮ menu to connect"
        }
        updateBtReconnectButton()
    }

    private fun updateOutputPathLabel() {
        binding.tvOutputPath.text = "► ${csvManager.outputFilePath}"
    }

    private fun updateTerminalHeader() {
        val date = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        val count = scanLogAdapter.recordCount()
        val scanner = csvManager.scannerName.ifBlank { "DEFAULT" }.uppercase()
        val label = if (count == 1) "1 RECORD TODAY" else "$count RECORDS TODAY"
        binding.tvTerminalHeader.text = "[ MONASH CEQA │ $date │ $label ]"
    }

    private fun updateEmptyState() {
        val hasItems = scanLogAdapter.recordCount() > 0
        binding.rvScanLog.visibility = if (hasItems) View.VISIBLE else View.GONE
        binding.tvLogEmpty.visibility = if (hasItems) View.GONE else View.VISIBLE
    }
}
