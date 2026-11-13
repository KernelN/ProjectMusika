package com.musika.player.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musika.player.core.model.Album
import com.musika.player.core.model.AlbumSortOption
import com.musika.player.core.model.Artist
import com.musika.player.core.model.ArtistSortOption
import com.musika.player.core.model.DeduplicatedTrackGroup
import com.musika.player.core.model.FolderNode
import com.musika.player.core.model.FolderSortOption
import com.musika.player.core.model.NowPlayingArtworkStyle
import com.musika.player.core.model.Playlist
import com.musika.player.core.model.SongSortOption
import com.musika.player.core.model.Track
import com.musika.player.data.local.entity.WebMetadataEntity
import com.musika.player.data.scanner.MusicalRootScanner
import com.musika.player.ui.components.AddToPlaylistDialog
import com.musika.player.ui.components.ArtworkThumbnail
import com.musika.player.ui.components.DualQueueBottomSheet
import com.musika.player.ui.components.FastAlphabetScrollbar
import com.musika.player.ui.components.FormatVariantPickerDialog
import com.musika.player.ui.components.MetadataWriteConfirmationDialog
import com.musika.player.ui.components.MultiFolderBottomSheet
import com.musika.player.ui.components.NowPlayingScreenOverlay
import com.musika.player.ui.components.OnlineDeviceControlBottomSheet
import com.musika.player.ui.components.SettingsDialog
import com.musika.player.ui.components.WinampMiniPlayerBar
import com.musika.player.ui.components.formatLoopLabel
import com.musika.player.ui.components.formatRandomLabel
import com.musika.player.ui.theme.SamsungBlue
import com.musika.player.ui.theme.WinampAmberGold
import com.musika.player.ui.theme.WinampDarkBezel
import com.musika.player.ui.theme.WinampLedGreen
import kotlinx.coroutines.launch

/**
 * Remembers a [LazyListState] whose exact scroll index and pixel offset are restored from
 * and saved back into [MusikaViewModel]'s recursive scroll memory whenever [scrollKey] changes
 * or the composable leaves composition.
 */
@Composable
private fun rememberPersistedLazyListState(
    scrollKey: String,
    viewModel: MusikaViewModel
): LazyListState {
    val saved = remember(scrollKey) { viewModel.getSavedScrollPosition(scrollKey) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = saved.first,
        initialFirstVisibleItemScrollOffset = saved.second
    )
    DisposableEffect(scrollKey, listState) {
        onDispose {
            viewModel.saveScrollPosition(
                key = scrollKey,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
            )
        }
    }
    return listState
}

@Composable
fun MusikaMainScreen(
    viewModel: MusikaViewModel,
    onRequestStoragePermissions: () -> Unit,
    onLaunchNativeFolderPicker: () -> Unit = {},
    onLaunchQrScanner: () -> Unit = {},
    onExitApp: () -> Unit = {}
) {
    val context = LocalContext.current
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
    val selectedArtistAlbumFilter by viewModel.selectedArtistAlbumFilter.collectAsState()
    val selectedAlbumTracks by viewModel.selectedAlbumTracks.collectAsState()
    val selectedArtistTracks by viewModel.selectedArtistTracks.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val selectedPlaylistId by viewModel.selectedPlaylistId.collectAsState()
    val selectedPlaylistTracks by viewModel.selectedPlaylistTracks.collectAsState()
    val addToPlaylistCandidates by viewModel.addToPlaylistCandidates.collectAsState()
    val webMetadataMap by viewModel.webMetadataMap.collectAsState()
    val songSort by viewModel.songSortOption.collectAsState()
    val albumSort by viewModel.albumSortOption.collectAsState()
    val artistSort by viewModel.artistSortOption.collectAsState()
    val folderSort by viewModel.folderSortOption.collectAsState()
    val artworkStyle by viewModel.artworkStyle.collectAsState()
    val showListTrackThumbnails by viewModel.showListTrackThumbnails.collectAsState()
    val hudScale by viewModel.hudScale.collectAsState()
    val multiFolderSheetState by viewModel.multiFolderSheetState.collectAsState()
    val variantPickerGroup by viewModel.variantPickerGroup.collectAsState()
    val writeConfirmationState by viewModel.writeConfirmationDialogState.collectAsState()
    val showNowPlayingOverlay by viewModel.showNowPlayingOverlay.collectAsState()
    val showQueueSheet by viewModel.showQueueSheet.collectAsState()
    val showOnlineControlSheet by viewModel.showOnlineControlSheet.collectAsState()
    val onlineControlState by viewModel.onlineControlState.collectAsState()
    val showSettingsDialog by viewModel.showSettingsDialog.collectAsState()
    val musicalRootPath by viewModel.musicalRootPath.collectAsState()
    val alwaysScanEntirePhone by viewModel.alwaysScanEntirePhone.collectAsState()
    val allowLanStreaming by viewModel.allowLanStreamingForMissingTracks.collectAsState()
    val supabaseUrl by viewModel.supabaseUrl.collectAsState()
    val supabaseAnonKey by viewModel.supabaseAnonKey.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val statusBanner by viewModel.statusBannerMessage.collectAsState()
    val localDevId = remember(viewModel) { viewModel.onlineControlCoordinator.effectiveLocalDeviceId() }
    val isBrowsingRemote = onlineControlState.browsingDeviceId != localDevId && onlineControlState.browsingDeviceId.isNotBlank()
    val effectiveSongs = remember(isBrowsingRemote, onlineControlState, deduplicatedSongs) {
        if (!isBrowsingRemote) {
            deduplicatedSongs
        } else {
            val remoteTracks = viewModel.onlineControlCoordinator.getActiveBrowsingTracks(
                deduplicatedSongs.map { it.primaryTrack }
            )
            remoteTracks.map { t ->
                DeduplicatedTrackGroup(
                    normalizedKey = t.normalizedKey,
                    primaryTrack = t,
                    allVariants = listOf(t)
                )
            }
        }
    }

    val tabs = MusikaTab.entries
    val pagerState = rememberPagerState(
        initialPage = selectedTab.ordinal,
        pageCount = { tabs.size }
    )

    // Eliminate intermediate-page race condition when switching to opposite tabs (e.g. Songs <-> Folders):
    // 1. Programmatic tab selection jumps directly with scrollToPage (never passes through intermediate pages).
    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab.ordinal) {
            pagerState.scrollToPage(selectedTab.ordinal)
        }
    }

    // 2. User swipe gesture updates selectedTab ONLY after the pager settles and is no longer scrolling.
    LaunchedEffect(pagerState.settledPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            val settledTab = tabs[pagerState.settledPage]
            if (selectedTab != settledTab) {
                viewModel.selectTab(settledTab)
            }
        }
    }

    // Step-by-Step Back Navigation Hierarchy + Double-Back within 2s on Root Section to Exit
    var lastRootBackPressMs by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = true) {
        when {
            showNowPlayingOverlay -> viewModel.setShowNowPlayingOverlay(false)
            showQueueSheet -> viewModel.setShowQueueSheet(false)
            showOnlineControlSheet -> viewModel.setShowOnlineControlSheet(false)
            addToPlaylistCandidates != null -> viewModel.dismissAddToPlaylistDialog()
            multiFolderSheetState != null -> viewModel.dismissMultiFolderSheet()
            variantPickerGroup != null -> viewModel.dismissFormatVariantPicker()
            writeConfirmationState != null -> viewModel.dismissWriteConfirmation()
            showSettingsDialog -> viewModel.setShowSettingsDialog(false)
            selectedTab == MusikaTab.ALBUMS && selectedAlbumDetail != null -> viewModel.closeAlbumDetail()
            selectedTab == MusikaTab.ARTISTS && selectedArtistDetail != null -> viewModel.closeArtistDetail()
            selectedTab == MusikaTab.PLAYLISTS && selectedPlaylistId != null -> viewModel.closePlaylistDetail()
            selectedTab == MusikaTab.FOLDERS && viewModel.canNavigateUpFolder() -> viewModel.navigateUpFolder()
            selectedTab != MusikaTab.SONGS -> viewModel.selectTab(MusikaTab.SONGS)
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastRootBackPressMs < 2000L) {
                    onExitApp()
                } else {
                    lastRootBackPressMs = now
                    Toast.makeText(context, "Press back again to exit Musika", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val activePlayingPath = playbackState.currentTrack?.filePath
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, hudScale) {
        Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * hudScale
        )
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets.systemBars,
                topBar = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TopAppBar(
                            title = {
                                Text(
                                    text = "MUSIKA",
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.2.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            actions = {
                                // Dedicated 1-tap "Playing" access pill in the top bar whenever a song is loaded
                                if (playbackState.currentTrack != null) {
                                    Surface(
                                        color = SamsungBlue.copy(alpha = 0.22f),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier
                                            .padding(end = 4.dp)
                                            .border(1.dp, SamsungBlue, RoundedCornerShape(16.dp))
                                            .clickable { viewModel.setShowNowPlayingOverlay(true) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Equalizer,
                                                contentDescription = "Open Now Playing",
                                                tint = WinampLedGreen,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "Playing",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }

                                // Compact non-wrapping Random & Loop mode pills
                                Surface(
                                    color = WinampDarkBezel,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .border(1.dp, WinampAmberGold.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                                        .clickable { viewModel.cycleRandomMode() }
                                ) {
                                    Text(
                                        text = formatRandomLabel(playbackState.randomMode),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = WinampAmberGold,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }

                                Surface(
                                    color = WinampDarkBezel,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .border(1.dp, WinampLedGreen.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                                        .clickable { viewModel.cycleLoopMode() }
                                ) {
                                    Text(
                                        text = formatLoopLabel(playbackState.loopMode),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = WinampLedGreen,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }

                                IconButton(onClick = { viewModel.setShowOnlineControlSheet(true) }) {
                                    val transportColor = when (onlineControlState.transportMode) {
                                        com.musika.player.core.sync.RemoteSyncContract.ConnectionTransportMode.LAN_DIRECT -> WinampLedGreen
                                        com.musika.player.core.sync.RemoteSyncContract.ConnectionTransportMode.ONLINE_SUPABASE -> SamsungBlue
                                        com.musika.player.core.sync.RemoteSyncContract.ConnectionTransportMode.OFFLINE_LOCAL -> Color.LightGray
                                    }
                                    Icon(
                                        imageVector = Icons.Default.Devices,
                                        contentDescription = "Online Multi-Device Control",
                                        tint = transportColor
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

                        if (isBrowsingRemote) {
                            val browsingName = onlineControlState.connectedDevices
                                .firstOrNull { it.deviceId == onlineControlState.browsingDeviceId }
                                ?.deviceName ?: onlineControlState.browsingDeviceId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(WinampAmberGold.copy(alpha = 0.2f))
                                    .clickable { viewModel.setBrowsingDeviceId(localDevId) }
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Remote Library: $browsingName (Tap plays remotely)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "Back to Phone",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = WinampLedGreen,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
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
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Dismiss",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WinampAmberGold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        // Samsung Music Top Swipeable Tabs (manual horizontal scrolling when scaled or 5 tabs)
                        ScrollableTabRow(
                            selectedTabIndex = selectedTab.ordinal,
                            edgePadding = 8.dp
                        ) {
                            tabs.forEachIndexed { idx, tab ->
                                val isSelected = selectedTab.ordinal == idx
                                Tab(
                                    selected = isSelected,
                                    onClick = { viewModel.selectTab(tab) },
                                    text = {
                                        Text(
                                            text = tab.title,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
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
                        artworkStyle = artworkStyle,
                        onExpandNowPlaying = { viewModel.setShowNowPlayingOverlay(true) },
                        onTogglePlayPause = { viewModel.togglePlayPause() },
                        onSkipPrevious = { viewModel.skipPrevious() },
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
                            viewModel = viewModel,
                            groups = effectiveSongs,
                            sortOption = songSort,
                            activePlayingPath = activePlayingPath,
                            showListThumbnails = showListTrackThumbnails,
                            artworkStyle = artworkStyle,
                            webMetadataMap = webMetadataMap,
                            onSortChanged = viewModel::setSongSort,
                            onPlayGroup = { group ->
                                viewModel.playSongGroup(group, effectiveSongs, "All Songs")
                            },
                            onOpenNowPlaying = { viewModel.setShowNowPlayingOverlay(true) },
                            onOpenVariantPicker = viewModel::openFormatVariantPicker,
                            onPlayNext = viewModel::enqueuePlayNext,
                            onAddAtEnd = viewModel::enqueueAddAtEnd,
                            onAddToPlaylist = { track -> viewModel.openAddToPlaylistDialog(listOf(track)) },
                            onJumpToAlbum = viewModel::jumpToAlbum,
                            onJumpToArtist = viewModel::jumpToArtist,
                            onJumpToFolder = { track ->
                                viewModel.jumpToFolders(listOf(track.parentFolderPath), track.title)
                            },
                            onFetchWebMetadata = viewModel::fetchInternetMetadataForTrack,
                            onWriteMetadataToFile = viewModel::startTrackWriteConfirmation
                        )

                        MusikaTab.ALBUMS -> AlbumsTabPage(
                            viewModel = viewModel,
                            albums = albums,
                            artists = artists,
                            selectedAlbumTitle = selectedAlbumDetail,
                            selectedAlbumTracks = selectedAlbumTracks,
                            sortOption = albumSort,
                            musicalRootPath = musicalRootPath,
                            activePlayingPath = activePlayingPath,
                            showListThumbnails = showListTrackThumbnails,
                            artworkStyle = artworkStyle,
                            webMetadataMap = webMetadataMap,
                            onSortChanged = viewModel::setAlbumSort,
                            onSelectAlbum = viewModel::jumpToAlbum,
                            onCloseAlbumDetail = viewModel::closeAlbumDetail,
                            onJumpToSectionRoot = viewModel::jumpToSectionRoot,
                            onPlayAlbumTrack = { group, albumTitle ->
                                viewModel.playSongGroup(group, selectedAlbumTracks, "Album: $albumTitle")
                            },
                            onOpenNowPlaying = { viewModel.setShowNowPlayingOverlay(true) },
                            onPlayAllDiscs = viewModel::playAllDiscsForAlbum,
                            onJumpToSiblingDiscFolder = viewModel::openSingleFolderInDirectoryView,
                            onJumpToArtist = viewModel::jumpToArtist,
                            onJumpToAlbumFolders = { album ->
                                viewModel.jumpToFolders(album.folderPaths, album.title)
                            },
                            onAddAlbumToPlaylist = {
                                val tracks = selectedAlbumTracks.map { it.primaryTrack }
                                viewModel.openAddToPlaylistDialog(tracks)
                            },
                            onAddTrackToPlaylist = { track -> viewModel.openAddToPlaylistDialog(listOf(track)) },
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
                            viewModel = viewModel,
                            artists = artists,
                            albums = albums,
                            selectedArtistName = selectedArtistDetail,
                            selectedArtistAlbumFilter = selectedArtistAlbumFilter,
                            selectedArtistTracks = selectedArtistTracks,
                            sortOption = artistSort,
                            musicalRootPath = musicalRootPath,
                            activePlayingPath = activePlayingPath,
                            showListThumbnails = showListTrackThumbnails,
                            artworkStyle = artworkStyle,
                            webMetadataMap = webMetadataMap,
                            onSortChanged = viewModel::setArtistSort,
                            onSelectArtist = viewModel::jumpToArtist,
                            onSelectArtistAlbumFilter = viewModel::selectArtistAlbumFilter,
                            onCloseArtistDetail = viewModel::closeArtistDetail,
                            onJumpToSectionRoot = viewModel::jumpToSectionRoot,
                            onPlayArtistTrack = { group, artistName ->
                                viewModel.playSongGroup(group, selectedArtistTracks, "Artist: $artistName")
                            },
                            onOpenNowPlaying = { viewModel.setShowNowPlayingOverlay(true) },
                            onJumpToAlbum = viewModel::jumpToAlbum,
                            onJumpToArtistFolders = { artist ->
                                viewModel.jumpToFolders(artist.folderPaths, artist.name)
                            },
                            onAddTrackToPlaylist = { track -> viewModel.openAddToPlaylistDialog(listOf(track)) },
                            onOpenVariantPicker = viewModel::openFormatVariantPicker,
                            onPlayNext = viewModel::enqueuePlayNext,
                            onAddAtEnd = viewModel::enqueueAddAtEnd,
                            onFetchTrackWebMetadata = viewModel::fetchInternetMetadataForTrack,
                            onWriteTrackMetadata = viewModel::startTrackWriteConfirmation
                        )

                        MusikaTab.PLAYLISTS -> PlaylistsTabPage(
                            viewModel = viewModel,
                            playlists = playlists,
                            selectedPlaylistId = selectedPlaylistId,
                            selectedPlaylistTracks = selectedPlaylistTracks,
                            activePlayingPath = activePlayingPath,
                            showListThumbnails = showListTrackThumbnails,
                            artworkStyle = artworkStyle,
                            onSelectPlaylist = viewModel::selectPlaylist,
                            onClosePlaylistDetail = viewModel::closePlaylistDetail,
                            onJumpToSectionRoot = viewModel::jumpToSectionRoot,
                            onCreatePlaylist = { name -> viewModel.createPlaylist(name) },
                            onRenamePlaylist = viewModel::renamePlaylist,
                            onDeletePlaylist = viewModel::deletePlaylist,
                            onPlayPlaylistTrack = { track, playlistName ->
                                viewModel.playPlaylistTrack(track, selectedPlaylistTracks, playlistName)
                            },
                            onMoveTrackInPlaylist = viewModel::moveTrackInPlaylist,
                            onRemoveTrackFromPlaylist = viewModel::removeTrackFromPlaylist,
                            onOpenNowPlaying = { viewModel.setShowNowPlayingOverlay(true) },
                            onPlayNext = viewModel::enqueuePlayNext,
                            onAddAtEnd = viewModel::enqueueAddAtEnd,
                            onJumpToAlbum = viewModel::jumpToAlbum,
                            onJumpToArtist = viewModel::jumpToArtist,
                            onJumpToFolder = { track ->
                                viewModel.jumpToFolders(listOf(track.parentFolderPath), track.title)
                            }
                        )

                        MusikaTab.FOLDERS -> FoldersDirectoryTabPage(
                            viewModel = viewModel,
                            currentFolderPath = currentFolderPath,
                            currentFolderNode = currentFolderNode,
                            canNavigateUp = viewModel.canNavigateUpFolder(),
                            subfolders = currentSubfolders,
                            rawTracks = currentFolderRawTracks,
                            sortOption = folderSort,
                            activePlayingPath = activePlayingPath,
                            showListThumbnails = showListTrackThumbnails,
                            artworkStyle = artworkStyle,
                            webMetadataMap = webMetadataMap,
                            onSortChanged = viewModel::setFolderSort,
                            onNavigateUp = { viewModel.navigateUpFolder() },
                            onJumpToRootFolder = viewModel::jumpToRootFolder,
                            onOpenSubfolder = viewModel::openSingleFolderInDirectoryView,
                            onPlayAllDiscsInFolder = {
                                val name = currentFolderNode?.folderName
                                    ?: currentFolderPath.substringAfterLast('/')
                                viewModel.playAllDiscsFromFolder(currentFolderPath, name)
                            },
                            onAddFolderTracksToPlaylist = {
                                viewModel.openAddToPlaylistDialog(currentFolderRawTracks)
                            },
                            onAddTrackToPlaylist = { track ->
                                viewModel.openAddToPlaylistDialog(listOf(track))
                            },
                            onPlayRawTrack = { track ->
                                val name = currentFolderNode?.folderName
                                    ?: currentFolderPath.substringAfterLast('/')
                                viewModel.playRawTrackInFolder(track, currentFolderRawTracks, name)
                            },
                            onOpenNowPlaying = { viewModel.setShowNowPlayingOverlay(true) },
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
                    artworkStyle = artworkStyle,
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

            addToPlaylistCandidates?.let { candidates ->
                AddToPlaylistDialog(
                    playlists = playlists,
                    candidateTracks = candidates,
                    onAddToExistingPlaylist = { playlistId, playlistName ->
                        viewModel.addTracksToExistingPlaylist(playlistId, playlistName, candidates)
                    },
                    onCreateNewPlaylistWithTracks = { newName ->
                        viewModel.createPlaylistWithTracks(newName, candidates)
                    },
                    onDismiss = viewModel::dismissAddToPlaylistDialog
                )
            }

            if (showQueueSheet) {
                DualQueueBottomSheet(
                    state = playbackState,
                    onMovePriorityTrack = { from, to ->
                        viewModel.dualQueueEngine.movePriorityQueueTrack(from, to)
                    },
                    onRemovePriorityIndex = { idx ->
                        viewModel.dualQueueEngine.removeFromPriorityQueue(idx)
                    },
                    onClearPriorityQueue = {
                        viewModel.dualQueueEngine.clearPriorityQueue()
                    },
                    onMoveContextTrack = { from, to ->
                        viewModel.dualQueueEngine.moveContextQueueTrack(from, to)
                    },
                    onRemoveContextIndex = { idx ->
                        viewModel.dualQueueEngine.removeFromContextQueue(idx)
                    },
                    onClearContextQueue = {
                        viewModel.dualQueueEngine.clearContextQueue()
                    },
                    onSelectTrackFromStandard = { track ->
                        viewModel.dualQueueEngine.playFromContext(
                            selectedTrack = track,
                            contextTracks = playbackState.standardQueue,
                            contextName = playbackState.activeContextName
                        )
                    },
                    onSaveQueueAsPlaylist = viewModel::saveCurrentQueueAsPlaylist,
                    onDismiss = { viewModel.setShowQueueSheet(false) }
                )
            }

            if (showOnlineControlSheet) {
                val qrPayload = remember(onlineControlState.accountId, onlineControlState.activePinCode, onlineControlState.lanHost) {
                    viewModel.onlineControlCoordinator.generateQrPayload()
                }
                OnlineDeviceControlBottomSheet(
                    onlineState = onlineControlState,
                    playbackState = playbackState,
                    qrPairPayload = qrPayload,
                    onSelectSyncMode = viewModel::setAccountDeviceSyncMode,
                    onSendRemoteCommand = viewModel::sendRemoteControlCommand,
                    onMirrorCatalogToDevice = viewModel::mirrorCatalogToPeerDevice,
                    onClearPeerCatalog = viewModel::clearPeerDeviceCatalog,
                    onTransferPlaybackToDevice = { deviceId ->
                        viewModel.transferPlaybackToDevice(deviceId)
                    },
                    onLaunchQrScanner = onLaunchQrScanner,
                    onPairViaQrOrPin = { input, host -> viewModel.pairViaQrOrPin(input, host) },
                    onConnectManualLanHost = viewModel::connectManualLanHost,
                    onSignInUsername = viewModel::signInOnlineAccount,
                    onSignUpUsername = viewModel::signUpOnlineAccount,
                    onSignOutAccount = viewModel::signOutOnlineAccount,
                    onToggleListenLocallyInSynced = viewModel::setListenLocallyWhenSyncedTargetIsRemote,
                    onSelectBrowsingDevice = viewModel::setBrowsingDeviceId,
                    onDismiss = { viewModel.setShowOnlineControlSheet(false) }
                )
            }

            if (showSettingsDialog) {
                SettingsDialog(
                    currentRootPath = musicalRootPath,
                    alwaysScanEntirePhone = alwaysScanEntirePhone,
                    artworkStyle = artworkStyle,
                    showListTrackThumbnails = showListTrackThumbnails,
                    hudScale = hudScale,
                    allowLanStreamingForMissingTracks = allowLanStreaming,
                    supabaseUrl = supabaseUrl,
                    supabaseAnonKey = supabaseAnonKey,
                    onLaunchNativeFolderPicker = onLaunchNativeFolderPicker,
                    onSaveRootPath = viewModel::setMusicalRootFolder,
                    onToggleAlwaysScanEntirePhone = viewModel::setAlwaysScanEntirePhone,
                    onSelectArtworkStyle = viewModel::setArtworkStyle,
                    onToggleListTrackThumbnails = viewModel::setShowListTrackThumbnails,
                    onHudScaleChanged = viewModel::setHudScale,
                    onToggleAllowLanStreaming = viewModel::setAllowLanStreamingForMissingTracks,
                    onSaveSupabaseConfig = viewModel::updateSupabaseConfig,
                    onRequestStoragePermissions = onRequestStoragePermissions,
                    onDismiss = { viewModel.setShowSettingsDialog(false) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongsTabPage(
    viewModel: MusikaViewModel,
    groups: List<DeduplicatedTrackGroup>,
    sortOption: SongSortOption,
    activePlayingPath: String?,
    showListThumbnails: Boolean,
    artworkStyle: NowPlayingArtworkStyle,
    webMetadataMap: Map<String, WebMetadataEntity>,
    onSortChanged: (SongSortOption) -> Unit,
    onPlayGroup: (DeduplicatedTrackGroup) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenVariantPicker: (DeduplicatedTrackGroup) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddAtEnd: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onJumpToAlbum: (String) -> Unit,
    onJumpToArtist: (String) -> Unit,
    onJumpToFolder: (Track) -> Unit,
    onFetchWebMetadata: (Track) -> Unit,
    onWriteMetadataToFile: (Track) -> Unit
) {
    val listState = rememberPersistedLazyListState("songs_root_${sortOption.name}", viewModel)
    val scope = rememberCoroutineScope()

    // Sort-aware keys for the FastAlphabetScrollbar:
    // Uses the exact field active for sorting (Title, Album, Artist, or Topmost Directory Name)!
    val sortKeys = remember(groups, sortOption) {
        groups.map { group ->
            val t = group.primaryTrack
            when (sortOption) {
                SongSortOption.ALPHABETICAL -> t.title
                SongSortOption.ALBUM -> t.album
                SongSortOption.ARTIST -> t.artist
                SongSortOption.DIRECTORY -> t.relativePath
                    .trimStart('/', '\\')
                    .substringBefore('/')
                    .substringBefore('\\')
                    .ifBlank { t.fileName }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Sort:", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            SongSortOption.entries.forEach { opt ->
                FilterChip(
                    selected = sortOption == opt,
                    onClick = { onSortChanged(opt) },
                    label = {
                        Text(
                            text = opt.name.lowercase().replaceFirstChar { it.uppercase() },
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 36.dp)
            ) {
                items(groups, key = { it.normalizedKey }) { group ->
                    val primary = group.primaryTrack
                    val webMeta = webMetadataMap[primary.filePath]
                    val isPlayingThis = group.allVariants.any { it.filePath == activePlayingPath }
                    DeduplicatedTrackRow(
                        group = group,
                        isCurrentlyPlaying = isPlayingThis,
                        showThumbnail = showListThumbnails,
                        asCircularDisc = artworkStyle == NowPlayingArtworkStyle.SPINNING_DISC,
                        webMetadata = webMeta,
                        onPlay = { onPlayGroup(group) },
                        onOpenNowPlaying = onOpenNowPlaying,
                        onBadgeClick = { onOpenVariantPicker(group) },
                        onPlayNext = { onPlayNext(primary) },
                        onAddAtEnd = { onAddAtEnd(primary) },
                        onAddToPlaylist = { onAddToPlaylist(primary) },
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
                items = sortKeys,
                listState = listState,
                onLetterSelected = { idx ->
                    scope.launch { listState.scrollToItem(idx) }
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

/**
 * Persistent Horizontal Scrollable Album Index Bar for Artists with > 1 Album.
 * Displayed right below the Artist/Album header both when inside the Artist view AND when inside
 * any of that Artist's albums!
 */
@Composable
private fun ArtistHorizontalAlbumIndexBar(
    artistAlbums: List<Album>,
    selectedAlbumTitle: String?,
    onSelectAllTracks: () -> Unit,
    onSelectAlbum: (String) -> Unit
) {
    if (artistAlbums.size <= 1) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141822))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Albums:",
            style = MaterialTheme.typography.labelSmall,
            color = WinampAmberGold,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
        FilterChip(
            selected = selectedAlbumTitle == null,
            onClick = onSelectAllTracks,
            label = {
                Text(
                    text = "All Tracks",
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        )
        artistAlbums.forEach { album ->
            val isSelected = selectedAlbumTitle != null &&
                    album.title.equals(selectedAlbumTitle, ignoreCase = true)
            FilterChip(
                selected = isSelected,
                onClick = { onSelectAlbum(album.title) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                label = {
                    Text(
                        text = "${album.title} (${album.trackCount})",
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun AlbumsTabPage(
    viewModel: MusikaViewModel,
    albums: List<Album>,
    artists: List<Artist>,
    selectedAlbumTitle: String?,
    selectedAlbumTracks: List<DeduplicatedTrackGroup>,
    sortOption: AlbumSortOption,
    musicalRootPath: String,
    activePlayingPath: String?,
    showListThumbnails: Boolean,
    artworkStyle: NowPlayingArtworkStyle,
    webMetadataMap: Map<String, WebMetadataEntity>,
    onSortChanged: (AlbumSortOption) -> Unit,
    onSelectAlbum: (String) -> Unit,
    onCloseAlbumDetail: () -> Unit,
    onJumpToSectionRoot: () -> Unit,
    onPlayAlbumTrack: (DeduplicatedTrackGroup, String) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onPlayAllDiscs: (String) -> Unit,
    onJumpToSiblingDiscFolder: (String) -> Unit,
    onJumpToArtist: (String) -> Unit,
    onJumpToAlbumFolders: (Album) -> Unit,
    onAddAlbumToPlaylist: () -> Unit,
    onAddTrackToPlaylist: (Track) -> Unit,
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
        val detailListState = rememberPersistedLazyListState(
            scrollKey = "album_detail:${activeAlbum.title}",
            viewModel = viewModel
        )
        val siblingAlbumsOfArtist = remember(albums, activeAlbum.artist) {
            albums.filter { it.artist.equals(activeAlbum.artist, ignoreCase = true) }
        }
        val parentArtist = remember(artists, activeAlbum.artist) {
            artists.firstOrNull { it.name.equals(activeAlbum.artist, ignoreCase = true) }
        }
        val hasMultipleAlbumsForArtist =
            siblingAlbumsOfArtist.size > 1 || (parentArtist?.albumCount ?: 1) > 1

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
                IconButton(onClick = onJumpToSectionRoot) {
                    Icon(
                        imageVector = Icons.Default.KeyboardDoubleArrowUp,
                        contentDescription = "Up to Root",
                        tint = WinampAmberGold
                    )
                }
                ArtworkThumbnail(
                    artworkUri = activeAlbum.artworkUri,
                    fallbackIcon = Icons.Default.Album,
                    fallbackTint = SamsungBlue,
                    size = 44.dp,
                    cornerRadius = 8.dp,
                    asCircularDisc = artworkStyle == NowPlayingArtworkStyle.SPINNING_DISC
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeAlbum.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee()
                    )
                    Text(
                        text = "${activeAlbum.artist} • ${activeAlbum.trackCount} songs" +
                                if (activeAlbum.isMultiDisc) " • ${activeAlbum.discCount} Discs" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee()
                    )
                }
            }

            // Persistent Horizontal Album Index when this album's artist has > 1 album!
            if (hasMultipleAlbumsForArtist) {
                ArtistHorizontalAlbumIndexBar(
                    artistAlbums = siblingAlbumsOfArtist,
                    selectedAlbumTitle = activeAlbum.title,
                    onSelectAllTracks = { onJumpToArtist(activeAlbum.artist) },
                    onSelectAlbum = { siblingTitle -> onSelectAlbum(siblingTitle) }
                )
            }

            // Cross-Navigation + Multi-Disc + Playlist + Web Metadata Actions (single-line chips)
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { onJumpToArtist(activeAlbum.artist) },
                    label = { Text("Artist", maxLines = 1, softWrap = false) },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                AssistChip(
                    onClick = { onJumpToAlbumFolders(activeAlbum) },
                    label = {
                        Text(
                            text = if (activeAlbum.folderPaths.size > 1)
                                "Folders (${activeAlbum.folderPaths.size})"
                            else "Folder",
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                AssistChip(
                    onClick = onAddAlbumToPlaylist,
                    label = { Text("+ Playlist", maxLines = 1, softWrap = false) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                if (activeAlbum.isMultiDisc) {
                    AssistChip(
                        onClick = { onPlayAllDiscs(activeAlbum.title) },
                        label = { Text("Play All Discs (${activeAlbum.discCount})", maxLines = 1, softWrap = false) },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    activeAlbum.discFolders.forEach { discFolderPath ->
                        val discName = discFolderPath.substringAfterLast('/').substringAfterLast('\\')
                        AssistChip(
                            onClick = { onJumpToSiblingDiscFolder(discFolderPath) },
                            label = { Text("Disc: $discName", maxLines = 1, softWrap = false) }
                        )
                    }
                }
                AssistChip(
                    onClick = { onFetchAlbumWebMetadata(activeAlbum.title) },
                    label = { Text("Web Match", maxLines = 1, softWrap = false) },
                    leadingIcon = { Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                AssistChip(
                    onClick = { onWriteAlbumMetadata(activeAlbum.title) },
                    label = { Text("Save Tags", maxLines = 1, softWrap = false) },
                    leadingIcon = { Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            LazyColumn(
                state = detailListState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(selectedAlbumTracks, key = { it.normalizedKey }) { group ->
                    val primary = group.primaryTrack
                    val isPlayingThis = group.allVariants.any { it.filePath == activePlayingPath }
                    DeduplicatedTrackRow(
                        group = group,
                        isCurrentlyPlaying = isPlayingThis,
                        showThumbnail = showListThumbnails,
                        asCircularDisc = artworkStyle == NowPlayingArtworkStyle.SPINNING_DISC,
                        webMetadata = webMetadataMap[primary.filePath],
                        onPlay = { onPlayAlbumTrack(group, activeAlbum.title) },
                        onOpenNowPlaying = onOpenNowPlaying,
                        onBadgeClick = { onOpenVariantPicker(group) },
                        onPlayNext = { onPlayNext(primary) },
                        onAddAtEnd = { onAddAtEnd(primary) },
                        onAddToPlaylist = { onAddTrackToPlaylist(primary) },
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

    val listState = rememberPersistedLazyListState("albums_root_${sortOption.name}", viewModel)
    val scope = rememberCoroutineScope()

    val sortKeys = remember(albums, sortOption, musicalRootPath) {
        albums.map { album ->
            when (sortOption) {
                AlbumSortOption.ALPHABETICAL -> album.title
                AlbumSortOption.ARTIST -> album.artist
                AlbumSortOption.DIRECTORY -> {
                    val firstFolder = album.folderPaths.firstOrNull().orEmpty()
                    MusicalRootScanner.computeRelativePath(musicalRootPath, firstFolder)
                        .trimStart('/', '\\')
                        .substringBefore('/')
                        .substringBefore('\\')
                        .ifBlank { album.title }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Sort:", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            AlbumSortOption.entries.forEach { opt ->
                FilterChip(
                    selected = sortOption == opt,
                    onClick = { onSortChanged(opt) },
                    label = {
                        Text(
                            text = opt.name.lowercase().replaceFirstChar { it.uppercase() },
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 36.dp)
            ) {
                items(albums, key = { "${it.title}|${it.artist}" }) { album ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAlbum(album.title) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showListThumbnails) {
                            ArtworkThumbnail(
                                artworkUri = album.artworkUri,
                                fallbackIcon = Icons.Default.Album,
                                fallbackTint = SamsungBlue,
                                size = 42.dp,
                                cornerRadius = 8.dp,
                                asCircularDisc = artworkStyle == NowPlayingArtworkStyle.SPINNING_DISC
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = album.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.basicMarquee()
                            )
                            Text(
                                text = "${album.artist} • ${album.trackCount} tracks" +
                                        if (album.isMultiDisc) " • Multi-Disc (${album.discCount})" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.basicMarquee()
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
                items = sortKeys,
                listState = listState,
                onLetterSelected = { idx -> scope.launch { listState.scrollToItem(idx) } },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistsTabPage(
    viewModel: MusikaViewModel,
    artists: List<Artist>,
    albums: List<Album>,
    selectedArtistName: String?,
    selectedArtistAlbumFilter: String?,
    selectedArtistTracks: List<DeduplicatedTrackGroup>,
    sortOption: ArtistSortOption,
    musicalRootPath: String,
    activePlayingPath: String?,
    showListThumbnails: Boolean,
    artworkStyle: NowPlayingArtworkStyle,
    webMetadataMap: Map<String, WebMetadataEntity>,
    onSortChanged: (ArtistSortOption) -> Unit,
    onSelectArtist: (String) -> Unit,
    onSelectArtistAlbumFilter: (String?) -> Unit,
    onCloseArtistDetail: () -> Unit,
    onJumpToSectionRoot: () -> Unit,
    onPlayArtistTrack: (DeduplicatedTrackGroup, String) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onJumpToAlbum: (String) -> Unit,
    onJumpToArtistFolders: (Artist) -> Unit,
    onAddTrackToPlaylist: (Track) -> Unit,
    onOpenVariantPicker: (DeduplicatedTrackGroup) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddAtEnd: (Track) -> Unit,
    onFetchTrackWebMetadata: (Track) -> Unit,
    onWriteTrackMetadata: (Track) -> Unit
) {
    val activeArtist = artists.firstOrNull { it.name.equals(selectedArtistName, ignoreCase = true) }

    if (activeArtist != null) {
        val detailListState = rememberPersistedLazyListState(
            scrollKey = "artist_detail:${activeArtist.name}:${selectedArtistAlbumFilter ?: "ALL"}",
            viewModel = viewModel
        )
        // Build distinct albums belonging to this artist
        val artistAlbums = remember(albums, selectedArtistTracks, activeArtist.name) {
            val fromAlbumsList = albums.filter { it.artist.equals(activeArtist.name, ignoreCase = true) }
            if (fromAlbumsList.size >= activeArtist.albumCount) {
                fromAlbumsList
            } else {
                selectedArtistTracks
                    .groupBy { it.primaryTrack.album.trim() }
                    .map { (albumTitle, groups) ->
                        Album(
                            title = albumTitle,
                            artist = activeArtist.name,
                            trackCount = groups.size,
                            totalDurationMs = groups.sumOf { it.primaryTrack.durationMs },
                            artworkUri = groups.firstNotNullOfOrNull { it.primaryTrack.artworkUri }
                        )
                    }
            }
        }

        val displayedTracks = remember(selectedArtistTracks, selectedArtistAlbumFilter) {
            if (selectedArtistAlbumFilter == null) {
                selectedArtistTracks
            } else {
                selectedArtistTracks.filter {
                    it.primaryTrack.album.equals(selectedArtistAlbumFilter, ignoreCase = true)
                }
            }
        }

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
                IconButton(onClick = onJumpToSectionRoot) {
                    Icon(
                        imageVector = Icons.Default.KeyboardDoubleArrowUp,
                        contentDescription = "Up to Root",
                        tint = WinampAmberGold
                    )
                }
                ArtworkThumbnail(
                    artworkUri = activeArtist.artworkUri,
                    fallbackIcon = Icons.Default.Person,
                    fallbackTint = SamsungBlue,
                    size = 44.dp,
                    cornerRadius = 8.dp,
                    asCircularDisc = artworkStyle == NowPlayingArtworkStyle.SPINNING_DISC
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeArtist.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee()
                    )
                    Text(
                        text = "${activeArtist.albumCount} albums • ${activeArtist.trackCount} songs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee()
                    )
                }
                AssistChip(
                    onClick = { onJumpToArtistFolders(activeArtist) },
                    label = {
                        Text(
                            text = if (activeArtist.folderPaths.size > 1)
                                "Folders (${activeArtist.folderPaths.size})"
                            else "Folder",
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            // Persistent Horizontal Album Index right below Artist header when albumCount > 1
            if (activeArtist.albumCount > 1 || artistAlbums.size > 1) {
                ArtistHorizontalAlbumIndexBar(
                    artistAlbums = artistAlbums,
                    selectedAlbumTitle = selectedArtistAlbumFilter,
                    onSelectAllTracks = { onSelectArtistAlbumFilter(null) },
                    onSelectAlbum = { albumTitle ->
                        if (selectedArtistAlbumFilter.equals(albumTitle, ignoreCase = true)) {
                            // Tapping the already-selected album chip opens the full Album view
                            onJumpToAlbum(albumTitle)
                        } else {
                            onSelectArtistAlbumFilter(albumTitle)
                        }
                    }
                )
            }

            LazyColumn(
                state = detailListState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(displayedTracks, key = { it.normalizedKey }) { group ->
                    val primary = group.primaryTrack
                    val isPlayingThis = group.allVariants.any { it.filePath == activePlayingPath }
                    DeduplicatedTrackRow(
                        group = group,
                        isCurrentlyPlaying = isPlayingThis,
                        showThumbnail = showListThumbnails,
                        asCircularDisc = artworkStyle == NowPlayingArtworkStyle.SPINNING_DISC,
                        webMetadata = webMetadataMap[primary.filePath],
                        onPlay = { onPlayArtistTrack(group, activeArtist.name) },
                        onOpenNowPlaying = onOpenNowPlaying,
                        onBadgeClick = { onOpenVariantPicker(group) },
                        onPlayNext = { onPlayNext(primary) },
                        onAddAtEnd = { onAddAtEnd(primary) },
                        onAddToPlaylist = { onAddTrackToPlaylist(primary) },
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

    val listState = rememberPersistedLazyListState("artists_root_${sortOption.name}", viewModel)
    val scope = rememberCoroutineScope()

    val sortKeys = remember(artists, sortOption, musicalRootPath) {
        artists.map { artist ->
            when (sortOption) {
                ArtistSortOption.ALPHABETICAL -> artist.name
                ArtistSortOption.DIRECTORY -> {
                    val firstFolder = artist.folderPaths.firstOrNull().orEmpty()
                    MusicalRootScanner.computeRelativePath(musicalRootPath, firstFolder)
                        .trimStart('/', '\\')
                        .substringBefore('/')
                        .substringBefore('\\')
                        .ifBlank { artist.name }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Sort:", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            ArtistSortOption.entries.forEach { opt ->
                FilterChip(
                    selected = sortOption == opt,
                    onClick = { onSortChanged(opt) },
                    label = {
                        Text(
                            text = opt.name.lowercase().replaceFirstChar { it.uppercase() },
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 36.dp)
            ) {
                items(artists, key = { it.name }) { artist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectArtist(artist.name) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showListThumbnails) {
                            ArtworkThumbnail(
                                artworkUri = artist.artworkUri,
                                fallbackIcon = Icons.Default.Person,
                                fallbackTint = SamsungBlue,
                                size = 42.dp,
                                cornerRadius = 8.dp,
                                asCircularDisc = artworkStyle == NowPlayingArtworkStyle.SPINNING_DISC
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = artist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.basicMarquee()
                            )
                            Text(
                                text = "${artist.albumCount} albums • ${artist.trackCount} songs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.basicMarquee()
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
                items = sortKeys,
                listState = listState,
                onLetterSelected = { idx -> scope.launch { listState.scrollToItem(idx) } },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

/**
 * Playlists Tab Page:
 * - Create, Rename, Delete playlists
 * - Enter playlist detail to play, reorder (Move Up/Down), or remove tracks
 * - Includes both Back arrow and "Up to Root" button + recursive scroll position memory
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistsTabPage(
    viewModel: MusikaViewModel,
    playlists: List<Playlist>,
    selectedPlaylistId: Long?,
    selectedPlaylistTracks: List<Track>,
    activePlayingPath: String?,
    showListThumbnails: Boolean,
    artworkStyle: NowPlayingArtworkStyle,
    onSelectPlaylist: (Long) -> Unit,
    onClosePlaylistDetail: () -> Unit,
    onJumpToSectionRoot: () -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onPlayPlaylistTrack: (Track, String) -> Unit,
    onMoveTrackInPlaylist: (Long, Int, Int) -> Unit,
    onRemoveTrackFromPlaylist: (Long, Int) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddAtEnd: (Track) -> Unit,
    onJumpToAlbum: (String) -> Unit,
    onJumpToArtist: (String) -> Unit,
    onJumpToFolder: (Track) -> Unit
) {
    val activePlaylist = playlists.firstOrNull { it.id == selectedPlaylistId }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var renameText by remember { mutableStateOf("") }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            onCreatePlaylist(newPlaylistName.trim())
                            newPlaylistName = ""
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Playlist") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("New Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            onRenamePlaylist(target.id, renameText.trim())
                            renameTarget = null
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (activePlaylist != null) {
        val detailListState = rememberPersistedLazyListState(
            scrollKey = "playlist_detail:${activePlaylist.id}",
            viewModel = viewModel
        )
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClosePlaylistDetail) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Playlists")
                }
                IconButton(onClick = onJumpToSectionRoot) {
                    Icon(
                        imageVector = Icons.Default.KeyboardDoubleArrowUp,
                        contentDescription = "Up to Root",
                        tint = WinampAmberGold
                    )
                }
                ArtworkThumbnail(
                    artworkUri = activePlaylist.artworkUri,
                    fallbackIcon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    fallbackTint = SamsungBlue,
                    size = 44.dp,
                    cornerRadius = 8.dp,
                    asCircularDisc = artworkStyle == NowPlayingArtworkStyle.SPINNING_DISC
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activePlaylist.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee()
                    )
                    Text(
                        text = "${selectedPlaylistTracks.size} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = WinampLedGreen,
                        maxLines = 1
                    )
                }
                IconButton(
                    onClick = {
                        renameText = activePlaylist.name
                        renameTarget = activePlaylist
                    }
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename Playlist")
                }
                IconButton(onClick = { onDeletePlaylist(activePlaylist.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Playlist")
                }
            }

            if (selectedPlaylistTracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "This playlist has no tracks yet. Add songs, albums, or folders from their menu, or save the active Queue as a playlist.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = detailListState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        selectedPlaylistTracks,
                        key = { idx, t -> "${idx}_${t.filePath}" }
                    ) { idx, track ->
                        val isPlayingThis = track.filePath == activePlayingPath
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isPlayingThis) SamsungBlue.copy(alpha = 0.16f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    if (isPlayingThis) onOpenNowPlaying()
                                    else onPlayPlaylistTrack(track, activePlaylist.name)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (showListThumbnails) {
                                ArtworkThumbnail(
                                    artworkUri = track.artworkUri,
                                    fallbackIcon = Icons.Default.MusicNote,
                                    fallbackTint = SamsungBlue,
                                    size = 38.dp,
                                    cornerRadius = 8.dp,
                                    asCircularDisc = artworkStyle == NowPlayingArtworkStyle.SPINNING_DISC
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp)
                            ) {
                                Text(
                                    text = "${idx + 1}. ${track.title}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isPlayingThis) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    color = if (isPlayingThis) WinampLedGreen else Color.White,
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
                                onClick = {
                                    if (idx > 0) onMoveTrackInPlaylist(activePlaylist.id, idx, idx - 1)
                                },
                                enabled = idx > 0,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                            }
                            IconButton(
                                onClick = {
                                    if (idx < selectedPlaylistTracks.lastIndex) {
                                        onMoveTrackInPlaylist(activePlaylist.id, idx, idx + 1)
                                    }
                                },
                                enabled = idx < selectedPlaylistTracks.lastIndex,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                            }
                            IconButton(
                                onClick = { onRemoveTrackFromPlaylist(activePlaylist.id, idx) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove from Playlist")
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                    }
                }
            }
        }
        return
    }

    val listState = rememberPersistedLazyListState("playlists_root", viewModel)
    val scope = rememberCoroutineScope()
    val sortKeys = remember(playlists) { playlists.map { it.name } }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Playlists (${playlists.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Button(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("New Playlist", maxLines = 1, softWrap = false)
            }
        }

        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No playlists yet. Tap 'New Playlist' above, or open the Queue sheet and tap 'Save as Playlist'.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 36.dp)
                ) {
                    items(playlists, key = { it.id }) { pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectPlaylist(pl.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (showListThumbnails) {
                                ArtworkThumbnail(
                                    artworkUri = pl.artworkUri,
                                    fallbackIcon = Icons.AutoMirrored.Filled.PlaylistPlay,
                                    fallbackTint = SamsungBlue,
                                    size = 42.dp,
                                    cornerRadius = 8.dp,
                                    asCircularDisc = artworkStyle == NowPlayingArtworkStyle.SPINNING_DISC
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = pl.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.basicMarquee()
                                )
                                Text(
                                    text = "${pl.trackCount} tracks",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WinampLedGreen,
                                    maxLines = 1
                                )
                            }
                            IconButton(
                                onClick = {
                                    renameText = pl.name
                                    renameTarget = pl
                                }
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename Playlist")
                            }
                            IconButton(onClick = { onDeletePlaylist(pl.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Playlist")
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                    }
                }

                FastAlphabetScrollbar(
                    items = sortKeys,
                    listState = listState,
                    onLetterSelected = { idx -> scope.launch { listState.scrollToItem(idx) } },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }
}

/**
 * Strictly Hierarchical Directory View (Specifications #3, #4, #5, #6):
 * - Starts at the Musical Root Folder.
 * - Sortable by System Default, Alphabetical, and Most Music Files (recursive sum across branch).
 * - Offers quick actions to Jump between sibling Disc folders and Play All Discs.
 * - Preserves exact scrollbar position at every folder level recursively until Root.
 * - Includes a dedicated "Up to Root" button alongside the single-step Back arrow.
 * - NEVER deduplicates format extensions — always displays raw audio files.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun FoldersDirectoryTabPage(
    viewModel: MusikaViewModel,
    currentFolderPath: String,
    currentFolderNode: FolderNode?,
    canNavigateUp: Boolean,
    subfolders: List<FolderNode>,
    rawTracks: List<Track>,
    sortOption: FolderSortOption,
    activePlayingPath: String?,
    showListThumbnails: Boolean,
    artworkStyle: NowPlayingArtworkStyle,
    webMetadataMap: Map<String, WebMetadataEntity>,
    onSortChanged: (FolderSortOption) -> Unit,
    onNavigateUp: () -> Unit,
    onJumpToRootFolder: () -> Unit,
    onOpenSubfolder: (String) -> Unit,
    onPlayAllDiscsInFolder: () -> Unit,
    onAddFolderTracksToPlaylist: () -> Unit,
    onAddTrackToPlaylist: (Track) -> Unit,
    onPlayRawTrack: (Track) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddAtEnd: (Track) -> Unit,
    onJumpToAlbum: (String) -> Unit,
    onJumpToArtist: (String) -> Unit,
    onFetchTrackWebMetadata: (Track) -> Unit,
    onWriteTrackMetadata: (Track) -> Unit
) {
    val normFolderKey = remember(currentFolderPath, sortOption) {
        "folder:${MusicalRootScanner.normalizePath(currentFolderPath)}:${sortOption.name}"
    }
    val listState = rememberPersistedLazyListState(normFolderKey, viewModel)
    val scope = rememberCoroutineScope()

    val combinedKeys = remember(subfolders, rawTracks) {
        subfolders.map { it.folderName } + rawTracks.map { it.fileName }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Current folder header + Up to Parent + Up to Root navigation
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateUp,
                enabled = canNavigateUp
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up to Parent Folder")
            }
            if (canNavigateUp) {
                IconButton(onClick = onJumpToRootFolder) {
                    Icon(
                        imageVector = Icons.Default.KeyboardDoubleArrowUp,
                        contentDescription = "Up to Musical Root Folder",
                        tint = WinampAmberGold
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentFolderNode?.folderName ?: currentFolderPath.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee()
                )
                Text(
                    text = currentFolderPath,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee()
                )
            }
            if (rawTracks.isNotEmpty()) {
                IconButton(onClick = onAddFolderTracksToPlaylist) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = "Add Folder Tracks to Playlist",
                        tint = SamsungBlue
                    )
                }
            }
        }

        // Folder Sort Bar (System Default | Alphabetical | Most Music Files [Recursive Sum])
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = sortOption == FolderSortOption.SYSTEM_DEFAULT,
                onClick = { onSortChanged(FolderSortOption.SYSTEM_DEFAULT) },
                label = { Text("System Default", fontSize = 11.sp, maxLines = 1, softWrap = false) }
            )
            FilterChip(
                selected = sortOption == FolderSortOption.ALPHABETICAL,
                onClick = { onSortChanged(FolderSortOption.ALPHABETICAL) },
                label = { Text("Alphabetical", fontSize = 11.sp, maxLines = 1, softWrap = false) }
            )
            FilterChip(
                selected = sortOption == FolderSortOption.MOST_MUSIC_FILES,
                onClick = { onSortChanged(FolderSortOption.MOST_MUSIC_FILES) },
                label = { Text("Most Music Files", fontSize = 11.sp, maxLines = 1, softWrap = false) }
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
                    label = { Text("Play All Discs", maxLines = 1, softWrap = false) },
                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                siblings.forEach { siblingPath ->
                    val siblingName = siblingPath.substringAfterLast('/').substringAfterLast('\\')
                    AssistChip(
                        onClick = { onOpenSubfolder(siblingPath) },
                        label = { Text("Jump to $siblingName", maxLines = 1, softWrap = false) },
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 36.dp)
            ) {
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
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.basicMarquee()
                            )
                            Text(
                                text = "${folder.recursiveTrackCount} total branch files (${folder.directTrackCount} direct)" +
                                        if (folder.isDiscFolder) " • Disc ${folder.discNumber}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = WinampLedGreen,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.basicMarquee()
                            )
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                }

                // Raw (non-deduplicated) files in this directory
                items(rawTracks, key = { "raw:${it.filePath}" }) { track ->
                    val isPlayingThis = track.filePath == activePlayingPath
                    RawDirectoryTrackRow(
                        track = track,
                        isCurrentlyPlaying = isPlayingThis,
                        showThumbnail = showListThumbnails,
                        asCircularDisc = artworkStyle == NowPlayingArtworkStyle.SPINNING_DISC,
                        webMetadata = webMetadataMap[track.filePath],
                        onPlay = { onPlayRawTrack(track) },
                        onOpenNowPlaying = onOpenNowPlaying,
                        onPlayNext = { onPlayNext(track) },
                        onAddAtEnd = { onAddAtEnd(track) },
                        onAddToPlaylist = { onAddTrackToPlaylist(track) },
                        onJumpToAlbum = { onJumpToAlbum(track.album) },
                        onJumpToArtist = { onJumpToArtist(track.artist) },
                        onFetchWebMetadata = { onFetchTrackWebMetadata(track) },
                        onWriteMetadataToFile = { onWriteTrackMetadata(track) }
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                }
            }

            if (combinedKeys.isNotEmpty()) {
                FastAlphabetScrollbar(
                    items = combinedKeys,
                    listState = listState,
                    onLetterSelected = { idx -> scope.launch { listState.scrollToItem(idx) } },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeduplicatedTrackRow(
    group: DeduplicatedTrackGroup,
    isCurrentlyPlaying: Boolean,
    showThumbnail: Boolean,
    asCircularDisc: Boolean,
    webMetadata: WebMetadataEntity?,
    onPlay: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onBadgeClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddAtEnd: () -> Unit,
    onAddToPlaylist: () -> Unit,
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
            .background(if (isCurrentlyPlaying) SamsungBlue.copy(alpha = 0.16f) else Color.Transparent)
            .clickable {
                if (isCurrentlyPlaying) onOpenNowPlaying() else onPlay()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showThumbnail) {
            ArtworkThumbnail(
                artworkUri = track.artworkUri,
                fallbackIcon = if (isCurrentlyPlaying) Icons.Default.Equalizer else Icons.Default.MusicNote,
                fallbackTint = if (isCurrentlyPlaying) WinampLedGreen else SamsungBlue,
                size = 40.dp,
                cornerRadius = 8.dp,
                asCircularDisc = asCircularDisc
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrentlyPlaying) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = if (isCurrentlyPlaying) WinampLedGreen else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .basicMarquee()
                )
                if (isCurrentlyPlaying) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "PLAYING",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = WinampLedGreen,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(WinampLedGreen.copy(alpha = 0.15f))
                            .border(1.dp, WinampLedGreen, RoundedCornerShape(4.dp))
                            .clickable { onOpenNowPlaying() }
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
                if (isUnsavedWebMatch) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Web Match",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = WinampAmberGold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(WinampAmberGold.copy(alpha = 0.15f))
                            .border(1.dp, WinampAmberGold, RoundedCornerShape(4.dp))
                            .clickable { onWriteMetadataToFile() }
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = "${track.artist} • ${track.album}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee()
            )
            if (isUnsavedWebMatch && webMetadata != null) {
                Text(
                    text = "Candidate: ${webMetadata.candidateTitle} — ${webMetadata.candidateArtist}",
                    style = MaterialTheme.typography.labelSmall,
                    color = WinampAmberGold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee()
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
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (track.alternateVariantsCount > 0) WinampAmberGold else WinampLedGreen,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }

        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "Track Actions")
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                if (isCurrentlyPlaying) {
                    DropdownMenuItem(
                        text = { Text("Open Now Playing Screen") },
                        onClick = {
                            showMenu = false
                            onOpenNowPlaying()
                        }
                    )
                    HorizontalDivider()
                }
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
                DropdownMenuItem(
                    text = { Text("Add to Playlist...") },
                    onClick = {
                        showMenu = false
                        onAddToPlaylist()
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RawDirectoryTrackRow(
    track: Track,
    isCurrentlyPlaying: Boolean,
    showThumbnail: Boolean,
    asCircularDisc: Boolean,
    webMetadata: WebMetadataEntity?,
    onPlay: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onPlayNext: () -> Unit,
    onAddAtEnd: () -> Unit,
    onAddToPlaylist: () -> Unit,
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
            .background(if (isCurrentlyPlaying) SamsungBlue.copy(alpha = 0.16f) else Color.Transparent)
            .clickable {
                if (isCurrentlyPlaying) onOpenNowPlaying() else onPlay()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showThumbnail) {
            ArtworkThumbnail(
                artworkUri = track.artworkUri,
                fallbackIcon = if (isCurrentlyPlaying) Icons.Default.Equalizer else Icons.Default.MusicNote,
                fallbackTint = WinampLedGreen,
                size = 38.dp,
                cornerRadius = 8.dp,
                asCircularDisc = asCircularDisc
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = track.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrentlyPlaying) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = if (isCurrentlyPlaying) WinampLedGreen else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .basicMarquee()
                )
                if (isCurrentlyPlaying) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "PLAYING",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = WinampLedGreen,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(WinampLedGreen.copy(alpha = 0.15f))
                            .border(1.dp, WinampLedGreen, RoundedCornerShape(4.dp))
                            .clickable { onOpenNowPlaying() }
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = "${track.title} • ${track.artist} (${track.audioQuality.telemetrySummary})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee()
            )
            if (isUnsavedWebMatch) {
                Text(
                    text = "Web Match — Unsaved",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = WinampAmberGold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "Raw File Actions")
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                if (isCurrentlyPlaying) {
                    DropdownMenuItem(
                        text = { Text("Open Now Playing Screen") },
                        onClick = {
                            showMenu = false
                            onOpenNowPlaying()
                        }
                    )
                    HorizontalDivider()
                }
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
                DropdownMenuItem(
                    text = { Text("Add to Playlist...") },
                    onClick = {
                        showMenu = false
                        onAddToPlaylist()
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
