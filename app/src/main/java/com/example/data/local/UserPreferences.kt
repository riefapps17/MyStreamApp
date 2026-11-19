package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "streamvault_prefs")

class UserPreferences(private val context: Context) {
    private val darkThemeKey = booleanPreferencesKey("dark_theme")
    private val adBlockEnabledKey = booleanPreferencesKey("ad_block_enabled")
    private val blockPopupsKey = booleanPreferencesKey("block_popups")
    private val totalAdsBlockedKey = longPreferencesKey("total_ads_blocked")

    val isDarkTheme: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[darkThemeKey] ?: true
    }

    val isAdBlockEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[adBlockEnabledKey] ?: true
    }

    val isPopupBlockingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[blockPopupsKey] ?: true
    }

    val totalAdsBlocked: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[totalAdsBlockedKey] ?: 0L
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[darkThemeKey] = enabled
        }
    }

    suspend fun setAdBlockEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[adBlockEnabledKey] = enabled
        }
    }

    suspend fun setPopupBlockingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[blockPopupsKey] = enabled
        }
    }

    suspend fun incrementTotalAdsBlocked(delta: Long = 1L) {
        if (delta <= 0L) return
        context.dataStore.edit { prefs ->
            val current = prefs[totalAdsBlockedKey] ?: 0L
            prefs[totalAdsBlockedKey] = current + delta
        }
    }

    suspend fun resetTotalAdsBlocked() {
        context.dataStore.edit { prefs ->
            prefs[totalAdsBlockedKey] = 0L
        }
    }
}
