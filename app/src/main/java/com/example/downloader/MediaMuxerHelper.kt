package com.example.downloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object MediaMuxerHelper {

    /**
     * Merges a video-only MP4/M4V file and an audio-only MP4/M4A file into a single
     * playable MP4 container using Android's native [MediaExtractor] and [MediaMuxer].
     *
     * @return true if muxing succeeded with video (and audio when compatible), false otherwise.
     */
    fun muxVideoAndAudio(
        videoFile: File,
        audioFile: File?,
        outputFile: File
    ): Boolean {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        return try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            val videoTrackIndex = selectTrack(videoExtractor, "video/")
            if (videoTrackIndex < 0) return false
            videoExtractor.selectTrack(videoTrackIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            if (audioFile != null && audioFile.exists() && audioFile.length() > 0L) {
                runCatching {
                    audioExtractor.setDataSource(audioFile.absolutePath)
                    val found = selectTrack(audioExtractor, "audio/")
                    if (found >= 0) {
                        audioTrackIndex = found
                        audioExtractor.selectTrack(found)
                        audioFormat = audioExtractor.getTrackFormat(found)
                    }
                }
            }

            if (outputFile.exists()) outputFile.delete()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val muxVideoTrack = muxer.addTrack(videoFormat)
            val muxAudioTrack = if (audioFormat != null) {
                runCatching { muxer.addTrack(audioFormat!!) }.getOrElse { -1 }
            } else {
                -1
            }

            muxer.start()

            val maxBufferSize = maxOf(
                getMaxInputSize(videoFormat, 1024 * 1024),
                audioFormat?.let { getMaxInputSize(it, 256 * 1024) } ?: (256 * 1024)
            ).coerceAtMost(4 * 1024 * 1024)
            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            copyTrackSamples(videoExtractor, muxer, muxVideoTrack, buffer, bufferInfo)
            if (muxAudioTrack >= 0 && audioTrackIndex >= 0) {
                copyTrackSamples(audioExtractor, muxer, muxAudioTrack, buffer, bufferInfo)
            }

            muxer.stop()
            outputFile.exists() && outputFile.length() > 0L
        } catch (_: Exception) {
            runCatching { outputFile.delete() }
            false
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) {
                return i
            }
        }
        return -1
    }

    private fun getMaxInputSize(format: MediaFormat, fallback: Int): Int {
        return if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(fallback)
        } else {
            fallback
        }
    }

    private fun copyTrackSamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxTrackIndex: Int,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            val sampleTime = extractor.sampleTime
            if (sampleTime < 0L) {
                extractor.advance()
                continue
            }

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = sampleTime
            bufferInfo.flags = mapExtractorFlagsToCodecFlags(extractor.sampleFlags)

            muxer.writeSampleData(muxTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }

    private fun mapExtractorFlagsToCodecFlags(sampleFlags: Int): Int {
        var codecFlags = 0
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return codecFlags
    }
}
