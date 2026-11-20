package com.example.downloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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

    private fun getTempDir(): File {
        return File(context.cacheDir, "stream_mux").apply {
            if (!exists()) mkdirs()
        }
    }

    fun startDownload(entity: DownloadEntity) {
        val job = scope.launch {
            try {
                when {
                    entity.format == MediaFormat.DASH.label ||
                        entity.sourceUrl.contains(".mpd", ignoreCase = true) -> {
                        downloadDashStream(entity)
                    }
                    entity.format == MediaFormat.HLS.label ||
                        entity.sourceUrl.contains(".m3u8", ignoreCase = true) -> {
                        downloadHlsStream(entity)
                    }
                    entity.audioUrl.isNotBlank() -> {
                        downloadSplitVideoAndAudio(entity)
                    }
                    else -> {
                        downloadDirectFile(entity)
                    }
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

        repository.updateDownloadProgress(
            id = entity.id,
            status = DownloadStatus.DOWNLOADING.name,
            downloadedBytes = 0L,
            totalBytes = entity.totalBytes,
            progressPercent = 0,
            statusDetail = "Menghubungkan ke server..."
        )

        executeWithHeaderFallback(entity.sourceUrl, entity).use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: Server menolak unduhan (token sesi mungkin kedaluwarsa)")
            }

            val rawContentType = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
            val body = response.body ?: throw IllegalStateException("Respons stream kosong")
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: entity.totalBytes

            // Peek first 512 bytes to auto-detect disguised HLS/DASH playlists or HTML error pages
            val rawStream = body.byteStream()
            val headBuf = ByteArray(512)
            var headRead = 0
            while (headRead < headBuf.size) {
                val r = rawStream.read(headBuf, headRead, headBuf.size - headRead)
                if (r <= 0) break
                headRead += r
            }
            val headSlice = headBuf.copyOf(headRead)
            val magicMime = MediaSniffer.detectMimeFromMagicBytes(headSlice)

            if (rawContentType.contains("mpegurl", ignoreCase = true) ||
                magicMime == "application/vnd.apple.mpegurl"
            ) {
                response.close()
                downloadHlsStream(entity.copy(format = MediaFormat.HLS.label))
                return
            }

            if (rawContentType.contains("dash+xml", ignoreCase = true) ||
                magicMime == "application/dash+xml"
            ) {
                response.close()
                downloadDashStream(entity.copy(format = MediaFormat.DASH.label))
                return
            }

            val headAscii = runCatching {
                String(headSlice, Charsets.UTF_8).trimStart()
            }.getOrDefault("")
            if (rawContentType.startsWith("text/html", ignoreCase = true) ||
                headAscii.startsWith("<!DOCTYPE", ignoreCase = true) ||
                headAscii.startsWith("<html", ignoreCase = true)
            ) {
                throw IllegalStateException("Server mengembalikan halaman HTML, bukan file video langsung")
            }

            val mime = magicMime
                ?: rawContentType.takeIf { it.startsWith("video/") }
                ?: entity.mimeType

            val combinedStream = SequenceInputStream(ByteArrayInputStream(headSlice), rawStream)
            streamBodyToFile(
                entity = entity,
                input = combinedStream,
                targetFile = targetFile,
                totalBytes = totalBytes,
                progressMin = 0,
                progressMax = 99,
                prefixLabel = ""
            )

            finalizeDownload(
                entity = entity,
                targetFile = targetFile,
                fileName = entity.fileName,
                mimeType = mime,
                exportedDetail = "Tersimpan & diekspor ke Galeri",
                offlineDetail = "Tersimpan offline"
            )
        }
    }

    private suspend fun downloadSplitVideoAndAudio(entity: DownloadEntity) {
        val tempVideo = File(getTempDir(), "vid_${entity.id}.mp4")
        val tempAudio = File(getTempDir(), "aud_${entity.id}.m4a")
        val finalFileName = entity.fileName.substringBeforeLast('.') + ".mp4"
        val targetFile = File(getVideosDir(), finalFileName)

        try {
            repository.updateDownloadProgress(
                id = entity.id,
                status = DownloadStatus.DOWNLOADING.name,
                downloadedBytes = 0L,
                totalBytes = entity.totalBytes,
                progressPercent = 2,
                statusDetail = "Mengunduh trek video..."
            )
            downloadUrlToFile(entity.sourceUrl, entity, tempVideo, progressMin = 2, progressMax = 65, prefixLabel = "Video: ")

            repository.updateDownloadProgress(
                id = entity.id,
                status = DownloadStatus.DOWNLOADING.name,
                downloadedBytes = tempVideo.length(),
                totalBytes = 0L,
                progressPercent = 68,
                statusDetail = "Mengunduh trek audio..."
            )
            val audioOk = runCatching {
                downloadUrlToFile(entity.audioUrl, entity, tempAudio, progressMin = 68, progressMax = 88, prefixLabel = "Audio: ")
                true
            }.getOrDefault(false)

            repository.updateDownloadProgress(
                id = entity.id,
                status = DownloadStatus.DOWNLOADING.name,
                downloadedBytes = tempVideo.length() + if (audioOk) tempAudio.length() else 0L,
                totalBytes = 0L,
                progressPercent = 92,
                statusDetail = "Menggabungkan video & audio (MediaMuxer)..."
            )

            val muxed = MediaMuxerHelper.muxVideoAndAudio(
                videoFile = tempVideo,
                audioFile = if (audioOk) tempAudio else null,
                outputFile = targetFile
            )
            if (!muxed) {
                tempVideo.copyTo(targetFile, overwrite = true)
            }

            finalizeDownload(
                entity = entity,
                targetFile = targetFile,
                fileName = finalFileName,
                mimeType = "video/mp4",
                exportedDetail = if (muxed && audioOk) "Video + Audio digabung & diekspor" else "Tersimpan & diekspor ke Galeri",
                offlineDetail = if (muxed && audioOk) "Video + Audio tersimpan offline" else "Tersimpan offline"
            )
        } finally {
            runCatching { tempVideo.delete() }
            runCatching { tempAudio.delete() }
        }
    }

    private suspend fun downloadDashStream(entity: DownloadEntity) {
        repository.updateDownloadProgress(
            id = entity.id,
            status = DownloadStatus.DOWNLOADING.name,
            downloadedBytes = 0L,
            totalBytes = 0L,
            progressPercent = 0,
            statusDetail = "Membaca manifest DASH (.mpd)..."
        )

        val xml = fetchTextWithRetry(entity.sourceUrl, entity)
        val plan = DashManifestParser.parse(entity.sourceUrl, xml)

        if (plan.isDrmProtected) {
            throw IllegalStateException("Stream DASH ini dilindungi DRM (Widevine/CENC) sehingga tidak dapat disimpan")
        }

        val videoTrack = plan.videoTrack
            ?: throw IllegalStateException("Manifest DASH tidak memiliki trek video yang dapat diunduh")

        val tempVideo = File(getTempDir(), "dash_vid_${entity.id}.mp4")
        val tempAudio = File(getTempDir(), "dash_aud_${entity.id}.m4a")
        val finalFileName = entity.fileName.substringBeforeLast('.') + ".mp4"
        val targetFile = File(getVideosDir(), finalFileName)

        try {
            downloadDashTrack(
                entity = entity,
                track = videoTrack,
                outFile = tempVideo,
                progressMin = 5,
                progressMax = if (plan.audioTrack != null) 65 else 92,
                label = "Video DASH"
            )

            val hasAudio = if (plan.audioTrack != null) {
                runCatching {
                    downloadDashTrack(
                        entity = entity,
                        track = plan.audioTrack,
                        outFile = tempAudio,
                        progressMin = 66,
                        progressMax = 90,
                        label = "Audio DASH"
                    )
                    true
                }.getOrDefault(false)
            } else {
                false
            }

            repository.updateDownloadProgress(
                id = entity.id,
                status = DownloadStatus.DOWNLOADING.name,
                downloadedBytes = tempVideo.length() + if (hasAudio) tempAudio.length() else 0L,
                totalBytes = 0L,
                progressPercent = 94,
                statusDetail = "Menggabungkan trek DASH (MediaMuxer)..."
            )

            val muxed = MediaMuxerHelper.muxVideoAndAudio(
                videoFile = tempVideo,
                audioFile = if (hasAudio) tempAudio else null,
                outputFile = targetFile
            )
            if (!muxed) {
                tempVideo.copyTo(targetFile, overwrite = true)
            }

            finalizeDownload(
                entity = entity,
                targetFile = targetFile,
                fileName = finalFileName,
                mimeType = "video/mp4",
                exportedDetail = if (muxed && hasAudio) "DASH Video+Audio digabung & disimpan" else "DASH tersimpan & diekspor",
                offlineDetail = if (muxed && hasAudio) "DASH Video+Audio tersimpan offline" else "DASH tersimpan offline"
            )
        } finally {
            runCatching { tempVideo.delete() }
            runCatching { tempAudio.delete() }
        }
    }

    private suspend fun downloadDashTrack(
        entity: DownloadEntity,
        track: DashTrackPlan,
        outFile: File,
        progressMin: Int,
        progressMax: Int,
        label: String
    ) {
        if (!track.singleBaseUrl.isNullOrBlank()) {
            downloadUrlToFile(
                url = track.singleBaseUrl,
                entity = entity,
                targetFile = outFile,
                progressMin = progressMin,
                progressMax = progressMax,
                prefixLabel = "$label: "
            )
            return
        }

        if (track.segmentUrls.isEmpty()) {
            throw IllegalStateException("$label tidak berisi segmen")
        }

        val totalSegs = track.segmentUrls.size
        val completedCount = AtomicInteger(0)
        val totalBytes = AtomicLong(0L)
        val batchDir = File(getTempDir(), "dash_${entity.id}_${System.nanoTime()}").apply { mkdirs() }

        try {
            val initBytes = if (!track.initUrl.isNullOrBlank()) {
                fetchBytesWithRetry(track.initUrl, entity).also { totalBytes.addAndGet(it.size.toLong()) }
            } else {
                null
            }

            val sem = Semaphore(4)
            val segFiles = Array<File?>(totalSegs) { null }

            coroutineScope {
                track.segmentUrls.mapIndexed { idx, segUrl ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            ensureActive()
                            val bytes = fetchBytesWithRetry(segUrl, entity)
                            val f = File(batchDir, "s_$idx.bin")
                            f.writeBytes(bytes)
                            segFiles[idx] = f

                            val done = completedCount.incrementAndGet()
                            val bytesSoFar = totalBytes.addAndGet(bytes.size.toLong())
                            val fraction = done.toFloat() / totalSegs.toFloat()
                            val pct = (progressMin + (fraction * (progressMax - progressMin)).toInt())
                                .coerceIn(progressMin, progressMax)
                            repository.updateDownloadProgress(
                                id = entity.id,
                                status = DownloadStatus.DOWNLOADING.name,
                                downloadedBytes = bytesSoFar,
                                totalBytes = 0L,
                                progressPercent = pct,
                                statusDetail = "$label $done/$totalSegs • ${MediaSniffer.formatBytes(bytesSoFar)}"
                            )
                        }
                    }
                }.awaitAll()
            }

            FileOutputStream(outFile).use { output ->
                if (initBytes != null) output.write(initBytes)
                for (f in segFiles) {
                    if (f != null && f.exists()) {
                        f.inputStream().use { it.copyTo(output) }
                    }
                }
                output.flush()
            }
        } finally {
            runCatching { batchDir.deleteRecursively() }
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
        var playlistText = fetchTextWithRetry(playlistUrl, entity)
        validateHlsPlaylistText(playlistText)

        var separateAudioPlaylistUrl: String? = null

        // Resolve nested master playlists (up to 3 hops) and select best video + audio rendition
        var hops = 0
        while (playlistText.contains("#EXT-X-STREAM-INF") && hops < 3) {
            val masterChoice = parseMasterPlaylist(playlistUrl, playlistText)
            if (separateAudioPlaylistUrl.isNullOrBlank() && !masterChoice.audioRenditionUrl.isNullOrBlank()) {
                separateAudioPlaylistUrl = masterChoice.audioRenditionUrl
            }
            val nextUrl = masterChoice.videoVariantUrl ?: break
            if (nextUrl == playlistUrl) break
            playlistUrl = nextUrl
            playlistText = fetchTextWithRetry(playlistUrl, entity)
            validateHlsPlaylistText(playlistText)
            hops++
        }

        val parsed = parseMediaPlaylist(playlistUrl, playlistText)
        if (parsed.segments.isEmpty()) {
            throw IllegalStateException("Playlist HLS tidak berisi segmen video yang valid (mungkin tautan sudah kedaluwarsa)")
        }

        val isFmp4 = parsed.initMapUrl != null
        val mp4FileName = entity.fileName.substringBeforeLast('.') + ".mp4"
        val tsFileName = entity.fileName.substringBeforeLast('.') + ".ts"
        val mp4TargetFile = File(getVideosDir(), mp4FileName)
        val tsTargetFile = File(getVideosDir(), tsFileName)

        // Separate audio rendition (supports BOTH fMP4 and MPEG-TS HLS playlists)
        if (!separateAudioPlaylistUrl.isNullOrBlank()) {
            val tempVideo = File(getTempDir(), "hls_vid_${entity.id}.${if (isFmp4) "mp4" else "ts"}")
            val tempAudio = File(getTempDir(), "hls_aud_${entity.id}.m4a")
            try {
                downloadHlsMediaSegments(
                    entity = entity,
                    parsed = parsed,
                    outFile = tempVideo,
                    isFmp4 = isFmp4,
                    progressMin = 2,
                    progressMax = 68,
                    label = "Video"
                )
                val audioOk = runCatching {
                    val audioText = fetchTextWithRetry(separateAudioPlaylistUrl, entity)
                    validateHlsPlaylistText(audioText)
                    val audioParsed = parseMediaPlaylist(separateAudioPlaylistUrl, audioText)
                    if (audioParsed.segments.isNotEmpty()) {
                        val audioIsFmp4 = audioParsed.initMapUrl != null
                        downloadHlsMediaSegments(
                            entity = entity,
                            parsed = audioParsed,
                            outFile = tempAudio,
                            isFmp4 = audioIsFmp4,
                            progressMin = 70,
                            progressMax = 90,
                            label = "Audio"
                        )
                        tempAudio.exists() && tempAudio.length() > 0L
                    } else {
                        false
                    }
                }.getOrDefault(false)

                repository.updateDownloadProgress(
                    id = entity.id,
                    status = DownloadStatus.DOWNLOADING.name,
                    downloadedBytes = tempVideo.length() + if (audioOk) tempAudio.length() else 0L,
                    totalBytes = 0L,
                    progressPercent = 94,
                    statusDetail = "Menggabungkan HLS Video & Audio..."
                )
                val muxed = MediaMuxerHelper.muxVideoAndAudio(
                    videoFile = tempVideo,
                    audioFile = if (audioOk) tempAudio else null,
                    outputFile = mp4TargetFile
                )
                if (muxed) {
                    finalizeDownload(
                        entity = entity,
                        targetFile = mp4TargetFile,
                        fileName = mp4FileName,
                        mimeType = "video/mp4",
                        exportedDetail = if (audioOk) "HLS Video+Audio tersimpan di Galeri" else "HLS MP4 tersimpan di Galeri",
                        offlineDetail = if (audioOk) "HLS Video+Audio tersimpan offline" else "HLS MP4 tersimpan offline"
                    )
                } else if (!isFmp4 && MediaMuxerHelper.remuxToMp4(tempVideo, mp4TargetFile)) {
                    finalizeDownload(
                        entity = entity,
                        targetFile = mp4TargetFile,
                        fileName = mp4FileName,
                        mimeType = "video/mp4",
                        exportedDetail = "HLS MP4 tersimpan di Galeri",
                        offlineDetail = "HLS MP4 tersimpan offline"
                    )
                } else {
                    val fallbackFile = if (isFmp4) mp4TargetFile else tsTargetFile
                    val fallbackName = if (isFmp4) mp4FileName else tsFileName
                    val fallbackMime = if (isFmp4) "video/mp4" else "video/mp2t"
                    tempVideo.copyTo(fallbackFile, overwrite = true)
                    finalizeDownload(
                        entity = entity,
                        targetFile = fallbackFile,
                        fileName = fallbackName,
                        mimeType = fallbackMime,
                        exportedDetail = "HLS tersimpan di Galeri/Unduhan",
                        offlineDetail = "HLS tersimpan offline"
                    )
                }
            } finally {
                runCatching { tempVideo.delete() }
                runCatching { tempAudio.delete() }
            }
            return
        }

        if (!isFmp4) {
            val tempTs = File(getTempDir(), "hls_raw_${entity.id}.ts")
            try {
                downloadHlsMediaSegments(entity, parsed, tempTs, isFmp4 = false, progressMin = 1, progressMax = 92, label = "Segmen")

                repository.updateDownloadProgress(
                    id = entity.id,
                    status = DownloadStatus.DOWNLOADING.name,
                    downloadedBytes = tempTs.length(),
                    totalBytes = tempTs.length(),
                    progressPercent = 95,
                    statusDetail = "Mengemas ke MP4 agar muncul di Galeri..."
                )

                val remuxed = MediaMuxerHelper.remuxToMp4(tempTs, mp4TargetFile)
                if (remuxed) {
                    finalizeDownload(
                        entity = entity,
                        targetFile = mp4TargetFile,
                        fileName = mp4FileName,
                        mimeType = "video/mp4",
                        exportedDetail = "HLS MP4 tersimpan di Galeri",
                        offlineDetail = "HLS MP4 tersimpan offline"
                    )
                } else {
                    tempTs.copyTo(tsTargetFile, overwrite = true)
                    finalizeDownload(
                        entity = entity,
                        targetFile = tsTargetFile,
                        fileName = tsFileName,
                        mimeType = "video/mp2t",
                        exportedDetail = "HLS tersimpan di Galeri/Unduhan",
                        offlineDetail = "HLS tersimpan offline"
                    )
                }
            } finally {
                runCatching { tempTs.delete() }
            }
            return
        }

        downloadHlsMediaSegments(entity, parsed, mp4TargetFile, isFmp4 = true, progressMin = 1, progressMax = 99, label = "Segmen")

        finalizeDownload(
            entity = entity,
            targetFile = mp4TargetFile,
            fileName = mp4FileName,
            mimeType = "video/mp4",
            exportedDetail = "HLS tersimpan di Galeri",
            offlineDetail = "HLS tersimpan offline"
        )
    }

    private fun validateHlsPlaylistText(text: String) {
        val trimmed = text.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        if (trimmed.isEmpty()) {
            throw IllegalStateException("Playlist HLS kosong dari server")
        }
        val lower = trimmed.take(600).lowercase()
        if (lower.startsWith("<!doctype") ||
            lower.startsWith("<html") ||
            lower.contains("cf-browser-verification") ||
            lower.contains("just a moment...") ||
            lower.contains("cloudflare") && !lower.contains("#extm3u")
        ) {
            throw IllegalStateException("Server mengembalikan halaman proteksi/HTML (Cloudflare atau token sesi kedaluwarsa), bukan playlist HLS")
        }
        if (!trimmed.startsWith("#EXTM3U", ignoreCase = true) &&
            !trimmed.contains("#EXTINF:", ignoreCase = true) &&
            !trimmed.contains("#EXT-X-STREAM-INF", ignoreCase = true)
        ) {
            throw IllegalStateException("Respons server bukan format playlist HLS (#EXTM3U) yang valid")
        }
    }

    private suspend fun downloadHlsMediaSegments(
        entity: DownloadEntity,
        parsed: HlsPlaylist,
        outFile: File,
        isFmp4: Boolean,
        progressMin: Int,
        progressMax: Int,
        label: String
    ) {
        val totalSegments = parsed.segments.size
        val keyCache = ConcurrentHashMap<String, ByteArray>()
        val completedCount = AtomicInteger(0)
        val totalBytes = AtomicLong(0L)
        val batchDir = File(getTempDir(), "hls_${entity.id}_${System.nanoTime()}").apply { mkdirs() }

        // Pre-validate DRM & pre-fetch unique AES-128 keys before launching parallel segment jobs
        val uniqueKeyUris = linkedSetOf<String>()
        for (seg in parsed.segments) {
            val method = seg.keyMethod.orEmpty()
            if (method.equals("SAMPLE-AES", ignoreCase = true) ||
                method.equals("SAMPLE-AES-CTR", ignoreCase = true) ||
                method.equals("SAMPLE-AES-CENC", ignoreCase = true) ||
                seg.isDrmKeyFormat
            ) {
                throw IllegalStateException("Stream HLS ini dilindungi DRM ($method) sehingga tidak dapat disimpan")
            }
            if (method.equals("AES-128", ignoreCase = true) && !seg.keyUri.isNullOrBlank()) {
                uniqueKeyUris.add(seg.keyUri)
            }
        }
        for (keyUri in uniqueKeyUris) {
            val keyBytes = if (keyUri.startsWith("data:", ignoreCase = true)) {
                decodeDataUriKey(keyUri)
                    ?: throw IllegalStateException("Kunci dekripsi AES-128 inline tidak valid")
            } else {
                fetchBytesWithRetry(keyUri, entity)
            }
            if (keyBytes.size != 16) {
                throw IllegalStateException("Kunci dekripsi AES-128 tidak valid (${keyBytes.size} byte — sesi mungkin kedaluwarsa)")
            }
            keyCache[keyUri] = keyBytes
        }

        suspend fun fetchAndProcessSegment(index: Int, seg: HlsSegment): File {
            val rawBytes = fetchBytesWithRetry(seg.url, entity, seg.byteRangeHeader)
            if (rawBytes.isEmpty()) {
                throw IllegalStateException("Segmen #$index kosong")
            }
            val decrypted = if (seg.keyMethod.equals("AES-128", ignoreCase = true) &&
                !seg.keyUri.isNullOrBlank()
            ) {
                val key = keyCache[seg.keyUri]
                if (key != null) {
                    decryptAes128(rawBytes, key, seg.ivBytes ?: indexToIv(seg.sequenceIndex))
                } else {
                    rawBytes
                }
            } else {
                rawBytes
            }
            val finalBytes = if (!isFmp4) stripCloakedTsPrefix(decrypted) else decrypted
            val f = File(batchDir, "seg_$index.bin")
            f.writeBytes(finalBytes)

            val done = completedCount.incrementAndGet()
            val bytesSoFar = totalBytes.addAndGet(finalBytes.size.toLong())
            val fraction = done.toFloat() / totalSegments.toFloat()
            val pct = (progressMin + (fraction * (progressMax - progressMin)).toInt())
                .coerceIn(progressMin, progressMax)
            repository.updateDownloadProgress(
                id = entity.id,
                status = DownloadStatus.DOWNLOADING.name,
                downloadedBytes = bytesSoFar,
                totalBytes = 0L,
                progressPercent = pct,
                statusDetail = "$label $done/$totalSegments • ${MediaSniffer.formatBytes(bytesSoFar)}"
            )
            return f
        }

        try {
            val initBytes = if (parsed.initMapUrl != null) {
                fetchBytesWithRetry(parsed.initMapUrl, entity, parsed.initMapByteRange).also {
                    totalBytes.addAndGet(it.size.toLong())
                }
            } else {
                null
            }

            val sem = Semaphore(4)
            val segFiles = Array<File?>(totalSegments) { null }
            val failedIndices = ConcurrentHashMap<Int, Throwable>()

            coroutineScope {
                parsed.segments.mapIndexed { index, seg ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            ensureActive()
                            try {
                                segFiles[index] = fetchAndProcessSegment(index, seg)
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (e: Throwable) {
                                failedIndices[index] = e
                            }
                        }
                    }
                }.awaitAll()
            }

            // Sequential calm retry pass for segments that hit CDN burst rate-limits (429/503/timeout)
            var lastSegmentError: Throwable? = failedIndices.values.firstOrNull()
            if (failedIndices.isNotEmpty()) {
                val toRetry = failedIndices.keys.sorted()
                failedIndices.clear()
                for (index in toRetry) {
                    scope.coroutineContext.ensureActive()
                    delay(450L)
                    try {
                        segFiles[index] = fetchAndProcessSegment(index, parsed.segments[index])
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Throwable) {
                        failedIndices[index] = e
                        lastSegmentError = e
                    }
                }
            }

            val succeededCount = segFiles.count { it != null && it.exists() && it.length() > 0L }
            val maxAllowedFailures = if (totalSegments <= 5) 0 else maxOf(2, totalSegments / 10)
            if (succeededCount == 0 || failedIndices.size > maxAllowedFailures) {
                val reason = lastSegmentError?.localizedMessage ?: "Gagal mengunduh segmen video"
                throw IllegalStateException("Gagal mengunduh ${failedIndices.size}/$totalSegments segmen HLS: $reason")
            }

            FileOutputStream(outFile).use { output ->
                if (initBytes != null) output.write(initBytes)
                for (f in segFiles) {
                    if (f != null && f.exists()) {
                        f.inputStream().use { it.copyTo(output) }
                    }
                }
                output.flush()
            }

            if (!outFile.exists() || outFile.length() <= 0L) {
                throw IllegalStateException("File hasil penggabungan HLS kosong")
            }
        } finally {
            runCatching { batchDir.deleteRecursively() }
        }
    }

    private suspend fun downloadUrlToFile(
        url: String,
        entity: DownloadEntity,
        targetFile: File,
        progressMin: Int,
        progressMax: Int,
        prefixLabel: String
    ) {
        executeWithHeaderFallback(url, entity).use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: Gagal mengunduh trek media")
            }
            val body = response.body ?: throw IllegalStateException("Respons trek media kosong")
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: 0L
            streamBodyToFile(
                entity = entity,
                input = body.byteStream(),
                targetFile = targetFile,
                totalBytes = totalBytes,
                progressMin = progressMin,
                progressMax = progressMax,
                prefixLabel = prefixLabel
            )
        }
    }

    private suspend fun streamBodyToFile(
        entity: DownloadEntity,
        input: InputStream,
        targetFile: File,
        totalBytes: Long,
        progressMin: Int,
        progressMax: Int,
        prefixLabel: String
    ) {
        var downloaded = 0L
        var lastUpdateMs = 0L
        val buffer = ByteArray(64 * 1024)

        input.use { stream ->
            FileOutputStream(targetFile).use { output ->
                while (true) {
                    scope.coroutineContext.ensureActive()
                    val read = stream.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateMs >= 250L) {
                        lastUpdateMs = now
                        val pct = if (totalBytes > 0L) {
                            val frac = downloaded.toFloat() / totalBytes.toFloat()
                            (progressMin + (frac * (progressMax - progressMin)).toInt())
                                .coerceIn(progressMin, progressMax)
                        } else {
                            progressMin
                        }
                        repository.updateDownloadProgress(
                            id = entity.id,
                            status = DownloadStatus.DOWNLOADING.name,
                            downloadedBytes = downloaded,
                            totalBytes = totalBytes,
                            progressPercent = pct,
                            statusDetail = if (totalBytes > 0L) {
                                "$prefixLabel${MediaSniffer.formatBytes(downloaded)} / ${MediaSniffer.formatBytes(totalBytes)}"
                            } else {
                                "$prefixLabel${MediaSniffer.formatBytes(downloaded)} terunduh"
                            }
                        )
                    }
                }
                output.flush()
            }
        }
    }

    private suspend fun finalizeDownload(
        entity: DownloadEntity,
        targetFile: File,
        fileName: String,
        mimeType: String,
        exportedDetail: String,
        offlineDetail: String
    ) {
        val exportedUri = exportToDeviceGallery(targetFile, fileName, mimeType)
        repository.markDownloadCompleted(
            id = entity.id,
            status = DownloadStatus.COMPLETED.name,
            localPath = targetFile.absolutePath,
            fileName = fileName,
            mimeType = mimeType,
            totalBytes = targetFile.length(),
            statusDetail = if (exportedUri != null) exportedDetail else offlineDetail,
            mediaStoreUri = exportedUri?.toString()
        )
    }

    fun exportExistingToGallery(entity: DownloadEntity): Uri? {
        val file = File(entity.localPath)
        if (!file.exists()) return null

        var exportFile = file
        var exportFileName = entity.fileName
        var exportMime = entity.mimeType
        var tempRemux: File? = null

        // If an older HLS .ts video is being exported manually, remux to .mp4 so Gallery displays it
        if (exportMime == "video/mp2t" || exportFileName.endsWith(".ts", ignoreCase = true)) {
            val candidate = File(getTempDir(), "export_${entity.id}.mp4")
            if (MediaMuxerHelper.remuxToMp4(file, candidate)) {
                tempRemux = candidate
                exportFile = candidate
                exportFileName = exportFileName.substringBeforeLast('.') + ".mp4"
                exportMime = "video/mp4"
            }
        }

        return try {
            val uri = exportToDeviceGallery(exportFile, exportFileName, exportMime)
            if (uri != null) {
                scope.launch {
                    repository.updateMediaStoreUri(entity.id, uri.toString())
                }
            }
            uri
        } finally {
            runCatching { tempRemux?.delete() }
        }
    }

    private fun exportToDeviceGallery(file: File, fileName: String, mimeType: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val normalizedMime = when {
            mimeType.startsWith("video/", ignoreCase = true) -> mimeType.lowercase()
            fileName.endsWith(".webm", ignoreCase = true) -> "video/webm"
            fileName.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
            fileName.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
            fileName.endsWith(".ts", ignoreCase = true) -> "video/mp2t"
            else -> "video/mp4"
        }

        // Primary: insert directly into MediaStore.Video.Media (Movies/ArfStream) with DATE_TAKEN
        // so Android 10-16 Gallery / Google Photos indexes and displays it immediately at the top.
        val galleryUri = insertIntoMediaStore(
            file = file,
            fileName = fileName,
            mimeType = normalizedMime,
            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            relativePath = "${Environment.DIRECTORY_MOVIES}/ArfStream",
            includeVideoTimestamps = true
        )
        if (galleryUri != null) return galleryUri

        // Fallback for rare video containers rejected by MediaStore.Video.Media
        return insertIntoMediaStore(
            file = file,
            fileName = fileName,
            mimeType = normalizedMime,
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            relativePath = "${Environment.DIRECTORY_DOWNLOADS}/ArfStream",
            includeVideoTimestamps = false
        )
    }

    private fun insertIntoMediaStore(
        file: File,
        fileName: String,
        mimeType: String,
        collection: Uri,
        relativePath: String,
        includeVideoTimestamps: Boolean
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        var insertedUri: Uri? = null
        return runCatching {
            val nowMs = System.currentTimeMillis()
            val nowSec = nowMs / 1000L
            val title = fileName.substringBeforeLast('.').ifBlank { "ArfStream_Video" }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.TITLE, title)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.DATE_ADDED, nowSec)
                put(MediaStore.MediaColumns.DATE_MODIFIED, nowSec)
                if (includeVideoTimestamps) {
                    put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
                }
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val itemUri = resolver.insert(collection, values) ?: return@runCatching null
            insertedUri = itemUri

            val outputStream = resolver.openOutputStream(itemUri)
                ?: throw IllegalStateException("Tidak dapat membuka MediaStore OutputStream")
            outputStream.use { out ->
                file.inputStream().use { input ->
                    input.copyTo(out)
                }
                out.flush()
            }

            val doneValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.SIZE, file.length())
                put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
            }
            resolver.update(itemUri, doneValues, null, null)
            itemUri
        }.getOrElse {
            insertedUri?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
            null
        }
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

    private fun resolveDynamicCookie(url: String, fallbackCookie: String): String {
        val liveCookie = runCatching {
            CookieManager.getInstance().getCookie(url)
        }.getOrNull().orEmpty()
        return when {
            liveCookie.isNotBlank() -> liveCookie
            fallbackCookie.isNotBlank() -> fallbackCookie
            else -> ""
        }
    }

    private fun buildRequest(
        url: String,
        entity: DownloadEntity,
        customReferer: String? = entity.referer,
        customOrigin: String? = entity.originHeader,
        includeCookie: Boolean = true,
        rangeHeader: String? = null
    ): Request {
        val builder = Request.Builder().url(url)
        if (entity.userAgent.isNotBlank()) builder.header("User-Agent", entity.userAgent)
        if (!customReferer.isNullOrBlank()) builder.header("Referer", customReferer)
        if (!customOrigin.isNullOrBlank()) builder.header("Origin", customOrigin)
        if (includeCookie) {
            val cookie = resolveDynamicCookie(url, entity.cookie)
            if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        }
        if (!rangeHeader.isNullOrBlank()) builder.header("Range", rangeHeader)
        builder.header("Accept", "*/*")
        return builder.build()
    }

    private data class HeaderStrategy(
        val referer: String?,
        val origin: String?,
        val includeCookie: Boolean
    )

    /**
     * Executes an HTTP request with 5-stage 401/403 header fallback so strict CORS CDNs,
     * cross-domain embed iframes, and hotlink-protected HLS servers still download reliably.
     */
    private fun executeWithHeaderFallback(
        url: String,
        entity: DownloadEntity,
        rangeHeader: String? = null
    ): Response {
        val derivedOrigin = MediaSniffer.deriveOriginHeader(entity.referer, entity.pageUrl)
        val originRootReferer = if (derivedOrigin.isNotBlank()) "$derivedOrigin/" else ""

        val candidates = linkedSetOf(
            // 1. Exact captured session headers
            HeaderStrategy(entity.referer.ifBlank { null }, entity.originHeader.ifBlank { null }, true),
            // 2. Drop Origin, keep full Referer + Cookie (fixes non-CORS strict CDNs)
            HeaderStrategy(entity.referer.ifBlank { null }, null, true),
            // 3. Origin-root Referer + derived Origin + Cookie (fixes iframe embed CDNs)
            HeaderStrategy(originRootReferer.ifBlank { null }, derivedOrigin.ifBlank { null }, true),
            // 4. Drop Origin & Referer, keep Cookie
            HeaderStrategy(null, null, true),
            // 5. Clean User-Agent only
            HeaderStrategy(null, null, false)
        )

        var lastResponse: Response? = null
        for (strat in candidates) {
            lastResponse?.close()
            val req = buildRequest(
                url = url,
                entity = entity,
                customReferer = strat.referer,
                customOrigin = strat.origin,
                includeCookie = strat.includeCookie,
                rangeHeader = rangeHeader
            )
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful || resp.code == 206) {
                return resp
            }
            lastResponse = resp
            if (resp.code != 401 && resp.code != 403) {
                return resp
            }
        }
        return lastResponse ?: client.newCall(buildRequest(url, entity, rangeHeader = rangeHeader)).execute()
    }

    private suspend fun fetchTextWithRetry(url: String, entity: DownloadEntity): String {
        var lastErr: Exception? = null
        for (attempt in 1..4) {
            try {
                executeWithHeaderFallback(url, entity).use { resp ->
                    if (!resp.isSuccessful) {
                        val code = resp.code
                        if (code == 429 || code == 502 || code == 503 || code == 504) {
                            throw IllegalStateException("HTTP $code (server sibuk/rate-limit)")
                        }
                        throw IllegalStateException("HTTP $code saat membaca manifest")
                    }
                    return resp.body?.string().orEmpty()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastErr = e
                val isRateLimited = e.message?.contains("429") == true || e.message?.contains("503") == true
                if (attempt < 4) delay(if (isRateLimited) 900L * attempt else 350L * attempt)
            }
        }
        throw lastErr ?: IllegalStateException("Gagal membaca manifest stream")
    }

    private suspend fun fetchBytesWithRetry(
        url: String,
        entity: DownloadEntity,
        rangeHeader: String? = null
    ): ByteArray {
        var lastErr: Exception? = null
        for (attempt in 1..4) {
            try {
                executeWithHeaderFallback(url, entity, rangeHeader).use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) {
                        val code = resp.code
                        if (code == 429 || code == 502 || code == 503 || code == 504) {
                            throw IllegalStateException("HTTP $code (rate-limit segmen)")
                        }
                        throw IllegalStateException("HTTP $code saat mengunduh segmen")
                    }
                    return resp.body?.bytes() ?: ByteArray(0)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastErr = e
                val isRateLimited = e.message?.contains("429") == true || e.message?.contains("503") == true
                if (attempt < 4) delay(if (isRateLimited) 850L * attempt else 300L * attempt)
            }
        }
        throw lastErr ?: IllegalStateException("Gagal mengunduh segmen stream")
    }

    private fun resolveRelativeUrl(baseUrl: String, relative: String): String {
        return runCatching { URL(URL(baseUrl), relative).toString() }.getOrElse { relative }
    }

    private data class HlsMasterChoice(
        val videoVariantUrl: String?,
        val audioRenditionUrl: String?
    )

    private fun parseMasterPlaylist(masterUrl: String, content: String): HlsMasterChoice {
        val lines = content.lines().map { it.trim() }
        var bestBandwidth = -1L
        var bestUrl: String? = null
        var bestAudioGroup: String? = null
        var pendingBandwidth = 0L
        var pendingAudioGroup: String? = null
        val audioGroups = mutableMapOf<String, String>()

        for (line in lines) {
            if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO")) {
                val group = Regex("""GROUP-ID="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                if (!group.isNullOrBlank() && !uri.isNullOrBlank() && !audioGroups.containsKey(group)) {
                    audioGroups[group] = resolveRelativeUrl(masterUrl, uri)
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pendingBandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                pendingAudioGroup = Regex("""AUDIO="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                val resolved = resolveRelativeUrl(masterUrl, line)
                if (pendingBandwidth >= bestBandwidth) {
                    bestBandwidth = pendingBandwidth
                    bestUrl = resolved
                    bestAudioGroup = pendingAudioGroup
                }
                pendingBandwidth = 0L
                pendingAudioGroup = null
            }
        }

        val audioUrl = bestAudioGroup?.let { audioGroups[it] } ?: audioGroups.values.firstOrNull()
        return HlsMasterChoice(videoVariantUrl = bestUrl, audioRenditionUrl = audioUrl)
    }

    private data class HlsSegment(
        val url: String,
        val sequenceIndex: Long,
        val keyMethod: String? = null,
        val keyUri: String? = null,
        val ivBytes: ByteArray? = null,
        val byteRangeHeader: String? = null,
        val isDrmKeyFormat: Boolean = false
    )

    private data class HlsPlaylist(
        val initMapUrl: String? = null,
        val initMapByteRange: String? = null,
        val segments: List<HlsSegment>
    )

    private fun parseMediaPlaylist(playlistUrl: String, content: String): HlsPlaylist {
        val lines = content.lines().map { it.trim() }
        var mediaSequence = 0L
        var currentKeyMethod: String? = null
        var currentKeyUri: String? = null
        var currentIv: ByteArray? = null
        var currentIsDrmFormat = false
        var initMapUrl: String? = null
        var initMapByteRange: String? = null
        val segments = mutableListOf<HlsSegment>()
        var expectSegment = false
        var seqOffset = 0L
        var pendingByteRangeHeader: String? = null
        var lastByteRangeEnd = 0L

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(line)
                    val rel = uriMatch?.groupValues?.getOrNull(1)
                    if (rel != null) initMapUrl = resolveRelativeUrl(playlistUrl, rel)
                    val brMatch = Regex("""BYTERANGE="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                    if (brMatch != null) {
                        val (header, _) = parseHlsByteRange(brMatch, 0L)
                        initMapByteRange = header
                    }
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val methodMatch = Regex("""METHOD=([^,\s]+)""").find(line)
                    val method = methodMatch?.groupValues?.getOrNull(1)?.trim()
                    if (method.equals("NONE", ignoreCase = true)) {
                        currentKeyMethod = null
                        currentKeyUri = null
                        currentIv = null
                        currentIsDrmFormat = false
                    } else {
                        val uriMatch = Regex("""URI="([^"]+)"""").find(line)
                        val ivMatch = Regex("""IV=0[xX]([0-9a-fA-F]+)""").find(line)
                        val keyFormat = Regex("""KEYFORMAT="([^"]+)"""").find(line)?.groupValues?.getOrNull(1).orEmpty()
                        currentKeyMethod = method
                        currentKeyUri = uriMatch?.groupValues?.getOrNull(1)?.let { rawUri ->
                            if (rawUri.startsWith("data:", ignoreCase = true)) {
                                rawUri
                            } else {
                                resolveRelativeUrl(playlistUrl, rawUri)
                            }
                        }
                        currentIv = ivMatch?.groupValues?.getOrNull(1)?.let { hexToBytes(it) }
                        currentIsDrmFormat = keyFormat.contains("widevine", ignoreCase = true) ||
                            keyFormat.contains("edef8ba9", ignoreCase = true) ||
                            keyFormat.contains("com.apple.streamingkeydelivery", ignoreCase = true) ||
                            keyFormat.contains("playready", ignoreCase = true)
                    }
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val spec = line.substringAfter(':').trim()
                    val (header, nextEnd) = parseHlsByteRange(spec, lastByteRangeEnd)
                    pendingByteRangeHeader = header
                    lastByteRangeEnd = nextEnd
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
                            ivBytes = currentIv,
                            byteRangeHeader = pendingByteRangeHeader,
                            isDrmKeyFormat = currentIsDrmFormat
                        )
                    )
                    pendingByteRangeHeader = null
                    seqOffset++
                    expectSegment = false
                }
            }
        }
        return HlsPlaylist(
            initMapUrl = initMapUrl,
            initMapByteRange = initMapByteRange,
            segments = segments
        )
    }

    private fun parseHlsByteRange(spec: String, defaultOffset: Long): Pair<String?, Long> {
        val parts = spec.split('@')
        val length = parts.getOrNull(0)?.toLongOrNull() ?: return null to defaultOffset
        val start = parts.getOrNull(1)?.toLongOrNull() ?: defaultOffset
        val end = (start + length - 1L).coerceAtLeast(start)
        return "bytes=$start-$end" to (end + 1L)
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

    companion object {
        /**
         * Decodes an inline HLS AES-128 key URI (`data:text/plain;base64,...` or `data:;base64,...`).
         */
        fun decodeDataUriKey(dataUri: String): ByteArray? {
            if (!dataUri.startsWith("data:", ignoreCase = true)) return null
            val commaIdx = dataUri.indexOf(',')
            if (commaIdx <= 0 || commaIdx >= dataUri.lastIndex) return null
            val meta = dataUri.substring(5, commaIdx)
            val payload = dataUri.substring(commaIdx + 1)
            return runCatching {
                if (meta.contains(";base64", ignoreCase = true)) {
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    payload.toByteArray(Charsets.ISO_8859_1)
                }
            }.getOrNull()
        }

        /**
         * Some streaming CDNs disguise MPEG-TS segments with fake PNG/GIF/JPEG/HTML header bytes
         * (e.g. .png/.jpg/.ico/.js segments). Scans up to 64 KB for aligned 188-byte MPEG-TS
         * sync packets (0x47 at i, i+188, and i+376 when available) and strips the fake prefix,
         * while preserving valid ISO-BMFF/fMP4 boxes untouched.
         */
        fun stripCloakedTsPrefix(data: ByteArray): ByteArray {
            if (data.size < 189 || data[0] == 0x47.toByte()) return data

            // Never corrupt valid ISO-BMFF / fMP4 segments (ftyp / styp / moof at offset 4..7)
            if (data.size >= 8) {
                val box = String(data, 4, 4, Charsets.US_ASCII)
                if (box == "ftyp" || box == "styp" || box == "moof" || box == "sidx") {
                    return data
                }
            }

            val scanLimit = (data.size - 189).coerceAtMost(65536)
            for (i in 1..scanLimit) {
                if (data[i] == 0x47.toByte() && data[i + 188] == 0x47.toByte()) {
                    val hasThirdCheck = (i + 376) < data.size
                    if (!hasThirdCheck || data[i + 376] == 0x47.toByte()) {
                        return data.copyOfRange(i, data.size)
                    }
                }
            }
            return data
        }
    }
}
