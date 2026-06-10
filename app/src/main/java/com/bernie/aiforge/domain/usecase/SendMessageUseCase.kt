package com.bernie.aiforge.domain.usecase

import com.bernie.aiforge.agent.AgentChunk
import com.bernie.aiforge.agent.AgentEngine
import com.bernie.aiforge.data.db.MessageDao
import com.bernie.aiforge.data.db.MessageEntity
import com.bernie.aiforge.data.preferences.UserSettingsRepository
import com.bernie.aiforge.llm.LlmMessage
import com.bernie.aiforge.memory.MemoryExtractor
import com.bernie.aiforge.skills.ActiveSkill
import com.bernie.aiforge.skills.SkillRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val agentEngine: AgentEngine,
    private val skillRegistry: SkillRegistry,
    private val messageDao: MessageDao,
    private val memoryExtractor: MemoryExtractor,
    private val settingsRepo: UserSettingsRepository,
) {
    /**
     * Sends [userInput] in the context of [chatId] using [skillId].
     *
     * 1. Loads conversation history from Room
     * 2. Fetches relevant memories
     * 3. Runs the agent loop (streaming)
     * 4. Persists user + assistant messages
     * 5. Triggers async memory extraction after response
     */
    suspend fun execute(
        chatId: String,
        userInput: String,
        skillId: String,
    ): Flow<AgentChunk> {
        val settings    = settingsRepo.userSettings.first()
        val skill: ActiveSkill = skillRegistry.getActive(skillId)
        val memories    = if (settings.memoryEnabled) memoryExtractor.getMemoriesForPrompt() else ""
        val rawHistory  = messageDao.getMessagesOnce(chatId)

        // Convert DB messages to LLM format
        val history = rawHistory.map { msg ->
            LlmMessage(
                role    = when (msg.role) {
                    "user"      -> LlmMessage.Role.USER
                    "assistant" -> LlmMessage.Role.ASSISTANT
                    else        -> LlmMessage.Role.TOOL
                },
                content = msg.content,
            )
        }

        // Save user message to DB
        messageDao.insertMessage(
            MessageEntity(chatId = chatId, role = "user", content = userInput)
        )

        val assistantBuffer = StringBuilder()
        var activeProvider  = ""

        return agentEngine.run(
            history     = history,
            userMessage = userInput,
            skill       = skill,
            memories    = memories,
        ).onStart {
            // nothing yet
        }.onCompletion {
            // Persist assistant response
            if (assistantBuffer.isNotEmpty()) {
                messageDao.insertMessage(
                    MessageEntity(
                        chatId       = chatId,
                        role         = "assistant",
                        content      = assistantBuffer.toString(),
                        providerName = activeProvider,
                    )
                )
            }

            // Auto-extract memories if enabled (fire-and-forget)
            if (settings.autoExtractMemories && rawHistory.size % 4 == 0) {
                val fullConversation = (rawHistory.map { "${it.role}: ${it.content}" } +
                        listOf("user: $userInput", "assistant: ${assistantBuffer}"))
                    .joinToString("\n")
                memoryExtractor.extractAndStore(fullConversation, chatId)
            }
        }.also { flow ->
            // Tap the flow to buffer assistant text (without consuming it)
            // The ViewModel collects the same flow reference; we tap via transform
        }.let { upstream ->
            // Intercept to buffer text for persistence
            kotlinx.coroutines.flow.flow {
                upstream.collect { chunk ->
                    when (chunk) {
                        is AgentChunk.TextDelta       -> assistantBuffer.append(chunk.text)
                        is AgentChunk.ProviderSelected -> activeProvider = chunk.provider.name
                        else                           -> Unit
                    }
                    emit(chunk)
                }
            }
        }
    }
}
