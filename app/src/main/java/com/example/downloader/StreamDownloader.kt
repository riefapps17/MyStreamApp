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
        var separateAudioPlaylistUrl: String? = null

        // If master playlist, select highest-bandwidth variant and check separate audio rendition
        if (playlistText.contains("#EXT-X-STREAM-INF")) {
            val masterChoice = parseMasterPlaylist(playlistUrl, playlistText)
            if (masterChoice.videoVariantUrl != null) {
                playlistUrl = masterChoice.videoVariantUrl
                playlistText = fetchTextWithRetry(playlistUrl, entity)
            }
            separateAudioPlaylistUrl = masterChoice.audioRenditionUrl
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

        if (isFmp4 && !separateAudioPlaylistUrl.isNullOrBlank()) {
            val tempVideo = File(getTempDir(), "hls_vid_${entity.id}.mp4")
            val tempAudio = File(getTempDir(), "hls_aud_${entity.id}.m4a")
            try {
                downloadHlsMediaSegments(entity, parsed, tempVideo, isFmp4 = true, progressMin = 2, progressMax = 68, label = "Video")
                val audioText = fetchTextWithRetry(separateAudioPlaylistUrl, entity)
                val audioParsed = parseMediaPlaylist(separateAudioPlaylistUrl, audioText)
                val audioOk = if (audioParsed.segments.isNotEmpty()) {
                    runCatching {
                        downloadHlsMediaSegments(entity, audioParsed, tempAudio, isFmp4 = true, progressMin = 70, progressMax = 90, label = "Audio")
                        true
                    }.getOrDefault(false)
                } else {
                    false
                }

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
                    outputFile = targetFile
                )
                if (!muxed) tempVideo.copyTo(targetFile, overwrite = true)

                finalizeDownload(
                    entity = entity,
                    targetFile = targetFile,
                    fileName = finalFileName,
                    mimeType = "video/mp4",
                    exportedDetail = "HLS Video+Audio digabung & disimpan",
                    offlineDetail = "HLS Video+Audio tersimpan offline"
                )
            } finally {
                runCatching { tempVideo.delete() }
                runCatching { tempAudio.delete() }
            }
            return
        }

        downloadHlsMediaSegments(entity, parsed, targetFile, isFmp4 = isFmp4, progressMin = 1, progressMax = 99, label = "Segmen")

        finalizeDownload(
            entity = entity,
            targetFile = targetFile,
            fileName = finalFileName,
            mimeType = finalMime,
            exportedDetail = "HLS digabung & disimpan",
            offlineDetail = "HLS tersimpan offline"
        )
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
            if (seg.keyMethod.equals("SAMPLE-AES", ignoreCase = true) ||
                seg.keyMethod.equals("SAMPLE-AES-CTR", ignoreCase = true)
            ) {
                throw IllegalStateException("Stream HLS ini memakai enkripsi DRM (${seg.keyMethod})")
            }
            if (seg.keyMethod.equals("AES-128", ignoreCase = true) && !seg.keyUri.isNullOrBlank()) {
                uniqueKeyUris.add(seg.keyUri)
            }
        }
        for (keyUri in uniqueKeyUris) {
            keyCache[keyUri] = fetchBytesWithRetry(keyUri, entity)
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

            coroutineScope {
                parsed.segments.mapIndexed { index, seg ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            ensureActive()
                            val rawBytes = fetchBytesWithRetry(seg.url, entity, seg.byteRangeHeader)
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
                            segFiles[index] = f

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

    private fun buildRequest(
        url: String,
        entity: DownloadEntity,
        includeOrigin: Boolean = true,
        includeReferer: Boolean = true,
        includeCookie: Boolean = true,
        rangeHeader: String? = null
    ): Request {
        val builder = Request.Builder().url(url)
        if (entity.userAgent.isNotBlank()) builder.header("User-Agent", entity.userAgent)
        if (includeReferer && entity.referer.isNotBlank()) builder.header("Referer", entity.referer)
        if (includeOrigin && entity.originHeader.isNotBlank()) builder.header("Origin", entity.originHeader)
        if (includeCookie && entity.cookie.isNotBlank()) builder.header("Cookie", entity.cookie)
        if (!rangeHeader.isNullOrBlank()) builder.header("Range", rangeHeader)
        builder.header("Accept", "*/*")
        return builder.build()
    }

    /**
     * Executes an HTTP request with smart 401/403 header fallback so strict CORS CDNs
     * (rejecting Origin or cross-site Referer) still download reliably.
     */
    private fun executeWithHeaderFallback(
        url: String,
        entity: DownloadEntity,
        rangeHeader: String? = null
    ): Response {
        val strategies = listOf(
            Triple(true, true, true),    // Full captured session headers
            Triple(false, true, true),   // Drop Origin (fixes strict CORS CDNs)
            Triple(false, false, true),  // Drop Referer + Origin (fixes hotlink-strict CDNs)
            Triple(false, false, false)  // Clean User-Agent only
        )
        var lastResponse: Response? = null
        for ((idx, strat) in strategies.withIndex()) {
            val (useOrigin, useReferer, useCookie) = strat
            if (idx == 1 && entity.originHeader.isBlank()) continue
            if (idx == 2 && entity.referer.isBlank()) continue
            if (idx == 3 && entity.cookie.isBlank()) continue

            lastResponse?.close()
            val req = buildRequest(
                url = url,
                entity = entity,
                includeOrigin = useOrigin,
                includeReferer = useReferer,
                includeCookie = useCookie,
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
        for (attempt in 1..3) {
            try {
                executeWithHeaderFallback(url, entity).use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code} saat membaca manifest")
                    }
                    return resp.body?.string().orEmpty()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastErr = e
                if (attempt < 3) delay(350L * attempt)
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
        for (attempt in 1..3) {
            try {
                executeWithHeaderFallback(url, entity, rangeHeader).use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) {
                        throw IllegalStateException("HTTP ${resp.code} saat mengunduh segmen")
                    }
                    return resp.body?.bytes() ?: ByteArray(0)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastErr = e
                if (attempt < 3) delay(300L * attempt)
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
        val byteRangeHeader: String? = null
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
                    val methodMatch = Regex("""METHOD=([^,]+)""").find(line)
                    val uriMatch = Regex("""URI="([^"]+)"""").find(line)
                    val ivMatch = Regex("""IV=0x([0-9a-fA-F]+)""").find(line)
                    currentKeyMethod = methodMatch?.groupValues?.getOrNull(1)?.trim()
                    currentKeyUri = uriMatch?.groupValues?.getOrNull(1)?.let {
                        resolveRelativeUrl(playlistUrl, it)
                    }
                    currentIv = ivMatch?.groupValues?.getOrNull(1)?.let { hexToBytes(it) }
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
                            byteRangeHeader = pendingByteRangeHeader
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
         * Some streaming CDNs disguise MPEG-TS segments with fake PNG/GIF/JPEG header bytes
         * (e.g. .png/.jpg segments). If data does not begin with MPEG-TS sync byte 0x47,
         * locates the first valid 188-byte sync pair (0x47 at i and i+188) and strips the fake prefix.
         */
        fun stripCloakedTsPrefix(data: ByteArray): ByteArray {
            if (data.isEmpty() || data[0] == 0x47.toByte()) return data
            val scanLimit = (data.size - 189).coerceAtMost(4096)
            for (i in 1..scanLimit) {
                if (data[i] == 0x47.toByte() && data[i + 188] == 0x47.toByte()) {
                    return data.copyOfRange(i, data.size)
                }
            }
            return data
        }
    }
}
