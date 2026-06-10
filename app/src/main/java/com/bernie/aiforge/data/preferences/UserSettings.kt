package com.bernie.aiforge.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.bernie.aiforge.llm.ProviderId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("user_settings")

// ─── Settings model ───────────────────────────────────────────────────────────

data class UserSettings(
    val defaultProvider: ProviderId = ProviderId.ANTHROPIC,
    val globalSystemInstructions: String = "",
    val defaultSkillId: String = "default",
    val memoryEnabled: Boolean = true,
    val autoExtractMemories: Boolean = true,
    val defaultTemperature: Float = 0.7f,
    val defaultMaxTokens: Int = 2048,
    val appLockEnabled: Boolean = false,
    val localModelPath: String = "",
    val streamingEnabled: Boolean = true,
    val showProviderBadge: Boolean = true,
)

// ─── Keys ─────────────────────────────────────────────────────────────────────

private object Keys {
    val DEFAULT_PROVIDER          = stringPreferencesKey("default_provider")
    val GLOBAL_SYSTEM_INSTRUCTIONS = stringPreferencesKey("global_system_instructions")
    val DEFAULT_SKILL_ID          = stringPreferencesKey("default_skill_id")
    val MEMORY_ENABLED            = booleanPreferencesKey("memory_enabled")
    val AUTO_EXTRACT_MEMORIES     = booleanPreferencesKey("auto_extract_memories")
    val DEFAULT_TEMPERATURE       = floatPreferencesKey("default_temperature")
    val DEFAULT_MAX_TOKENS        = intPreferencesKey("default_max_tokens")
    val APP_LOCK_ENABLED          = booleanPreferencesKey("app_lock_enabled")
    val LOCAL_MODEL_PATH          = stringPreferencesKey("local_model_path")
    val STREAMING_ENABLED         = booleanPreferencesKey("streaming_enabled")
    val SHOW_PROVIDER_BADGE       = booleanPreferencesKey("show_provider_badge")
}

// ─── Repository ───────────────────────────────────────────────────────────────

@Singleton
class UserSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val userSettings: Flow<UserSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            UserSettings(
                defaultProvider           = prefs[Keys.DEFAULT_PROVIDER]
                    ?.let { runCatching { ProviderId.valueOf(it) }.getOrNull() }
                    ?: ProviderId.ANTHROPIC,
                globalSystemInstructions  = prefs[Keys.GLOBAL_SYSTEM_INSTRUCTIONS] ?: "",
                defaultSkillId            = prefs[Keys.DEFAULT_SKILL_ID] ?: "default",
                memoryEnabled             = prefs[Keys.MEMORY_ENABLED] ?: true,
                autoExtractMemories       = prefs[Keys.AUTO_EXTRACT_MEMORIES] ?: true,
                defaultTemperature        = prefs[Keys.DEFAULT_TEMPERATURE] ?: 0.7f,
                defaultMaxTokens          = prefs[Keys.DEFAULT_MAX_TOKENS] ?: 2048,
                appLockEnabled            = prefs[Keys.APP_LOCK_ENABLED] ?: false,
                localModelPath            = prefs[Keys.LOCAL_MODEL_PATH] ?: "",
                streamingEnabled          = prefs[Keys.STREAMING_ENABLED] ?: true,
                showProviderBadge         = prefs[Keys.SHOW_PROVIDER_BADGE] ?: true,
            )
        }

    suspend fun update(block: suspend (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    suspend fun setDefaultProvider(provider: ProviderId) = update {
        it[Keys.DEFAULT_PROVIDER] = provider.name
    }

    suspend fun setGlobalInstructions(text: String) = update {
        it[Keys.GLOBAL_SYSTEM_INSTRUCTIONS] = text
    }

    suspend fun setMemoryEnabled(enabled: Boolean) = update {
        it[Keys.MEMORY_ENABLED] = enabled
    }

    suspend fun setLocalModelPath(path: String) = update {
        it[Keys.LOCAL_MODEL_PATH] = path
    }

    suspend fun setAppLock(enabled: Boolean) = update {
        it[Keys.APP_LOCK_ENABLED] = enabled
    }
}
