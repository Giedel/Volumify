package com.example.volumify.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.ComposeView
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationCompat
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
import com.example.volumify.MainActivity
import com.example.volumify.model.AudioStreamDefaults
import com.example.volumify.model.AudioStreamInfo
import com.example.volumify.ui.PatternButtonView
import com.example.volumify.ui.FullScreenOverlayContainer

class FloatingVolumeService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    companion object {
        var isRunning = false
    }

    private val lifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager

    private var floatingButtonView: PatternButtonView? = null
    private var buttonLayoutParams: WindowManager.LayoutParams? = null

    private var dashboardView: ComposeView? = null
    private var dashboardLayoutParams: WindowManager.LayoutParams? = null

    private var isDashboardExpanded = false

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    private val audioStreams = mutableStateListOf<AudioStreamInfo>()

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                refreshAudioStreams()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        startForegroundServiceWithNotification()
        refreshAudioStreams()
        setupFloatingButton()

        // Register broadcast receiver for system-wide volume key / app volume changes
        try {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            registerReceiver(volumeReceiver, filter)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceWithNotification()
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "floating_volume_service_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Floating Volume Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the system-wide floating volume controls active"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    1001,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, notification, 0)
            } else {
                startForeground(1001, notification)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            try {
                startForeground(1001, notification)
            } catch (ex: Throwable) {
                ex.printStackTrace()
            }
        }
    }

    private fun refreshAudioStreams() {
        val updated = AudioStreamDefaults.getStreams(audioManager)
        audioStreams.clear()
        audioStreams.addAll(updated)
    }

    private fun setupFloatingButton() {
        if (!Settings.canDrawOverlays(this)) {
            return
        }

        try {
            val buttonSize = (45 * resources.displayMetrics.density).toInt()

            buttonLayoutParams = WindowManager.LayoutParams(
                buttonSize,
                buttonSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 20
                y = (resources.displayMetrics.heightPixels * 0.4).toInt()
            }

            floatingButtonView = PatternButtonView(this).apply {
                setOnTouchListener(FloatingButtonTouchListener())
            }

            windowManager.addView(floatingButtonView, buttonLayoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private inner class FloatingButtonTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var touchStartTime = 0L
        private var velocityTracker: VelocityTracker? = null

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.85f)
                        .scaleY(0.85f)
                        .setDuration(120)
                        .setInterpolator(DecelerateInterpolator())
                        .start()

                    initialX = buttonLayoutParams?.x ?: 0
                    initialY = buttonLayoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()

                    velocityTracker?.clear()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    buttonLayoutParams?.let { params ->
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(floatingButtonView, params)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(250)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .start()

                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)

                    val xVelocity = velocityTracker?.xVelocity ?: 0f
                    val touchDuration = System.currentTimeMillis() - touchStartTime
                    val totalDx = Math.abs(event.rawX - initialTouchX)
                    val totalDy = Math.abs(event.rawY - initialTouchY)

                    // Check for click/tap
                    if (totalDx < 15 && totalDy < 15 && touchDuration < 250) {
                        toggleDashboard()
                    } else {
                        // Snap or Fling to screen side
                        snapOrFlingToSide(xVelocity)
                    }

                    velocityTracker?.recycle()
                    velocityTracker = null
                    return true
                }
            }
            return false
        }
    }

    private fun snapOrFlingToSide(xVelocity: Float) {
        val params = buttonLayoutParams ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val buttonWidth = floatingButtonView?.width ?: 150
        val padding = 20

        val targetX: Int = when {
            xVelocity > 1000f -> screenWidth - buttonWidth - padding // Flicked right
            xVelocity < -1000f -> padding                           // Flicked left
            params.x + (buttonWidth / 2) > screenWidth / 2 -> screenWidth - buttonWidth - padding // Dragged past middle
            else -> padding
        }

        val startX = params.x
        val animator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                try {
                    windowManager.updateViewLayout(floatingButtonView, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        animator.start()
    }

    private fun toggleDashboard() {
        if (isDashboardExpanded) {
            collapseDashboard()
        } else {
            expandDashboard()
        }
    }

    private var overlayLifecycleOwner: OverlayViewLifecycleOwner? = null

    private class OverlayViewLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
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

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        override val viewModelStore: ViewModelStore
            get() = store
    }

    private fun expandDashboard() {
        if (isDashboardExpanded) return

        refreshAudioStreams()

        // Smooth shrink & fade out transition for floating pattern button
        floatingButtonView?.animate()
            ?.scaleX(0.15f)
            ?.scaleY(0.15f)
            ?.alpha(0f)
            ?.setDuration(160)
            ?.setInterpolator(DecelerateInterpolator())
            ?.withEndAction {
                floatingButtonView?.visibility = View.INVISIBLE
            }
            ?.start()

        val buttonX = buttonLayoutParams?.x ?: 20
        val buttonY = buttonLayoutParams?.y ?: (resources.displayMetrics.heightPixels * 0.4).toInt()

        val cardWidthPx = (230 * resources.displayMetrics.density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val initX = if (buttonX > screenWidth / 2) {
            (buttonX - cardWidthPx + 40).coerceAtLeast(10).toFloat()
        } else {
            (buttonX + 10).coerceAtMost(screenWidth - cardWidthPx - 10).toFloat()
        }
        val initY = (buttonY - 20).coerceIn(20, (screenHeight - 300).coerceAtLeast(20)).toFloat()

        dashboardLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        val owner = OverlayViewLifecycleOwner().apply {
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
            handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        overlayLifecycleOwner = owner

        dashboardView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)

            setContent {
                FullScreenOverlayContainer(
                    streams = audioStreams,
                    initialOffsetX = initX,
                    initialOffsetY = initY,
                    onVolumeChanged = { streamType, newValue ->
                        try {
                            val current = audioManager.getStreamVolume(streamType)
                            val diff = newValue - current
                            if (diff > 0) {
                                repeat(diff) {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, 0)
                                }
                            } else if (diff < 0) {
                                repeat(Math.abs(diff)) {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, 0)
                                }
                            }
                            audioManager.setStreamVolume(streamType, newValue, 0)

                            audioStreams.find { it.streamType == streamType }?.let { item ->
                                item.currentVolume = newValue
                                item.isMuted = (newValue == 0)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    onMuteToggled = { streamType, isMuted ->
                        try {
                            if (isMuted) {
                                audioManager.setStreamVolume(streamType, 0, 0)
                                try {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, 0)
                                } catch (t: Throwable) {}
                                audioStreams.find { it.streamType == streamType }?.let { item ->
                                    item.currentVolume = 0
                                    item.isMuted = true
                                }
                            } else {
                                val max = audioManager.getStreamMaxVolume(streamType)
                                val half = (max / 2).coerceAtLeast(1)
                                try {
                                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0)
                                } catch (t: Throwable) {}
                                audioManager.setStreamVolume(streamType, half, 0)
                                audioStreams.find { it.streamType == streamType }?.let { item ->
                                    item.currentVolume = half
                                    item.isMuted = false
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    onDismissed = { collapseDashboard() }
                )
            }
        }

        try {
            windowManager.addView(dashboardView, dashboardLayoutParams)
            isDashboardExpanded = true
        } catch (e: Exception) {
            e.printStackTrace()
            floatingButtonView?.visibility = View.VISIBLE
        }
    }

    private fun collapseDashboard() {
        if (!isDashboardExpanded) return
        overlayLifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        overlayLifecycleOwner = null
        dashboardView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        dashboardView = null
        isDashboardExpanded = false

        // Smooth spring scale in & fade in transition for floating pattern button
        floatingButtonView?.apply {
            visibility = View.VISIBLE
            scaleX = 0.15f
            scaleY = 0.15f
            alpha = 0f
            animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1.0f)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(2.0f))
                .start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        collapseDashboard()
        floatingButtonView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        try {
            unregisterReceiver(volumeReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
