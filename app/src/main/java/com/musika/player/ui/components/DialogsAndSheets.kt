package com.musika.player.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musika.player.core.model.DeduplicatedTrackGroup
import com.musika.player.core.model.NowPlayingArtworkStyle
import com.musika.player.core.model.Playlist
import com.musika.player.core.model.RandomMode
import com.musika.player.core.model.Track
import com.musika.player.core.sync.OnlineControlCoordinator
import com.musika.player.core.sync.RemoteSyncContract
import com.musika.player.data.metadata.MetadataWriteDialogState
import com.musika.player.data.metadata.WriteConfirmationStep
import com.musika.player.player.DualQueueEngine
import com.musika.player.ui.MultiFolderSheetState
import com.musika.player.ui.theme.SamsungBlue
import com.musika.player.ui.theme.WinampAmberGold
import com.musika.player.ui.theme.WinampLedGreen
import java.util.Locale

/**
 * Multi-Folder Disambiguation Bottom Sheet (Specification #4):
 * Opens as a Compact Bottom Sheet by default when an Album or Artist spans multiple folders,
 * with a toggleable expanded path view.
 */
@Composable
fun MultiFolderBottomSheet(
    sheetState: MultiFolderSheetState,
    onToggleExpandedPaths: () -> Unit,
    onSelectFolder: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Folders for ${sheetState.sourceTitle}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (sheetState.showExpandedPaths) "Expanded Full Path View" else "Compact View (Default)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onToggleExpandedPaths) {
                    Icon(
                        imageVector = if (sheetState.showExpandedPaths) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                        contentDescription = "Toggle Expanded Path View"
                    )
                    Text(
                        text = if (sheetState.showExpandedPaths) "Compact" else "Expand",
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(sheetState.folderPaths, key = { it }) { folderPath ->
                    val folderName = folderPath.substringAfterLast('/').substringAfterLast('\\')
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onSelectFolder(folderPath) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = SamsungBlue
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        ) {
                            Text(
                                text = folderName.ifEmpty { folderPath },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (sheetState.showExpandedPaths) {
                                Text(
                                    text = folderPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Format Variant Picker Dialog (Specification #6):
 * Shown when tapping the format badge (e.g. "FLAC +1") to manually pick an alternate extension.
 */
@Composable
fun FormatVariantPickerDialog(
    group: DeduplicatedTrackGroup,
    onSelectVariant: (Track) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Select Audio Format Variant")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${group.primaryTrack.title} — ${group.primaryTrack.artist}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                group.allVariants.forEachIndexed { index, variant ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onSelectVariant(variant) }
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (index == 0) {
                                    "${variant.fileExtension.uppercase()} (Default Highest Quality)"
                                } else {
                                    variant.fileExtension.uppercase()
                                },
                                fontWeight = FontWeight.Bold,
                                color = if (index == 0) WinampAmberGold else Color.White
                            )
                            Text(
                                text = variant.audioQuality.telemetrySummary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = WinampLedGreen
                            )
                            Text(
                                text = variant.fileName,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Single Dialog Flow with Multiple "Are you sure?" Confirmation Steps (Specification #7):
 * Supports per-track and per-album confirmation with a before/after diff before writing
 * approved tags into local files.
 */
@Composable
fun MetadataWriteConfirmationDialog(
    state: MetadataWriteDialogState,
    onConfirmStep: () -> Unit,
    onStepBack: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = state.promptTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Confirmation Progress: Step ${state.stepNumber} of ${state.totalSteps}",
                    color = WinampAmberGold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                when (state.currentStep) {
                    WriteConfirmationStep.STEP_1_REVIEW_DIFF -> {
                        Text(
                            text = "Review the internet-matched tag changes below (Before -> After):",
                            style = MaterialTheme.typography.bodySmall
                        )
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(state.diffs, key = { it.filePath }) { diff ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = "Title: ${diff.beforeTitle} → ${diff.afterTitle}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Artist: ${diff.beforeArtist} → ${diff.afterArtist}",
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "Album: ${diff.beforeAlbum} → ${diff.afterAlbum}",
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "Year: ${diff.beforeYear ?: "-"} → ${diff.afterYear ?: "-"}",
                                        fontSize = 11.sp,
                                        color = WinampLedGreen
                                    )
                                }
                            }
                        }
                    }
                    WriteConfirmationStep.STEP_2_FIRST_ARE_YOU_SURE -> {
                        Text(
                            text = "Are you sure you want to write these approved web tags into ${state.diffs.size} local audio file(s)?",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    WriteConfirmationStep.STEP_3_FINAL_ARE_YOU_SURE -> {
                        Text(
                            text = "FINAL CHECK — Are you sure? Proceeding will permanently update the embedded metadata tags on disk.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WinampAmberGold,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    WriteConfirmationStep.COMPLETED_READY_TO_WRITE -> {
                        Text("Writing tags to local files...")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirmStep) {
                Text(
                    text = when (state.currentStep) {
                        WriteConfirmationStep.STEP_1_REVIEW_DIFF -> "Approve Diff (Next)"
                        WriteConfirmationStep.STEP_2_FIRST_ARE_YOU_SURE -> "Yes, I'm Sure (2/3)"
                        WriteConfirmationStep.STEP_3_FINAL_ARE_YOU_SURE -> "Write to Files Now"
                        WriteConfirmationStep.COMPLETED_READY_TO_WRITE -> "Done"
                    },
                    maxLines = 1,
                    softWrap = false
                )
            }
        },
        dismissButton = {
            Row {
                if (state.stepNumber > 1) {
                    OutlinedButton(
                        onClick = onStepBack,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text("Back", maxLines = 1, softWrap = false)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        }
    )
}

/**
 * Dual Queue Bottom Sheet (Specification #8 + Queue Editor & Save Queue as Playlist):
 * - Displays the Spotify-style FIFO Priority Queue ("Play Next") with Move Up/Down & Remove.
 * - Displays the active upcoming Context Queue (Standard Queue when RND:OFF, Shuffle Pool when SHUFFLE)
 *   with free modification (Move Up, Move Down, Remove, Clear) whenever Random != TRUE_RANDOM.
 * - When Random == TRUE_RANDOM, clearly explains that True Random is memoryless on each track change.
 * - Includes 1-tap "Save Queue as Playlist" conversion.
 */
@Composable
fun DualQueueBottomSheet(
    state: DualQueueEngine.EngineState,
    onMovePriorityTrack: (Int, Int) -> Unit,
    onRemovePriorityIndex: (Int) -> Unit,
    onClearPriorityQueue: () -> Unit,
    onMoveContextTrack: (Int, Int) -> Unit,
    onRemoveContextIndex: (Int) -> Unit,
    onClearContextQueue: () -> Unit,
    onSelectTrackFromStandard: (Track) -> Unit,
    onSaveQueueAsPlaylist: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showSavePlaylistPrompt by remember { mutableStateOf(false) }
    var newPlaylistName by remember {
        mutableStateOf(
            state.activeContextName?.let { "Queue - $it" } ?: "Saved Queue"
        )
    }

    val activeContextQueue = remember(state.randomMode, state.standardQueue, state.shufflePool) {
        when (state.randomMode) {
            RandomMode.OFF -> state.standardQueue
            RandomMode.SHUFFLE_POOL -> state.shufflePool.ifEmpty { state.standardQueue }
            RandomMode.TRUE_RANDOM -> emptyList()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Dual Playback Queue",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${formatRandomLabel(state.randomMode)} • ${formatLoopLabel(state.loopMode)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = WinampLedGreen
                    )
                }
                OutlinedButton(
                    onClick = { showSavePlaylistPrompt = !showSavePlaylistPrompt }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Save as Playlist",
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            if (showSavePlaylistPrompt) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("Playlist Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (newPlaylistName.isNotBlank()) {
                                onSaveQueueAsPlaylist(newPlaylistName.trim())
                                showSavePlaylistPrompt = false
                            }
                        }
                    ) {
                        Text("Save", maxLines = 1, softWrap = false)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Priority Queue (FIFO Play Next)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Priority Queue — Play Next (FIFO: ${state.priorityQueue.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = WinampAmberGold,
                    modifier = Modifier.weight(1f)
                )
                if (state.priorityQueue.isNotEmpty()) {
                    TextButton(onClick = onClearPriorityQueue) {
                        Text("Clear", maxLines = 1, softWrap = false)
                    }
                }
            }

            if (state.priorityQueue.isEmpty()) {
                Text(
                    text = "No immediate tracks queued. Use 'Play Next' on any track.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 150.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(state.priorityQueue) { idx, track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "#${idx + 1} ${track.title}",
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${track.artist} • ${track.audioQuality.format}",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { if (idx > 0) onMovePriorityTrack(idx, idx - 1) },
                                enabled = idx > 0,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                            }
                            IconButton(
                                onClick = {
                                    if (idx < state.priorityQueue.lastIndex) onMovePriorityTrack(idx, idx + 1)
                                },
                                enabled = idx < state.priorityQueue.lastIndex,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                            }
                            IconButton(
                                onClick = { onRemovePriorityIndex(idx) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (state.randomMode == RandomMode.TRUE_RANDOM) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(WinampAmberGold.copy(alpha = 0.14f))
                        .border(1.dp, WinampAmberGold.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "True Random Active (Memoryless)",
                        style = MaterialTheme.typography.labelLarge,
                        color = WinampAmberGold,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Upcoming context order is not fixed in True Random mode — each track change picks randomly from ${state.standardQueue.size} context tracks after draining the Priority Queue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (state.randomMode == RandomMode.SHUFFLE_POOL) {
                            "Shuffle Pool Queue (${activeContextQueue.size} tracks)"
                        } else {
                            "Standard Context Queue (${activeContextQueue.size} tracks)"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = SamsungBlue,
                        modifier = Modifier.weight(1f)
                    )
                    if (activeContextQueue.size > 1) {
                        TextButton(onClick = onClearContextQueue) {
                            Text("Clear", maxLines = 1, softWrap = false)
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(activeContextQueue) { idx, track ->
                        val isCurrent = state.currentTrack?.filePath == track.filePath
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isCurrent) SamsungBlue.copy(alpha = 0.25f)
                                    else Color.Transparent
                                )
                                .clickable { onSelectTrackFromStandard(track) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${idx + 1}. ${track.title}",
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isCurrent) WinampLedGreen else Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${track.artist} • ${track.audioQuality.format}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { if (idx > 0) onMoveContextTrack(idx, idx - 1) },
                                enabled = idx > 0,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                            }
                            IconButton(
                                onClick = {
                                    if (idx < activeContextQueue.lastIndex) onMoveContextTrack(idx, idx + 1)
                                },
                                enabled = idx < activeContextQueue.lastIndex,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                            }
                            IconButton(
                                onClick = { onRemoveContextIndex(idx) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove from Queue")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Dialog to add 1 or more tracks (Song, Album, or Folder) into an existing Playlist
 * or create a new Playlist on the spot.
 */
@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    candidateTracks: List<Track>,
    onAddToExistingPlaylist: (Long, String) -> Unit,
    onCreateNewPlaylistWithTracks: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add ${candidateTracks.size} Track(s) to Playlist",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New Playlist Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (newName.isNotBlank()) {
                                onCreateNewPlaylistWithTracks(newName.trim())
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Create", maxLines = 1, softWrap = false)
                    }
                }

                if (playlists.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = "Or choose an existing playlist:",
                        style = MaterialTheme.typography.labelMedium,
                        color = SamsungBlue
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(playlists, key = { it.id }) { pl ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { onAddToExistingPlaylist(pl.id, pl.name) }
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = pl.name,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${pl.trackCount} tracks",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WinampLedGreen
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Settings Dialog with:
 * 1. Phone Native Folder Picker (ACTION_OPEN_DOCUMENT_TREE) + collapsible manual path backup
 * 2. HUD & Font Size Scale (0.85x Compact .. 1.00x Default .. 1.40x Extra Large)
 * 3. Track Artwork Display Customization (4 Now Playing styles + list thumbnail toggle)
 * 4. "Always scan entire phone for music" toggle (OFF by default)
 */
@Composable
fun SettingsDialog(
    currentRootPath: String,
    alwaysScanEntirePhone: Boolean,
    artworkStyle: NowPlayingArtworkStyle,
    showListTrackThumbnails: Boolean,
    hudScale: Float,
    allowLanStreamingForMissingTracks: Boolean = false,
    supabaseUrl: String = "",
    supabaseAnonKey: String = "",
    onLaunchNativeFolderPicker: () -> Unit,
    onSaveRootPath: (String) -> Unit,
    onToggleAlwaysScanEntirePhone: (Boolean) -> Unit,
    onSelectArtworkStyle: (NowPlayingArtworkStyle) -> Unit,
    onToggleListTrackThumbnails: (Boolean) -> Unit,
    onHudScaleChanged: (Float) -> Unit,
    onToggleAllowLanStreaming: (Boolean) -> Unit = {},
    onSaveSupabaseConfig: (String, String) -> Unit = { _, _ -> },
    onRequestStoragePermissions: () -> Unit,
    onDismiss: () -> Unit
) {
    var editedPath by remember(currentRootPath) { mutableStateOf(currentRootPath) }
    var showManualPathField by remember { mutableStateOf(false) }
    var editedSbUrl by remember(supabaseUrl) { mutableStateOf(supabaseUrl) }
    var editedSbKey by remember(supabaseAnonKey) { mutableStateOf(supabaseAnonKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Musika Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Native Root Music Folder Selector
                Text(
                    text = "Musical Root Folder",
                    style = MaterialTheme.typography.labelLarge,
                    color = SamsungBlue,
                    fontWeight = FontWeight.Bold
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, SamsungBlue.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = editedPath,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = WinampLedGreen
                    )
                    Button(
                        onClick = onLaunchNativeFolderPicker,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Choose Folder (Phone Native Picker)",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(
                        onClick = { showManualPathField = !showManualPathField },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(
                            text = if (showManualPathField) "Hide Manual Path Input" else "Edit Path Manually (Advanced)",
                            fontSize = 11.sp
                        )
                    }
                    if (showManualPathField) {
                        OutlinedTextField(
                            value = editedPath,
                            onValueChange = { editedPath = it },
                            label = { Text("Folder Path") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 2. Full-Phone Scan Toggle (OFF by default)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Always scan entire phone for music",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "OFF by default (scans strictly from Root Folder)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = alwaysScanEntirePhone,
                        onCheckedChange = onToggleAlwaysScanEntirePhone
                    )
                }

                HorizontalDivider()

                // 3. HUD & Font Size Scaling (Default 1.0x)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HUD & Font Size Scale",
                        style = MaterialTheme.typography.labelLarge,
                        color = WinampLedGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format(Locale.US, "%.2fx", hudScale) +
                                if (kotlin.math.abs(hudScale - 1.0f) < 0.03f) " (Default)" else "",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = WinampAmberGold
                    )
                }
                Slider(
                    value = hudScale,
                    onValueChange = { onHudScaleChanged(it) },
                    valueRange = 0.85f..1.40f,
                    steps = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        0.85f to "0.85x",
                        1.00f to "1.0x Default",
                        1.15f to "1.15x",
                        1.30f to "1.30x"
                    ).forEach { (preset, label) ->
                        FilterChip(
                            selected = kotlin.math.abs(hudScale - preset) < 0.03f,
                            onClick = { onHudScaleChanged(preset) },
                            label = { Text(label, fontSize = 10.sp, maxLines = 1, softWrap = false) }
                        )
                    }
                }

                HorizontalDivider()

                // 4. Track Image / Artwork Customization
                Text(
                    text = "Track Artwork & Display Style",
                    style = MaterialTheme.typography.labelLarge,
                    color = WinampAmberGold,
                    fontWeight = FontWeight.Bold
                )
                NowPlayingArtworkStyle.entries.forEach { style ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSelectArtworkStyle(style) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = artworkStyle == style,
                            onClick = { onSelectArtworkStyle(style) }
                        )
                        Text(
                            text = style.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Show track cover thumbnails in lists",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = showListTrackThumbnails,
                        onCheckedChange = onToggleListTrackThumbnails
                    )
                }

                HorizontalDivider()

                // 5. Stage 2 Network, LAN Streaming Fallback & Supabase Project
                Text(
                    text = "Online Sync & LAN Transfer Rules",
                    style = MaterialTheme.typography.labelLarge,
                    color = WinampAmberGold,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Allow LAN audio streaming for missing tracks",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "OFF by default. Keep OFF for strict local-match-only Move Music Here.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = allowLanStreamingForMissingTracks,
                        onCheckedChange = onToggleAllowLanStreaming
                    )
                }

                OutlinedTextField(
                    value = editedSbUrl,
                    onValueChange = { editedSbUrl = it },
                    label = { Text("Supabase Project URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editedSbKey,
                    onValueChange = { editedSbKey = it },
                    label = { Text("Supabase Anon Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { onSaveSupabaseConfig(editedSbUrl, editedSbKey) },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Save Supabase Keys", fontSize = 11.sp, maxLines = 1, softWrap = false)
                }

                HorizontalDivider()

                OutlinedButton(
                    onClick = onRequestStoragePermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Grant Storage & Notification Permissions",
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (editedSbUrl != supabaseUrl || editedSbKey != supabaseAnonKey) {
                        onSaveSupabaseConfig(editedSbUrl, editedSbKey)
                    }
                    onSaveRootPath(editedPath)
                    onDismiss()
                }
            ) {
                Text("Save & Rescan", maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", maxLines = 1, softWrap = false)
            }
        }
    )
}

/**
 * Stage 2: Online Device Control, Account Sync Mode & Metadata-Verified Playback Transfer Sheet.
 * Supports:
 * 1. Remote control of the currently playing device.
 * 2. Switching between SYNCED_PLAYBACK (kept synchronized) and INDEPENDENT_CONCURRENT (2+ devices at the same time).
 * 3. Moving active music from one device to another strictly when both devices have a song with matching metadata loaded.
 */
private fun renderQrBitmap(content: String, sizePx: Int = 360): Bitmap? {
    if (content.isBlank()) return null
    return runCatching {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565).apply {
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.WHITE else android.graphics.Color.rgb(11, 14, 20))
                }
            }
        }
    }.getOrNull()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OnlineDeviceControlBottomSheet(
    onlineState: OnlineControlCoordinator.OnlineControlState,
    playbackState: DualQueueEngine.EngineState,
    qrPairPayload: RemoteSyncContract.QrPairPayload? = null,
    onSelectSyncMode: (RemoteSyncContract.AccountDeviceSyncMode) -> Unit,
    onSendRemoteCommand: (RemoteSyncContract.SyncCommand, String) -> Unit,
    onMirrorCatalogToDevice: (String) -> Unit,
    onClearPeerCatalog: (String) -> Unit,
    onTransferPlaybackToDevice: (String) -> Unit,
    onLaunchQrScanner: () -> Unit = {},
    onPairViaQrOrPin: (String, String?) -> Unit = { _, _ -> },
    onConnectManualLanHost: (String, Int) -> Unit = { _, _ -> },
    onSignInUsername: (String, String) -> Unit = { _, _ -> },
    onSignUpUsername: (String, String) -> Unit = { _, _ -> },
    onSignOutAccount: () -> Unit = {},
    onToggleListenLocallyInSynced: (Boolean) -> Unit = {},
    onSelectBrowsingDevice: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val currentTrackDescriptor = playbackState.currentTrack?.let { track ->
        RemoteSyncContract.RemoteTrackDescriptor.fromTrack(track)
    }
    var pairInput by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("8765") }
    var authUsername by remember { mutableStateOf(if (onlineState.username == "MusikaUser") "" else onlineState.username) }
    var authPassword by remember { mutableStateOf("") }
    var isSignUpMode by remember { mutableStateOf(false) }

    val qrLink = remember(qrPairPayload) { qrPairPayload?.toQrDeepLink().orEmpty() }
    val qrBitmap = remember(qrLink) { renderQrBitmap(qrLink) }
    val transportLabel = when (onlineState.transportMode) {
        RemoteSyncContract.ConnectionTransportMode.LAN_DIRECT -> "LAN DIRECT (${onlineState.connectedLanPeer ?: "Active"})"
        RemoteSyncContract.ConnectionTransportMode.ONLINE_SUPABASE -> "SUPABASE ONLINE"
        RemoteSyncContract.ConnectionTransportMode.OFFLINE_LOCAL -> "LOCAL OFFLINE (LAN Server Ready)"
    }
    val transportColor = when (onlineState.transportMode) {
        RemoteSyncContract.ConnectionTransportMode.LAN_DIRECT -> WinampLedGreen
        RemoteSyncContract.ConnectionTransportMode.ONLINE_SUPABASE -> SamsungBlue
        RemoteSyncContract.ConnectionTransportMode.OFFLINE_LOCAL -> WinampAmberGold
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Musika Connect — LAN & Online Sync",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$transportLabel • User: ${onlineState.username}",
                        style = MaterialTheme.typography.labelSmall,
                        color = transportColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    onlineState.lanHost?.let { ip ->
                        Text(
                            text = "Browser LAN Portal: http://$ip:${onlineState.lanPort}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = WinampLedGreen
                        )
                    }
                }
            }

            onlineState.statusMessage?.let { status ->
                Text(
                    text = status,
                    fontSize = 11.sp,
                    color = WinampAmberGold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(8.dp)
                )
            }

            // 1. Account Sync vs. Independent Concurrent Mode Selector
            Text(
                text = "Account Multi-Device Playback Mode",
                style = MaterialTheme.typography.labelLarge,
                color = SamsungBlue,
                fontWeight = FontWeight.Bold
            )
            RemoteSyncContract.AccountDeviceSyncMode.entries.forEach { mode ->
                val isSelected = onlineState.syncMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            1.dp,
                            if (isSelected) SamsungBlue else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            RoundedCornerShape(8.dp)
                        )
                        .background(
                            if (isSelected) SamsungBlue.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                        .clickable { onSelectSyncMode(mode) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelectSyncMode(mode) }
                    )
                    Column(modifier = Modifier.padding(start = 6.dp).weight(1f)) {
                        Text(
                            text = mode.displayName,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = mode.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (onlineState.syncMode == RemoteSyncContract.AccountDeviceSyncMode.SYNCED_PLAYBACK) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Play Audio Here Too (Monitor Mode)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "OFF by default to avoid double-speaker echo when synced to PC.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = onlineState.listenLocallyWhenSyncedTargetIsRemote,
                        onCheckedChange = onToggleListenLocallyInSynced
                    )
                }
            }

            HorizontalDivider()

            // Instant QR / 6-Digit PIN & Manual LAN Probe
            Text(
                text = "Instant Pair (QR / 6-Digit LAN PIN) & IP Probe",
                style = MaterialTheme.typography.labelLarge,
                color = WinampAmberGold,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                qrBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Musika Pair QR Code",
                        modifier = Modifier
                            .size(116.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, SamsungBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "LAN PIN: ${onlineState.activePinCode}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = WinampLedGreen
                    )
                    Button(
                        onClick = onLaunchQrScanner,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Scan Web/PC QR Code", fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = pairInput,
                    onValueChange = { pairInput = it },
                    label = { Text("PIN or musika:// link") },
                    singleLine = true,
                    modifier = Modifier.weight(1.4f)
                )
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it },
                    label = { Text("Peer IP") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = manualPort,
                    onValueChange = { manualPort = it },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.width(84.dp)
                )
                Button(
                    onClick = { onPairViaQrOrPin(pairInput, manualHost.ifBlank { null }) },
                    enabled = pairInput.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Pair PIN/QR", fontSize = 11.sp, maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = {
                        if (manualHost.isNotBlank()) {
                            onConnectManualLanHost(manualHost, manualPort.toIntOrNull() ?: 8765)
                        }
                    },
                    enabled = manualHost.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Probe LAN", fontSize = 11.sp, maxLines = 1, softWrap = false)
                }
            }

            HorizontalDivider()

            // Online Supabase Username Account
            Text(
                text = "Online Account (Username + Password)",
                style = MaterialTheme.typography.labelLarge,
                color = SamsungBlue,
                fontWeight = FontWeight.Bold
            )
            if (onlineState.accountId != "musika-account-default" && onlineState.username != "MusikaUser") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Signed in as ${onlineState.username}",
                        color = WinampLedGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    OutlinedButton(onClick = onSignOutAccount) {
                        Text("Sign Out", fontSize = 11.sp)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = authUsername,
                        onValueChange = { authUsername = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = authPassword,
                        onValueChange = { authPassword = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isSignUpMode,
                        onClick = { isSignUpMode = false },
                        label = { Text("Sign In", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = isSignUpMode,
                        onClick = { isSignUpMode = true },
                        label = { Text("Create Account", fontSize = 11.sp) }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            if (isSignUpMode) onSignUpUsername(authUsername, authPassword)
                            else onSignInUsername(authUsername, authPassword)
                        },
                        enabled = onlineState.supabaseConfigured && authUsername.isNotBlank() && authPassword.length >= 6
                    ) {
                        Text(if (isSignUpMode) "Register" else "Login", fontSize = 11.sp)
                    }
                }
                if (!onlineState.supabaseConfigured) {
                    Text(
                        text = "Set Supabase URL + Anon Key in Settings (or scan a configured device QR). Direct LAN works offline!",
                        style = MaterialTheme.typography.labelSmall,
                        color = WinampAmberGold
                    )
                }
            }

            HorizontalDivider()

            // 2. Remote Transport Controls for Active Device
            Text(
                text = "Remote Control (${onlineState.activeControllingDeviceId})",
                style = MaterialTheme.typography.labelLarge,
                color = WinampAmberGold,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onSendRemoteCommand(
                            RemoteSyncContract.SyncCommand.SkipPrevious,
                            onlineState.activeControllingDeviceId
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Prev", maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = {
                        val cmd = if (playbackState.isPlaying) {
                            RemoteSyncContract.SyncCommand.Pause
                        } else {
                            RemoteSyncContract.SyncCommand.Play
                        }
                        onSendRemoteCommand(cmd, onlineState.activeControllingDeviceId)
                    },
                    modifier = Modifier.weight(1.3f)
                ) {
                    Text(
                        text = if (playbackState.isPlaying) "Remote Pause" else "Remote Play",
                        maxLines = 1,
                        softWrap = false
                    )
                }
                OutlinedButton(
                    onClick = {
                        onSendRemoteCommand(
                            RemoteSyncContract.SyncCommand.SkipNext,
                            onlineState.activeControllingDeviceId
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Next", maxLines = 1, softWrap = false)
                }
            }

            HorizontalDivider()

            // 3. Connected Account Devices & Metadata-Verified Cross-Device Music Transfer
            Text(
                text = "Connected Account Devices & Music Transfer",
                style = MaterialTheme.typography.labelLarge,
                color = WinampLedGreen,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Rule: Moving active music to another device is only allowed when both devices have a song with matching metadata loaded.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            onlineState.connectedDevices.forEach { device ->
                val isActiveTarget = onlineState.activeControllingDeviceId == device.deviceId
                val hasMetadataMatch = currentTrackDescriptor != null &&
                        device.hasMatchingTrackMetadataLoaded(currentTrackDescriptor)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            width = 1.dp,
                            color = if (isActiveTarget) WinampLedGreen else SamsungBlue.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${device.deviceName} (${device.deviceType})",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "ID: ${device.deviceId} • Loaded Tracks: ${device.loadedTrackKeys.size}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = if (isActiveTarget) WinampLedGreen else WinampAmberGold
                            )
                        }
                        if (isActiveTarget) {
                            Text(
                                text = "PLAYING HERE",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = WinampLedGreen
                            )
                        }
                    }

                    if (currentTrackDescriptor != null) {
                        Text(
                            text = if (hasMetadataMatch) {
                                "Metadata Verified: '${currentTrackDescriptor.title}' is loaded on this device"
                            } else {
                                "Missing Metadata Match: '${currentTrackDescriptor.title}' is NOT loaded on this device"
                            },
                            fontSize = 11.sp,
                            color = if (hasMetadataMatch) WinampLedGreen else WinampAmberGold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val isBrowsingThis = onlineState.browsingDeviceId == device.deviceId
                        OutlinedButton(
                            onClick = {
                                onSelectBrowsingDevice(device.deviceId)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (isBrowsingThis) "Browsing Now" else "Browse Files",
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (!device.isThisDevice) {
                            OutlinedButton(
                                onClick = {
                                    if (device.loadedTrackKeys.isEmpty()) {
                                        onMirrorCatalogToDevice(device.deviceId)
                                    } else {
                                        onClearPeerCatalog(device.deviceId)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (device.loadedTrackKeys.isEmpty()) {
                                        "Sync Catalog"
                                    } else {
                                        "Clear Cache"
                                    },
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Button(
                            onClick = { onTransferPlaybackToDevice(device.deviceId) },
                            enabled = currentTrackDescriptor != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Move Music Here",
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            onlineState.lastTransferResult?.let { transferResult ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (transferResult.allowed) WinampLedGreen.copy(alpha = 0.15f)
                            else WinampAmberGold.copy(alpha = 0.15f)
                        )
                        .padding(10.dp)
                ) {
                    Text(
                        text = if (transferResult.allowed) "Transfer Succeeded" else "Transfer Blocked (Metadata Guard)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = if (transferResult.allowed) WinampLedGreen else WinampAmberGold
                    )
                    Text(
                        text = transferResult.reason,
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

