package com.bernie.aiforge.presentation.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bernie.aiforge.llm.ProviderId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    // Auto-scroll on new messages
    val messageCount = state.messages.size + if (state.isStreaming) 1 else 0
    LaunchedEffect(messageCount) {
        if (messageCount > 0) listState.animateScrollToItem(messageCount - 1)
    }

    // Error snackbar
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let { err ->
            snackbarHost.showSnackbar(err, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.activeSkillName, style = MaterialTheme.typography.titleMedium)
                        state.activeProvider?.let { provider ->
                            Text(
                                providerLabel(provider),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { /* TODO: skill picker */ }) {
                        Icon(Icons.Default.Tune, contentDescription = "Change skill")
                    }
                },
            )
        },
        bottomBar = {
            ChatInput(
                text      = state.inputText,
                isLoading = state.isStreaming,
                onChange  = viewModel::onInputChanged,
                onSend    = { viewModel.onSend(); keyboard?.hide() },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        LazyColumn(
            state           = listState,
            contentPadding  = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier        = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.messages.isEmpty() && !state.isStreaming) {
                item { WelcomePlaceholder(state.activeSkillName) }
            }

            items(state.messages, key = { it.id }) { msg ->
                when (msg.role) {
                    "user"      -> UserBubble(msg.content)
                    "assistant" -> AssistantBubble(msg.content, msg.providerName)
                    "tool"      -> ToolResultBubble(msg.toolName ?: "tool", msg.content)
                }
            }

            // Live streaming bubble
            if (state.isStreaming) {
                item {
                    state.activeToolCall?.let { toolName ->
                        ToolCallIndicator(toolName)
                    } ?: AssistantBubble(
                        text         = state.streamingText,
                        providerName = state.activeProvider?.name,
                        isStreaming  = true,
                    )
                }
            }
        }
    }
}

// ─── Input bar ────────────────────────────────────────────────────────────────

@Composable
private fun ChatInput(
    text: String,
    isLoading: Boolean,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(shadowElevation = 6.dp) {
        Row(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment   = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value         = text,
                onValueChange = onChange,
                placeholder   = { Text("Ask anything…") },
                maxLines      = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier      = Modifier.weight(1f),
                shape         = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick  = onSend,
                enabled  = text.isNotBlank() && !isLoading,
                modifier = Modifier.size(48.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color       = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}

// ─── Message bubbles ──────────────────────────────────────────────────────────

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color  = MaterialTheme.colorScheme.primaryContainer,
            shape  = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text     = text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style    = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AssistantBubble(
    text: String,
    providerName: String?,
    isStreaming: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color  = MaterialTheme.colorScheme.surfaceVariant,
            shape  = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (text.isNotEmpty()) {
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                } else if (isStreaming) {
                    // Blinking cursor when waiting for first token
                    Text("▌", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (isStreaming && text.isNotEmpty()) {
                    Text("▌", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        providerName?.let {
            Text(
                text     = providerBadge(it),
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun ToolResultBubble(toolName: String, result: String) {
    Surface(
        color  = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        shape  = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint     = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(4.dp))
                Text(
                    text  = toolName.replace("_", " ").capitalize(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text  = result.take(300) + if (result.length > 300) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize   = 11.sp,
            )
        }
    }
}

@Composable
private fun ToolCallIndicator(toolName: String) {
    Row(
        verticalAlignment  = Alignment.CenterVertically,
        modifier           = Modifier.padding(4.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text  = "Running ${toolName.replace("_", " ")}…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun WelcomePlaceholder(skillName: String) {
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("✨", style = MaterialTheme.typography.displaySmall)
        Text(skillName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Start a conversation",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun providerLabel(provider: ProviderId) = when (provider) {
    ProviderId.ANTHROPIC -> "Claude"
    ProviderId.OPENAI    -> "GPT-4o"
    ProviderId.GEMINI    -> "Gemini"
    ProviderId.LOCAL     -> "Gemma 4 · on-device"
}

private fun providerBadge(name: String) = when (name.uppercase()) {
    "ANTHROPIC" -> "via Claude"
    "OPENAI"    -> "via GPT-4o"
    "GEMINI"    -> "via Gemini"
    "LOCAL"     -> "on-device · private"
    else        -> "via $name"
}

private fun String.capitalize() =
    replaceFirstChar { it.uppercaseChar() }
