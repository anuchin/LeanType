// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceSettingsTest {

    private lateinit var settings: VoiceSettings

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        settings = VoiceSettings(ctx)
    }

    @Test
    fun defaults_matchSpec() {
        assertFalse(settings.reformatEnabled)
        assertEquals(ReformatTone.CASUAL, settings.reformatTone)
        assertNull(settings.defaultLanguage)
        assertNull(settings.defaultProviderId)
        assertNull(settings.defaultModelId)
        assertEquals(60, settings.maxDurationSeconds)
        assertEquals(20, settings.silenceTimeoutSeconds)
        assertFalse(settings.wifiOnly)
        assertFalse(settings.holdMode)
    }

    @Test
    fun reformatEnabled_roundTrip() {
        settings.reformatEnabled = true
        assertTrue(settings.reformatEnabled)
        settings.reformatEnabled = false
        assertFalse(settings.reformatEnabled)
    }

    @Test
    fun reformatTone_roundTrip() {
        ReformatTone.entries.forEach { tone ->
            settings.reformatTone = tone
            assertEquals(tone, settings.reformatTone)
        }
    }

    @Test
    fun reformatTone_fromKey_unknownDefaultsToCasual() {
        assertEquals(ReformatTone.CASUAL, ReformatTone.fromKey(null))
        assertEquals(ReformatTone.CASUAL, ReformatTone.fromKey(""))
        assertEquals(ReformatTone.CASUAL, ReformatTone.fromKey("nonsense"))
        assertEquals(ReformatTone.FORMAL, ReformatTone.fromKey("FORMAL"))
        assertEquals(ReformatTone.FORMAL, ReformatTone.fromKey("formal"))
    }

    @Test
    fun defaultLanguage_roundTrip() {
        settings.defaultLanguage = "es"
        assertEquals("es", settings.defaultLanguage)
        settings.defaultLanguage = null
        assertNull(settings.defaultLanguage)
    }

    @Test
    fun maxDurationSeconds_clampedToValidRange() {
        settings.maxDurationSeconds = 1
        assertEquals(15, settings.maxDurationSeconds)
        settings.maxDurationSeconds = 9999
        assertEquals(300, settings.maxDurationSeconds)
        settings.maxDurationSeconds = 120
        assertEquals(120, settings.maxDurationSeconds)
    }

    @Test
    fun silenceTimeoutSeconds_clampedToValidRange() {
        settings.silenceTimeoutSeconds = 1
        assertEquals(5, settings.silenceTimeoutSeconds)
        settings.silenceTimeoutSeconds = 9999
        assertEquals(60, settings.silenceTimeoutSeconds)
        settings.silenceTimeoutSeconds = 30
        assertEquals(30, settings.silenceTimeoutSeconds)
    }

    @Test
    fun defaultProviderIdAndModelId_roundTrip() {
        settings.defaultProviderId = "builtin-groq"
        settings.defaultModelId = "whisper-large-v3"
        assertEquals("builtin-groq", settings.defaultProviderId)
        assertEquals("whisper-large-v3", settings.defaultModelId)
    }

    @Test
    fun wifiOnly_roundTrip() {
        settings.wifiOnly = true
        assertTrue(settings.wifiOnly)
    }

    @Test
    fun holdMode_roundTrip() {
        settings.holdMode = true
        assertTrue(settings.holdMode)
        settings.holdMode = false
        assertFalse(settings.holdMode)
    }
}
