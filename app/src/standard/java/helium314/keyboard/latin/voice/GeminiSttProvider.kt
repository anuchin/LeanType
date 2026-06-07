package helium314.keyboard.latin.voice

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiSttProvider(
    private val config: ProviderConfig,
) : SttProvider {

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
        }
    }

    override suspend fun testKey(): Result<Unit> = runCatching {
        val url = "${config.baseUrl.trimEnd('/')}/models?key=${config.apiKey}"
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
        val url = "${config.baseUrl.trimEnd('/')}/models?key=${config.apiKey}"
        val conn = openConnection(url).apply { requestMethod = "GET" }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                throw RuntimeException("HTTP $code: ${err.take(200)}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val models = json.optJSONArray("models") ?: return@runCatching emptyList()
            (0 until models.length()).map { i ->
                val obj = models.getJSONObject(i)
                val name = obj.optString("name").removePrefix("models/")
                val display = obj.optString("displayName", name)
                SttModel(id = name, displayName = display, ownedBy = "google")
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
        val url = "${config.baseUrl.trimEnd('/')}/models/$modelId:generateContent?key=${config.apiKey}"
        val conn = openConnection(url)
        conn.doOutput = true

        val langHint = if (!language.isNullOrBlank()) " The audio is in $language." else ""
        val prompt = "Transcribe the following audio verbatim. Return ONLY the transcribed text, no explanation, no formatting, no preamble.$langHint"

        val audioB64 = Base64.encodeToString(wav, Base64.NO_WRAP)
        val parts = JSONArray()
        parts.put(JSONObject().apply {
            put("text", prompt)
        })
        parts.put(JSONObject().apply {
            put("inline_data", JSONObject().apply {
                put("mime_type", "audio/wav")
                put("data", audioB64)
            })
        })
        val contents = JSONArray().put(JSONObject().put("parts", parts))
        val body = JSONObject().put("contents", contents).toString()

        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                throw RuntimeException("HTTP $code: ${err.take(200)}")
            }
            val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates") ?: return@runCatching ""
            if (candidates.length() == 0) return@runCatching ""
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return@runCatching ""
            val responseParts = content.optJSONArray("parts") ?: return@runCatching ""
            (0 until responseParts.length())
                .mapNotNull { responseParts.getJSONObject(it).optString("text", "").takeIf { t -> t.isNotBlank() } }
                .joinToString(" ")
        } finally {
            conn.disconnect()
        }
    }
}
