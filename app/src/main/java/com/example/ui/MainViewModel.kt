package com.example.ui

import android.app.Application
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebStorage
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.DetectedMedia
import com.example.browser.MediaFormat
import com.example.browser.MediaSniffer
import com.example.data.local.BookmarkEntity
import com.example.data.local.DownloadEntity
import com.example.data.local.DownloadStatus
import com.example.data.local.HistoryEntity
import com.example.data.local.SavedTabRecord
import com.example.data.local.SavedTabSession
import com.example.data.local.SearchEngine
import com.example.data.local.StreamDatabase
import com.example.data.local.UserPreferences
import com.example.data.repository.StreamRepository
import com.example.downloader.StreamDownloader
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class AppSection {
    BROWSER,
    VAULT,
    BOOKMARKS
}

data class BrowserTabState(
    val id: String = UUID.randomUUID().toString(),
    val url: String = "",
    val title: String = "Beranda",
    val isHomeScreen: Boolean = true,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isDesktopMode: Boolean = false,
    val detectedMedia: List<DetectedMedia> = emptyList(),
    val pageThumbnailUrl: String = "",
    val blockedAdsCount: Int = 0,
    val blockedPopupsCount: Int = 0,
    val scrollY: Int = 0,
    val pendingRestoreScrollY: Int = 0
)

sealed interface PlayerTarget {
    data class RemoteStream(val media: DetectedMedia) : PlayerTarget
    data class LocalDownload(val download: DownloadEntity) : PlayerTarget
}

sealed interface WebViewCommand {
    data class LoadUrl(val tabId: String, val url: String) : WebViewCommand
    data class Reload(val tabId: String) : WebViewCommand
    data class StopLoading(val tabId: String) : WebViewCommand
    data class GoBack(val tabId: String) : WebViewCommand
    data class GoForward(val tabId: String) : WebViewCommand
    data class SetDesktopMode(val tabId: String, val enabled: Boolean) : WebViewCommand
    data class RescanDom(val tabId: String) : WebViewCommand
    data class DestroyTab(val tabId: String) : WebViewCommand
    data class ClearWebViewData(val clearCache: Boolean, val clearCookiesAndLogin: Boolean) : WebViewCommand
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = StreamDatabase.getInstance(application)
    private val repository = StreamRepository(
        downloadDao = database.downloadDao(),
        bookmarkDao = database.bookmarkDao(),
        historyDao = database.historyDao()
    )
    private val preferences = UserPreferences(application)
    private val downloader = StreamDownloader(application, repository)

    val isDarkTheme: StateFlow<Boolean> = preferences.isDarkTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val isAdBlockEnabled: StateFlow<Boolean> = preferences.isAdBlockEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val isPopupBlockingEnabled: StateFlow<Boolean> = preferences.isPopupBlockingEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val totalAdsBlocked: StateFlow<Long> = preferences.totalAdsBlocked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val searchEngine: StateFlow<SearchEngine> = preferences.searchEngine
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchEngine.GOOGLE)

    val downloads: StateFlow<List<DownloadEntity>> = repository.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bookmarks: StateFlow<List<BookmarkEntity>> = repository.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<HistoryEntity>> = repository.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val initialTab = BrowserTabState()
    private val _tabs = MutableStateFlow(listOf(initialTab))
    val tabs: StateFlow<List<BrowserTabState>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow(initialTab.id)
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    private val _currentSection = MutableStateFlow(AppSection.BROWSER)
    val currentSection: StateFlow<AppSection> = _currentSection.asStateFlow()

    private val _isMediaSheetOpen = MutableStateFlow(false)
    val isMediaSheetOpen: StateFlow<Boolean> = _isMediaSheetOpen.asStateFlow()

    private val _isTabsSheetOpen = MutableStateFlow(false)
    val isTabsSheetOpen: StateFlow<Boolean> = _isTabsSheetOpen.asStateFlow()

    private val _isShieldSheetOpen = MutableStateFlow(false)
    val isShieldSheetOpen: StateFlow<Boolean> = _isShieldSheetOpen.asStateFlow()

    private val _isSearchEngineSheetOpen = MutableStateFlow(false)
    val isSearchEngineSheetOpen: StateFlow<Boolean> = _isSearchEngineSheetOpen.asStateFlow()

    private val _isClearDataSheetOpen = MutableStateFlow(false)
    val isClearDataSheetOpen: StateFlow<Boolean> = _isClearDataSheetOpen.asStateFlow()

    private val _playerTarget = MutableStateFlow<PlayerTarget?>(null)
    val playerTarget: StateFlow<PlayerTarget?> = _playerTarget.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    private val _webViewCommands = MutableSharedFlow<WebViewCommand>(extraBufferCapacity = 16)
    val webViewCommands = _webViewCommands.asSharedFlow()

    private val probedUrls = ConcurrentHashMap.newKeySet<String>()
    private val probeCandidateUrls = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var sessionRestoreCompleted = false

    @Volatile
    private var hasUserMutatedTabs = false

    init {
        viewModelScope.launch {
            repository.seedInitialBookmarksIfEmpty()
        }
        viewModelScope.launch {
            val saved = preferences.loadSavedTabSession()
            if (saved != null && saved.tabs.isNotEmpty() && !hasUserMutatedTabs) {
                val restoredTabs = saved.tabs.map { rec ->
                    BrowserTabState(
                        id = rec.id,
                        url = rec.url,
                        title = rec.title,
                        isHomeScreen = rec.isHomeScreen,
                        isDesktopMode = rec.isDesktopMode,
                        scrollY = rec.scrollY,
                        pendingRestoreScrollY = if (rec.isHomeScreen) 0 else rec.scrollY
                    )
                }
                _tabs.value = restoredTabs
                _activeTabId.value = saved.activeTabId
            }
            sessionRestoreCompleted = true
        }
    }

    private fun buildSavedSessionSnapshot(scrollPositions: Map<String, Int> = emptyMap()): SavedTabSession {
        val currentTabs = _tabs.value
        val activeId = _activeTabId.value.takeIf { id -> currentTabs.any { it.id == id } }
            ?: currentTabs.firstOrNull()?.id.orEmpty()
        val records = currentTabs.map { tab ->
            val liveScroll = scrollPositions[tab.id] ?: tab.scrollY
            SavedTabRecord(
                id = tab.id,
                url = if (tab.isHomeScreen) "" else tab.url,
                title = tab.title.ifBlank { if (tab.isHomeScreen) "Beranda" else tab.url },
                isHomeScreen = tab.isHomeScreen || tab.url.isBlank(),
                isDesktopMode = tab.isDesktopMode,
                scrollY = if (tab.isHomeScreen) 0 else liveScroll.coerceAtLeast(0)
            )
        }
        return SavedTabSession(activeTabId = activeId, tabs = records)
    }

    private fun persistTabsIfReady(scrollPositions: Map<String, Int> = emptyMap()) {
        if (!sessionRestoreCompleted && !hasUserMutatedTabs) return
        val snapshot = buildSavedSessionSnapshot(scrollPositions)
        viewModelScope.launch {
            preferences.saveTabSession(snapshot)
        }
    }

    fun persistSessionNow(scrollPositions: Map<String, Int>) {
        if (scrollPositions.isNotEmpty()) {
            _tabs.update { list ->
                list.map { tab ->
                    val y = scrollPositions[tab.id]
                    if (y != null && !tab.isHomeScreen) tab.copy(scrollY = y.coerceAtLeast(0)) else tab
                }
            }
        }
        persistTabsIfReady(scrollPositions)
    }

    fun consumePendingScrollY(tabId: String) {
        updateTab(tabId) {
            if (it.pendingRestoreScrollY > 0) it.copy(pendingRestoreScrollY = 0) else it
        }
    }

    fun toggleTheme() {
        viewModelScope.launch {
            preferences.setDarkTheme(!isDarkTheme.value)
        }
    }

    fun toggleAdBlock() {
        val next = !isAdBlockEnabled.value
        viewModelScope.launch {
            preferences.setAdBlockEnabled(next)
        }
        _snackbarMessage.tryEmit(
            if (next) "Perisai AdBlock diaktifkan" else "Perisai AdBlock dijeda"
        )
    }

    fun togglePopupBlocking() {
        val next = !isPopupBlockingEnabled.value
        viewModelScope.launch {
            preferences.setPopupBlockingEnabled(next)
        }
        _snackbarMessage.tryEmit(
            if (next) "Pemblokir Pop-up diaktifkan" else "Pemblokir Pop-up dinonaktifkan"
        )
    }

    fun setSearchEngine(engine: SearchEngine) {
        viewModelScope.launch {
            preferences.setSearchEngine(engine)
        }
        _snackbarMessage.tryEmit("Mesin pencari diubah ke ${engine.displayName}")
    }

    fun selectSection(section: AppSection) {
        _currentSection.value = section
    }

    fun openMediaSheet() {
        rescanActiveTab()
        _isMediaSheetOpen.value = true
    }

    fun closeMediaSheet() {
        _isMediaSheetOpen.value = false
    }

    fun openTabsSheet() {
        _isTabsSheetOpen.value = true
    }

    fun closeTabsSheet() {
        _isTabsSheetOpen.value = false
    }

    fun openShieldSheet() {
        _isShieldSheetOpen.value = true
    }

    fun closeShieldSheet() {
        _isShieldSheetOpen.value = false
    }

    fun openSearchEngineSheet() {
        _isSearchEngineSheetOpen.value = true
    }

    fun closeSearchEngineSheet() {
        _isSearchEngineSheetOpen.value = false
    }

    fun openClearDataSheet() {
        _isClearDataSheetOpen.value = true
    }

    fun closeClearDataSheet() {
        _isClearDataSheetOpen.value = false
    }

    fun clearBrowsingData(
        clearCache: Boolean,
        clearHistory: Boolean,
        clearCookiesAndLogin: Boolean
    ) {
        viewModelScope.launch {
            if (clearHistory) {
                repository.clearHistory()
            }
            if (clearCookiesAndLogin) {
                runCatching {
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies(null)
                    cm.flush()
                }
                runCatching {
                    WebStorage.getInstance().deleteAllData()
                }
                repository.clearAllStoredCookies()
            }
            _webViewCommands.tryEmit(
                WebViewCommand.ClearWebViewData(
                    clearCache = clearCache,
                    clearCookiesAndLogin = clearCookiesAndLogin
                )
            )
            val msg = when {
                clearCookiesAndLogin -> "Cookie, data login, dan data terpilih berhasil dihapus"
                clearCache && clearHistory -> "Cache & riwayat dibersihkan (data login tetap disimpan)"
                clearCache -> "Cache browser dibersihkan (data login tetap disimpan)"
                clearHistory -> "Riwayat kunjungan dihapus (data login tetap disimpan)"
                else -> "Tidak ada data yang dipilih"
            }
            _snackbarMessage.tryEmit(msg)
        }
    }

    fun createNewTab(initialUrl: String? = null) {
        hasUserMutatedTabs = true
        val newTab = BrowserTabState()
        _tabs.update { it + newTab }
        _activeTabId.value = newTab.id
        _isTabsSheetOpen.value = false
        _currentSection.value = AppSection.BROWSER
        if (!initialUrl.isNullOrBlank()) {
            navigateToInput(initialUrl, newTab.id)
        } else {
            persistTabsIfReady()
        }
    }

    fun selectTab(tabId: String) {
        val target = _tabs.value.firstOrNull { it.id == tabId } ?: return
        hasUserMutatedTabs = true
        val changed = _activeTabId.value != tabId
        _activeTabId.value = tabId
        _isTabsSheetOpen.value = false
        _currentSection.value = AppSection.BROWSER
        persistTabsIfReady()
        // Trigger fresh video detection strictly for the newly active tab
        if (changed && !target.isHomeScreen) {
            _webViewCommands.tryEmit(WebViewCommand.RescanDom(tabId))
        }
    }

    fun closeTab(tabId: String) {
        hasUserMutatedTabs = true
        val currentList = _tabs.value
        if (currentList.size <= 1) {
            // Reset single tab back to Home
            val reset = BrowserTabState(id = tabId)
            _tabs.value = listOf(reset)
            _webViewCommands.tryEmit(WebViewCommand.DestroyTab(tabId))
            persistTabsIfReady()
            return
        }
        val index = currentList.indexOfFirst { it.id == tabId }
        val filtered = currentList.filterNot { it.id == tabId }
        _tabs.value = filtered
        if (_activeTabId.value == tabId) {
            val nextIndex = (index - 1).coerceAtLeast(0)
            _activeTabId.value = filtered[nextIndex].id
        }
        _webViewCommands.tryEmit(WebViewCommand.DestroyTab(tabId))
        persistTabsIfReady()
    }

    fun closeAllTabs() {
        hasUserMutatedTabs = true
        val oldIds = _tabs.value.map { it.id }
        val freshTab = BrowserTabState()
        _tabs.value = listOf(freshTab)
        _activeTabId.value = freshTab.id
        _isTabsSheetOpen.value = false
        oldIds.forEach { oldId ->
            _webViewCommands.tryEmit(WebViewCommand.DestroyTab(oldId))
        }
        persistTabsIfReady()
    }

    fun goHome() {
        hasUserMutatedTabs = true
        val tabId = _activeTabId.value
        updateTab(tabId) {
            it.copy(
                isHomeScreen = true,
                url = "",
                title = "Beranda",
                isLoading = false,
                progress = 0,
                detectedMedia = emptyList(),
                pageThumbnailUrl = "",
                blockedAdsCount = 0,
                blockedPopupsCount = 0,
                scrollY = 0,
                pendingRestoreScrollY = 0
            )
        }
        persistTabsIfReady()
    }

    fun navigateToInput(rawInput: String, targetTabId: String = _activeTabId.value) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return

        hasUserMutatedTabs = true
        _currentSection.value = AppSection.BROWSER

        val resolvedUrl = resolveUserUrlOrSearch(trimmed)
        updateTab(targetTabId) {
            it.copy(
                isHomeScreen = false,
                url = resolvedUrl,
                isLoading = true,
                detectedMedia = emptyList(),
                scrollY = 0,
                pendingRestoreScrollY = 0
            )
        }
        _webViewCommands.tryEmit(WebViewCommand.LoadUrl(targetTabId, resolvedUrl))
        persistTabsIfReady()
    }

    private fun resolveUserUrlOrSearch(input: String): String {
        if (input.startsWith("http://", ignoreCase = true) ||
            input.startsWith("https://", ignoreCase = true)
        ) {
            return input
        }
        val looksLikeDomain = !input.contains(' ') &&
            input.contains('.') &&
            URLUtil.isValidUrl("https://$input")
        return if (looksLikeDomain) {
            "https://$input"
        } else {
            searchEngine.value.buildSearchUrl(input)
        }
    }

    fun reloadOrStopActiveTab() {
        val tab = _tabs.value.firstOrNull { it.id == _activeTabId.value } ?: return
        if (tab.isHomeScreen) return
        if (tab.isLoading) {
            _webViewCommands.tryEmit(WebViewCommand.StopLoading(tab.id))
            updateTab(tab.id) { it.copy(isLoading = false) }
        } else {
            _webViewCommands.tryEmit(WebViewCommand.Reload(tab.id))
        }
    }

    fun goBackActiveTab(): Boolean {
        val tab = _tabs.value.firstOrNull { it.id == _activeTabId.value } ?: return false
        if (tab.isHomeScreen) return false
        _webViewCommands.tryEmit(WebViewCommand.GoBack(tab.id))
        return true
    }

    fun goForwardActiveTab() {
        val tab = _tabs.value.firstOrNull { it.id == _activeTabId.value } ?: return
        if (!tab.isHomeScreen && tab.canGoForward) {
            _webViewCommands.tryEmit(WebViewCommand.GoForward(tab.id))
        }
    }

    fun toggleDesktopMode() {
        val tab = _tabs.value.firstOrNull { it.id == _activeTabId.value } ?: return
        val next = !tab.isDesktopMode
        updateTab(tab.id) { it.copy(isDesktopMode = next) }
        _webViewCommands.tryEmit(WebViewCommand.SetDesktopMode(tab.id, next))
        if (!tab.isHomeScreen) {
            _webViewCommands.tryEmit(WebViewCommand.Reload(tab.id))
        }
        persistTabsIfReady()
        _snackbarMessage.tryEmit(if (next) "Mode Desktop aktif" else "Mode Mobile aktif")
    }

    fun rescanActiveTab() {
        val tab = _tabs.value.firstOrNull { it.id == _activeTabId.value } ?: return
        if (!tab.isHomeScreen) {
            _webViewCommands.tryEmit(WebViewCommand.RescanDom(tab.id))
        }
    }

    fun onPageStarted(tabId: String, url: String) {
        val prefix = "$tabId|"
        probeCandidateUrls.removeIf { it.startsWith(prefix) }
        probedUrls.removeIf { it.startsWith(prefix) }
        updateTab(tabId) {
            val isDifferentUrl = it.url.isNotBlank() && it.url != url
            it.copy(
                isHomeScreen = false,
                url = url,
                isLoading = true,
                progress = 10,
                detectedMedia = emptyList(),
                pageThumbnailUrl = "",
                blockedAdsCount = 0,
                blockedPopupsCount = 0,
                scrollY = if (isDifferentUrl) 0 else it.scrollY,
                pendingRestoreScrollY = if (isDifferentUrl) 0 else it.pendingRestoreScrollY
            )
        }
        persistTabsIfReady()
    }

    fun onAdBlocked(tabId: String, @Suppress("UNUSED_PARAMETER") blockedUrl: String, count: Int = 1) {
        if (count <= 0) return
        updateTab(tabId) { it.copy(blockedAdsCount = it.blockedAdsCount + count) }
        viewModelScope.launch {
            preferences.incrementTotalAdsBlocked(count.toLong())
        }
    }

    fun onPopupBlocked(tabId: String, @Suppress("UNUSED_PARAMETER") blockedUrl: String) {
        updateTab(tabId) {
            it.copy(
                blockedPopupsCount = it.blockedPopupsCount + 1,
                blockedAdsCount = it.blockedAdsCount + 1
            )
        }
        viewModelScope.launch {
            preferences.incrementTotalAdsBlocked(1L)
        }
        _snackbarMessage.tryEmit("Pop-up / iklan pengalihan diblokir")
    }

    fun onPageFinished(
        tabId: String,
        url: String,
        title: String,
        canBack: Boolean,
        canForward: Boolean
    ) {
        updateTab(tabId) {
            it.copy(
                url = url,
                title = title.ifBlank { url },
                isLoading = false,
                progress = 100,
                canGoBack = canBack,
                canGoForward = canForward
            )
        }
        viewModelScope.launch {
            repository.recordHistory(title, url)
        }
        persistTabsIfReady()
    }

    fun onProgressChanged(tabId: String, progress: Int) {
        updateTab(tabId) {
            it.copy(
                progress = progress,
                isLoading = progress in 1..99
            )
        }
    }

    fun onTitleChanged(tabId: String, title: String) {
        updateTab(tabId) {
            it.copy(title = title)
        }
        persistTabsIfReady()
    }

    fun onNavigationStateChanged(tabId: String, canBack: Boolean, canForward: Boolean) {
        updateTab(tabId) {
            it.copy(canGoBack = canBack, canGoForward = canForward)
        }
    }

    fun onUrlVisited(tabId: String, url: String, canBack: Boolean, canForward: Boolean) {
        updateTab(tabId) {
            if (it.isHomeScreen) it else it.copy(
                url = url,
                canGoBack = canBack,
                canGoForward = canForward
            )
        }
        persistTabsIfReady()
    }

    fun onPagePosterFound(tabId: String, posterUrl: String) {
        if (posterUrl.isBlank() || tabId != _activeTabId.value) return
        updateTab(tabId) { tab ->
            if (tab.pageThumbnailUrl.isBlank()) tab.copy(pageThumbnailUrl = posterUrl) else tab
        }
    }

    fun onMediaSniffed(
        tabId: String,
        url: String,
        rawTitle: String,
        mimeHint: String?,
        resolution: String,
        durationSeconds: Int,
        referer: String,
        originHeader: String,
        userAgent: String,
        cookie: String,
        origin: String,
        audioUrl: String = "",
        thumbnailUrl: String = ""
    ) {
        // Strictly allow video detection ONLY for the currently active tab
        if (tabId != _activeTabId.value) return
        val normalizedUrl = MediaSniffer.normalizeVideoUrl(url)
        val format = MediaSniffer.classifyUrl(normalizedUrl, mimeHint) ?: return
        val exactOrigin = originHeader.trim()
        val isNetworkAuthoritative = origin == "Network" || origin == "CDN Probe"
        var isNewEntry = false

        _tabs.update { list ->
            list.map { tab ->
                if (tab.id != tabId) return@map tab
                val effectiveTitle = rawTitle.ifBlank {
                    MediaSniffer.deriveTitle(normalizedUrl, tab.title, format)
                }
                val existingIndex = tab.detectedMedia.indexOfFirst { it.url == normalizedUrl }
                if (existingIndex >= 0) {
                    val existing = tab.detectedMedia[existingIndex]
                    val enriched = existing.copy(
                        resolution = existing.resolution.ifBlank { resolution },
                        durationSeconds = if (existing.durationSeconds > 0) existing.durationSeconds else durationSeconds,
                        title = if (rawTitle.isNotBlank()) rawTitle else existing.title,
                        referer = if (isNetworkAuthoritative && referer.isNotBlank()) {
                            referer
                        } else {
                            existing.referer.ifBlank { referer }
                        },
                        originHeader = if (isNetworkAuthoritative && exactOrigin.isNotBlank()) {
                            exactOrigin
                        } else {
                            existing.originHeader.ifBlank { exactOrigin }
                        },
                        userAgent = if (isNetworkAuthoritative && userAgent.isNotBlank()) {
                            userAgent
                        } else {
                            existing.userAgent.ifBlank { userAgent }
                        },
                        cookie = if (isNetworkAuthoritative && cookie.isNotBlank()) {
                            cookie
                        } else {
                            existing.cookie.ifBlank { cookie }
                        },
                        audioUrl = existing.audioUrl.ifBlank { audioUrl },
                        thumbnailUrl = existing.thumbnailUrl.ifBlank { thumbnailUrl }
                    )
                    if (enriched != existing) {
                        val updatedList = tab.detectedMedia.toMutableList().apply { set(existingIndex, enriched) }
                        tab.copy(detectedMedia = updatedList)
                    } else {
                        tab
                    }
                } else {
                    isNewEntry = true
                    val media = DetectedMedia(
                        id = UUID.randomUUID().toString(),
                        url = normalizedUrl,
                        title = effectiveTitle,
                        format = format,
                        mimeType = mimeHint?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
                            ?: format.defaultMime,
                        resolution = resolution,
                        durationSeconds = durationSeconds,
                        referer = referer,
                        originHeader = exactOrigin,
                        userAgent = userAgent,
                        cookie = cookie,
                        audioUrl = audioUrl,
                        thumbnailUrl = thumbnailUrl,
                        sourceOrigin = origin
                    )
                    tab.copy(detectedMedia = listOf(media) + tab.detectedMedia)
                }
            }
        }

        // Probe file size & content-type asynchronously; automatically remove tiny sub-segments (<300 KB)
        val probeKey = "$tabId|$normalizedUrl"
        if (isNewEntry && format != MediaFormat.HLS && format != MediaFormat.DASH && probedUrls.add(probeKey)) {
            viewModelScope.launch {
                val probe = MediaSniffer.probeMediaMetadata(
                    url = normalizedUrl,
                    referer = referer,
                    originHeader = exactOrigin,
                    userAgent = userAgent,
                    cookie = cookie
                )
                val probedCt = probe.contentType?.substringBefore(';')?.trim().orEmpty()
                val isInvalidOrSegmentMime = probedCt.startsWith("text/html", ignoreCase = true) ||
                    probedCt.contains("video/mp2t", ignoreCase = true) ||
                    probedCt.contains("video/iso.segment", ignoreCase = true)
                val isTinyFragment = MediaSniffer.isTinyOrFragmentSize(format, probe.sizeBytes)

                if (isTinyFragment || isInvalidOrSegmentMime) {
                    updateTab(tabId) { current ->
                        current.copy(
                            detectedMedia = current.detectedMedia.filterNot { it.url == normalizedUrl }
                        )
                    }
                    return@launch
                }

                // If magic bytes revealed this URL is actually an HLS/DASH manifest, upgrade its format
                val upgradedFormat = if (probedCt.isNotBlank()) {
                    MediaSniffer.classifyUrl(normalizedUrl, probedCt) ?: format
                } else {
                    format
                }

                if (probe.sizeBytes > 0L || probedCt.isNotBlank()) {
                    updateTab(tabId) { current ->
                        val updated = current.detectedMedia.map { item ->
                            if (item.url == normalizedUrl) {
                                item.copy(
                                    format = upgradedFormat,
                                    sizeBytes = if (upgradedFormat == MediaFormat.HLS || upgradedFormat == MediaFormat.DASH) {
                                        -1L
                                    } else if (probe.sizeBytes > 0L) {
                                        probe.sizeBytes
                                    } else {
                                        item.sizeBytes
                                    },
                                    mimeType = probedCt.ifBlank { item.mimeType }
                                )
                            } else {
                                item
                            }
                        }
                        current.copy(detectedMedia = updated)
                    }
                }
            }
        }
    }

    fun onProbeCandidateFound(
        tabId: String,
        url: String,
        referer: String,
        originHeader: String,
        userAgent: String,
        cookie: String
    ) {
        // Strictly probe & detect videos ONLY on the currently active tab
        if (tabId != _activeTabId.value) return
        val normalizedUrl = MediaSniffer.normalizeVideoUrl(url)
        if (MediaSniffer.isLikelyMediaSegmentOrFragment(normalizedUrl)) return
        val probeKey = "$tabId|$normalizedUrl"
        if (!probeCandidateUrls.add(probeKey)) return
        val alreadyDetected = _tabs.value
            .firstOrNull { it.id == tabId }
            ?.detectedMedia
            ?.any { it.url == normalizedUrl } == true
        if (alreadyDetected) return

        val exactOrigin = originHeader.trim()
        viewModelScope.launch {
            val probe = MediaSniffer.probeMediaMetadata(
                url = normalizedUrl,
                referer = referer,
                originHeader = exactOrigin,
                userAgent = userAgent,
                cookie = cookie
            )
            // Re-verify active tab after async network probe completes
            if (tabId != _activeTabId.value) return@launch
            val ct = probe.contentType
            val detectedFormat = if (!ct.isNullOrBlank()) {
                MediaSniffer.classifyUrl(normalizedUrl, ct)
            } else {
                null
            }
            if (detectedFormat != null) {
                // Skip tiny few-KB initialization headers or micro-chunks of the main video
                if (MediaSniffer.isTinyOrFragmentSize(detectedFormat, probe.sizeBytes)) {
                    return@launch
                }
                probedUrls.add(probeKey)
                onMediaSniffed(
                    tabId = tabId,
                    url = normalizedUrl,
                    rawTitle = "",
                    mimeHint = ct,
                    resolution = "",
                    durationSeconds = 0,
                    referer = referer,
                    originHeader = exactOrigin,
                    userAgent = userAgent,
                    cookie = cookie,
                    origin = "CDN Probe",
                    audioUrl = ""
                )
                if (probe.sizeBytes > 0L &&
                    detectedFormat != MediaFormat.HLS &&
                    detectedFormat != MediaFormat.DASH
                ) {
                    updateTab(tabId) { current ->
                        current.copy(
                            detectedMedia = current.detectedMedia.map { item ->
                                if (item.url == normalizedUrl && item.sizeBytes <= 0L) {
                                    item.copy(sizeBytes = probe.sizeBytes)
                                } else {
                                    item
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    fun addManualStreamUrl(rawUrl: String) {
        val url = MediaSniffer.normalizeVideoUrl(rawUrl.trim())
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _snackbarMessage.tryEmit("Masukkan URL http:// atau https:// yang valid")
            return
        }
        val format = MediaSniffer.classifyUrl(url) ?: MediaFormat.MP4
        val tabId = _activeTabId.value
        val tabTitle = _tabs.value.firstOrNull { it.id == tabId }?.title ?: "Stream Manual"
        onMediaSniffed(
            tabId = tabId,
            url = url,
            rawTitle = MediaSniffer.deriveTitle(url, tabTitle, format),
            mimeHint = format.defaultMime,
            resolution = "",
            durationSeconds = 0,
            referer = "",
            originHeader = MediaSniffer.deriveOriginHeader("", url),
            userAgent = "",
            cookie = "",
            origin = "Manual",
            audioUrl = ""
        )
    }

    fun playRemoteStream(media: DetectedMedia) {
        _isMediaSheetOpen.value = false
        _playerTarget.value = PlayerTarget.RemoteStream(media)
    }

    fun playLocalDownload(download: DownloadEntity) {
        val file = File(download.localPath)
        if (!file.exists()) {
            _snackbarMessage.tryEmit("File video tidak ditemukan di penyimpanan lokal")
            return
        }
        _playerTarget.value = PlayerTarget.LocalDownload(download)
    }

    fun closePlayer() {
        _playerTarget.value = null
    }

    fun saveDetectedMedia(media: DetectedMedia) {
        val activeTab = _tabs.value.firstOrNull { it.id == _activeTabId.value }
        viewModelScope.launch {
            val existing = repository.findDownloadBySourceUrl(media.url)
            if (existing != null &&
                (existing.status == DownloadStatus.COMPLETED.name ||
                    existing.status == DownloadStatus.DOWNLOADING.name)
            ) {
                _snackbarMessage.tryEmit("Video sudah ada di daftar Tersimpan")
                return@launch
            }

            val fileName = MediaSniffer.buildSafeFileName(media.title, media.format)
            val entity = DownloadEntity(
                title = media.title,
                fileName = fileName,
                sourceUrl = media.url,
                pageUrl = activeTab?.url.orEmpty(),
                pageTitle = activeTab?.title.orEmpty(),
                format = media.format.label,
                localPath = "",
                mimeType = media.mimeType,
                totalBytes = if (media.sizeBytes > 0L) media.sizeBytes else 0L,
                status = DownloadStatus.QUEUED.name,
                statusDetail = "Menyiapkan unduhan...",
                referer = media.referer,
                originHeader = media.originHeader,
                userAgent = media.userAgent,
                cookie = media.cookie,
                audioUrl = media.audioUrl
            )
            val rowId = repository.insertDownload(entity)
            downloader.startDownload(entity.copy(id = rowId))
            _snackbarMessage.tryEmit("Mulai menyimpan: ${media.title}")
        }
    }

    fun cancelDownload(id: Long) {
        downloader.cancelDownload(id)
    }

    fun retryDownload(entity: DownloadEntity) {
        viewModelScope.launch {
            repository.updateDownloadProgress(
                id = entity.id,
                status = DownloadStatus.QUEUED.name,
                downloadedBytes = 0L,
                totalBytes = entity.totalBytes,
                progressPercent = 0,
                statusDetail = "Mengulang unduhan...",
                errorMessage = null
            )
            downloader.startDownload(entity)
        }
    }

    fun exportToGallery(entity: DownloadEntity) {
        viewModelScope.launch {
            val uri = downloader.exportExistingToGallery(entity)
            if (uri != null) {
                _snackbarMessage.tryEmit("Video berhasil diekspor ke folder Galeri/Movies")
            } else {
                _snackbarMessage.tryEmit("Gunakan tombol Bagikan untuk menyimpan ke aplikasi lain")
            }
        }
    }

    fun shareVideo(entity: DownloadEntity) {
        downloader.shareSavedVideo(entity)
    }

    fun deleteDownload(entity: DownloadEntity) {
        downloader.cancelDownload(entity.id)
        viewModelScope.launch {
            runCatching { File(entity.localPath).delete() }
            repository.deleteDownload(entity.id)
            _snackbarMessage.tryEmit("Video dihapus dari penyimpanan")
        }
    }

    fun toggleBookmarkCurrentPage() {
        val tab = _tabs.value.firstOrNull { it.id == _activeTabId.value } ?: return
        if (tab.isHomeScreen || tab.url.isBlank()) return
        viewModelScope.launch {
            val exists = bookmarks.value.any { it.url == tab.url }
            if (exists) {
                repository.removeBookmarkByUrl(tab.url)
                _snackbarMessage.tryEmit("Dihapus dari Penanda")
            } else {
                repository.addBookmark(tab.title, tab.url, "Tersimpan")
                _snackbarMessage.tryEmit("Ditambahkan ke Penanda")
            }
        }
    }

    fun addBookmark(title: String, url: String) {
        val resolved = resolveUserUrlOrSearch(url.trim())
        viewModelScope.launch {
            repository.addBookmark(title.ifBlank { resolved }, resolved)
            _snackbarMessage.tryEmit("Penanda disimpan")
        }
    }

    fun removeBookmark(id: Long) {
        viewModelScope.launch {
            repository.removeBookmarkById(id)
        }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch {
            repository.deleteHistoryItem(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            _snackbarMessage.tryEmit("Riwayat penjelajahan dibersihkan")
        }
    }

    private fun updateTab(tabId: String, transform: (BrowserTabState) -> BrowserTabState) {
        _tabs.update { list ->
            list.map { tab ->
                if (tab.id == tabId) transform(tab) else tab
            }
        }
    }
}
