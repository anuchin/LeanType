package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.voice.ProviderConfig
import helium314.keyboard.latin.voice.ProviderKind
import helium314.keyboard.latin.voice.ProviderStore
import helium314.keyboard.latin.voice.VoiceProviderApi
import helium314.keyboard.latin.voice.VoiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderConfigScreen(
    providerId: String,
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ProviderStore(context) }
    val settings = remember { VoiceSettings(context) }
    val scope = rememberCoroutineScope()

    val initial = remember { store.getById(providerId) ?: newCustomConfig(providerId) }
    val addProviderLabel = stringResource(R.string.voice_provider_add)

    var name by remember { mutableStateOf(initial.name) }
    var kind by remember { mutableStateOf(initial.kind) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var models by remember { mutableStateOf(initial.models) }
    var defaultModelId by remember { mutableStateOf(initial.defaultModelId) }
    var showKey by remember { mutableStateOf(false) }

    var testingKey by remember { mutableStateOf(false) }
    var fetchingModels by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            message = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.ifBlank { addProviderLabel }) },
                navigationIcon = { IconButton(onClick = onClickBack) { Text("←") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomAppBar(actions = {
                if (initial.isBuiltin) {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.reset(providerId) }
                            message = context.getString(R.string.voice_provider_reset)
                        }
                    }) { Text(stringResource(R.string.voice_provider_reset)) }
                } else {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.delete(providerId) }
                            onClickBack()
                        }
                    }) { Text(stringResource(R.string.voice_provider_delete)) }
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = {
                    if (apiKey.isBlank()) {
                        message = context.getString(R.string.voice_provider_api_key_required)
                        return@Button
                    }
                    val config = initial.copy(
                        name = name.ifBlank { "Provider" },
                        kind = kind,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        models = models,
                        defaultModelId = defaultModelId,
                    )
                    scope.launch {
                        withContext(Dispatchers.IO) { store.save(config) }
                        if (settings.defaultProviderId == null) {
                            settings.defaultProviderId = providerId
                            if (defaultModelId != null) settings.defaultModelId = defaultModelId
                        }
                        onClickBack()
                    }
                }) { Text(stringResource(R.string.voice_provider_save)) }
            })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.voice_provider_name)) },
                    singleLine = true,
                    enabled = !initial.isBuiltin,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.voice_provider_kind),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = kind == ProviderKind.OPENAI_COMPATIBLE,
                        onClick = { kind = ProviderKind.OPENAI_COMPATIBLE },
                    )
                    Text(stringResource(R.string.voice_provider_kind_openai))
                    Spacer(Modifier.width(16.dp))
                    RadioButton(
                        selected = kind == ProviderKind.GEMINI,
                        onClick = { kind = ProviderKind.GEMINI },
                    )
                    Text(stringResource(R.string.voice_provider_kind_gemini))
                }
            }
            item {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.voice_provider_base_url)) },
                    singleLine = true,
                    enabled = !initial.isBuiltin,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.voice_provider_api_key)) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row {
                    FilledTonalButton(
                        onClick = {
                            fetchingModels = true
                            scope.launch {
                                val cfg = initial.copy(apiKey = apiKey, baseUrl = baseUrl, kind = kind)
                                val result = withContext(Dispatchers.IO) {
                                    VoiceProviderApi.fetchModels(cfg)
                                }
                                fetchingModels = false
                                result.onSuccess {
                                    models = it
                                    if (defaultModelId !in it.map { m -> m.id }) {
                                        defaultModelId = it.firstOrNull()?.id
                                    }
                                    message = context.getString(R.string.voice_provider_connected, it.size)
                                }.onFailure {
                                    message = context.getString(R.string.voice_provider_fetch_failed, it.message ?: "Unknown")
                                }
                            }
                        },
                        enabled = !fetchingModels && !testingKey && apiKey.isNotBlank(),
                    ) {
                        if (fetchingModels) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.voice_provider_fetch_models))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            testingKey = true
                            scope.launch {
                                val cfg = initial.copy(apiKey = apiKey, baseUrl = baseUrl, kind = kind)
                                val result = withContext(Dispatchers.IO) {
                                    VoiceProviderApi.testKey(cfg)
                                }
                                testingKey = false
                                result.onSuccess {
                                    message = context.getString(R.string.voice_provider_key_valid)
                                }.onFailure {
                                    message = context.getString(R.string.voice_provider_key_invalid, it.message ?: "Unknown")
                                }
                            }
                        },
                        enabled = !testingKey && !fetchingModels && apiKey.isNotBlank(),
                    ) {
                        if (testingKey) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.voice_provider_test_key))
                    }
                }
            }
            if (models.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.voice_provider_no_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    Text(
                        text = stringResource(R.string.voice_provider_default_model),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(models, key = { it.id }) { model ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = defaultModelId == model.id,
                            onClick = { defaultModelId = model.id },
                        )
                        Column {
                            Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                            if (model.ownedBy.isNotBlank()) {
                                Text(
                                    model.ownedBy,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun newCustomConfig(id: String): ProviderConfig {
    return ProviderConfig(
        id = id,
        name = "",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "",
        apiKey = "",
        models = emptyList(),
        defaultModelId = null,
        isBuiltin = false,
    )
}
