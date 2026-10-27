package me.rxue.mhceqa.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rxue.mhceqa.CsvManager
import me.rxue.mhceqa.models.Asset
import me.rxue.mhceqa.ui.components.BtState
import me.rxue.mhceqa.ui.components.BtStatusChip
import me.rxue.mhceqa.ui.components.CustomSnackbar
import me.rxue.mhceqa.ui.components.CustomSnackbarVisuals
import me.rxue.mhceqa.ui.components.ScanLogCard
import me.rxue.mhceqa.ui.theme.JetBrainsMonoFontFamily
import me.rxue.mhceqa.ui.theme.PrimaryBlue
import me.rxue.mhceqa.ui.theme.TabUnselectedText
import me.rxue.mhceqa.ui.theme.TerminalBackground
import me.rxue.mhceqa.ui.theme.TerminalGreenBright
import me.rxue.mhceqa.ui.theme.TerminalGreenSecondary
import me.rxue.mhceqa.ui.theme.TerminalHeaderBackground
import me.rxue.mhceqa.ui.theme.TerminalSelectionBackground
import me.rxue.mhceqa.ui.theme.TextDark
import me.rxue.mhceqa.ui.theme.TextSubtle

/**
 * Checks if the scan record matches the currently logged in session user.
 * Supports composite initials (e.g. "RX AS" matches records scanned under "RX", "AS", or "RX AS").
 */
fun matchesUser(assetInitials: String, currentInitials: String): Boolean {
    val trimmedCurrent = currentInitials.trim().uppercase()
    val trimmedAsset = assetInitials.trim().uppercase()

    if (trimmedCurrent.isEmpty() || trimmedAsset.isEmpty()) return true
    if (trimmedCurrent == trimmedAsset) return true

    val currentTokens = trimmedCurrent.split(Regex("[\\s,/+\\-&]+")).filter { it.isNotBlank() }.toSet()
    val assetTokens = trimmedAsset.split(Regex("[\\s,/+\\-&]+")).filter { it.isNotBlank() }.toSet()

    return currentTokens.intersect(assetTokens).isNotEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScannerScreen(
    scanLog: List<Asset>,
    btState: BtState,
    btDeviceName: String?,
    sourceCsvName: String,
    csvItemCount: Int,
    outputPath: String,
    fontSizeSp: Float,
    snackbarHostState: SnackbarHostState,
    focusRequester: FocusRequester,
    selectedTab: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    lastScannedAssetNumber: String = "",
    lastScannedRoutinePeriod: Int = 0,
    isIoSession: Boolean = false,
    searchInput: String = "",
    onSearchInputChange: (String) -> Unit = {},
    userInitials: String = "",
    userLocation: String = "",
    onSearchSubmitted: (String) -> Unit,
    onConnectBt: () -> Unit,
    onDisconnectBt: () -> Unit,
    onPickCsv: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onEndSession: () -> Unit,
    onSyncDrive: () -> Unit = {},
    onUpdateSourceCsv: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = if (isIoSession) "MH CEQA (IO)" else "MH CEQA",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    },
                    actions = {
                        TextButton(
                            onClick = onEndSession,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF8A80))
                        ) {
                            Text(
                                text = "END SESSION",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menu",
                                tint = Color.White
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (!isIoSession) {
                                DropdownMenuItem(
                                    text = { Text("Bluetooth Connect") },
                                    onClick = {
                                        showMenu = false
                                        onConnectBt()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Bluetooth Disconnect") },
                                    onClick = {
                                        showMenu = false
                                        onDisconnectBt()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Sync with Google Drive") },
                                onClick = {
                                    showMenu = false
                                    onSyncDrive()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    showMenu = false
                                    onOpenSettings()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("About") },
                                onClick = {
                                    showMenu = false
                                    onOpenAbout()
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = PrimaryBlue
                    )
                )

                // 2-Tab Row (SCAN & LOG) - shorter height for compact screens
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = PrimaryBlue,
                    contentColor = Color.White,
                    modifier = Modifier.height(36.dp),
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            height = 2.dp,
                            color = Color.White
                        )
                    }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { onTabSelected(0) },
                        modifier = Modifier.height(36.dp),
                        text = {
                            Text(
                                text = "SCAN",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (selectedTab == 0) Color.White else TabUnselectedText
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { onTabSelected(1) },
                        modifier = Modifier.height(36.dp),
                        text = {
                            Text(
                                text = "LOG",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (selectedTab == 1) Color.White else TabUnselectedText
                            )
                        }
                    )
                }
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                val custom = data.visuals as? CustomSnackbarVisuals
                if (custom != null) {
                    CustomSnackbar(custom)
                } else {
                    androidx.compose.material3.Snackbar(
                        snackbarData = data,
                        containerColor = Color(0xFF323232),
                        contentColor = Color.White
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (selectedTab == 0) {
                // ══════════════════════════════════════════════════════════════
                // TAB 1 - SCAN (White Background, Original XML Layout)
                // ══════════════════════════════════════════════════════════════
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // CSV Status
                        Text(
                            text = if (csvItemCount > 0)
                                "✓ Loaded $csvItemCount assets"
                            else
                                "No source CSV loaded - use ⋮ menu to load",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSubtle
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // User info on left (2 lines: initials top, location bottom), BT status chip / IO banner on right
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (userInitials.isNotBlank() || userLocation.isNotBlank()) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .padding(end = 8.dp)
                                ) {
                                    if (userInitials.isNotBlank()) {
                                        Text(
                                            text = "👤 ${userInitials.trim()}",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextDark,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                    if (userLocation.isNotBlank()) {
                                        Text(
                                            text = "📍 ${userLocation.trim()}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextSubtle,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            } else {
                                Spacer(modifier = Modifier.weight(1f, fill = false))
                            }

                            if (isIoSession) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFFFFF3E0),
                                    border = BorderStroke(1.dp, Color(0xFFFFB74D))
                                ) {
                                    Text(
                                        text = "INSPECTION ONLY",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFE65100),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            } else {
                                // BT Status Chip
                                BtStatusChip(
                                    state = btState,
                                    deviceName = btDeviceName,
                                    onClick = if (btState != BtState.CONNECTED) onConnectBt else onDisconnectBt
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFFE0E0E0))
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Enter or Scan Asset Number",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextDark
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = searchInput,
                        onValueChange = { onSearchInputChange(it.uppercase()) },
                        label = { Text("Asset Number, S/N or Barcode") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                val query = searchInput.trim()
                                if (query.isNotEmpty()) {
                                    onSearchSubmitted(query)
                                }
                            }
                        ),
                        trailingIcon = {
                            if (searchInput.isNotEmpty()) {
                                IconButton(onClick = { onSearchInputChange("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = Color.Gray
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryBlue,
                            focusedLabelColor = PrimaryBlue,
                            unfocusedBorderColor = Color.LightGray
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { keyEvent ->
                                val isEnter = keyEvent.key == Key.Enter ||
                                              keyEvent.key == Key.NumPadEnter ||
                                              keyEvent.key == Key.DirectionCenter
                                if (isEnter && keyEvent.type == KeyEventType.KeyDown) {
                                    val query = searchInput.trim()
                                    if (query.isNotEmpty()) {
                                        onSearchSubmitted(query)
                                    }
                                    true
                                } else {
                                    false
                                }
                            }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val query = searchInput.trim()
                            if (query.isNotEmpty()) {
                                onSearchSubmitted(query)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryBlue,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Search", style = MaterialTheme.typography.labelLarge)
                    }

                    if (lastScannedAssetNumber.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFF8F9FA),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Last Scanned: ",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                                Text(
                                    text = lastScannedAssetNumber,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryBlue
                                )
                                val stickerColor = when (lastScannedRoutinePeriod) {
                                    12 -> Color(0xFFFDD835) // Yellow
                                    24 -> Color(0xFFFFFFFF) // White
                                    60 -> Color(0xFF66BB6A) // Green
                                    else -> null
                                }
                                if (stickerColor != null) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(stickerColor)
                                            .then(
                                                if (lastScannedRoutinePeriod == 24)
                                                    Modifier.border(1.dp, Color(0xFFBDBDBD), CircleShape)
                                                else Modifier
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // ══════════════════════════════════════════════════════════════
                // TAB 2 - LOG (Terminal Dark Background)
                // ══════════════════════════════════════════════════════════════
                var showAllScans by remember { mutableStateOf(true) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TerminalBackground)
                ) {
                    val allScansCount = scanLog.count { !it.isSessionHeader }
                    val myScansCount = scanLog.count { !it.isSessionHeader && matchesUser(it.userInitials, userInitials) }

                    val scannedAssets = if (showAllScans) {
                        scanLog.filter { !it.isSessionHeader }
                    } else {
                        scanLog.filter { !it.isSessionHeader && matchesUser(it.userInitials, userInitials) }
                    }

                    // Strict reverse-chronological ordering: newest scans appear at the top regardless of NEW or FOUND status.
                    val displayAssets = remember(scannedAssets) {
                        scannedAssets.withIndex().sortedWith { a, b ->
                            val timeComp = CsvManager.normalizeTimestamp(b.value.timestamp)
                                .compareTo(CsvManager.normalizeTimestamp(a.value.timestamp))
                            if (timeComp != 0) timeComp else b.index.compareTo(a.index)
                        }.map { it.value }
                    }

                    // Terminal Header Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TerminalHeaderBackground)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Chip: ALL
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (showAllScans) Color(0xFF1E2830) else Color.Transparent,
                                border = if (showAllScans) BorderStroke(1.dp, TerminalGreenBright) else BorderStroke(1.dp, Color(0xFF37474F)),
                                modifier = Modifier.clickable { showAllScans = true }
                            ) {
                                Text(
                                    text = "ALL ($allScansCount)",
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (showAllScans) TerminalGreenBright else TerminalGreenSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            // Chip: MY SCANS
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (!showAllScans) Color(0xFF1E2830) else Color.Transparent,
                                border = if (!showAllScans) BorderStroke(1.dp, TerminalGreenBright) else BorderStroke(1.dp, Color(0xFF37474F)),
                                modifier = Modifier.clickable { showAllScans = false }
                            ) {
                                Text(
                                    text = "MY SCANS ($myScansCount)",
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (!showAllScans) TerminalGreenBright else TerminalGreenSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isIoSession) Color(0xFFE65100) else PrimaryBlue
                        ) {
                            Text(
                                text = if (isIoSession) "IO SESSION" else "QA SESSION",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = TerminalSelectionBackground)

                    // Column Header aligned pixel-for-pixel with ScanLogCard
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TerminalHeaderBackground)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TIME",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TerminalGreenSecondary,
                            modifier = Modifier.width(54.dp)
                        )
                        Text(
                            text = "ASSET NUMBER",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TerminalGreenSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "STATUS",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TerminalGreenSecondary
                        )
                    }

                    HorizontalDivider(color = TerminalSelectionBackground)

                    // Scan Log List / Empty state
                    if (displayAssets.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            val emptyText = if (!showAllScans && allScansCount > 0) {
                                "No scans under '$userInitials' today.\n\n$allScansCount scan(s) recorded by others.\nTap 'ALL' above to view."
                            } else {
                                "No scans recorded today.\n\nSwitch to the SCAN tab to begin."
                            }
                            Text(
                                text = emptyText,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = TerminalGreenSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f)
                        ) {
                            itemsIndexed(displayAssets) { index, asset ->
                                ScanLogCard(
                                    asset = asset,
                                    fontSizeSp = fontSizeSp,
                                    isEven = index % 2 == 0
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = TerminalSelectionBackground)

                    // Terminal Footer Path
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TerminalHeaderBackground)
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "Output: ${outputPath.takeLast(40)}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                            fontSize = 10.sp,
                            color = TerminalGreenBright
                        )
                    }
                }
            }
        }
    }
}
