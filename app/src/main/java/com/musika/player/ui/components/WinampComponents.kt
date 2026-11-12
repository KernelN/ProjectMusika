package com.musika.player.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.musika.player.core.model.AudioQuality
import com.musika.player.core.model.LoopMode
import com.musika.player.core.model.NowPlayingArtworkStyle
import com.musika.player.core.model.RandomMode
import com.musika.player.core.model.Track
import com.musika.player.player.DualQueueEngine
import com.musika.player.ui.theme.SamsungBlue
import com.musika.player.ui.theme.WinampAmberGold
import com.musika.player.ui.theme.WinampDarkBezel
import com.musika.player.ui.theme.WinampLedGreen
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Renders extracted Track/Album/Artist artwork (embedded ID3 APIC/PIC, FLAC PICTURE, M4A covr,
 * or folder cover image) with a styled Samsung/Winamp fallback icon.
 */
@Composable
fun ArtworkThumbnail(
    artworkUri: String?,
    modifier: Modifier = Modifier,
    fallbackIcon: ImageVector = Icons.Default.MusicNote,
    fallbackTint: Color = SamsungBlue,
    size: Dp = 42.dp,
    cornerRadius: Dp = 8.dp,
    asCircularDisc: Boolean = false
) {
    val shape = if (asCircularDisc) CircleShape else RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(WinampDarkBezel)
            .border(1.dp, fallbackTint.copy(alpha = 0.35f), shape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = fallbackIcon,
            contentDescription = null,
            tint = fallbackTint,
            modifier = Modifier.size(size * 0.55f)
        )
        if (!artworkUri.isNullOrBlank()) {
            AsyncImage(
                model = artworkUri,
                contentDescription = "Cover Artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (asCircularDisc) {
            Box(
                modifier = Modifier
                    .size(size * 0.24f)
                    .clip(CircleShape)
                    .background( Color(0xFF0D1017))
                    .border(1.dp, WinampAmberGold.copy(alpha = 0.7f), CircleShape)
            )
        }
    }
}

/**
 * Renders the main Now Playing cover artwork according to the user's selected [NowPlayingArtworkStyle]:
 * - ROUNDED_SQUARE: Classic rounded square cover
 * - SPINNING_DISC: Rotating vinyl/CD platter with grooves & center spindle hole
 * - FULL_SCREEN_BACKGROUND: Compact floating card over the full-bleed background
 * - WINAMP_RETRO_BEZEL: Classic metallic/LED green bordered Winamp frame
 */
@Composable
fun StyledNowPlayingArtwork(
    artworkUri: String?,
    artworkStyle: NowPlayingArtworkStyle,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "discRotation")
    val animatedAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vinylSpin"
    )
    val effectiveRotation = if (artworkStyle == NowPlayingArtworkStyle.SPINNING_DISC && isPlaying) {
        animatedAngle
    } else {
        0f
    }

    when (artworkStyle) {
        NowPlayingArtworkStyle.SPINNING_DISC -> {
            Box(
                modifier = modifier
                    .size(210.dp)
                    .rotate(effectiveRotation)
                    .clip(CircleShape)
                    .background(Color(0xFF10131A))
                    .border(3.dp, SamsungBlue.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Album,
                    contentDescription = null,
                    tint = SamsungBlue,
                    modifier = Modifier.size(96.dp)
                )
                if (!artworkUri.isNullOrBlank()) {
                    AsyncImage(
                        model = artworkUri,
                        contentDescription = "Spinning Disc Artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // Vinyl groove rings & center spindle hole
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.25f),
                        radius = size.minDimension * 0.46f,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.14f),
                        radius = size.minDimension * 0.36f,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF141822))
                        .border(2.dp, WinampAmberGold, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color.Black)
                    )
                }
            }
        }

        NowPlayingArtworkStyle.WINAMP_RETRO_BEZEL -> {
            Box(
                modifier = modifier
                    .size(196.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(WinampDarkBezel)
                    .border(2.dp, WinampLedGreen, RoundedCornerShape(4.dp))
                    .padding(6.dp)
                    .border(1.dp, WinampAmberGold.copy(alpha = 0.7f), RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Album,
                    contentDescription = null,
                    tint = WinampLedGreen,
                    modifier = Modifier.size(84.dp)
                )
                if (!artworkUri.isNullOrBlank()) {
                    AsyncImage(
                        model = artworkUri,
                        contentDescription = "Winamp Bezel Artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        NowPlayingArtworkStyle.FULL_SCREEN_BACKGROUND -> {
            ArtworkThumbnail(
                artworkUri = artworkUri,
                modifier = modifier,
                fallbackIcon = Icons.Default.Album,
                fallbackTint = WinampAmberGold,
                size = 156.dp,
                cornerRadius = 18.dp
            )
        }

        NowPlayingArtworkStyle.ROUNDED_SQUARE -> {
            ArtworkThumbnail(
                artworkUri = artworkUri,
                modifier = modifier,
                fallbackIcon = Icons.Default.Album,
                fallbackTint = SamsungBlue,
                size = 196.dp,
                cornerRadius = 16.dp
            )
        }
    }
}

/**
 * Live Winamp Classic Telemetry Bar (Specification #1):
 * Displays format (FLAC/MP3/OPUS), bitrate (kbps), sample rate (kHz), and bit depth (16/24bit).
 */
@Composable
fun WinampTelemetryBar(
    audioQuality: AudioQuality,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(WinampDarkBezel)
            .border(1.dp, WinampLedGreen.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = audioQuality.format.uppercase(Locale.US),
            color = if (audioQuality.isLossless) WinampAmberGold else WinampLedGreen,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = audioQuality.bitrateFormatted,
            color = WinampLedGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = audioQuality.sampleRateKhzFormatted,
            color = WinampLedGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = "${audioQuality.bitDepth}bit",
            color = WinampLedGreen.copy(alpha = 0.9f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Samsung Music + Winamp Hybrid Mini-Player docked at the bottom of the screen.
 * Respects system navigation bar insets so it is never hidden behind phone UI.
 * Tapping anywhere on the bar opens the full Now Playing screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WinampMiniPlayerBar(
    state: DualQueueEngine.EngineState,
    artworkStyle: NowPlayingArtworkStyle,
    onExpandNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = state.currentTrack ?: return
    val progressFraction = if (track.durationMs > 0L) {
        (state.positionMs.toFloat() / track.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onExpandNowPlaying() },
        color = Color(0xFF161A24),
        tonalElevation = 10.dp,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = SamsungBlue,
                trackColor = Color.White.copy(alpha = 0.08f)
            )

            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkThumbnail(
                        artworkUri = track.artworkUri,
                        fallbackIcon = Icons.Default.MusicNote,
                        fallbackTint = SamsungBlue,
                        size = 44.dp,
                        cornerRadius = 8.dp,
                        asCircularDisc = artworkStyle == NowPlayingArtworkStyle.SPINNING_DISC
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee()
                        )
                        Text(
                            text = "${track.artist} • ${track.album}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee()
                        )
                    }

                    IconButton(
                        onClick = onSkipPrevious,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Skip Previous"
                        )
                    }
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            tint = WinampLedGreen
                        )
                    }
                    IconButton(
                        onClick = onSkipNext,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Skip Next"
                        )
                    }
                    IconButton(
                        onClick = onOpenQueue,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Instant Queue Access",
                            tint = if (state.priorityQueue.isNotEmpty()) WinampAmberGold else Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WinampTelemetryBar(
                        audioQuality = track.audioQuality,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatDurationMs(state.positionMs)} / ${formatDurationMs(track.durationMs)}",
                            color = Color.LightGray,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = formatRandomLabel(state.randomMode),
                            color = if (state.randomMode != RandomMode.OFF) SamsungBlue else Color.Gray,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = formatLoopLabel(state.loopMode),
                            color = if (state.loopMode != LoopMode.NO_LOOP) WinampLedGreen else Color.Gray,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-screen Now Playing view with live Winamp Telemetry, 4 customizable artwork styles
 * (including Spinning Vinyl Disc and Full-Screen Spotify-style background), 1-tap Cross-Navigation
 * to Album / Artist / Folder, 3-state Random & 3-state Loop controls, and full system-bar safe insets.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreenOverlay(
    state: DualQueueEngine.EngineState,
    artworkStyle: NowPlayingArtworkStyle,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onCycleRandom: () -> Unit,
    onCycleLoop: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onJumpToAlbum: (String) -> Unit,
    onJumpToArtist: (String) -> Unit,
    onJumpToFolder: (Track) -> Unit
) {
    val track = state.currentTrack ?: return
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Optional Full-Screen Spotify-style Artwork Background with dark gradient scrim
            if (artworkStyle == NowPlayingArtworkStyle.FULL_SCREEN_BACKGROUND && !track.artworkUri.isNullOrBlank()) {
                AsyncImage(
                    model = track.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xCC0B0E14),
                                    Color(0xB30B0E14),
                                    Color(0xF20B0E14)
                                )
                            )
                        )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close Now Playing")
                    }
                    Text(
                        text = state.activeContextName ?: "NOW PLAYING",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    IconButton(onClick = onOpenQueue) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Open Dual Queue",
                            tint = WinampAmberGold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Winamp Classic Live Telemetry Readout
                WinampTelemetryBar(
                    audioQuality = track.audioQuality,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                // User-Customizable Cover Artwork (Rounded Square, Spinning Disc, Full-Screen, or Winamp Bezel)
                StyledNowPlayingArtwork(
                    artworkUri = track.artworkUri,
                    artworkStyle = artworkStyle,
                    isPlaying = state.isPlaying
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Track Title, Artist, Album (with smooth auto marquee scrolling when overflowing)
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.titleMedium,
                    color = SamsungBlue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .basicMarquee()
                        .clickable {
                            onDismiss()
                            onJumpToArtist(track.artist)
                        }
                )
                Text(
                    text = "${track.album} • Disc ${track.discNumber}, Track ${track.trackNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .basicMarquee()
                        .clickable {
                            onDismiss()
                            onJumpToAlbum(track.album)
                        }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 1-Tap Cross-Navigation Chips (never wrap onto multiple ugly lines)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = {
                            onDismiss()
                            onJumpToAlbum(track.album)
                        },
                        label = { Text("Album", maxLines = 1, softWrap = false) },
                        leadingIcon = { Icon(Icons.Default.Album, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    AssistChip(
                        onClick = {
                            onDismiss()
                            onJumpToArtist(track.artist)
                        },
                        label = { Text("Artist", maxLines = 1, softWrap = false) },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    AssistChip(
                        onClick = {
                            onDismiss()
                            onJumpToFolder(track)
                        },
                        label = { Text("Folder", maxLines = 1, softWrap = false) },
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Seek Slider
                val duration = track.durationMs.coerceAtLeast(1L)
                Slider(
                    value = state.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { onSeekTo(it.toLong()) },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDurationMs(state.positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = formatDurationMs(track.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3-State Random & 3-State Loop Controls + Transport
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = onCycleRandom) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "Cycle 3-State Random",
                                tint = when (state.randomMode) {
                                    RandomMode.OFF -> Color.Gray
                                    RandomMode.SHUFFLE_POOL -> SamsungBlue
                                    RandomMode.TRUE_RANDOM -> WinampAmberGold
                                }
                            )
                        }
                        Text(
                            text = formatRandomLabel(state.randomMode),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    IconButton(onClick = onSkipPrevious) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
                    }

                    Button(
                        onClick = onTogglePlayPause,
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(onClick = onSkipNext) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = onCycleLoop) {
                            Icon(
                                imageVector = if (state.loopMode == LoopMode.FILE_LOOP) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                contentDescription = "Cycle 3-State Loop",
                                tint = when (state.loopMode) {
                                    LoopMode.NO_LOOP -> Color.Gray
                                    LoopMode.QUEUE_LOOP -> WinampLedGreen
                                    LoopMode.FILE_LOOP -> WinampAmberGold
                                }
                            )
                        }
                        Text(
                            text = formatLoopLabel(state.loopMode),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Resolves the target list index for [letter] against [sortKeys] (where each entry in [sortKeys]
 * is the active field used for sorting: Title, Artist, Album, or Topmost Folder Name).
 *
 * 1. First checks for an exact leading letter match (e.g. 'Q' -> first item whose sort key starts with 'Q').
 * 2. If no item starts with that exact letter, falls back to the first item whose leading letter is >= [letter].
 */
fun findIndexForAlphabetLetter(sortKeys: List<String>, letter: Char): Int {
    if (sortKeys.isEmpty()) return -1
    fun leadingChar(raw: String): Char {
        val trimmed = raw.trimStart { it <= ' ' || it == '/' || it == '\\' }
        val first = trimmed.firstOrNull()?.uppercaseChar() ?: '#'
        return if (first in 'A'..'Z') first else '#'
    }
    if (letter == '#') {
        val exactHash = sortKeys.indexOfFirst { leadingChar(it) == '#' }
        return if (exactHash >= 0) exactHash else 0
    }
    val exactIdx = sortKeys.indexOfFirst { leadingChar(it) == letter }
    if (exactIdx >= 0) return exactIdx
    val fallbackIdx = sortKeys.indexOfFirst {
        val c = leadingChar(it)
        c in 'A'..'Z' && c >= letter
    }
    return if (fallbackIdx >= 0) fallbackIdx else sortKeys.lastIndex
}

/**
 * Combined Proportional Draggable Scrollbar + Sort-Aware Fast Alphabet Quick Access Rail.
 * - Left rail: Draggable proportional scrollbar thumb showing current position in the list.
 * - Right rail: A..Z # fast alphabet index with a floating letter indicator bubble.
 * - Dynamically adapts to any height without overflowing or overlapping list items.
 */
@Composable
fun FastAlphabetScrollbar(
    items: List<String>,
    onLetterSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState? = null
) {
    if (items.isEmpty()) return
    val alphabet = remember { ('A'..'Z').toList() + '#' }
    var activeBubbleLetter by remember { mutableStateOf<Char?>(null) }
    var bubbleYOffsetPx by remember { mutableStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(34.dp)
            .padding(vertical = 6.dp, horizontal = 2.dp)
    ) {
        val density = LocalDensity.current
        val railHeightPx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
        val showStep = if (maxHeight < 340.dp) 2 else 1

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Proportional Draggable Scrollbar Track & Thumb
            val totalCount = items.size.coerceAtLeast(1)
            val firstVisible = listState?.firstVisibleItemIndex ?: 0
            val visibleCount = listState?.layoutInfo?.visibleItemsInfo?.size?.coerceAtLeast(1) ?: 10
            val thumbFraction = (visibleCount.toFloat() / totalCount.toFloat()).coerceIn(0.06f, 0.5f)
            val scrollFraction = (firstVisible.toFloat() / (totalCount - visibleCount).coerceAtLeast(1).toFloat())
                .coerceIn(0f, 1f)

            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(8.dp)
                    .pointerInput(totalCount) {
                        detectVerticalDragGestures { change, _ ->
                            change.consume()
                            val frac = (change.position.y / size.height.toFloat()).coerceIn(0f, 0.999f)
                            val targetIdx = (frac * totalCount).toInt().coerceIn(0, totalCount - 1)
                            onLetterSelected(targetIdx)
                        }
                    }
                    .pointerInput(totalCount) {
                        detectTapGestures { offset ->
                            val frac = (offset.y / size.height.toFloat()).coerceIn(0f, 0.999f)
                            val targetIdx = (frac * totalCount).toInt().coerceIn(0, totalCount - 1)
                            onLetterSelected(targetIdx)
                        }
                    }
            ) {
                val trackWidth = 3.dp.toPx()
                val trackX = (size.width - trackWidth) / 2f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.12f),
                    topLeft = Offset(trackX, 0f),
                    size = Size(trackWidth, size.height),
                    cornerRadius = CornerRadius(trackWidth / 2f, trackWidth / 2f)
                )
                val thumbH = (size.height * thumbFraction).coerceAtLeast(18.dp.toPx())
                val maxThumbTop = (size.height - thumbH).coerceAtLeast(0f)
                val thumbTop = maxThumbTop * scrollFraction
                drawRoundRect(
                    color = SamsungBlue.copy(alpha = 0.85f),
                    topLeft = Offset(0f, thumbTop),
                    size = Size(size.width, thumbH),
                    cornerRadius = CornerRadius(size.width / 2f, size.width / 2f)
                )
            }

            Spacer(modifier = Modifier.width(3.dp))

            // 2. Fast Alphabet Quick Access Rail (A..Z #)
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF141824).copy(alpha = 0.75f))
                    .pointerInput(items) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                val fraction = (offset.y / size.height.toFloat()).coerceIn(0f, 0.999f)
                                val letter = alphabet[(fraction * alphabet.size).toInt()]
                                activeBubbleLetter = letter
                                bubbleYOffsetPx = offset.y
                                val idx = findIndexForAlphabetLetter(items, letter)
                                if (idx >= 0) onLetterSelected(idx)
                            },
                            onDragEnd = { activeBubbleLetter = null },
                            onDragCancel = { activeBubbleLetter = null },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                val fraction = (change.position.y / size.height.toFloat()).coerceIn(0f, 0.999f)
                                val letter = alphabet[(fraction * alphabet.size).toInt()]
                                activeBubbleLetter = letter
                                bubbleYOffsetPx = change.position.y
                                val idx = findIndexForAlphabetLetter(items, letter)
                                if (idx >= 0) onLetterSelected(idx)
                            }
                        )
                    }
                    .pointerInput(items) {
                        detectTapGestures { offset ->
                            val fraction = (offset.y / size.height.toFloat()).coerceIn(0f, 0.999f)
                            val letter = alphabet[(fraction * alphabet.size).toInt()]
                            val idx = findIndexForAlphabetLetter(items, letter)
                            if (idx >= 0) onLetterSelected(idx)
                        }
                    },
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                alphabet.forEachIndexed { idx, letter ->
                    if (idx % showStep == 0 || letter == 'Z' || letter == '#') {
                        Text(
                            text = letter.toString(),
                            fontSize = if (showStep == 1) 8.5.sp else 9.sp,
                            lineHeight = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (activeBubbleLetter == letter) WinampAmberGold else SamsungBlue,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // Floating Letter Indicator Bubble when scrubbing the alphabet rail
        activeBubbleLetter?.let { currentLetter ->
            val bubbleSizePx = with(density) { 48.dp.toPx() }
            val clampedY = (bubbleYOffsetPx - bubbleSizePx / 2f)
                .coerceIn(0f, (railHeightPx - bubbleSizePx).coerceAtLeast(0f))
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = -with(density) { 58.dp.roundToPx() },
                            y = clampedY.roundToInt()
                        )
                    }
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(SamsungBlue)
                    .border(2.dp, WinampAmberGold, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentLetter.toString(),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

fun formatRandomLabel(mode: RandomMode): String = when (mode) {
    RandomMode.OFF -> "RND:OFF"
    RandomMode.SHUFFLE_POOL -> "SHUFFLE"
    RandomMode.TRUE_RANDOM -> "TRUE RND"
}

fun formatLoopLabel(mode: LoopMode): String = when (mode) {
    LoopMode.NO_LOOP -> "LOOP:OFF"
    LoopMode.QUEUE_LOOP -> "LOOP:ALL"
    LoopMode.FILE_LOOP -> "LOOP:ONE"
}

fun formatDurationMs(durationMs: Long): String {
    val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", min, sec)
}
