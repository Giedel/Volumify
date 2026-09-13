package com.example.volumify.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.volumify.model.AudioStreamInfo
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class CapsuleSliderOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onVolumeChanged: (streamType: Int, newValue: Int) -> Unit,
    private val onMuteToggled: (streamType: Int, isMuted: Boolean) -> Unit
) {

    private var overlayView: ComposeView? = null
    private var lifecycleOwner: SliderLifecycleOwner? = null

    var isShowing: Boolean = false
        private set

    // Observable states for Compose
    private var streamState by mutableStateOf<AudioStreamInfo?>(null)
    private var targetButtonX by mutableIntStateOf(0)
    private var targetButtonY by mutableIntStateOf(0)
    private var isLongPressModeState by mutableStateOf(false)
    private var isVisibleState by mutableStateOf(false)

    private class SliderLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        init {
            savedStateRegistryController.performAttach()
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
        }

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            lifecycleRegistry.handleLifecycleEvent(event)
        }

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val removeOverlayRunnable = Runnable {
        try {
            lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            lifecycleOwner = null
            overlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            overlayView = null
            isShowing = false
        }
    }

    fun show(stream: AudioStreamInfo, buttonScreenX: Int, buttonScreenY: Int, isLongPress: Boolean) {
        mainHandler.removeCallbacks(removeOverlayRunnable)
        streamState = stream
        targetButtonX = buttonScreenX
        targetButtonY = buttonScreenY
        isLongPressModeState = isLongPress
        isVisibleState = true

        if (overlayView == null) {
            val owner = SliderLifecycleOwner().apply {
                handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                handleLifecycleEvent(Lifecycle.Event.ON_START)
                handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            lifecycleOwner = owner

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

            overlayView = ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)

                setContent {
                    CapsuleSliderContent(
                        stream = streamState,
                        buttonScreenX = targetButtonX,
                        buttonScreenY = targetButtonY,
                        isLongPressMode = isLongPressModeState,
                        isVisible = isVisibleState,
                        onVolumeChange = { streamType, newVal ->
                            onVolumeChanged(streamType, newVal)
                        },
                        onMuteToggle = { streamType, isMuted ->
                            onMuteToggled(streamType, isMuted)
                        },
                        onDismiss = { dismiss() }
                    )
                }
            }

            try {
                windowManager.addView(overlayView, params)
                isShowing = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateVolume(newVolume: Int, isMuted: Boolean) {
        streamState?.let { current ->
            current.currentVolume = newVolume
            current.isMuted = isMuted
            // Trigger Compose recomposition
            streamState = current.copy(currentVolume = newVolume, isMuted = isMuted)
        }
    }

    fun dismiss() {
        if (!isShowing) return
        isVisibleState = false
        mainHandler.removeCallbacks(removeOverlayRunnable)
        mainHandler.postDelayed(removeOverlayRunnable, 180)
    }
}

@Composable
fun CapsuleSliderContent(
    stream: AudioStreamInfo?,
    buttonScreenX: Int,
    buttonScreenY: Int,
    isLongPressMode: Boolean,
    isVisible: Boolean,
    onVolumeChange: (streamType: Int, value: Int) -> Unit,
    onMuteToggle: (streamType: Int, isMuted: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    if (stream == null) return

    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    val pillWidth = 52.dp
    val pillHeight = 160.dp

    // Calculate position directly above button
    val btnXDp = with(density) { buttonScreenX.toDp() }
    val btnYDp = with(density) { buttonScreenY.toDp() }

    val pillX = (btnXDp - (pillWidth / 2)).coerceIn(12.dp, screenWidth - pillWidth - 12.dp)
    val pillY = if (btnYDp >= pillHeight + 28.dp) {
        btnYDp - pillHeight - 16.dp
    } else {
        btnYDp + 28.dp
    }.coerceIn(12.dp, screenHeight - pillHeight - 12.dp)

    // Stream color palette
    val (primaryColor, secondaryColor) = when (stream.streamType) {
        android.media.AudioManager.STREAM_RING -> Pair(Color(0xFF10B981), Color(0xFF059669))
        android.media.AudioManager.STREAM_NOTIFICATION -> Pair(Color(0xFFA855F7), Color(0xFF7E22CE))
        android.media.AudioManager.STREAM_ALARM -> Pair(Color(0xFFF59E0B), Color(0xFFD97706))
        else -> Pair(Color(0xFF38BDF8), Color(0xFF0284C7)) // Media / Active App
    }

    // Auto-dismiss timeout for Tap mode
    LaunchedEffect(stream.currentVolume, isVisible) {
        if (!isLongPressMode && isVisible) {
            delay(4000)
            onDismiss()
        }
    }

    // Full-screen backdrop
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                if (!isLongPressMode) {
                    detectTapGestures { onDismiss() }
                }
            }
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = scaleIn(initialScale = 0.7f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
            exit = scaleOut(targetScale = 0.8f, animationSpec = tween(150)) + fadeOut(),
            modifier = Modifier.offset {
                IntOffset(
                    with(density) { pillX.roundToPx() },
                    with(density) { pillY.roundToPx() }
                )
            }
        ) {
            val fillRatio = if (stream.maxVolume > 0) {
                (stream.currentVolume.toFloat() / stream.maxVolume).coerceIn(0f, 1f)
            } else 0f

            val animatedFill by animateFloatAsState(
                targetValue = fillRatio,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
                label = "volumeFill"
            )

            // Vertical Capsule Pill
            Box(
                modifier = Modifier
                    .size(pillWidth, pillHeight)
                    .shadow(16.dp, RoundedCornerShape(26.dp), spotColor = primaryColor.copy(alpha = 0.5f))
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xF0111827)) // Dark slate glass
                    .border(
                        1.5.dp,
                        Brush.verticalGradient(listOf(primaryColor, secondaryColor)),
                        RoundedCornerShape(26.dp)
                    )
                    .pointerInput(stream) {
                        if (!isLongPressMode) {
                            detectVerticalDragGestures { change, dragAmount ->
                                change.consume()
                                // Drag up decreases Y, which means increase volume
                                val stepPx = (pillHeight.toPx() / stream.maxVolume.coerceAtLeast(1))
                                val deltaSteps = -(dragAmount / stepPx)
                                val currentVol = stream.currentVolume
                                val newVol = (currentVol + deltaSteps).toInt().coerceIn(0, stream.maxVolume)
                                if (newVol != stream.currentVolume) {
                                    onVolumeChange(stream.streamType, newVol)
                                }
                            }
                        }
                    }
            ) {
                // Animated Dynamic Fill rising from bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(pillHeight * animatedFill)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(primaryColor.copy(alpha = 0.95f), secondaryColor.copy(alpha = 0.9f))
                            )
                        )
                )

                // Content inside pill: Volume % at top, Mute / Icon at bottom
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top Volume Percentage / Status Text
                    Text(
                        text = if (stream.isMuted || stream.currentVolume == 0) "MUTE" else "${stream.percentage}%",
                        color = Color.White,
                        fontSize = if (stream.isMuted || stream.currentVolume == 0) 9.sp else 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Bottom Mute / Stream Icon button
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x33000000))
                            .clickable {
                                onMuteToggle(stream.streamType, !stream.isMuted)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (stream.streamType) {
                                android.media.AudioManager.STREAM_RING -> "🔔"
                                android.media.AudioManager.STREAM_NOTIFICATION -> "💬"
                                android.media.AudioManager.STREAM_ALARM -> "⏰"
                                else -> if (stream.isMuted || stream.currentVolume == 0) "🔇" else "🎵"
                            },
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}
