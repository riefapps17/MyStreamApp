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

object DemoStreamPage {
    const val DEMO_URL = "streamvault://demo"
    const val BASE_URL = "https://streamvault.local/"

    val htmlContent = """
        <!DOCTYPE html>
        <html lang="id">
        <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <title>Studio Demo — Tanpa Tombol Unduh</title>
            <style>
                body {
                    margin: 0; padding: 18px; background: #0B0F14; color: #F0F4F8;
                    font-family: -apple-system, BlinkMacSystemFont, sans-serif;
                }
                .badge {
                    display: inline-block; padding: 4px 10px; border-radius: 999px;
                    background: rgba(0, 229, 195, 0.14); color: #00E5C3;
                    font-size: 12px; font-weight: 600; margin-bottom: 10px;
                }
                h1 { font-size: 20px; margin: 0 0 8px 0; }
                p { font-size: 13px; color: #94A3B8; line-height: 1.5; margin: 0 0 16px 0; }
                .card {
                    background: #131922; border: 1px solid #2A3647; border-radius: 16px;
                    padding: 14px; margin-bottom: 18px;
                }
                .card-title { font-size: 15px; font-weight: 600; margin-bottom: 4px; }
                .card-sub { font-size: 12px; color: #94A3B8; margin-bottom: 10px; }
                video {
                    width: 100%; border-radius: 12px; background: #000;
                    max-height: 220px;
                }
                .notice {
                    margin-top: 8px; font-size: 11px; color: #FF7A45; font-weight: 600;
                }
                .ad-banner {
                    background: linear-gradient(135deg, #4D1E0B, #2B0B00);
                    border: 1px dashed #FF7A45;
                    border-radius: 14px;
                    padding: 12px;
                    margin-bottom: 16px;
                    font-size: 12px;
                    color: #FFD6C4;
                }
            </style>
        </head>
        <body oncontextmenu="return false;">
            <div class="badge">SIMULASI STREAMING TERPROTEKSI</div>
            <h1>Pemutar Web Tanpa Tombol Download</h1>
            <p>
                Halaman ini meniru situs streaming yang memakai <code>controlsList="nodownload"</code>
                dan memblokir klik kanan. Putar salah satu video di bawah — StreamVault akan
                menangkap aliran MP4 dan HLS (.m3u8) secara otomatis di tombol melayang!
            </p>

            <div id="demo-ad-banner" class="ad-banner">
                <strong>[Simulasi Banner Iklan]</strong> Elemen ini disembunyikan otomatis saat Perisai AdBlock aktif.
            </div>

            <div class="card">
                <div class="card-title">1. Big Buck Bunny (Stream MP4 Langsung)</div>
                <div class="card-sub">Blender Foundation • HTML5 Video tanpa tombol unduh</div>
                <video
                    controls
                    playsinline
                    preload="metadata"
                    controlsList="nodownload noplaybackrate"
                    disablePictureInPicture
                    poster="https://peach.blender.org/wp-content/uploads/title_anouncement.jpg?x11217"
                    src="https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4">
                </video>
                <div class="notice">controlsList="nodownload" aktif di halaman ini</div>
            </div>

            <div class="card">
                <div class="card-title">2. Sintel Trailer (Adaptive HLS .m3u8)</div>
                <div class="card-sub">Stream adaptif M3U8 • Siap diputar & digabung ke offline</div>
                <video
                    id="hlsDemo"
                    controls
                    playsinline
                    preload="metadata"
                    controlsList="nodownload">
                    <source src="https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" type="application/x-mpegURL" />
                </video>
                <div class="notice">Aliran HLS (.m3u8) tanpa tautan unduhan</div>
            </div>

            <div class="card">
                <div class="card-title">3. For Bigger Blazes (Klip HD MP4)</div>
                <div class="card-sub">Klip singkat 15 detik • Cepat untuk uji simpan</div>
                <video
                    controls
                    playsinline
                    preload="metadata"
                    controlsList="nodownload"
                    src="https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4">
                </video>
            </div>
        </body>
        </html>
    """.trimIndent()
}

class StreamSnifferBridge(
    private val onMediaDetected: (
        url: String,
        title: String,
        resolution: String,
        durationSeconds: Int,
        origin: String,
        audioUrl: String
    ) -> Unit,
    private val onCosmeticAdBlocked: (count: Int) -> Unit
) {
    @JavascriptInterface
    fun onMediaFound(
        url: String,
        title: String,
        resolution: String,
        durationSeconds: Int,
        origin: String,
        audioUrl: String
    ) {
        onMediaDetected(url, title, resolution, durationSeconds, origin, audioUrl)
    }

    @JavascriptInterface
    fun onCosmeticBlocked(count: Int) {
        if (count > 0) {
            onCosmeticAdBlocked(count)
        }
    }
}

interface WebViewCallbacks {
    fun onPageStarted(tabId: String, url: String)
    fun onPageFinished(tabId: String, url: String, title: String, canBack: Boolean, canForward: Boolean)
    fun onProgressChanged(tabId: String, progress: Int)
    fun onTitleChanged(tabId: String, title: String)
    fun onNavigationStateChanged(tabId: String, canBack: Boolean, canForward: Boolean)
    fun onUrlVisited(tabId: String, url: String, canBack: Boolean, canForward: Boolean)
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
        audioUrl: String
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
    isPopupBlockEnabled: () -> Boolean
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
                onMediaDetected = { mediaUrl, title, res, dur, origin, audioUrl ->
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
                        audioUrl = normalizedAudio
                    )
                },
                onCosmeticAdBlocked = { count ->
                    callbacks.onAdBlocked(tabId, currentPageUrlRef.get().orEmpty(), count)
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
                val cleanUrl = if (url == DemoStreamPage.BASE_URL) DemoStreamPage.DEMO_URL else url
                currentPageUrlRef.set(cleanUrl)
                currentPageHostRef.set(runCatching { Uri.parse(cleanUrl).host.orEmpty() }.getOrDefault(""))
                callbacks.onPageStarted(tabId, cleanUrl)
                callbacks.onNavigationStateChanged(tabId, hasValidBackHistory(view), view.canGoForward())
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (url.isNullOrBlank() || url.equals("about:blank", ignoreCase = true)) {
                    return
                }
                val cleanUrl = if (url == DemoStreamPage.BASE_URL) DemoStreamPage.DEMO_URL else url
                currentPageUrlRef.set(cleanUrl)
                currentPageHostRef.set(runCatching { Uri.parse(cleanUrl).host.orEmpty() }.getOrDefault(""))
                val pageTitle = view.title?.takeIf { it.isNotBlank() } ?: cleanUrl
                callbacks.onPageFinished(tabId, cleanUrl, pageTitle, hasValidBackHistory(view), view.canGoForward())
                view.evaluateJavascript(MediaSniffer.MEDIA_SNIFFER_JS, null)
                if (isAdBlockEnabled()) {
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
                val cleanUrl = if (url == DemoStreamPage.BASE_URL) DemoStreamPage.DEMO_URL else url
                currentPageUrlRef.set(cleanUrl)
                currentPageHostRef.set(runCatching { Uri.parse(cleanUrl).host.orEmpty() }.getOrDefault(""))
                callbacks.onUrlVisited(tabId, cleanUrl, hasValidBackHistory(view), view.canGoForward())
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

                // 1. Run AdBlockEngine filter on sub-resources when enabled
                if (isAdBlockEnabled() &&
                    !request.isForMainFrame &&
                    AdBlockEngine.shouldBlockRequest(reqUrl, currentPageHostRef.get().orEmpty())
                ) {
                    callbacks.onAdBlocked(tabId, reqUrl, 1)
                    return AdBlockEngine.createEmptyResponse()
                }

                // 2. Run MediaSniffer to capture playable streaming URLs (direct, HLS, DASH, extensionless CDN)
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
                        audioUrl = ""
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
                // Block unsolicited popup windows when popup blocker is active
                if (isPopupBlockEnabled() && !isUserGesture) {
                    callbacks.onPopupBlocked(tabId, currentPageUrlRef.get().orEmpty())
                    return false
                }

                val hitUrl = view?.hitTestResult?.extra.orEmpty()
                if (hitUrl.isNotBlank()) {
                    if (AdBlockEngine.shouldBlockNavigation(
                            targetUrl = hitUrl,
                            currentPageUrl = currentPageUrlRef.get().orEmpty(),
                            isAdBlockEnabled = isAdBlockEnabled(),
                            isPopupBlockEnabled = isPopupBlockEnabled()
                        )
                    ) {
                        callbacks.onPopupBlocked(tabId, hitUrl)
                        return false
                    }
                    // Open legitimate target="_blank" link in the same WebView instead of spawning a popup
                    view?.loadUrl(hitUrl)
                    return false
                }

                if (isPopupBlockEnabled()) {
                    callbacks.onPopupBlocked(tabId, currentPageUrlRef.get().orEmpty())
                }
                return false
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

    val targetUrl = list.getItemAtIndex(targetIndex)?.url.orEmpty()
    if (targetUrl == DemoStreamPage.BASE_URL) {
        webView.loadDataWithBaseURL(
            DemoStreamPage.BASE_URL,
            DemoStreamPage.htmlContent,
            "text/html",
            "utf-8",
            null
        )
        return true
    }

    val steps = targetIndex - currentIndex
    if (webView.canGoBackOrForward(steps)) {
        webView.goBackOrForward(steps)
        return true
    }
    return false
}
