package com.musika.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.musika.player.core.model.AudioQuality
import com.musika.player.core.model.LoopMode
import com.musika.player.core.model.RandomMode
import com.musika.player.core.model.Track
import com.musika.player.player.DualQueueEngine
import com.musika.player.ui.theme.SamsungBlue
import com.musika.player.ui.theme.WinampAmberGold
import com.musika.player.ui.theme.WinampDarkBezel
import com.musika.player.ui.theme.WinampLedGreen
import java.util.Locale

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
    cornerRadius: Dp = 8.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(WinampDarkBezel)
            .border(1.dp, fallbackTint.copy(alpha = 0.35f), RoundedCornerShape(cornerRadius)),
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = audioQuality.format.uppercase(Locale.US),
            color = if (audioQuality.isLossless) WinampAmberGold else WinampLedGreen,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
        Text(
            text = audioQuality.bitrateFormatted,
            color = WinampLedGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Text(
            text = audioQuality.sampleRateKhzFormatted,
            color = WinampLedGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Text(
            text = "${audioQuality.bitDepth}bit",
            color = WinampLedGreen.copy(alpha = 0.9f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
    }
}

/**
 * Samsung Music + Winamp Hybrid Mini-Player docked at the bottom of the screen.
 * Displays current track, live Winamp telemetry bar, transport controls, and 1-tap Queue button.
 */
@Composable
fun WinampMiniPlayerBar(
    state: DualQueueEngine.EngineState,
    onExpandNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = state.currentTrack ?: return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onExpandNowPlaying() },
        color = Color(0xFF181C26),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkThumbnail(
                    artworkUri = track.artworkUri,
                    fallbackIcon = Icons.Default.MusicNote,
                    fallbackTint = SamsungBlue,
                    size = 42.dp,
                    cornerRadius = 8.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${track.artist} • ${track.album}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play"
                    )
                }
                IconButton(onClick = onSkipNext) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Skip Next"
                    )
                }
                IconButton(onClick = onOpenQueue) {
                    Box {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Instant Queue Access",
                            tint = if (state.priorityQueue.isNotEmpty()) WinampAmberGold else Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                WinampTelemetryBar(audioQuality = track.audioQuality)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = formatRandomLabel(state.randomMode),
                        color = if (state.randomMode != RandomMode.OFF) SamsungBlue else Color.Gray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = formatLoopLabel(state.loopMode),
                        color = if (state.loopMode != LoopMode.NO_LOOP) WinampLedGreen else Color.Gray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Full-screen Now Playing view with live Winamp Telemetry, 1-tap Cross-Navigation to
 * Album / Artist / Folder, 3-state Random & 3-state Loop state machine controls, and Queue access.
 */
@Composable
fun NowPlayingScreenOverlay(
    state: DualQueueEngine.EngineState,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onOpenQueue) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Open Dual Queue",
                        tint = WinampAmberGold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Winamp Classic Live Telemetry Readout
            WinampTelemetryBar(
                audioQuality = track.audioQuality,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Prominent Cover Artwork (Embedded APIC/FLAC/Folder.jpg or styled fallback)
            ArtworkThumbnail(
                artworkUri = track.artworkUri,
                fallbackIcon = Icons.Default.Album,
                fallbackTint = SamsungBlue,
                size = 180.dp,
                cornerRadius = 16.dp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Track Title, Artist, Album
            Text(
                text = track.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = track.artist,
                style = MaterialTheme.typography.titleMedium,
                color = SamsungBlue,
                modifier = Modifier.clickable {
                    onDismiss()
                    onJumpToArtist(track.artist)
                }
            )
            Text(
                text = "${track.album} (Disc ${track.discNumber}, Track ${track.trackNumber})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable {
                    onDismiss()
                    onJumpToAlbum(track.album)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 1-Tap Cross-Navigation Chips (Song -> Album / Artist / Folder)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {
                        onDismiss()
                        onJumpToAlbum(track.album)
                    },
                    label = { Text("Go to Album") },
                    leadingIcon = { Icon(Icons.Default.Album, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                AssistChip(
                    onClick = {
                        onDismiss()
                        onJumpToArtist(track.artist)
                    },
                    label = { Text("Go to Artist") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                AssistChip(
                    onClick = {
                        onDismiss()
                        onJumpToFolder(track)
                    },
                    label = { Text("Go to Folder") },
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

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
                Text(formatDurationMs(state.positionMs), style = MaterialTheme.typography.labelSmall)
                Text(formatDurationMs(track.durationMs), style = MaterialTheme.typography.labelSmall)
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                        fontFamily = FontFamily.Monospace
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
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Samsung Music Fast Alphabet Scrollbar along the right edge of lists.
 */
@Composable
fun FastAlphabetScrollbar(
    items: List<String>,
    onLetterSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val alphabet = ('A'..'Z').toList() + '#'
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(22.dp)
            .padding(vertical = 8.dp)
            .pointerInput(items) {
                detectVerticalDragGestures { change, _ ->
                    val fraction = (change.position.y / size.height.toFloat()).coerceIn(0f, 0.999f)
                    val charIdx = (fraction * alphabet.size).toInt()
                    val letter = alphabet[charIdx]
                    val targetIdx = items.indexOfFirst { title ->
                        val firstChar = title.firstOrNull()?.uppercaseChar() ?: '#'
                        if (letter == '#') !firstChar.isLetter() else firstChar >= letter
                    }
                    if (targetIdx >= 0) {
                        onLetterSelected(targetIdx)
                    }
                }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        alphabet.forEach { letter ->
            Text(
                text = letter.toString(),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = SamsungBlue,
                modifier = Modifier.clickable {
                    val targetIdx = items.indexOfFirst { title ->
                        val firstChar = title.firstOrNull()?.uppercaseChar() ?: '#'
                        if (letter == '#') !firstChar.isLetter() else firstChar >= letter
                    }
                    if (targetIdx >= 0) {
                        onLetterSelected(targetIdx)
                    }
                }
            )
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
