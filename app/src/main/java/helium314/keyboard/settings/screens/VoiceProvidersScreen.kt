package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.voice.ProviderConfig
import helium314.keyboard.latin.voice.ProviderStore
import helium314.keyboard.latin.voice.VoiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceProvidersScreen(
    onClickBack: () -> Unit,
    onEditProvider: (String) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ProviderStore(context) }
    val settings = remember { VoiceSettings(context) }
    val scope = rememberCoroutineScope()

    var providers by remember { mutableStateOf<List<ProviderConfig>>(emptyList()) }
    var defaultId by remember { mutableStateOf<String?>(null) }

    fun reload() {
        providers = store.loadAll()
        defaultId = settings.defaultProviderId
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_providers_title)) },
                navigationIcon = {
                    IconButton(onClick = onClickBack) { Text("←") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val newId = "custom-${UUID.randomUUID()}"
                    onEditProvider(newId)
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.voice_provider_add)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(providers, key = { it.id }) { provider ->
                ProviderRow(
                    provider = provider,
                    isDefault = provider.id == defaultId,
                    onEdit = { onEditProvider(provider.id) },
                    onSetDefault = {
                        settings.defaultProviderId = provider.id
                        if (provider.defaultModelId != null) {
                            settings.defaultModelId = provider.defaultModelId
                        }
                        reload()
                    },
                    onDelete = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                store.delete(provider.id)
                                if (defaultId == provider.id) {
                                    settings.defaultProviderId = null
                                    settings.defaultModelId = null
                                }
                            }
                            reload()
                        }
                    },
                    onReset = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.reset(provider.id) }
                            reload()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: ProviderConfig,
    isDefault: Boolean,
    onEdit: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val isConfigured = provider.apiKey.isNotBlank()
    val modelCount = provider.models.size

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (provider.isBuiltin) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.voice_provider_builtin_badge)) },
                    )
                } else {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.voice_provider_custom_badge)) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isConfigured) {
                    context.getString(R.string.voice_provider_connected, modelCount)
                } else {
                    stringResource(R.string.voice_provider_disconnected)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isDefault) {
                Spacer(Modifier.height(4.dp))
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.voice_provider_used_as_default)) },
                    leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onEdit) {
                    Text(stringResource(R.string.voice_provider_save))
                }
                Spacer(Modifier.width(8.dp))
                if (!isDefault && isConfigured) {
                    OutlinedButton(onClick = onSetDefault) {
                        Text(stringResource(R.string.voice_provider_set_default))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) { Text("⋮") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (provider.isBuiltin) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.voice_provider_reset)) },
                                onClick = { menuOpen = false; onReset() },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.voice_provider_delete)) },
                                onClick = { menuOpen = false; onDelete() },
                            )
                        }
                    }
                }
            }
        }
    }
}
