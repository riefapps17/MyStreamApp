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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.browser.DemoStreamPage
import com.example.browser.MediaSniffer
import com.example.browser.WebViewCallbacks
import com.example.browser.createConfiguredWebView
import com.example.browser.goBackSafely
import com.example.browser.hasValidBackHistory
import com.example.data.local.BookmarkEntity
import com.example.data.local.SearchEngine
import com.example.ui.BrowserTabState
import com.example.ui.WebViewCommand
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

@Composable
fun BrowserScreen(
    activeTab: BrowserTabState,
    tabCount: Int,
    bookmarks: List<BookmarkEntity>,
    isDarkTheme: Boolean,
    isAdBlockEnabled: Boolean,
    isPopupBlockEnabled: Boolean,
    totalAdsBlocked: Long,
    searchEngine: SearchEngine,
    webViews: MutableMap<String, WebView>,
    defaultUserAgents: MutableMap<String, String>,
    webViewCommands: SharedFlow<WebViewCommand>,
    onNavigate: (String) -> Unit,
    onReloadOrStop: () -> Unit,
    onGoBack: () -> Boolean,
    onGoForward: () -> Unit,
    onGoHome: () -> Unit,
    onOpenMediaSheet: () -> Unit,
    onOpenTabsSheet: () -> Unit,
    onOpenShieldSheet: () -> Unit,
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
    onMediaSniffed: (String, String, String, String?, String, Int, String, String, String, String, String, String) -> Unit,
    onProbeCandidateFound: (String, String, String, String, String, String) -> Unit,
    onAdBlocked: (String, String, Int) -> Unit,
    onPopupBlocked: (String, String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val adBlockEnabledRef = remember { AtomicBoolean(isAdBlockEnabled) }
    val popupBlockEnabledRef = remember { AtomicBoolean(isPopupBlockEnabled) }
    SideEffect {
        adBlockEnabledRef.set(isAdBlockEnabled)
        popupBlockEnabledRef.set(isPopupBlockEnabled)
    }

    var fullscreenCustomView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var showSearchEngineDialog by remember { mutableStateOf(false) }

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
            ) = onPageFinished(tabId, url, title, canBack, canForward)

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
                audioUrl: String
            ) = onMediaSniffed(
                tabId, url, title, mimeHint, resolution,
                durationSeconds, referer, originHeader, userAgent, cookie, origin, audioUrl
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
                isPopupBlockEnabled = { popupBlockEnabledRef.get() }
            ).also { wv ->
                defaultUserAgents[tabId] = wv.settings.userAgentString
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
                    if (cmd.url == DemoStreamPage.DEMO_URL) {
                        wv.loadDataWithBaseURL(
                            DemoStreamPage.BASE_URL,
                            DemoStreamPage.htmlContent,
                            "text/html",
                            "utf-8",
                            null
                        )
                    } else {
                        wv.loadUrl(cmd.url)
                    }
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
                                        activeTab.url == DemoStreamPage.DEMO_URL -> Icons.Default.Shield
                                        activeTab.url.startsWith("https://") -> Icons.Default.Lock
                                        else -> Icons.Default.Public
                                    },
                                    contentDescription = null,
                                    tint = if (activeTab.url.startsWith("https://") ||
                                        activeTab.url == DemoStreamPage.DEMO_URL
                                    ) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(18.dp)
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

                        // Overflow Menu
                        Box {
                            IconButton(
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.testTag("browser_menu_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Menu browser"
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
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
                                DropdownMenuItem(
                                    text = { Text("Mesin Pencari: ${searchEngine.displayName}") },
                                    leadingIcon = { Icon(Icons.Default.TravelExplore, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showSearchEngineDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Buka Halaman Demo Video") },
                                    leadingIcon = { Icon(Icons.Default.SmartDisplay, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onNavigate(DemoStreamPage.DEMO_URL)
                                    }
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
                            }
                        }
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
                        onOpenDemo = { onNavigate(DemoStreamPage.DEMO_URL) },
                        onOpenShield = onOpenShieldSheet,
                        onOpenSearchEnginePicker = { showSearchEngineDialog = true }
                    )
                } else {
                    val activeWebView = remember(activeTab.id) {
                        getOrCreateWebView(activeTab.id)
                    }
                    LaunchedEffect(activeTab.id, activeTab.isHomeScreen, activeTab.url) {
                        if (!activeTab.isHomeScreen &&
                            activeTab.url.isNotBlank() &&
                            (activeWebView.url.isNullOrBlank() || activeWebView.url == "about:blank")
                        ) {
                            if (activeTab.url == DemoStreamPage.DEMO_URL) {
                                activeWebView.loadDataWithBaseURL(
                                    DemoStreamPage.BASE_URL,
                                    DemoStreamPage.htmlContent,
                                    "text/html",
                                    "utf-8",
                                    null
                                )
                            } else {
                                activeWebView.loadUrl(activeTab.url)
                            }
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

        if (showSearchEngineDialog) {
            AlertDialog(
                onDismissRequest = { showSearchEngineDialog = false },
                title = { Text("Pilih Mesin Pencari") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SearchEngine.entries.forEach { engine ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onSelectSearchEngine(engine)
                                        showSearchEngineDialog = false
                                    }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = engine == searchEngine,
                                    onClick = {
                                        onSelectSearchEngine(engine)
                                        showSearchEngineDialog = false
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = engine.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSearchEngineDialog = false }) {
                        Text("Tutup")
                    }
                }
            )
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
    onOpenDemo: () -> Unit,
    onOpenShield: () -> Unit,
    onOpenSearchEnginePicker: () -> Unit
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
            // Hero Banner Card
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(178.dp)
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
                            text = "StreamVault",
                            style = MaterialTheme.typography.displaySmall,
                            color = Color.White
                        )
                        Text(
                            text = "Putar & simpan aliran video dari web bebas iklan dan tanpa tombol unduh.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                    }
                }
            }

            // Interactive Demo CTA Card
            item {
                Surface(
                    onClick = onOpenDemo,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_demo_page_card")
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircleFilled,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Uji Coba: Halaman Tanpa Tombol Unduh",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Buka simulasi pemutar MP4 & HLS (.m3u8) terproteksi untuk mencoba tangkapan otomatis.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // How It Works Micro Strip
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StepMiniCard(
                        number = "1",
                        label = "Buka Situs",
                        modifier = Modifier.weight(1f)
                    )
                    StepMiniCard(
                        number = "2",
                        label = "Putar Video",
                        modifier = Modifier.weight(1f)
                    )
                    StepMiniCard(
                        number = "3",
                        label = "Simpan Offline",
                        modifier = Modifier.weight(1f)
                    )
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

@Composable
private fun StepMiniCard(
    number: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
