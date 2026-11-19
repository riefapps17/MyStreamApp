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
    val userAgent: String = "",
    val cookie: String = "",
    val sourceOrigin: String = "Network",
    val detectedAt: Long = System.currentTimeMillis()
)

object MediaSniffer {

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val ignoredExtensions = setOf(
        "js", "css", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico",
        "woff", "woff2", "ttf", "otf", "json", "xml", "vtt", "srt", "ass",
        "html", "htm", "php", "txt"
    )

    private val directVideoExtensions = mapOf(
        "mp4" to MediaFormat.MP4,
        "m4v" to MediaFormat.MP4,
        "webm" to MediaFormat.WEBM,
        "mkv" to MediaFormat.MKV,
        "mov" to MediaFormat.MOV,
        "3gp" to MediaFormat.MP4
    )

    fun classifyUrl(url: String, hintMime: String? = null): MediaFormat? {
        if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) {
            return null
        }

        val normalized = url.lowercase(Locale.US)
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val path = (uri.path ?: "").lowercase(Locale.US)
        val ext = path.substringAfterLast('.', "").substringBefore('?').substringBefore('#')

        // Skip obvious non-video assets and single HLS TS chunks (the .m3u8 playlist is captured instead)
        if (ext in ignoredExtensions || ext == "ts" || ext == "m4s") {
            return null
        }

        // Check mime hint first
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

        // Check extension
        if (ext == "m3u8" || normalized.contains(".m3u8?") || normalized.endsWith(".m3u8")) {
            return MediaFormat.HLS
        }
        if (ext == "mpd" || normalized.contains(".mpd?")) {
            return MediaFormat.DASH
        }
        directVideoExtensions[ext]?.let { return it }

        // Check streaming query parameters often used by CDNs without file extensions
        val hasVideoQuery = (normalized.contains("mime=video") ||
            normalized.contains("mime%3dvideo") ||
            normalized.contains("format=mp4") ||
            normalized.contains("type=mp4") ||
            (normalized.contains("/videoplayback") && normalized.contains("itag=")))
        if (hasVideoQuery && !normalized.contains("mime=audio")) {
            return MediaFormat.MP4
        }

        return null
    }

    fun deriveTitle(url: String, pageTitle: String, format: MediaFormat): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val lastSegment = uri?.lastPathSegment
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.trim()
            .orEmpty()

        val cleanSegment = lastSegment.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        if (cleanSegment.length in 4..60 && cleanSegment.contains('.')) {
            return cleanSegment
        }
        val cleanPageTitle = pageTitle
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(42)
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
        val ext = if (format == MediaFormat.HLS) "ts" else format.extension
        return "${base}_${System.currentTimeMillis() % 10000}.$ext"
    }

    suspend fun probeMediaMetadata(
        url: String,
        referer: String,
        userAgent: String,
        cookie: String
    ): Pair<Long, String?> = withContext(Dispatchers.IO) {
        runCatching {
            val reqBuilder = Request.Builder()
                .url(url)
                .head()
            if (userAgent.isNotBlank()) reqBuilder.header("User-Agent", userAgent)
            if (referer.isNotBlank()) reqBuilder.header("Referer", referer)
            if (cookie.isNotBlank()) reqBuilder.header("Cookie", cookie)

            probeClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use -1L to null
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val contentType = response.header("Content-Type")
                contentLength to contentType
            }
        }.getOrElse { -1L to null }
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
     * JavaScript injected into pages to uncover HTML5 <video>, <source>, OpenGraph og:video,
     * and dynamically loaded Fetch/XHR media requests even when download buttons are disabled.
     */
    const val MEDIA_SNIFFER_JS = """
        (function() {
            if (!window.__streamVaultBridgeInstalled) {
                window.__streamVaultBridgeInstalled = true;

                function report(url, title, res, dur, origin) {
                    try {
                        if (!url || typeof url !== 'string') return;
                        if (url.startsWith('blob:') || url.startsWith('data:')) return;
                        if (window.StreamVaultBridge && window.StreamVaultBridge.onMediaFound) {
                            window.StreamVaultBridge.onMediaFound(
                                url,
                                title || document.title || '',
                                res || '',
                                Math.floor(dur || 0),
                                origin || 'DOM'
                            );
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

                        var metas = document.querySelectorAll('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"]');
                        metas.forEach(function(m) {
                            var c = m.getAttribute('content');
                            if (c) report(c, document.title, '', 0, 'Meta og:video');
                        });

                        var anchors = document.querySelectorAll('a[href]');
                        anchors.forEach(function(a) {
                            var href = a.href || '';
                            if (/\.(mp4|m3u8|webm|mkv|mov)(\?|#|$)/i.test(href)) {
                                report(href, a.textContent.trim() || document.title, '', 0, 'Link');
                            }
                        });
                    } catch (e) {}
                };

                // Hook fetch() for dynamic manifest/video URLs
                if (window.fetch) {
                    var origFetch = window.fetch;
                    window.fetch = function(input) {
                        try {
                            var reqUrl = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                            if (/\.(m3u8|mp4|webm|mpd)(\?|#|$)|mime=video/i.test(reqUrl)) {
                                report(reqUrl, document.title, '', 0, 'Fetch Hook');
                            }
                        } catch (e) {}
                        return origFetch.apply(this, arguments);
                    };
                }

                // Hook XMLHttpRequest.open() for HLS.js / Video.js / Dash.js
                if (window.XMLHttpRequest) {
                    var origOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        try {
                            if (typeof url === 'string' && /\.(m3u8|mp4|webm|mpd)(\?|#|$)|mime=video/i.test(url)) {
                                var absUrl = new URL(url, window.location.href).href;
                                report(absUrl, document.title, '', 0, 'XHR Hook');
                            }
                        } catch (e) {}
                        return origOpen.apply(this, arguments);
                    };
                }

                // Observe dynamic <video> additions or src changes
                var observer = new MutationObserver(function() {
                    window.__streamVaultScanDom();
                });
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
