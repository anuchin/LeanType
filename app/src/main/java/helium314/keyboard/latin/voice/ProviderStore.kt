package helium314.keyboard.latin.voice

import android.content.Context
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import kotlinx.serialization.json.Json

class ProviderStore(context: Context) {
    private val prefs = DeviceProtectedUtils.getSharedPreferences(context, PREFS_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun loadAll(): List<ProviderConfig> {
        val raw = prefs.getString(KEY_PROVIDERS_JSON, null) ?: return emptyList()
        val stored = runCatching { json.decodeFromString<List<ProviderConfig>>(raw) }.getOrNull() ?: return emptyList()
        val storedIds = stored.map { it.id }.toSet()
        val merged = stored.toMutableList()
        BuiltinProviders.builtinConfigs().forEach { builtin ->
            if (builtin.id !in storedIds) merged.add(builtin)
        }
        return merged
    }

    fun save(config: ProviderConfig) {
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.id == config.id }
        if (idx >= 0) all[idx] = config else all.add(config)
        persist(all)
    }

    fun delete(id: String) {
        val all = loadAll().filter { it.id != id || it.isBuiltin }
        persist(all)
    }

    fun reset(id: String) {
        val builtin = BuiltinProviders.builtinConfigs().firstOrNull { it.id == id } ?: return
        save(builtin.copy(apiKey = ""))
    }

    private fun persist(list: List<ProviderConfig>) {
        val userOnly = list.filter { !it.isBuiltin }
        prefs.edit().putString(KEY_PROVIDERS_JSON, json.encodeToString(userOnly)).apply()
    }

    fun getById(id: String?): ProviderConfig? = id?.let { idVal -> loadAll().firstOrNull { it.id == idVal } }

    companion object {
        const val PREFS_NAME = "voice_provider_prefs"
        const val KEY_PROVIDERS_JSON = "voice_providers_json"
    }
}
