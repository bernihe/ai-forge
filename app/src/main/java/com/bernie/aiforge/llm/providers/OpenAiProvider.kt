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

private const val BASE_URL      = "https://api.openai.com/v1/chat/completions"
private const val DEFAULT_MODEL = "gpt-4o"

@Singleton
class OpenAiProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val apiKey: String,
) : LlmProvider {

    override val id           = ProviderId.OPENAI
    override val displayName  = "OpenAI GPT-4o"
    override val capabilities = setOf(
        Capability.STREAMING,
        Capability.TOOL_CALLING,
        Capability.VISION,
        Capability.LONG_CONTEXT,
    )

    override suspend fun isAvailable() = apiKey.isNotBlank()

    override fun generate(request: LlmRequest): Flow<LlmChunk> = flow {
        val body = buildRequestBody(request)
        val httpRequest = Request.Builder()
            .url(BASE_URL)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()

        val latch  = CountDownLatch(1)
        val chunks = mutableListOf<LlmChunk>()

        val listener = object : EventSourceListener() {
            override fun onEvent(src: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") { latch.countDown(); return }
                runCatching {
                    val json  = Json.parseToJsonElement(data).jsonObject
                    val delta = json["choices"]?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("delta")?.jsonObject ?: return

                    // Text
                    delta["content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                        if (text.isNotEmpty())
                            synchronized(chunks) { chunks += LlmChunk.TextDelta(text) }
                    }

                    // Tool calls
                    delta["tool_calls"]?.jsonArray?.forEach { tc ->
                        val obj = tc.jsonObject
                        val fn  = obj["function"]?.jsonObject ?: return@forEach
                        synchronized(chunks) {
                            chunks += LlmChunk.ToolCallDelta(
                                ToolCall(
                                    id            = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                                    name          = fn["name"]?.jsonPrimitive?.contentOrNull ?: "",
                                    argumentsJson = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                                )
                            )
                        }
                    }

                    // Check finish
                    val finishReason = json["choices"]?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("finish_reason")?.jsonPrimitive?.contentOrNull
                    if (finishReason != null) latch.countDown()
                }
            }

            override fun onFailure(src: EventSource, t: Throwable?, resp: Response?) {
                synchronized(chunks) {
                    chunks += LlmChunk.Error(t?.message ?: "OpenAI error")
                }
                latch.countDown()
            }
        }

        EventSources.createFactory(okHttpClient).newEventSource(httpRequest, listener)
        latch.await()

        chunks.forEach { emit(it) }
        emit(LlmChunk.Done(StopReason.END_TURN))
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(request: LlmRequest): String = buildJsonObject {
        put("model", DEFAULT_MODEL)
        put("max_tokens", request.maxTokens)
        put("stream", true)
        put("temperature", request.temperature.toDouble())

        putJsonArray("messages") {
            request.systemPrompt?.let {
                addJsonObject { put("role", "system"); put("content", it) }
            }
            request.messages.forEach { msg ->
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
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            putJsonObject("parameters") {
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
        }
    }.toString()
}
