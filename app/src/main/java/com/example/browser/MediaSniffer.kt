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
    val thumbnailUrl: String = "",
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
        "woff", "woff2", "ttf", "otf", "eot", "vtt", "srt", "ass", "map"
    )

    private val probeableTextExtensions = setOf("txt", "php", "asp", "aspx", "jsp", "cgi", "json")

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
        "dmcdn.net",
        "googlevideo.com",
        "cloudflarestream.com",
        "b-cdn.net",
        "akamaized.net",
        "cloudfront.net",
        "fastly.net",
        "mux.com",
        "jwplatform.com",
        "jwpcdn.com",
        "okcdn.ru",
        "vkuservideo.net",
        "rumble.com",
        "kwcdn.com",
        "kwai-pro.com"
    )

    private val streamPathKeywords = listOf(
        "m3u8", "hls", "master", "playlist", "chunklist", "manifest",
        "stream", "video", "play", "mpd", "media", "embed", "get_video", "vid/"
    )

    /**
     * Removes MSE byte-range query parameters (bytestart/byteend) so IG/FB/YouTube
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

    /**
     * Inspects the first bytes of a network response to detect real media formats even when
     * streaming servers disguise playlists/videos with fake extensions (.txt/.php) or
     * generic Content-Type headers (text/plain, application/octet-stream).
     */
    fun detectMimeFromMagicBytes(bytes: ByteArray): String? {
        if (bytes.size < 6) return null

        // Check text-based manifests (#EXTM3U for HLS, <MPD for DASH)
        val asciiHead = runCatching {
            String(bytes, 0, bytes.size.coerceAtMost(256), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        }.getOrDefault("")

        if (asciiHead.startsWith("#EXTM3U", ignoreCase = true)) {
            return "application/vnd.apple.mpegurl"
        }
        if (asciiHead.contains("<MPD", ignoreCase = true) && asciiHead.contains("urn:mpeg:dash", ignoreCase = true)) {
            return "application/dash+xml"
        }
        if (asciiHead.startsWith("<MPD", ignoreCase = true) ||
            (asciiHead.startsWith("<?xml", ignoreCase = true) && asciiHead.contains("<MPD", ignoreCase = true))
        ) {
            return "application/dash+xml"
        }

        // MP4 / ISO Base Media File Format ('ftyp' at byte offset 4..7)
        if (bytes.size >= 12 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
        ) {
            return "video/mp4"
        }

        // WebM / Matroska EBML header (0x1A 0x45 0xDF 0xA3)
        if (bytes[0] == 0x1A.toByte() &&
            bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() &&
            bytes[3] == 0xA3.toByte()
        ) {
            return "video/webm"
        }

        return null
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

        // Skip single HLS/DASH segment chunks (we want the playlist, not individual .ts/.m4s chunks)
        if (ext == "ts" || ext == "m4s") {
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

        // 1. Check MIME hint FIRST (from Content-Type / Magic-Bytes probe) so disguised .txt/.php playlists match!
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

        // Skip obvious static non-video assets when no video MIME was confirmed
        if (ext in ignoredExtensions || ext == "html" || ext == "htm" || ext == "json") {
            return null
        }

        // 2. Check explicit streaming/video extensions
        if (ext == "m3u8" || normalized.contains(".m3u8?") || normalized.contains(".m3u8&") || normalized.endsWith(".m3u8")) {
            return MediaFormat.HLS
        }
        if (ext == "mpd" || normalized.contains(".mpd?") || normalized.contains(".mpd&") || normalized.endsWith(".mpd")) {
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
        if (normalized.contains("format=m3u8") || normalized.contains("type=m3u8")) {
            return MediaFormat.HLS
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

        val looksLikeVideoPath = path.contains("/o1/v/t2/") ||
            path.contains("/v/t42.") ||
            path.contains("/v/t50.") ||
            path.contains("/v/t16.") ||
            path.contains("/v/t2/") ||
            fullLowerUrl.contains("video_mp4") ||
            (fullLowerUrl.contains("efg=") && (fullLowerUrl.contains("dash") || fullLowerUrl.contains("xpv")))
        val isAudioTrack = fullLowerUrl.contains("audio") || fullLowerUrl.contains("heaac") || fullLowerUrl.contains("dash_ln_heaac")
        return looksLikeVideoPath && !isAudioTrack
    }

    /**
     * Identifies extensionless or disguised requests (.txt/.php/master/playlist/Range/video-dest/CDN)
     * so the ViewModel can run a lightweight Content-Type + Magic-Bytes probe in the background.
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
        val fullLower = url.lowercase(Locale.US)
        val ext = path.substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .substringBefore('?')
            .substringBefore('#')

        if (ext in ignoredExtensions || ext == "ts" || ext == "m4s" || ext == "html" || ext == "htm") {
            return false
        }
        if (AdBlockEngine.isAdHost(host)) return false

        // Skip obvious audio-only endpoints
        if (fullLower.contains("mime=audio") || fullLower.contains("mime_type=audio") || ext == "m4a" || ext == "mp3") {
            return false
        }

        val dest = secFetchDest?.lowercase(Locale.US).orEmpty()
        if (dest == "video") return true

        val accept = acceptHeader?.lowercase(Locale.US).orEmpty()
        if (accept.contains("video/") || accept.contains("mpegurl") || accept.contains("dash+xml")) return true

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
            return true
        }

        // Disguised HLS/DASH/MP4 endpoints (.txt, .php, extensionless) with streaming path hints
        val hasStreamKeyword = streamPathKeywords.any { path.contains(it) || fullLower.contains("?$it") || fullLower.contains("&$it") }
        if (hasStreamKeyword && (ext.isEmpty() || ext in probeableTextExtensions)) {
            // Avoid probing generic API/analytics json endpoints unless strongly video-named
            if (ext == "json" && !fullLower.contains("hls") && !fullLower.contains("m3u8") && !fullLower.contains("manifest")) {
                return false
            }
            return true
        }

        if (knownVideoCdnHosts.any { host == it || host.endsWith(".$it") }) {
            return hasStreamKeyword || path.contains("/v/") || path.contains("/e/")
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
            !cleanSegment.startsWith("master.") &&
            !cleanSegment.startsWith("index.") &&
            !cleanSegment.startsWith("playlist.") &&
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
     * Probes stream metadata with a lightweight 256-byte Range GET (with 403 header fallback & HEAD fallback)
     * to read BOTH Content-Type/size AND magic bytes (#EXTM3U, <MPD, ftyp, WebM) in one shot.
     */
    suspend fun probeMediaMetadata(
        url: String,
        referer: String,
        originHeader: String = "",
        userAgent: String,
        cookie: String
    ): MediaProbeResult = withContext(Dispatchers.IO) {
        fun buildAttempt(includeOrigin: Boolean, includeReferer: Boolean, methodHead: Boolean): Request {
            val builder = Request.Builder().url(url)
            if (methodHead) {
                builder.head()
            } else {
                builder.get().header("Range", "bytes=0-255")
            }
            if (userAgent.isNotBlank()) builder.header("User-Agent", userAgent)
            if (includeReferer && referer.isNotBlank()) builder.header("Referer", referer)
            if (includeOrigin && originHeader.isNotBlank()) builder.header("Origin", originHeader)
            if (cookie.isNotBlank()) builder.header("Cookie", cookie)
            builder.header("Accept", "*/*")
            return builder.build()
        }

        fun executeRangePeek(includeOrigin: Boolean, includeReferer: Boolean): Pair<Int, MediaProbeResult?> {
            return runCatching {
                probeClient.newCall(buildAttempt(includeOrigin, includeReferer, methodHead = false)).execute().use { response ->
                    val code = response.code
                    if (!response.isSuccessful && code != 206) {
                        return@use code to null
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
                    val rawCt = response.header("Content-Type")?.substringBefore(';')?.trim()

                    // Peek up to 256 bytes safely (handles servers that ignore Range and return 200 OK!)
                    val peekBuf = ByteArray(256)
                    var peekRead = 0
                    response.body?.byteStream()?.use { stream ->
                        while (peekRead < peekBuf.size) {
                            val r = stream.read(peekBuf, peekRead, peekBuf.size - peekRead)
                            if (r <= 0) break
                            peekRead += r
                        }
                    }
                    val magicMime = if (peekRead >= 6) {
                        detectMimeFromMagicBytes(peekBuf.copyOf(peekRead))
                    } else {
                        null
                    }

                    val resolvedCt = magicMime ?: rawCt
                    code to MediaProbeResult(contentLength, resolvedCt)
                }
            }.getOrElse { -1 to null }
        }

        // Attempt 1: Range GET with exact WebView headers
        val (code1, res1) = executeRangePeek(includeOrigin = originHeader.isNotBlank(), includeReferer = true)
        if (res1 != null) return@withContext res1

        // Attempt 2: Retry without Origin if server rejected CORS Origin with 401/403
        if ((code1 == 401 || code1 == 403) && originHeader.isNotBlank()) {
            val (code2, res2) = executeRangePeek(includeOrigin = false, includeReferer = true)
            if (res2 != null) return@withContext res2
            if (code2 == 401 || code2 == 403) {
                val (_, res3) = executeRangePeek(includeOrigin = false, includeReferer = false)
                if (res3 != null) return@withContext res3
            }
        } else if (code1 == 401 || code1 == 403) {
            val (_, res3) = executeRangePeek(includeOrigin = false, includeReferer = false)
            if (res3 != null) return@withContext res3
        }

        // Attempt 3: HEAD fallback for servers that disallow Range GET
        runCatching {
            probeClient.newCall(buildAttempt(includeOrigin = false, includeReferer = true, methodHead = true)).execute().use { response ->
                if (!response.isSuccessful) return@use MediaProbeResult(-1L, null)
                val len = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val ct = response.header("Content-Type")?.substringBefore(';')?.trim()
                MediaProbeResult(len, ct)
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
     * 1) HTML5 <video>, <source>, og:video, direct links, same-origin iframes
     * 2) Embedded hydration/JSON metadata & JWPlayer/VideoJS inline script configs
     * 3) Live fetch() and XMLHttpRequest responses (Content-Type + #EXTM3U + JSON body extraction)
     * 4) PerformanceResourceTiming entries for missed CDN video & playlist requests
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

                function resolveAbsUrl(raw, baseDoc) {
                    try {
                        var clean = unescapeJsonUrl(raw);
                        if (!clean) return '';
                        if (clean.indexOf('data:image/') === 0) return clean;
                        return new URL(clean, (baseDoc && baseDoc.location && baseDoc.location.href) || window.location.href).href;
                    } catch (e) {
                        return '';
                    }
                }

                function getPagePoster(doc) {
                    try {
                        var d = doc || document;
                        var m = d.querySelector('meta[property="og:image"], meta[property="og:image:secure_url"], meta[name="twitter:image"], meta[name="twitter:image:src"], link[rel="image_src"]');
                        var c = m ? (m.getAttribute('content') || m.getAttribute('href') || '') : '';
                        return resolveAbsUrl(c, d);
                    } catch (e) {
                        return '';
                    }
                }

                function captureVideoThumb(v, doc) {
                    try {
                        var posterAttr = v.getAttribute('poster') || v.poster || '';
                        if (posterAttr) {
                            var resolved = resolveAbsUrl(posterAttr, doc);
                            if (resolved) return resolved;
                        }
                        // Check nearby poster <img> inside player container
                        var parent = v.parentElement;
                        if (parent) {
                            var img = parent.querySelector('img[src]');
                            if (img && img.src && img.naturalWidth >= 80) {
                                return resolveAbsUrl(img.src, doc);
                            }
                        }
                        // Same-origin loaded video canvas snapshot (fails silently on cross-origin)
                        if (v.readyState >= 2 && v.videoWidth > 0 && v.videoHeight > 0) {
                            var c = (doc || document).createElement('canvas');
                            var targetW = 240;
                            var targetH = Math.max(90, Math.round((v.videoHeight / v.videoWidth) * targetW));
                            c.width = targetW;
                            c.height = targetH;
                            var ctx = c.getContext('2d');
                            if (ctx) {
                                ctx.drawImage(v, 0, 0, targetW, targetH);
                                var dataUrl = c.toDataURL('image/jpeg', 0.55);
                                if (dataUrl && dataUrl.length > 200 && dataUrl.length < 90000) {
                                    return dataUrl;
                                }
                            }
                        }
                    } catch (e) {}
                    return '';
                }

                function report(url, title, res, dur, origin, audioUrl, thumbUrl) {
                    try {
                        var clean = unescapeJsonUrl(url);
                        if (!clean || clean.indexOf('http') !== 0) return;
                        if (clean.indexOf('blob:') === 0 || clean.indexOf('data:') === 0) return;
                        if (clean.length > 2200) return;
                        var cleanThumb = thumbUrl ? (thumbUrl.indexOf('data:image/') === 0 ? thumbUrl : unescapeJsonUrl(thumbUrl)) : '';
                        var key = clean + '|' + (audioUrl || '') + '|' + (cleanThumb ? '1' : '0');
                        if (window.__svReported.has(key)) return;
                        window.__svReported.add(key);

                        if (window.StreamVaultBridge && window.StreamVaultBridge.onMediaFound) {
                            window.StreamVaultBridge.onMediaFound(
                                clean,
                                title || document.title || '',
                                res || '',
                                Math.floor(dur || 0),
                                origin || 'DOM',
                                unescapeJsonUrl(audioUrl || ''),
                                cleanThumb
                            );
                        }
                    } catch (e) {}
                }

                function extractFromJsonText(text, sourceLabel) {
                    try {
                        if (!text || text.length < 24 || text.length > 1800000) return;

                        // Try finding a cover/poster URL inside the same JSON payload
                        var jsonPoster = '';
                        var coverMatch = /["']?(?:poster|thumbnailUrl|thumbnail_url|origin_cover|dynamic_cover|cover|display_url|image_url)["']?\s*:\s*["'](https?:[^"'\\]*(?:\\.[^"'\\]*)*)["']/i.exec(text);
                        if (coverMatch && coverMatch[1]) {
                            jsonPoster = unescapeJsonUrl(coverMatch[1]);
                        }

                        // 1. Keyed social & player video fields (TikTok, IG, FB, X, JWPlayer, Plyr, VideoJS)
                        var keyedRegex = /["']?(?:playAddr|downloadAddr|play_addr|video_url|playable_url_quality_hd|playable_url|browser_native_hd_url|browser_native_sd_url|hls_url|dash_manifest|manifest_url|contentUrl|hd_src|sd_src|fallback_url|videoUrl|streamUrl|hlsUrl|mp4Url|file|src)["']?\s*:\s*["'](https?:[^"'\\]*(?:\\.[^"'\\]*)*)["']/gi;
                        var m;
                        var count = 0;
                        while ((m = keyedRegex.exec(text)) !== null && count < 14) {
                            var found = unescapeJsonUrl(m[1]);
                            if (found &&
                                !/\.(jpg|jpeg|png|webp|gif|svg|js|css|vtt|srt|ico|woff2?)(\?|#|$)/i.test(found) &&
                                (/\.(m3u8|mpd|mp4|webm|mkv|mov)(\?|#|$)/i.test(found) ||
                                    /hls|m3u8|mpd|video|play|stream|manifest|playlist|tos-|cdninstagram|fbcdn|twimg/i.test(found))
                            ) {
                                report(found, document.title, '', 0, sourceLabel, '', jsonPoster);
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
                                    report(igUrl, document.title, '', 0, 'IG JSON', '', jsonPoster);
                                    igCount++;
                                }
                            }
                        }

                        // 3. Direct embedded .m3u8 / .mpd / .mp4 URLs inside JSON or inline player scripts
                        var directRegex = /["'](https?:\\?\/\\?\/[^"'\s<>]{12,900}?(?:\.m3u8|\.mpd|\.mp4|\.webm)(?:[^"'\s<>]{0,400})?)["']/gi;
                        var dm;
                        var dCount = 0;
                        while ((dm = directRegex.exec(text)) !== null && dCount < 12) {
                            var dUrl = unescapeJsonUrl(dm[1]);
                            if (dUrl && dUrl.indexOf('mime=audio') < 0) {
                                report(dUrl, document.title, '', 0, sourceLabel, '', jsonPoster);
                                dCount++;
                            }
                        }
                    } catch (e) {}
                }

                function scanDocument(doc) {
                    try {
                        if (!doc) return;
                        var pagePoster = getPagePoster(doc);
                        if (pagePoster && doc === document && window.StreamVaultBridge && window.StreamVaultBridge.onPagePosterFound) {
                            window.StreamVaultBridge.onPagePosterFound(pagePoster);
                        }

                        var videos = doc.querySelectorAll('video');
                        videos.forEach(function(v) {
                            var res = (v.videoWidth && v.videoHeight) ? (v.videoWidth + 'x' + v.videoHeight) : '';
                            var dur = isFinite(v.duration) ? v.duration : 0;
                            var label = v.getAttribute('title') || v.getAttribute('aria-label') || doc.title || document.title;
                            var thumb = captureVideoThumb(v, doc);
                            if (v.currentSrc) report(v.currentSrc, label, res, dur, 'DOM <video>', '', thumb);
                            if (v.src) report(v.src, label, res, dur, 'DOM <video>', '', thumb);
                            var sources = v.querySelectorAll('source');
                            sources.forEach(function(s) {
                                if (s.src) report(s.src, label, res, dur, 'DOM <source>', '', thumb);
                            });
                        });

                        var metas = doc.querySelectorAll('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"], meta[name="twitter:player:stream"]');
                        metas.forEach(function(m) {
                            var c = m.getAttribute('content');
                            if (c) report(c, document.title, '', 0, 'Meta Video', '', pagePoster);
                        });

                        var anchors = doc.querySelectorAll('a[href]');
                        anchors.forEach(function(a) {
                            var href = a.href || '';
                            if (/\.(mp4|m3u8|mpd|webm|mkv|mov)(\?|#|$)/i.test(href)) {
                                var aImg = a.querySelector('img[src]');
                                var aThumb = aImg ? resolveAbsUrl(aImg.src, doc) : '';
                                report(href, a.textContent.trim() || document.title, '', 0, 'Link', '', aThumb);
                            }
                        });

                        // Scan hydration, JSON-LD, AND inline player config scripts (JWPlayer/VideoJS/HLS)
                        var scripts = doc.querySelectorAll('script');
                        for (var sIdx = 0; sIdx < scripts.length && sIdx < 60; sIdx++) {
                            var sc = scripts[sIdx];
                            var txt = sc.textContent || '';
                            if (txt.length < 30 || txt.length > 1500000) continue;
                            if (sc.id === '__UNIVERSAL_DATA_FOR_REHYDRATION__' ||
                                sc.id === 'SIGI_STATE' ||
                                sc.id === '__NEXT_DATA__' ||
                                sc.type === 'application/json' ||
                                sc.type === 'application/ld+json' ||
                                /m3u8|\.mp4|\.mpd|playAddr|video_versions|jwplayer|sources\s*[:=]/i.test(txt)
                            ) {
                                extractFromJsonText(txt, 'Script Data');
                            }
                        }
                    } catch (e) {}
                }

                window.__streamVaultScanDom = function() {
                    try {
                        scanDocument(document);

                        // Scan accessible same-origin iframes
                        var frames = document.querySelectorAll('iframe');
                        for (var f = 0; f < frames.length && f < 8; f++) {
                            try {
                                if (frames[f].contentDocument) {
                                    scanDocument(frames[f].contentDocument);
                                }
                            } catch (e) {}
                        }

                        // Scan PerformanceResourceTiming for missed media/playlist URLs
                        if (window.performance && performance.getEntriesByType) {
                            var entries = performance.getEntriesByType('resource') || [];
                            for (var i = Math.max(0, entries.length - 80); i < entries.length; i++) {
                                var name = entries[i].name || '';
                                if (/\.(m3u8|mpd|mp4|webm)(\?|#|$)|\/video\/tos\/|video\.twimg\.com|mime_type=video|master\.txt|playlist|chunklist/i.test(name)) {
                                    report(name, document.title, '', 0, 'Resource');
                                }
                            }
                        }
                    } catch (e) {}
                };

                // Hook fetch() requests AND inspect video / HLS / DASH / JSON responses
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
                                    } else if (ct.indexOf('application/json') >= 0 || ct.indexOf('text/javascript') >= 0 || ct.indexOf('text/plain') >= 0 || ct.indexOf('application/octet-stream') >= 0) {
                                        var clone = resp.clone();
                                        clone.text().then(function(txt) {
                                            if (!txt) return;
                                            var head = txt.trimStart();
                                            if (head.indexOf('#EXTM3U') === 0 || head.indexOf('<MPD') === 0) {
                                                report(finalUrl, document.title, '', 0, 'Fetch Playlist');
                                            } else if (ct.indexOf('json') >= 0 || ct.indexOf('javascript') >= 0) {
                                                extractFromJsonText(txt, 'API JSON');
                                            }
                                        }).catch(function() {});
                                    }
                                } catch (e) {}
                            }).catch(function() {});
                        } catch (e) {}
                        return promise;
                    };
                }

                // Hook XMLHttpRequest open() + load response inspection (catches hls.js / dash.js / disguised .txt playlists!)
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
                                    } else if ((!this.responseType || this.responseType === 'text') && this.responseText) {
                                        var head = this.responseText.trimStart();
                                        if (head.indexOf('#EXTM3U') === 0 || head.indexOf('<MPD') === 0) {
                                            report(respUrl, document.title, '', 0, 'XHR Playlist');
                                        } else if (ct.indexOf('application/json') >= 0 || ct.indexOf('text/plain') >= 0) {
                                            extractFromJsonText(this.responseText, 'XHR JSON');
                                        }
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
