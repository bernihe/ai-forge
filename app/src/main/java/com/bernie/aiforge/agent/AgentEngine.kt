package com.bernie.aiforge.agent

import com.bernie.aiforge.llm.*
import com.bernie.aiforge.skills.ActiveSkill
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_TOOL_ITERATIONS = 8

/**
 * Orchestrates multi-turn, tool-enabled LLM interactions.
 *
 * Flow:
 *  1. Build [LlmRequest] from conversation history + active skill config
 *  2. Stream response from [LlmRouter]-selected provider
 *  3. If the response contains a [LlmChunk.ToolCallDelta], execute the tool
 *  4. Append the tool result and loop (up to MAX_TOOL_ITERATIONS)
 *  5. Emit all text deltas back to the ViewModel
 */
@Singleton
class AgentEngine @Inject constructor(
    private val router: LlmRouter,
    private val toolRegistry: ToolRegistry,
) {
    /**
     * Runs the full agent loop.
     *
     * @param history     The full conversation history (user + assistant turns so far).
     * @param userMessage The new user message to process.
     * @param skill       The currently active skill (provides system prompt, tools, model params).
     * @param memories    Relevant memories to inject into the system prompt.
     */
    fun run(
        history: List<LlmMessage>,
        userMessage: String,
        skill: ActiveSkill,
        memories: String = "",
    ): Flow<AgentChunk> = flow {
        val systemPrompt = buildSystemPrompt(skill, memories)
        val tools = toolRegistry.getTools(skill.toolNames)
            .map { it.definition }

        val mutableHistory = history.toMutableList().also {
            it += LlmMessage(LlmMessage.Role.USER, userMessage)
        }

        var iterations = 0
        var continueLoop = true

        while (continueLoop && iterations < MAX_TOOL_ITERATIONS) {
            iterations++

            val request = LlmRequest(
                messages      = mutableHistory,
                systemPrompt  = systemPrompt,
                tools         = tools,
                temperature   = skill.temperature,
                maxTokens     = skill.maxTokens,
                providerId    = skill.preferredProvider,
                requiresTools = tools.isNotEmpty(),
            )

            val (provider, finalRequest) = router.select(request)
            emit(AgentChunk.ProviderSelected(provider.id))

            val pendingToolCalls = mutableListOf<LlmChunk.ToolCallDelta>()
            val fullText         = StringBuilder()
            var stopReason       = StopReason.END_TURN

            provider.generate(finalRequest).collect { chunk ->
                when (chunk) {
                    is LlmChunk.TextDelta     -> {
                        fullText.append(chunk.text)
                        emit(AgentChunk.TextDelta(chunk.text))
                    }
                    is LlmChunk.ToolCallDelta -> pendingToolCalls += chunk
                    is LlmChunk.Done          -> stopReason = chunk.stopReason
                    is LlmChunk.Error         -> {
                        emit(AgentChunk.Error(chunk.message))
                        continueLoop = false
                    }
                }
            }

            // Add assistant turn to history
            if (fullText.isNotEmpty()) {
                mutableHistory += LlmMessage(LlmMessage.Role.ASSISTANT, fullText.toString())
            }

            // Execute tool calls
            if (pendingToolCalls.isNotEmpty() && stopReason == StopReason.TOOL_USE) {
                for (toolCall in pendingToolCalls) {
                    emit(AgentChunk.ToolCallStarted(toolCall.call.name))
                    val tool   = toolRegistry.getTool(toolCall.call.name)
                    val result = tool?.execute(toolCall.call.argumentsJson)
                        ?: com.bernie.aiforge.llm.ToolResult(
                            toolCallId = toolCall.call.id,
                            content    = "Tool '${toolCall.call.name}' not found.",
                            isError    = true,
                        )
                    emit(AgentChunk.ToolCallCompleted(toolCall.call.name, result.content))
                    mutableHistory += LlmMessage(
                        role         = LlmMessage.Role.TOOL,
                        content      = result.content,
                        toolResults  = listOf(result.copy(toolCallId = toolCall.call.id)),
                    )
                }
            } else {
                continueLoop = false
            }
        }

        emit(AgentChunk.Done)
    }

    private fun buildSystemPrompt(skill: ActiveSkill, memories: String): String = buildString {
        append(skill.systemPrompt)
        if (memories.isNotBlank()) {
            appendLine()
            appendLine()
            append("## What I know about you\n$memories")
        }
    }
}

// ─── Agent output chunks (higher-level than LlmChunk) ────────────────────────

sealed class AgentChunk {
    data class TextDelta(val text: String) : AgentChunk()
    data class ToolCallStarted(val toolName: String) : AgentChunk()
    data class ToolCallCompleted(val toolName: String, val result: String) : AgentChunk()
    data class ProviderSelected(val provider: ProviderId) : AgentChunk()
    data class Error(val message: String) : AgentChunk()
    object Done : AgentChunk()
}

// ─── Tool registry ────────────────────────────────────────────────────────────

@Singleton
class ToolRegistry @Inject constructor(
    private val webSearch: WebSearchTool,
    private val fileRead: FileReadTool,
) {
    private val tools: Map<String, Tool> by lazy {
        listOf(webSearch, fileRead)
            .associateBy { it.name }
    }

    fun getTools(names: List<String>): List<Tool> =
        names.mapNotNull { tools[it] }

    fun getTool(name: String): Tool? = tools[name]

    fun allTools(): List<Tool> = tools.values.toList()
}
