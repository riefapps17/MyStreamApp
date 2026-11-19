package com.example

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.DownloadStatus
import com.example.player.VideoPlayerScreen
import com.example.ui.AppSection
import com.example.ui.BrowserTabState
import com.example.ui.MainViewModel
import com.example.ui.components.AdBlockShieldSheet
import com.example.ui.components.ClearDataSheet
import com.example.ui.components.DetectedMediaSheet
import com.example.ui.components.SearchEngineSheet
import com.example.ui.components.TabsSheet
import com.example.ui.screens.BookmarksHistoryScreen
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.VaultScreen
import com.example.ui.theme.StreamVaultTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()

            StreamVaultTheme(darkTheme = isDarkTheme) {
                StreamVaultApp(viewModel = viewModel, isDarkTheme = isDarkTheme)
            }
        }
    }
}

@Composable
private fun StreamVaultApp(
    viewModel: MainViewModel,
    isDarkTheme: Boolean
) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val currentSection by viewModel.currentSection.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val isMediaSheetOpen by viewModel.isMediaSheetOpen.collectAsStateWithLifecycle()
    val isTabsSheetOpen by viewModel.isTabsSheetOpen.collectAsStateWithLifecycle()
    val isShieldSheetOpen by viewModel.isShieldSheetOpen.collectAsStateWithLifecycle()
    val isSearchEngineSheetOpen by viewModel.isSearchEngineSheetOpen.collectAsStateWithLifecycle()
    val isClearDataSheetOpen by viewModel.isClearDataSheetOpen.collectAsStateWithLifecycle()
    val isAdBlockEnabled by viewModel.isAdBlockEnabled.collectAsStateWithLifecycle()
    val isPopupBlockEnabled by viewModel.isPopupBlockingEnabled.collectAsStateWithLifecycle()
    val totalAdsBlocked by viewModel.totalAdsBlocked.collectAsStateWithLifecycle()
    val searchEngine by viewModel.searchEngine.collectAsStateWithLifecycle()
    val playerTarget by viewModel.playerTarget.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()

    // Hoisted WebView registry so switching between Browser / Vault / Bookmarks preserves open pages & back history
    val webViews = remember { mutableStateMapOf<String, WebView>() }
    val defaultUserAgents = remember { mutableStateMapOf<String, String>() }

    DisposableEffect(Unit) {
        onDispose {
            webViews.values.forEach { wv ->
                wv.stopLoading()
                wv.destroy()
            }
            webViews.clear()
            defaultUserAgents.clear()
        }
    }

    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.firstOrNull() ?: BrowserTabState()
    val activeDownloadsCount = downloads.count {
        it.status == DownloadStatus.DOWNLOADING.name || it.status == DownloadStatus.QUEUED.name
    }
    val completedDownloadsCount = downloads.count {
        it.status == DownloadStatus.COMPLETED.name
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel.snackbarMessage) {
        viewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Root BackHandler: return from Vault/Bookmarks to Browser, or require double-back on Home before exiting
    var lastExitBackPressMs by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = playerTarget == null && (currentSection != AppSection.BROWSER || activeTab.isHomeScreen)) {
        if (currentSection != AppSection.BROWSER) {
            viewModel.selectSection(AppSection.BROWSER)
        } else {
            val now = System.currentTimeMillis()
            if (now - lastExitBackPressMs < 2000L) {
                activity?.finish()
            } else {
                lastExitBackPressMs = now
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Tekan kembali sekali lagi untuk keluar")
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentSection) {
                    AppSection.BROWSER -> {
                        BrowserScreen(
                            activeTab = activeTab,
                            tabCount = tabs.size,
                            bookmarks = bookmarks,
                            activeDownloadsCount = activeDownloadsCount,
                            completedDownloadsCount = completedDownloadsCount,
                            isDarkTheme = isDarkTheme,
                            isAdBlockEnabled = isAdBlockEnabled,
                            isPopupBlockEnabled = isPopupBlockEnabled,
                            totalAdsBlocked = totalAdsBlocked,
                            searchEngine = searchEngine,
                            webViews = webViews,
                            defaultUserAgents = defaultUserAgents,
                            webViewCommands = viewModel.webViewCommands,
                            onSelectSection = { viewModel.selectSection(it) },
                            onNavigate = { viewModel.navigateToInput(it) },
                            onReloadOrStop = { viewModel.reloadOrStopActiveTab() },
                            onGoBack = { viewModel.goBackActiveTab() },
                            onGoForward = { viewModel.goForwardActiveTab() },
                            onGoHome = { viewModel.goHome() },
                            onOpenMediaSheet = { viewModel.openMediaSheet() },
                            onOpenTabsSheet = { viewModel.openTabsSheet() },
                            onOpenShieldSheet = { viewModel.openShieldSheet() },
                            onOpenSearchEngineSheet = { viewModel.openSearchEngineSheet() },
                            onOpenClearDataSheet = { viewModel.openClearDataSheet() },
                            onSelectSearchEngine = { viewModel.setSearchEngine(it) },
                            onToggleDesktopMode = { viewModel.toggleDesktopMode() },
                            onToggleBookmark = { viewModel.toggleBookmarkCurrentPage() },
                            onToggleTheme = { viewModel.toggleTheme() },
                            onRescanDom = { viewModel.rescanActiveTab() },
                            onPageStarted = viewModel::onPageStarted,
                            onPageFinished = viewModel::onPageFinished,
                            onProgressChanged = viewModel::onProgressChanged,
                            onTitleChanged = viewModel::onTitleChanged,
                            onNavigationStateChanged = viewModel::onNavigationStateChanged,
                            onUrlVisited = viewModel::onUrlVisited,
                            onPagePosterFound = viewModel::onPagePosterFound,
                            onMediaSniffed = viewModel::onMediaSniffed,
                            onProbeCandidateFound = viewModel::onProbeCandidateFound,
                            onAdBlocked = viewModel::onAdBlocked,
                            onPopupBlocked = viewModel::onPopupBlocked
                        )
                    }
                    AppSection.VAULT -> {
                        VaultScreen(
                            downloads = downloads,
                            bookmarksCount = bookmarks.size,
                            onNavigateBack = { viewModel.selectSection(AppSection.BROWSER) },
                            onSelectSection = { viewModel.selectSection(it) },
                            onPlay = { viewModel.playLocalDownload(it) },
                            onCancel = { viewModel.cancelDownload(it) },
                            onRetry = { viewModel.retryDownload(it) },
                            onExportGallery = { viewModel.exportToGallery(it) },
                            onShare = { viewModel.shareVideo(it) },
                            onDelete = { viewModel.deleteDownload(it) }
                        )
                    }
                    AppSection.BOOKMARKS -> {
                        BookmarksHistoryScreen(
                            bookmarks = bookmarks,
                            history = history,
                            activeDownloadsCount = activeDownloadsCount,
                            completedDownloadsCount = completedDownloadsCount,
                            onNavigateBack = { viewModel.selectSection(AppSection.BROWSER) },
                            onSelectSection = { viewModel.selectSection(it) },
                            onOpenUrl = { viewModel.navigateToInput(it) },
                            onAddBookmark = { t, u -> viewModel.addBookmark(t, u) },
                            onDeleteBookmark = { viewModel.removeBookmark(it) },
                            onDeleteHistory = { viewModel.deleteHistoryItem(it) },
                            onClearHistory = { viewModel.clearHistory() }
                        )
                    }
                }
            }
        }

        if (isMediaSheetOpen) {
            DetectedMediaSheet(
                detectedMedia = activeTab.detectedMedia,
                pageFallbackThumbnail = activeTab.pageThumbnailUrl,
                downloads = downloads,
                onDismiss = { viewModel.closeMediaSheet() },
                onRescan = { viewModel.rescanActiveTab() },
                onPlayStream = { viewModel.playRemoteStream(it) },
                onSaveStream = { viewModel.saveDetectedMedia(it) },
                onAddManualUrl = { viewModel.addManualStreamUrl(it) }
            )
        }

        if (isTabsSheetOpen) {
            TabsSheet(
                tabs = tabs,
                activeTabId = activeTabId,
                onDismiss = { viewModel.closeTabsSheet() },
                onSelectTab = { viewModel.selectTab(it) },
                onCloseTab = { viewModel.closeTab(it) },
                onNewTab = { viewModel.createNewTab() }
            )
        }

        if (isShieldSheetOpen) {
            AdBlockShieldSheet(
                currentUrl = activeTab.url,
                isHomeScreen = activeTab.isHomeScreen,
                isAdBlockEnabled = isAdBlockEnabled,
                isPopupBlockEnabled = isPopupBlockEnabled,
                pageAdsBlocked = activeTab.blockedAdsCount,
                pagePopupsBlocked = activeTab.blockedPopupsCount,
                totalAdsBlocked = totalAdsBlocked,
                onDismiss = { viewModel.closeShieldSheet() },
                onToggleAdBlock = { viewModel.toggleAdBlock() },
                onTogglePopupBlock = { viewModel.togglePopupBlocking() },
                onReloadPage = { viewModel.reloadOrStopActiveTab() }
            )
        }

        if (isSearchEngineSheetOpen) {
            SearchEngineSheet(
                selectedEngine = searchEngine,
                onSelectEngine = { viewModel.setSearchEngine(it) },
                onDismiss = { viewModel.closeSearchEngineSheet() }
            )
        }

        if (isClearDataSheetOpen) {
            ClearDataSheet(
                onDismiss = { viewModel.closeClearDataSheet() },
                onConfirmClear = { clearCache, clearHistory, clearCookiesAndLogin ->
                    viewModel.clearBrowsingData(clearCache, clearHistory, clearCookiesAndLogin)
                }
            )
        }

        playerTarget?.let { target ->
            VideoPlayerScreen(
                target = target,
                onClose = { viewModel.closePlayer() },
                onSaveStream = { media -> viewModel.saveDetectedMedia(media) }
            )
        }
    }
}
