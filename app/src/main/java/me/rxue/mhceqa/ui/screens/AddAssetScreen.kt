package me.rxue.mhceqa.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rxue.mhceqa.ui.theme.PrimaryBlue
import me.rxue.mhceqa.ui.theme.TextDark
import me.rxue.mhceqa.ui.theme.TextSubtle

enum class AddAssetTargetField(val label: String) {
    DEPARTMENT("Department"),
    MAKE("Make"),
    MODEL("Model"),
    SERIAL("Serial Number")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAssetScreen(
    assetNumber: String,
    isIo: Boolean = false,
    scannedBarcode: String? = null,
    onClearBarcode: () -> Unit = {},
    onSave: (dept: String, make: String, model: String, serial: String, conditionScore: String) -> Unit,
    onCancel: () -> Unit
) {
    var department by remember { mutableStateOf("") }
    var make by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("") }
    var conditionScore by remember { mutableStateOf("0") }

    var activeField by remember { mutableStateOf(AddAssetTargetField.SERIAL) }
    var lastScannedMsg by remember { mutableStateOf<String?>(null) }

    var showDiscardDialog by remember { mutableStateOf(false) }
    var missingFieldsToConfirm by remember { mutableStateOf<List<String>?>(null) }

    // When hardware scanner broadcast arrives, fill the active target field (defaults to SERIAL)
    LaunchedEffect(scannedBarcode) {
        val code = scannedBarcode?.trim() ?: return@LaunchedEffect
        when (activeField) {
            AddAssetTargetField.DEPARTMENT -> department = code.uppercase()
            AddAssetTargetField.MAKE -> make = code
            AddAssetTargetField.MODEL -> model = code
            AddAssetTargetField.SERIAL -> serial = code.uppercase()
        }
        lastScannedMsg = "Scanned into ${activeField.label}: $code"
        onClearBarcode()
    }

    fun hasInput() = department.isNotBlank() || make.isNotBlank() || model.isNotBlank() || serial.isNotBlank()

    fun attemptSave() {
        val missing = mutableListOf<String>()
        if (department.isBlank()) missing.add("Department")
        if (make.isBlank()) missing.add("Make")
        if (model.isBlank()) missing.add("Model")
        if (serial.isBlank()) missing.add("Serial Number")

        if (missing.isEmpty()) {
            onSave(department.trim(), make.trim(), model.trim(), serial.trim(), if (isIo) conditionScore else "")
        } else {
            missingFieldsToConfirm = missing
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add New Asset", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (hasInput()) showDiscardDialog = true else onCancel()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
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
            Text(
                text = "New Asset: $assetNumber",
                style = MaterialTheme.typography.titleMedium,
                color = TextDark
            )
            Text(
                text = "Not found in database. Hardware scanner will populate the focused field (defaults to S/N).",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSubtle
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Scan feedback banner
            if (lastScannedMsg != null) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFFE8F5E9),
                    border = BorderStroke(1.dp, Color(0xFF81C784)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✓ $lastScannedMsg",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF2E7D32),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { lastScannedMsg = null },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Department
            OutlinedTextField(
                value = department,
                onValueChange = { department = it.uppercase() },
                label = { Text("Department") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    focusedLabelColor = PrimaryBlue
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) activeField = AddAssetTargetField.DEPARTMENT }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Make
            OutlinedTextField(
                value = make,
                onValueChange = { make = it },
                label = { Text("Make") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    focusedLabelColor = PrimaryBlue
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) activeField = AddAssetTargetField.MAKE }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Model
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    focusedLabelColor = PrimaryBlue
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) activeField = AddAssetTargetField.MODEL }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Serial Number
            OutlinedTextField(
                value = serial,
                onValueChange = { serial = it.uppercase() },
                label = { Text("Serial Number") },
                placeholder = { Text("Scan barcode or enter S/N") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    focusedLabelColor = PrimaryBlue
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) activeField = AddAssetTargetField.SERIAL }
            )

            if (isIo) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Asset Condition Score",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )
                Text(
                    text = "Select condition rating for inspection:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtle,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                val scoreOptions = listOf("-2", "-1", "0", "+1", "+2")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    scoreOptions.forEach { score ->
                        val isSelected = conditionScore == score
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clickable { conditionScore = score },
                            shape = RoundedCornerShape(4.dp),
                            color = if (isSelected) PrimaryBlue else Color(0xFFF1F5F9),
                            border = BorderStroke(
                                1.5.dp,
                                if (isSelected) PrimaryBlue else Color(0xFFCBD5E1)
                            )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = score,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else TextDark
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { attemptSave() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryBlue,
                    contentColor = Color.White
                )
            ) {
                Text("Save Asset", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard New Asset?") },
            text = { Text("You haven't finished adding this new asset.\nAre you sure you want to go back without saving?") },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onCancel() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep Editing")
                }
            }
        )
    }

    missingFieldsToConfirm?.let { missing ->
        val fieldList = missing.joinToString("\n") { "  • $it" }
        val msg = if (missing.size == 1)
            "The following field has not been filled in:\n$fieldList\n\nDo you want to save anyway?"
        else
            "The following ${missing.size} fields have not been filled in:\n$fieldList\n\nDo you want to save anyway?"

        AlertDialog(
            onDismissRequest = { missingFieldsToConfirm = null },
            title = { Text("Incomplete Information") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(
                    onClick = {
                        missingFieldsToConfirm = null
                        onSave(department.trim(), make.trim(), model.trim(), serial.trim(), if (isIo) conditionScore else "")
                    }
                ) {
                    Text("Save Anyway", color = PrimaryBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = { missingFieldsToConfirm = null }) {
                    Text("Go Back")
                }
            }
        )
    }
}
