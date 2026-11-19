package com.example.browser

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class MediaFormat(val label: String, val extension: String, val defaultMime: String) {
    MP4("MP4", "mp4", "video/mp4"),
    HLS("HLS", "ts", "application/vnd.apple.mpegurl"),
    WEBM("WEBM", "webm", "video/webm"),
    MKV("MKV", "mkv", "video/x-matroska"),
    MOV("MOV", "mov", "video/quicktime"),
    DASH("DASH", "mpd", "application/dash+xml"),
    VIDEO("VIDEO", "mp4", "video/mp4")
}

data class DetectedMedia(
    val id: String,
    val url: String,
    val title: String,
    val format: MediaFormat,
    val mimeType: String,
    val resolution: String = "",
    val durationSeconds: Int = 0,
    val sizeBytes: Long = -1L,
    val referer: String = "",
    val originHeader: String = "",
    val userAgent: String = "",
    val cookie: String = "",
    val audioUrl: String = "",
    val sourceOrigin: String = "Network",
    val detectedAt: Long = System.currentTimeMillis()
)

data class MediaProbeResult(
    val sizeBytes: Long,
    val contentType: String?
)

object MediaSniffer {

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val ignoredExtensions = setOf(
        "js", "mjs", "css", "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico",
        "woff", "woff2", "ttf", "otf", "eot", "json", "vtt", "srt", "ass",
        "html", "htm", "php", "txt", "map"
    )

    private val directVideoExtensions = mapOf(
        "mp4" to MediaFormat.MP4,
        "m4v" to MediaFormat.MP4,
        "webm" to MediaFormat.WEBM,
        "mkv" to MediaFormat.MKV,
        "mov" to MediaFormat.MOV,
        "3gp" to MediaFormat.MP4
    )

    private val knownVideoCdnHosts = listOf(
        "tiktokcdn.com",
        "tiktokcdn-us.com",
        "tiktokcdn-eu.com",
        "tiktokv.com",
        "muscdn.com",
        "byteoversea.com",
        "ibytedtos.com",
        "video.twimg.com",
        "v.redd.it",
        "cdninstagram.com",
        "fbcdn.net",
        "vimeocdn.com",
        "dmcdn.net"
    )

    /**
     * Removes MSE byte-range query parameters (bytestart/byteend/range) so IG/FB/YouTube
     * chunked requests resolve to the full downloadable video URL instead of a tiny slice.
     */
    fun normalizeVideoUrl(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.contains("bytestart=", ignoreCase = true) &&
            !trimmed.contains("byteend=", ignoreCase = true)
        ) {
            return trimmed
        }
        return runCatching {
            val uri = Uri.parse(trimmed)
            val builder = uri.buildUpon().clearQuery()
            for (name in uri.queryParameterNames) {
                val lower = name.lowercase(Locale.US)
                if (lower == "bytestart" || lower == "byteend") continue
                uri.getQueryParameters(name).forEach { value ->
                    builder.appendQueryParameter(name, value)
                }
            }
            builder.build().toString()
        }.getOrElse { trimmed }
    }

    fun deriveOriginHeader(referer: String, fallbackUrl: String = ""): String {
        val candidate = referer.ifBlank { fallbackUrl }
        if (candidate.isBlank() || candidate.startsWith("streamvault://")) return ""
        return runCatching {
            val uri = Uri.parse(candidate)
            val scheme = uri.scheme
            val host = uri.host
            if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) {
                val port = if (uri.port != -1) ":${uri.port}" else ""
                "$scheme://$host$port"
            } else {
                ""
            }
        }.getOrDefault("")
    }

    fun classifyUrl(url: String, hintMime: String? = null): MediaFormat? {
        if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) {
            return null
        }

        val normalized = url.lowercase(Locale.US)
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host?.lowercase(Locale.US).orEmpty()
        val path = (uri.path ?: "").lowercase(Locale.US)
        val ext = path.substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .substringBefore('?')
            .substringBefore('#')

        // Skip obvious non-video assets and single HLS/DASH segment chunks
        if (ext in ignoredExtensions || ext == "ts" || ext == "m4s") {
            return null
        }

        // Skip audio-only streams from being treated as primary video
        if (normalized.contains("mime=audio") ||
            normalized.contains("mime%3daudio") ||
            normalized.contains("mime_type=audio") ||
            ext == "m4a" || ext == "mp3" || ext == "aac" || ext == "ogg"
        ) {
            return null
        }

        // 1. Check MIME hint (from Content-Type probe or Accept/type hint)
        val mime = hintMime?.lowercase(Locale.US).orEmpty()
        if (mime.contains("mpegurl") || mime.contains("m3u8")) return MediaFormat.HLS
        if (mime.contains("dash+xml")) return MediaFormat.DASH
        if (mime.startsWith("video/")) {
            return when {
                mime.contains("webm") -> MediaFormat.WEBM
                mime.contains("matroska") -> MediaFormat.MKV
                mime.contains("quicktime") -> MediaFormat.MOV
                else -> MediaFormat.MP4
            }
        }

        // 2. Check explicit streaming/video extensions
        if (ext == "m3u8" || normalized.contains(".m3u8?") || normalized.endsWith(".m3u8")) {
            return MediaFormat.HLS
        }
        if (ext == "mpd" || normalized.contains(".mpd?") || normalized.endsWith(".mpd")) {
            return MediaFormat.DASH
        }
        directVideoExtensions[ext]?.let { return it }

        // 3. Check streaming query parameters used by CDNs without file extensions
        val hasVideoQuery = (normalized.contains("mime=video") ||
            normalized.contains("mime%3dvideo") ||
            normalized.contains("mime_type=video") ||
            normalized.contains("content_type=video") ||
            normalized.contains("format=mp4") ||
            normalized.contains("type=mp4") ||
            (normalized.contains("/videoplayback") && normalized.contains("itag=")))
        if (hasVideoQuery) {
            return MediaFormat.MP4
        }

        // 4. Extensionless CDN video URL patterns (TikTok, IG/FB, X, Reddit, Vimeo)
        if (isExtensionlessTikTokVideo(host, path, normalized)) {
            return MediaFormat.MP4
        }
        if (isExtensionlessMetaVideo(host, path, normalized)) {
            return MediaFormat.MP4
        }
        if (host.endsWith("video.twimg.com") &&
            (path.contains("/vid/") || path.contains("/ext_tw_video/") || path.contains("/amplify_video/"))
        ) {
            return if (path.contains("m3u8") || path.contains("pl/")) MediaFormat.HLS else MediaFormat.MP4
        }
        if (host.endsWith("v.redd.it") && path.contains("dash")) {
            return if (path.contains("mpd")) MediaFormat.DASH else MediaFormat.MP4
        }

        return null
    }

    private fun isExtensionlessTikTokVideo(host: String, path: String, fullLowerUrl: String): Boolean {
        val isTikTokHost = host.contains("tiktokcdn") ||
            host.contains("tiktokv.com") ||
            host.contains("tiktok.com") ||
            host.contains("byteoversea.com") ||
            host.contains("ibytedtos.com") ||
            host.contains("muscdn.com")
        if (!isTikTokHost) return false

        return path.contains("/video/tos/") ||
            path.contains("/aweme/v1/play") ||
            (path.contains("/tos-") && (fullLowerUrl.contains("mime_type=video") || fullLowerUrl.contains("item_id=")))
    }

    private fun isExtensionlessMetaVideo(host: String, path: String, fullLowerUrl: String): Boolean {
        val isMetaCdn = host.contains("cdninstagram.com") || host.contains("fbcdn.net")
        if (!isMetaCdn) return false

        // Meta CDN serves images with /t51. and videos with /o1/v/t2/ or /v/t42/ /v/t50/ /v/t16/ or efg=...dash/video
        val looksLikeVideoPath = path.contains("/o1/v/t2/") ||
            path.contains("/v/t42.") ||
            path.contains("/v/t50.") ||
            path.contains("/v/t16.") ||
            path.contains("/v/t2/") ||
            fullLowerUrl.contains("video_mp4") ||
            (fullLowerUrl.contains("efg=") && (fullLowerUrl.contains("dash") || fullLowerUrl.contains("xpv")))
        // Exclude audio-only Meta DASH tracks from primary video list
        val isAudioTrack = fullLowerUrl.contains("audio") || fullLowerUrl.contains("heaac") || fullLowerUrl.contains("dash_ln_heaac")
        return looksLikeVideoPath && !isAudioTrack
    }

    /**
     * Identifies extensionless requests that strongly signal a streaming media fetch
     * (e.g., Range requests, Sec-Fetch-Dest: video, or known media CDNs) so the ViewModel
     * can run a lightweight Content-Type probe in the background.
     */
    fun isProbeCandidate(
        url: String,
        acceptHeader: String?,
        rangeHeader: String?,
        secFetchDest: String?
    ): Boolean {
        if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        if (scheme != "http" && scheme != "https") return false

        val host = uri.host?.lowercase(Locale.US).orEmpty()
        val path = (uri.path ?: "").lowercase(Locale.US)
        val ext = path.substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .substringBefore('?')
            .substringBefore('#')

        if (ext in ignoredExtensions || ext == "ts" || ext == "m4s") return false
        if (AdBlockEngine.isAdHost(host)) return false

        val dest = secFetchDest?.lowercase(Locale.US).orEmpty()
        if (dest == "video") return true

        val accept = acceptHeader?.lowercase(Locale.US).orEmpty()
        if (accept.contains("video/")) return true

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
            return true
        }

        if (knownVideoCdnHosts.any { host == it || host.endsWith(".$it") }) {
            return path.contains("video") || path.contains("/v/") || path.contains("play") || path.contains("stream")
        }

        return false
    }

    fun deriveTitle(url: String, pageTitle: String, format: MediaFormat): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val lastSegment = uri?.lastPathSegment
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.trim()
            .orEmpty()

        val cleanSegment = lastSegment.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        if (cleanSegment.length in 4..48 &&
            cleanSegment.contains('.') &&
            !cleanSegment.startsWith("tos_") &&
            !cleanSegment.matches(Regex("^[0-9a-fA-F_.-]+$"))
        ) {
            return cleanSegment
        }
        val cleanPageTitle = pageTitle
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(44)
        return if (cleanPageTitle.isNotBlank() && cleanPageTitle != "about:blank") {
            "$cleanPageTitle (${format.label})"
        } else {
            "Video_${System.currentTimeMillis() % 100000}.${format.extension}"
        }
    }

    fun buildSafeFileName(title: String, format: MediaFormat): String {
        val base = title
            .replace(Regex("\\.[a-zA-Z0-9]{2,5}$"), "")
            .replace(Regex("[^a-zA-Z0-9._ -]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "stream_video" }
        val ext = when (format) {
            MediaFormat.HLS -> "ts"
            MediaFormat.DASH -> "mp4"
            else -> format.extension
        }
        return "${base}_${System.currentTimeMillis() % 10000}.$ext"
    }

    /**
     * Probes stream metadata using HEAD first, falling back to a 2-byte Range GET request
     * for CDNs (like TikTok/IG) that reject HEAD requests with 403/405.
     */
    suspend fun probeMediaMetadata(
        url: String,
        referer: String,
        originHeader: String = "",
        userAgent: String,
        cookie: String
    ): MediaProbeResult = withContext(Dispatchers.IO) {
        val effectiveOrigin = originHeader.ifBlank { deriveOriginHeader(referer, url) }

        fun buildBase(builder: Request.Builder): Request.Builder {
            if (userAgent.isNotBlank()) builder.header("User-Agent", userAgent)
            if (referer.isNotBlank()) builder.header("Referer", referer)
            if (effectiveOrigin.isNotBlank()) builder.header("Origin", effectiveOrigin)
            if (cookie.isNotBlank()) builder.header("Cookie", cookie)
            return builder
        }

        // Attempt 1: HEAD
        val headResult = runCatching {
            val req = buildBase(Request.Builder().url(url).head()).build()
            probeClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val len = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val ct = response.header("Content-Type")?.substringBefore(';')?.trim()
                MediaProbeResult(len, ct)
            }
        }.getOrNull()

        if (headResult != null && (!headResult.contentType.isNullOrBlank() || headResult.sizeBytes > 0L)) {
            return@withContext headResult
        }

        // Attempt 2: Range GET bytes=0-1 (safe 2-byte peek for CDNs blocking HEAD)
        runCatching {
            val req = buildBase(Request.Builder().url(url).get())
                .header("Range", "bytes=0-1")
                .build()
            probeClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    return@use MediaProbeResult(-1L, null)
                }
                val contentRange = response.header("Content-Range")
                val totalFromRange = contentRange
                    ?.substringAfter('/', "")
                    ?.trim()
                    ?.toLongOrNull()
                    ?: -1L
                val contentLength = if (totalFromRange > 0L) {
                    totalFromRange
                } else {
                    response.header("Content-Length")?.toLongOrNull() ?: -1L
                }
                val ct = response.header("Content-Type")?.substringBefore(';')?.trim()
                MediaProbeResult(contentLength, ct)
            }
        }.getOrElse { MediaProbeResult(-1L, null) }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "Ukuran stream"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    /**
     * Deep JavaScript sniffer injected into pages to capture:
     * 1) HTML5 <video>, <source>, og:video, direct links
     * 2) Embedded hydration/JSON metadata (TikTok, Instagram, Facebook, Next.js, LD+JSON)
     * 3) Live fetch() and XMLHttpRequest responses (Content-Type + JSON body URL extraction)
     * 4) PerformanceResourceTiming entries for missed CDN video requests
     */
    const val MEDIA_SNIFFER_JS = """
        (function() {
            if (!window.__streamVaultBridgeInstalled) {
                window.__streamVaultBridgeInstalled = true;
                window.__svReported = new Set();

                function unescapeJsonUrl(raw) {
                    if (!raw || typeof raw !== 'string') return '';
                    return raw
                        .replace(/\\u0026/gi, '&')
                        .replace(/\\u002f/gi, '/')
                        .replace(/\\u003d/gi, '=')
                        .replace(/\\\//g, '/')
                        .replace(/&amp;/g, '&')
                        .trim();
                }

                function report(url, title, res, dur, origin, audioUrl) {
                    try {
                        var clean = unescapeJsonUrl(url);
                        if (!clean || clean.indexOf('http') !== 0) return;
                        if (clean.indexOf('blob:') === 0 || clean.indexOf('data:') === 0) return;
                        if (clean.length > 2200) return;
                        var key = clean + '|' + (audioUrl || '');
                        if (window.__svReported.has(key)) return;
                        window.__svReported.add(key);

                        if (window.StreamVaultBridge && window.StreamVaultBridge.onMediaFound) {
                            window.StreamVaultBridge.onMediaFound(
                                clean,
                                title || document.title || '',
                                res || '',
                                Math.floor(dur || 0),
                                origin || 'DOM',
                                unescapeJsonUrl(audioUrl || '')
                            );
                        }
                    } catch (e) {}
                }

                function extractFromJsonText(text, sourceLabel) {
                    try {
                        if (!text || text.length < 30 || text.length > 1800000) return;

                        // 1. Keyed social/streaming video fields (TikTok, IG, FB, X, generic APIs)
                        var keyedRegex = /"(?:playAddr|downloadAddr|play_addr|video_url|playable_url_quality_hd|playable_url|browser_native_hd_url|browser_native_sd_url|hls_url|dash_manifest|manifest_url|contentUrl|hd_src|sd_src|fallback_url|videoUrl|streamUrl)"\s*:\s*"(https?:[^"\\]*(?:\\.[^"\\]*)*)"/gi;
                        var m;
                        var count = 0;
                        while ((m = keyedRegex.exec(text)) !== null && count < 12) {
                            var found = unescapeJsonUrl(m[1]);
                            if (found && !/\.(jpg|jpeg|png|webp|gif|svg|js|css)(\?|$)/i.test(found)) {
                                report(found, document.title, '', 0, sourceLabel);
                                count++;
                            }
                        }

                        // 2. Instagram video_versions array: "video_versions":[{..."url":"https://..."
                        var igIdx = text.indexOf('video_versions');
                        if (igIdx >= 0) {
                            var slice = text.substring(igIdx, Math.min(text.length, igIdx + 6000));
                            var igUrlRegex = /"url"\s*:\s*"(https?:[^"\\]*(?:\\.[^"\\]*)*)"/gi;
                            var im;
                            var igCount = 0;
                            while ((im = igUrlRegex.exec(slice)) !== null && igCount < 4) {
                                var igUrl = unescapeJsonUrl(im[1]);
                                if (igUrl && !/\.(jpg|jpeg|png|webp)(\?|$)/i.test(igUrl)) {
                                    report(igUrl, document.title, '', 0, 'IG JSON');
                                    igCount++;
                                }
                            }
                        }

                        // 3. Direct embedded .m3u8 / .mpd / .mp4 URLs inside JSON responses
                        var directRegex = /"(https?:\\?\/\\?\/[^"\s<>]{12,900}?(?:\.m3u8|\.mpd|\.mp4|\.webm)(?:[^"\s<>]{0,400})?)"/gi;
                        var dm;
                        var dCount = 0;
                        while ((dm = directRegex.exec(text)) !== null && dCount < 10) {
                            var dUrl = unescapeJsonUrl(dm[1]);
                            if (dUrl && dUrl.indexOf('mime=audio') < 0) {
                                report(dUrl, document.title, '', 0, sourceLabel);
                                dCount++;
                            }
                        }
                    } catch (e) {}
                }

                window.__streamVaultScanDom = function() {
                    try {
                        var videos = document.querySelectorAll('video');
                        videos.forEach(function(v) {
                            var res = (v.videoWidth && v.videoHeight) ? (v.videoWidth + 'x' + v.videoHeight) : '';
                            var dur = isFinite(v.duration) ? v.duration : 0;
                            var label = v.getAttribute('title') || v.getAttribute('aria-label') || document.title;
                            if (v.currentSrc) report(v.currentSrc, label, res, dur, 'DOM <video>');
                            if (v.src) report(v.src, label, res, dur, 'DOM <video>');
                            var sources = v.querySelectorAll('source');
                            sources.forEach(function(s) {
                                if (s.src) report(s.src, label, res, dur, 'DOM <source>');
                            });
                        });

                        var metas = document.querySelectorAll('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"], meta[name="twitter:player:stream"]');
                        metas.forEach(function(m) {
                            var c = m.getAttribute('content');
                            if (c) report(c, document.title, '', 0, 'Meta Video');
                        });

                        var anchors = document.querySelectorAll('a[href]');
                        anchors.forEach(function(a) {
                            var href = a.href || '';
                            if (/\.(mp4|m3u8|mpd|webm|mkv|mov)(\?|#|$)/i.test(href)) {
                                report(href, a.textContent.trim() || document.title, '', 0, 'Link');
                            }
                        });

                        // Scan hydration & JSON-LD scripts (TikTok, IG, FB, Next.js)
                        var scripts = document.querySelectorAll(
                            'script#__UNIVERSAL_DATA_FOR_REHYDRATION__, script#SIGI_STATE, script#__NEXT_DATA__, script[type="application/json"], script[type="application/ld+json"]'
                        );
                        scripts.forEach(function(sc) {
                            if (sc.textContent && sc.textContent.length > 40) {
                                extractFromJsonText(sc.textContent, 'JSON Data');
                            }
                        });

                        // Scan PerformanceResourceTiming for missed media URLs
                        if (window.performance && performance.getEntriesByType) {
                            var entries = performance.getEntriesByType('resource') || [];
                            for (var i = Math.max(0, entries.length - 60); i < entries.length; i++) {
                                var name = entries[i].name || '';
                                if (/\.(m3u8|mpd|mp4|webm)(\?|#|$)|\/video\/tos\/|video\.twimg\.com|mime_type=video/i.test(name)) {
                                    report(name, document.title, '', 0, 'Resource');
                                }
                            }
                        }
                    } catch (e) {}
                };

                // Hook fetch() requests AND inspect JSON / video Content-Type responses
                if (window.fetch) {
                    var origFetch = window.fetch;
                    window.fetch = function(input) {
                        var reqUrl = '';
                        try {
                            reqUrl = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                            if (/\.(m3u8|mp4|webm|mpd)(\?|#|$)|mime=video|mime_type=video|\/video\/tos\//i.test(reqUrl)) {
                                report(reqUrl, document.title, '', 0, 'Fetch');
                            }
                        } catch (e) {}

                        var promise = origFetch.apply(this, arguments);
                        try {
                            promise.then(function(resp) {
                                try {
                                    if (!resp) return;
                                    var ct = (resp.headers && resp.headers.get('content-type')) ? resp.headers.get('content-type').toLowerCase() : '';
                                    var finalUrl = resp.url || reqUrl;
                                    if (ct.indexOf('video/') === 0 || ct.indexOf('mpegurl') >= 0 || ct.indexOf('dash+xml') >= 0) {
                                        report(finalUrl, document.title, '', 0, 'Fetch Media');
                                    } else if (ct.indexOf('application/json') >= 0 || ct.indexOf('text/javascript') >= 0) {
                                        var clone = resp.clone();
                                        clone.text().then(function(txt) {
                                            extractFromJsonText(txt, 'API JSON');
                                        }).catch(function() {});
                                    }
                                } catch (e) {}
                            }).catch(function() {});
                        } catch (e) {}
                        return promise;
                    };
                }

                // Hook XMLHttpRequest open() + load response inspection
                if (window.XMLHttpRequest) {
                    var origOpen = XMLHttpRequest.prototype.open;
                    var origSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        try {
                            if (typeof url === 'string') {
                                this.__svUrl = new URL(url, window.location.href).href;
                                if (/\.(m3u8|mp4|webm|mpd)(\?|#|$)|mime=video|mime_type=video|\/video\/tos\//i.test(this.__svUrl)) {
                                    report(this.__svUrl, document.title, '', 0, 'XHR');
                                }
                            }
                        } catch (e) {}
                        return origOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                        try {
                            this.addEventListener('load', function() {
                                try {
                                    var ct = (this.getResponseHeader('content-type') || '').toLowerCase();
                                    var respUrl = this.responseURL || this.__svUrl || '';
                                    if (ct.indexOf('video/') === 0 || ct.indexOf('mpegurl') >= 0 || ct.indexOf('dash+xml') >= 0) {
                                        report(respUrl, document.title, '', 0, 'XHR Media');
                                    } else if ((ct.indexOf('application/json') >= 0 || ct.indexOf('text/plain') >= 0) &&
                                        (!this.responseType || this.responseType === 'text') &&
                                        this.responseText) {
                                        extractFromJsonText(this.responseText, 'XHR JSON');
                                    }
                                } catch (e) {}
                            });
                        } catch (e) {}
                        return origSend.apply(this, arguments);
                    };
                }

                // Observe dynamic <video> additions or src changes
                var scanTimer = null;
                function scheduleScan() {
                    if (scanTimer) return;
                    scanTimer = setTimeout(function() {
                        scanTimer = null;
                        window.__streamVaultScanDom();
                    }, 450);
                }

                var observer = new MutationObserver(scheduleScan);
                if (document.documentElement) {
                    observer.observe(document.documentElement, {
                        childList: true,
                        subtree: true,
                        attributes: true,
                        attributeFilter: ['src']
                    });
                }

                document.addEventListener('play', function() {
                    window.__streamVaultScanDom();
                }, true);
            }
            window.__streamVaultScanDom();
        })();
    """
}
