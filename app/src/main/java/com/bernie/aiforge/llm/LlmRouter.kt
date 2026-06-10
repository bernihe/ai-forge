package com.bernie.aiforge.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.bernie.aiforge.data.preferences.UserSettingsRepository
import com.bernie.aiforge.llm.providers.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the best LLM provider for a given request.
 *
 * Routing priority (first matching rule wins):
 * 1. User explicitly requested a provider → honour it
 * 2. Request requires offline → Local (Gemma)
 * 3. Request requires tool calling → prefer Claude, fall back to GPT-4o
 * 4. No internet → Local (Gemma)
 * 5. User's default provider (from Settings) → use it
 * 6. Fallback chain: Claude → GPT-4o → Gemini → Local
 */
@Singleton
class LlmRouter @Inject constructor(
    val anthropic: AnthropicProvider,
    val openAi: OpenAiProvider,
    val gemini: GeminiProvider,
    val local: LocalLiteRtProvider,
    private val settingsRepo: UserSettingsRepository,
    @ApplicationContext private val context: Context,
) {
    private val all: List<LlmProvider> = listOf(anthropic, openAi, gemini, local)

    /** Returns the provider to use and a fully-updated request (e.g. tools removed if provider can't handle them). */
    suspend fun select(request: LlmRequest): Pair<LlmProvider, LlmRequest> {
        val settings = settingsRepo.userSettings.first()

        // 1. Explicit provider override
        request.providerId?.let { pid ->
            val provider = all.firstOrNull { it.id == pid && it.isAvailable() }
            if (provider != null) return provider to request
        }

        // 2. Offline required
        if (request.requiresOffline) {
            if (local.isAvailable()) {
                local.ensureLoaded()
                return local to request.copy(tools = emptyList())
            }
        }

        // 3. No internet → local
        if (!isOnline()) {
            if (local.isAvailable()) {
                local.ensureLoaded()
                return local to request.copy(tools = emptyList())
            }
        }

        // 4. Tool calling needed → prefer providers that support it
        if (request.requiresTools || request.tools.isNotEmpty()) {
            val toolProviders = listOf(anthropic, openAi, gemini)
            for (provider in toolProviders) {
                if (provider.isAvailable()) return provider to request
            }
        }

        // 5. User's preferred provider
        val preferred = all.firstOrNull { it.id == settings.defaultProvider && it.isAvailable() }
        if (preferred != null) {
            val cleanRequest = if (!preferred.capabilities.contains(Capability.TOOL_CALLING))
                request.copy(tools = emptyList()) else request
            return preferred to cleanRequest
        }

        // 6. Fallback chain
        for (provider in all) {
            if (provider.isAvailable()) {
                val cleanRequest = if (!provider.capabilities.contains(Capability.TOOL_CALLING))
                    request.copy(tools = emptyList()) else request
                return provider to cleanRequest
            }
        }

        // Nothing available
        throw IllegalStateException(
            "No LLM provider is available. Check API keys in Settings or load the Gemma model."
        )
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
