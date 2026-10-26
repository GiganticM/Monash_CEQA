package me.rxue.mhceqa.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rxue.mhceqa.models.Asset
import me.rxue.mhceqa.ui.theme.BadgeFoundColor
import me.rxue.mhceqa.ui.theme.BadgeNewColor
import me.rxue.mhceqa.ui.theme.JetBrainsMonoFontFamily
import me.rxue.mhceqa.ui.theme.TerminalGreenPrimary
import me.rxue.mhceqa.ui.theme.TerminalGreenSecondary
import me.rxue.mhceqa.ui.theme.TerminalGreenSubtle

@Composable
fun ScanLogCard(
    asset: Asset,
    fontSizeSp: Float = 13f,
    isEven: Boolean = false,
    modifier: Modifier = Modifier
) {
    var isExpanded by rememberSaveable(asset.assetNumber, asset.timestamp) { mutableStateOf(false) }

    val rowBackgroundColor = when {
        asset.isNew -> Color(0x24FF5252) // Distinct warm reddish/amber tint for NEW
        asset.isSessionHeader -> Color(0x1E80CBC4) // Subtle teal tint for SESSION
        isEven -> Color(0x0C00E676) // Alternating soft green tint for zebra striping
        else -> Color.Transparent
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBackgroundColor)
            .then(
                if (asset.isNew) {
                    Modifier.clickable { isExpanded = !isExpanded }
                } else Modifier
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
            // [HH:MM]
            val displayTime = if (asset.timestamp.length > 5 && asset.timestamp.contains(":")) {
                asset.timestamp.take(5)
            } else {
                asset.timestamp
            }
            Text(
                text = displayTime,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = (fontSizeSp - 1).coerceAtLeast(10f).sp,
                color = TerminalGreenSecondary,
                modifier = Modifier.width(54.dp)
            )

            // Asset Number
            Text(
                text = asset.assetNumber,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.Bold,
                color = TerminalGreenPrimary,
                modifier = Modifier.weight(1f)
            )

            // User initials badge
            if (asset.userInitials.isNotBlank()) {
                Text(
                    text = asset.userInitials,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = (fontSizeSp - 2).coerceAtLeast(10f).sp,
                    color = Color(0xFF80CBC4),
                    modifier = Modifier.padding(end = 6.dp)
                )
            }

            // Sticker color dot (12 / 24 / 60 mth routine period)
            val stickerColor = when (asset.routinePeriod) {
                12 -> Color(0xFFFDD835) // Yellow
                24 -> Color(0xFFF5F5F5) // White
                60 -> Color(0xFF66BB6A) // Green
                else -> null
            }

            if (stickerColor != null) {
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(stickerColor)
                )
            }

            // Type Badge (FOUND / NEW / SESSION)
            val (badgeText, badgeColor) = when {
                asset.isSessionHeader -> "SESSION" to TerminalGreenSecondary
                asset.isNew -> "NEW" to BadgeNewColor
                else -> "FOUND" to BadgeFoundColor
            }

            Text(
                text = badgeText,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = (fontSizeSp - 2).coerceAtLeast(10f).sp,
                fontWeight = FontWeight.Bold,
                color = badgeColor,
                modifier = Modifier.padding(start = 4.dp)
            )

            // Interactive expand/collapse indicator for NEW assets
            if (asset.isNew) {
                Text(
                    text = if (isExpanded) "▲" else "▼",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = (fontSizeSp - 3).coerceAtLeast(9f).sp,
                    fontWeight = FontWeight.Bold,
                    color = BadgeNewColor,
                    modifier = Modifier.padding(start = 3.dp)
                )
            }
        }

        // 4 distinct rows for NEW assets (dept, make, model and S/N) - collapsible
        if (asset.isNew) {
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 54.dp, top = 3.dp, bottom = 2.dp)
                ) {
                    val detailFontSize = (fontSizeSp - 2).coerceAtLeast(10f).sp

                    // Row 1: Dept
                    if (asset.department.isNotBlank()) {
                        Row(modifier = Modifier.padding(vertical = 1.dp)) {
                            Text(
                                text = "Dept:  ",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = detailFontSize,
                                fontWeight = FontWeight.Medium,
                                color = TerminalGreenSubtle
                            )
                            Text(
                                text = asset.department,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = detailFontSize,
                                color = TerminalGreenSecondary
                            )
                        }
                    }

                    // Row 2: Make
                    if (asset.make.isNotBlank()) {
                        Row(modifier = Modifier.padding(vertical = 1.dp)) {
                            Text(
                                text = "Make:  ",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = detailFontSize,
                                fontWeight = FontWeight.Medium,
                                color = TerminalGreenSubtle
                            )
                            Text(
                                text = asset.make,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = detailFontSize,
                                color = TerminalGreenSecondary
                            )
                        }
                    }

                    // Row 3: Model
                    if (asset.model.isNotBlank()) {
                        Row(modifier = Modifier.padding(vertical = 1.dp)) {
                            Text(
                                text = "Model: ",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = detailFontSize,
                                fontWeight = FontWeight.Medium,
                                color = TerminalGreenSubtle
                            )
                            Text(
                                text = asset.model,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = detailFontSize,
                                color = TerminalGreenSecondary
                            )
                        }
                    }

                    // Row 4: S/N
                    if (asset.serial.isNotBlank()) {
                        Row(modifier = Modifier.padding(vertical = 1.dp)) {
                            Text(
                                text = "S/N:   ",
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = detailFontSize,
                                fontWeight = FontWeight.Medium,
                                color = TerminalGreenSubtle
                            )
                            Text(
                                text = asset.serial,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = detailFontSize,
                                color = TerminalGreenSecondary
                            )
                        }
                    }
                }
            }
        }
    }
    HorizontalDivider(color = Color(0x12FFFFFF), thickness = 0.5.dp)
}
}
