package com.earflows.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.earflows.app.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "earflows_settings")

class PreferencesManager(private val context: Context) {

    // DataStore keys
    private object Keys {
        val USE_CLOUD = booleanPreferencesKey("use_cloud")
        val SOURCE_LANG = stringPreferencesKey("source_lang")
        val TARGET_LANG = stringPreferencesKey("target_lang")
        val SPLIT_CHANNEL = booleanPreferencesKey("split_channel")
        val RECORD_TRANSLATION = booleanPreferencesKey("record_translation")
        val OUTPUT_VOLUME = floatPreferencesKey("output_volume")
    }

    // --- Public flows ---

    val useCloud: Flow<Boolean> = context.dataStore.data.map { it[Keys.USE_CLOUD] ?: false }
    val sourceLang: Flow<String> = context.dataStore.data.map { it[Keys.SOURCE_LANG] ?: Constants.DEFAULT_SOURCE_LANG }
    val targetLang: Flow<String> = context.dataStore.data.map { it[Keys.TARGET_LANG] ?: Constants.DEFAULT_TARGET_LANG }
    val splitChannel: Flow<Boolean> = context.dataStore.data.map { it[Keys.SPLIT_CHANNEL] ?: false }
    val recordTranslation: Flow<Boolean> = context.dataStore.data.map { it[Keys.RECORD_TRANSLATION] ?: false }
    val outputVolume: Flow<Float> = context.dataStore.data.map { it[Keys.OUTPUT_VOLUME] ?: 1.0f }

    suspend fun setUseCloud(value: Boolean) {
        context.dataStore.edit { it[Keys.USE_CLOUD] = value }
    }

    suspend fun setSourceLang(value: String) {
        context.dataStore.edit { it[Keys.SOURCE_LANG] = value }
    }

    suspend fun setTargetLang(value: String) {
        context.dataStore.edit { it[Keys.TARGET_LANG] = value }
    }

    suspend fun setSplitChannel(value: Boolean) {
        context.dataStore.edit { it[Keys.SPLIT_CHANNEL] = value }
    }

    suspend fun setRecordTranslation(value: Boolean) {
        context.dataStore.edit { it[Keys.RECORD_TRANSLATION] = value }
    }

    suspend fun setOutputVolume(value: Float) {
        context.dataStore.edit { it[Keys.OUTPUT_VOLUME] = value }
    }

    // --- Encrypted storage for API keys ---

    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "earflows_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getApiKey(): String? =
        encryptedPrefs.getString("cloud_api_key", null)
            ?: encryptedPrefs.getString("openai_api_key", null)  // Migration from old key

    fun setApiKey(key: String) {
        encryptedPrefs.edit().putString("cloud_api_key", key).apply()
    }

    fun clearApiKey() {
        encryptedPrefs.edit()
            .remove("cloud_api_key")
            .remove("openai_api_key")  // Clean old key too
            .apply()
    }
}
