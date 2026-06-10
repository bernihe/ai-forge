package com.bernie.aiforge.data.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bernie.aiforge.llm.ProviderId
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores and retrieves API keys using Android Keystore + AES256-GCM encryption.
 * Keys never touch the network and are not backed up by default.
 *
 * Usage in Settings: user types their key → `set(provider, key)`.
 * Usage in providers: DI module calls `getOrEmpty(provider)`.
 */
@Singleton
class ApiKeyVault @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "api_keys_vault",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun set(provider: ProviderId, key: String) {
        prefs.edit().putString(provider.name, key).apply()
    }

    fun getOrEmpty(provider: ProviderId): String =
        prefs.getString(provider.name, "") ?: ""

    fun clear(provider: ProviderId) {
        prefs.edit().remove(provider.name).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun hasKey(provider: ProviderId): Boolean =
        getOrEmpty(provider).isNotBlank()
}
