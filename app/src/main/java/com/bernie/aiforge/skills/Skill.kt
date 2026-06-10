package com.bernie.aiforge.skills

import android.content.Context
import com.bernie.aiforge.llm.ProviderId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// ─── Skill model (matches the JSON schema) ────────────────────────────────────

@Serializable
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val emoji: String = "🤖",
    val systemPrompt: String,
    val tools: List<String> = emptyList(),     // tool names the skill can use
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val topK: Int = 40,
    val preferredProvider: String? = null,     // ProviderId name, null = router decides
    val customInstructions: String = "",       // per-skill personalization
    val isBuiltIn: Boolean = false,
)

/** A [Skill] that has been resolved and is ready to use in [AgentEngine]. */
data class ActiveSkill(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val toolNames: List<String>,
    val temperature: Float,
    val maxTokens: Int,
    val preferredProvider: ProviderId?,
)

fun Skill.toActive() = ActiveSkill(
    id                = id,
    name              = name,
    systemPrompt      = buildString {
        append(systemPrompt)
        if (customInstructions.isNotBlank()) {
            appendLine("\n\n## Custom instructions\n$customInstructions")
        }
    },
    toolNames         = tools,
    temperature       = temperature,
    maxTokens         = maxTokens,
    preferredProvider = preferredProvider?.let {
        runCatching { ProviderId.valueOf(it.uppercase()) }.getOrNull()
    },
)

// ─── Default skill (plain assistant) ─────────────────────────────────────────

val DEFAULT_SKILL = Skill(
    id           = "default",
    name         = "AI Assistant",
    emoji        = "✨",
    description  = "General-purpose AI assistant",
    systemPrompt = "You are a helpful, smart AI assistant. Be concise, accurate, and friendly.",
    isBuiltIn    = true,
)

// ─── Skill registry ───────────────────────────────────────────────────────────

@Singleton
class SkillRegistry @Inject constructor(
    private val jsonLoader: JsonSkillLoader,
) {
    private val skills = mutableMapOf<String, Skill>()

    /** Call once at app start (or when the user adds/removes skills). */
    suspend fun reload() {
        skills.clear()
        skills[DEFAULT_SKILL.id] = DEFAULT_SKILL
        jsonLoader.loadAll().forEach { skills[it.id] = it }
    }

    fun getAll(): List<Skill> = skills.values.toList()

    fun get(id: String): Skill = skills[id] ?: DEFAULT_SKILL

    fun getActive(id: String): ActiveSkill = get(id).toActive()

    /** Saves a skill back to the user skills folder. */
    suspend fun save(skill: Skill) = jsonLoader.save(skill)

    fun delete(id: String) = jsonLoader.delete(id)
}

// ─── JSON skill loader ────────────────────────────────────────────────────────

/**
 * Reads skills from two locations:
 * 1. `assets/skills/` — bundled, read-only (built-in skills like MeLi agent)
 * 2. `files/skills/`  — user-added, writable
 */
@Singleton
class JsonSkillLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val userSkillsDir get() = context.filesDir.resolve("skills").also { it.mkdirs() }

    fun loadAll(): List<Skill> {
        val bundled  = loadBundled()
        val userDefined = loadUserDefined()
        return bundled + userDefined
    }

    private fun loadBundled(): List<Skill> = runCatching {
        context.assets.list("skills")
            ?.filter { it.endsWith(".json") }
            ?.mapNotNull { name ->
                runCatching {
                    val text = context.assets.open("skills/$name").bufferedReader().readText()
                    json.decodeFromString<Skill>(text).copy(isBuiltIn = true)
                }.getOrNull()
            } ?: emptyList()
    }.getOrElse { emptyList() }

    private fun loadUserDefined(): List<Skill> = runCatching {
        userSkillsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { json.decodeFromString<Skill>(file.readText()) }.getOrNull()
            } ?: emptyList()
    }.getOrElse { emptyList() }

    fun save(skill: Skill) {
        val file = userSkillsDir.resolve("${skill.id}.json")
        file.writeText(json.encodeToString(Skill.serializer(), skill))
    }

    fun delete(id: String) {
        userSkillsDir.resolve("$id.json").delete()
    }
}
