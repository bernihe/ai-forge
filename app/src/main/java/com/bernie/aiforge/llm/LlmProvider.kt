package com.bernie.aiforge.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

// ─── Provider identity ────────────────────────────────────────────────────────

enum class ProviderId { ANTHROPIC, OPENAI, GEMINI, LOCAL }

enum class Capability {
    STREAMING,
    TOOL_CALLING,
    VISION,              // image input
    LONG_CONTEXT,        // 128k+ tokens
    OFFLINE,             // works without internet
}

// ─── Request ─────────────────────────────────────────────────────────────────

data class LlmRequest(
    val messages: List<LlmMessage>,
    val systemPrompt: String? = null,
    val tools: List<LlmTool> = emptyList(),
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val topK: Int = 40,
    val providerId: ProviderId? = null,   // null = let router decide
    val requiresTools: Boolean = false,
    val requiresOffline: Boolean = false,
)

data class LlmMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResults: List<ToolResult> = emptyList(),
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

// ─── Tool definitions (sent to the LLM so it knows what tools exist) ─────────

data class LlmTool(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParameter>,
    val requiredParams: List<String> = emptyList(),
)

data class ToolParameter(
    val type: String,         // "string" | "number" | "boolean" | "array"
    val description: String,
    val enum: List<String> = emptyList(),
)

// ─── Streaming output ─────────────────────────────────────────────────────────

sealed class LlmChunk {
    /** A fragment of generated text. */
    data class TextDelta(val text: String) : LlmChunk()
    /** The LLM wants to call a tool. */
    data class ToolCallDelta(val call: ToolCall) : LlmChunk()
    /** Generation is complete. */
    data class Done(val stopReason: StopReason) : LlmChunk()
    /** Provider-level error. */
    data class Error(val message: String) : LlmChunk()
}

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class ToolResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false,
)

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, ERROR }

// ─── Provider interface ───────────────────────────────────────────────────────

interface LlmProvider {
    val id: ProviderId
    val displayName: String
    val capabilities: Set<Capability>

    /** Returns true if the provider can currently service requests. */
    suspend fun isAvailable(): Boolean

    /**
     * Streams the response to [request].
     * The flow emits [LlmChunk.TextDelta] fragments followed by
     * exactly one terminal chunk ([LlmChunk.Done] or [LlmChunk.Error]).
     */
    fun generate(request: LlmRequest): Flow<LlmChunk>
}
