// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * A horizontal utility bar that sits above the QWERTY rows, inside
 * [R.layout.main_keyboard_frame]. It provides modifier keys (Ctrl, Shift, Alt)
 * with sticky + double-tap-to-lock semantics, plus Esc, Tab, and the four
 * arrow keys.
 *
 * Modifier state machine (per modifier):
 * ```
 *   OFF  ──tap──►  ARMED  ──tap──►  OFF
 *                   │
 *                   └──double-tap (within 300ms)──►  LOCKED
 *   LOCKED  ──tap──►  OFF
 *   ARMED  ──timeout (5s)──►  OFF
 * ```
 *
 * When a modifier is ARMED or LOCKED, the next key sent (either from this bar
 * or from the main keyboard, via the hook in
 * [helium314.keyboard.keyboard.KeyboardActionListenerImpl]) is sent as a real
 * [KeyEvent] through the [android.view.inputmethod.InputConnection] with the
 * appropriate meta state, instead of being committed as plain text. ARMED
 * modifiers clear after one use; LOCKED modifiers stay active until explicitly
 * cleared.
 */
@SuppressLint("ViewConstructor")
class UtilityKeyBar(
        context: Context,
        private val latinIME: LatinIME
) : LinearLayout(context) {

    enum class ModifierState { OFF, ARMED, LOCKED }

    private enum class Modifier { CTRL, SHIFT, ALT }

    private var ctrlState = ModifierState.OFF
    private var shiftState = ModifierState.OFF
    private var altState = ModifierState.OFF

    private val lastTapTime = HashMap<Modifier, Long>()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var ctrlButton: TextView
    private lateinit var shiftButton: TextView
    private lateinit var altButton: TextView
    private lateinit var escButton: TextView
    private lateinit var tabButton: TextView
    private lateinit var arrowUpButton: TextView
    private lateinit var arrowDownButton: TextView
    private lateinit var arrowLeftButton: TextView
    private lateinit var arrowRightButton: TextView

    private val timeoutRunnable = Runnable {
        if (ctrlState == ModifierState.ARMED) ctrlState = ModifierState.OFF
        if (shiftState == ModifierState.ARMED) shiftState = ModifierState.OFF
        if (altState == ModifierState.ARMED) altState = ModifierState.OFF
        updateModifierStyles()
    }

    init {
        orientation = HORIZONTAL
        val density = resources.displayMetrics.density
        val heightPx = (BAR_HEIGHT_DP * density).toInt()
        layoutParams = ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, heightPx)
        setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
        createButtons()
    }

    private fun createButtons() {
        val density = resources.displayMetrics.density
        val colors = Settings.getValues().mColors
        val bgColor = colors.get(ColorType.MAIN_BACKGROUND)
        val textColor = colors.get(ColorType.KEY_TEXT)

        // Modifier buttons
        ctrlButton = createModifierButton(Modifier.CTRL, "Ctrl", textColor, density)
        shiftButton = createModifierButton(Modifier.SHIFT, "Shift", textColor, density)
        altButton = createModifierButton(Modifier.ALT, "Alt", textColor, density)

        addView(createSeparator(density))

        // Special keys
        escButton = createActionButton("Esc", textColor, bgColor, density,
                contentDesc = context.getString(R.string.utility_bar_esc_desc)) {
            sendKeyEvent(KeyEvent.KEYCODE_ESCAPE)
        }
        tabButton = createActionButton("Tab", textColor, bgColor, density,
                contentDesc = context.getString(R.string.utility_bar_tab_desc)) {
            sendKeyEvent(KeyEvent.KEYCODE_TAB)
        }

        addView(createSeparator(density))

        // Arrow keys (cross layout: ↑ ◀ ▼ ▶)
        arrowUpButton = createActionButton("▲", textColor, bgColor, density,
                contentDesc = context.getString(R.string.utility_bar_arrow_up_desc)) {
            sendKeyEvent(KeyEvent.KEYCODE_DPAD_UP)
        }
        arrowLeftButton = createActionButton("◀", textColor, bgColor, density,
                contentDesc = context.getString(R.string.utility_bar_arrow_left_desc)) {
            sendKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT)
        }
        arrowDownButton = createActionButton("▼", textColor, bgColor, density,
                contentDesc = context.getString(R.string.utility_bar_arrow_down_desc)) {
            sendKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        arrowRightButton = createActionButton("▶", textColor, bgColor, density,
                contentDesc = context.getString(R.string.utility_bar_arrow_right_desc)) {
            sendKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
    }

    private fun createModifierButton(
            modifier: Modifier, label: String, textColor: Int, density: Float
    ): TextView {
        val button = TextView(context).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                val m = (2 * density).toInt()
                marginStart = m
                marginEnd = m
            }
            background = makeModifierDrawable(textColor, ModifierState.OFF)
            contentDescription = context.getString(R.string.utility_bar_modifier_desc, label)
        }
        button.setOnClickListener { onModifierTap(modifier) }
        addView(button)
        return button
    }

    private fun createActionButton(
            label: String, textColor: Int, bgColor: Int, density: Float,
            contentDesc: String, onClick: () -> Unit
    ): TextView {
        val button = TextView(context).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                val m = (2 * density).toInt()
                marginStart = m
                marginEnd = m
            }
            background = makeActionDrawable(bgColor, textColor)
            contentDescription = contentDesc
        }
        button.setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        addView(button)
        return button
    }

    private fun createSeparator(density: Float): View {
        val view = View(context)
        view.layoutParams = LinearLayout.LayoutParams(
                (1 * density).toInt(), LayoutParams.MATCH_PARENT
        )
        view.setBackgroundColor(0x33000000)
        return view
    }

    private fun makeModifierDrawable(textColor: Int, state: ModifierState): GradientDrawable {
        val alpha: Int
        when (state) {
            ModifierState.OFF -> alpha = 0x00
            ModifierState.ARMED -> alpha = ARMED_ALPHA
            ModifierState.LOCKED -> alpha = LOCKED_ALPHA
        }
        val color = (textColor and 0x00FFFFFF) or (alpha shl 24)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6 * resources.displayMetrics.density
            setColor(color)
        }
    }

    private fun makeActionDrawable(bgColor: Int, textColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6 * resources.displayMetrics.density
            setColor(blendWithWhite(bgColor, 0.08f))
            setStroke((1 * resources.displayMetrics.density).toInt(),
                    (textColor and 0x00FFFFFF) or 0x22000000)
        }
    }

    private fun blendWithWhite(color: Int, ratio: Float): Int {
        val r = (Color.red(color) * (1 - ratio) + 255 * ratio).toInt()
        val g = (Color.green(color) * (1 - ratio) + 255 * ratio).toInt()
        val b = (Color.blue(color) * (1 - ratio) + 255 * ratio).toInt()
        return Color.argb(Color.alpha(color), r, g, b)
    }

    // ── State machine ──────────────────────────────────────────────────

    private fun onModifierTap(modifier: Modifier) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val now = SystemClock.uptimeMillis()
        val lastTap = lastTapTime[modifier] ?: 0L
        val isDoubleTap = lastTap != 0L && (now - lastTap) < DOUBLE_TAP_THRESHOLD_MS

        val currentState = stateOf(modifier)
        val newState: ModifierState

        when {
            currentState == ModifierState.OFF -> {
                newState = ModifierState.ARMED
                lastTapTime[modifier] = now
            }
            currentState == ModifierState.ARMED && isDoubleTap -> {
                newState = ModifierState.LOCKED
                lastTapTime[modifier] = now
            }
            currentState == ModifierState.ARMED -> {
                newState = ModifierState.OFF
                lastTapTime[modifier] = 0L
            }
            currentState == ModifierState.LOCKED -> {
                newState = ModifierState.OFF
                lastTapTime[modifier] = 0L
            }
            else -> {
                newState = ModifierState.OFF
                lastTapTime[modifier] = 0L
            }
        }

        setState(modifier, newState)
        updateModifierStyles()

        if (newState == ModifierState.ARMED) {
            scheduleTimeout()
        } else if (ctrlState != ModifierState.ARMED
                && shiftState != ModifierState.ARMED
                && altState != ModifierState.ARMED) {
            cancelTimeout()
        }
    }

    private fun stateOf(modifier: Modifier): ModifierState = when (modifier) {
        Modifier.CTRL -> ctrlState
        Modifier.SHIFT -> shiftState
        Modifier.ALT -> altState
    }

    private fun setState(modifier: Modifier, state: ModifierState) {
        when (modifier) {
            Modifier.CTRL -> ctrlState = state
            Modifier.SHIFT -> shiftState = state
            Modifier.ALT -> altState = state
        }
    }

    private fun scheduleTimeout() {
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        handler.removeCallbacks(timeoutRunnable)
    }

    // ── Public API ─────────────────────────────────────────────────────

    /** True if any modifier is ARMED or LOCKED. */
    fun hasActiveModifiers(): Boolean =
            ctrlState != ModifierState.OFF
                    || shiftState != ModifierState.OFF
                    || altState != ModifierState.OFF

    /**
     * The current [KeyEvent] meta state derived from this bar's modifier
     * states. Does not include any meta state from the main keyboard's
     * Shift/Alt/Ctrl keys — callers should OR this with any external state
     * they already track.
     */
    fun getMetaState(): Int {
        var state = 0
        if (ctrlState != ModifierState.OFF) state = state or KeyEvent.META_CTRL_ON
        if (shiftState != ModifierState.OFF) state = state or KeyEvent.META_SHIFT_ON
        if (altState != ModifierState.OFF) state = state or KeyEvent.META_ALT_ON
        return state
    }

    /**
     * Called by the main keyboard's input pipeline after a key has been
     * routed through this bar's modifiers. Clears any ARMED modifiers;
     * LOCKED modifiers stay active.
     */
    fun onModifierKeySent() {
        if (ctrlState == ModifierState.ARMED) ctrlState = ModifierState.OFF
        if (shiftState == ModifierState.ARMED) shiftState = ModifierState.OFF
        if (altState == ModifierState.ARMED) altState = ModifierState.OFF
        lastTapTime[Modifier.CTRL] = 0L
        lastTapTime[Modifier.SHIFT] = 0L
        lastTapTime[Modifier.ALT] = 0L
        cancelTimeout()
        updateModifierStyles()
    }

    /** Clear all modifier state. Called when the input field changes or
     *  the floating keyboard is hidden. */
    fun clearState() {
        ctrlState = ModifierState.OFF
        shiftState = ModifierState.OFF
        altState = ModifierState.OFF
        lastTapTime.clear()
        cancelTimeout()
        updateModifierStyles()
    }

    // ── Key event sending ──────────────────────────────────────────────

    /**
     * Send a key event through the current
     * [android.view.inputmethod.InputConnection] with the bar's current
     * modifier meta state, then clear ARMED modifiers. Used by the bar's
     * own buttons (Esc, Tab, arrows) and by the main-keyboard hook for
     * character keys.
     */
    fun sendKeyEvent(keyCode: Int) {
        val ric = latinIME.mInputLogic.connection
        if (!ric.isConnected) return
        val metaState = getMetaState()
        val eventTime = SystemClock.uptimeMillis()
        ric.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        ric.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        onModifierKeySent()
    }

    // ── Visual updates ─────────────────────────────────────────────────

    private fun updateModifierStyles() {
        val textColor = Settings.getValues().mColors.get(ColorType.KEY_TEXT)
        ctrlButton.background = makeModifierDrawable(textColor, ctrlState)
        shiftButton.background = makeModifierDrawable(textColor, shiftState)
        altButton.background = makeModifierDrawable(textColor, altState)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelTimeout()
    }

    companion object {
        private const val BAR_HEIGHT_DP = 40f
        private const val DOUBLE_TAP_THRESHOLD_MS = 300L
        private const val TIMEOUT_MS = 5000L
        private const val ARMED_ALPHA = 0x50  // ~31%
        private const val LOCKED_ALPHA = 0x99 // ~60%
    }
}
