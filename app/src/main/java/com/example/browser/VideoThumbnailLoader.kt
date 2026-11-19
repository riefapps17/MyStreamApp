package com.example.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class VideoPreviewResult(
    val bitmap: Bitmap?,
    val durationSeconds: Int = 0,
    val resolution: String = ""
)

/**
 * Extracts and caches lightweight video frame thumbnails for detected streaming URLs
 * (direct MP4/WebM/MKV/MOV and HLS .m3u8) using session headers so users can visually
 * identify which video to save inside the DetectedMediaSheet popup.
 */
object VideoThumbnailLoader {

    private val previewCache = object : LruCache<String, VideoPreviewResult>(36) {}
    private val extractionSemaphore = Semaphore(permits = 2)

    fun getCached(url: String): VideoPreviewResult? = previewCache.get(url)

    fun decodeBase64DataUrl(dataUrl: String): Bitmap? {
        if (!dataUrl.startsWith("data:image/", ignoreCase = true)) return null
        val commaIndex = dataUrl.indexOf(',')
        if (commaIndex <= 0 || commaIndex >= dataUrl.lastIndex) return null
        return runCatching {
            val base64Part = dataUrl.substring(commaIndex + 1)
            val bytes = Base64.decode(base64Part, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    suspend fun loadPreview(media: DetectedMedia): VideoPreviewResult {
        previewCache.get(media.url)?.let { return it }

        // 1. Instant decode if JS captured a canvas data-URL snapshot
        if (media.thumbnailUrl.startsWith("data:image/", ignoreCase = true)) {
            val decoded = withContext(Dispatchers.Default) {
                decodeBase64DataUrl(media.thumbnailUrl)
            }
            if (decoded != null) {
                val res = VideoPreviewResult(
                    bitmap = decoded,
                    durationSeconds = media.durationSeconds,
                    resolution = media.resolution
                )
                previewCache.put(media.url, res)
                return res
            }
        }

        // 2. Skip remote frame extraction for DASH XML manifests (MediaMetadataRetriever doesn't parse .mpd)
        if (media.format == MediaFormat.DASH) {
            return VideoPreviewResult(null)
        }

        // 3. Extract a scaled keyframe via MediaMetadataRetriever with session headers & timeout
        return extractionSemaphore.withPermit {
            previewCache.get(media.url)?.let { return@withPermit it }

            val extracted = withTimeoutOrNull(4500L) {
                withContext(Dispatchers.IO) {
                    extractWithHeaders(media, includeOrigin = media.originHeader.isNotBlank())
                        ?: if (media.originHeader.isNotBlank()) {
                            extractWithHeaders(media, includeOrigin = false)
                        } else {
                            null
                        }
                }
            } ?: VideoPreviewResult(null)

            if (extracted.bitmap != null) {
                previewCache.put(media.url, extracted)
            }
            extracted
        }
    }

    private fun extractWithHeaders(
        media: DetectedMedia,
        includeOrigin: Boolean
    ): VideoPreviewResult? {
        val headers = HashMap<String, String>()
        if (media.userAgent.isNotBlank()) headers["User-Agent"] = media.userAgent
        if (media.referer.isNotBlank()) headers["Referer"] = media.referer
        if (includeOrigin && media.originHeader.isNotBlank()) headers["Origin"] = media.originHeader
        if (media.cookie.isNotBlank()) headers["Cookie"] = media.cookie

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(media.url, headers)

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0

            val durationSec = if (durationMs > 0L) (durationMs / 1000L).toInt() else 0
            val resolution = if (width > 0 && height > 0) "${width}x${height}" else ""

            val targetTimeUs = when {
                durationMs > 4_000L -> 2_000_000L
                durationMs > 1_500L -> 1_000_000L
                else -> 0L
            }

            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    targetTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    320,
                    180
                ) ?: retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    320,
                    180
                )
            } else {
                retriever.getFrameAtTime(
                    targetTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: retriever.frameAtTime

            if (frame != null) {
                VideoPreviewResult(
                    bitmap = frame,
                    durationSeconds = durationSec,
                    resolution = resolution
                )
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
