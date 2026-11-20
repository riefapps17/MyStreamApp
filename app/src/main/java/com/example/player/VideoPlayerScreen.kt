package com.example.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.browser.DetectedMedia
import com.example.browser.MediaFormat
import com.example.browser.MediaSniffer
import com.example.downloader.StreamDownloader
import com.example.ui.PlayerTarget
import kotlinx.coroutines.delay
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    target: PlayerTarget,
    onClose: () -> Unit,
    onSaveStream: (DetectedMedia) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val title = when (target) {
        is PlayerTarget.RemoteStream -> target.media.title
        is PlayerTarget.LocalDownload -> target.download.title
    }
    val formatLabel = when (target) {
        is PlayerTarget.RemoteStream -> target.media.format.label
        is PlayerTarget.LocalDownload -> "${target.download.format} • OFFLINE"
    }

    var isControlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var retryNonce by remember { mutableIntStateOf(0) }
    var currentPosMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isLandscape by remember { mutableStateOf(false) }
    var speedIndex by remember { mutableIntStateOf(1) }
    val speeds = remember { listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f) }
    var resizeModeIndex by remember { mutableIntStateOf(0) }
    val resizeModes = remember {
        listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
    }

    val exoPlayer = remember(target, retryNonce) {
        val builder = ExoPlayer.Builder(context)
        if (target is PlayerTarget.RemoteStream) {
            val okClient = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor(SmartStreamInterceptor(target.media))
                .build()
            val okFactory = OkHttpDataSource.Factory(okClient)
            val dataSourceFactory = DefaultDataSource.Factory(context, okFactory)
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        }
        builder.build().apply {
            val mediaItem = when (target) {
                is PlayerTarget.RemoteStream -> {
                    val itemBuilder = MediaItem.Builder().setUri(Uri.parse(target.media.url))
                    // Explicitly hint HLS/DASH MIME so disguised playlists (.txt/.php/extensionless)
                    // are routed to HlsMediaSource/DashMediaSource instead of failing as Progressive MP4
                    when (target.media.format) {
                        MediaFormat.HLS -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                        MediaFormat.DASH -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
                        else -> Unit
                    }
                    itemBuilder.build()
                }
                is PlayerTarget.LocalDownload -> {
                    MediaItem.fromUri(Uri.fromFile(File(target.download.localPath)))
                }
            }
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    playbackError = null
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = formatPlaybackError(error)
                isBuffering = false
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(exoPlayer, isPlaying) {
        while (true) {
            currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            if (exoPlayer.duration > 0) {
                durationMs = exoPlayer.duration
            }
            delay(400L)
        }
    }

    LaunchedEffect(isControlsVisible, isPlaying) {
        if (isControlsVisible && isPlaying) {
            delay(4000L)
            isControlsVisible = false
        }
    }

    BackHandler {
        onClose()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                isControlsVisible = !isControlsVisible
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = false
                    keepScreenOn = true
                    player = exoPlayer
                }
            },
            update = { view ->
                if (view.player !== exoPlayer) {
                    view.player = exoPlayer
                }
                view.resizeMode = resizeModes[resizeModeIndex]
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isBuffering && playbackError == null) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (playbackError != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Pemutaran Terganggu",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = playbackError ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            playbackError = null
                            isBuffering = true
                            retryNonce++
                        },
                        modifier = Modifier.testTag("player_retry_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Coba Lagi", color = Color.White)
                    }
                    Button(onClick = onClose) {
                        Text("Kembali ke Browser")
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.78f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            ) {
                // Top Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("player_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Tutup pemutar",
                            tint = Color.White
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (target is PlayerTarget.RemoteStream) {
                        Button(
                            onClick = { onSaveStream(target.media) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.testTag("player_save_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Simpan", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                // Center Transport Buttons
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = CircleShape
                    ) {
                        IconButton(
                            onClick = {
                                exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                            },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = "Mundur 10 detik",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    ) {
                        IconButton(
                            onClick = {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                            modifier = Modifier
                                .size(68.dp)
                                .testTag("player_play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Jeda" else "Putar",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Surface(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = CircleShape
                    ) {
                        IconButton(
                            onClick = {
                                val targetPos = exoPlayer.currentPosition + 10_000L
                                exoPlayer.seekTo(
                                    if (durationMs > 0) targetPos.coerceAtMost(durationMs) else targetPos
                                )
                            },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = "Maju 10 detik",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                // Bottom Scrubber & Options
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .align(Alignment.BottomCenter)
                ) {
                    var sliderDraggingPos by remember { mutableFloatStateOf(-1f) }
                    val sliderValue = if (sliderDraggingPos >= 0f) {
                        sliderDraggingPos
                    } else if (durationMs > 0L) {
                        (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }

                    Slider(
                        value = sliderValue,
                        onValueChange = { fraction ->
                            sliderDraggingPos = fraction
                        },
                        onValueChangeFinished = {
                            if (durationMs > 0L && sliderDraggingPos >= 0f) {
                                exoPlayer.seekTo((sliderDraggingPos * durationMs).toLong())
                            }
                            sliderDraggingPos = -1f
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatMs(currentPosMs)} / ${formatMs(durationMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        // Speed Toggle
                        Surface(
                            color = Color.White.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.clickable {
                                speedIndex = (speedIndex + 1) % speeds.size
                                exoPlayer.setPlaybackSpeed(speeds[speedIndex])
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = "Kecepatan putar",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${speeds[speedIndex]}x",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Aspect Mode Toggle
                        IconButton(onClick = {
                            resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                        }) {
                            Icon(
                                imageVector = Icons.Default.AspectRatio,
                                contentDescription = "Ubah rasio layar",
                                tint = Color.White
                            )
                        }

                        // Orientation Toggle
                        IconButton(onClick = {
                            isLandscape = !isLandscape
                            activity?.requestedOrientation = if (isLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                        }) {
                            Icon(
                                imageVector = if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = "Putar layar penuh",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000L
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

@OptIn(UnstableApi::class)
private fun formatPlaybackError(error: PlaybackException): String {
    var cursor: Throwable? = error
    while (cursor != null) {
        when (cursor) {
            is HttpDataSource.InvalidResponseCodeException -> {
                return when (cursor.responseCode) {
                    401, 403 -> "Server menolak akses (HTTP ${cursor.responseCode}). Situs mungkin memakai proteksi Cloudflare/hotlink atau token sesi kedaluwarsa — coba muat ulang halaman web lalu pindai ulang."
                    404, 410 -> "Tautan stream sudah kedaluwarsa (HTTP ${cursor.responseCode}). Muat ulang halaman web untuk mendapatkan tautan baru."
                    429, 503 -> "Server sedang membatasi koneksi (HTTP ${cursor.responseCode}). Ketuk Coba Lagi beberapa saat lagi."
                    else -> "Server mengembalikan galat HTTP ${cursor.responseCode}."
                }
            }
            is UnrecognizedInputFormatException -> {
                return "Format stream tidak dikenali pemutar (kemungkinan terenkripsi DRM, halaman HTML proteksi, atau format khusus situs)."
            }
            is ParserException -> {
                return "Gagal membaca struktur stream video (kemungkinan playlist terproteksi atau segmen terenkripsi)."
            }
            is BehindLiveWindowException -> {
                return "Siaran langsung tertinggal dari jendela live. Ketuk Coba Lagi untuk menyambung ulang."
            }
        }
        cursor = cursor.cause
    }
    return error.localizedMessage ?: "Gagal memutar aliran video"
}

/**
 * OkHttp interceptor for ExoPlayer that:
 * 1) Injects live per-URL WebView cookies + captured session headers
 * 2) Runs 5-stage 401/403 header fallback (handling strict CORS CDNs & embed iframes)
 * 3) Transparently strips cloaked PNG/JPG/GIF headers from disguised HLS MPEG-TS segments
 *    so ExoPlayer can play streams that normally require a patched web hls.js.
 */
private class SmartStreamInterceptor(
    private val media: DetectedMedia
) : Interceptor {

    private data class Strategy(
        val referer: String?,
        val origin: String?,
        val includeCookie: Boolean
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val reqUrl = original.url.toString()
        val derivedOrigin = MediaSniffer.deriveOriginHeader(media.referer, reqUrl)
        val originRootReferer = if (derivedOrigin.isNotBlank()) "$derivedOrigin/" else ""

        val liveCookie = runCatching {
            CookieManager.getInstance().getCookie(reqUrl)
        }.getOrNull().orEmpty().ifBlank { media.cookie }

        val strategies = linkedSetOf(
            Strategy(media.referer.ifBlank { null }, media.originHeader.ifBlank { null }, true),
            Strategy(media.referer.ifBlank { null }, null, true),
            Strategy(originRootReferer.ifBlank { null }, derivedOrigin.ifBlank { null }, true),
            Strategy(null, null, true),
            Strategy(null, null, false)
        )

        var response: Response? = null
        for (strat in strategies) {
            response?.close()
            val reqBuilder = original.newBuilder()
                .removeHeader("Referer")
                .removeHeader("Origin")
                .removeHeader("Cookie")
                .removeHeader("User-Agent")

            if (media.userAgent.isNotBlank()) reqBuilder.header("User-Agent", media.userAgent)
            if (!strat.referer.isNullOrBlank()) reqBuilder.header("Referer", strat.referer)
            if (!strat.origin.isNullOrBlank()) reqBuilder.header("Origin", strat.origin)
            if (strat.includeCookie && liveCookie.isNotBlank()) reqBuilder.header("Cookie", liveCookie)
            if (original.header("Accept").isNullOrBlank()) reqBuilder.header("Accept", "*/*")

            val attemptResp = chain.proceed(reqBuilder.build())
            if (attemptResp.isSuccessful || attemptResp.code == 206) {
                response = attemptResp
                break
            }
            response = attemptResp
            if (attemptResp.code != 401 && attemptResp.code != 403) {
                break
            }
        }

        val finalResp = response ?: chain.proceed(original)
        if (media.format == MediaFormat.HLS && (finalResp.isSuccessful || finalResp.code == 206)) {
            return maybeStripCloakedHlsSegment(finalResp)
        }
        return finalResp
    }

    private fun maybeStripCloakedHlsSegment(response: Response): Response {
        val body = response.body ?: return response
        val contentLength = body.contentLength()
        // Only inspect medium-sized segment payloads (avoid buffering huge progressive files)
        if (contentLength > 20L * 1024 * 1024) return response

        val ct = response.header("Content-Type")?.lowercase(Locale.US).orEmpty()
        if (ct.contains("mpegurl") || ct.contains("html") || ct.contains("json")) {
            return response
        }

        val head = runCatching { response.peekBody(64).bytes() }.getOrNull() ?: return response
        if (head.size < 64) return response
        // Already clean MPEG-TS sync byte
        if (head[0] == 0x47.toByte()) return response
        // Skip HLS playlist text
        val ascii = runCatching {
            String(head, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        }.getOrDefault("")
        if (ascii.startsWith("#EXTM3U", ignoreCase = true) || ascii.startsWith("#EXT", ignoreCase = true)) {
            return response
        }
        // Skip valid ISO-BMFF / fMP4 segments
        val box = runCatching { String(head, 4, 4, Charsets.US_ASCII) }.getOrDefault("")
        if (box == "ftyp" || box == "styp" || box == "moof" || box == "sidx") {
            return response
        }

        // Candidate cloaked segment (e.g. PNG/JPG/GIF prefix before MPEG-TS sync packets)
        val rawBytes = body.bytes()
        val cleaned = StreamDownloader.stripCloakedTsPrefix(rawBytes)
        val mediaType = if (cleaned.size < rawBytes.size) {
            "video/mp2t".toMediaTypeOrNull()
        } else {
            body.contentType()
        }
        return response.newBuilder()
            .body(cleaned.toResponseBody(mediaType))
            .header("Content-Length", cleaned.size.toString())
            .build()
    }
}
