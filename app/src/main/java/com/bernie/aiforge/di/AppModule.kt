package com.bernie.aiforge.di

import android.content.Context
import androidx.room.Room
import com.bernie.aiforge.data.db.*
import com.bernie.aiforge.data.security.ApiKeyVault
import com.bernie.aiforge.llm.ProviderId
import com.bernie.aiforge.llm.providers.*
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import javax.inject.Named
import javax.inject.Singleton

// ─── Network ──────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS   // change to BODY for deep debug
        })
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

// ─── LLM Providers ───────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {

    @Provides @Singleton
    fun provideAnthropicProvider(
        okHttpClient: OkHttpClient,
        vault: ApiKeyVault,
    ): AnthropicProvider = AnthropicProvider(
        okHttpClient = okHttpClient,
        apiKey       = vault.getOrEmpty(ProviderId.ANTHROPIC),
    )

    @Provides @Singleton
    fun provideOpenAiProvider(
        okHttpClient: OkHttpClient,
        vault: ApiKeyVault,
    ): OpenAiProvider = OpenAiProvider(
        okHttpClient = okHttpClient,
        apiKey       = vault.getOrEmpty(ProviderId.OPENAI),
    )

    @Provides @Singleton
    fun provideGeminiProvider(
        okHttpClient: OkHttpClient,
        vault: ApiKeyVault,
    ): GeminiProvider = GeminiProvider(
        okHttpClient = okHttpClient,
        apiKey       = vault.getOrEmpty(ProviderId.GEMINI),
    )
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "aiforge.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideChatDao(db: AppDatabase): ChatDao       = db.chatDao()
    @Provides fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()
    @Provides fun provideMemoryDao(db: AppDatabase): MemoryDao   = db.memoryDao()
}
