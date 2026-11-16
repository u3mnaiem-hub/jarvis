package com.voiceagent.automation.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.voiceagent.automation.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating, draggable "Stop Recording" button shown on top of every app while a macro
 * recording is active.
 *
 * It is drawn by [VoiceAccessibilityService] through WindowManager using
 * TYPE_ACCESSIBILITY_OVERLAY, so no SYSTEM_ALERT_WINDOW permission is required.
 *
 * The tap is detected with an OnTouchListener (never a click action), so the button does
 * not produce TYPE_VIEW_CLICKED events. All calls must be made on the main thread.
 */
class RecordingOverlayManager(
    private val service: AccessibilityService,
    private val onStopTapped: (tapDownTimeMs: Long) -> Unit
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = service.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** Accessibility window id of the overlay (or the last overlay), -1 if unknown. */
    @Volatile
    var overlayWindowId: Int = NO_WINDOW_ID
        private set

    /** True while a finger is down on the overlay (tap or drag in progress). */
    @Volatile
    var isTouchInProgress: Boolean = false
        private set

    val isShowing: Boolean
        get() = overlayView != null

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    fun show() {
        if (overlayView != null) return

        val view = try {
            LayoutInflater.from(service).inflate(R.layout.overlay_stop_recording, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inflate recording overlay: ${e.message}")
            return
        }
        view.contentDescription = OVERLAY_CONTENT_DESCRIPTION
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        view.isClickable = false
        view.isLongClickable = false
        view.isFocusable = false

        val sizePx = buttonSizePx()
        val (x, y) = restoredPosition(sizePx)
        val lp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            title = OVERLAY_WINDOW_TITLE
        }

        view.setOnTouchListener(DragOrTapTouchListener())

        try {
            windowManager.addView(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add recording overlay: ${e.message}")
            return
        }

        overlayView = view
        layoutParams = lp
        overlayWindowId = NO_WINDOW_ID
        // The accessibility window list is updated asynchronously after addView.
        view.post { resolveOverlayWindowId() }
        view.postDelayed({ resolveOverlayWindowId() }, 300L)
        Log.i(TAG, "Recording overlay shown at ($x, $y)")
    }

    fun hide() {
        val view = overlayView ?: return
        overlayView = null
        layoutParams = null
        isTouchInProgress = false
        view.setOnTouchListener(null)
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove recording overlay: ${e.message}")
        }
        // overlayWindowId is intentionally kept so late events from the removed window are still filtered.
        Log.i(TAG, "Recording overlay removed")
    }

    /** Called on rotation / screen size change: keep the button inside the visible screen. */
    fun onConfigurationChanged() {
        val view = overlayView ?: return
        applyClampedSavedPosition(view)
        // Display metrics can settle slightly after the configuration callback; re-check once.
        view.postDelayed({ overlayView?.let { applyClampedSavedPosition(it) } }, 250L)
    }

    private fun applyClampedSavedPosition(view: View) {
        val lp = layoutParams ?: return
        val (x, y) = restoredPosition(lp.width)
        lp.x = x
        lp.y = y
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update overlay layout: ${e.message}")
        }
    }

    private fun resolveOverlayWindowId() {
        val view = overlayView ?: return
        try {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val viewRect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
            val overlays = service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            val match = overlays.firstOrNull { it.title?.toString() == OVERLAY_WINDOW_TITLE }
                ?: overlays.firstOrNull {
                    val r = Rect()
                    it.getBoundsInScreen(r)
                    !viewRect.isEmpty && Rect.intersects(r, viewRect)
                }
                ?: overlays.singleOrNull()
            if (match != null) {
                overlayWindowId = match.id
                Log.d(TAG, "Recording overlay window id = ${match.id}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve overlay window id: ${e.message}")
        }
    }

    // ---------- Position persistence (stored as fractions so it survives rotation) ----------

    private fun restoredPosition(sizePx: Int): Pair<Int, Int> {
        val (w, h) = screenSizePx()
        val maxX = (w - sizePx).coerceAtLeast(0)
        val maxY = (h - sizePx).coerceAtLeast(0)
        val fx = prefs.getFloat(KEY_X_FRACTION, DEFAULT_X_FRACTION).coerceIn(0f, 1f)
        val fy = prefs.getFloat(KEY_Y_FRACTION, DEFAULT_Y_FRACTION).coerceIn(0f, 1f)
        return (fx * maxX).roundToInt().coerceIn(0, maxX) to (fy * maxY).roundToInt().coerceIn(0, maxY)
    }

    private fun savePosition(x: Int, y: Int, sizePx: Int) {
        val (w, h) = screenSizePx()
        val maxX = (w - sizePx).coerceAtLeast(1)
        val maxY = (h - sizePx).coerceAtLeast(1)
        prefs.edit()
            .putFloat(KEY_X_FRACTION, (x.toFloat() / maxX).coerceIn(0f, 1f))
            .putFloat(KEY_Y_FRACTION, (y.toFloat() / maxY).coerceIn(0f, 1f))
            .apply()
    }

    @Suppress("DEPRECATION")
    private fun screenSizePx(): Pair<Int, Int> {
        return try {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            if (dm.widthPixels > 0 && dm.heightPixels > 0) dm.widthPixels to dm.heightPixels
            else service.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        } catch (_: Exception) {
            service.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
    }

    private fun buttonSizePx(): Int =
        (BUTTON_SIZE_DP * service.resources.displayMetrics.density).roundToInt()

    // ---------- Touch handling: drag to move, single tap to stop ----------

    private inner class DragOrTapTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var downTimeMs = 0L
        private var isDragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val lp = layoutParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isTouchInProgress = true
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    downTimeMs = System.currentTimeMillis()
                    isDragging = false
                    v.alpha = 1f
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val (w, h) = screenSizePx()
                        lp.x = (startX + dx.roundToInt()).coerceIn(0, (w - lp.width).coerceAtLeast(0))
                        lp.y = (startY + dy.roundToInt()).coerceIn(0, (h - lp.height).coerceAtLeast(0))
                        try {
                            windowManager.updateViewLayout(v, lp)
                        } catch (_: Exception) {
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = IDLE_ALPHA
                    if (isDragging) {
                        savePosition(lp.x, lp.y, lp.width)
                        isTouchInProgress = false
                    } else {
                        // Single tap. Handled on the next main-loop turn so the overlay window is not
                        // removed while it is still dispatching this touch event. isTouchInProgress stays
                        // true until then, so no event can slip into the recording in between; the service
                        // sets isStoppingFromOverlay as the very first thing it does.
                        val tapTime = downTimeMs
                        mainHandler.post {
                            onStopTapped(tapTime)
                            isTouchInProgress = false
                        }
                    }
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = IDLE_ALPHA
                    if (isDragging) savePosition(lp.x, lp.y, lp.width)
                    isDragging = false
                    isTouchInProgress = false
                    return true
                }
            }
            return false
        }
    }

    companion object {
        private const val TAG = "RecordingOverlay"
        const val NO_WINDOW_ID = -1
        private const val PREFS_NAME = "jarvis_recording_overlay"
        private const val KEY_X_FRACTION = "overlay_x_fraction"
        private const val KEY_Y_FRACTION = "overlay_y_fraction"
        private const val DEFAULT_X_FRACTION = 1f      // right edge
        private const val DEFAULT_Y_FRACTION = 0.35f   // upper third
        private const val BUTTON_SIZE_DP = 48f
        private const val IDLE_ALPHA = 0.85f

        /** Unique identifiers used to recognise (and never record) the overlay. */
        const val OVERLAY_CONTENT_DESCRIPTION = "jarvis_overlay_stop_recording_button"
        const val OVERLAY_VIEW_ID_NAME = "jarvis_overlay_stop_button"
        const val OVERLAY_VIEW_ID_SUFFIX = ":id/$OVERLAY_VIEW_ID_NAME"
        const val OVERLAY_WINDOW_TITLE = "JarvisRecordingStopOverlay"

        fun isOverlayResourceId(resId: String?): Boolean =
            resId != null && (resId.endsWith(OVERLAY_VIEW_ID_SUFFIX) || resId == OVERLAY_VIEW_ID_NAME)

        fun isOverlayContentDescription(desc: CharSequence?): Boolean =
            desc != null && desc.toString() == OVERLAY_CONTENT_DESCRIPTION

        fun isOverlayNode(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            return isOverlayResourceId(node.viewIdResourceName) ||
                isOverlayContentDescription(node.contentDescription)
        }
    }
}
