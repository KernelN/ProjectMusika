package com.musika.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.musika.player.core.model.Album
import com.musika.player.core.model.AlbumSortOption
import com.musika.player.core.model.Artist
import com.musika.player.core.model.DeduplicatedTrackGroup
import com.musika.player.core.model.FolderNode
import com.musika.player.core.model.FolderSortOption
import com.musika.player.core.model.SongSortOption
import com.musika.player.core.model.Track
import com.musika.player.data.local.entity.WebMetadataEntity
import com.musika.player.ui.components.DualQueueBottomSheet
import com.musika.player.ui.components.FastAlphabetScrollbar
import com.musika.player.ui.components.FormatVariantPickerDialog
import com.musika.player.ui.components.MetadataWriteConfirmationDialog
import com.musika.player.ui.components.MultiFolderBottomSheet
import com.musika.player.ui.components.NowPlayingScreenOverlay
import com.musika.player.ui.components.SettingsDialog
import com.musika.player.ui.components.WinampMiniPlayerBar
import com.musika.player.ui.components.formatLoopLabel
import com.musika.player.ui.components.formatRandomLabel
import com.musika.player.ui.theme.SamsungBlue
import com.musika.player.ui.theme.WinampAmberGold
import com.musika.player.ui.theme.WinampLedGreen
import kotlinx.coroutines.launch

@Composable
fun MusikaMainScreen(
    viewModel: MusikaViewModel,
    onRequestStoragePermissions: () -> Unit
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val deduplicatedSongs by viewModel.deduplicatedSongs.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val currentFolderPath by viewModel.currentFolderPath.collectAsState()
    val currentFolderNode by viewModel.currentFolderNode.collectAsState()
    val currentSubfolders by viewModel.currentSubfolders.collectAsState()
    val currentFolderRawTracks by viewModel.currentFolderRawTracks.collectAsState()
    val selectedAlbumDetail by viewModel.selectedAlbumDetail.collectAsState()
    val selectedArtistDetail by viewModel.selectedArtistDetail.collectAsState()
    val selectedAlbumTracks by viewModel.selectedAlbumTracks.collectAsState()
    val selectedArtistTracks by viewModel.selectedArtistTracks.collectAsState()
    val webMetadataMap by viewModel.webMetadataMap.collectAsState()
    val songSort by viewModel.songSortOption.collectAsState()
    val albumSort by viewModel.albumSortOption.collectAsState()
    val folderSort by viewModel.folderSortOption.collectAsState()
    val multiFolderSheetState by viewModel.multiFolderSheetState.collectAsState()
    val variantPickerGroup by viewModel.variantPickerGroup.collectAsState()
    val writeConfirmationState by viewModel.writeConfirmationDialogState.collectAsState()
    val showNowPlayingOverlay by viewModel.showNowPlayingOverlay.collectAsState()
    val showQueueSheet by viewModel.showQueueSheet.collectAsState()
    val showSettingsDialog by viewModel.showSettingsDialog.collectAsState()
    val musicalRootPath by viewModel.musicalRootPath.collectAsState()
    val alwaysScanEntirePhone by viewModel.alwaysScanEntirePhone.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val statusBanner by viewModel.statusBannerMessage.collectAsState()

    val tabs = MusikaTab.entries
    val pagerState = rememberPagerState(
        initialPage = selectedTab.ordinal,
        pageCount = { tabs.size }
    )
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab.ordinal) {
            pagerState.animateScrollToPage(selectedTab.ordinal)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        val targetTab = tabs[pagerState.currentPage]
        if (selectedTab != targetTab) {
            viewModel.selectTab(targetTab)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = "MUSIKA",
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.2.sp
                                )
                                Text(
                                    text = "${formatRandomLabel(playbackState.randomMode)} • ${formatLoopLabel(playbackState.loopMode)}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = WinampLedGreen
                                )
                            }
                        },
                        actions = {
                            TextButton(onClick = { viewModel.cycleRandomMode() }) {
                                Text(
                                    text = formatRandomLabel(playbackState.randomMode),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = WinampAmberGold
                                )
                            }
                            TextButton(onClick = { viewModel.cycleLoopMode() }) {
                                Text(
                                    text = formatLoopLabel(playbackState.loopMode),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = WinampLedGreen
                                )
                            }
                            IconButton(onClick = { viewModel.triggerLibraryScan() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Rescan Root Folder")
                            }
                            IconButton(onClick = { viewModel.setShowSettingsDialog(true) }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    )

                    if (isScanning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    statusBanner?.let { msg ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SamsungBlue.copy(alpha = 0.2f))
                                .clickable { viewModel.clearStatusBanner() }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "Dismiss",
                                style = MaterialTheme.typography.labelSmall,
                                color = WinampAmberGold
                            )
                        }
                    }

                    // Samsung Music Top Swipeable Tabs
                    TabRow(selectedTabIndex = pagerState.currentPage) {
                        tabs.forEachIndexed { idx, tab ->
                            Tab(
                                selected = pagerState.currentPage == idx,
                                onClick = {
                                    viewModel.selectTab(tab)
                                    coroutineScope.launch { pagerState.animateScrollToPage(idx) }
                                },
                                text = {
                                    Text(
                                        text = tab.title,
                                        fontWeight = if (pagerState.currentPage == idx) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }
            },
            bottomBar = {
                WinampMiniPlayerBar(
                    state = playbackState,
                    onExpandNowPlaying = { viewModel.setShowNowPlayingOverlay(true) },
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onSkipNext = { viewModel.skipNext() },
                    onOpenQueue = { viewModel.setShowQueueSheet(true) }
                )
            }
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) { pageIndex ->
                when (tabs[pageIndex]) {
                    MusikaTab.SONGS -> SongsTabPage(
                        groups = deduplicatedSongs,
                        sortOption = songSort,
                        webMetadataMap = webMetadataMap,
                        onSortChanged = viewModel::setSongSort,
                        onPlayGroup = { group ->
                            viewModel.playSongGroup(group, deduplicatedSongs, "All Songs")
                        },
                        onOpenVariantPicker = viewModel::openFormatVariantPicker,
                        onPlayNext = viewModel::enqueuePlayNext,
                        onAddAtEnd = viewModel::enqueueAddAtEnd,
                        onJumpToAlbum = viewModel::jumpToAlbum,
                        onJumpToArtist = viewModel::jumpToArtist,
                        onJumpToFolder = { track ->
                            viewModel.jumpToFolders(listOf(track.parentFolderPath), track.title)
                        },
                        onFetchWebMetadata = viewModel::fetchInternetMetadataForTrack,
                        onWriteMetadataToFile = viewModel::startTrackWriteConfirmation
                    )

                    MusikaTab.ALBUMS -> AlbumsTabPage(
                        albums = albums,
                        selectedAlbumTitle = selectedAlbumDetail,
                        selectedAlbumTracks = selectedAlbumTracks,
                        sortOption = albumSort,
                        webMetadataMap = webMetadataMap,
                        onSortChanged = viewModel::setAlbumSort,
                        onSelectAlbum = viewModel::jumpToAlbum,
                        onCloseAlbumDetail = viewModel::closeAlbumDetail,
                        onPlayAlbumTrack = { group, albumTitle ->
                            viewModel.playSongGroup(group, selectedAlbumTracks, "Album: $albumTitle")
                        },
                        onPlayAllDiscs = viewModel::playAllDiscsForAlbum,
                        onJumpToSiblingDiscFolder = viewModel::openSingleFolderInDirectoryView,
                        onJumpToArtist = viewModel::jumpToArtist,
                        onJumpToAlbumFolders = { album ->
                            viewModel.jumpToFolders(album.folderPaths, album.title)
                        },
                        onOpenVariantPicker = viewModel::openFormatVariantPicker,
                        onPlayNext = viewModel::enqueuePlayNext,
                        onAddAtEnd = viewModel::enqueueAddAtEnd,
                        onFetchAlbumWebMetadata = { albumTitle ->
                            val primary = selectedAlbumTracks.map { it.primaryTrack }
                            viewModel.fetchInternetMetadataForAlbum(albumTitle, primary)
                        },
                        onWriteAlbumMetadata = { albumTitle ->
                            val primary = selectedAlbumTracks.map { it.primaryTrack }
                            viewModel.startAlbumWriteConfirmation(albumTitle, primary)
                        },
                        onFetchTrackWebMetadata = viewModel::fetchInternetMetadataForTrack,
                        onWriteTrackMetadata = viewModel::startTrackWriteConfirmation
                    )

                    MusikaTab.ARTISTS -> ArtistsTabPage(
                        artists = artists,
                        selectedArtistName = selectedArtistDetail,
                        selectedArtistTracks = selectedArtistTracks,
                        webMetadataMap = webMetadataMap,
                        onSelectArtist = viewModel::jumpToArtist,
                        onCloseArtistDetail = viewModel::closeArtistDetail,
                        onPlayArtistTrack = { group, artistName ->
                            viewModel.playSongGroup(group, selectedArtistTracks, "Artist: $artistName")
                        },
                        onJumpToAlbum = viewModel::jumpToAlbum,
                        onJumpToArtistFolders = { artist ->
                            viewModel.jumpToFolders(artist.folderPaths, artist.name)
                        },
                        onOpenVariantPicker = viewModel::openFormatVariantPicker,
                        onPlayNext = viewModel::enqueuePlayNext,
                        onAddAtEnd = viewModel::enqueueAddAtEnd,
                        onFetchTrackWebMetadata = viewModel::fetchInternetMetadataForTrack,
                        onWriteTrackMetadata = viewModel::startTrackWriteConfirmation
                    )

                    MusikaTab.FOLDERS -> FoldersDirectoryTabPage(
                        currentFolderPath = currentFolderPath,
                        currentFolderNode = currentFolderNode,
                        subfolders = currentSubfolders,
                        rawTracks = currentFolderRawTracks,
                        sortOption = folderSort,
                        webMetadataMap = webMetadataMap,
                        onSortChanged = viewModel::setFolderSort,
                        onNavigateUp = { viewModel.navigateUpFolder() },
                        onOpenSubfolder = viewModel::openSingleFolderInDirectoryView,
                        onPlayAllDiscsInFolder = {
                            val name = currentFolderNode?.folderName
                                ?: currentFolderPath.substringAfterLast('/')
                            viewModel.playAllDiscsFromFolder(currentFolderPath, name)
                        },
                        onPlayRawTrack = { track ->
                            val name = currentFolderNode?.folderName
                                ?: currentFolderPath.substringAfterLast('/')
                            viewModel.playRawTrackInFolder(track, currentFolderRawTracks, name)
                        },
                        onPlayNext = viewModel::enqueuePlayNext,
                        onAddAtEnd = viewModel::enqueueAddAtEnd,
                        onJumpToAlbum = viewModel::jumpToAlbum,
                        onJumpToArtist = viewModel::jumpToArtist,
                        onFetchTrackWebMetadata = viewModel::fetchInternetMetadataForTrack,
                        onWriteTrackMetadata = viewModel::startTrackWriteConfirmation
                    )
                }
            }
        }

        if (showNowPlayingOverlay) {
            NowPlayingScreenOverlay(
                state = playbackState,
                onDismiss = { viewModel.setShowNowPlayingOverlay(false) },
                onTogglePlayPause = viewModel::togglePlayPause,
                onSkipPrevious = { viewModel.skipPrevious() },
                onSkipNext = { viewModel.skipNext() },
                onCycleRandom = { viewModel.cycleRandomMode() },
                onCycleLoop = { viewModel.cycleLoopMode() },
                onSeekTo = viewModel::seekTo,
                onOpenQueue = { viewModel.setShowQueueSheet(true) },
                onJumpToAlbum = viewModel::jumpToAlbum,
                onJumpToArtist = viewModel::jumpToArtist,
                onJumpToFolder = { track ->
                    viewModel.jumpToFolders(listOf(track.parentFolderPath), track.title)
                }
            )
        }

        multiFolderSheetState?.let { sheetState ->
            MultiFolderBottomSheet(
                sheetState = sheetState,
                onToggleExpandedPaths = viewModel::toggleMultiFolderSheetExpanded,
                onSelectFolder = viewModel::openSingleFolderInDirectoryView,
                onDismiss = viewModel::dismissMultiFolderSheet
            )
        }

        variantPickerGroup?.let { group ->
            FormatVariantPickerDialog(
                group = group,
                onSelectVariant = { chosen -> viewModel.playSelectedFormatVariant(group, chosen) },
                onDismiss = viewModel::dismissFormatVariantPicker
            )
        }

        writeConfirmationState?.let { dialogState ->
            MetadataWriteConfirmationDialog(
                state = dialogState,
                onConfirmStep = viewModel::confirmNextWriteStep,
                onStepBack = viewModel::stepBackWriteConfirmation,
                onDismiss = viewModel::dismissWriteConfirmation
            )
        }

        if (showQueueSheet) {
            DualQueueBottomSheet(
                state = playbackState,
                onRemovePriorityIndex = { idx -> viewModel.dualQueueEngine.removeFromPriorityQueue(idx) },
                onClearPriorityQueue = { viewModel.dualQueueEngine.clearPriorityQueue() },
                onSelectTrackFromStandard = { track ->
                    viewModel.dualQueueEngine.playFromContext(
                        selectedTrack = track,
                        contextTracks = playbackState.standardQueue,
                        contextName = playbackState.activeContextName
                    )
                },
                onDismiss = { viewModel.setShowQueueSheet(false) }
            )
        }

        if (showSettingsDialog) {
            SettingsDialog(
                currentRootPath = musicalRootPath,
                alwaysScanEntirePhone = alwaysScanEntirePhone,
                onSaveRootPath = viewModel::setMusicalRootFolder,
                onToggleAlwaysScanEntirePhone = viewModel::setAlwaysScanEntirePhone,
                onRequestStoragePermissions = onRequestStoragePermissions,
                onDismiss = { viewModel.setShowSettingsDialog(false) }
            )
        }
    }
}

@Composable
private fun SongsTabPage(
    groups: List<DeduplicatedTrackGroup>,
    sortOption: SongSortOption,
    webMetadataMap: Map<String, WebMetadataEntity>,
    onSortChanged: (SongSortOption) -> Unit,
    onPlayGroup: (DeduplicatedTrackGroup) -> Unit,
    onOpenVariantPicker: (DeduplicatedTrackGroup) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddAtEnd: (Track) -> Unit,
    onJumpToAlbum: (String) -> Unit,
    onJumpToArtist: (String) -> Unit,
    onJumpToFolder: (Track) -> Unit,
    onFetchWebMetadata: (Track) -> Unit,
    onWriteMetadataToFile: (Track) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Sort:", style = MaterialTheme.typography.labelMedium)
            SongSortOption.entries.forEach { opt ->
                FilterChip(
                    selected = sortOption == opt,
                    onClick = { onSortChanged(opt) },
                    label = { Text(opt.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 22.dp)
            ) {
                items(groups, key = { it.normalizedKey }) { group ->
                    val primary = group.primaryTrack
                    val webMeta = webMetadataMap[primary.filePath]
                    DeduplicatedTrackRow(
                        group = group,
                        webMetadata = webMeta,
                        onPlay = { onPlayGroup(group) },
                        onBadgeClick = { onOpenVariantPicker(group) },
                        onPlayNext = { onPlayNext(primary) },
                        onAddAtEnd = { onAddAtEnd(primary) },
                        onJumpToAlbum = { onJumpToAlbum(primary.album) },
                        onJumpToArtist = { onJumpToArtist(primary.artist) },
                        onJumpToFolder = { onJumpToFolder(primary) },
                        onFetchWebMetadata = { onFetchWebMetadata(primary) },
                        onWriteMetadataToFile = { onWriteMetadataToFile(primary) }
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                }
            }

            FastAlphabetScrollbar(
                items = groups.map { it.primaryTrack.title },
                onLetterSelected = { idx ->
                    scope.launch { listState.scrollToItem(idx) }
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlbumsTabPage(
    albums: List<Album>,
    selectedAlbumTitle: String?,
    selectedAlbumTracks: List<DeduplicatedTrackGroup>,
    sortOption: AlbumSortOption,
    webMetadataMap: Map<String, WebMetadataEntity>,
    onSortChanged: (AlbumSortOption) -> Unit,
    onSelectAlbum: (String) -> Unit,
    onCloseAlbumDetail: () -> Unit,
    onPlayAlbumTrack: (DeduplicatedTrackGroup, String) -> Unit,
    onPlayAllDiscs: (String) -> Unit,
    onJumpToSiblingDiscFolder: (String) -> Unit,
    onJumpToArtist: (String) -> Unit,
    onJumpToAlbumFolders: (Album) -> Unit,
    onOpenVariantPicker: (DeduplicatedTrackGroup) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddAtEnd: (Track) -> Unit,
    onFetchAlbumWebMetadata: (String) -> Unit,
    onWriteAlbumMetadata: (String) -> Unit,
    onFetchTrackWebMetadata: (Track) -> Unit,
    onWriteTrackMetadata: (Track) -> Unit
) {
    val activeAlbum = albums.firstOrNull { it.title.equals(selectedAlbumTitle, ignoreCase = true) }

    if (activeAlbum != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCloseAlbumDetail) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Albums")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeAlbum.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${activeAlbum.artist} • ${activeAlbum.trackCount} songs" +
                                if (activeAlbum.isMultiDisc) " • ${activeAlbum.discCount} Discs" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Cross-Navigation + Multi-Disc + Web Metadata Actions
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { onJumpToArtist(activeAlbum.artist) },
                    label = { Text("Go to Artist") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                AssistChip(
                    onClick = { onJumpToAlbumFolders(activeAlbum) },
                    label = {
                        Text(
                            if (activeAlbum.folderPaths.size > 1)
                                "Go to Folders (${activeAlbum.folderPaths.size})"
                            else "Go to Folder"
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                if (activeAlbum.isMultiDisc) {
                    AssistChip(
                        onClick = { onPlayAllDiscs(activeAlbum.title) },
                        label = { Text("Play All Discs (${activeAlbum.discCount})") },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    activeAlbum.discFolders.forEach { discFolderPath ->
                        val discName = discFolderPath.substringAfterLast('/').substringAfterLast('\\')
                        AssistChip(
                            onClick = { onJumpToSiblingDiscFolder(discFolderPath) },
                            label = { Text("Jump: $discName") }
                        )
                    }
                }
                AssistChip(
                    onClick = { onFetchAlbumWebMetadata(activeAlbum.title) },
                    label = { Text("Web Match Album") },
                    leadingIcon = { Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                AssistChip(
                    onClick = { onWriteAlbumMetadata(activeAlbum.title) },
                    label = { Text("Save Web Tags") },
                    leadingIcon = { Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(selectedAlbumTracks, key = { it.normalizedKey }) { group ->
                    val primary = group.primaryTrack
                    DeduplicatedTrackRow(
                        group = group,
                        webMetadata = webMetadataMap[primary.filePath],
                        onPlay = { onPlayAlbumTrack(group, activeAlbum.title) },
                        onBadgeClick = { onOpenVariantPicker(group) },
                        onPlayNext = { onPlayNext(primary) },
                        onAddAtEnd = { onAddAtEnd(primary) },
                        onJumpToAlbum = {},
                        onJumpToArtist = { onJumpToArtist(primary.artist) },
                        onJumpToFolder = { onJumpToSiblingDiscFolder(primary.parentFolderPath) },
                        onFetchWebMetadata = { onFetchTrackWebMetadata(primary) },
                        onWriteMetadataToFile = { onWriteTrackMetadata(primary) }
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                }
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Sort:", style = MaterialTheme.typography.labelMedium)
            AlbumSortOption.entries.forEach { opt ->
                FilterChip(
                    selected = sortOption == opt,
                    onClick = { onSortChanged(opt) },
                    label = { Text(opt.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 22.dp)
            ) {
                items(albums, key = { "${it.title}|${it.artist}" }) { album ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAlbum(album.title) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Album,
                            contentDescription = null,
                            tint = SamsungBlue,
                            modifier = Modifier.size(36.dp)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = album.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${album.artist} • ${album.trackCount} tracks" +
                                        if (album.isMultiDisc) " • Multi-Disc (${album.discCount})" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onJumpToArtist(album.artist) }) {
                            Icon(Icons.Default.Person, contentDescription = "Go to Artist")
                        }
                        IconButton(onClick = { onJumpToAlbumFolders(album) }) {
                            Icon(Icons.Default.Folder, contentDescription = "Go to Folder")
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                }
            }

            FastAlphabetScrollbar(
                items = albums.map { it.title },
                onLetterSelected = { idx -> scope.launch { listState.scrollToItem(idx) } },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun ArtistsTabPage(
    artists: List<Artist>,
    selectedArtistName: String?,
    selectedArtistTracks: List<DeduplicatedTrackGroup>,
    webMetadataMap: Map<String, WebMetadataEntity>,
    onSelectArtist: (String) -> Unit,
    onCloseArtistDetail: () -> Unit,
    onPlayArtistTrack: (DeduplicatedTrackGroup, String) -> Unit,
    onJumpToAlbum: (String) -> Unit,
    onJumpToArtistFolders: (Artist) -> Unit,
    onOpenVariantPicker: (DeduplicatedTrackGroup) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddAtEnd: (Track) -> Unit,
    onFetchTrackWebMetadata: (Track) -> Unit,
    onWriteTrackMetadata: (Track) -> Unit
) {
    val activeArtist = artists.firstOrNull { it.name.equals(selectedArtistName, ignoreCase = true) }

    if (activeArtist != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCloseArtistDetail) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Artists")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeArtist.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${activeArtist.albumCount} albums • ${activeArtist.trackCount} songs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AssistChip(
                    onClick = { onJumpToArtistFolders(activeArtist) },
                    label = {
                        Text(
                            if (activeArtist.folderPaths.size > 1)
                                "Folders (${activeArtist.folderPaths.size})"
                            else "Go to Folder"
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(selectedArtistTracks, key = { it.normalizedKey }) { group ->
                    val primary = group.primaryTrack
                    DeduplicatedTrackRow(
                        group = group,
                        webMetadata = webMetadataMap[primary.filePath],
                        onPlay = { onPlayArtistTrack(group, activeArtist.name) },
                        onBadgeClick = { onOpenVariantPicker(group) },
                        onPlayNext = { onPlayNext(primary) },
                        onAddAtEnd = { onAddAtEnd(primary) },
                        onJumpToAlbum = { onJumpToAlbum(primary.album) },
                        onJumpToArtist = {},
                        onJumpToFolder = {
                            onJumpToArtistFolders(
                                activeArtist.copy(folderPaths = listOf(primary.parentFolderPath))
                            )
                        },
                        onFetchWebMetadata = { onFetchTrackWebMetadata(primary) },
                        onWriteMetadataToFile = { onWriteTrackMetadata(primary) }
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                }
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 22.dp)
        ) {
            items(artists, key = { it.name }) { artist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectArtist(artist.name) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = SamsungBlue,
                        modifier = Modifier.size(36.dp)
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = artist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${artist.albumCount} albums • ${artist.trackCount} songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onJumpToArtistFolders(artist) }) {
                        Icon(Icons.Default.Folder, contentDescription = "Go to Artist Folder(s)")
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            }
        }

        FastAlphabetScrollbar(
            items = artists.map { it.name },
            onLetterSelected = { idx -> scope.launch { listState.scrollToItem(idx) } },
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

/**
 * Strictly Hierarchical Directory View (Specifications #3, #4, #5, #6):
 * - Starts at the Musical Root Folder.
 * - Sortable by System Default, Alphabetical, and Most Music Files (recursive sum across branch).
 * - Offers quick actions to Jump between sibling Disc folders and Play All Discs.
 * - NEVER deduplicates format extensions — always displays raw audio files.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoldersDirectoryTabPage(
    currentFolderPath: String,
    currentFolderNode: FolderNode?,
    subfolders: List<FolderNode>,
    rawTracks: List<Track>,
    sortOption: FolderSortOption,
    webMetadataMap: Map<String, WebMetadataEntity>,
    onSortChanged: (FolderSortOption) -> Unit,
    onNavigateUp: () -> Unit,
    onOpenSubfolder: (String) -> Unit,
    onPlayAllDiscsInFolder: () -> Unit,
    onPlayRawTrack: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddAtEnd: (Track) -> Unit,
    onJumpToAlbum: (String) -> Unit,
    onJumpToArtist: (String) -> Unit,
    onFetchTrackWebMetadata: (Track) -> Unit,
    onWriteTrackMetadata: (Track) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Current folder header + Up navigation
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateUp) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up to Parent Folder")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentFolderNode?.folderName ?: currentFolderPath.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = currentFolderPath,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Folder Sort Bar (System Default | Alphabetical | Most Music Files [Recursive Sum])
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = sortOption == FolderSortOption.SYSTEM_DEFAULT,
                onClick = { onSortChanged(FolderSortOption.SYSTEM_DEFAULT) },
                label = { Text("System Default", fontSize = 11.sp) }
            )
            FilterChip(
                selected = sortOption == FolderSortOption.ALPHABETICAL,
                onClick = { onSortChanged(FolderSortOption.ALPHABETICAL) },
                label = { Text("Alphabetical", fontSize = 11.sp) }
            )
            FilterChip(
                selected = sortOption == FolderSortOption.MOST_MUSIC_FILES,
                onClick = { onSortChanged(FolderSortOption.MOST_MUSIC_FILES) },
                label = { Text("Most Music Files", fontSize = 11.sp) }
            )
        }

        // Multi-Disc Sibling Quick Actions (when inside a CD1/CD2 or Disc 1/Disc 2 folder)
        val siblings = currentFolderNode?.siblingDiscFolders ?: emptyList()
        if (siblings.isNotEmpty() || currentFolderNode?.isDiscFolder == true) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = onPlayAllDiscsInFolder,
                    label = { Text("Play All Discs") },
                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                siblings.forEach { siblingPath ->
                    val siblingName = siblingPath.substringAfterLast('/').substringAfterLast('\\')
                    AssistChip(
                        onClick = { onOpenSubfolder(siblingPath) },
                        label = { Text("Jump to $siblingName") },
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Hierarchical child folders
            items(subfolders, key = { "dir:${it.absolutePath}" }) { folder ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSubfolder(folder.absolutePath) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = if (folder.isDiscFolder) WinampAmberGold else SamsungBlue,
                        modifier = Modifier.size(32.dp)
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = folder.folderName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${folder.recursiveTrackCount} total branch files (${folder.directTrackCount} direct)" +
                                    if (folder.isDiscFolder) " • Disc ${folder.discNumber}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = WinampLedGreen
                        )
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            }

            // Raw (non-deduplicated) files in this directory
            items(rawTracks, key = { "raw:${it.filePath}" }) { track ->
                RawDirectoryTrackRow(
                    track = track,
                    webMetadata = webMetadataMap[track.filePath],
                    onPlay = { onPlayRawTrack(track) },
                    onPlayNext = { onPlayNext(track) },
                    onAddAtEnd = { onAddAtEnd(track) },
                    onJumpToAlbum = { onJumpToAlbum(track.album) },
                    onJumpToArtist = { onJumpToArtist(track.artist) },
                    onFetchWebMetadata = { onFetchTrackWebMetadata(track) },
                    onWriteMetadataToFile = { onWriteTrackMetadata(track) }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            }
        }
    }
}

@Composable
private fun DeduplicatedTrackRow(
    group: DeduplicatedTrackGroup,
    webMetadata: WebMetadataEntity?,
    onPlay: () -> Unit,
    onBadgeClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddAtEnd: () -> Unit,
    onJumpToAlbum: () -> Unit,
    onJumpToArtist: () -> Unit,
    onJumpToFolder: () -> Unit,
    onFetchWebMetadata: () -> Unit,
    onWriteMetadataToFile: () -> Unit
) {
    val track = group.primaryTrack
    var showMenu by remember { mutableStateOf(false) }
    val isUnsavedWebMatch =
        track.webMatchPending || (webMetadata != null && webMetadata.status == WebMetadataEntity.STATUS_UNSAVED)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = SamsungBlue,
            modifier = Modifier.size(28.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isUnsavedWebMatch) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Web Match — Unsaved",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = WinampAmberGold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(WinampAmberGold.copy(alpha = 0.15f))
                            .border(1.dp, WinampAmberGold, RoundedCornerShape(4.dp))
                            .clickable { onWriteMetadataToFile() }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = "${track.artist} • ${track.album}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isUnsavedWebMatch && webMetadata != null) {
                Text(
                    text = "Candidate: ${webMetadata.candidateTitle} — ${webMetadata.candidateArtist}",
                    style = MaterialTheme.typography.labelSmall,
                    color = WinampAmberGold
                )
            }
        }

        // Format Deduplication Badge (e.g. "FLAC +1" or "FLAC")
        Surface(
            color = if (track.alternateVariantsCount > 0) WinampAmberGold.copy(alpha = 0.2f)
            else WinampLedGreen.copy(alpha = 0.15f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = if (track.alternateVariantsCount > 0) WinampAmberGold else WinampLedGreen,
                    shape = RoundedCornerShape(6.dp)
                )
                .clickable {
                    if (track.alternateVariantsCount > 0) onBadgeClick() else onPlay()
                }
        ) {
            Text(
                text = track.formatBadgeText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (track.alternateVariantsCount > 0) WinampAmberGold else WinampLedGreen,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }

        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Track Actions")
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Play Next (Immediate FIFO Queue)") },
                    onClick = {
                        showMenu = false
                        onPlayNext()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Add at End (Winamp Standard Queue)") },
                    onClick = {
                        showMenu = false
                        onAddAtEnd()
                    }
                )
                if (track.alternateVariantsCount > 0) {
                    DropdownMenuItem(
                        text = { Text("Select Format Variant (${track.formatBadgeText})") },
                        onClick = {
                            showMenu = false
                            onBadgeClick()
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Go to Album") },
                    onClick = {
                        showMenu = false
                        onJumpToAlbum()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Go to Artist") },
                    onClick = {
                        showMenu = false
                        onJumpToArtist()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Go to Folder") },
                    onClick = {
                        showMenu = false
                        onJumpToFolder()
                    }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Fetch Internet Metadata (MusicBrainz)") },
                    onClick = {
                        showMenu = false
                        onFetchWebMetadata()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Write Approved Tags to File...") },
                    onClick = {
                        showMenu = false
                        onWriteMetadataToFile()
                    }
                )
            }
        }
    }
}

@Composable
private fun RawDirectoryTrackRow(
    track: Track,
    webMetadata: WebMetadataEntity?,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddAtEnd: () -> Unit,
    onJumpToAlbum: () -> Unit,
    onJumpToArtist: () -> Unit,
    onFetchWebMetadata: () -> Unit,
    onWriteMetadataToFile: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val isUnsavedWebMatch =
        track.webMatchPending || (webMetadata != null && webMetadata.status == WebMetadataEntity.STATUS_UNSAVED)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = WinampLedGreen,
            modifier = Modifier.size(26.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        ) {
            Text(
                text = track.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${track.title} • ${track.artist} (${track.audioQuality.telemetrySummary})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isUnsavedWebMatch) {
                Text(
                    text = "Web Match — Unsaved",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = WinampAmberGold
                )
            }
        }

        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Raw File Actions")
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Play Next (Immediate FIFO Queue)") },
                    onClick = {
                        showMenu = false
                        onPlayNext()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Add at End (Winamp Standard Queue)") },
                    onClick = {
                        showMenu = false
                        onAddAtEnd()
                    }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Go to Album") },
                    onClick = {
                        showMenu = false
                        onJumpToAlbum()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Go to Artist") },
                    onClick = {
                        showMenu = false
                        onJumpToArtist()
                    }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Fetch Internet Metadata (MusicBrainz)") },
                    onClick = {
                        showMenu = false
                        onFetchWebMetadata()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Write Approved Tags to File...") },
                    onClick = {
                        showMenu = false
                        onWriteMetadataToFile()
                    }
                )
            }
        }
    }
}
