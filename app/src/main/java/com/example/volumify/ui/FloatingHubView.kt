package com.example.volumify.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class HubButtonType {
    ACTIVE_APP,
    MEDIA,
    RINGTONE,
    NOTIFICATION,
    ALARM
}

interface FloatingHubListener {
    fun onToggleExpandRequested()
    fun onCollapseRequested()
    fun onButtonClicked(buttonType: HubButtonType, buttonScreenX: Int, buttonScreenY: Int)
    fun onQuickAdjustStart(buttonType: HubButtonType, buttonScreenX: Int, buttonScreenY: Int)
    fun onQuickAdjustMove(buttonType: HubButtonType, deltaY: Float)
    fun onQuickAdjustEnd(buttonType: HubButtonType)
    fun onHubDragStart()
    fun onHubDrag(dx: Int, dy: Int)
    fun onHubDragEnd(xVelocity: Float)
}

class FloatingHubView(context: Context) : View(context) {

    var listener: FloatingHubListener? = null

    var hasActiveApp: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var activeAppName: String = "App"
        set(value) {
            field = value
            invalidate()
        }

    var activeAppIcon: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var isDockedToRight: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var orbitIntervalDp: Int = com.example.volumify.data.AppPreferences.DEFAULT_ORBIT_INTERVAL_DP
        set(value) {
            field = value.coerceIn(
                com.example.volumify.data.AppPreferences.MIN_ORBIT_INTERVAL_DP,
                com.example.volumify.data.AppPreferences.MAX_ORBIT_INTERVAL_DP
            )
            requestLayout()
            invalidate()
        }

    var isExpanded: Boolean = false
        private set

    var expandProgress: Float = 0f
        private set

    private val density = resources.displayMetrics.density

    val compactSize: Int get() = (48 * density).toInt()
    val orbitDistance: Float get() = orbitIntervalDp * density
    val centerButtonRadius: Float get() = 22f * density
    val outerButtonRadius: Float get() = 18f * density
    private val extraPadding: Float get() = 8f * density

    fun getExpandedWidth(): Int {
        return (compactSize / 2f + orbitDistance + outerButtonRadius + extraPadding).toInt()
    }

    fun getExpandedHeight(): Int {
        return (2f * (orbitDistance + outerButtonRadius + extraPadding)).toInt()
    }

    // Paints
    private val buttonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#181F30")
    }

    private val buttonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val buttonGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val patternPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    // Touch State Machine
    private enum class TouchState {
        IDLE,
        BUTTON_DOWN_PENDING_LONG_PRESS,
        BUTTON_LONG_PRESS_ADJUSTING,
        DRAGGING
    }

    private var touchState = TouchState.IDLE
    private var touchedButton: HubButtonType? = null
    private var touchedCenterMain = false

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var longPressStartY = 0f

    private var velocityTracker: VelocityTracker? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var buttonScale = 1.0f
    private var buttonScaleAnimator: ValueAnimator? = null
    private var expandAnimator: ValueAnimator? = null

    private val longPressRunnable = Runnable {
        val btn = touchedButton ?: return@Runnable
        if (touchState == TouchState.BUTTON_DOWN_PENDING_LONG_PRESS) {
            touchState = TouchState.BUTTON_LONG_PRESS_ADJUSTING
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

            val coords = getButtonCenterScreenCoords(btn)
            listener?.onQuickAdjustStart(btn, coords.first, coords.second)
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (isExpanded || expandProgress > 0f) {
            setMeasuredDimension(getExpandedWidth(), getExpandedHeight())
        } else {
            setMeasuredDimension(compactSize, compactSize)
        }
    }

    fun animateExpand(onComplete: () -> Unit = {}) {
        expandAnimator?.cancel()
        isExpanded = true
        requestLayout()
        val anim = ValueAnimator.ofFloat(expandProgress, 1.0f).apply {
            duration = 280
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener {
                expandProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onComplete()
                }
            })
        }
        expandAnimator = anim
        anim.start()
    }

    fun animateCollapse(onComplete: () -> Unit = {}) {
        expandAnimator?.cancel()
        var finished = false
        val finishAction = {
            if (!finished) {
                finished = true
                expandProgress = 0.0f
                invalidate()
                onComplete()
            }
        }

        val anim = ValueAnimator.ofFloat(expandProgress, 0.0f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                expandProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    finishAction()
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    finishAction()
                }
            })
        }
        expandAnimator = anim
        anim.start()
    }

    fun collapseImmediately() {
        expandAnimator?.cancel()
        expandAnimator = null
        isExpanded = false
        expandProgress = 0.0f
        requestLayout()
        invalidate()
    }

    fun getAnchorCenter(): Pair<Float, Float> {
        val cy = height / 2f
        val cx = if (isExpanded || expandProgress > 0f) {
            if (isDockedToRight) {
                width - compactSize / 2f
            } else {
                compactSize / 2f
            }
        } else {
            width / 2f
        }
        return Pair(cx, cy)
    }

    private fun getButtonAngle(type: HubButtonType): Double {
        val baseDeg = if (hasActiveApp) {
            when (type) {
                HubButtonType.MEDIA -> -72.0
                HubButtonType.RINGTONE -> -36.0
                HubButtonType.ACTIVE_APP -> 0.0
                HubButtonType.ALARM -> 36.0
                HubButtonType.NOTIFICATION -> 72.0
            }
        } else {
            when (type) {
                HubButtonType.MEDIA -> -65.0
                HubButtonType.RINGTONE -> -22.0
                HubButtonType.ALARM -> 22.0
                HubButtonType.NOTIFICATION -> 65.0
                HubButtonType.ACTIVE_APP -> 0.0
            }
        }

        val effectiveDeg = if (!isDockedToRight) {
            baseDeg
        } else {
            180.0 - baseDeg
        }
        return Math.toRadians(effectiveDeg)
    }

    fun getButtonCenterCoords(type: HubButtonType): Pair<Float, Float> {
        val anchor = getAnchorCenter()
        val angle = getButtonAngle(type)
        val dist = orbitDistance * expandProgress
        val bx = anchor.first + (dist * cos(angle)).toFloat()
        val by = anchor.second + (dist * sin(angle)).toFloat()
        return Pair(bx, by)
    }

    fun getButtonCenterScreenCoords(type: HubButtonType): Pair<Int, Int> {
        val local = getButtonCenterCoords(type)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return Pair(loc[0] + local.first.toInt(), loc[1] + local.second.toInt())
    }

    fun getMainButtonCenterScreenCoords(): Pair<Int, Int> {
        val anchor = getAnchorCenter()
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return Pair(loc[0] + anchor.first.toInt(), loc[1] + anchor.second.toInt())
    }

    private fun findTouchedSurroundingButton(x: Float, y: Float): HubButtonType? {
        if (!isExpanded || expandProgress < 0.5f) return null

        val buttonsToCheck = if (hasActiveApp) {
            listOf(
                HubButtonType.MEDIA,
                HubButtonType.RINGTONE,
                HubButtonType.ACTIVE_APP,
                HubButtonType.ALARM,
                HubButtonType.NOTIFICATION
            )
        } else {
            listOf(
                HubButtonType.MEDIA,
                HubButtonType.RINGTONE,
                HubButtonType.ALARM,
                HubButtonType.NOTIFICATION
            )
        }

        for (btn in buttonsToCheck) {
            val center = getButtonCenterCoords(btn)
            val radius = outerButtonRadius + (6f * density)
            if (hypot(x - center.first, y - center.second) <= radius) {
                return btn
            }
        }
        return null
    }

    private fun isTouchOnCenterMain(x: Float, y: Float): Boolean {
        val anchor = getAnchorCenter()
        val radius = centerButtonRadius + (6f * density)
        return hypot(x - anchor.first, y - anchor.second) <= radius
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                longPressStartY = event.rawY

                velocityTracker?.clear()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)

                if (!isExpanded) {
                    // Collapsed mode: Any touch on the main button starts possible drag or tap to expand
                    touchedCenterMain = true
                    touchState = TouchState.BUTTON_DOWN_PENDING_LONG_PRESS
                    animateButtonScale(0.88f)
                    listener?.onHubDragStart()
                    return true
                } else {
                    // Expanded mode:
                    val hitSurrounding = findTouchedSurroundingButton(event.x, event.y)
                    if (hitSurrounding != null) {
                        touchedButton = hitSurrounding
                        touchedCenterMain = false
                        touchState = TouchState.BUTTON_DOWN_PENDING_LONG_PRESS
                        animateButtonScale(0.88f)
                        mainHandler.postDelayed(longPressRunnable, 260)
                        return true
                    } else if (isTouchOnCenterMain(event.x, event.y)) {
                        touchedCenterMain = true
                        touchedButton = null
                        touchState = TouchState.BUTTON_DOWN_PENDING_LONG_PRESS
                        animateButtonScale(0.88f)
                        return true
                    } else {
                        // Tapped outside all buttons -> collapse
                        listener?.onCollapseRequested()
                        return true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                val totalDist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                when (touchState) {
                    TouchState.BUTTON_DOWN_PENDING_LONG_PRESS -> {
                        if (totalDist > 8f * density) {
                            mainHandler.removeCallbacks(longPressRunnable)
                            animateButtonScale(1.0f)
                            if (!isExpanded) {
                                // In collapsed mode, moving drags the floating button
                                touchState = TouchState.DRAGGING
                                listener?.onHubDrag(dx, dy)
                            } else {
                                // In expanded mode, moving too far cancels button press
                                touchState = TouchState.IDLE
                            }
                        }
                    }
                    TouchState.BUTTON_LONG_PRESS_ADJUSTING -> {
                        touchedButton?.let { btn ->
                            val deltaY = longPressStartY - event.rawY
                            listener?.onQuickAdjustMove(btn, deltaY)
                        }
                    }
                    TouchState.DRAGGING -> {
                        listener?.onHubDrag(dx, dy)
                    }
                    else -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val xVel = velocityTracker?.xVelocity ?: 0f
                val isCancel = (event.actionMasked == MotionEvent.ACTION_CANCEL)

                when (touchState) {
                    TouchState.BUTTON_DOWN_PENDING_LONG_PRESS -> {
                        animateButtonScale(1.0f)
                        if (!isCancel) {
                            if (!isExpanded && touchedCenterMain) {
                                // Tap on collapsed button -> Expand!
                                listener?.onToggleExpandRequested()
                            } else if (isExpanded) {
                                if (touchedCenterMain) {
                                    // Tap on expanded center button -> Collapse!
                                    listener?.onCollapseRequested()
                                } else {
                                    touchedButton?.let { btn ->
                                        // Tap on surrounding button -> Open Capsule Slider!
                                        val coords = getButtonCenterScreenCoords(btn)
                                        listener?.onButtonClicked(btn, coords.first, coords.second)
                                    }
                                }
                            }
                        }
                    }
                    TouchState.BUTTON_LONG_PRESS_ADJUSTING -> {
                        animateButtonScale(1.0f)
                        touchedButton?.let { btn ->
                            listener?.onQuickAdjustEnd(btn)
                        }
                    }
                    TouchState.DRAGGING -> {
                        listener?.onHubDragEnd(if (isCancel) 0f else xVel)
                    }
                    else -> {}
                }

                velocityTracker?.recycle()
                velocityTracker = null
                touchState = TouchState.IDLE
                touchedButton = null
                touchedCenterMain = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun animateButtonScale(target: Float) {
        buttonScaleAnimator?.cancel()
        buttonScaleAnimator = ValueAnimator.ofFloat(buttonScale, target).apply {
            duration = 130
            interpolator = if (target < 1f) DecelerateInterpolator() else OvershootInterpolator(2.0f)
            addUpdateListener {
                buttonScale = it.animatedValue as Float
                invalidate()
            }
        }
        buttonScaleAnimator?.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val anchor = getAnchorCenter()
        val cx = anchor.first
        val cy = anchor.second

        // Draw surrounding volume buttons only (transparent background)
        if (expandProgress > 0.01f) {
            // Draw surrounding volume buttons
            drawSurroundingButton(canvas, HubButtonType.MEDIA, "#38BDF8", "#0284C7")
            drawSurroundingButton(canvas, HubButtonType.RINGTONE, "#10B981", "#059669")
            drawSurroundingButton(canvas, HubButtonType.ALARM, "#F59E0B", "#D97706")
            drawSurroundingButton(canvas, HubButtonType.NOTIFICATION, "#A855F7", "#7E22CE")

            if (hasActiveApp) {
                drawSurroundingButton(canvas, HubButtonType.ACTIVE_APP, "#EC4899", "#8B5CF6")
            }
        }

        // Draw Center Main Floating Button
        drawCenterMainButton(canvas, cx, cy)
    }

    private fun drawCenterMainButton(canvas: Canvas, cx: Float, cy: Float) {
        val radius = centerButtonRadius
        val isPressed = (touchedCenterMain && touchState != TouchState.IDLE)
        val scale = if (isPressed) buttonScale else 1.0f

        canvas.save()
        canvas.scale(scale, scale, cx, cy)

        // Glow ring
        buttonGlowPaint.color = Color.parseColor("#38BDF8")
        buttonGlowPaint.alpha = if (isPressed) 120 else 60
        canvas.drawCircle(cx, cy, radius + (3f * density), buttonGlowPaint)

        // Dark glass background
        buttonBgPaint.color = Color.parseColor("#121726")
        canvas.drawCircle(cx, cy, radius, buttonBgPaint)

        // Gradient border
        val borderShader = LinearGradient(
            cx - radius, cy - radius,
            cx + radius, cy + radius,
            intArrayOf(Color.parseColor("#38BDF8"), Color.parseColor("#818CF8"), Color.parseColor("#C084FC")),
            null,
            Shader.TileMode.CLAMP
        )
        buttonBorderPaint.shader = borderShader
        canvas.drawCircle(cx, cy, radius, buttonBorderPaint)

        // Center visual icon:
        if (expandProgress > 0.6f) {
            // When expanded, draw a sleek "X" close cross in center
            val crossSize = radius * 0.36f
            iconPaint.strokeWidth = 2.4f * density
            iconPaint.color = Color.parseColor("#38BDF8")
            canvas.drawLine(cx - crossSize, cy - crossSize, cx + crossSize, cy + crossSize, iconPaint)
            canvas.drawLine(cx + crossSize, cy - crossSize, cx - crossSize, cy + crossSize, iconPaint)
        } else {
            // When collapsed, draw the sleek Volumify geometric arcs
            patternPaint.shader = borderShader
            val arcR1 = radius * 0.62f
            val arcBounds1 = RectF(cx - arcR1, cy - arcR1, cx + arcR1, cy + arcR1)
            canvas.drawArc(arcBounds1, 45f, 100f, false, patternPaint)
            canvas.drawArc(arcBounds1, 225f, 100f, false, patternPaint)

            val arcR2 = radius * 0.38f
            val arcBounds2 = RectF(cx - arcR2, cy - arcR2, cx + arcR2, cy + arcR2)
            canvas.drawArc(arcBounds2, 135f, 80f, false, patternPaint)
            canvas.drawArc(arcBounds2, 315f, 80f, false, patternPaint)

            // Center glowing node
            canvas.drawCircle(cx, cy, 2.5f * density, iconFillPaint)
        }

        canvas.restore()
    }

    private fun drawSurroundingButton(
        canvas: Canvas,
        type: HubButtonType,
        primaryHex: String,
        secondaryHex: String
    ) {
        val center = getButtonCenterCoords(type)
        val radius = outerButtonRadius
        val isPressed = (touchedButton == type && touchState != TouchState.IDLE)
        val scale = (if (isPressed) buttonScale else 1.0f) * expandProgress
        val alphaInt = (255 * expandProgress).toInt().coerceIn(0, 255)

        if (scale <= 0.05f) return

        canvas.save()
        canvas.scale(scale, scale, center.first, center.second)

        // Glow
        buttonGlowPaint.color = Color.parseColor(primaryHex)
        buttonGlowPaint.alpha = ((if (isPressed) 100 else 40) * expandProgress).toInt()
        canvas.drawCircle(center.first, center.second, radius + (2f * density), buttonGlowPaint)

        // Button background
        buttonBgPaint.color = Color.parseColor("#181F30")
        buttonBgPaint.alpha = alphaInt
        canvas.drawCircle(center.first, center.second, radius, buttonBgPaint)

        // Border
        val borderShader = LinearGradient(
            center.first - radius, center.second - radius,
            center.first + radius, center.second + radius,
            Color.parseColor(primaryHex),
            Color.parseColor(secondaryHex),
            Shader.TileMode.CLAMP
        )
        buttonBorderPaint.shader = borderShader
        buttonBorderPaint.alpha = alphaInt
        canvas.drawCircle(center.first, center.second, radius, buttonBorderPaint)

        // Icon
        iconPaint.alpha = alphaInt
        iconFillPaint.alpha = alphaInt
        drawIconForButton(canvas, type, center.first, center.second, radius)

        canvas.restore()
    }

    private val iconPath = Path()
    private val iconRectF = RectF()

    private fun drawIconForButton(canvas: Canvas, type: HubButtonType, cx: Float, cy: Float, radius: Float) {
        when (type) {
            HubButtonType.MEDIA -> {
                // Musical note
                iconPath.reset()
                val noteHeadR = radius * 0.22f
                val stemH = radius * 0.55f
                val startX = cx - (radius * 0.22f)
                val endX = cx + (radius * 0.26f)
                val bottomY = cy + (radius * 0.24f)

                canvas.drawCircle(startX, bottomY, noteHeadR, iconFillPaint)
                canvas.drawCircle(endX, bottomY - (3f * density), noteHeadR, iconFillPaint)

                iconPaint.strokeWidth = 2f * density
                canvas.drawLine(startX + noteHeadR, bottomY, startX + noteHeadR, bottomY - stemH, iconPaint)
                canvas.drawLine(endX + noteHeadR, bottomY - (3f * density), endX + noteHeadR, bottomY - stemH - (3f * density), iconPaint)
                canvas.drawLine(startX + noteHeadR, bottomY - stemH, endX + noteHeadR, bottomY - stemH - (3f * density), iconPaint)
            }

            HubButtonType.RINGTONE -> {
                // Bell
                iconPath.reset()
                val w = radius * 0.44f
                val h = radius * 0.5f
                val topY = cy - (h * 0.55f)
                val botY = cy + (h * 0.4f)

                iconPath.moveTo(cx, topY)
                iconPath.quadTo(cx + w, topY + (h * 0.45f), cx + w, botY)
                iconPath.lineTo(cx - w, botY)
                iconPath.quadTo(cx - w, topY + (h * 0.45f), cx, topY)
                iconPath.close()

                iconPaint.strokeWidth = 2f * density
                canvas.drawPath(iconPath, iconPaint)
                canvas.drawLine(cx - (w * 1.25f), botY, cx + (w * 1.25f), botY, iconPaint)
                canvas.drawCircle(cx, botY + (3f * density), 2.2f * density, iconFillPaint)
            }

            HubButtonType.NOTIFICATION -> {
                // Chat bubble
                val bw = radius * 0.48f
                val bh = radius * 0.38f
                iconRectF.set(cx - bw, cy - bh, cx + bw, cy + bh)
                iconPaint.strokeWidth = 2f * density
                canvas.drawRoundRect(iconRectF, 5f * density, 5f * density, iconPaint)

                iconPath.reset()
                iconPath.moveTo(cx - (bw * 0.3f), cy + bh)
                iconPath.lineTo(cx - (bw * 0.55f), cy + bh + (4f * density))
                iconPath.lineTo(cx - (bw * 0.05f), cy + bh)
                canvas.drawPath(iconPath, iconPaint)
            }

            HubButtonType.ALARM -> {
                // Alarm clock
                val clockR = radius * 0.40f
                iconPaint.strokeWidth = 2f * density
                canvas.drawCircle(cx, cy + (1.5f * density), clockR, iconPaint)
                canvas.drawLine(cx, cy + (1.5f * density), cx, cy - (clockR * 0.5f), iconPaint)
                canvas.drawLine(cx, cy + (1.5f * density), cx + (clockR * 0.45f), cy + (1.5f * density), iconPaint)

                val bellR = 2.2f * density
                canvas.drawCircle(cx - (clockR * 0.75f), cy - (clockR * 0.75f), bellR, iconFillPaint)
                canvas.drawCircle(cx + (clockR * 0.75f), cy - (clockR * 0.75f), bellR, iconFillPaint)
            }

            HubButtonType.ACTIVE_APP -> {
                // 5th button (Active App volume)
                if (activeAppIcon != null) {
                    val iconSize = (radius * 1.2f).toInt()
                    val srcRect = Rect(0, 0, activeAppIcon!!.width, activeAppIcon!!.height)
                    val dstRect = Rect(
                        (cx - iconSize / 2).toInt(),
                        (cy - iconSize / 2).toInt(),
                        (cx + iconSize / 2).toInt(),
                        (cy + iconSize / 2).toInt()
                    )
                    canvas.drawBitmap(activeAppIcon!!, srcRect, dstRect, null)
                } else {
                    val barW = 2.2f * density
                    val space = 3.2f * density
                    val heights = floatArrayOf(0.35f, 0.65f, 0.95f, 0.55f)
                    val startBarX = cx - (heights.size * space / 2f)

                    iconPaint.strokeWidth = barW
                    for (i in heights.indices) {
                        val bx = startBarX + (i * space)
                        val bh = radius * heights[i]
                        canvas.drawLine(bx, cy - (bh / 2f), bx, cy + (bh / 2f), iconPaint)
                    }
                }
            }
        }
    }
}
