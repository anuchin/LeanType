package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.voice.ProviderStore
import helium314.keyboard.latin.voice.ReformatTone
import helium314.keyboard.latin.voice.VoiceSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { VoiceSettings(context) }
    val store = remember { ProviderStore(context) }
    val providers by remember { mutableStateOf(store.loadAll()) }
    val defaultProvider = remember(providers) {
        providers.firstOrNull { it.id == settings.defaultProviderId } ?: providers.firstOrNull()
    }

    var language by remember { mutableStateOf(settings.defaultLanguage ?: "") }
    var reformatEnabled by remember { mutableStateOf(settings.reformatEnabled) }
    var reformatTone by remember { mutableStateOf(settings.reformatTone) }
    var maxDuration by remember { mutableStateOf(settings.maxDurationSeconds.toFloat()) }
    var silenceTimeout by remember { mutableStateOf(settings.silenceTimeoutSeconds.toFloat()) }
    var wifiOnly by remember { mutableStateOf(settings.wifiOnly) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_settings_title)) },
                navigationIcon = { IconButton(onClick = onClickBack) { Text("←") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (defaultProvider == null) {
                Text(
                    text = stringResource(R.string.voice_provider_no_default),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.voice_provider_set_default_first),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = "${stringResource(R.string.voice_provider_used_as_default)}: ${defaultProvider.name}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            OutlinedTextField(
                value = language,
                onValueChange = {
                    language = it
                    settings.defaultLanguage = it.ifBlank { null }
                },
                label = { Text(stringResource(R.string.voice_default_language_title)) },
                placeholder = { Text("en, es, fr…") },
                supportingText = { Text(stringResource(R.string.voice_default_language_summary)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.voice_reformat_enabled_title),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = reformatEnabled,
                    onCheckedChange = {
                        reformatEnabled = it
                        settings.reformatEnabled = it
                    },
                )
            }
            Text(
                text = stringResource(R.string.voice_reformat_enabled_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (reformatEnabled) {
                Text(
                    text = stringResource(R.string.voice_reformat_tone_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                ReformatTone.entries.forEach { tone ->
                    val label = context.getString(
                        context.resources.getIdentifier(tone.displayKey, "string", context.packageName)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = reformatTone == tone,
                            onClick = {
                                reformatTone = tone
                                settings.reformatTone = tone
                            },
                        )
                        Text(text = label)
                    }
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.voice_max_duration_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "${maxDuration.toInt()} s",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = maxDuration,
                onValueChange = { maxDuration = it },
                valueRange = 15f..300f,
                steps = 18,
                onValueChangeFinished = { settings.maxDurationSeconds = maxDuration.toInt() },
            )
            Text(
                text = stringResource(R.string.voice_max_duration_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.voice_silence_timeout_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "${silenceTimeout.toInt()} s",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = silenceTimeout,
                onValueChange = { silenceTimeout = it },
                valueRange = 5f..60f,
                steps = 10,
                onValueChangeFinished = { settings.silenceTimeoutSeconds = silenceTimeout.toInt() },
            )
            Text(
                text = stringResource(R.string.voice_silence_timeout_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.voice_wifi_only_title),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = {
                        wifiOnly = it
                        settings.wifiOnly = it
                    },
                )
            }
            Text(
                text = stringResource(R.string.voice_wifi_only_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
