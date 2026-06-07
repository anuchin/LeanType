package helium314.keyboard.latin.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioRecorder(
    private val context: Context,
    private val sampleRate: Int = 16_000,
) {
    private var record: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _pcmBuffer = mutableListOf<ByteArray>()
    private val _isRecording = MutableStateFlow(false)
    private val _rms = MutableStateFlow(0f)
    private val _elapsedMs = MutableStateFlow(0L)
    private val _error = MutableStateFlow<String?>(null)

    val isRecording: StateFlow<Boolean> = _isRecording
    val rms: StateFlow<Float> = _rms
    val elapsedMs: StateFlow<Long> = _elapsedMs
    val error: StateFlow<String?> = _error

    val silenceDetector = SilenceDetector(sampleRate = sampleRate)

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun hasBluetoothMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun missingPermissions(): List<String> {
        val out = mutableListOf<String>()
        if (!hasPermission()) out.add(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            !hasBluetoothMicPermission()) {
            out.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return out
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (_isRecording.value) return
        recordingJob?.cancel()
        if (!hasPermission()) {
            _error.value = "RECORD_AUDIO permission not granted"
            return
        }
        _pcmBuffer.clear()
        silenceDetector.reset()
        _elapsedMs.value = 0L
        _rms.value = 0f
        _error.value = null

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            _error.value = "AudioRecord getMinBufferSize failed: $minBuffer"
            return
        }
        val bufferSize = (minBuffer * 2).coerceAtLeast(4096)

        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        } catch (e: SecurityException) {
            _error.value = "AudioRecord init failed: ${e.message}"
            return
        } catch (e: IllegalArgumentException) {
            _error.value = "AudioRecord init failed: ${e.message}"
            return
        } catch (e: UnsupportedOperationException) {
            _error.value = "AudioRecord init failed: ${e.message}"
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            _error.value = "AudioRecord not initialized"
            recorder.release()
            return
        }

        record = recorder
        recorder.startRecording()
        _isRecording.value = true

        val startTime = System.currentTimeMillis()
        val readBuffer = ByteArray(bufferSize)

        recordingJob = scope.launch {
            while (isActive && _isRecording.value) {
                val read = withContext(Dispatchers.IO) {
                    try {
                        recorder.read(readBuffer, 0, readBuffer.size)
                    } catch (e: Exception) {
                        -1
                    }
                }
                if (read <= 0) {
                    _error.value = "Audio read error: $read"
                    break
                }
                val chunk = readBuffer.copyOf(read)
                _pcmBuffer.add(chunk)
                silenceDetector.feedSamples(chunk)
                val rms = computeRms(chunk)
                _rms.value = rms
                _elapsedMs.value = System.currentTimeMillis() - startTime
            }
        }
    }

    fun stop(): ByteArray {
        if (!_isRecording.value) {
            return ByteArray(0)
        }
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null
        val recorder = record
        record = null
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder?.release()

        val totalSize = _pcmBuffer.sumOf { it.size }
        val pcm = ByteArray(totalSize)
        var offset = 0
        for (chunk in _pcmBuffer) {
            System.arraycopy(chunk, 0, pcm, offset, chunk.size)
            offset += chunk.size
        }
        _pcmBuffer.clear()
        return WavEncoder.encode(pcm, sampleRate = sampleRate)
    }

    fun cancel() {
        if (!_isRecording.value) return
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null
        val recorder = record
        record = null
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        _pcmBuffer.clear()
        _rms.value = 0f
    }

    fun release() {
        cancel()
        scope.cancel()
    }

    private fun computeRms(pcm: ByteArray): Float {
        if (pcm.isEmpty()) return 0f
        var sumSquares = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val low = pcm[i].toInt() and 0xFF
            val high = pcm[i + 1].toInt()
            val sample = (high shl 8) or low
            val signed = sample.toShort().toInt()
            sumSquares += (signed * signed).toDouble()
            count++
            i += 2
        }
        if (count == 0) return 0f
        return kotlin.math.sqrt(sumSquares / count).toFloat() / Short.MAX_VALUE.toFloat()
    }
}
