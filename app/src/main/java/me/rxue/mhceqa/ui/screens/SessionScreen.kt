package me.rxue.mhceqa.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rxue.mhceqa.R
import me.rxue.mhceqa.SessionActivity
import me.rxue.mhceqa.ui.theme.PrimaryBlue
import me.rxue.mhceqa.ui.theme.TextDark
import me.rxue.mhceqa.ui.theme.TextSubtle

@Composable
fun SessionScreen(
    onStartSession: (initials: String, location: String, sessionType: String) -> Unit,
    onOpenSettings: () -> Unit
) {
    var initials by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var sessionType by remember { mutableStateOf(SessionActivity.SESSION_TYPE_QA) }
    var initialsError by remember { mutableStateOf<String?>(null) }
    var showEmptyLocationDialog by remember { mutableStateOf(false) }
    var showIoConfirmDialog by remember { mutableStateOf(false) }

    val activeColor = if (sessionType == SessionActivity.SESSION_TYPE_IO) Color(0xFFE65100) else PrimaryBlue

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(end = 12.dp, top = 8.dp)
            ) {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color(0xFF757575),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        },
        containerColor = Color(0xFFF8F9FA)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Official Logo with high-quality bicubic filtering
            val logoBitmap = ImageBitmap.imageResource(id = R.drawable.monash_health_logo)
            Image(
                painter = remember(logoBitmap) {
                    BitmapPainter(logoBitmap, filterQuality = FilterQuality.High)
                },
                contentDescription = "Logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(78.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "MH CEQA",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF221C58),
                letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 18.dp)
            )

            // Elevated Login Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shadowElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // Session Mode Selector Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { sessionType = SessionActivity.SESSION_TYPE_QA },
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (sessionType == SessionActivity.SESSION_TYPE_QA) PrimaryBlue else Color(0xFFF1F5F9),
                                contentColor = if (sessionType == SessionActivity.SESSION_TYPE_QA) Color.White else TextDark
                            )
                        ) {
                            Text(
                                text = "QA Session",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        Button(
                            onClick = { sessionType = SessionActivity.SESSION_TYPE_IO },
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (sessionType == SessionActivity.SESSION_TYPE_IO) Color(0xFFE65100) else Color(0xFFF1F5F9),
                                contentColor = if (sessionType == SessionActivity.SESSION_TYPE_IO) Color.White else TextDark
                            )
                        ) {
                            Text(
                                text = "IO Session",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    // User Initials
                    OutlinedTextField(
                        value = initials,
                        onValueChange = { input ->
                            if (input.length <= 15) {
                                initials = input.uppercase()
                                initialsError = null
                            }
                        },
                        label = { Text("Your Initials") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Initials",
                                tint = activeColor
                            )
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters
                        ),
                        isError = initialsError != null,
                        supportingText = initialsError?.let { { Text(it) } },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = activeColor,
                            focusedLabelColor = activeColor,
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Location
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it.uppercase() },
                        label = { Text("Location (e.g. MHCE)", maxLines = 1, softWrap = false) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = "Location",
                                tint = activeColor
                            )
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = activeColor,
                            focusedLabelColor = activeColor,
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Start Session Button
                    Button(
                        onClick = {
                            val trimmedInitials = initials.trim()
                            val trimmedLoc = location.trim().uppercase()
                            if (trimmedInitials.isEmpty()) {
                                initialsError = "Please enter your initials"
                                return@Button
                            }
                            if (trimmedLoc.isEmpty()) {
                                showEmptyLocationDialog = true
                            } else if (sessionType == SessionActivity.SESSION_TYPE_IO) {
                                showIoConfirmDialog = true
                            } else {
                                onStartSession(trimmedInitials, trimmedLoc, sessionType)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = activeColor,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = if (sessionType == SessionActivity.SESSION_TYPE_IO) "START IO SESSION" else "START QA SESSION",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // Dialog for empty location confirmation
    if (showEmptyLocationDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyLocationDialog = false },
            title = { Text("Location Empty") },
            text = { Text("No location was entered. Are you sure you want to proceed without setting a location?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEmptyLocationDialog = false
                        if (sessionType == SessionActivity.SESSION_TYPE_IO) {
                            showIoConfirmDialog = true
                        } else {
                            onStartSession(initials.trim(), "", sessionType)
                        }
                    }
                ) {
                    Text("Proceed", color = activeColor, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyLocationDialog = false }) {
                    Text("Add Location")
                }
            }
        )
    }

    // Dialog for IO session confirmation
    if (showIoConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showIoConfirmDialog = false },
            title = { Text("Inspection Only (IO) Mode") },
            text = { Text("You selected IO (Inspection Only) Session.\nIn this mode, Bluetooth connection is disabled.\n\nAre you sure you want to proceed?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showIoConfirmDialog = false
                        onStartSession(initials.trim(), location.trim().uppercase(), sessionType)
                    }
                ) {
                    Text("Confirm IO Mode", color = Color(0xFFE65100), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showIoConfirmDialog = false }) {
                    Text("Back to QA")
                }
            }
        )
    }
}
