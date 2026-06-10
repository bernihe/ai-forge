package com.bernie.aiforge.presentation.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bernie.aiforge.agent.AgentChunk
import com.bernie.aiforge.data.db.ChatDao
import com.bernie.aiforge.data.db.ChatEntity
import com.bernie.aiforge.data.db.MessageDao
import com.bernie.aiforge.data.db.MessageEntity
import com.bernie.aiforge.domain.usecase.SendMessageUseCase
import com.bernie.aiforge.llm.ProviderId
import com.bernie.aiforge.skills.SkillRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

// ─── UI State ─────────────────────────────────────────────────────────────────

data class ChatUiState(
    val chatId: String = "",
    val messages: List<UiMessage> = emptyList(),
    val inputText: String = "",
    val isStreaming: Boolean = false,
    val streamingText: String = "",          // text being built in real-time
    val activeProvider: ProviderId? = null,
    val activeSkillId: String = "default",
    val activeSkillName: String = "AI Assistant",
    val activeToolCall: String? = null,      // e.g. "web_search" while running
    val error: String? = null,
)

data class UiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,                        // "user" | "assistant" | "tool"
    val content: String,
    val providerName: String? = null,
    val toolName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sendMessage: SendMessageUseCase,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val skillRegistry: SkillRegistry,
) : ViewModel() {

    // Route arg: "chatId" (null → create new chat)
    private val incomingChatId: String? = savedStateHandle["chatId"]
    private val incomingSkillId: String = savedStateHandle["skillId"] ?: "default"

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val chatId = incomingChatId ?: createNewChat()
            val skill  = skillRegistry.get(incomingSkillId)
            _state.update { it.copy(
                chatId          = chatId,
                activeSkillId   = incomingSkillId,
                activeSkillName = skill.name,
            )}
            loadHistory(chatId)
        }
    }

    fun onInputChanged(text: String) =
        _state.update { it.copy(inputText = text) }

    fun onSend() {
        val text = _state.value.inputText.trim()
        if (text.isBlank() || _state.value.isStreaming) return

        val chatId  = _state.value.chatId
        val skillId = _state.value.activeSkillId

        // Optimistically add user message to UI
        val userMsg = UiMessage(role = "user", content = text)
        _state.update { it.copy(
            messages    = it.messages + userMsg,
            inputText   = "",
            isStreaming = true,
            streamingText = "",
            error       = null,
        )}

        viewModelScope.launch {
            try {
                sendMessage.execute(chatId, text, skillId).collect { chunk ->
                    when (chunk) {
                        is AgentChunk.TextDelta ->
                            _state.update { it.copy(streamingText = it.streamingText + chunk.text) }

                        is AgentChunk.ProviderSelected ->
                            _state.update { it.copy(activeProvider = chunk.provider) }

                        is AgentChunk.ToolCallStarted ->
                            _state.update { it.copy(activeToolCall = chunk.toolName) }

                        is AgentChunk.ToolCallCompleted -> {
                            val toolMsg = UiMessage(
                                role     = "tool",
                                content  = chunk.result,
                                toolName = chunk.toolName,
                            )
                            _state.update { it.copy(
                                messages      = it.messages + toolMsg,
                                activeToolCall = null,
                            )}
                        }

                        is AgentChunk.Error ->
                            _state.update { it.copy(error = chunk.message) }

                        AgentChunk.Done -> {
                            val assistantMsg = UiMessage(
                                role         = "assistant",
                                content      = _state.value.streamingText,
                                providerName = _state.value.activeProvider?.name,
                            )
                            _state.update { it.copy(
                                messages      = it.messages + assistantMsg,
                                isStreaming   = false,
                                streamingText = "",
                                activeToolCall = null,
                            )}
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(
                    isStreaming = false,
                    error       = e.message ?: "Unknown error",
                )}
            }
        }
    }

    fun onSkillChanged(skillId: String) {
        val skill = skillRegistry.get(skillId)
        _state.update { it.copy(
            activeSkillId   = skillId,
            activeSkillName = skill.name,
        )}
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ─── Private ──────────────────────────────────────────────────────────────

    private suspend fun createNewChat(): String {
        val id = UUID.randomUUID().toString()
        chatDao.insertChat(ChatEntity(
            id      = id,
            title   = "New chat",
            skillId = incomingSkillId,
        ))
        return id
    }

    private fun loadHistory(chatId: String) {
        viewModelScope.launch {
            messageDao.getMessages(chatId).collect { entities ->
                if (!_state.value.isStreaming) {
                    _state.update { s ->
                        s.copy(messages = entities.map { it.toUiMessage() })
                    }
                }
            }
        }
    }

    private fun MessageEntity.toUiMessage() = UiMessage(
        id           = id,
        role         = role,
        content      = content,
        providerName = providerName,
        toolName     = toolName,
        timestamp    = timestamp,
    )
}
