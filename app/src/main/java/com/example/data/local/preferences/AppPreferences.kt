package com.example.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class AppPreferences(private val dataStore: DataStore<Preferences>) {

    companion object {
        val KEY_VIBRATE = booleanPreferencesKey("vibrate_on_success")
        val KEY_SOUND = booleanPreferencesKey("sound_on_success")
        val KEY_AUTO_EXPORT = booleanPreferencesKey("auto_export")
        val KEY_THREAD_COUNT = stringPreferencesKey("thread_count")
        val KEY_DEFAULT_ROUTER_ID = longPreferencesKey("default_router_id")
        val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
        
        // Home settings state
        val KEY_LAST_CARD_PREFIX = stringPreferencesKey("last_card_prefix")
        val KEY_LAST_CARD_LENGTH = intPreferencesKey("last_card_length")
        val KEY_LAST_CARD_COUNT = intPreferencesKey("last_card_count")
        val KEY_LAST_CHARSET = stringPreferencesKey("last_charset")

        fun getLanguageSync(context: Context): String {
            return context.getSharedPreferences("locale_prefs", Context.MODE_PRIVATE)
                .getString("app_language", "system") ?: "system"
        }
    }

    private val safeData: Flow<Preferences> = dataStore.data
        .catch { e ->
            timber.log.Timber.e(e, "Error reading AppPreferences DataStore")
            emit(emptyPreferences())
        }

    val vibrateOnSuccess: Flow<Boolean> = safeData.map { it[KEY_VIBRATE] ?: true }
    val soundOnSuccess: Flow<Boolean> = safeData.map { it[KEY_SOUND] ?: false }
    val autoExport: Flow<Boolean> = safeData.map { it[KEY_AUTO_EXPORT] ?: false }
    val threadCount: Flow<Int> = safeData.map { prefs ->
        val v = prefs[KEY_THREAD_COUNT] ?: "3"
        v.toIntOrNull() ?: 3
    }
    val defaultRouterId: Flow<Long> = safeData.map { it[KEY_DEFAULT_ROUTER_ID] ?: 0L }
    val appLanguage: Flow<String> = safeData.map { it[KEY_APP_LANGUAGE] ?: "system" }
    
    val lastCardPrefix: Flow<String> = safeData.map { it[KEY_LAST_CARD_PREFIX] ?: "" }
    val lastCardLength: Flow<Int> = safeData.map { it[KEY_LAST_CARD_LENGTH] ?: 6 }
    val lastCardCount: Flow<Int> = safeData.map { it[KEY_LAST_CARD_COUNT] ?: 10 }
    val lastCharset: Flow<String> = safeData.map { it[KEY_LAST_CHARSET] ?: "0123456789" }

    suspend fun saveHomeSettings(prefix: String, length: Int, count: Int, charset: String, routerId: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_CARD_PREFIX] = prefix
            prefs[KEY_LAST_CARD_LENGTH] = length
            prefs[KEY_LAST_CARD_COUNT] = count
            prefs[KEY_LAST_CHARSET] = charset
            if (routerId != -1L) {
                prefs[KEY_DEFAULT_ROUTER_ID] = routerId
            }
        }
    }

    suspend fun setAppLanguage(lang: String) {
        dataStore.edit { it[KEY_APP_LANGUAGE] = lang }
    }

    suspend fun setVibrateOnSuccess(enabled: Boolean) {
        dataStore.edit { it[KEY_VIBRATE] = enabled }
    }

    suspend fun setSoundOnSuccess(enabled: Boolean) {
        dataStore.edit { it[KEY_SOUND] = enabled }
    }

    suspend fun setDefaultRouterId(routerId: Long) {
        dataStore.edit { it[KEY_DEFAULT_ROUTER_ID] = routerId }
    }
}
