package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.browser.DetectedMedia
import com.example.browser.MediaFormat
import com.example.browser.MediaSniffer
import com.example.browser.VideoPreviewResult
import com.example.browser.VideoThumbnailLoader
import com.example.data.local.DownloadEntity
import com.example.data.local.DownloadStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectedMediaSheet(
    detectedMedia: List<DetectedMedia>,
    pageFallbackThumbnail: String,
    downloads: List<DownloadEntity>,
    onDismiss: () -> Unit,
    onRescan: () -> Unit,
    onPlayStream: (DetectedMedia) -> Unit,
    onSaveStream: (DetectedMedia) -> Unit,
    onAddManualUrl: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showManualInput by remember { mutableStateOf(false) }
    var manualUrl by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Radar,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Radar Media Streaming",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "${detectedMedia.size} aliran video tertangkap • Ketuk pratinjau untuk cek",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onRescan,
                    modifier = Modifier.testTag("rescan_media_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.action_scan_page),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (detectedMedia.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.empty_sniffer_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.empty_sniffer_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 148.dp, max = 460.dp)
                ) {
                    items(detectedMedia, key = { it.id }) { media ->
                        val matchingDownload = downloads.firstOrNull { it.sourceUrl == media.url }
                        DetectedMediaCard(
                            media = media,
                            pageFallbackThumbnail = pageFallbackThumbnail,
                            existingDownload = matchingDownload,
                            onPlay = { onPlayStream(media) },
                            onSave = { onSaveStream(media) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            if (!showManualInput) {
                TextButton(
                    onClick = { showManualInput = true },
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Tempel URL Stream Langsung (.mp4 / .m3u8 / .mpd)")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualUrl,
                        onValueChange = { manualUrl = it },
                        placeholder = { Text("https://.../video.mp4, .m3u8, atau .mpd") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (manualUrl.isNotBlank()) {
                                onAddManualUrl(manualUrl)
                                manualUrl = ""
                                showManualInput = false
                            }
                        }
                    ) {
                        Text("Tambah")
                    }
                }
            }
        }
    }
}

private sealed interface ThumbnailLoadState {
    data object Loading : ThumbnailLoadState
    data class Ready(val result: VideoPreviewResult) : ThumbnailLoadState
}

@Composable
private fun DetectedMediaCard(
    media: DetectedMedia,
    pageFallbackThumbnail: String,
    existingDownload: DownloadEntity?,
    onPlay: () -> Unit,
    onSave: () -> Unit
) {
    val host = remember(media.url) {
        runCatching { Uri.parse(media.url).host }.getOrNull().orEmpty()
    }

    val hasRemotePoster = media.thumbnailUrl.startsWith("http://", ignoreCase = true) ||
        media.thumbnailUrl.startsWith("https://", ignoreCase = true)

    val frameState by produceState<ThumbnailLoadState>(
        initialValue = VideoThumbnailLoader.getCached(media.url)
            ?.let { ThumbnailLoadState.Ready(it) }
            ?: if (hasRemotePoster) {
                ThumbnailLoadState.Ready(VideoPreviewResult(null))
            } else {
                ThumbnailLoadState.Loading
            },
        key1 = media.url,
        key2 = media.thumbnailUrl
    ) {
        if (!hasRemotePoster) {
            val result = VideoThumbnailLoader.loadPreview(media)
            value = ThumbnailLoadState.Ready(result)
        }
    }

    val extractedPreview = (frameState as? ThumbnailLoadState.Ready)?.result
    val effectiveDuration = if (media.durationSeconds > 0) {
        media.durationSeconds
    } else {
        extractedPreview?.durationSeconds ?: 0
    }
    val effectiveResolution = media.resolution.ifBlank {
        extractedPreview?.resolution.orEmpty()
    }

    val metaParts = remember(media, effectiveDuration, effectiveResolution) {
        buildList {
            if (effectiveResolution.isNotBlank()) add(effectiveResolution)
            if (effectiveDuration > 0) add(MediaSniffer.formatDuration(effectiveDuration))
            if (media.sizeBytes > 0) add(MediaSniffer.formatBytes(media.sizeBytes))
            add(media.sourceOrigin)
        }.joinToString(" • ")
    }

    val isSaving = existingDownload?.status == DownloadStatus.DOWNLOADING.name ||
        existingDownload?.status == DownloadStatus.QUEUED.name
    val isSaved = existingDownload?.status == DownloadStatus.COMPLETED.name

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("detected_media_card")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Visual Video Thumbnail Preview (tap to quick-preview in native player)
                VideoThumbnailPreview(
                    media = media,
                    hasRemotePoster = hasRemotePoster,
                    frameState = frameState,
                    pageFallbackThumbnail = pageFallbackThumbnail,
                    durationSeconds = effectiveDuration,
                    onClick = onPlay
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = media.format.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        if (media.audioUrl.isNotBlank() || media.format == MediaFormat.DASH) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "+Audio",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = media.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (host.isNotBlank()) "$host • $metaParts" else metaParts,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onPlay,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("play_detected_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_play_native))
                }

                Button(
                    onClick = onSave,
                    enabled = !isSaving && !isSaved,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSaved) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (isSaved) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("save_detected_button")
                ) {
                    Icon(
                        imageVector = if (isSaved) Icons.Default.CheckCircle else Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        when {
                            isSaved -> "Tersimpan"
                            isSaving -> "${existingDownload?.progressPercent ?: 0}%"
                            else -> stringResource(R.string.action_save_video)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoThumbnailPreview(
    media: DetectedMedia,
    hasRemotePoster: Boolean,
    frameState: ThumbnailLoadState,
    pageFallbackThumbnail: String,
    durationSeconds: Int,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val extractedBitmap = (frameState as? ThumbnailLoadState.Ready)?.result?.bitmap
    val fallbackUrl = pageFallbackThumbnail.takeIf {
        it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
    }
    val remoteImageUrl = when {
        hasRemotePoster -> media.thumbnailUrl
        extractedBitmap == null && frameState is ThumbnailLoadState.Ready && fallbackUrl != null -> fallbackUrl
        else -> null
    }

    val imageRequest = remember(remoteImageUrl, media.referer, media.userAgent, media.cookie) {
        remoteImageUrl?.let { imgUrl ->
            ImageRequest.Builder(context)
                .data(imgUrl)
                .crossfade(true)
                .apply {
                    if (media.referer.isNotBlank()) addHeader("Referer", media.referer)
                    if (media.userAgent.isNotBlank()) addHeader("User-Agent", media.userAgent)
                    if (media.cookie.isNotBlank()) addHeader("Cookie", media.cookie)
                }
                .build()
        }
    }

    Box(
        modifier = Modifier
            .width(124.dp)
            .height(78.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    )
                )
            )
            .clickable(onClick = onClick)
            .testTag("detected_media_thumbnail"),
        contentAlignment = Alignment.Center
    ) {
        when {
            extractedBitmap != null -> {
                Image(
                    bitmap = extractedBitmap.asImageBitmap(),
                    contentDescription = "Pratinjau ${media.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            imageRequest != null -> {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = "Thumbnail ${media.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            frameState is ThumbnailLoadState.Loading -> {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = "Ikon video",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Subtle scrim for play button & duration readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x99000000)
                        )
                    )
                )
        )

        // Centered Play badge so users know tapping previews the video
        if (frameState !is ThumbnailLoadState.Loading) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Pratinjau video",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Bottom-right duration badge
        if (durationSeconds > 0) {
            Surface(
                color = Color(0xCC000000),
                shape = RoundedCornerShape(5.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
            ) {
                Text(
                    text = MediaSniffer.formatDuration(durationSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
    }
}
