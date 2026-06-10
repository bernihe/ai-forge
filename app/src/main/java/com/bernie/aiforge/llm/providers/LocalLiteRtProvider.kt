package com.bernie.aiforge.llm.providers

import android.content.Context
import com.bernie.aiforge.llm.*
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs Gemma 4 E2B/4B completely on-device using Google's LiteRT-LM runtime.
 *
 * The model file (.litertlm) must be placed (or copied) into the app's
 * files directory. The user picks the file via the Settings screen and
 * the app copies it to [modelDir]. No internet required.
 *
 * Loading takes ~5-10 s — always done in the background.
 */
@Singleton
class LocalLiteRtProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : LlmProvider {

    override val id           = ProviderId.LOCAL
    override val displayName  = "Gemma 4 (on-device)"
    override val capabilities = setOf(
        Capability.STREAMING,
        Capability.OFFLINE,
    )

    private val mutex  = Mutex()
    private var engine: Engine? = null

    /** Where the app stores the copied .litertlm file. */
    val modelDir: File get() = File(context.filesDir, "models")
    val modelFile: File get() = modelDir.listFiles()
        ?.firstOrNull { it.name.endsWith(".litertlm") }
        ?: File(modelDir, "gemma4.litertlm")

    override suspend fun isAvailable(): Boolean = modelFile.exists()

    /**
     * Loads the model if not already loaded.
     * Safe to call multiple times; uses a Mutex to prevent races.
     */
    suspend fun ensureLoaded() = mutex.withLock {
        if (engine != null) return@withLock
        if (!modelFile.exists()) return@withLock

        withContext(Dispatchers.IO) {
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend   = Backend.GPU,          // falls back to CPU if GPU unavailable
            )
            engine = Engine(config).also { it.initialize() }
        }
    }

    fun unload() {
        engine?.close()
        engine = null
    }

    override fun generate(request: LlmRequest): Flow<LlmChunk> = callbackFlow {
        val eng = engine ?: run {
            trySend(LlmChunk.Error("Model not loaded. Set model path in Settings."))
            trySend(LlmChunk.Done(StopReason.ERROR))
            close()
            return@callbackFlow
        }

        val prompt = buildPrompt(request)

        eng.generateResponseAsync(prompt) { chunk, done ->
            if (chunk != null) trySend(LlmChunk.TextDelta(chunk))
            if (done) {
                trySend(LlmChunk.Done(StopReason.END_TURN))
                close()
            }
        }

        awaitClose { /* LiteRT-LM handles cancellation via Engine lifecycle */ }
    }.flowOn(Dispatchers.IO)

    // ─── Prompt formatting ────────────────────────────────────────────────────

    /**
     * Builds a Gemma-style chat prompt.
     * Format: <start_of_turn>user\n{text}<end_of_turn>\n<start_of_turn>model\n
     */
    private fun buildPrompt(request: LlmRequest): String = buildString {
        request.systemPrompt?.let {
            append("<start_of_turn>system\n$it<end_of_turn>\n")
        }
        request.messages.forEach { msg ->
            val role = when (msg.role) {
                LlmMessage.Role.USER      -> "user"
                LlmMessage.Role.ASSISTANT -> "model"
                else                      -> "user"
            }
            append("<start_of_turn>$role\n${msg.content}<end_of_turn>\n")
        }
        append("<start_of_turn>model\n")
    }
}
