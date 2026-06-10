package com.bernie.aiforge.memory

import com.bernie.aiforge.data.db.MemoryDao
import com.bernie.aiforge.data.db.MemoryEntity
import com.bernie.aiforge.llm.*
import com.bernie.aiforge.llm.providers.AnthropicProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val EXTRACTION_PROMPT = """
You are a memory extraction assistant. Given a conversation, extract key facts 
about the user that are worth remembering for future interactions.

Focus on:
- Personal details (name, location, profession, preferences)
- Recurring needs or tasks
- Technical setup (tools, languages, systems they use)
- Explicit preferences they've stated

Return ONLY a JSON array of short fact strings (max 15 words each).
Example: ["User lives in Palermo, CABA, Argentina", "User uses Kotlin for Android development"]
If there's nothing worth remembering, return an empty array: []
"""

@Singleton
class MemoryExtractor @Inject constructor(
    private val anthropicProvider: AnthropicProvider,
    private val memoryDao: MemoryDao,
) {
    /**
     * Analyses [conversation] and stores any newly extracted facts.
     * Uses Claude as the extraction engine (best at following JSON format).
     * Falls back silently if not available.
     */
    suspend fun extractAndStore(conversation: String, chatId: String) =
        withContext(Dispatchers.IO) {
            if (!anthropicProvider.isAvailable()) return@withContext

            runCatching {
                val request = LlmRequest(
                    systemPrompt = EXTRACTION_PROMPT,
                    messages     = listOf(
                        LlmMessage(LlmMessage.Role.USER, conversation)
                    ),
                    temperature  = 0.1f,
                    maxTokens    = 512,
                )

                // Collect full response
                val fullText = anthropicProvider.generate(request)
                    .filterIsInstance<LlmChunk.TextDelta>()
                    .fold("") { acc, chunk -> acc + chunk.text }

                // Parse JSON array
                val cleaned = fullText.trim()
                    .removePrefix("```json").removeSuffix("```").trim()

                val facts = Json.parseToJsonElement(cleaned).jsonArray
                    .map { it.jsonPrimitive.content }
                    .filter { it.isNotBlank() }

                // Store each fact
                facts.forEach { fact ->
                    memoryDao.insertMemory(
                        MemoryEntity(
                            content      = fact,
                            sourceChatId = chatId,
                        )
                    )
                }
            } // silent on failure
        }

    /** Returns a formatted string of all active memories for injection into prompts. */
    suspend fun getMemoriesForPrompt(): String {
        val memories = memoryDao.getActiveMemoriesOnce()
        if (memories.isEmpty()) return ""
        return memories.joinToString("\n") { "- ${it.content}" }
    }
}
