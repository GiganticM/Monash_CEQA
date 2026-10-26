package me.rxue.mhceqa.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rxue.mhceqa.ui.theme.PrimaryBlue
import me.rxue.mhceqa.ui.theme.TextDark
import me.rxue.mhceqa.ui.theme.TextSubtle

@Composable
fun ConditionScoreDialog(
    assetNumber: String,
    initialScore: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var selectedScore by remember(assetNumber, initialScore) {
        mutableStateOf(initialScore.ifEmpty { "0" })
    }
    val scoreOptions = listOf("-2", "-1", "0", "+1", "+2")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Asset Condition Score",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = TextDark
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Asset: $assetNumber",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDark
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Select condition score:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtle
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    scoreOptions.forEach { score ->
                        val isSelected = selectedScore == score
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clickable { selectedScore = score },
                            shape = RoundedCornerShape(6.dp),
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
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedScore) },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("Save Score", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSubtle)
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(12.dp)
    )
}
