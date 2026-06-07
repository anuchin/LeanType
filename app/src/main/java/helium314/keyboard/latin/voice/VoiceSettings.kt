package helium314.keyboard.latin.voice

import android.content.Context
import helium314.keyboard.latin.utils.DeviceProtectedUtils

class VoiceSettings(context: Context) {
    private val prefs = DeviceProtectedUtils.getSharedPreferences(context, "voice_settings_prefs")

    var defaultProviderId: String?
        get() = prefs.getString(KEY_DEFAULT_PROVIDER_ID, null)
        set(value) { prefs.edit().putString(KEY_DEFAULT_PROVIDER_ID, value).apply() }

    var defaultModelId: String?
        get() = prefs.getString(KEY_DEFAULT_MODEL_ID, null)
        set(value) { prefs.edit().putString(KEY_DEFAULT_MODEL_ID, value).apply() }

    var defaultLanguage: String?
        get() = prefs.getString(KEY_DEFAULT_LANGUAGE, null)
        set(value) { prefs.edit().putString(KEY_DEFAULT_LANGUAGE, value).apply() }

    var reformatEnabled: Boolean
        get() = prefs.getBoolean(KEY_REFORMAT_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_REFORMAT_ENABLED, value).apply() }

    var reformatTone: ReformatTone
        get() = ReformatTone.fromKey(prefs.getString(KEY_REFORMAT_TONE, ReformatTone.CASUAL.name))
        set(value) { prefs.edit().putString(KEY_REFORMAT_TONE, value.name).apply() }

    var maxDurationSeconds: Int
        get() = prefs.getInt(KEY_MAX_DURATION, 60).coerceIn(15, 300)
        set(value) { prefs.edit().putInt(KEY_MAX_DURATION, value.coerceIn(15, 300)).apply() }

    var silenceTimeoutSeconds: Int
        get() = prefs.getInt(KEY_SILENCE_TIMEOUT, 20).coerceIn(5, 60)
        set(value) { prefs.edit().putInt(KEY_SILENCE_TIMEOUT, value.coerceIn(5, 60)).apply() }

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) { prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply() }

    var holdMode: Boolean
        get() = prefs.getBoolean(KEY_HOLD_MODE, false)
        set(value) { prefs.edit().putBoolean(KEY_HOLD_MODE, value).apply() }

    companion object {
        const val KEY_DEFAULT_PROVIDER_ID = "voice_default_provider_id"
        const val KEY_DEFAULT_MODEL_ID = "voice_default_model_id"
        const val KEY_DEFAULT_LANGUAGE = "voice_default_language"
        const val KEY_REFORMAT_ENABLED = "voice_reformat_enabled"
        const val KEY_REFORMAT_TONE = "voice_reformat_tone"
        const val KEY_MAX_DURATION = "voice_max_duration_seconds"
        const val KEY_SILENCE_TIMEOUT = "voice_silence_timeout_seconds"
        const val KEY_WIFI_ONLY = "voice_wifi_only"
        const val KEY_HOLD_MODE = "voice_hold_mode"
    }
}
