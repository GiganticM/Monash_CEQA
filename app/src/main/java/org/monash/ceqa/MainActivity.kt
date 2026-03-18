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
import android.util.Log
import android.text.InputFilter
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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

    /** True when the SCAN tab is the active tab — set synchronously in onTabSelected
     *  so dispatchKeyEvent never races against the layout visibility pass. */
    private var isScanTabActive = true

    /** Tracks the currently running connect coroutine so it can be cancelled immediately. */
    private var connectJob: Job? = null
    /** Runs a 10-second retry loop after unexpected disconnection. Cancelled on manual disconnect. */
    private var reconnectJob: Job? = null
    /** Pulses the RECONNECT button alpha while auto-reconnect is in progress. */
    private var reconnectPulse: ObjectAnimator? = null

    /** Always dismiss this before showing a new snackbar so they never overlap. */
    private var activeSnackbar: Snackbar? = null

    /** Buffer for hardware scanner input — flushed to etSearch only when Enter arrives,
     *  avoiding race conditions with view initialisation on first scan after launch/resume. */
    private val scanBuffer = StringBuilder()

    /** Set to true just before launching the CSV file picker so onResume doesn't
     *  show the "Reloading" message while the picker is returning. */
    private var manualCsvPickInProgress = false

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
            startAutoReconnect()
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
            csvManager.saveSourceUri(uri)
            loadSourceCsv(uri, showProgress = true)
        }
    }

    /** Folder picker for custom output location. */
    /** Launches SettingsActivity; refreshes font size and output path on return. */
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Re-read font size in case it changed
            val newFontSize = prefs.getFloat(KEY_FONT_SIZE, ScanLogAdapter.FONT_SIZE_LARGE)
            scanLogAdapter.fontSizeSp = newFontSize
            scanLogAdapter.refreshFontSize()
            updateOutputPathLabel()
            updateTerminalHeader()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }
    }

    private val addAssetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val assetNumber = data.getStringExtra(AddAssetActivity.EXTRA_ASSET_NUMBER) ?: ""
            val asset = Asset(
                assetNumber  = assetNumber,
                department   = data.getStringExtra(AddAssetActivity.EXTRA_DEPARTMENT) ?: "",
                make         = data.getStringExtra(AddAssetActivity.EXTRA_MAKE) ?: "",
                model        = data.getStringExtra(AddAssetActivity.EXTRA_MODEL) ?: "",
                serial       = data.getStringExtra(AddAssetActivity.EXTRA_SERIAL) ?: "",
                isNew        = true,
                userInitials = prefs.getString(SessionActivity.KEY_USER_INITIALS, "") ?: "",
                qaLocation   = prefs.getString(SessionActivity.KEY_QA_LOCATION, "") ?: ""
            )
            saveAsset(asset)
            sendViaBluetooth(assetNumber)
            showSnackbar(
                "✓ NEW - '$assetNumber' added successfully",
                android.R.color.holo_green_dark,
                prefs.getInt(KEY_SNACKBAR_DURATION, 3000)
            )
        }
        // Always restore focus after returning from Add Asset screen
        binding.etSearch.post { binding.etSearch.requestFocus() }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        supportActionBar?.title = "Monash Health CEQA"
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Redirect to session screen if no active session
        if (!SessionActivity.isSessionActive(this)) {
            startActivity(Intent(this, SessionActivity::class.java))
            finish()
            return
        }

        // Exit selection mode on back gesture/button; otherwise default behaviour
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (scanLogAdapter.selectionMode) {
                    scanLogAdapter.exitSelectionMode()
                    binding.layoutSelectionBar.visibility = View.GONE
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        csvManager = CsvManager(this)
        btManager = BluetoothSppManager(this)
        btManager.onDisconnected = {
            // Called on IO thread — post to main thread
            runOnUiThread {
                updateBtStatusIndicator()
                startAutoReconnect()
            }
        }

        csvManager.ensureOutputFileReady()

        setupScanLog()
        setupTabs()
        setupClickListeners()
        updateOutputPathLabel()
        updateBtStatusIndicator()
        updateSessionInfoLabel()
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
        // Also skip if a manual CSV pick is in progress — the picker result will
        // handle the load itself so we don't show the wrong "Reloading" message.
        if (lookupMap.isEmpty() && !manualCsvPickInProgress) {
            csvManager.loadSavedSourceUri()?.let { savedUri ->
                binding.tvCsvStatus.text = "Reloading saved csv file..."
                loadSourceCsv(savedUri)
            }
        }
        manualCsvPickInProgress = false

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

        // Move focus to the container so etSearch has no active InputConnection
        // on resume. This matches the tab-switch state where the first scan works
        // correctly — chars go via dispatchKeyEvent buffer rather than InputConnection.
        binding.tabContentScan.requestFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectJob?.cancel()
        reconnectJob?.cancel()
        stopReconnectPulse()
        btManager.cancelConnect()
        btManager.disconnect()
    }

    /**
     * Focuses etSearch the moment the window is ready so InputConnection is
     * active before the first scan. Immediately hides the soft keyboard so it
     * doesn't appear — the hardware barcode scanner doesn't need it.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && binding.tabContentScan.visibility == View.VISIBLE) {
            binding.etSearch.requestFocus()
        }
    }

    /**
     * Intercepts all hardware key events at the Activity level so the barcode
     * scanner works even when [etSearch] is not focused (e.g. after switching
     * tabs and back).  Only active when the SCAN tab is visible.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Only intercept on the SCAN tab
        if (binding.tabContentScan.visibility != View.VISIBLE) return super.dispatchKeyEvent(event)
        // Only act on key-down to avoid double-firing
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        return when {
            event.keyCode == KeyEvent.KEYCODE_ENTER -> {
                val buffered = scanBuffer.toString()
                scanBuffer.clear()
                binding.etSearch.post {
                    val fromField = binding.etSearch.text.toString()
                    // Prefer buffer if non-empty (scanner path); fall back to field
                    // (InputConnection path). Using buffer+field would duplicate when
                    // both paths delivered the same characters.
                    val combined = (if (buffered.isNotEmpty()) buffered else fromField).trim().uppercase()
                    binding.etSearch.setText("")
                    if (combined.isEmpty()) {
                        binding.etSearch.error = "Please enter or scan asset number"
                    } else if (lookupMap.isEmpty()) {
                        Toast.makeText(this, "Please load a source csv file first", Toast.LENGTH_SHORT).show()
                    } else {
                        binding.etSearch.error = null
                        performSearch(combined)
                    }
                    binding.etSearch.post { binding.etSearch.requestFocus() }
                }
                true
            }
            event.unicodeChar > 0 -> {
                // Buffer the character for the Enter handler to collect.
                // Also pass to super so InputConnection receives it when etSearch
                // is focused — this handles soft keyboard input correctly.
                // The Enter handler combines both buffer + field to avoid duplicates
                // when both paths deliver the same character.
                scanBuffer.append(event.getUnicodeChar(0).toChar().uppercaseChar())
                super.dispatchKeyEvent(event)
            }
            else -> super.dispatchKeyEvent(event)
        }
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

        // ── Selection mode callbacks ──────────────────────────────────────────
        scanLogAdapter.onEnterSelectionMode = {
            binding.layoutSelectionBar.visibility = View.VISIBLE
            binding.tvSelectionCount.text = "1 selected"
        }
        scanLogAdapter.onSelectionChanged = { count ->
            binding.tvSelectionCount.text = if (count == 1) "1 selected" else "$count selected"
        }
        binding.btnCancelSelection.setOnClickListener {
            scanLogAdapter.exitSelectionMode()
            binding.layoutSelectionBar.visibility = View.GONE
        }
        binding.btnDeleteSelected.setOnClickListener {
            val count = scanLogAdapter.selectedCount
            if (count == 0) return@setOnClickListener
            val label = if (count == 1) "1 record" else "$count records"
            AlertDialog.Builder(this)
                .setTitle("Delete Records")
                .setMessage("Are you sure you want to permanently delete $label from the log and the output CSV?\n\nThis cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> deleteSelectedRecords() }
                .setNegativeButton("Cancel", null)
                .show()
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
                manualCsvPickInProgress = true
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
                reconnectJob?.cancel()
                reconnectJob = null
                stopReconnectPulse()
                btManager.cancelConnect()
                btManager.disconnect()
                btManager.clearLastDevice()
                prefs.edit().remove(KEY_LAST_DEVICE).apply()
                updateBtStatusIndicator()
                Toast.makeText(this, "Bluetooth disconnected", Toast.LENGTH_SHORT).show()
                true
            }

            R.id.menu_settings -> {
                settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
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

    private fun showAboutDialog() {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        else
            @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0)

        AlertDialog.Builder(this)
            .setTitle("ℹ️ About ${getString(R.string.app_name)}")
            .setMessage("\nVersion: ${packageInfo.versionName}\n\nBuilt by: Rui Xue")
            .setPositiveButton("OK", null)
            .show()
    }

    // ─── Click listeners ──────────────────────────────────────────────────────

    private fun setupClickListeners() {
        // Force uppercase for both soft keyboard and hardware scanner input
        binding.etSearch.filters = arrayOf(InputFilter.AllCaps())

        binding.btnSearch.setOnClickListener { triggerSearch() }
        binding.btnEndSession.setOnClickListener { showEndSessionDialog() }

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

    private fun triggerSearch() {
        val query = binding.etSearch.text.toString().trim()
        if (query.isEmpty()) {
            binding.etSearch.error = "Please enter or scan asset number"
        } else if (lookupMap.isEmpty()) {
            Toast.makeText(this, "Please load a source csv file first", Toast.LENGTH_SHORT).show()
        } else {
            binding.etSearch.error = null
            performSearch(query)
        }
        // Always clear text and restore focus after a search attempt
        binding.etSearch.setText("")
        binding.etSearch.post { binding.etSearch.requestFocus() }
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
            if (!asset.isNew) {
                val match = lookupMap[asset.assetNumber.lowercase()]?.firstOrNull()
                if (match != null) {
                    val needsDept   = asset.department.isEmpty() && match.department.isNotEmpty()
                    val needsPeriod = asset.routinePeriod == 0 && match.routinePeriod != 0
                    if (needsDept || needsPeriod) {
                        scanLog[index] = asset.copy(
                            department    = if (needsDept) match.department else asset.department,
                            routinePeriod = if (needsPeriod) match.routinePeriod else asset.routinePeriod
                        )
                        changed = true
                    }
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
            showFoundBanner(record.assetNumber, alreadyLogged = true, routinePeriod = record.routinePeriod)
        } else {
            val asset = Asset(
                assetNumber  = record.assetNumber,
                department   = record.department,
                isNew        = false,
                routinePeriod = record.routinePeriod,
                userInitials = prefs.getString(SessionActivity.KEY_USER_INITIALS, "") ?: "",
                qaLocation   = prefs.getString(SessionActivity.KEY_QA_LOCATION, "") ?: ""
            )
            saveAsset(asset)
            showFoundBanner(record.assetNumber, alreadyLogged = false, routinePeriod = record.routinePeriod)
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

    private fun deleteSelectedRecords() {
        val toDelete = scanLogAdapter.getSelectedAssetNumbers()
        lifecycleScope.launch(Dispatchers.IO) {
            csvManager.deleteAssets(toDelete)
            withContext(Dispatchers.Main) {
                scanLog.removeAll { it.assetNumber in toDelete }
                scanLogAdapter.exitSelectionMode()
                binding.layoutSelectionBar.visibility = View.GONE
                scanLogAdapter.notifyDataSetChanged()
                updateTerminalHeader()
                updateEmptyState()
                val label = if (toDelete.size == 1) "1 record deleted" else "${toDelete.size} records deleted"
                Toast.makeText(this@MainActivity, label, Toast.LENGTH_SHORT).show()
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
                    @SuppressLint("MissingPermission")
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

    private fun startReconnectPulse() {
        reconnectPulse?.cancel()
        reconnectPulse = ObjectAnimator.ofFloat(binding.btnBtReconnect, "alpha", 1f, 0.25f).apply {
            duration = 800
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopReconnectPulse() {
        reconnectPulse?.cancel()
        reconnectPulse = null
        binding.btnBtReconnect.alpha = 1f
    }

    /**
     * Starts a background loop that retries connecting to the last known device
     * every 10 seconds until it succeeds or is cancelled.
     * No-op if already reconnecting or no last device MAC is known.
     */
    private fun startAutoReconnect() {
        if (reconnectJob?.isActive == true) return
        val mac = btManager.lastKnownMac ?: return
        startReconnectPulse()
        reconnectJob = lifecycleScope.launch {
            Log.i("MainActivity", "Auto-reconnect loop started for $mac")
            while (true) {
                kotlinx.coroutines.delay(10_000)
                if (btManager.isConnected) break
                val device = btManager.getPairedDevices().find { it.address == mac } ?: break
                Log.i("MainActivity", "Auto-reconnect: attempting…")
                val result = withContext(Dispatchers.IO) { btManager.connect(device) }
                when (result) {
                    is BluetoothSppManager.ConnectionResult.Success -> {
                        Log.i("MainActivity", "Auto-reconnect succeeded")
                        stopReconnectPulse()
                        updateBtStatusIndicator()
                        showSnackbar(
                            "✓ Reconnected to ${result.deviceName}",
                            android.R.color.holo_green_dark,
                            Snackbar.LENGTH_SHORT
                        )
                        break
                    }
                    is BluetoothSppManager.ConnectionResult.Failure ->
                        Log.i("MainActivity", "Auto-reconnect attempt failed, retrying in 10s…")
                    is BluetoothSppManager.ConnectionResult.Cancelled -> break
                }
            }
            stopReconnectPulse()
            reconnectJob = null
        }
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
                    reconnectJob?.cancel()
                    reconnectJob = null
                    stopReconnectPulse()
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
                    if (silent) {
                        Toast.makeText(this@MainActivity, "⚠ Bluetooth connection failed", Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Connection Failed")
                            .setMessage("${result.error}\n\nMake sure the device is powered on and in range.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
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
    /**
     * Shows a snackbar, dismissing any currently visible one first so they never overlap.
     * When [duration] is a custom millisecond value (> 10ms), a thin countdown bar is drawn
     * at the bottom of the snackbar that drains from right to left over the full duration.
     */
    private fun showSnackbar(message: CharSequence, colorRes: Int, duration: Int = Snackbar.LENGTH_LONG) {
        activeSnackbar?.dismiss()
        activeSnackbar = Snackbar.make(binding.root, message, duration).apply {
            setBackgroundTint(getColor(colorRes))
            setTextColor(getColor(android.R.color.white))

            // Add countdown bar only for custom ms durations (not LENGTH_SHORT/LONG constants)
            if (duration > 10) {
                val snackLayout = view as android.widget.FrameLayout
                val countdownBar = View(this@MainActivity).apply {
                    setBackgroundColor(Color.argb(80, 255, 255, 255))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 6
                    )
                    pivotX = 0f   // anchor left — bar drains from right to left
                }
                snackLayout.addView(countdownBar)

                // Wait for layout pass so the view has a real width before animating
                countdownBar.post {
                    val animator = ValueAnimator.ofFloat(1f, 0f).apply {
                        this.duration = duration.toLong()
                        interpolator = android.view.animation.LinearInterpolator()
                        addUpdateListener { countdownBar.scaleX = it.animatedValue as Float }
                        start()
                    }
                    addCallback(object : Snackbar.Callback() {
                        override fun onDismissed(bar: Snackbar?, event: Int) {
                            animator.cancel()
                        }
                    })
                }
            }

            show()
        }
    }

    private fun showFoundBanner(assetNumber: String, alreadyLogged: Boolean, routinePeriod: Int = 0) {
        val stickerSuffix = when (routinePeriod) {
            12   -> "\n[YELLOW (12mths)]"
            24   -> "\n[WHITE (24mths)]"
            60   -> "\n[GREEN (60mths)]"
            else -> ""
        }
        val stickerColor = when (routinePeriod) {
            12   -> Color.parseColor("#FDD835")
            24   -> Color.parseColor("#F5F5F5")
            60   -> Color.parseColor("#66BB6A")
            else -> 0
        }
        val base = if (alreadyLogged)
            "⚠ FOUND - '$assetNumber' already logged"
        else
            "✓ FOUND - '$assetNumber' logged successfully"

        val message: CharSequence = if (stickerSuffix.isNotEmpty()) {
            SpannableString(base + stickerSuffix).also { span ->
                span.setSpan(
                    ForegroundColorSpan(stickerColor),
                    base.length, base.length + stickerSuffix.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } else base

        val duration = prefs.getInt(KEY_SNACKBAR_DURATION, 3000)
        showSnackbar(
            message,
            if (alreadyLogged) android.R.color.holo_orange_dark
            else android.R.color.holo_green_dark,
            duration
        )
    }

    private fun showEndSessionDialog() {
        AlertDialog.Builder(this)
            .setTitle("End Session?")
            .setMessage("Are you sure you want to end the current QA session?")
            .setPositiveButton("End Session") { _, _ ->
                prefs.edit()
                    .putBoolean(SessionActivity.KEY_SESSION_ACTIVE, false)
                    .remove(SessionActivity.KEY_USER_INITIALS)
                    .remove(SessionActivity.KEY_QA_LOCATION)
                    .apply()
                connectJob?.cancel()
                reconnectJob?.cancel()
                stopReconnectPulse()
                btManager.cancelConnect()
                btManager.disconnect()
                startActivity(Intent(this, SessionActivity::class.java))
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateSessionInfoLabel() {
        val initials = prefs.getString(SessionActivity.KEY_USER_INITIALS, "") ?: ""
        val location = prefs.getString(SessionActivity.KEY_QA_LOCATION, "") ?: ""
        if (initials.isNotEmpty() || location.isNotEmpty()) {
            binding.tvSessionInfo.text = "👤 $initials  📍 $location"
            binding.layoutSessionInfo.visibility = View.VISIBLE
        } else {
            binding.layoutSessionInfo.visibility = View.GONE
        }
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
        binding.tvTerminalHeader.text = "[ MH CEQA │ $date │ $label ]"
    }

    private fun updateEmptyState() {
        val hasItems = scanLogAdapter.recordCount() > 0
        binding.rvScanLog.visibility = if (hasItems) View.VISIBLE else View.GONE
        binding.tvLogEmpty.visibility = if (hasItems) View.GONE else View.VISIBLE
    }
}
