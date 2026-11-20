package com.example.ui.screens

import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.browser.MediaSniffer
import com.example.browser.WebViewCallbacks
import com.example.browser.createConfiguredWebView
import com.example.browser.goBackSafely
import com.example.browser.hasValidBackHistory
import com.example.data.local.BookmarkEntity
import com.example.data.local.SearchEngine
import com.example.ui.AppSection
import com.example.ui.BrowserTabState
import com.example.ui.WebViewCommand
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

private const val PAUSE_BACKGROUND_MEDIA_JS =
    "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){try{m.pause();}catch(e){}});}catch(e){}})();"

@Composable
fun BrowserScreen(
    tabs: List<BrowserTabState>,
    activeTab: BrowserTabState,
    tabCount: Int,
    bookmarks: List<BookmarkEntity>,
    activeDownloadsCount: Int,
    completedDownloadsCount: Int,
    isDarkTheme: Boolean,
    isAdBlockEnabled: Boolean,
    isPopupBlockEnabled: Boolean,
    totalAdsBlocked: Long,
    searchEngine: SearchEngine,
    webViews: MutableMap<String, WebView>,
    defaultUserAgents: MutableMap<String, String>,
    webViewCommands: SharedFlow<WebViewCommand>,
    onSelectSection: (AppSection) -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onConsumePendingScrollY: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onReloadOrStop: () -> Unit,
    onGoBack: () -> Boolean,
    onGoForward: () -> Unit,
    onGoHome: () -> Unit,
    onOpenMediaSheet: () -> Unit,
    onOpenTabsSheet: () -> Unit,
    onOpenShieldSheet: () -> Unit,
    onOpenSearchEngineSheet: () -> Unit,
    onOpenClearDataSheet: () -> Unit,
    onExitApp: () -> Unit,
    onSelectSearchEngine: (SearchEngine) -> Unit,
    onToggleDesktopMode: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleTheme: () -> Unit,
    onRescanDom: () -> Unit,
    onPageStarted: (String, String) -> Unit,
    onPageFinished: (String, String, String, Boolean, Boolean) -> Unit,
    onProgressChanged: (String, Int) -> Unit,
    onTitleChanged: (String, String) -> Unit,
    onNavigationStateChanged: (String, Boolean, Boolean) -> Unit,
    onUrlVisited: (String, String, Boolean, Boolean) -> Unit,
    onPagePosterFound: (String, String) -> Unit,
    onMediaSniffed: (String, String, String, String?, String, Int, String, String, String, String, String, String, String) -> Unit,
    onProbeCandidateFound: (String, String, String, String, String, String) -> Unit,
    onAdBlocked: (String, String, Int) -> Unit,
    onPopupBlocked: (String, String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val adBlockEnabledRef = remember { AtomicBoolean(isAdBlockEnabled) }
    val popupBlockEnabledRef = remember { AtomicBoolean(isPopupBlockEnabled) }
    val activeTabIdRef = remember { AtomicReference(activeTab.id) }
    val tabsRef = remember { AtomicReference(tabs) }
    SideEffect {
        adBlockEnabledRef.set(isAdBlockEnabled)
        popupBlockEnabledRef.set(isPopupBlockEnabled)
        activeTabIdRef.set(activeTab.id)
        tabsRef.set(tabs)
    }

    var fullscreenCustomView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val callbacks = remember {
        object : WebViewCallbacks {
            override fun onPageStarted(tabId: String, url: String) =
                onPageStarted(tabId, url)

            override fun onPageFinished(
                tabId: String,
                url: String,
                title: String,
                canBack: Boolean,
                canForward: Boolean
            ) {
                onPageFinished(tabId, url, title, canBack, canForward)
                // Restore last viewed scroll position if this tab was restored from a previous session
                val pendingScroll = tabsRef.get()
                    .firstOrNull { it.id == tabId }
                    ?.pendingRestoreScrollY ?: 0
                if (pendingScroll > 0) {
                    webViews[tabId]?.postDelayed({
                        webViews[tabId]?.scrollTo(0, pendingScroll)
                    }, 180L)
                    onConsumePendingScrollY(tabId)
                }
            }

            override fun onProgressChanged(tabId: String, progress: Int) =
                onProgressChanged(tabId, progress)

            override fun onTitleChanged(tabId: String, title: String) =
                onTitleChanged(tabId, title)

            override fun onNavigationStateChanged(
                tabId: String,
                canBack: Boolean,
                canForward: Boolean
            ) = onNavigationStateChanged(tabId, canBack, canForward)

            override fun onUrlVisited(
                tabId: String,
                url: String,
                canBack: Boolean,
                canForward: Boolean
            ) = onUrlVisited(tabId, url, canBack, canForward)

            override fun onPagePosterFound(tabId: String, posterUrl: String) =
                onPagePosterFound(tabId, posterUrl)

            override fun onMediaSniffed(
                tabId: String,
                url: String,
                title: String,
                mimeHint: String?,
                resolution: String,
                durationSeconds: Int,
                referer: String,
                originHeader: String,
                userAgent: String,
                cookie: String,
                origin: String,
                audioUrl: String,
                thumbnailUrl: String
            ) = onMediaSniffed(
                tabId, url, title, mimeHint, resolution,
                durationSeconds, referer, originHeader, userAgent, cookie, origin, audioUrl, thumbnailUrl
            )

            override fun onProbeCandidateFound(
                tabId: String,
                url: String,
                referer: String,
                originHeader: String,
                userAgent: String,
                cookie: String
            ) = onProbeCandidateFound(tabId, url, referer, originHeader, userAgent, cookie)

            override fun onAdBlocked(tabId: String, blockedUrl: String, count: Int) =
                onAdBlocked(tabId, blockedUrl, count)

            override fun onPopupBlocked(tabId: String, blockedUrl: String) =
                onPopupBlocked(tabId, blockedUrl)

            override fun onEnterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
                fullscreenCustomView = view
                fullscreenCallback = callback
            }

            override fun onExitFullscreen() {
                fullscreenCustomView = null
                fullscreenCallback = null
            }
        }
    }

    fun getOrCreateWebView(tabId: String): WebView {
        return webViews.getOrPut(tabId) {
            createConfiguredWebView(
                context = context,
                tabId = tabId,
                callbacks = callbacks,
                isAdBlockEnabled = { adBlockEnabledRef.get() },
                isPopupBlockEnabled = { popupBlockEnabledRef.get() },
                isTabActive = { activeTabIdRef.get() == tabId }
            ).also { wv ->
                defaultUserAgents[tabId] = wv.settings.userAgentString
            }
        }
    }

    // Pause inactive background tab WebViews & media playback so video detection and playback
    // stay strictly on the active tab while keeping background tabs intact for instant switching.
    LaunchedEffect(activeTab.id, activeTab.isHomeScreen) {
        webViews.forEach { (tabId, wv) ->
            if (tabId == activeTab.id && !activeTab.isHomeScreen) {
                wv.onResume()
            } else {
                runCatching { wv.evaluateJavascript(PAUSE_BACKGROUND_MEDIA_JS, null) }
                wv.onPause()
            }
        }
    }

    fun performSafeBack(tabId: String) {
        val wv = webViews[tabId]
        if (wv != null && goBackSafely(wv)) {
            onNavigationStateChanged(tabId, hasValidBackHistory(wv), wv.canGoForward())
        } else {
            onGoHome()
        }
    }

    LaunchedEffect(webViewCommands) {
        webViewCommands.collect { cmd ->
            when (cmd) {
                is WebViewCommand.LoadUrl -> {
                    val wv = getOrCreateWebView(cmd.tabId)
                    wv.loadUrl(cmd.url)
                }
                is WebViewCommand.Reload -> webViews[cmd.tabId]?.reload()
                is WebViewCommand.StopLoading -> webViews[cmd.tabId]?.stopLoading()
                is WebViewCommand.GoBack -> performSafeBack(cmd.tabId)
                is WebViewCommand.GoForward -> webViews[cmd.tabId]?.goForward()
                is WebViewCommand.SetDesktopMode -> {
                    val wv = webViews[cmd.tabId]
                    if (wv != null) {
                        wv.settings.userAgentString = if (cmd.enabled) {
                            DESKTOP_USER_AGENT
                        } else {
                            defaultUserAgents[cmd.tabId] ?: WebSettings.getDefaultUserAgent(context)
                        }
                        wv.settings.useWideViewPort = true
                    }
                }
                is WebViewCommand.RescanDom -> {
                    webViews[cmd.tabId]?.evaluateJavascript(MediaSniffer.MEDIA_SNIFFER_JS, null)
                }
                is WebViewCommand.DestroyTab -> {
                    webViews.remove(cmd.tabId)?.apply {
                        stopLoading()
                        destroy()
                    }
                    defaultUserAgents.remove(cmd.tabId)
                }
                is WebViewCommand.ClearWebViewData -> {
                    webViews.values.forEach { wv ->
                        if (cmd.clearCache) {
                            wv.clearCache(true)
                        }
                        if (cmd.clearCookiesAndLogin) {
                            wv.clearFormData()
                        }
                    }
                }
            }
        }
    }

    BackHandler(enabled = fullscreenCustomView != null || !activeTab.isHomeScreen) {
        if (fullscreenCustomView != null) {
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCustomView = null
            fullscreenCallback = null
        } else {
            performSafeBack(activeTab.id)
        }
    }

    var omniboxText by remember(activeTab.id, activeTab.url, activeTab.isHomeScreen) {
        mutableStateOf(if (activeTab.isHomeScreen) "" else activeTab.url)
    }
    var isOmniboxFocused by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val isCurrentBookmarked = bookmarks.any { it.url == activeTab.url }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Omnibox Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isOmniboxFocused) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when {
                                        activeTab.isHomeScreen -> Icons.Default.Search
                                        activeTab.url.startsWith("https://") -> Icons.Default.Lock
                                        else -> Icons.Default.Public
                                    },
                                    contentDescription = if (activeTab.isHomeScreen) "Pilih mesin pencari" else null,
                                    tint = if (activeTab.isHomeScreen || activeTab.url.startsWith("https://")) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier
                                        .size(20.dp)
                                        .then(
                                            if (activeTab.isHomeScreen) {
                                                Modifier.clickable { onOpenSearchEngineSheet() }
                                            } else {
                                                Modifier
                                            }
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))

                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (omniboxText.isEmpty()) {
                                        Text(
                                            text = "Cari di ${searchEngine.displayName} atau ketik URL...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    BasicTextField(
                                        value = omniboxText,
                                        onValueChange = { omniboxText = it },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                        keyboardActions = KeyboardActions(
                                            onGo = {
                                                if (omniboxText.isNotBlank()) {
                                                    focusManager.clearFocus()
                                                    onNavigate(omniboxText)
                                                }
                                            }
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onFocusChanged { isOmniboxFocused = it.isFocused }
                                            .testTag("omnibox_input")
                                    )
                                }

                                if (isOmniboxFocused && omniboxText.isNotEmpty()) {
                                    IconButton(
                                        onClick = { omniboxText = "" },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Hapus URL",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else if (!activeTab.isHomeScreen) {
                                    IconButton(
                                        onClick = onReloadOrStop,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (activeTab.isLoading) {
                                                Icons.Default.Close
                                            } else {
                                                Icons.Default.Refresh
                                            },
                                            contentDescription = if (activeTab.isLoading) "Berhenti" else "Muat ulang",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // AdBlock Shield Button (48dp touch target)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clickable { onOpenShieldSheet() }
                                .testTag("adblock_shield_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isAdBlockEnabled) Icons.Default.GppGood else Icons.Default.GppMaybe,
                                contentDescription = stringResource(R.string.adblock_shield_title),
                                tint = if (isAdBlockEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(22.dp)
                            )
                            if (activeTab.blockedAdsCount > 0 && isAdBlockEnabled) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondary,
                                    shape = RoundedCornerShape(999.dp),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 6.dp, end = 4.dp)
                                ) {
                                    Text(
                                        text = if (activeTab.blockedAdsCount > 99) "99+" else "${activeTab.blockedAdsCount}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }

                        // Tab Counter Pill (48dp touch target)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clickable { onOpenTabsSheet() }
                                .testTag("tabs_counter_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant),
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "$tabCount",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }

                        // Overflow Menu (contains Jelajah, Tersimpan, Penanda + Browser Tools)
                        Box {
                            IconButton(
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.testTag("browser_menu_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Menu browser"
                                    )
                                    if (activeDownloadsCount > 0) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(9.dp)
                                        ) {}
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Tab Baru") },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onNewTab()
                                    },
                                    modifier = Modifier.testTag("menu_new_tab")
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (activeTab.isHomeScreen) {
                                                "${stringResource(R.string.nav_browser)} • Aktif"
                                            } else {
                                                "${stringResource(R.string.nav_browser)} (Beranda)"
                                            },
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Explore,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        if (!activeTab.isHomeScreen) {
                                            onGoHome()
                                        }
                                        onSelectSection(AppSection.BROWSER)
                                    },
                                    modifier = Modifier.testTag("menu_nav_browser")
                                )
                                DropdownMenuItem(
                                    text = {
                                        val label = when {
                                            activeDownloadsCount > 0 ->
                                                "${stringResource(R.string.nav_vault)} ($activeDownloadsCount mengunduh)"
                                            completedDownloadsCount > 0 ->
                                                "${stringResource(R.string.nav_vault)} ($completedDownloadsCount video)"
                                            else -> stringResource(R.string.nav_vault)
                                        }
                                        Text(label)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Download,
                                            contentDescription = null,
                                            tint = if (activeDownloadsCount > 0) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onSelectSection(AppSection.VAULT)
                                    },
                                    modifier = Modifier.testTag("menu_nav_vault")
                                )
                                DropdownMenuItem(
                                    text = {
                                        val label = if (bookmarks.isNotEmpty()) {
                                            "${stringResource(R.string.nav_bookmarks)} & Riwayat (${bookmarks.size})"
                                        } else {
                                            "${stringResource(R.string.nav_bookmarks)} & Riwayat"
                                        }
                                        Text(label)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Bookmarks, contentDescription = null)
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onSelectSection(AppSection.BOOKMARKS)
                                    },
                                    modifier = Modifier.testTag("menu_nav_bookmarks")
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = { Text("Mesin Pencari: ${searchEngine.displayName}") },
                                    leadingIcon = { Icon(Icons.Default.TravelExplore, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenSearchEngineSheet()
                                    },
                                    modifier = Modifier.testTag("menu_search_engine")
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isAdBlockEnabled) {
                                                "Perisai AdBlock: Aktif (${activeTab.blockedAdsCount})"
                                            } else {
                                                "Perisai AdBlock: Dijeda"
                                            }
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (isAdBlockEnabled) Icons.Default.GppGood else Icons.Default.GppMaybe,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenShieldSheet()
                                    }
                                )
                                if (!activeTab.isHomeScreen) {
                                    DropdownMenuItem(
                                        text = { Text("Pindai Media Halaman") },
                                        leadingIcon = { Icon(Icons.Default.Radar, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            onRescanDom()
                                            onOpenMediaSheet()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (isCurrentBookmarked) "Hapus dari Penanda" else "Simpan ke Penanda"
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (isCurrentBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            onToggleBookmark()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (activeTab.isDesktopMode) "Matikan Mode Desktop" else "Aktifkan Mode Desktop"
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.DesktopWindows, contentDescription = null)
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            onToggleDesktopMode()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Privasi & Hapus Data") },
                                    leadingIcon = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenClearDataSheet()
                                    },
                                    modifier = Modifier.testTag("menu_clear_data")
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(if (isDarkTheme) "Ganti ke Tema Terang" else "Ganti ke Tema Gelap")
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onToggleTheme()
                                    }
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Keluar",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onExitApp()
                                    },
                                    modifier = Modifier.testTag("menu_exit_app")
                                )
                            }
                        }
                    }

                    // Fast 1-tap Horizontal Multi-Tab Strip when 2+ tabs are open
                    if (tabs.size > 1) {
                        QuickTabStrip(
                            tabs = tabs,
                            activeTabId = activeTab.id,
                            onSelectTab = onSelectTab,
                            onCloseTab = onCloseTab,
                            onNewTab = onNewTab
                        )
                    }

                    if (activeTab.isLoading) {
                        LinearProgressIndicator(
                            progress = { (activeTab.progress / 100f).coerceIn(0.05f, 1f) },
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                        )
                    }
                }
            }

            // Main WebView or Home Surface
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (activeTab.isHomeScreen) {
                    BrowserHomeStartPage(
                        bookmarks = bookmarks,
                        isAdBlockEnabled = isAdBlockEnabled,
                        totalAdsBlocked = totalAdsBlocked,
                        searchEngine = searchEngine,
                        onOpenUrl = onNavigate,
                        onOpenShield = onOpenShieldSheet,
                        onOpenSearchEnginePicker = onOpenSearchEngineSheet,
                        onSelectSearchEngine = onSelectSearchEngine
                    )
                } else {
                    val activeWebView = remember(activeTab.id) {
                        getOrCreateWebView(activeTab.id)
                    }
                    LaunchedEffect(activeTab.id, activeTab.isHomeScreen, activeTab.url, activeTab.isDesktopMode) {
                        if (!activeTab.isHomeScreen &&
                            activeTab.url.isNotBlank() &&
                            (activeWebView.url.isNullOrBlank() || activeWebView.url == "about:blank")
                        ) {
                            if (activeTab.isDesktopMode) {
                                activeWebView.settings.userAgentString = DESKTOP_USER_AGENT
                                activeWebView.settings.useWideViewPort = true
                            }
                            activeWebView.loadUrl(activeTab.url)
                        }
                    }
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                (activeWebView.parent as? android.view.ViewGroup)?.removeView(activeWebView)
                                addView(activeWebView)
                            }
                        },
                        update = { container ->
                            if (container.getChildAt(0) !== activeWebView) {
                                container.removeAllViews()
                                (activeWebView.parent as? android.view.ViewGroup)?.removeView(activeWebView)
                                container.addView(activeWebView)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Floating Media Detected Pulse Pill
                val detectedCount = activeTab.detectedMedia.size
                androidx.compose.animation.AnimatedVisibility(
                    visible = detectedCount > 0 && !activeTab.isHomeScreen,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 14.dp)
                ) {
                    MediaDetectedPulsePill(
                        count = detectedCount,
                        onClick = onOpenMediaSheet
                    )
                }
            }

            // Bottom Browser Controls Strip
            if (!activeTab.isHomeScreen) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { performSafeBack(activeTab.id) },
                            modifier = Modifier.testTag("browser_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Kembali"
                            )
                        }
                        IconButton(
                            onClick = onGoForward,
                            enabled = activeTab.canGoForward,
                            modifier = Modifier.testTag("browser_forward_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Maju"
                            )
                        }
                        IconButton(
                            onClick = onGoHome,
                            modifier = Modifier.testTag("browser_home_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Beranda"
                            )
                        }
                        IconButton(
                            onClick = onOpenMediaSheet,
                            modifier = Modifier.testTag("browser_radar_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Radar,
                                contentDescription = "Radar media terdeteksi",
                                tint = if (activeTab.detectedMedia.isNotEmpty()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        }

        // Fullscreen HTML5 Video Overlay
        fullscreenCustomView?.let { fsView ->
            AndroidView(
                factory = { fsView },
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
    }
}

@Composable
private fun QuickTabStrip(
    tabs: List<BrowserTabState>,
    activeTabId: String,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit
) {
    val listState = rememberLazyListState()
    val activeIndex = tabs.indexOfFirst { it.id == activeTabId }
    LaunchedEffect(activeIndex, tabs.size) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex)
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("quick_tab_strip"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(end = 6.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(tabs, key = { it.id }) { tab ->
                val isActive = tab.id == activeTabId
                val label = tab.title.ifBlank {
                    if (tab.isHomeScreen) "Beranda" else tab.url
                }
                Surface(
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = if (isActive) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    },
                    modifier = Modifier
                        .height(40.dp)
                        .widthIn(min = 92.dp, max = 176.dp)
                        .clickable { onSelectTab(tab.id) }
                        .testTag("quick_tab_chip_${tab.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(start = 10.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isActive && tab.detectedMedia.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isActive) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .clickable { onCloseTab(tab.id) }
                                .testTag("quick_tab_close_${tab.id}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Tutup tab $label",
                                tint = if (isActive) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }

        IconButton(
            onClick = onNewTab,
            modifier = Modifier.testTag("quick_tab_new_button")
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Buka tab baru",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaDetectedPulsePill(
    count: Int,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(999.dp),
        shadowElevation = 8.dp,
        modifier = Modifier.testTag("media_detected_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (count == 1) {
                    stringResource(R.string.media_detected_singular)
                } else {
                    stringResource(R.string.media_detected_plural, count)
                },
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun BrowserHomeStartPage(
    bookmarks: List<BookmarkEntity>,
    isAdBlockEnabled: Boolean,
    totalAdsBlocked: Long,
    searchEngine: SearchEngine,
    onOpenUrl: (String) -> Unit,
    onOpenShield: () -> Unit,
    onOpenSearchEnginePicker: () -> Unit,
    onSelectSearchEngine: (SearchEngine) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Clean Pro Browser Brand Header
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(136.dp)
                        .clip(RoundedCornerShape(22.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_stream_hero),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0x660B0F14),
                                        Color(0xEE0B0F14)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(18.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = onOpenSearchEnginePicker,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier.testTag("search_engine_chip")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.TravelExplore,
                                        contentDescription = "Pilih mesin pencari",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = searchEngine.displayName.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Surface(
                                onClick = onOpenShield,
                                color = if (isAdBlockEnabled) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
                                },
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isAdBlockEnabled) Icons.Default.GppGood else Icons.Default.GppMaybe,
                                        contentDescription = null,
                                        tint = if (isAdBlockEnabled) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.secondary
                                        },
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isAdBlockEnabled) {
                                            "ADBLOCK • $totalAdsBlocked"
                                        } else {
                                            "ADBLOCK OFF"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isAdBlockEnabled) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "ArfStream",
                            style = MaterialTheme.typography.displaySmall,
                            color = Color.White
                        )
                    }
                }
            }

            // Direct 1-Tap Search Engine Selector Strip
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Mesin Pencari",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(SearchEngine.entries) { engine ->
                            val selected = engine == searchEngine
                            Surface(
                                onClick = { onSelectSearchEngine(engine) },
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                                contentColor = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                border = if (selected) {
                                    null
                                } else {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                },
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier.testTag("home_engine_${engine.id}")
                            ) {
                                Text(
                                    text = engine.displayName,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Quick Access Portals
            if (bookmarks.isNotEmpty()) {
                item {
                    Text(
                        text = "Portal & Penanda Cepat",
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                items(bookmarks.chunked(2).size) { rowIndex ->
                    val pair = bookmarks.chunked(2)[rowIndex]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEach { bookmark ->
                            Surface(
                                onClick = { onOpenUrl(bookmark.url) },
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Public,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = bookmark.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = bookmark.category,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
