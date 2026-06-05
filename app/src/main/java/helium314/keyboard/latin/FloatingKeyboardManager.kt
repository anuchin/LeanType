package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.content.Intent
import android.net.Uri
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
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
 * The floating keyboard can be resized by the user via drag handles on the
 * bottom corners of the overlay. The size is stored as a fraction of the
 * current screen width so it scales correctly across orientation changes
 * and different displays.
 */
class FloatingKeyboardManager(private val context: Context, private val latinIME: LatinIME) {

    companion object {
        private const val TAG = "FloatingKeyboardManager"
        private const val PREFS_NAME = "floating_keyboard_prefs"
        private const val PREF_X = "floating_x"
        private const val PREF_Y = "floating_y"
        private const val PREF_WIDTH_FRACTION = "floating_width_fraction"
        private const val DEFAULT_WIDTH_FRACTION = 0.75f
        private const val MIN_WIDTH_FRACTION = 0.4f
        private const val MAX_WIDTH_FRACTION = 1.0f
        private const val MIN_WIDTH_DP = 200
        private const val HEADER_HEIGHT_DP = 28
        private const val CORNER_RADIUS_DP = 16f
        private const val RESIZE_HANDLE_SIZE_DP = 28
        private const val RESIZE_HANDLE_MARGIN_DP = 4
    }

    private val prefs: SharedPreferences by lazy {
        DeviceProtectedUtils.getSharedPreferences(context, PREFS_NAME)
    }

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

    // Touch tracking for resize
    private var resizeInitialWidth = 0
    private var resizeInitialWindowX = 0
    private var resizeInitialWindowRight = 0

    // Current width fraction of the screen width (clamped to [MIN, MAX]).
    // Persisted across configuration changes and floating mode sessions.
    private var widthFraction: Float = DEFAULT_WIDTH_FRACTION

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

        // Load the persisted width fraction (or use the default).
        widthFraction = prefs.getFloat(PREF_WIDTH_FRACTION, DEFAULT_WIDTH_FRACTION)
            .coerceIn(MIN_WIDTH_FRACTION, MAX_WIDTH_FRACTION)

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Calculate the floating keyboard width
        val dm = context.resources.displayMetrics
        val floatingWidth = calculateFloatingWidth(dm.widthPixels)

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
                FrameLayout.LayoutParams.WRAP_CONTENT
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

        // Add resize handles on the bottom corners of the overlay. They are
        // children of overlayRoot (siblings of the content container) and
        // overlap the bottom corners of the keyboard. overlayRoot has
        // clipToOutline = true, so they are clipped to the rounded corners.
        val resizeHandleLeft = createResizeHandle(density, textColor, isLeft = true)
        val resizeHandleRight = createResizeHandle(density, textColor, isLeft = false)
        overlayRoot!!.addView(resizeHandleLeft)
        overlayRoot!!.addView(resizeHandleRight)

        // Calculate window position
        val savedX = prefs.getInt(PREF_X, -1)
        val savedY = prefs.getInt(PREF_Y, -1)

        windowParams = WindowManager.LayoutParams(
            floatingWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
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
                y = dm.heightPixels / 3
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

        Log.i(TAG, "Floating keyboard shown at ${floatingWidth}px width")
    }

    fun hide(showDockedKeyboard: Boolean = true) {
        if (!isFloating) return

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

        // Recalculate the floating width from the current display metrics so the
        // overlay window and the keyboard frame match the current screen size
        // (e.g. after an orientation change landscape -> portrait). The
        // user-configured width fraction is preserved across rotations.
        val dm = context.resources.displayMetrics
        val newFloatingWidth = calculateFloatingWidth(dm.widthPixels)
        val previousFloatingWidth = ResourceUtils.getFloatingKeyboardWidth()
        val widthChanged = newFloatingWidth != previousFloatingWidth
        if (widthChanged) {
            Log.i(TAG, "Floating keyboard width changed: $previousFloatingWidth -> $newFloatingWidth")
            ResourceUtils.setFloatingKeyboardWidth(newFloatingWidth)
            windowParams?.let { lp ->
                lp.width = newFloatingWidth
                try {
                    windowManager?.updateViewLayout(overlayRoot, lp)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update overlay width on input view recreation", e)
                }
            }
        }

        // Reparent new keyboard frame at the (possibly updated) floating width
        newParent.removeView(newMainKeyboardFrame)
        newMainKeyboardFrame.layoutParams = LinearLayout.LayoutParams(
            if (newFloatingWidth > 0) newFloatingWidth else LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        contentContainer.addView(newMainKeyboardFrame)

        // If the floating width changed, reload the keyboard so the keys re-measure
        // at the new width. Without this, keys keep the sizes from the previous
        // orientation and the keyboard looks wrong.
        if (widthChanged) {
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

    private fun calculateMinWidth(dm: DisplayMetrics): Int {
        val minDpPx = (MIN_WIDTH_DP * dm.density).toInt()
        val minFractionPx = (dm.widthPixels * MIN_WIDTH_FRACTION).toInt()
        return max(minDpPx, minFractionPx)
    }

    /**
     * Apply a new size to the floating keyboard overlay and reparented
     * keyboard. The left edge of the window stays fixed (anchorLeft = true)
     * or the right edge stays fixed (anchorLeft = false) depending on which
     * handle is being dragged. The width fraction is persisted so the size
     * is preserved across orientation changes and floating-mode sessions.
     */
    private fun resizeTo(newWidth: Int, anchorLeft: Boolean) {
        if (!isFloating) return
        val dm = context.resources.displayMetrics
        val currentLp = windowParams ?: return
        val minWidth = calculateMinWidth(dm)
        // Max width depends on which side is anchored so the keyboard never
        // extends past the screen on the side being dragged.
        val maxWidth = if (anchorLeft) {
            // Left handle: the left edge can move as far left as 0, so the
            // right edge of the keyboard (which is fixed) limits the width.
            resizeInitialWindowRight
        } else {
            // Right handle: the right edge can move as far right as the
            // screen width, so the left edge of the keyboard (which is
            // fixed) limits the width.
            dm.widthPixels - resizeInitialWindowX
        }
        val clampedWidth = newWidth.coerceIn(minWidth, maxWidth)

        val newX: Int = if (anchorLeft) {
            // Right edge stays fixed: x = rightEdge - newWidth
            resizeInitialWindowRight - clampedWidth
        } else {
            // Left edge stays fixed
            resizeInitialWindowX
        }

        if (clampedWidth == currentLp.width && newX == currentLp.x) return

        // Persist as a fraction of the current screen width so it scales
        // correctly across orientation changes.
        val newFraction = (clampedWidth.toFloat() / dm.widthPixels)
            .coerceIn(MIN_WIDTH_FRACTION, MAX_WIDTH_FRACTION)
        if (newFraction != widthFraction) {
            widthFraction = newFraction
            prefs.edit().putFloat(PREF_WIDTH_FRACTION, widthFraction).apply()
        }

        ResourceUtils.setFloatingKeyboardWidth(clampedWidth)
        currentLp.width = clampedWidth
        currentLp.x = newX
        try {
            windowManager?.updateViewLayout(overlayRoot, currentLp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update overlay layout during resize", e)
        }

        // Update keyboard frame layout so it fills the new width.
        val contentContainer = overlayRoot?.getChildAt(0) as? LinearLayout
        val mainKeyboardFrame = contentContainer?.findViewById<View>(R.id.main_keyboard_frame)
        if (mainKeyboardFrame != null) {
            mainKeyboardFrame.layoutParams = LinearLayout.LayoutParams(
                clampedWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            contentContainer.requestLayout()
        }

        // Reload keyboard so keys re-measure at the new width. Same
        // approach as one-handed mode resize: not great for performance
        // on every move event, but good enough and ensures correctness.
        KeyboardSwitcher.getInstance().reloadKeyboard()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createResizeHandle(density: Float, textColor: Int, isLeft: Boolean): View {
        val size = (RESIZE_HANDLE_SIZE_DP * density).toInt()
        val margin = (RESIZE_HANDLE_MARGIN_DP * density).toInt()
        val handle = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = if (isLeft) Gravity.BOTTOM or Gravity.START else Gravity.BOTTOM or Gravity.END
                if (isLeft) marginStart = margin else marginEnd = margin
                bottomMargin = margin
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(textColor and 0x00FFFFFF or 0x33000000) // 20% alpha — subtle
            }
            contentDescription = context.getString(R.string.floating_keyboard_resize_handle)
        }

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resizeInitialWidth = windowParams?.width ?: 0
                    resizeInitialWindowX = windowParams?.x ?: 0
                    resizeInitialWindowRight = resizeInitialWindowX + resizeInitialWidth
                    initialTouchX = event.rawX
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val targetWidth = if (isLeft) {
                        // Left handle: dragging left widens, right edge stays put
                        resizeInitialWidth - dx.toInt()
                    } else {
                        // Right handle: dragging right widens, left edge stays put
                        resizeInitialWidth + dx.toInt()
                    }
                    resizeTo(targetWidth, anchorLeft = isLeft)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePosition()
                    true
                }
                else -> false
            }
        }

        return handle
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
