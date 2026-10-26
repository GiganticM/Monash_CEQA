package me.rxue.mhceqa.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class CustomSnackbarVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Indefinite,
    val containerColor: Color = Color(0xFF323232),
    val contentColor: Color = Color.White,
    val stickerText: String? = null,
    val stickerColor: Color? = null,
    val durationMs: Long = 3000L,
    val isSystemNotification: Boolean = false
) : SnackbarVisuals

@Composable
fun CustomSnackbar(visuals: CustomSnackbarVisuals) {
    if (visuals.isSystemNotification) {
        // Compact, quick system toast pill (for Settings saved, BT connected, sync status, etc.)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = visuals.containerColor,
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 4.dp
            ) {
                Text(
                    text = visuals.message,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = visuals.contentColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                )
            }
        }
    } else {
        // Scanned Asset Result Alert banner (Found, New, Already Logged)
        val progress = remember(visuals) { Animatable(1f) }
        LaunchedEffect(visuals) {
            progress.snapTo(1f)
            if (visuals.durationMs > 10) {
                progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = visuals.durationMs.toInt(),
                        easing = LinearEasing
                    )
                )
            }
        }

        Surface(
            color = visuals.containerColor,
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Column {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = visuals.message,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = visuals.contentColor
                    )
                    if (!visuals.stickerText.isNullOrBlank()) {
                        Text(
                            text = visuals.stickerText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = visuals.stickerColor ?: Color.White,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                if (visuals.durationMs > 10) {
                    LinearProgressIndicator(
                        progress = { progress.value },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = Color.White.copy(alpha = 0.6f),
                        trackColor = Color.Transparent
                    )
                }
            }
        }
    }
}
