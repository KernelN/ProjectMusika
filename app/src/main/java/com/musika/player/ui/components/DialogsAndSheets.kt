package com.musika.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.musika.player.core.model.Track
import com.musika.player.data.metadata.MetadataWriteDialogState
import com.musika.player.data.metadata.WriteConfirmationStep
import com.musika.player.player.DualQueueEngine
import com.musika.player.ui.MultiFolderSheetState
import com.musika.player.ui.theme.SamsungBlue
import com.musika.player.ui.theme.WinampAmberGold
import com.musika.player.ui.theme.WinampLedGreen

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
                        fontWeight = FontWeight.Bold
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
                    Text(if (sheetState.showExpandedPaths) "Compact" else "Expand Paths")
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
                                fontWeight = FontWeight.SemiBold
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
                    when (state.currentStep) {
                        WriteConfirmationStep.STEP_1_REVIEW_DIFF -> "Approve Diff (Next)"
                        WriteConfirmationStep.STEP_2_FIRST_ARE_YOU_SURE -> "Yes, I'm Sure (Step 2/3)"
                        WriteConfirmationStep.STEP_3_FINAL_ARE_YOU_SURE -> "Write to Local Files Now"
                        WriteConfirmationStep.COMPLETED_READY_TO_WRITE -> "Done"
                    }
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
                        Text("Back")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

/**
 * Dual Queue Bottom Sheet (Specification #8):
 * Displays both the Spotify-style FIFO Priority Queue ("Play Next") and the
 * Winamp-style Standard Queue ("Add at End").
 */
@Composable
fun DualQueueBottomSheet(
    state: DualQueueEngine.EngineState,
    onRemovePriorityIndex: (Int) -> Unit,
    onClearPriorityQueue: () -> Unit,
    onSelectTrackFromStandard: (Track) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
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
                    color = WinampAmberGold
                )
                if (state.priorityQueue.isNotEmpty()) {
                    TextButton(onClick = onClearPriorityQueue) {
                        Text("Clear Priority")
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
                    modifier = Modifier.heightIn(max = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(state.priorityQueue) { idx, track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
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
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            IconButton(onClick = { onRemovePriorityIndex(idx) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Standard Context Queue (${state.standardQueue.size} tracks)",
                style = MaterialTheme.typography.labelLarge,
                color = SamsungBlue
            )

            LazyColumn(
                modifier = Modifier.heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(state.standardQueue) { idx, track ->
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
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${idx + 1}. ${track.title}",
                            modifier = Modifier.weight(1f),
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) WinampLedGreen else Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.audioQuality.format,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = WinampAmberGold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Scoped Musical Root Folder & Full-Phone Scan Toggle Settings Dialog (Specification #2).
 * "Always scan entire phone for music" is OFF by default.
 */
@Composable
fun SettingsDialog(
    currentRootPath: String,
    alwaysScanEntirePhone: Boolean,
    onSaveRootPath: (String) -> Unit,
    onToggleAlwaysScanEntirePhone: (Boolean) -> Unit,
    onRequestStoragePermissions: () -> Unit,
    onDismiss: () -> Unit
) {
    var editedPath by remember(currentRootPath) { mutableStateOf(currentRootPath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Musical Root Folder & Scanner Settings")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Scanner traverses strictly from your Musical Root Folder down unless full-phone scanning is enabled below.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = editedPath,
                    onValueChange = { editedPath = it },
                    label = { Text("Musical Root Folder Path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
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
                            text = "OFF by default (scoped strictly to Musical Root Folder)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = alwaysScanEntirePhone,
                        onCheckedChange = onToggleAlwaysScanEntirePhone
                    )
                }
                OutlinedButton(
                    onClick = onRequestStoragePermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant All-Files / Audio Storage Permission")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveRootPath(editedPath)
                    onDismiss()
                }
            ) {
                Text("Save & Rescan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
