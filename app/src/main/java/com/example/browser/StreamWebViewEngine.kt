package com.example.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicReference

class StreamSnifferBridge(
    private val onMediaDetected: (
        url: String,
        title: String,
        resolution: String,
        durationSeconds: Int,
        origin: String,
        audioUrl: String,
        thumbnailUrl: String
    ) -> Unit,
    private val onPagePosterDetected: (posterUrl: String) -> Unit,
    private val onCosmeticAdBlocked: (count: Int) -> Unit,
    private val onJsPopupBlocked: (url: String) -> Unit = {}
) {
    @JavascriptInterface
    fun onMediaFound(
        url: String,
        title: String,
        resolution: String,
        durationSeconds: Int,
        origin: String,
        audioUrl: String,
        thumbnailUrl: String
    ) {
        onMediaDetected(url, title, resolution, durationSeconds, origin, audioUrl, thumbnailUrl)
    }

    @JavascriptInterface
    fun onPagePosterFound(posterUrl: String) {
        if (posterUrl.isNotBlank()) {
            onPagePosterDetected(posterUrl)
        }
    }

    @JavascriptInterface
    fun onCosmeticBlocked(count: Int) {
        if (count > 0) {
            onCosmeticAdBlocked(count)
        }
    }

    @JavascriptInterface
    fun onPopupBlockedInJs(url: String) {
        onJsPopupBlocked(url)
    }
}

interface WebViewCallbacks {
    fun onPageStarted(tabId: String, url: String)
    fun onPageFinished(tabId: String, url: String, title: String, canBack: Boolean, canForward: Boolean)
    fun onProgressChanged(tabId: String, progress: Int)
    fun onTitleChanged(tabId: String, title: String)
    fun onNavigationStateChanged(tabId: String, canBack: Boolean, canForward: Boolean)
    fun onUrlVisited(tabId: String, url: String, canBack: Boolean, canForward: Boolean)
    fun onPagePosterFound(tabId: String, posterUrl: String)
    fun onMediaSniffed(
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
    )
    fun onProbeCandidateFound(
        tabId: String,
        url: String,
        referer: String,
        originHeader: String,
        userAgent: String,
        cookie: String
    )
    fun onAdBlocked(tabId: String, blockedUrl: String, count: Int = 1)
    fun onPopupBlocked(tabId: String, blockedUrl: String)
    fun onEnterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
    fun onExitFullscreen()
}

@SuppressLint("SetJavaScriptEnabled")
fun createConfiguredWebView(
    context: Context,
    tabId: String,
    callbacks: WebViewCallbacks,
    isAdBlockEnabled: () -> Boolean,
    isPopupBlockEnabled: () -> Boolean,
    isTabActive: () -> Boolean = { true }
): WebView {
    // Thread-safe mirror of current page URL/host for IO-thread shouldInterceptRequest access
    val currentPageUrlRef = AtomicReference("")
    val currentPageHostRef = AtomicReference("")

    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            // Remove WebView "; wv" token so streaming players & CDNs treat browser as standard Mobile Chrome
            userAgentString = userAgentString.replace("; wv)", ")").replace("; wv;", ";")
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        val defaultUserAgent = settings.userAgentString

        addJavascriptInterface(
            StreamSnifferBridge(
                onMediaDetected = { mediaUrl, title, res, dur, origin, audioUrl, thumbUrl ->
                    // Strictly detect videos only for the currently active tab
                    if (!isTabActive()) return@StreamSnifferBridge
                    val currentPage = currentPageUrlRef.get().orEmpty()
                    val normalizedUrl = MediaSniffer.normalizeVideoUrl(mediaUrl)
                    val normalizedAudio = if (audioUrl.isNotBlank()) MediaSniffer.normalizeVideoUrl(audioUrl) else ""
                    val originHeader = MediaSniffer.deriveOriginHeader(currentPage, normalizedUrl)
                    val cookie = runCatching {
                        CookieManager.getInstance().getCookie(normalizedUrl)
                            ?: CookieManager.getInstance().getCookie(currentPage)
                    }.getOrNull().orEmpty()
                    callbacks.onMediaSniffed(
                        tabId = tabId,
                        url = normalizedUrl,
                        title = title,
                        mimeHint = null,
                        resolution = res,
                        durationSeconds = dur,
                        referer = currentPage,
                        originHeader = originHeader,
                        userAgent = defaultUserAgent,
                        cookie = cookie,
                        origin = origin,
                        audioUrl = normalizedAudio,
                        thumbnailUrl = thumbUrl
                    )
                },
                onPagePosterDetected = { posterUrl ->
                    if (!isTabActive()) return@StreamSnifferBridge
                    callbacks.onPagePosterFound(tabId, posterUrl)
                },
                onCosmeticAdBlocked = { count ->
                    callbacks.onAdBlocked(tabId, currentPageUrlRef.get().orEmpty(), count)
                },
                onJsPopupBlocked = { blockedUrl ->
                    callbacks.onPopupBlocked(
                        tabId,
                        blockedUrl.ifBlank { currentPageUrlRef.get().orEmpty() }
                    )
                }
            ),
            "StreamVaultBridge"
        )

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url.isNullOrBlank() || url.equals("about:blank", ignoreCase = true)) {
                    return
                }
                currentPageUrlRef.set(url)
                currentPageHostRef.set(runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault(""))
                callbacks.onPageStarted(tabId, url)
                callbacks.onNavigationStateChanged(tabId, hasValidBackHistory(view), view.canGoForward())
                if (isAdBlockEnabled() || isPopupBlockEnabled()) {
                    view.evaluateJavascript(
                        AdBlockEngine.buildCosmeticScript(blockPopups = isPopupBlockEnabled()),
                        null
                    )
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (url.isNullOrBlank() || url.equals("about:blank", ignoreCase = true)) {
                    return
                }
                currentPageUrlRef.set(url)
                currentPageHostRef.set(runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault(""))
                val pageTitle = view.title?.takeIf { it.isNotBlank() } ?: url
                callbacks.onPageFinished(tabId, url, pageTitle, hasValidBackHistory(view), view.canGoForward())
                if (isTabActive()) {
                    view.evaluateJavascript(MediaSniffer.MEDIA_SNIFFER_JS, null)
                }
                if (isAdBlockEnabled() || isPopupBlockEnabled()) {
                    view.evaluateJavascript(
                        AdBlockEngine.buildCosmeticScript(blockPopups = isPopupBlockEnabled()),
                        null
                    )
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (view == null || url.isNullOrBlank() || url.equals("about:blank", ignoreCase = true)) {
                    return
                }
                currentPageUrlRef.set(url)
                currentPageHostRef.set(runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault(""))
                callbacks.onUrlVisited(tabId, url, hasValidBackHistory(view), view.canGoForward())
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val targetUrl = request.url?.toString().orEmpty()
                if (targetUrl.isBlank()) return false

                if (AdBlockEngine.shouldBlockNavigation(
                        targetUrl = targetUrl,
                        currentPageUrl = currentPageUrlRef.get().orEmpty(),
                        isAdBlockEnabled = isAdBlockEnabled(),
                        isPopupBlockEnabled = isPopupBlockEnabled()
                    )
                ) {
                    callbacks.onPopupBlocked(tabId, targetUrl)
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val reqUrl = request.url?.toString().orEmpty()

                // 1. Run AdBlockEngine filter on sub-resources when enabled (returns harmless JS stub for .js bait scripts)
                if (isAdBlockEnabled() &&
                    !request.isForMainFrame &&
                    AdBlockEngine.shouldBlockRequest(reqUrl, currentPageHostRef.get().orEmpty())
                ) {
                    callbacks.onAdBlocked(tabId, reqUrl, 1)
                    return AdBlockEngine.createBlockedResponse(reqUrl)
                }

                // 2. Run MediaSniffer ONLY when this tab is currently active
                if (!isTabActive()) {
                    return super.shouldInterceptRequest(view, request)
                }

                val normalizedUrl = MediaSniffer.normalizeVideoUrl(reqUrl)
                val acceptHeader = request.requestHeaders["Accept"]
                val rangeHeader = request.requestHeaders["Range"]
                val secFetchDest = request.requestHeaders["Sec-Fetch-Dest"]
                val referer = request.requestHeaders["Referer"].orEmpty().ifBlank { currentPageUrlRef.get().orEmpty() }
                // Preserve exact Origin sent by WebView (avoid forcing a mismatched Origin on non-CORS CDNs)
                val originHeader = request.requestHeaders["Origin"].orEmpty()
                val ua = request.requestHeaders["User-Agent"] ?: defaultUserAgent

                if (MediaSniffer.classifyUrl(normalizedUrl, acceptHeader) != null) {
                    val cookie = runCatching {
                        CookieManager.getInstance().getCookie(normalizedUrl)
                            ?: CookieManager.getInstance().getCookie(referer)
                    }.getOrNull().orEmpty()
                    callbacks.onMediaSniffed(
                        tabId = tabId,
                        url = normalizedUrl,
                        title = "",
                        mimeHint = acceptHeader,
                        resolution = "",
                        durationSeconds = 0,
                        referer = referer,
                        originHeader = originHeader,
                        userAgent = ua,
                        cookie = cookie,
                        origin = "Network",
                        audioUrl = "",
                        thumbnailUrl = ""
                    )
                } else if (!request.isForMainFrame &&
                    MediaSniffer.isProbeCandidate(normalizedUrl, acceptHeader, rangeHeader, secFetchDest)
                ) {
                    val cookie = runCatching {
                        CookieManager.getInstance().getCookie(normalizedUrl)
                            ?: CookieManager.getInstance().getCookie(referer)
                    }.getOrNull().orEmpty()
                    callbacks.onProbeCandidateFound(
                        tabId = tabId,
                        url = normalizedUrl,
                        referer = referer,
                        originHeader = originHeader,
                        userAgent = ua,
                        cookie = cookie
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                callbacks.onProgressChanged(tabId, newProgress)
                if (newProgress in 25..75 && (isAdBlockEnabled() || isPopupBlockEnabled())) {
                    view.evaluateJavascript(
                        AdBlockEngine.buildCosmeticScript(blockPopups = isPopupBlockEnabled()),
                        null
                    )
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank()) {
                    callbacks.onTitleChanged(tabId, title)
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val currentPageUrl = currentPageUrlRef.get().orEmpty()
                // Block unsolicited popup windows when popup blocker is active
                if (isPopupBlockEnabled() && !isUserGesture) {
                    callbacks.onPopupBlocked(tabId, currentPageUrl)
                    return false
                }

                val hitUrl = view?.hitTestResult?.extra.orEmpty()
                if (hitUrl.isNotBlank()) {
                    if (AdBlockEngine.shouldBlockPopupWindow(
                            popupUrl = hitUrl,
                            currentPageUrl = currentPageUrl,
                            isAdBlockEnabled = isAdBlockEnabled(),
                            isPopupBlockEnabled = isPopupBlockEnabled()
                        )
                    ) {
                        callbacks.onPopupBlocked(tabId, hitUrl)
                        return false
                    }
                    // Open legitimate same-site target="_blank" link in the current WebView instead of spawning a popup
                    view?.loadUrl(hitUrl)
                    return false
                }

                if (isPopupBlockEnabled()) {
                    callbacks.onPopupBlocked(tabId, currentPageUrl)
                }
                return false
            }

            override fun onJsBeforeUnload(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                if (isPopupBlockEnabled()) {
                    result?.confirm()
                    return true
                }
                return super.onJsBeforeUnload(view, url, message, result)
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                val host = runCatching { Uri.parse(url.orEmpty()).host.orEmpty() }.getOrDefault("")
                val msgLower = message.orEmpty().lowercase()
                if (isPopupBlockEnabled() &&
                    (AdBlockEngine.isAdHost(host) ||
                        (msgLower.contains("adblock") && (msgLower.contains("disable") || msgLower.contains("matikan"))))
                ) {
                    result?.cancel()
                    callbacks.onPopupBlocked(tabId, url.orEmpty())
                    return true
                }
                return super.onJsAlert(view, url, message, result)
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null && callback != null) {
                    callbacks.onEnterFullscreen(view, callback)
                }
            }

            override fun onHideCustomView() {
                callbacks.onExitFullscreen()
            }
        }
    }
}

fun hasValidBackHistory(webView: WebView): Boolean {
    val list = webView.copyBackForwardList()
    val currentIndex = list.currentIndex
    for (i in currentIndex - 1 downTo 0) {
        val u = list.getItemAtIndex(i)?.url.orEmpty()
        if (u.isNotBlank() && !u.equals("about:blank", ignoreCase = true)) {
            return true
        }
    }
    return false
}

/**
 * Navigates back in WebView while skipping blank/empty entries and restoring the
 * built-in Demo page cleanly instead of landing on an empty screen.
 * Returns true if WebView navigated back to a real page, false if caller should return Home.
 */
fun goBackSafely(webView: WebView): Boolean {
    val list = webView.copyBackForwardList()
    val currentIndex = list.currentIndex
    var targetIndex = -1
    for (i in currentIndex - 1 downTo 0) {
        val u = list.getItemAtIndex(i)?.url.orEmpty()
        if (u.isNotBlank() && !u.equals("about:blank", ignoreCase = true)) {
            targetIndex = i
            break
        }
    }
    if (targetIndex < 0) return false

    val steps = targetIndex - currentIndex
    if (webView.canGoBackOrForward(steps)) {
        webView.goBackOrForward(steps)
        return true
    }
    return false
}
