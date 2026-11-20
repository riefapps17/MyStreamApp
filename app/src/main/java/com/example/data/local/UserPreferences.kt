package com.example.data.local

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

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

data class SavedTabRecord(
    val id: String,
    val url: String,
    val title: String,
    val isHomeScreen: Boolean,
    val isDesktopMode: Boolean = false,
    val scrollY: Int = 0
)

data class SavedTabSession(
    val activeTabId: String,
    val tabs: List<SavedTabRecord>
)

object TabSessionSerializer {
    fun serialize(session: SavedTabSession): String {
        val root = JSONObject()
        root.put("activeTabId", session.activeTabId)
        val arr = JSONArray()
        for (tab in session.tabs.take(25)) {
            val obj = JSONObject()
            obj.put("id", tab.id)
            obj.put("url", tab.url)
            obj.put("title", tab.title)
            obj.put("isHomeScreen", tab.isHomeScreen)
            obj.put("isDesktopMode", tab.isDesktopMode)
            obj.put("scrollY", tab.scrollY.coerceAtLeast(0))
            arr.put(obj)
        }
        root.put("tabs", arr)
        return root.toString()
    }

    fun deserialize(rawJson: String?): SavedTabSession? {
        if (rawJson.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(rawJson)
            val arr = root.optJSONArray("tabs") ?: return null
            val tabs = mutableListOf<SavedTabRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isEmpty()) continue
                val url = obj.optString("url").trim()
                val isHome = obj.optBoolean("isHomeScreen", url.isEmpty())
                val title = obj.optString("title").ifBlank { if (isHome) "Beranda" else url }
                tabs.add(
                    SavedTabRecord(
                        id = id,
                        url = if (isHome) "" else url,
                        title = title,
                        isHomeScreen = isHome || url.isEmpty(),
                        isDesktopMode = obj.optBoolean("isDesktopMode", false),
                        scrollY = obj.optInt("scrollY", 0).coerceAtLeast(0)
                    )
                )
            }
            if (tabs.isEmpty()) return null
            val activeId = root.optString("activeTabId").takeIf { savedId ->
                tabs.any { it.id == savedId }
            } ?: tabs.first().id
            SavedTabSession(activeTabId = activeId, tabs = tabs)
        }.getOrNull()
    }
}

private val Context.dataStore by preferencesDataStore(name = "streamvault_prefs")

class UserPreferences(private val context: Context) {
    private val darkThemeKey = booleanPreferencesKey("dark_theme")
    private val adBlockEnabledKey = booleanPreferencesKey("ad_block_enabled")
    private val blockPopupsKey = booleanPreferencesKey("block_popups")
    private val totalAdsBlockedKey = longPreferencesKey("total_ads_blocked")
    private val searchEngineKey = stringPreferencesKey("search_engine")
    private val savedTabSessionKey = stringPreferencesKey("saved_tab_session_json")

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

    suspend fun loadSavedTabSession(): SavedTabSession? {
        val raw = runCatching { context.dataStore.data.first()[savedTabSessionKey] }.getOrNull()
        return TabSessionSerializer.deserialize(raw)
    }

    suspend fun saveTabSession(session: SavedTabSession) {
        val serialized = TabSessionSerializer.serialize(session)
        context.dataStore.edit { prefs ->
            prefs[savedTabSessionKey] = serialized
        }
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
