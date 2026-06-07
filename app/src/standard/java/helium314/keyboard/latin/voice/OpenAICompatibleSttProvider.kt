package helium314.keyboard.latin.voice

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class OpenAICompatibleSttProvider(
    private val config: ProviderConfig,
) : SttProvider {

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("User-Agent", "LeanType-IME/1.0")
        }
    }

    override suspend fun testKey(): Result<Unit> = runCatching {
        val url = "${config.baseUrl.trimEnd('/')}/models"
        val conn = openConnection(url).apply { requestMethod = "GET" }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                throw RuntimeException("HTTP $code: ${err.take(200)}")
            }
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun fetchModels(): Result<List<SttModel>> = runCatching {
        val url = "${config.baseUrl.trimEnd('/')}/models"
        val conn = openConnection(url).apply { requestMethod = "GET" }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                throw RuntimeException("HTTP $code: ${err.take(200)}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return@runCatching emptyList()
            (0 until data.length()).map { i ->
                val obj = data.getJSONObject(i)
                SttModel(
                    id = obj.optString("id"),
                    displayName = obj.optString("id"),
                    ownedBy = obj.optString("owned_by", ""),
                )
            }.filter { it.id.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun transcribe(
        wav: ByteArray,
        modelId: String,
        language: String?,
    ): Result<String> = runCatching {
        val url = "${config.baseUrl.trimEnd('/')}/audio/transcriptions"
        val boundary = "----LeanTypeBoundary${UUID.randomUUID()}"
        val body = buildMultipartBody(boundary, modelId, language, wav)
        val conn = openConnection(url)
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.doOutput = true
        conn.outputStream.use { it.write(body) }

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                throw RuntimeException("HTTP $code: ${err.take(200)}")
            }
            val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(responseBody).optString("text", "")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        internal fun buildMultipartBody(
            boundary: String,
            modelId: String,
            language: String?,
            wav: ByteArray,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val data = DataOutputStream(out)
            data.writeBytes("--$boundary\r\n")
            data.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
            data.write(modelId.toByteArray(Charsets.UTF_8))
            data.writeBytes("\r\n")
            if (!language.isNullOrBlank()) {
                data.writeBytes("--$boundary\r\n")
                data.writeBytes("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
                data.write(language.toByteArray(Charsets.UTF_8))
                data.writeBytes("\r\n")
            }
            data.writeBytes("--$boundary\r\n")
            data.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
            data.writeBytes("Content-Type: audio/wav\r\n\r\n")
            data.write(wav)
            data.writeBytes("\r\n")
            data.writeBytes("--$boundary--\r\n")
            data.flush()
            return out.toByteArray()
        }
    }
}

