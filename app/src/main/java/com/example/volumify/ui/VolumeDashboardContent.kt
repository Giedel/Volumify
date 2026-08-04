package com.example.volumify.ui

import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.volumify.model.AudioStreamInfo
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun FullScreenOverlayContainer(
    streams: List<AudioStreamInfo>,
    initialOffsetX: Float,
    initialOffsetY: Float,
    onVolumeChanged: (streamType: Int, value: Int) -> Unit,
    onMuteToggled: (streamType: Int, isMuted: Boolean) -> Unit,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(false) }
    var panelOffsetX by remember { mutableStateOf(initialOffsetX) }
    var panelOffsetY by remember { mutableStateOf(initialOffsetY) }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    fun dismissWithAnimation() {
        isVisible = false
    }

    LaunchedEffect(isVisible) {
        if (!isVisible) {
            delay(160)
            onDismissed()
        }
    }

    // Full-Screen Non-Interactive Background Scrim (Tapping deactivates panel)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .pointerInput(Unit) {
                detectTapGestures {
                    dismissWithAnimation()
                }
            }
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(180)) + scaleIn(
                initialScale = 0.55f,
                animationSpec = spring(
                    dampingRatio = 0.68f,
                    stiffness = Spring.StiffnessLow
                )
            ),
            exit = fadeOut(animationSpec = tween(150)) + scaleOut(
                targetScale = 0.55f,
                animationSpec = tween(150)
            ),
            modifier = Modifier.offset { IntOffset(panelOffsetX.roundToInt(), panelOffsetY.roundToInt()) }
        ) {
            val cardBorder = Brush.verticalGradient(
                colors = listOf(Color(0xFF38BDF8), Color(0xFF818CF8).copy(alpha = 0.5f), Color(0x22000000))
            )

            Card(
                modifier = Modifier
                    .width(230.dp)
                    .border(1.2.dp, cardBorder, RoundedCornerShape(26.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            panelOffsetX += dragAmount.x
                            panelOffsetY += dragAmount.y
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { /* Consume tap inside card so background tap isn't triggered */ }
                    },
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xF70F172A)),
                elevation = CardDefaults.cardElevation(defaultElevation = 20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    streams.forEachIndexed { index, stream ->
                        ThinVolumePillStreamItem(
                            stream = stream,
                            onVolumeChanged = { newValue -> onVolumeChanged(stream.streamType, newValue) },
                            onMuteToggled = { isMuted -> onMuteToggled(stream.streamType, isMuted) }
                        )
                        if (index < streams.size - 1) {
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ThinVolumePillStreamItem(
    stream: AudioStreamInfo,
    onVolumeChanged: (Int) -> Unit,
    onMuteToggled: (Boolean) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var localValue by remember(stream.currentVolume) { mutableStateOf(stream.currentVolume.toFloat()) }

    val displayValue = if (isDragging) localValue else stream.currentVolume.toFloat()

    // Smooth transitional height expansion when dragging specific volume stream
    val animatedPillHeight by animateDpAsState(
        targetValue = if (isDragging) 18.dp else 12.dp,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "thin_pill_height"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        // Label & Vector Icon placed ABOVE corresponding volume pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onMuteToggled(!stream.isMuted) }
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (stream.isMuted || stream.currentVolume == 0) Color(0xFF334155).copy(alpha = 0.6f)
                        else Color(0xFF0284C7).copy(alpha = 0.35f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                MinimalStreamIcon(
                    streamType = stream.streamType,
                    isMuted = stream.isMuted || stream.currentVolume == 0
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = stream.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (stream.isMuted) Color(0xFF94A3B8) else Color.White,
                    fontSize = 12.sp
                )
            )
        }

        // Thin Volume Pill (No Slider line holder, No thumb, No volume numbers, No Mute text)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedPillHeight)
                .clip(CircleShape)
                .background(Color(0xFF1E293B).copy(alpha = 0.8f))
                .pointerInput(stream.maxVolume) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        val newVol = (fraction * stream.maxVolume).roundToInt()
                        localValue = newVol.toFloat()
                        onVolumeChanged(newVol)
                    }
                }
                .pointerInput(stream.maxVolume) {
                    detectHorizontalDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            val newVol = (fraction * stream.maxVolume).roundToInt()
                            localValue = (fraction * stream.maxVolume)
                            onVolumeChanged(newVol)
                        }
                    )
                }
        ) {
            val totalWidth = maxWidth
            val fillWidth = totalWidth * (displayValue / (if (stream.maxVolume > 0) stream.maxVolume.toFloat() else 1f)).coerceIn(0f, 1f)
            val fillBrush: Brush = if (stream.isMuted || stream.currentVolume == 0) {
                SolidColor(Color(0xFF475569).copy(alpha = 0.45f))
            } else {
                Brush.horizontalGradient(listOf(Color(0xFF0284C7), Color(0xFF38BDF8)))
            }

            // Expanding Filled Progress Background
            Box(
                modifier = Modifier
                    .width(fillWidth)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(fillBrush)
            )
        }
    }
}

@Composable
fun MinimalStreamIcon(
    streamType: Int,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = if (isMuted) Color(0xFF94A3B8) else Color(0xFF38BDF8)

    Canvas(modifier = modifier.size(13.dp)) {
        val w = size.width
        val h = size.height

        when (streamType) {
            AudioManager.STREAM_MUSIC -> {
                drawCircle(
                    color = activeColor,
                    radius = w * 0.2f,
                    center = Offset(w * 0.3f, h * 0.72f)
                )
                drawCircle(
                    color = activeColor,
                    radius = w * 0.2f,
                    center = Offset(w * 0.75f, h * 0.62f)
                )
                drawLine(
                    color = activeColor,
                    start = Offset(w * 0.48f, h * 0.72f),
                    end = Offset(w * 0.48f, h * 0.25f),
                    strokeWidth = 2.5f
                )
                drawLine(
                    color = activeColor,
                    start = Offset(w * 0.93f, h * 0.62f),
                    end = Offset(w * 0.93f, h * 0.15f),
                    strokeWidth = 2.5f
                )
                drawLine(
                    color = activeColor,
                    start = Offset(w * 0.48f, h * 0.25f),
                    end = Offset(w * 0.93f, h * 0.15f),
                    strokeWidth = 3f
                )
            }
            AudioManager.STREAM_RING -> {
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.15f)
                    cubicTo(w * 0.3f, h * 0.15f, w * 0.25f, h * 0.4f, w * 0.25f, h * 0.65f)
                    lineTo(w * 0.15f, h * 0.75f)
                    lineTo(w * 0.85f, h * 0.75f)
                    lineTo(w * 0.75f, h * 0.65f)
                    cubicTo(w * 0.75f, h * 0.4f, w * 0.7f, h * 0.15f, w * 0.5f, h * 0.15f)
                    close()
                }
                drawPath(path = path, color = activeColor, style = Stroke(width = 2.5f))
                drawCircle(
                    color = activeColor,
                    radius = w * 0.08f,
                    center = Offset(w * 0.5f, h * 0.87f)
                )
            }
            AudioManager.STREAM_NOTIFICATION -> {
                val path = Path().apply {
                    moveTo(w * 0.2f, h * 0.2f)
                    lineTo(w * 0.8f, h * 0.2f)
                    cubicTo(w * 0.9f, h * 0.2f, w * 0.9f, h * 0.3f, w * 0.9f, h * 0.3f)
                    lineTo(w * 0.9f, h * 0.65f)
                    cubicTo(w * 0.9f, h * 0.75f, w * 0.8f, h * 0.75f, w * 0.8f, h * 0.75f)
                    lineTo(w * 0.4f, h * 0.75f)
                    lineTo(w * 0.2f, h * 0.9f)
                    lineTo(w * 0.2f, h * 0.75f)
                    close()
                }
                drawPath(path = path, color = activeColor, style = Stroke(width = 2.5f))
            }
            AudioManager.STREAM_ALARM -> {
                drawCircle(
                    color = activeColor,
                    radius = w * 0.35f,
                    center = Offset(w * 0.5f, h * 0.55f),
                    style = Stroke(width = 2.5f)
                )
                drawLine(
                    color = activeColor,
                    start = Offset(w * 0.5f, h * 0.55f),
                    end = Offset(w * 0.5f, h * 0.35f),
                    strokeWidth = 2.5f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = activeColor,
                    start = Offset(w * 0.5f, h * 0.55f),
                    end = Offset(w * 0.68f, h * 0.55f),
                    strokeWidth = 2.5f,
                    cap = StrokeCap.Round
                )
            }
            else -> {
                drawCircle(color = activeColor, radius = w * 0.3f, center = Offset(w * 0.5f, h * 0.5f))
            }
        }
    }
}
