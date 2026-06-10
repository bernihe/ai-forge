package com.bernie.aiforge.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bernie.aiforge.data.preferences.UserSettings
import com.bernie.aiforge.data.preferences.UserSettingsRepository
import com.bernie.aiforge.data.security.ApiKeyVault
import com.bernie.aiforge.llm.ProviderId
import com.bernie.aiforge.llm.providers.LocalLiteRtProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: UserSettingsRepository,
    private val apiKeyVault: ApiKeyVault,
    private val localProvider: LocalLiteRtProvider,
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepo.userSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    // Key states (masked for display)
    val anthropicKeySet get() = apiKeyVault.hasKey(ProviderId.ANTHROPIC)
    val openAiKeySet    get() = apiKeyVault.hasKey(ProviderId.OPENAI)
    val geminiKeySet    get() = apiKeyVault.hasKey(ProviderId.GEMINI)
    val localModelReady get() = localProvider.modelFile.exists()

    fun saveApiKey(provider: ProviderId, key: String) {
        if (key.isBlank()) apiKeyVault.clear(provider)
        else               apiKeyVault.set(provider, key)
    }

    fun setDefaultProvider(provider: ProviderId) = viewModelScope.launch {
        settingsRepo.setDefaultProvider(provider)
    }

    fun setGlobalInstructions(text: String) = viewModelScope.launch {
        settingsRepo.setGlobalInstructions(text)
    }

    fun setMemoryEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setMemoryEnabled(enabled)
    }

    fun setLocalModelPath(path: String) = viewModelScope.launch {
        settingsRepo.setLocalModelPath(path)
        // Trigger model reload on next use
        localProvider.unload()
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── API Keys ─────────────────────────────────────────────────────
            item { SectionTitle("API Keys") }

            item {
                ApiKeyField(
                    label    = "Anthropic (Claude)",
                    emoji    = "🟠",
                    isSet    = viewModel.anthropicKeySet,
                    onSave   = { viewModel.saveApiKey(ProviderId.ANTHROPIC, it) },
                )
            }
            item {
                ApiKeyField(
                    label    = "OpenAI (GPT-4o)",
                    emoji    = "🟢",
                    isSet    = viewModel.openAiKeySet,
                    onSave   = { viewModel.saveApiKey(ProviderId.OPENAI, it) },
                )
            }
            item {
                ApiKeyField(
                    label    = "Google (Gemini)",
                    emoji    = "🔵",
                    isSet    = viewModel.geminiKeySet,
                    onSave   = { viewModel.saveApiKey(ProviderId.GEMINI, it) },
                )
            }

            // ── Local model ───────────────────────────────────────────────
            item { SectionTitle("On-device Model") }
            item {
                LocalModelCard(
                    isReady   = viewModel.localModelReady,
                    modelPath = settings.localModelPath,
                    onPathSet = viewModel::setLocalModelPath,
                )
            }

            // ── Default provider ──────────────────────────────────────────
            item { SectionTitle("Default Provider") }
            item {
                ProviderPicker(
                    current   = settings.defaultProvider,
                    onSelect  = viewModel::setDefaultProvider,
                )
            }

            // ── Personalization ───────────────────────────────────────────
            item { SectionTitle("Global Instructions") }
            item {
                GlobalInstructionsField(
                    value   = settings.globalSystemInstructions,
                    onSave  = viewModel::setGlobalInstructions,
                )
            }

            // ── Memory ────────────────────────────────────────────────────
            item { SectionTitle("Memory") }
            item {
                ListItem(
                    headlineContent   = { Text("Memory enabled") },
                    supportingContent = { Text("Remember facts across conversations") },
                    trailingContent   = {
                        Switch(
                            checked         = settings.memoryEnabled,
                            onCheckedChange = viewModel::setMemoryEnabled,
                        )
                    },
                )
            }
        }
    }
}

// ─── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.titleSmall,
        color    = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ApiKeyField(
    label: String,
    emoji: String,
    isSet: Boolean,
    onSave: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var text    by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    ElevatedCard {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (isSet) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp))
                }
                TextButton(onClick = { editing = !editing; text = "" }) {
                    Text(if (editing) "Cancel" else if (isSet) "Replace" else "Add")
                }
            }
            if (editing) {
                OutlinedTextField(
                    value         = text,
                    onValueChange = { text = it },
                    placeholder   = { Text("sk-ant-api03-…") },
                    singleLine    = true,
                    visualTransformation = if (visible) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    trailingIcon  = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier      = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick  = { onSave(text); editing = false },
                    enabled  = text.isNotBlank(),
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun LocalModelCard(isReady: Boolean, modelPath: String, onPathSet: (String) -> Unit) {
    var pathText by remember(modelPath) { mutableStateOf(modelPath) }
    ElevatedCard {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🖥️ Gemma 4 E2/4B", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (isReady) {
                    AssistChip(onClick = {}, label = { Text("Ready") },
                        leadingIcon = { Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp)) })
                } else {
                    AssistChip(onClick = {}, label = { Text("Not found") })
                }
            }
            Text(
                "Copy your .litertlm file to the app's files/models/ folder, then paste the path here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            OutlinedTextField(
                value         = pathText,
                onValueChange = { pathText = it },
                placeholder   = { Text("/data/data/com.bernie.aiforge/files/models/gemma4.litertlm") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
                label         = { Text("Model path") },
            )
            Button(
                onClick = { onPathSet(pathText) },
                modifier = Modifier.align(Alignment.End),
            ) { Text("Set path") }
        }
    }
}

@Composable
private fun ProviderPicker(current: ProviderId, onSelect: (ProviderId) -> Unit) {
    val providers = listOf(
        ProviderId.ANTHROPIC to "Claude (Anthropic)",
        ProviderId.OPENAI    to "GPT-4o (OpenAI)",
        ProviderId.GEMINI    to "Gemini (Google)",
        ProviderId.LOCAL     to "Gemma 4 (on-device)",
    )
    Column {
        providers.forEach { (id, label) ->
            ListItem(
                headlineContent = { Text(label) },
                leadingContent  = {
                    RadioButton(selected = current == id, onClick = { onSelect(id) })
                },
                modifier = Modifier.clickable { onSelect(id) },
            )
        }
    }
}

@Composable
private fun GlobalInstructionsField(value: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value         = text,
            onValueChange = { text = it },
            placeholder   = { Text("e.g. Always respond in Spanish. I am a software engineer in Buenos Aires.") },
            minLines      = 3,
            maxLines      = 6,
            modifier      = Modifier.fillMaxWidth(),
            label         = { Text("Applied to every conversation") },
        )
        Button(
            onClick  = { onSave(text) },
            modifier = Modifier.align(Alignment.End),
        ) { Text("Save") }
    }
}

// Needed for clickable on ListItem
private fun Modifier.clickable(onClick: () -> Unit) =
    this.then(Modifier.clickable(onClick = onClick))
