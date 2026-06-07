package helium314.keyboard.latin.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

class VoiceSessionController(
    private val context: Context,
    private val settings: VoiceSettings = VoiceSettings(context),
    private val store: ProviderStore = ProviderStore(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transcribeJob: Job? = null

    private val _uiState = MutableStateFlow(VoiceUiState.IDLE)
    val uiState: StateFlow<VoiceUiState> = _uiState

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText

    private val _rms = MutableStateFlow(0f)
    val rms: StateFlow<Float> = _rms

    val recorder = AudioRecorder(context)

    private var elapsedJob: Job? = null
    private var silenceJob: Job? = null

    private var attachedView: VoiceModeView? = null
    private val uiCollectorJobs: MutableList<Job> = Collections.synchronizedList(mutableListOf())

    init {
        scope.launch {
            recorder.rms.collect { _rms.value = it }
        }
        scope.launch {
            recorder.elapsedMs.collect { ms ->
                if (recorder.isRecording.value) {
                    _statusText.value = formatTime(ms)
                }
            }
        }
        scope.launch {
            recorder.error.collect { err ->
                if (err != null) {
                    _uiState.value = VoiceUiState.ERROR
                    _statusText.value = err
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    fun startRecording() {
        if (_uiState.value == VoiceUiState.TRANSCRIBING ||
            _uiState.value == VoiceUiState.REFORMATTING) {
            transcribeJob?.cancel()
            return
        }
        if (!recorder.hasPermission()) {
            _uiState.value = VoiceUiState.PERMISSION_DENIED
            return
        }
        val defaultId = settings.defaultProviderId
        if (defaultId == null) {
            _uiState.value = VoiceUiState.NO_PROVIDER
            return
        }
        _uiState.value = VoiceUiState.RECORDING
        recorder.start()
        startMaxDurationTimer()
        startSilenceTimer()
    }

    fun stopAndTranscribe() {
        if (!recorder.isRecording.value) return
        val wav = recorder.stop()
        elapsedJob?.cancel()
        silenceJob?.cancel()
        if (wav.isEmpty()) {
            _uiState.value = VoiceUiState.ERROR
            _statusText.value = "Recording failed"
            return
        }
        _uiState.value = VoiceUiState.TRANSCRIBING
        _statusText.value = null
        transcribeJob?.cancel()
        transcribeJob = scope.launch {
            if (settings.wifiOnly && !isOnWifi()) {
                _uiState.value = VoiceUiState.ERROR
                _statusText.value = "Wi-Fi required"
                _lastTranscript = ""
                return@launch
            }
            val defaultId = settings.defaultProviderId
            val provider = defaultId?.let { store.getById(it) }
            if (provider == null) {
                _uiState.value = VoiceUiState.NO_PROVIDER
                return@launch
            }
            val modelId = settings.defaultModelId ?: provider.defaultModelId ?: provider.models.firstOrNull()?.id
            if (modelId == null) {
                _uiState.value = VoiceUiState.ERROR
                _statusText.value = "No model configured"
                return@launch
            }
            val language = settings.defaultLanguage
            val result = withContext(Dispatchers.IO) {
                VoiceProviderApi.transcribe(provider, wav, modelId, language)
            }
            result.onSuccess { text ->
                if (text.isBlank()) {
                    _uiState.value = VoiceUiState.ERROR
                    _statusText.value = "Empty transcript"
                    return@onSuccess
                }
                if (settings.reformatEnabled && settings.reformatTone != ReformatTone.NONE) {
                    _uiState.value = VoiceUiState.REFORMATTING
                    val reformatted = try {
                        reformatter?.invoke(text) ?: Result.success(text)
                    } catch (e: Throwable) {
                        Result.failure<String>(e)
                    }
                    reformatted.onSuccess { finalText ->
                        _uiState.value = VoiceUiState.PREVIEW
                        _lastTranscript = finalText
                    }.onFailure {
                        _uiState.value = VoiceUiState.PREVIEW
                        _lastTranscript = text
                        _statusText.value = "Reformat failed"
                    }
                } else {
                    _uiState.value = VoiceUiState.PREVIEW
                    _lastTranscript = text
                }
            }.onFailure { e ->
                _uiState.value = VoiceUiState.ERROR
                _statusText.value = e.message ?: "Transcription failed"
            }
        }
    }

    var reformatter: ((String) -> Result<String>)? = null

    fun setReformatter(reformatter: Reformatter) {
        this.reformatter = { text -> reformatter.reformat(text) }
    }

    fun interface Reformatter {
        fun reformat(text: String): Result<String>
    }

    @Volatile
    private var _lastTranscript: String = ""
    val lastTranscript: String get() = _lastTranscript

    fun setLastTranscript(text: String) {
        _lastTranscript = text
    }

    fun cancelRecording() {
        recorder.cancel()
        transcribeJob?.cancel()
        elapsedJob?.cancel()
        silenceJob?.cancel()
        _uiState.value = VoiceUiState.IDLE
        _statusText.value = null
    }

    private fun startMaxDurationTimer() {
        elapsedJob?.cancel()
        val maxMs = settings.maxDurationSeconds * 1000L
        elapsedJob = scope.launch {
            kotlinx.coroutines.delay(maxMs)
            if (recorder.isRecording.value) {
                stopAndTranscribe()
            }
        }
    }

    private fun startSilenceTimer() {
        silenceJob?.cancel()
        val timeoutMs = settings.silenceTimeoutSeconds * 1000L
        silenceJob = scope.launch {
            while (recorder.isRecording.value) {
                kotlinx.coroutines.delay(500)
                if (recorder.silenceDetector.silenceDurationMs >= timeoutMs) {
                    stopAndTranscribe()
                    break
                }
            }
        }
    }

    fun release() {
        recorder.release()
        transcribeJob?.cancel()
        elapsedJob?.cancel()
        silenceJob?.cancel()
        scope.cancel()
    }

    fun attachView(view: VoiceModeView) {
        detachView()
        attachedView = view
        uiCollectorJobs.add(scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            _uiState.collect { state ->
                attachedView?.setState(state)
                if (state == VoiceUiState.PREVIEW) {
                    attachedView?.showPreview(_lastTranscript)
                }
            }
        })
        uiCollectorJobs.add(scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            _statusText.collect { text ->
                if (text != null) attachedView?.setStatusText(text)
            }
        })
        uiCollectorJobs.add(scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            _rms.collect { rms ->
                attachedView?.pushWaveform(rms)
            }
        })
    }

    fun detachView() {
        uiCollectorJobs.forEach { it.cancel() }
        uiCollectorJobs.clear()
        attachedView = null
    }

    fun refreshUiState() {
        attachedView?.setState(_uiState.value)
        _statusText.value?.let { attachedView?.setStatusText(it) }
    }

    fun resetStateIfTransient() {
        when (_uiState.value) {
            VoiceUiState.RECORDING, VoiceUiState.TRANSCRIBING,
            VoiceUiState.REFORMATTING, VoiceUiState.PREVIEW -> {
                _uiState.value = VoiceUiState.IDLE
                _statusText.value = null
                _lastTranscript = ""
            }
            else -> Unit
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun reformatCurrent(text: String) {
        if (text.isBlank()) return
        val callback = reformatter ?: run {
            _uiState.value = VoiceUiState.PREVIEW
            _lastTranscript = text
            return
        }
        _uiState.value = VoiceUiState.REFORMATTING
        transcribeJob?.cancel()
        transcribeJob = scope.launch {
            val result = try {
                callback.invoke(text)
            } catch (e: Throwable) {
                Result.failure<String>(e)
            }
            result.onSuccess { finalText ->
                _lastTranscript = finalText
                _uiState.value = VoiceUiState.PREVIEW
            }.onFailure {
                _lastTranscript = text
                _uiState.value = VoiceUiState.PREVIEW
                _statusText.value = "Reformat failed"
            }
        }
    }
}
