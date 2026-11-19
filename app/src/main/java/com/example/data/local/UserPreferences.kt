package com.example.data.local

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class SearchEngine(
    val id: String,
    val displayName: String,
    val searchUrlPrefix: String
) {
    GOOGLE("google", "Google", "https://www.google.com/search?q="),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q="),
    BING("bing", "Bing", "https://www.bing.com/search?q="),
    BRAVE("brave", "Brave Search", "https://search.brave.com/search?q="),
    YAHOO("yahoo", "Yahoo", "https://search.yahoo.com/search?p=");

    fun buildSearchUrl(query: String): String = "$searchUrlPrefix${Uri.encode(query)}"

    companion object {
        fun fromId(id: String?): SearchEngine =
            entries.firstOrNull { it.id == id } ?: GOOGLE
    }
}

private val Context.dataStore by preferencesDataStore(name = "streamvault_prefs")

class UserPreferences(private val context: Context) {
    private val darkThemeKey = booleanPreferencesKey("dark_theme")
    private val adBlockEnabledKey = booleanPreferencesKey("ad_block_enabled")
    private val blockPopupsKey = booleanPreferencesKey("block_popups")
    private val totalAdsBlockedKey = longPreferencesKey("total_ads_blocked")
    private val searchEngineKey = stringPreferencesKey("search_engine")

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

    val searchEngine: Flow<SearchEngine> = context.dataStore.data.map { prefs ->
        SearchEngine.fromId(prefs[searchEngineKey])
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

    suspend fun setSearchEngine(engine: SearchEngine) {
        context.dataStore.edit { prefs ->
            prefs[searchEngineKey] = engine.id
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
