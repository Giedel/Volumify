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
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.volumify.MainActivity
import com.example.volumify.data.AppPreferences
import com.example.volumify.model.AudioStreamDefaults
import com.example.volumify.model.AudioStreamInfo
import com.example.volumify.ui.CapsuleSliderOverlay
import com.example.volumify.ui.FloatingHubListener
import com.example.volumify.ui.FloatingHubView
import com.example.volumify.ui.HubButtonType

class FloatingVolumeService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    companion object {
        var isRunning = false
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager

    private var floatingHubView: FloatingHubView? = null
    private var buttonLayoutParams: WindowManager.LayoutParams? = null
    private var isDockedToRight = false
    private var relativeYRatio = 0.4f
    private var snapAnimator: ValueAnimator? = null

    private var capsuleSliderOverlay: CapsuleSliderOverlay? = null

    private var initialDragX = 0
    private var initialDragY = 0
    private var quickAdjustStreamType = AudioManager.STREAM_MUSIC
    private var quickAdjustStartVolume = 0

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private val audioStreams = mutableStateListOf<AudioStreamInfo>()

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                refreshAudioStreams()
            }
        }
    }

    private fun getScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppPreferences.KEY_ORBIT_INTERVAL_DP) {
            val newOrbit = AppPreferences.getOrbitIntervalDp(this)
            floatingHubView?.orbitIntervalDp = newOrbit
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

        capsuleSliderOverlay = CapsuleSliderOverlay(
            context = this,
            windowManager = windowManager,
            onVolumeChanged = { streamType, newVal ->
                adjustVolume(streamType, newVal)
            },
            onMuteToggled = { streamType, isMuted ->
                toggleMute(streamType, isMuted)
            }
        )

        startForegroundServiceWithNotification()
        refreshAudioStreams()
        setupFloatingButton()

        AppPreferences.getSharedPreferences(this).registerOnSharedPreferenceChangeListener(prefListener)

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

    private fun updateActiveAppStatus() {
        try {
            var appName: String? = null
            var appIcon: Bitmap? = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                val pkgName = mediaSessionManager?.mediaKeyEventSessionPackageName
                if (!pkgName.isNullOrEmpty() && pkgName != packageName) {
                    val pm = packageManager
                    val appInfo = pm.getApplicationInfo(pkgName, 0)
                    appName = pm.getApplicationLabel(appInfo).toString()
                    val drawable = pm.getApplicationIcon(appInfo)
                    appIcon = drawableToBitmap(drawable)
                }
            }

            if (appName == null && audioManager.isMusicActive) {
                appName = "Active Media"
            }

            val hasActive = (appName != null)
            floatingHubView?.hasActiveApp = hasActive
            floatingHubView?.activeAppName = appName ?: "App"
            floatingHubView?.activeAppIcon = appIcon
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: (40 * resources.displayMetrics.density).toInt()
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: (40 * resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun getStreamInfoForButton(buttonType: HubButtonType): AudioStreamInfo? {
        refreshAudioStreams()
        return when (buttonType) {
            HubButtonType.MEDIA -> audioStreams.find { it.streamType == AudioManager.STREAM_MUSIC }
            HubButtonType.RINGTONE -> audioStreams.find { it.streamType == AudioManager.STREAM_RING }
            HubButtonType.NOTIFICATION -> audioStreams.find { it.streamType == AudioManager.STREAM_NOTIFICATION }
            HubButtonType.ALARM -> audioStreams.find { it.streamType == AudioManager.STREAM_ALARM }
            HubButtonType.ACTIVE_APP -> {
                val base = audioStreams.find { it.streamType == AudioManager.STREAM_MUSIC }
                base?.copy(name = floatingHubView?.activeAppName ?: "Active App")
            }
        }
    }

    private fun setupFloatingButton() {
        if (!Settings.canDrawOverlays(this)) return

        try {
            val compactSize = (48 * resources.displayMetrics.density).toInt()
            val screenSize = getScreenSize()
            val screenHeight = screenSize.second
            val padding = 20

            val initialX = padding
            val minY = padding
            val maxY = (screenHeight - compactSize - padding).coerceAtLeast(minY)
            val availableY = (maxY - minY).coerceAtLeast(1)
            val initialY = (minY + relativeYRatio * availableY).toInt().coerceIn(minY, maxY)

            buttonLayoutParams = WindowManager.LayoutParams(
                compactSize,
                compactSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialX
                y = initialY
            }

            val orbitDp = AppPreferences.getOrbitIntervalDp(this)
            floatingHubView = FloatingHubView(this).apply {
                orbitIntervalDp = orbitDp
                this.isDockedToRight = this@FloatingVolumeService.isDockedToRight
                listener = hubListener
            }

            updateActiveAppStatus()
            windowManager.addView(floatingHubView, buttonLayoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val hubListener = object : FloatingHubListener {
        override fun onToggleExpandRequested() {
            expandMenu()
        }

        override fun onCollapseRequested() {
            collapseMenu()
        }

        override fun onButtonClicked(buttonType: HubButtonType, buttonScreenX: Int, buttonScreenY: Int) {
            updateActiveAppStatus()
            val stream = getStreamInfoForButton(buttonType) ?: return
            capsuleSliderOverlay?.show(stream, buttonScreenX, buttonScreenY, isLongPress = false)
        }

        override fun onQuickAdjustStart(buttonType: HubButtonType, buttonScreenX: Int, buttonScreenY: Int) {
            updateActiveAppStatus()
            val stream = getStreamInfoForButton(buttonType) ?: return
            quickAdjustStreamType = stream.streamType
            quickAdjustStartVolume = stream.currentVolume
            capsuleSliderOverlay?.show(stream, buttonScreenX, buttonScreenY, isLongPress = true)
        }

        override fun onQuickAdjustMove(buttonType: HubButtonType, deltaY: Float) {
            val stream = audioStreams.find { it.streamType == quickAdjustStreamType } ?: return
            val stepPx = 18f * resources.displayMetrics.density
            val deltaSteps = (deltaY / stepPx).toInt()
            val targetVolume = (quickAdjustStartVolume + deltaSteps).coerceIn(0, stream.maxVolume)
            if (targetVolume != stream.currentVolume) {
                adjustVolume(quickAdjustStreamType, targetVolume)
                capsuleSliderOverlay?.updateVolume(targetVolume, targetVolume == 0)
            }
        }

        override fun onQuickAdjustEnd(buttonType: HubButtonType) {
            capsuleSliderOverlay?.dismiss()
        }

        override fun onHubDragStart() {
            snapAnimator?.cancel()
            snapAnimator = null
            initialDragX = buttonLayoutParams?.x ?: 0
            initialDragY = buttonLayoutParams?.y ?: 0
        }

        override fun onHubDrag(dx: Int, dy: Int) {
            buttonLayoutParams?.let { params ->
                val screenSize = getScreenSize()
                val screenWidth = screenSize.first
                val screenHeight = screenSize.second
                val buttonWidth = floatingHubView?.width?.takeIf { it > 0 } ?: params.width
                val buttonHeight = floatingHubView?.height?.takeIf { it > 0 } ?: params.height

                params.x = (initialDragX + dx).coerceIn(0, screenWidth - buttonWidth)
                params.y = (initialDragY + dy).coerceIn(0, screenHeight - buttonHeight)
                try {
                    windowManager.updateViewLayout(floatingHubView, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onHubDragEnd(xVelocity: Float) {
            snapOrFlingToSide(xVelocity)
        }
    }

    private fun expandMenu() {
        val params = buttonLayoutParams ?: return
        val hubView = floatingHubView ?: return
        if (hubView.isExpanded) return

        snapAnimator?.cancel()
        snapAnimator = null

        updateActiveAppStatus()

        val screenSize = getScreenSize()
        val screenWidth = screenSize.first
        val screenHeight = screenSize.second
        val padding = 20

        val anchorCenterY = params.y + params.height / 2
        val expandedW = hubView.getExpandedWidth()
        val expandedH = hubView.getExpandedHeight()

        hubView.isDockedToRight = isDockedToRight

        val newX = if (isDockedToRight) {
            (screenWidth - expandedW - padding).coerceAtLeast(0)
        } else {
            padding
        }

        val minY = padding
        val maxY = (screenHeight - expandedH - padding).coerceAtLeast(minY)
        val newY = (anchorCenterY - expandedH / 2).coerceIn(minY, maxY)

        params.width = expandedW
        params.height = expandedH
        params.x = newX
        params.y = newY

        try {
            windowManager.updateViewLayout(hubView, params)
            hubView.animateExpand()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun collapseMenu() {
        val params = buttonLayoutParams ?: return
        val hubView = floatingHubView ?: return
        if (!hubView.isExpanded) return

        capsuleSliderOverlay?.dismiss()

        hubView.animateCollapse {
            val compactSize = hubView.compactSize
            val screenSize = getScreenSize()
            val screenWidth = screenSize.first
            val screenHeight = screenSize.second
            val padding = 20

            val anchorCenterY = params.y + params.height / 2
            val minY = padding
            val maxY = (screenHeight - compactSize - padding).coerceAtLeast(minY)
            val targetY = (anchorCenterY - compactSize / 2).coerceIn(minY, maxY)

            val targetX = if (isDockedToRight) {
                screenWidth - compactSize - padding
            } else {
                padding
            }

            params.width = compactSize
            params.height = compactSize
            params.x = targetX
            params.y = targetY

            try {
                windowManager.updateViewLayout(hubView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val availableY = (maxY - minY).coerceAtLeast(1)
            relativeYRatio = ((targetY - minY).toFloat() / availableY).coerceIn(0f, 1f)
        }
    }

    private fun adjustVolume(streamType: Int, newValue: Int) {
        try {
            audioManager.setStreamVolume(streamType, newValue, 0)
            audioStreams.find { it.streamType == streamType }?.let { item ->
                item.currentVolume = newValue
                item.isMuted = (newValue == 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleMute(streamType: Int, isMuted: Boolean) {
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
                capsuleSliderOverlay?.updateVolume(0, true)
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
                capsuleSliderOverlay?.updateVolume(half, false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun snapOrFlingToSide(xVelocity: Float) {
        val params = buttonLayoutParams ?: return
        val screenSize = getScreenSize()
        val screenWidth = screenSize.first
        val screenHeight = screenSize.second
        val buttonWidth = floatingHubView?.width?.takeIf { it > 0 } ?: params.width
        val buttonHeight = floatingHubView?.height?.takeIf { it > 0 } ?: params.height
        val padding = 20

        val snapToRight: Boolean = when {
            xVelocity > 1000f -> true // Flicked right
            xVelocity < -1000f -> false // Flicked left
            params.x + (buttonWidth / 2) > screenWidth / 2 -> true // Dragged past middle
            else -> false
        }

        isDockedToRight = snapToRight
        floatingHubView?.isDockedToRight = snapToRight
        val targetX: Int = if (snapToRight) screenWidth - buttonWidth - padding else padding

        val minY = padding
        val maxY = (screenHeight - buttonHeight - padding).coerceAtLeast(minY)
        val clampedY = params.y.coerceIn(minY, maxY)
        params.y = clampedY

        val availableY = (maxY - minY).coerceAtLeast(1)
        relativeYRatio = ((clampedY - minY).toFloat() / availableY).coerceIn(0f, 1f)

        snapAnimator?.cancel()
        val startX = params.x
        snapAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                try {
                    windowManager.updateViewLayout(floatingHubView, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        snapAnimator?.start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        capsuleSliderOverlay?.dismiss()
        val hubView = floatingHubView ?: return
        val params = buttonLayoutParams ?: return

        hubView.collapseImmediately()
        params.width = hubView.compactSize
        params.height = hubView.compactSize

        hubView.post {
            updateFloatingButtonPositionOnOrientationChange()
        }
    }

    private fun updateFloatingButtonPositionOnOrientationChange() {
        val params = buttonLayoutParams ?: return
        val hubView = floatingHubView ?: return
        if (hubView.windowToken == null) return

        snapAnimator?.cancel()
        snapAnimator = null

        val screenSize = getScreenSize()
        val screenWidth = screenSize.first
        val screenHeight = screenSize.second
        val buttonWidth = hubView.width.takeIf { it > 0 } ?: params.width
        val buttonHeight = hubView.height.takeIf { it > 0 } ?: params.height
        val padding = 20

        hubView.isDockedToRight = isDockedToRight

        val targetX = if (isDockedToRight) {
            screenWidth - buttonWidth - padding
        } else {
            padding
        }

        val minY = padding
        val maxY = (screenHeight - buttonHeight - padding).coerceAtLeast(minY)
        val availableY = (maxY - minY).coerceAtLeast(1)
        val targetY = (minY + relativeYRatio * availableY).toInt().coerceIn(minY, maxY)

        params.x = targetX
        params.y = targetY

        try {
            windowManager.updateViewLayout(hubView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        snapAnimator?.cancel()
        snapAnimator = null
        capsuleSliderOverlay?.dismiss()
        capsuleSliderOverlay = null
        floatingHubView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        floatingHubView = null
        try {
            AppPreferences.getSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(volumeReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
