package me.rxue.mhceqa.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rxue.mhceqa.ScanLogAdapter
import me.rxue.mhceqa.ui.theme.PrimaryBlue
import me.rxue.mhceqa.ui.theme.TextDark
import me.rxue.mhceqa.ui.theme.TextSubtle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initialScannerName: String,
    currentFolderPath: String,
    currentSourceFileName: String = "",
    initialFontSize: Float,
    initialSnackbarDurationMs: Int,
    initialWebhookUrl: String = "",
    initialFolderId: String = "",
    initialAutoSync: Boolean = true,
    initialSourceFolderId: String = me.rxue.mhceqa.DriveSyncManager.DEFAULT_SOURCE_FOLDER_ID,
    initialSourceAutoUpdate: Boolean = false,
    sourceLastCheckTime: String = "Never",
    onCheckSourceUpdate: (folderId: String, onComplete: (String) -> Unit) -> Unit = { _, _ -> },
    onPickSourceCsv: () -> Unit = {},
    onChangeFolder: () -> Unit,
    onSaveSettings: (
        scannerName: String,
        fontSize: Float,
        snackbarDurationMs: Int,
        webhookUrl: String,
        folderId: String,
        autoSync: Boolean,
        sourceFolderId: String,
        sourceAutoUpdate: Boolean
    ) -> Unit,
    onBack: () -> Unit
) {
    var scannerName by remember { mutableStateOf(initialScannerName) }
    var fontSize by remember { mutableFloatStateOf(initialFontSize) }
    var snackbarSecs by remember { mutableFloatStateOf(initialSnackbarDurationMs / 1000f) }
    var webhookUrl by remember { mutableStateOf(initialWebhookUrl) }
    var folderId by remember { mutableStateOf(initialFolderId) }
    var autoSync by remember { mutableStateOf(initialAutoSync) }
    var sourceFolderId by remember { mutableStateOf(initialSourceFolderId) }
    var sourceAutoUpdate by remember { mutableStateOf(initialSourceAutoUpdate) }
    var isCheckingSource by remember { mutableStateOf(false) }
    var sourceCheckResult by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            onSaveSettings(
                                scannerName.trim(),
                                fontSize,
                                (snackbarSecs * 1000).toInt(),
                                webhookUrl.trim(),
                                folderId.trim(),
                                autoSync,
                                sourceFolderId.trim(),
                                sourceAutoUpdate
                            )
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = "Save", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryBlue
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Scanner Number
            Text("Scanner Number", style = MaterialTheme.typography.labelLarge, color = TextDark)
            Text("Identifies this unit in CSV output filenames", style = MaterialTheme.typography.bodyMedium, color = TextSubtle)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = scannerName,
                onValueChange = { input ->
                    if (input.all { it.isDigit() }) {
                        scannerName = input
                    }
                },
                label = { Text("Scanner Number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    focusedLabelColor = PrimaryBlue
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = Color(0xFFE0E0E0))
            Spacer(modifier = Modifier.height(20.dp))

            // CSV Files & Storage Consolidated Section
            Text("CSV Files & Storage", style = MaterialTheme.typography.labelLarge, color = TextDark)
            Spacer(modifier = Modifier.height(10.dp))

            // Master Asset Database (Source CSV)
            Text("Master Asset Database (Source CSV)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextDark)
            Spacer(modifier = Modifier.height(2.dp))
            Text(currentSourceFileName.ifBlank { "Not selected" }, style = MaterialTheme.typography.bodyMedium, color = PrimaryBlue)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onPickSourceCsv,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = null, tint = PrimaryBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Load / Change Source CSV", color = PrimaryBlue)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Daily Output Folder
            Text("Daily Output CSV Storage", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextDark)
            Spacer(modifier = Modifier.height(2.dp))
            Text(currentFolderPath, style = MaterialTheme.typography.bodyMedium, color = PrimaryBlue)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onChangeFolder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = null, tint = PrimaryBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Change Output Folder", color = PrimaryBlue)
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = Color(0xFFE0E0E0))
            Spacer(modifier = Modifier.height(20.dp))

            // Log Display Font Size
            Text("Log Text Size", style = MaterialTheme.typography.labelLarge, color = TextDark)
            Spacer(modifier = Modifier.height(8.dp))

            val options = listOf(
                "Small (12sp)" to ScanLogAdapter.FONT_SIZE_SMALL,
                "Medium (14sp)" to ScanLogAdapter.FONT_SIZE_MEDIUM,
                "Large (16sp)" to ScanLogAdapter.FONT_SIZE_LARGE
            )

            options.forEach { (label, value) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    RadioButton(
                        selected = fontSize == value,
                        onClick = { fontSize = value },
                        colors = RadioButtonDefaults.colors(selectedColor = PrimaryBlue)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = TextDark)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = Color(0xFFE0E0E0))
            Spacer(modifier = Modifier.height(20.dp))

            // Snackbar Alert Duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Notification Alert Duration", style = MaterialTheme.typography.labelLarge, color = TextDark, modifier = Modifier.weight(1f))
                Text("${snackbarSecs.toInt()}s", style = MaterialTheme.typography.labelLarge, color = PrimaryBlue)
            }
            Spacer(modifier = Modifier.height(8.dp))

            Slider(
                value = snackbarSecs,
                onValueChange = { snackbarSecs = it },
                valueRange = 2f..10f,
                steps = 7,
                colors = SliderDefaults.colors(
                    thumbColor = PrimaryBlue,
                    activeTrackColor = PrimaryBlue
                )
            )

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = Color(0xFFE0E0E0))
            Spacer(modifier = Modifier.height(20.dp))

            // Google Drive Records Upload
            Text("Google Drive Records Upload", style = MaterialTheme.typography.labelLarge, color = TextDark)
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = webhookUrl,
                onValueChange = { webhookUrl = it },
                label = { Text("Web App Webhook URL") },
                placeholder = { Text("https://script.google.com/macros/s/.../exec") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    focusedLabelColor = PrimaryBlue
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = folderId,
                onValueChange = { folderId = it },
                label = { Text("Custom Folder ID (Optional)") },
                placeholder = { Text("Leave blank to use Scanner Number mapped folder") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    focusedLabelColor = PrimaryBlue
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Sync Session Files", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextDark)
                    Text("Sync on session end and auto-retry when online", style = MaterialTheme.typography.bodySmall, color = TextSubtle)
                }
                Switch(
                    checked = autoSync,
                    onCheckedChange = { autoSync = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PrimaryBlue
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = Color(0xFFE0E0E0))
            Spacer(modifier = Modifier.height(20.dp))

            // Source Asset List Cloud Sync
            Text("Source Asset List Cloud Sync", style = MaterialTheme.typography.labelLarge, color = TextDark)
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = sourceFolderId,
                onValueChange = { sourceFolderId = it },
                label = { Text("Source Asset Folder ID") },
                placeholder = { Text("1TM9jjPbC6bE8pJHxkM7w9o5Jq03pf02W") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    focusedLabelColor = PrimaryBlue
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Check for Updates", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextDark)
                    Text("Check on startup & sync (Last: $sourceLastCheckTime)", style = MaterialTheme.typography.bodySmall, color = TextSubtle)
                }
                Switch(
                    checked = sourceAutoUpdate,
                    onCheckedChange = { sourceAutoUpdate = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PrimaryBlue
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    isCheckingSource = true
                    sourceCheckResult = null
                    onCheckSourceUpdate(sourceFolderId) { msg ->
                        isCheckingSource = false
                        sourceCheckResult = msg
                    }
                },
                enabled = !isCheckingSource,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Text(if (isCheckingSource) "Checking Google Drive..." else "Check for Updates Now")
            }

            if (sourceCheckResult != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = sourceCheckResult!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sourceCheckResult!!.startsWith("✓")) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
