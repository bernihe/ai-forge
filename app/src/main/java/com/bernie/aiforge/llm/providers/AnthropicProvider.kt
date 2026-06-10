package com.bernie.aiforge.llm.providers

import com.bernie.aiforge.llm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.CountDownLatch
import javax.inject.Inject
import javax.inject.Singleton

private const val BASE_URL        = "https://api.anthropic.com/v1/messages"
private const val ANTHROPIC_VER   = "2023-06-01"
private const val DEFAULT_MODEL   = "claude-sonnet-4-20250514"

@Singleton
class AnthropicProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val apiKey: String,        // injected by Hilt from ApiKeyVault
) : LlmProvider {

    override val id          = ProviderId.ANTHROPIC
    override val displayName = "Anthropic Claude"
    override val capabilities = setOf(
        Capability.STREAMING,
        Capability.TOOL_CALLING,
        Capability.LONG_CONTEXT,
    )

    override suspend fun isAvailable() = apiKey.isNotBlank()

    override fun generate(request: LlmRequest): Flow<LlmChunk> = flow {
        val body = buildRequestBody(request)
        val httpRequest = Request.Builder()
            .url(BASE_URL)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VER)
            .header("content-type", "application/json")
            .build()

        val latch  = CountDownLatch(1)
        val chunks = mutableListOf<LlmChunk>()
        var done   = false

        val listener = object : EventSourceListener() {
            override fun onEvent(src: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") { done = true; latch.countDown(); return }
                runCatching {
                    val json = Json.parseToJsonElement(data).jsonObject
                    val eventType = json["type"]?.jsonPrimitive?.content ?: return
                    when (eventType) {
                        "content_block_delta" -> {
                            val delta = json["delta"]?.jsonObject ?: return
                            when (delta["type"]?.jsonPrimitive?.content) {
                                "text_delta" -> {
                                    val text = delta["text"]?.jsonPrimitive?.content ?: return
                                    synchronized(chunks) { chunks += LlmChunk.TextDelta(text) }
                                }
                                "input_json_delta" -> {
                                    // tool call argument streaming – handled on message_stop
                                }
                            }
                        }
                        "content_block_start" -> {
                            val block = json["content_block"]?.jsonObject ?: return
                            if (block["type"]?.jsonPrimitive?.content == "tool_use") {
                                val call = ToolCall(
                                    id            = block["id"]?.jsonPrimitive?.content ?: "",
                                    name          = block["name"]?.jsonPrimitive?.content ?: "",
                                    argumentsJson = block["input"]?.toString() ?: "{}",
                                )
                                synchronized(chunks) { chunks += LlmChunk.ToolCallDelta(call) }
                            }
                        }
                        "message_stop" -> { latch.countDown() }
                    }
                }
            }
            override fun onFailure(src: EventSource, t: Throwable?, resp: Response?) {
                synchronized(chunks) {
                    chunks += LlmChunk.Error(t?.message ?: resp?.message ?: "Unknown error")
                }
                latch.countDown()
            }
        }

        EventSources.createFactory(okHttpClient).newEventSource(httpRequest, listener)
        latch.await()

        // Emit collected chunks
        for (chunk in chunks) emit(chunk)
        if (done) emit(LlmChunk.Done(StopReason.END_TURN))
    }.flowOn(Dispatchers.IO)

    // ─── Request builder ─────────────────────────────────────────────────────

    private fun buildRequestBody(request: LlmRequest): String = buildJsonObject {
        put("model", DEFAULT_MODEL)
        put("max_tokens", request.maxTokens)
        put("stream", true)
        put("temperature", request.temperature.toDouble())

        request.systemPrompt?.let { put("system", it) }

        putJsonArray("messages") {
            request.messages
                .filter { it.role != LlmMessage.Role.SYSTEM }
                .forEach { msg ->
                    addJsonObject {
                        put("role", msg.role.name.lowercase())
                        put("content", msg.content)
                    }
                }
        }

        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        putJsonObject("input_schema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                tool.parameters.forEach { (k, v) ->
                                    putJsonObject(k) {
                                        put("type", v.type)
                                        put("description", v.description)
                                    }
                                }
                            }
                            putJsonArray("required") {
                                tool.requiredParams.forEach { add(it) }
                            }
                        }
                    }
                }
            }
        }
    }.toString()
}
