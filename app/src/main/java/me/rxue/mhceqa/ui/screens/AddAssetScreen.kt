package me.rxue.mhceqa.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rxue.mhceqa.BarcodeHelper
import me.rxue.mhceqa.ui.components.CONDITION_SCORE_DESCRIPTIONS
import me.rxue.mhceqa.ui.components.getConditionScoreDescription
import me.rxue.mhceqa.ui.theme.PrimaryBlue
import me.rxue.mhceqa.ui.theme.TextDark
import me.rxue.mhceqa.ui.theme.TextSubtle

enum class AddAssetTargetField(val label: String) {
    ASSET_NUMBER("Asset Number"),
    DEPARTMENT("Department"),
    MAKE("Make"),
    MODEL("Model"),
    SERIAL("Serial Number"),
    NOTE("Note")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAssetScreen(
    assetNumber: String,
    initialSerial: String = "",
    isIo: Boolean = false,
    scannedBarcode: String? = null,
    onClearBarcode: () -> Unit = {},
    onSave: (assetNum: String, dept: String, make: String, model: String, serial: String, conditionScore: String, note: String) -> Unit,
    onCancel: () -> Unit
) {
    var editableAssetNumber by remember(assetNumber) { mutableStateOf(assetNumber) }
    var assetNumberError by remember { mutableStateOf<String?>(null) }
    var department by remember { mutableStateOf("") }
    var make by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var serial by remember(initialSerial) { mutableStateOf(initialSerial) }
    var conditionScore by remember { mutableStateOf("+1") }
    var note by remember { mutableStateOf("") }

    var activeField by remember { mutableStateOf(AddAssetTargetField.SERIAL) }
    var lastScannedMsg by remember { mutableStateOf<String?>(null) }

    var showDiscardDialog by remember { mutableStateOf(false) }
    var missingFieldsToConfirm by remember { mutableStateOf<List<String>?>(null) }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // When hardware scanner broadcast arrives, fill the active target field (defaults to SERIAL)
    LaunchedEffect(scannedBarcode) {
        val raw = scannedBarcode?.trim() ?: return@LaunchedEffect
        val decoded = BarcodeHelper.decodeBarcode(raw)
        val effectiveCode = decoded.code
        when (activeField) {
            AddAssetTargetField.ASSET_NUMBER -> {
                editableAssetNumber = effectiveCode.uppercase()
                assetNumberError = null
                if (serial.isBlank() && !decoded.extractedSerial.isNullOrBlank()) {
                    serial = decoded.extractedSerial
                }
            }
            AddAssetTargetField.DEPARTMENT -> department = effectiveCode.uppercase()
            AddAssetTargetField.MAKE -> make = effectiveCode
            AddAssetTargetField.MODEL -> model = effectiveCode
            AddAssetTargetField.SERIAL -> serial = effectiveCode.uppercase()
            AddAssetTargetField.NOTE -> note = if (note.isBlank()) effectiveCode else "$note $effectiveCode"
        }
        lastScannedMsg = "Scanned into ${activeField.label}: $effectiveCode"
        onClearBarcode()
    }

    fun hasInput() =
        editableAssetNumber.trim() != assetNumber.trim() ||
        department.isNotBlank() ||
        make.isNotBlank() ||
        model.isNotBlank() ||
        (serial.isNotBlank() && serial != initialSerial) ||
        note.isNotBlank()

    BackHandler {
        if (hasInput()) showDiscardDialog = true else onCancel()
    }

    fun attemptSave() {
        val finalAssetNum = editableAssetNumber.trim().uppercase()
        if (finalAssetNum.isEmpty()) {
            assetNumberError = "Asset Number cannot be empty"
            return
        }

        val missing = mutableListOf<String>()
        if (department.isBlank()) missing.add("Department")
        if (make.isBlank()) missing.add("Make")
        if (model.isBlank()) missing.add("Model")
        if (serial.isBlank()) missing.add("Serial Number")

        if (missing.isEmpty()) {
            onSave(finalAssetNum, department.trim(), make.trim(), model.trim(), serial.trim(), if (isIo) conditionScore else "", note.trim())
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
                text = "Not found in database. Hardware scanner will populate the focused field (defaults to S/N).",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSubtle
            )

            Spacer(modifier = Modifier.height(12.dp))

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

            // Editable Asset Number
            OutlinedTextField(
                value = editableAssetNumber,
                onValueChange = {
                    editableAssetNumber = it.replace("\n", "").replace("\r", "").uppercase()
                    assetNumberError = null
                },
                label = {
                    Text(
                        text = "Asset Number (or S/N if unlabeled)",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                singleLine = true,
                maxLines = 1,
                isError = assetNumberError != null,
                supportingText = assetNumberError?.let { { Text(it) } },
                trailingIcon = {
                    if (editableAssetNumber.isNotEmpty()) {
                        IconButton(onClick = { editableAssetNumber = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = Color.Gray
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Next
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    focusedLabelColor = PrimaryBlue
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) activeField = AddAssetTargetField.ASSET_NUMBER }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Department
            OutlinedTextField(
                value = department,
                onValueChange = { department = it.replace("\n", "").replace("\r", "").uppercase() },
                label = { Text("Department", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                maxLines = 1,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Next
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
                onValueChange = { make = it.replace("\n", "").replace("\r", "") },
                label = { Text("Make", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                maxLines = 1,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
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
                onValueChange = { model = it.replace("\n", "").replace("\r", "") },
                label = { Text("Model", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                maxLines = 1,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
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
                onValueChange = { serial = it.replace("\n", "").replace("\r", "").uppercase() },
                label = { Text("Serial Number", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                placeholder = { Text("Scan barcode or enter S/N", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                maxLines = 1,
                trailingIcon = {
                    if (serial.isNotEmpty()) {
                        IconButton(onClick = { serial = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = Color.Gray
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Next
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    focusedLabelColor = PrimaryBlue
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) activeField = AddAssetTargetField.SERIAL }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Note (both QA and IO sessions) - wraps multiple lines while keeping ImeAction.Done to retract keyboard
            OutlinedTextField(
                value = note,
                onValueChange = { newValue ->
                    if (newValue.contains('\n')) {
                        note = newValue.replace("\n", " ").replace("\r", "")
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    } else {
                        note = newValue
                    }
                },
                label = { Text("Note (Optional)", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                placeholder = { Text("Enter any additional notes about this asset") },
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                trailingIcon = {
                    if (note.isNotEmpty()) {
                        IconButton(onClick = { note = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = Color.Gray
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    focusedLabelColor = PrimaryBlue
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) activeField = AddAssetTargetField.NOTE }
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
                    text = "Select condition score:",
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

                Spacer(modifier = Modifier.height(10.dp))

                // Active description highlight
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFE3F2FD),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Rating $conditionScore: ${getConditionScoreDescription(conditionScore)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryBlue,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Description breakdown list
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF8FAFC), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    CONDITION_SCORE_DESCRIPTIONS.forEach { (score, desc) ->
                        val isCurrent = conditionScore == score
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$score :",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                color = if (isCurrent) PrimaryBlue else TextDark
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) PrimaryBlue else TextSubtle
                            )
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
                        onSave(
                            editableAssetNumber.trim().uppercase(),
                            department.trim(),
                            make.trim(),
                            model.trim(),
                            serial.trim(),
                            if (isIo) conditionScore else "",
                            note.trim()
                        )
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
