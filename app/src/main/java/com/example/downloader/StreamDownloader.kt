package com.example.downloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.browser.MediaFormat
import com.example.browser.MediaSniffer
import com.example.data.local.DownloadEntity
import com.example.data.local.DownloadStatus
import com.example.data.repository.StreamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class StreamDownloader(
    private val context: Context,
    private val repository: StreamRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<Long, Job>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun getVideosDir(): File {
        return File(context.filesDir, "videos").apply {
            if (!exists()) mkdirs()
        }
    }

    fun startDownload(entity: DownloadEntity) {
        val job = scope.launch {
            try {
                if (entity.format == MediaFormat.HLS.label ||
                    entity.sourceUrl.contains(".m3u8", ignoreCase = true)
                ) {
                    downloadHlsStream(entity)
                } else {
                    downloadDirectFile(entity)
                }
            } catch (ce: CancellationException) {
                repository.updateDownloadProgress(
                    id = entity.id,
                    status = DownloadStatus.CANCELLED.name,
                    downloadedBytes = entity.downloadedBytes,
                    totalBytes = entity.totalBytes,
                    progressPercent = entity.progressPercent,
                    statusDetail = "Dibatalkan",
                    errorMessage = null
                )
            } catch (e: Exception) {
                repository.updateDownloadProgress(
                    id = entity.id,
                    status = DownloadStatus.FAILED.name,
                    downloadedBytes = 0L,
                    totalBytes = entity.totalBytes,
                    progressPercent = 0,
                    statusDetail = "Gagal mengunduh",
                    errorMessage = e.localizedMessage ?: "Kesalahan jaringan saat mengunduh"
                )
            } finally {
                activeJobs.remove(entity.id)
            }
        }
        activeJobs[entity.id] = job
    }

    fun cancelDownload(downloadId: Long) {
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)
        scope.launch {
            repository.updateDownloadProgress(
                id = downloadId,
                status = DownloadStatus.CANCELLED.name,
                downloadedBytes = 0L,
                totalBytes = 0L,
                progressPercent = 0,
                statusDetail = "Dibatalkan",
                errorMessage = null
            )
        }
    }

    private suspend fun downloadDirectFile(entity: DownloadEntity) {
        val targetFile = File(getVideosDir(), entity.fileName)
        val req = buildRequest(entity.sourceUrl, entity.referer, entity.userAgent, entity.cookie)

        repository.updateDownloadProgress(
            id = entity.id,
            status = DownloadStatus.DOWNLOADING.name,
            downloadedBytes = 0L,
            totalBytes = entity.totalBytes,
            progressPercent = 0,
            statusDetail = "Menghubungkan ke server..."
        )

        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: Gagal mengambil stream video")
            }

            val body = response.body ?: throw IllegalStateException("Respons stream kosong")
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: entity.totalBytes
            val mime = response.header("Content-Type")?.substringBefore(';')?.trim()
                ?.takeIf { it.startsWith("video/") } ?: entity.mimeType

            var downloaded = 0L
            var lastUpdateMs = 0L
            val buffer = ByteArray(64 * 1024)

            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    while (true) {
                        scope.coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateMs >= 250L) {
                            lastUpdateMs = now
                            val pct = if (totalBytes > 0L) {
                                ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 99)
                            } else {
                                0
                            }
                            repository.updateDownloadProgress(
                                id = entity.id,
                                status = DownloadStatus.DOWNLOADING.name,
                                downloadedBytes = downloaded,
                                totalBytes = totalBytes,
                                progressPercent = pct,
                                statusDetail = if (totalBytes > 0L) {
                                    "${MediaSniffer.formatBytes(downloaded)} / ${MediaSniffer.formatBytes(totalBytes)}"
                                } else {
                                    "${MediaSniffer.formatBytes(downloaded)} terunduh"
                                }
                            )
                        }
                    }
                    output.flush()
                }
            }

            val exportedUri = exportToDeviceGallery(targetFile, entity.fileName, mime)
            repository.markDownloadCompleted(
                id = entity.id,
                status = DownloadStatus.COMPLETED.name,
                localPath = targetFile.absolutePath,
                fileName = entity.fileName,
                mimeType = mime,
                totalBytes = targetFile.length(),
                statusDetail = if (exportedUri != null) "Tersimpan & diekspor ke Galeri" else "Tersimpan offline",
                mediaStoreUri = exportedUri?.toString()
            )
        }
    }

    private suspend fun downloadHlsStream(entity: DownloadEntity) {
        repository.updateDownloadProgress(
            id = entity.id,
            status = DownloadStatus.DOWNLOADING.name,
            downloadedBytes = 0L,
            totalBytes = 0L,
            progressPercent = 0,
            statusDetail = "Membaca playlist HLS (.m3u8)..."
        )

        var playlistUrl = entity.sourceUrl
        var playlistText = fetchText(playlistUrl, entity.referer, entity.userAgent, entity.cookie)

        // If master playlist, select the highest-bandwidth variant
        if (playlistText.contains("#EXT-X-STREAM-INF")) {
            val bestVariant = parseBestVariantUrl(playlistUrl, playlistText)
            if (bestVariant != null) {
                playlistUrl = bestVariant
                playlistText = fetchText(playlistUrl, entity.referer, entity.userAgent, entity.cookie)
            }
        }

        val parsed = parseMediaPlaylist(playlistUrl, playlistText)
        if (parsed.segments.isEmpty()) {
            throw IllegalStateException("Playlist HLS tidak berisi segmen video yang valid")
        }

        val isFmp4 = parsed.initMapUrl != null
        val finalFileName = if (isFmp4) {
            entity.fileName.substringBeforeLast('.') + ".mp4"
        } else {
            entity.fileName.substringBeforeLast('.') + ".ts"
        }
        val finalMime = if (isFmp4) "video/mp4" else "video/mp2t"
        val targetFile = File(getVideosDir(), finalFileName)

        var totalBytesWritten = 0L
        val totalSegments = parsed.segments.size
        var currentKeyBytes: ByteArray? = null
        var currentKeyUri: String? = null

        FileOutputStream(targetFile).use { output ->
            // Write fMP4 init segment if present
            if (parsed.initMapUrl != null) {
                val initBytes = fetchBytes(parsed.initMapUrl, entity.referer, entity.userAgent, entity.cookie)
                output.write(initBytes)
                totalBytesWritten += initBytes.size
            }

            parsed.segments.forEachIndexed { index, seg ->
                scope.coroutineContext.ensureActive()

                if (seg.keyMethod == "SAMPLE-AES") {
                    throw IllegalStateException("Stream ini menggunakan enkripsi DRM SAMPLE-AES")
                }

                if (seg.keyMethod == "AES-128" && seg.keyUri != null && seg.keyUri != currentKeyUri) {
                    currentKeyBytes = fetchBytes(seg.keyUri, entity.referer, entity.userAgent, entity.cookie)
                    currentKeyUri = seg.keyUri
                }

                val rawBytes = fetchBytes(seg.url, entity.referer, entity.userAgent, entity.cookie)
                val finalBytes = if (seg.keyMethod == "AES-128" && currentKeyBytes != null) {
                    decryptAes128(rawBytes, currentKeyBytes!!, seg.ivBytes ?: indexToIv(seg.sequenceIndex))
                } else {
                    rawBytes
                }

                output.write(finalBytes)
                totalBytesWritten += finalBytes.size

                val pct = (((index + 1) * 100) / totalSegments).coerceIn(1, 99)
                repository.updateDownloadProgress(
                    id = entity.id,
                    status = DownloadStatus.DOWNLOADING.name,
                    downloadedBytes = totalBytesWritten,
                    totalBytes = 0L,
                    progressPercent = pct,
                    statusDetail = "Segmen ${index + 1}/$totalSegments • ${MediaSniffer.formatBytes(totalBytesWritten)}"
                )
            }
            output.flush()
        }

        val exportedUri = exportToDeviceGallery(targetFile, finalFileName, finalMime)
        repository.markDownloadCompleted(
            id = entity.id,
            status = DownloadStatus.COMPLETED.name,
            localPath = targetFile.absolutePath,
            fileName = finalFileName,
            mimeType = finalMime,
            totalBytes = targetFile.length(),
            statusDetail = if (exportedUri != null) "HLS digabung & disimpan" else "HLS tersimpan offline",
            mediaStoreUri = exportedUri?.toString()
        )
    }

    fun exportExistingToGallery(entity: DownloadEntity): Uri? {
        val file = File(entity.localPath)
        if (!file.exists()) return null
        val uri = exportToDeviceGallery(file, entity.fileName, entity.mimeType)
        if (uri != null) {
            scope.launch {
                repository.updateMediaStoreUri(entity.id, uri.toString())
            }
        }
        return uri
    }

    private fun exportToDeviceGallery(file: File, fileName: String, mimeType: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val resolver = context.contentResolver
            val useVideoCollection = mimeType == "video/mp4" || mimeType == "video/webm"
            val collection = if (useVideoCollection) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val relativePath = if (useVideoCollection) {
                "${Environment.DIRECTORY_MOVIES}/StreamVault"
            } else {
                "${Environment.DIRECTORY_DOWNLOADS}/StreamVault"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val itemUri = resolver.insert(collection, values) ?: return@runCatching null
            resolver.openOutputStream(itemUri)?.use { out ->
                file.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            itemUri
        }.getOrNull()
    }

    fun shareSavedVideo(entity: DownloadEntity) {
        val file = File(entity.localPath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = entity.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, entity.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(shareIntent, "Bagikan Video").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun buildRequest(url: String, referer: String, userAgent: String, cookie: String): Request {
        val builder = Request.Builder().url(url)
        if (userAgent.isNotBlank()) builder.header("User-Agent", userAgent)
        if (referer.isNotBlank()) builder.header("Referer", referer)
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        return builder.build()
    }

    private fun fetchText(url: String, referer: String, userAgent: String, cookie: String): String {
        val req = buildRequest(url, referer, userAgent, cookie)
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} saat membaca playlist")
            return resp.body?.string().orEmpty()
        }
    }

    private fun fetchBytes(url: String, referer: String, userAgent: String, cookie: String): ByteArray {
        val req = buildRequest(url, referer, userAgent, cookie)
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} saat mengunduh segmen")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, relative: String): String {
        return runCatching { URL(URL(baseUrl), relative).toString() }.getOrElse { relative }
    }

    private fun parseBestVariantUrl(masterUrl: String, content: String): String? {
        val lines = content.lines().map { it.trim() }
        var bestBandwidth = -1L
        var bestUrl: String? = null
        var pendingBandwidth = 0L

        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val match = Regex("BANDWIDTH=(\\d+)").find(line)
                pendingBandwidth = match?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                val resolved = resolveRelativeUrl(masterUrl, line)
                if (pendingBandwidth >= bestBandwidth) {
                    bestBandwidth = pendingBandwidth
                    bestUrl = resolved
                }
                pendingBandwidth = 0L
            }
        }
        return bestUrl
    }

    private data class HlsSegment(
        val url: String,
        val sequenceIndex: Long,
        val keyMethod: String? = null,
        val keyUri: String? = null,
        val ivBytes: ByteArray? = null
    )

    private data class HlsPlaylist(
        val initMapUrl: String? = null,
        val segments: List<HlsSegment>
    )

    private fun parseMediaPlaylist(playlistUrl: String, content: String): HlsPlaylist {
        val lines = content.lines().map { it.trim() }
        var mediaSequence = 0L
        var currentKeyMethod: String? = null
        var currentKeyUri: String? = null
        var currentIv: ByteArray? = null
        var initMapUrl: String? = null
        val segments = mutableListOf<HlsSegment>()
        var expectSegment = false
        var seqOffset = 0L

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val uriMatch = Regex("URI=\"([^\"]+)\"").find(line)
                    val rel = uriMatch?.groupValues?.getOrNull(1)
                    if (rel != null) initMapUrl = resolveRelativeUrl(playlistUrl, rel)
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val methodMatch = Regex("METHOD=([^,]+)").find(line)
                    val uriMatch = Regex("URI=\"([^\"]+)\"").find(line)
                    val ivMatch = Regex("IV=0x([0-9a-fA-F]+)").find(line)
                    currentKeyMethod = methodMatch?.groupValues?.getOrNull(1)?.trim()
                    currentKeyUri = uriMatch?.groupValues?.getOrNull(1)?.let {
                        resolveRelativeUrl(playlistUrl, it)
                    }
                    currentIv = ivMatch?.groupValues?.getOrNull(1)?.let { hexToBytes(it) }
                }
                line.startsWith("#EXTINF:") -> {
                    expectSegment = true
                }
                line.isNotEmpty() && !line.startsWith("#") && expectSegment -> {
                    segments.add(
                        HlsSegment(
                            url = resolveRelativeUrl(playlistUrl, line),
                            sequenceIndex = mediaSequence + seqOffset,
                            keyMethod = currentKeyMethod,
                            keyUri = currentKeyUri,
                            ivBytes = currentIv
                        )
                    )
                    seqOffset++
                    expectSegment = false
                }
            }
        }
        return HlsPlaylist(initMapUrl = initMapUrl, segments = segments)
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun indexToIv(seq: Long): ByteArray {
        val iv = ByteArray(16)
        var v = seq
        for (i in 15 downTo 8) {
            iv[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return iv
    }

    private fun hexToBytes(hex: String): ByteArray {
        val padded = hex.padStart(32, '0').takeLast(32)
        return ByteArray(16) { i ->
            padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
