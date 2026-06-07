package helium314.keyboard.latin.voice

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import helium314.keyboard.latin.R

class VoiceModeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Callbacks {
        fun onCloseClicked()
        fun onStartStopClicked()
        fun onHoldStart()
        fun onHoldEnd(canceled: Boolean)
        fun onCancelClicked()
        fun onCopyClicked()
        fun onPasteClicked()
        fun onBackspaceClicked()
        fun onEnterClicked()
        fun onSelectAllClicked()
        fun onInsertClicked(text: String)
        fun onReformatClicked(text: String)
        fun onReformatToggled(enabled: Boolean)
        fun onModeChanged(tapMode: Boolean)
        fun onPermissionRequest()
        fun onOpenProviders()
    }

    private val titleView: TextView
    private val statusView: TextView
    private val waveform: WaveformView
    private val micButton: ImageButton
    private val progressBar: ProgressBar
    private val modeToggle: MaterialButtonToggleGroup
    private val reformatToggle: MaterialSwitch
    private val preview: TranscriptPreviewView
    private val closeButton: ImageButton

    private val copyBtn: ImageButton
    private val pasteBtn: ImageButton
    private val backspaceBtn: ImageButton
    private val enterBtn: ImageButton
    private val selectAllBtn: ImageButton
    private val cancelBtn: ImageButton
    private val permissionBtn: MaterialButton
    private val openProvidersBtn: MaterialButton

    var callbacks: Callbacks? = null
    private var state: VoiceUiState = VoiceUiState.IDLE
    private var holdMode: Boolean = false
    private var suppressReformatCallback: Boolean = false
    private var holdCanceled: Boolean = false
    private var holdStartX: Float = 0f
    private var holdStartY: Float = 0f

    init {
        LayoutInflater.from(context).inflate(R.layout.voice_mode, this, true)
        titleView = findViewById(R.id.voice_title)
        statusView = findViewById(R.id.voice_status)
        waveform = findViewById(R.id.voice_waveform)
        micButton = findViewById(R.id.voice_mic)
        progressBar = findViewById(R.id.voice_progress)
        modeToggle = findViewById(R.id.voice_mode_toggle)
        reformatToggle = findViewById(R.id.voice_reformat_toggle)
        preview = findViewById(R.id.voice_preview)
        closeButton = findViewById(R.id.voice_close)
        copyBtn = findViewById(R.id.voice_btn_copy)
        pasteBtn = findViewById(R.id.voice_btn_paste)
        backspaceBtn = findViewById(R.id.voice_btn_backspace)
        enterBtn = findViewById(R.id.voice_btn_enter)
        selectAllBtn = findViewById(R.id.voice_btn_select_all)
        cancelBtn = findViewById(R.id.voice_btn_cancel)
        permissionBtn = findViewById(R.id.voice_grant_permission)
        openProvidersBtn = findViewById(R.id.voice_open_providers)

        closeButton.setOnClickListener { callbacks?.onCloseClicked() }
        cancelBtn.setOnClickListener { callbacks?.onCancelClicked() }

        micButton.setOnClickListener {
            if (holdMode) return@setOnClickListener
            callbacks?.onStartStopClicked()
        }

        micButton.setOnTouchListener { v, event ->
            if (!holdMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    holdCanceled = false
                    holdStartX = event.rawX
                    holdStartY = event.rawY
                    callbacks?.onHoldStart()
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - holdStartX
                    val dy = event.rawY - holdStartY
                    if (dx * dx + dy * dy > 100 * 100 && !holdCanceled) {
                        holdCanceled = true
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    callbacks?.onHoldEnd(holdCanceled)
                    true
                }
                else -> false
            }
        }

        copyBtn.setOnClickListener { callbacks?.onCopyClicked() }
        pasteBtn.setOnClickListener { callbacks?.onPasteClicked() }
        backspaceBtn.setOnClickListener { callbacks?.onBackspaceClicked() }
        enterBtn.setOnClickListener { callbacks?.onEnterClicked() }
        selectAllBtn.setOnClickListener { callbacks?.onSelectAllClicked() }

        modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val tap = checkedId == R.id.voice_mode_tap
            holdMode = !tap
            callbacks?.onModeChanged(tap)
        }

        reformatToggle.setOnCheckedChangeListener { _, isChecked ->
            if (suppressReformatCallback) return@setOnCheckedChangeListener
            callbacks?.onReformatToggled(isChecked)
        }

        preview.setOnInsertClick { text ->
            callbacks?.onInsertClicked(text)
        }
        preview.setOnReformatClick { text ->
            callbacks?.onReformatClicked(text)
        }

        permissionBtn.setOnClickListener { callbacks?.onPermissionRequest() }
        openProvidersBtn.setOnClickListener { callbacks?.onOpenProviders() }
    }

    fun setState(newState: VoiceUiState, statusText: String? = null) {
        state = newState
        applyState(statusText)
    }

    fun state(): VoiceUiState = state

    fun setStatusText(text: String) {
        statusView.text = text
    }

    fun pushWaveform(rms: Float) {
        waveform.pushSample(rms)
    }

    fun clearWaveform() {
        waveform.clear()
    }

    fun showPreview(text: String) {
        preview.setText(text)
        preview.visibility = View.VISIBLE
    }

    fun hidePreview() {
        preview.visibility = View.GONE
    }

    fun previewText(): String = preview.text()

    fun setReformatEnabled(enabled: Boolean) {
        suppressReformatCallback = true
        reformatToggle.isChecked = enabled
        suppressReformatCallback = false
    }

    fun isReformatEnabled(): Boolean = reformatToggle.isChecked

    fun setHoldMode(hold: Boolean) {
        holdMode = hold
        if (hold) {
            modeToggle.check(R.id.voice_mode_hold)
        } else {
            modeToggle.check(R.id.voice_mode_tap)
        }
    }

    fun isHoldMode(): Boolean = holdMode

    fun setReformatButtonVisible(visible: Boolean) {
        preview.setReformatVisible(visible)
    }

    private fun applyState(statusText: String?) {
        val ctx = context
        permissionBtn.visibility = View.GONE
        openProvidersBtn.visibility = View.GONE
        when (state) {
            VoiceUiState.IDLE -> {
                statusView.text = statusText ?: ctx.getString(R.string.voice_status_idle)
                micButton.visibility = View.VISIBLE
                micButton.isSelected = false
                micButton.setImageResource(R.drawable.ic_voice_mic)
                progressBar.visibility = View.GONE
                waveform.setActive(false)
                preview.visibility = View.GONE
                cancelBtn.visibility = View.GONE
            }
            VoiceUiState.RECORDING -> {
                statusView.text = statusText ?: ctx.getString(R.string.voice_status_recording)
                micButton.visibility = View.VISIBLE
                micButton.isSelected = true
                micButton.setImageResource(R.drawable.ic_voice_stop)
                progressBar.visibility = View.GONE
                waveform.setActive(true)
                preview.visibility = View.GONE
                cancelBtn.visibility = View.VISIBLE
            }
            VoiceUiState.TRANSCRIBING -> {
                statusView.text = statusText ?: ctx.getString(R.string.voice_status_transcribing)
                micButton.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                waveform.setActive(false)
                preview.visibility = View.GONE
                cancelBtn.visibility = View.VISIBLE
            }
            VoiceUiState.REFORMATTING -> {
                statusView.text = statusText ?: ctx.getString(R.string.voice_status_reformatting)
                micButton.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                preview.visibility = View.VISIBLE
                cancelBtn.visibility = View.VISIBLE
            }
            VoiceUiState.PREVIEW -> {
                statusView.text = statusText ?: ""
                micButton.visibility = View.VISIBLE
                micButton.isSelected = false
                micButton.setImageResource(R.drawable.ic_voice_mic)
                progressBar.visibility = View.GONE
                waveform.setActive(false)
                preview.visibility = View.VISIBLE
                cancelBtn.visibility = View.GONE
            }
            VoiceUiState.ERROR -> {
                statusView.text = statusText ?: ctx.getString(R.string.voice_status_error)
                micButton.visibility = View.VISIBLE
                micButton.isSelected = false
                micButton.setImageResource(R.drawable.ic_voice_mic)
                progressBar.visibility = View.GONE
                waveform.setActive(false)
                preview.visibility = View.GONE
                cancelBtn.visibility = View.GONE
            }
            VoiceUiState.PERMISSION_DENIED -> {
                statusView.text = statusText ?: ctx.getString(R.string.voice_status_permission_denied)
                micButton.visibility = View.GONE
                progressBar.visibility = View.GONE
                cancelBtn.visibility = View.GONE
                permissionBtn.visibility = View.VISIBLE
                openProvidersBtn.visibility = View.GONE
            }
            VoiceUiState.NO_PROVIDER -> {
                statusView.text = statusText ?: ctx.getString(R.string.voice_status_no_provider)
                micButton.visibility = View.GONE
                progressBar.visibility = View.GONE
                cancelBtn.visibility = View.GONE
                permissionBtn.visibility = View.GONE
                openProvidersBtn.visibility = View.VISIBLE
            }
            else -> {
                permissionBtn.visibility = View.GONE
                openProvidersBtn.visibility = View.GONE
            }
        }
    }
}
