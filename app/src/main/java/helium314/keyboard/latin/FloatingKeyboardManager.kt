package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.content.Intent
import android.net.Uri
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import kotlin.math.max

/**
 * Manages the floating keyboard by reparenting the existing main_keyboard_frame
 * from the IME's InputView into a TYPE_APPLICATION_OVERLAY window.
 *
 * Key sizes are dynamically adjusted by setting a floating width override in
 * ResourceUtils before triggering a keyboard reload.
 *
 * The floating keyboard can be resized by long-pressing one of the four
 * invisible corner hit areas, then dragging freely. Size is stored as a
 * fraction of the current screen dimensions so it scales correctly across
 * orientation changes and different displays.
 */
class FloatingKeyboardManager(private val context: Context, private val latinIME: LatinIME) {

    private enum class ResizeAnchor { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    companion object {
        private const val TAG = "FloatingKeyboardManager"
        private const val PREFS_NAME = "floating_keyboard_prefs"
        private const val PREF_X = "floating_x"
        private const val PREF_Y = "floating_y"
        private const val PREF_WIDTH_FRACTION = "floating_width_fraction"
        private const val PREF_HEIGHT_FRACTION = "floating_height_fraction"
        private const val PREF_RESIZE_HINT_SHOWN = "floating_resize_hint_shown"
        private const val DEFAULT_WIDTH_FRACTION = 0.75f
        private const val DEFAULT_HEIGHT_FRACTION = 0.4f
        private const val MIN_WIDTH_FRACTION = 0.4f
        private const val MAX_WIDTH_FRACTION = 1.0f
        private const val MIN_HEIGHT_FRACTION = 0.25f
        private const val MAX_HEIGHT_FRACTION = 0.85f
        private const val MIN_WIDTH_DP = 200
        private const val MIN_HEIGHT_DP = 160
        private const val HEADER_HEIGHT_DP = 28
        private const val CORNER_RADIUS_DP = 16f
        private const val CORNER_HIT_AREA_SIZE_DP = 40
        private const val RESIZE_HANDLE_THICKNESS_DP = 2
        private const val RESIZE_HANDLE_LENGTH_DP = 20
        private const val RESIZE_HANDLE_MARGIN_DP = 6
        private const val RESIZE_MODE_FADE_DURATION_MS = 120L
        private const val RESIZE_OUTLINE_ALPHA = 0x40
        private const val RESIZE_HANDLE_ALPHA = 0x80
    }

    private val prefs: SharedPreferences by lazy {
        DeviceProtectedUtils.getSharedPreferences(context, PREFS_NAME)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    var overlayRoot: FrameLayout? = null
        private set
    private var windowManager: WindowManager? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var savedParent: ViewGroup? = null
    private var savedLayoutParams: ViewGroup.LayoutParams? = null
    private var savedParentIndex: Int = -1

    // Touch tracking for drag
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // Resize state. Activated by long-press on a corner hit area; while active
    // the user's finger can roam freely and both axes resize independently.
    private var resizeMode = false
    private var resizeAnchor: ResizeAnchor = ResizeAnchor.TOP_LEFT
    private var resizeInitialWidth = 0
    private var resizeInitialHeight = 0
    private var resizeInitialX = 0
    private var resizeInitialY = 0
    private var resizeInitialTouchX = 0f
    private var resizeInitialTouchY = 0f
    private var longPressRunnable: Runnable? = null
    private val touchSlopPx by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var activeCornerView: View? = null

    private val visibleCornerHandles = mutableMapOf<ResizeAnchor, View>()
    private var resizeOutlineView: View? = null

    // Current width and height fractions of the screen dimensions (clamped to
    // [MIN, MAX]). Persisted across configuration changes and floating-mode sessions.
    private var widthFraction: Float = DEFAULT_WIDTH_FRACTION
    private var heightFraction: Float = DEFAULT_HEIGHT_FRACTION

    var isFloating = false
        private set

    fun canDrawOverlays(): Boolean = AndroidSettings.canDrawOverlays(context)

    fun requestOverlayPermission() {
        val intent = Intent(
            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (!canDrawOverlays() || isFloating) return

        // Load the persisted size fractions (or use the defaults).
        widthFraction = prefs.getFloat(PREF_WIDTH_FRACTION, DEFAULT_WIDTH_FRACTION)
            .coerceIn(MIN_WIDTH_FRACTION, MAX_WIDTH_FRACTION)
        heightFraction = prefs.getFloat(PREF_HEIGHT_FRACTION, DEFAULT_HEIGHT_FRACTION)
            .coerceIn(MIN_HEIGHT_FRACTION, MAX_HEIGHT_FRACTION)

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Calculate the floating keyboard size
        val dm = context.resources.displayMetrics
        val floatingWidth = calculateFloatingWidth(dm.widthPixels)
        val floatingHeight = calculateFloatingHeight(dm.heightPixels)

        // Get theme colors
        val colors = Settings.getValues().mColors
        val bgColor = colors.get(ColorType.MAIN_BACKGROUND)
        val textColor = colors.get(ColorType.KEY_TEXT)
        val density = dm.density
        val cornerRadius = CORNER_RADIUS_DP * density
        val headerHeight = (HEADER_HEIGHT_DP * density).toInt()

        // Create the overlay root with rounded corners and clipping
        val overlayBg = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            this.cornerRadius = cornerRadius
        }
        overlayRoot = FrameLayout(context).apply {
            background = overlayBg
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        // Create header bar with theme-matching colors and rounded top corners
        val headerBar = createHeaderBar(headerHeight, bgColor, textColor, density, cornerRadius)

        // Build content: header on top, keyboard below
        val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Rounded corner background on the container
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadii = floatArrayOf(
                    cornerRadius, cornerRadius,   // top-left
                    cornerRadius, cornerRadius,   // top-right
                    cornerRadius, cornerRadius,   // bottom-right
                    cornerRadius, cornerRadius    // bottom-left
                )
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        contentContainer.addView(headerBar)
        overlayRoot!!.addView(contentContainer)

        // Outline view drawn on top of the keyboard when resize mode is active.
        // Kept at 0 alpha until enterResizeMode().
        resizeOutlineView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
                setStroke(
                    (1 * density).toInt().coerceAtLeast(1),
                    textColor and 0x00FFFFFF or RESIZE_OUTLINE_ALPHA.shl(24)
                )
                this.cornerRadius = cornerRadius
            }
            alpha = 0f
            isClickable = false
            isFocusable = false
        }
        overlayRoot!!.addView(resizeOutlineView)

        // Four invisible corner hit areas + four visible L-brackets. Hit areas
        // are children of overlayRoot (siblings of the content container), so
        // they sit on top of everything in z-order. overlayRoot has
        // clipToOutline = true, so the visible L-brackets are clipped to the
        // rounded corners.
        ResizeAnchor.values().forEach { corner ->
            val hitArea = createCornerHitArea(corner, density)
            overlayRoot!!.addView(hitArea)
            val handle = createVisibleCornerHandle(corner, density, textColor)
            handle.alpha = 0f
            overlayRoot!!.addView(handle)
            visibleCornerHandles[corner] = handle
        }

        // Calculate window position
        val savedX = prefs.getInt(PREF_X, -1)
        val savedY = prefs.getInt(PREF_Y, -1)

        windowParams = WindowManager.LayoutParams(
            floatingWidth,
            floatingHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (savedX != -1 && savedY != -1) {
                x = savedX
                y = savedY
            } else {
                // Center horizontally, near bottom third
                x = (dm.widthPixels - floatingWidth) / 2
                y = (dm.heightPixels - floatingHeight) / 3
            }
        }

        try {
            windowManager?.addView(overlayRoot, windowParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            overlayRoot = null
            return
        }

        isFloating = true

        // Manually trigger reparenting of the current input view into the overlay.
        // reloadKeyboard() alone won't trigger setInputView() if the theme hasn't changed.
        latinIME.mInputView?.let { onInputViewRecreated(it) }

        // Set the floating width override so keyboard keys re-measure at this width
        ResourceUtils.setFloatingKeyboardWidth(floatingWidth)

        // Force keyboard reload so keys re-measure at the new width
        // This will trigger onInputViewRecreated which reparents the NEW keyboard into our overlay
        KeyboardSwitcher.getInstance().reloadKeyboard()

        // Hide the IME window so the bottom nav bar goes away
        latinIME.onFloatingKeyboardShown()

        // One-time hint so users discover the long-press-to-resize gesture.
        showResizeHintIfFirstTime()

        Log.i(TAG, "Floating keyboard shown at ${floatingWidth}x${floatingHeight}px")
    }

    fun hide(showDockedKeyboard: Boolean = true) {
        if (!isFloating) return

        // Cancel any pending long-press and clear resize state
        cancelLongPress()
        resizeMode = false
        visibleCornerHandles.clear()
        resizeOutlineView = null
        activeCornerView = null

        // Clear the floating width override FIRST
        ResourceUtils.setFloatingKeyboardWidth(0)

        val mainKeyboardFrame = overlayRoot?.findViewById<View>(R.id.main_keyboard_frame)
        if (mainKeyboardFrame != null) {
            // Remove from overlay content container so it can be safely GC'd
            (mainKeyboardFrame.parent as? ViewGroup)?.removeView(mainKeyboardFrame)
        }

        // Remove overlay window
        overlayRoot?.let { root ->
            try {
                windowManager?.removeView(root)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove overlay view", e)
            }
        }
        overlayRoot = null
        savedParent = null
        savedLayoutParams = null
        savedParentIndex = -1
        isFloating = false

        // Clear any sticky modifier state on the utility bar so a locked
        // Ctrl doesn't fire on the next character typed into the docked
        // keyboard.
        latinIME.utilityKeyBar?.clearState()

        // Show the IME window again
        latinIME.onFloatingKeyboardHidden(showDockedKeyboard)

        // Reload keyboard at full width so keys re-measure properly
        KeyboardSwitcher.getInstance().reloadKeyboard()

        Log.i(TAG, "Floating keyboard hidden, docked mode restored")
    }

    fun toggle() {
        if (isFloating) {
            hide()
        } else {
            if (canDrawOverlays()) {
                show()
            } else {
                requestOverlayPermission()
            }
        }
    }

    /**
     * Called from LatinIME.setInputView() when the input view is recreated
     * (e.g., theme change, orientation change). If floating mode is active,
     * we need to reparent the new keyboard views into the existing overlay.
     *
     * Also handles resizing the floating overlay to match the current display
     * width. The width is recalculated from the current display metrics so
     * that configuration changes (e.g. landscape -> portrait) cause the
     * overlay window and the reparented keyboard to scale down/up to match
     * the new screen size.
     */
    fun onInputViewRecreated(newInputView: View) {
        if (!isFloating) return

        Log.i(TAG, "Input view recreated while floating, re-reparenting keyboard")

        val newMainKeyboardFrame = newInputView.findViewById<View>(R.id.main_keyboard_frame)
            ?: return
        val newParent = newMainKeyboardFrame.parent as? ViewGroup ?: return

        // Save new parent info
        savedParent = newParent
        savedLayoutParams = newMainKeyboardFrame.layoutParams
        savedParentIndex = newParent.indexOfChild(newMainKeyboardFrame)

        // Find the content container in our overlay (the LinearLayout)
        val contentContainer = overlayRoot?.getChildAt(0) as? LinearLayout ?: return

        // Remove old keyboard frame from overlay content container (index 1, after header)
        if (contentContainer.childCount > 1) {
            contentContainer.removeViewAt(1)
        }

        // Recalculate the floating size from the current display metrics so the
        // overlay window and the keyboard frame match the current screen
        // (e.g. after an orientation change landscape -> portrait). The
        // user-configured fractions are preserved across rotations.
        val dm = context.resources.displayMetrics
        val newFloatingWidth = calculateFloatingWidth(dm.widthPixels)
        val newFloatingHeight = calculateFloatingHeight(dm.heightPixels)
        val previousFloatingWidth = ResourceUtils.getFloatingKeyboardWidth()
        val previousFloatingHeight = windowParams?.height ?: 0
        val widthChanged = newFloatingWidth != previousFloatingWidth
        val heightChanged = newFloatingHeight != previousFloatingHeight
        if (widthChanged || heightChanged) {
            Log.i(TAG, "Floating keyboard size changed: ${previousFloatingWidth}x$previousFloatingHeight -> ${newFloatingWidth}x$newFloatingHeight")
            if (widthChanged) {
                ResourceUtils.setFloatingKeyboardWidth(newFloatingWidth)
            }
            windowParams?.let { lp ->
                lp.width = newFloatingWidth
                lp.height = newFloatingHeight
                try {
                    windowManager?.updateViewLayout(overlayRoot, lp)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update overlay size on input view recreation", e)
                }
            }
        }

        // Reparent new keyboard frame at the (possibly updated) floating size
        newParent.removeView(newMainKeyboardFrame)
        newMainKeyboardFrame.layoutParams = LinearLayout.LayoutParams(
            if (newFloatingWidth > 0) newFloatingWidth else LinearLayout.LayoutParams.MATCH_PARENT,
            if (newFloatingHeight > 0) newFloatingHeight else LinearLayout.LayoutParams.WRAP_CONTENT
        )
        contentContainer.addView(newMainKeyboardFrame)

        // If the floating size changed, reload the keyboard so the keys re-measure.
        if (widthChanged || heightChanged) {
            KeyboardSwitcher.getInstance().reloadKeyboard()
        }
    }

    /**
     * Called from LatinIME.onDestroy() to clean up.
     */
    fun destroy() {
        if (isFloating) {
            ResourceUtils.setFloatingKeyboardWidth(0)
            overlayRoot?.let { root ->
                try {
                    windowManager?.removeView(root)
                } catch (_: Exception) {}
            }
            overlayRoot = null
            isFloating = false
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private fun calculateFloatingWidth(screenWidthPx: Int): Int =
        (screenWidthPx * widthFraction).toInt()

    private fun calculateFloatingHeight(screenHeightPx: Int): Int =
        (screenHeightPx * heightFraction).toInt()

    private fun calculateMinWidth(dm: DisplayMetrics): Int {
        val minDpPx = (MIN_WIDTH_DP * dm.density).toInt()
        val minFractionPx = (dm.widthPixels * MIN_WIDTH_FRACTION).toInt()
        return max(minDpPx, minFractionPx)
    }

    private fun calculateMinHeight(dm: DisplayMetrics): Int {
        val minDpPx = (MIN_HEIGHT_DP * dm.density).toInt()
        val minFractionPx = (dm.heightPixels * MIN_HEIGHT_FRACTION).toInt()
        return max(minDpPx, minFractionPx)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun showResizeHintIfFirstTime() {
        if (prefs.getBoolean(PREF_RESIZE_HINT_SHOWN, false)) return
        prefs.edit().putBoolean(PREF_RESIZE_HINT_SHOWN, true).apply()
        val toast = Toast.makeText(
            context,
            R.string.floating_keyboard_resize_hint,
            Toast.LENGTH_LONG
        )
        val lp = windowParams
        if (lp != null) {
            toast.setGravity(
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                0,
                lp.y + lp.height - (32 * context.resources.displayMetrics.density).toInt()
            )
        }
        toast.show()
    }

    /**
     * Enter resize mode for the given corner. The opposite corner becomes the
     * anchor (stays fixed). Subsequent ACTION_MOVE events on the same touch
     * gesture drive a free two-axis resize.
     */
    private fun enterResizeMode(corner: ResizeAnchor) {
        if (!isFloating) return
        val lp = windowParams ?: return
        resizeMode = true
        resizeAnchor = corner
        resizeInitialWidth = lp.width
        resizeInitialHeight = lp.height
        resizeInitialX = lp.x
        resizeInitialY = lp.y
        // Anchor on the screen is the opposite corner of the press; compute it
        // so we can clamp resize deltas against the screen edges the user
        // would otherwise run off.
        latinIME.vibrateOnResizeModeEnter()
        showVisibleResizeHandles()
    }

    /**
     * Exit resize mode. If persist is true, the new size is written to prefs.
     */
    private fun exitResizeMode(persist: Boolean) {
        if (!resizeMode) return
        resizeMode = false
        activeCornerView = null
        hideVisibleResizeHandles()
        if (persist) {
            val dm = context.resources.displayMetrics
            val lp = windowParams
            if (lp != null) {
                val newW = (lp.width.toFloat() / dm.widthPixels)
                    .coerceIn(MIN_WIDTH_FRACTION, MAX_WIDTH_FRACTION)
                val newH = (lp.height.toFloat() / dm.heightPixels)
                    .coerceIn(MIN_HEIGHT_FRACTION, MAX_HEIGHT_FRACTION)
                if (newW != widthFraction) widthFraction = newW
                if (newH != heightFraction) heightFraction = newH
                prefs.edit()
                    .putFloat(PREF_WIDTH_FRACTION, widthFraction)
                    .putFloat(PREF_HEIGHT_FRACTION, heightFraction)
                    .apply()
                savePosition()
            }
        }
    }

    private fun showVisibleResizeHandles() {
        val outline = resizeOutlineView ?: return
        outline.animate().cancel()
        outline.animate().alpha(1f).setDuration(RESIZE_MODE_FADE_DURATION_MS).start()
        visibleCornerHandles.values.forEach { handle ->
            handle.animate().cancel()
            handle.animate().alpha(1f).setDuration(RESIZE_MODE_FADE_DURATION_MS).start()
        }
    }

    private fun hideVisibleResizeHandles() {
        val outline = resizeOutlineView
        outline?.animate()?.cancel()
        outline?.animate()?.alpha(0f)?.setDuration(RESIZE_MODE_FADE_DURATION_MS)?.start()
        visibleCornerHandles.values.forEach { handle ->
            handle.animate().cancel()
            handle.animate().alpha(0f).setDuration(RESIZE_MODE_FADE_DURATION_MS).start()
        }
    }

    /**
     * Apply a new size to the floating keyboard overlay and reparented
     * keyboard, anchored on the corner opposite to which the user pressed.
     * The opposite corner stays fixed; the pressed corner moves to satisfy
     * the new width/height. Both axes are clamped independently against
     * minimum size and the screen edges. The size fractions are not persisted
     * here — exitResizeMode() handles that on ACTION_UP.
     */
    private fun resizeTo(newWidth: Int, newHeight: Int, anchor: ResizeAnchor) {
        if (!isFloating) return
        val dm = context.resources.displayMetrics
        val currentLp = windowParams ?: return
        val minWidth = calculateMinWidth(dm)
        val minHeight = calculateMinHeight(dm)

        // Maximum dimensions are bounded by where the anchored edges sit on
        // the screen. If the anchored left edge is at x=0 the keyboard can be
        // at most screenWidth wide; if at x=screenWidth it can be 0 wide.
        val maxWidth = when (anchor) {
            ResizeAnchor.TOP_LEFT, ResizeAnchor.BOTTOM_LEFT ->
                dm.widthPixels - resizeInitialX
            ResizeAnchor.TOP_RIGHT, ResizeAnchor.BOTTOM_RIGHT ->
                resizeInitialX + resizeInitialWidth
        }
        val maxHeight = when (anchor) {
            ResizeAnchor.TOP_LEFT, ResizeAnchor.TOP_RIGHT ->
                dm.heightPixels - resizeInitialY
            ResizeAnchor.BOTTOM_LEFT, ResizeAnchor.BOTTOM_RIGHT ->
                resizeInitialY + resizeInitialHeight
        }
        val clampedWidth = newWidth.coerceIn(minWidth, maxWidth.coerceAtLeast(minWidth))
        val clampedHeight = newHeight.coerceIn(minHeight, maxHeight.coerceAtLeast(minHeight))

        val newX: Int = when (anchor) {
            ResizeAnchor.TOP_LEFT, ResizeAnchor.BOTTOM_LEFT -> resizeInitialX
            ResizeAnchor.TOP_RIGHT, ResizeAnchor.BOTTOM_RIGHT ->
                resizeInitialX + resizeInitialWidth - clampedWidth
        }
        val newY: Int = when (anchor) {
            ResizeAnchor.TOP_LEFT, ResizeAnchor.TOP_RIGHT -> resizeInitialY
            ResizeAnchor.BOTTOM_LEFT, ResizeAnchor.BOTTOM_RIGHT ->
                resizeInitialY + resizeInitialHeight - clampedHeight
        }

        if (clampedWidth == currentLp.width && clampedHeight == currentLp.height &&
            newX == currentLp.x && newY == currentLp.y) return

        ResourceUtils.setFloatingKeyboardWidth(clampedWidth)
        currentLp.width = clampedWidth
        currentLp.height = clampedHeight
        currentLp.x = newX
        currentLp.y = newY
        try {
            windowManager?.updateViewLayout(overlayRoot, currentLp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update overlay layout during resize", e)
        }

        // Update keyboard frame layout so it fills the new size.
        val contentContainer = overlayRoot?.getChildAt(0) as? LinearLayout
        val mainKeyboardFrame = contentContainer?.findViewById<View>(R.id.main_keyboard_frame)
        if (mainKeyboardFrame != null) {
            mainKeyboardFrame.layoutParams = LinearLayout.LayoutParams(
                clampedWidth,
                clampedHeight - (HEADER_HEIGHT_DP * dm.density).toInt()
            )
            contentContainer.requestLayout()
        }

        // Reload keyboard so keys re-measure at the new size. Same
        // approach as one-handed mode resize: not great for performance
        // on every move event, but good enough and ensures correctness.
        KeyboardSwitcher.getInstance().reloadKeyboard()
    }

    /**
     * Build an invisible touch target anchored at the given corner. On
     * long-press the touch sequence transitions into resize mode. The
     * touchable area is intentionally larger than the visible L-bracket
     * so corners feel forgiving.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createCornerHitArea(corner: ResizeAnchor, density: Float): View {
        val size = (CORNER_HIT_AREA_SIZE_DP * density).toInt()
        val gravity = when (corner) {
            ResizeAnchor.TOP_LEFT -> Gravity.TOP or Gravity.START
            ResizeAnchor.TOP_RIGHT -> Gravity.TOP or Gravity.END
            ResizeAnchor.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            ResizeAnchor.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
        }
        val area = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                this.gravity = gravity
            }
            isClickable = true
            isFocusable = false
            contentDescription = context.getString(R.string.floating_keyboard_resize_corner_description)
        }

        var downX = 0f
        var downY = 0f

        area.setOnTouchListener { _, event ->
            if (windowParams == null) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    activeCornerView = area
                    val runnable = Runnable {
                        if (activeCornerView === area && !resizeMode) {
                            enterResizeMode(corner)
                            resizeInitialTouchX = event.rawX
                            resizeInitialTouchY = event.rawY
                        }
                    }
                    longPressRunnable = runnable
                    mainHandler.postDelayed(runnable, longPressTimeoutMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (resizeMode) {
                        val dx = event.rawX - resizeInitialTouchX
                        val dy = event.rawY - resizeInitialTouchY
                        resizeTo(
                            resizeInitialWidth + dx.toInt(),
                            resizeInitialHeight + dy.toInt(),
                            resizeAnchor
                        )
                        true
                    } else {
                        val totalDx = event.rawX - downX
                        val totalDy = event.rawY - downY
                        if (totalDx * totalDx + totalDy * totalDy > touchSlopPx * touchSlopPx) {
                            cancelLongPress()
                            activeCornerView = null
                            false
                        } else {
                            true
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPress()
                    if (resizeMode) {
                        exitResizeMode(persist = true)
                    }
                    activeCornerView = null
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    if (resizeMode) {
                        exitResizeMode(persist = false)
                    }
                    activeCornerView = null
                    true
                }
                else -> false
            }
        }
        return area
    }

    /**
     * Build the decorative L-bracket for a corner. Shown only when resize
     * mode is active. Two thin Views inside a FrameLayout form the L.
     */
    private fun createVisibleCornerHandle(corner: ResizeAnchor, density: Float, textColor: Int): View {
        val length = (RESIZE_HANDLE_LENGTH_DP * density).toInt()
        val thickness = (RESIZE_HANDLE_THICKNESS_DP * density).toInt().coerceAtLeast(1)
        val margin = (RESIZE_HANDLE_MARGIN_DP * density).toInt()
        val handleColor = textColor and 0x00FFFFFF or (RESIZE_HANDLE_ALPHA.shl(24))

        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(length, length).apply {
                gravity = when (corner) {
                    ResizeAnchor.TOP_LEFT -> Gravity.TOP or Gravity.START
                    ResizeAnchor.TOP_RIGHT -> Gravity.TOP or Gravity.END
                    ResizeAnchor.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
                    ResizeAnchor.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
                }
                if (corner == ResizeAnchor.TOP_LEFT || corner == ResizeAnchor.BOTTOM_LEFT) {
                    marginStart = margin
                } else {
                    marginEnd = margin
                }
                if (corner == ResizeAnchor.TOP_LEFT || corner == ResizeAnchor.TOP_RIGHT) {
                    topMargin = margin
                } else {
                    bottomMargin = margin
                }
            }
            isClickable = false
            isFocusable = false
        }

        val barGravity = when (corner) {
            ResizeAnchor.TOP_LEFT -> Gravity.START or Gravity.TOP
            ResizeAnchor.TOP_RIGHT -> Gravity.END or Gravity.TOP
            ResizeAnchor.BOTTOM_LEFT -> Gravity.START or Gravity.BOTTOM
            ResizeAnchor.BOTTOM_RIGHT -> Gravity.END or Gravity.BOTTOM
        }

        // Vertical bar of the L
        container.addView(View(context).apply {
            layoutParams = FrameLayout.LayoutParams(thickness, length).apply {
                gravity = barGravity
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = thickness / 2f
                setColor(handleColor)
            }
        })
        // Horizontal bar of the L
        container.addView(View(context).apply {
            layoutParams = FrameLayout.LayoutParams(length, thickness).apply {
                gravity = barGravity
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = thickness / 2f
                setColor(handleColor)
            }
        })
        return container
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createHeaderBar(height: Int, bgColor: Int, textColor: Int, density: Float, cornerRadius: Float): FrameLayout {
        // Header with rounded top corners matching the container
        val headerBar = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
            )
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadii = floatArrayOf(
                    cornerRadius, cornerRadius,   // top-left
                    cornerRadius, cornerRadius,   // top-right
                    0f, 0f,                       // bottom-right
                    0f, 0f                        // bottom-left
                )
            }
        }

        // Drag handle pill in center
        val pillWidth = (40 * density).toInt()
        val pillHeight = (4 * density).toInt()
        val dragHandle = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(pillWidth, pillHeight).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 3 * density
                setColor(textColor and 0x00FFFFFF or 0x55000000) // 33% alpha
            }
            contentDescription = context.getString(R.string.floating_keyboard_drag_handle)
        }
        headerBar.addView(dragHandle)

        // Close button — styled like a toolbar key (rounded, subtle background)
        val closeBtnSize = (height * 0.75f).toInt()
        val closePadding = (3 * density).toInt()
        val closeBtn = ImageButton(context).apply {
            layoutParams = FrameLayout.LayoutParams(closeBtnSize, closeBtnSize).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                marginEnd = (6 * density).toInt()
            }
            setPadding(closePadding, closePadding, closePadding, closePadding)
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(textColor)
            // Toolbar-style background: subtle rounded rectangle
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = 6 * density
                setColor(textColor and 0x00FFFFFF or 0x1A000000) // 10% alpha — subtle
            }
            contentDescription = "Close floating keyboard"
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setOnClickListener { toggle() }
        }
        headerBar.addView(closeBtn)

        // Setup drag on the entire header bar — allows free movement in both axes
        headerBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    windowParams?.let { lp ->
                        lp.x = (lp.x + dx).toInt()
                        lp.y = (lp.y + dy).toInt()
                        try {
                            windowManager?.updateViewLayout(overlayRoot, lp)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update overlay layout", e)
                        }
                    }
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePosition()
                    true
                }
                else -> false
            }
        }

        return headerBar
    }

    private fun savePosition() {
        windowParams?.let { lp ->
            prefs.edit()
                .putInt(PREF_X, lp.x)
                .putInt(PREF_Y, lp.y)
                .apply()
        }
    }
}
