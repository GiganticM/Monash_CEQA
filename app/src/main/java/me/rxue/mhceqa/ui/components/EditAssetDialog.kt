package me.rxue.mhceqa.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rxue.mhceqa.BarcodeHelper
import me.rxue.mhceqa.models.Asset
import me.rxue.mhceqa.ui.theme.PrimaryBlue
import me.rxue.mhceqa.ui.theme.TextDark
import me.rxue.mhceqa.ui.theme.TextSubtle

private enum class EditAssetTargetField {
    ASSET_NUMBER, DEPARTMENT, MAKE, MODEL, SERIAL, NOTE
}

internal fun textFieldValueAtEnd(text: String): TextFieldValue =
    TextFieldValue(text = text, selection = TextRange(text.length))

internal fun cleanSingleLineTextFieldValue(value: TextFieldValue, uppercase: Boolean = false): TextFieldValue {
    val stripped = value.text.replace("\n", "").replace("\r", "")
    val finalText = if (uppercase) stripped.uppercase() else stripped
    val start = value.selection.start.coerceIn(0, finalText.length)
    val end = value.selection.end.coerceIn(0, finalText.length)
    return value.copy(text = finalText, selection = TextRange(start, end))
}

@Composable
fun EditAssetDialog(
    asset: Asset,
    isIoSession: Boolean,
    scannedBarcode: String? = null,
    onClearBarcode: () -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (Asset) -> Unit,
    onDelete: (Asset) -> Unit
) {
    var assetNumber by remember(asset) { mutableStateOf(textFieldValueAtEnd(asset.assetNumber)) }
    var assetNumberError by remember { mutableStateOf<String?>(null) }
    var department by remember(asset) { mutableStateOf(textFieldValueAtEnd(asset.department)) }
    var make by remember(asset) { mutableStateOf(textFieldValueAtEnd(asset.make)) }
    var model by remember(asset) { mutableStateOf(textFieldValueAtEnd(asset.model)) }
    var serial by remember(asset) { mutableStateOf(textFieldValueAtEnd(asset.serial)) }
    var selectedScore by remember(asset) { mutableStateOf(asset.conditionScore.ifEmpty { "+1" }) }
    var note by remember(asset) { mutableStateOf(textFieldValueAtEnd(asset.note)) }
    var activeField by remember { mutableStateOf(EditAssetTargetField.NOTE) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(scannedBarcode) {
        val raw = scannedBarcode?.trim() ?: return@LaunchedEffect
        if (raw.isNotEmpty()) {
            val decoded = BarcodeHelper.decodeBarcode(raw)
            val effectiveCode = decoded.code
            if (!asset.isNew) {
                val updatedNote = if (note.text.isBlank()) effectiveCode else "${note.text.trimEnd()} $effectiveCode"
                note = textFieldValueAtEnd(updatedNote)
            } else {
                when (activeField) {
                    EditAssetTargetField.ASSET_NUMBER -> {
                        assetNumber = textFieldValueAtEnd(effectiveCode.uppercase())
                        assetNumberError = null
                    }
                    EditAssetTargetField.DEPARTMENT -> department = textFieldValueAtEnd(effectiveCode.uppercase())
                    EditAssetTargetField.MAKE -> make = textFieldValueAtEnd(effectiveCode)
                    EditAssetTargetField.MODEL -> model = textFieldValueAtEnd(effectiveCode)
                    EditAssetTargetField.SERIAL -> serial = textFieldValueAtEnd(effectiveCode.uppercase())
                    EditAssetTargetField.NOTE -> {
                        val updatedNote = if (note.text.isBlank()) effectiveCode else "${note.text.trimEnd()} $effectiveCode"
                        note = textFieldValueAtEnd(updatedNote)
                    }
                }
            }
        }
        onClearBarcode()
    }

    val scoreOptions = listOf("-2", "-1", "0", "+1", "+2")
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    text = "Delete Asset Record?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Are you sure you want to delete '${asset.assetNumber}' from today's log and CSV file?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete(asset)
                    }
                ) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (asset.isNew) "Edit New Asset" else "Edit Asset Record",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (asset.isNew) {
                    // Full editable fields for NEW assets
                    OutlinedTextField(
                        value = assetNumber,
                        onValueChange = {
                            assetNumber = cleanSingleLineTextFieldValue(it, uppercase = true)
                            assetNumberError = null
                        },
                        label = { Text("Asset Number", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        maxLines = 1,
                        isError = assetNumberError != null,
                        supportingText = assetNumberError?.let { { Text(it) } },
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
                            .onFocusChanged { if (it.isFocused) activeField = EditAssetTargetField.ASSET_NUMBER }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = department,
                        onValueChange = { department = cleanSingleLineTextFieldValue(it, uppercase = true) },
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
                            .onFocusChanged { if (it.isFocused) activeField = EditAssetTargetField.DEPARTMENT }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = make,
                        onValueChange = { make = cleanSingleLineTextFieldValue(it) },
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
                            .onFocusChanged { if (it.isFocused) activeField = EditAssetTargetField.MAKE }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = cleanSingleLineTextFieldValue(it) },
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
                            .onFocusChanged { if (it.isFocused) activeField = EditAssetTargetField.MODEL }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = serial,
                        onValueChange = { serial = cleanSingleLineTextFieldValue(it, uppercase = true) },
                        label = { Text("Serial Number", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
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
                            .onFocusChanged { if (it.isFocused) activeField = EditAssetTargetField.SERIAL }
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                } else {
                    // Header info for FOUND assets
                    Text(
                        text = "Asset: ${asset.assetNumber}",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDark
                    )
                    if (asset.department.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Department: ${asset.department}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSubtle
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Condition Score selector (IO session only)
                if (isIoSession) {
                    Text(
                        text = "Condition Score:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtle
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        scoreOptions.forEach { score ->
                            val isSelected = selectedScore == score
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clickable { selectedScore = score },
                                shape = RoundedCornerShape(8.dp),
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

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFE3F2FD),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Rating $selectedScore: ${getConditionScoreDescription(selectedScore)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Note input (multi-line wrapping with ImeAction.Done retracting keyboard)
                OutlinedTextField(
                    value = note,
                    onValueChange = { newValue ->
                        if (newValue.text.contains('\n')) {
                            val cleaned = newValue.text.replace("\n", " ").replace("\r", "")
                            note = textFieldValueAtEnd(cleaned)
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        } else {
                            note = newValue
                        }
                    },
                    label = {
                        Text(
                            text = "Note (Optional)",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    placeholder = { Text("Add any notes about this asset") },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    trailingIcon = {
                        if (note.text.isNotEmpty()) {
                            IconButton(onClick = { note = TextFieldValue("") }) {
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
                        .onFocusChanged { if (it.isFocused) activeField = EditAssetTargetField.NOTE }
                )

                // Breakdown list for FOUND IO assets (matching ConditionScoreDialog convenience)
                if (isIoSession && !asset.isNew) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CONDITION_SCORE_DESCRIPTIONS.forEach { (score, desc) ->
                            val isCurrent = selectedScore == score
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
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = {
                            if (asset.isNew) {
                                val trimmedAsset = assetNumber.text.trim().uppercase()
                                if (trimmedAsset.isEmpty()) {
                                    assetNumberError = "Asset Number cannot be empty"
                                    return@TextButton
                                }
                                onSave(
                                    asset.copy(
                                        assetNumber = trimmedAsset,
                                        department = department.text.trim().uppercase(),
                                        make = make.text.trim(),
                                        model = model.text.trim(),
                                        serial = serial.text.trim().uppercase(),
                                        conditionScore = if (isIoSession) selectedScore else asset.conditionScore,
                                        note = note.text.trim()
                                    )
                                )
                            } else {
                                onSave(
                                    asset.copy(
                                        conditionScore = if (isIoSession) selectedScore else asset.conditionScore,
                                        note = note.text.trim()
                                    )
                                )
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Save", color = PrimaryBlue, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    )
}
