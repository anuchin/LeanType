// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProviderStoreTest {

    private lateinit var store: ProviderStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        store = ProviderStore(ctx)
    }

    @Test
    fun loadAll_empty_returnsBuiltinsOnly() {
        val all = store.loadAll()
        val builtinIds = BuiltinProviders.builtinConfigs().map { it.id }.toSet()
        assertEquals(builtinIds, all.map { it.id }.toSet())
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it.isBuiltin })
    }

    @Test
    fun save_persistsCustomProvider() {
        val custom = ProviderConfig(
            id = "custom-acme",
            name = "Acme",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://api.acme.example/v1",
            apiKey = "sk-test",
        )
        store.save(custom)

        val all = store.loadAll()
        val acme = all.firstOrNull { it.id == "custom-acme" }
        assertNotNull(acme)
        assertEquals("Acme", acme!!.name)
        assertEquals("sk-test", acme.apiKey)
        assertEquals("https://api.acme.example/v1", acme.baseUrl)
        assertEquals(false, acme.isBuiltin)
    }

    @Test
    fun save_overwritesExistingById() {
        val original = ProviderConfig(
            id = "custom-acme",
            name = "Acme",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://api.acme.example/v1",
            apiKey = "old-key",
        )
        store.save(original)
        val updated = original.copy(apiKey = "new-key")
        store.save(updated)

        val all = store.loadAll()
        val acme = all.first { it.id == "custom-acme" }
        assertEquals("new-key", acme.apiKey)
        assertEquals(1, all.count { it.id == "custom-acme" })
    }

    @Test
    fun delete_removesCustomButKeepsBuiltins() {
        val custom = ProviderConfig(
            id = "custom-tmp",
            name = "Tmp",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://x.example/v1",
            apiKey = "",
        )
        store.save(custom)
        assertNotNull(store.getById("custom-tmp"))

        store.delete("custom-tmp")
        assertNull(store.getById("custom-tmp"))

        val builtin = BuiltinProviders.builtinConfigs().first()
        assertNotNull(store.getById(builtin.id))
    }

    @Test
    fun reset_clearsApiKeyForBuiltin() {
        val builtin = BuiltinProviders.builtinConfigs().first()
        store.save(builtin.copy(apiKey = "user-key"))

        store.reset(builtin.id)
        val after = store.getById(builtin.id)
        assertNotNull(after)
        assertEquals("", after!!.apiKey)
        assertEquals(builtin.baseUrl, after.baseUrl)
        assertEquals(builtin.defaultModelId, after.defaultModelId)
    }

    @Test
    fun getById_nullId_returnsNull() {
        assertNull(store.getById(null))
    }

    @Test
    fun getById_unknownId_returnsNull() {
        assertNull(store.getById("does-not-exist"))
    }

    @Test
    fun save_customWithModels_preservesModels() {
        val models = listOf(
            SttModel(id = "m-1", displayName = "Model 1"),
            SttModel(id = "m-2", displayName = "Model 2"),
        )
        val custom = ProviderConfig(
            id = "custom-with-models",
            name = "WM",
            kind = ProviderKind.GEMINI,
            baseUrl = "https://example/v1",
            apiKey = "k",
            models = models,
            defaultModelId = "m-1",
        )
        store.save(custom)

        val loaded = store.getById("custom-with-models")!!
        assertEquals(2, loaded.models.size)
        assertEquals("m-1", loaded.defaultModelId)
    }
}
