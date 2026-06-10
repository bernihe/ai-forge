package com.bernie.aiforge.agent

import com.bernie.aiforge.llm.LlmTool
import com.bernie.aiforge.llm.ToolParameter
import com.bernie.aiforge.llm.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

// ─── Tool abstraction ─────────────────────────────────────────────────────────

interface Tool {
    /** Matches the tool name in the LLM's tool call. */
    val name: String
    val description: String
    /** Converts this tool to the definition format sent to the LLM. */
    val definition: LlmTool
    /** Executes the tool given the JSON arguments string from the LLM. */
    suspend fun execute(argumentsJson: String): ToolResult
}

// ─── Web search tool (DuckDuckGo Instant Answer API — no API key needed) ──────

@Singleton
class WebSearchTool @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : Tool {

    override val name        = "web_search"
    override val description = "Searches the web for current information. Use for facts, news, prices, or anything that might have changed recently."

    override val definition = LlmTool(
        name          = name,
        description   = description,
        parameters    = mapOf(
            "query" to ToolParameter(
                type        = "string",
                description = "The search query. Be specific and concise.",
            )
        ),
        requiredParams = listOf("query"),
    )

    override suspend fun execute(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val query = runCatching {
            Json.parseToJsonElement(argumentsJson)
                .jsonObject["query"]?.jsonPrimitive?.content ?: ""
        }.getOrElse { return@withContext ToolResult("", "Invalid arguments: $it", isError = true) }

        if (query.isBlank()) {
            return@withContext ToolResult("", "Query cannot be empty", isError = true)
        }

        runCatching {
            val encodedQuery = query.replace(" ", "+")
            val url = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_redirect=1&no_html=1"
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@runCatching ToolResult("", "Empty response", isError = true)

            val json = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(body).jsonObject

            val abstract  = json["AbstractText"]?.jsonPrimitive?.content
            val answer    = json["Answer"]?.jsonPrimitive?.content
            val relatedTopics = json["RelatedTopics"]?.let { rt ->
                // Pull first 3 related topic texts
                runCatching {
                    rt.jsonObject["topics"]?.toString() ?: ""
                }.getOrElse { "" }
            }

            val result = buildString {
                if (!answer.isNullOrBlank())   appendLine("Answer: $answer")
                if (!abstract.isNullOrBlank()) appendLine("Summary: $abstract")
                if (!relatedTopics.isNullOrBlank()) appendLine("Related: $relatedTopics")
                if (isBlank()) append("No direct answer found for: $query")
            }.trim()

            ToolResult(toolCallId = "", content = result)
        }.getOrElse {
            ToolResult(toolCallId = "", content = "Search failed: ${it.message}", isError = true)
        }
    }
}

// ─── File read tool ───────────────────────────────────────────────────────────

@Singleton
class FileReadTool @Inject constructor() : Tool {

    override val name        = "read_file"
    override val description = "Reads the content of a file from the device. Use absolute paths."

    override val definition = LlmTool(
        name          = name,
        description   = description,
        parameters    = mapOf(
            "path" to ToolParameter("string", "Absolute path to the file."),
            "maxChars" to ToolParameter("number", "Maximum characters to read. Default 4000."),
        ),
        requiredParams = listOf("path"),
    )

    override suspend fun execute(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val obj      = Json.parseToJsonElement(argumentsJson).jsonObject
            val path     = obj["path"]?.jsonPrimitive?.content ?: return@runCatching ToolResult("", "No path provided", isError = true)
            val maxChars = obj["maxChars"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4000
            val file     = java.io.File(path)
            if (!file.exists()) return@runCatching ToolResult("", "File not found: $path", isError = true)
            val content = file.readText().take(maxChars)
            ToolResult(toolCallId = "", content = content)
        }.getOrElse {
            ToolResult(toolCallId = "", content = "File read error: ${it.message}", isError = true)
        }
    }
}
