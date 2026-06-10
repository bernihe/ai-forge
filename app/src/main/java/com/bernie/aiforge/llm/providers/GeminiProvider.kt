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
import javax.inject.Inject
import javax.inject.Singleton

private const val DEFAULT_MODEL = "gemini-2.0-flash"
private const val BASE_URL      =
    "https://generativelanguage.googleapis.com/v1beta/models/$DEFAULT_MODEL:streamGenerateContent"

@Singleton
class GeminiProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val apiKey: String,
) : LlmProvider {

    override val id           = ProviderId.GEMINI
    override val displayName  = "Google Gemini Flash"
    override val capabilities = setOf(
        Capability.STREAMING,
        Capability.TOOL_CALLING,
        Capability.VISION,
        Capability.LONG_CONTEXT,
    )

    override suspend fun isAvailable() = apiKey.isNotBlank()

    override fun generate(request: LlmRequest): Flow<LlmChunk> = flow {
        val body = buildRequestBody(request)
        val url  = "$BASE_URL?key=$apiKey&alt=sse"

        val httpRequest = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = okHttpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            emit(LlmChunk.Error("Gemini HTTP ${response.code}: ${response.message}"))
            emit(LlmChunk.Done(StopReason.ERROR))
            return@flow
        }

        val source = response.body?.source() ?: run {
            emit(LlmChunk.Error("Empty Gemini response body"))
            emit(LlmChunk.Done(StopReason.ERROR))
            return@flow
        }

        // Parse newline-delimited JSON (alt=sse format)
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isBlank() || data == "[DONE]") continue

            runCatching {
                val json = Json.parseToJsonElement(data).jsonObject
                val candidates = json["candidates"]?.jsonArray ?: return@runCatching
                val parts = candidates.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray ?: return@runCatching

                parts.forEach { part ->
                    val partObj = part.jsonObject
                    // Text
                    partObj["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                        emit(LlmChunk.TextDelta(text))
                    }
                    // Function call
                    partObj["functionCall"]?.jsonObject?.let { fn ->
                        emit(
                            LlmChunk.ToolCallDelta(
                                ToolCall(
                                    id            = fn["name"]?.jsonPrimitive?.content ?: "",
                                    name          = fn["name"]?.jsonPrimitive?.content ?: "",
                                    argumentsJson = fn["args"]?.toString() ?: "{}",
                                )
                            )
                        )
                    }
                }

                // Check finish
                val finishReason = candidates.firstOrNull()?.jsonObject
                    ?.get("finishReason")?.jsonPrimitive?.contentOrNull
                if (finishReason == "STOP") emit(LlmChunk.Done(StopReason.END_TURN))
            }
        }

        emit(LlmChunk.Done(StopReason.END_TURN))
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(request: LlmRequest): String = buildJsonObject {
        request.systemPrompt?.let {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", it) } }
            }
        }

        putJsonArray("contents") {
            request.messages.forEach { msg ->
                addJsonObject {
                    put("role", if (msg.role == LlmMessage.Role.ASSISTANT) "model" else "user")
                    putJsonArray("parts") { addJsonObject { put("text", msg.content) } }
                }
            }
        }

        putJsonObject("generationConfig") {
            put("temperature", request.temperature.toDouble())
            put("maxOutputTokens", request.maxTokens)
            put("topK", request.topK)
        }

        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                addJsonObject {
                    putJsonArray("functionDeclarations") {
                        request.tools.forEach { tool ->
                            addJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                putJsonObject("parameters") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        tool.parameters.forEach { (k, v) ->
                                            putJsonObject(k) {
                                                put("type", v.type.uppercase())
                                                put("description", v.description)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }.toString()
}
