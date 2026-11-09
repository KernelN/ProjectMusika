package com.musika.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musika.player.core.model.Album
import com.musika.player.core.model.AlbumSortOption
import com.musika.player.core.model.Artist
import com.musika.player.core.model.ArtistSortOption
import com.musika.player.core.model.DeduplicatedTrackGroup
import com.musika.player.core.model.FolderNode
import com.musika.player.core.model.FolderSortOption
import com.musika.player.core.model.LoopMode
import com.musika.player.core.model.NowPlayingArtworkStyle
import com.musika.player.core.model.RandomMode
import com.musika.player.core.model.SongSortOption
import com.musika.player.core.model.Track
import com.musika.player.core.sync.RemoteSyncContract
import com.musika.player.data.local.entity.WebMetadataEntity
import com.musika.player.data.metadata.ConfirmationScope
import com.musika.player.data.metadata.MetadataWriteDialogState
import com.musika.player.data.metadata.WriteConfirmationStep
import com.musika.player.data.repository.LibraryRepository
import com.musika.player.data.repository.SettingsPreferences
import com.musika.player.data.scanner.MusicalRootScanner
import com.musika.player.player.DualQueueEngine
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MusikaTab(val title: String) {
    SONGS("Songs"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    FOLDERS("Folders")
}

/**
 * State for the Multi-Folder Compact Bottom Sheet (Specification #4):
 * Opens in Compact mode by default (showExpandedPaths = false) with a toggleable expanded path view.
 */
data class MultiFolderSheetState(
    val sourceTitle: String,
    val folderPaths: List<String>,
    val showExpandedPaths: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class MusikaViewModel(
    private val repository: LibraryRepository,
    private val settings: SettingsPreferences
) : ViewModel() {

    val dualQueueEngine = DualQueueEngine(
        initialRandomMode = settings.randomMode.value,
        initialLoopMode = settings.loopMode.value,
        onModesChanged = { newRandom, newLoop ->
            settings.setRandomMode(newRandom)
            settings.setLoopMode(newLoop)
        }
    )

    val playbackState: StateFlow<DualQueueEngine.EngineState> = dualQueueEngine.state

    private val _selectedTab = MutableStateFlow(MusikaTab.SONGS)
    val selectedTab: StateFlow<MusikaTab> = _selectedTab.asStateFlow()

    private val _selectedAlbumDetail = MutableStateFlow<String?>(null)
    val selectedAlbumDetail: StateFlow<String?> = _selectedAlbumDetail.asStateFlow()

    private val _selectedArtistDetail = MutableStateFlow<String?>(null)
    val selectedArtistDetail: StateFlow<String?> = _selectedArtistDetail.asStateFlow()

    private val _currentFolderPath = MutableStateFlow(settings.musicalRootPath.value)
    val currentFolderPath: StateFlow<String> = _currentFolderPath.asStateFlow()

    private val _currentFolderNode = MutableStateFlow<FolderNode?>(null)
    val currentFolderNode: StateFlow<FolderNode?> = _currentFolderNode.asStateFlow()

    private val _multiFolderSheetState = MutableStateFlow<MultiFolderSheetState?>(null)
    val multiFolderSheetState: StateFlow<MultiFolderSheetState?> = _multiFolderSheetState.asStateFlow()

    private val _variantPickerGroup = MutableStateFlow<DeduplicatedTrackGroup?>(null)
    val variantPickerGroup: StateFlow<DeduplicatedTrackGroup?> = _variantPickerGroup.asStateFlow()

    private val _writeConfirmationDialogState = MutableStateFlow<MetadataWriteDialogState?>(null)
    val writeConfirmationDialogState: StateFlow<MetadataWriteDialogState?> =
        _writeConfirmationDialogState.asStateFlow()

    private val _showNowPlayingOverlay = MutableStateFlow(false)
    val showNowPlayingOverlay: StateFlow<Boolean> = _showNowPlayingOverlay.asStateFlow()

    private val _showQueueSheet = MutableStateFlow(false)
    val showQueueSheet: StateFlow<Boolean> = _showQueueSheet.asStateFlow()

    private val _showSettingsDialog = MutableStateFlow(false)
    val showSettingsDialog: StateFlow<Boolean> = _showSettingsDialog.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    val musicalRootPath: StateFlow<String> = settings.musicalRootPath
    val alwaysScanEntirePhone: StateFlow<Boolean> = settings.alwaysScanEntirePhone
    val hasRequestedInitialPermissions: StateFlow<Boolean> = settings.hasRequestedInitialPermissions
    val songSortOption: StateFlow<SongSortOption> = settings.songSortOption
    val albumSortOption: StateFlow<AlbumSortOption> = settings.albumSortOption
    val artistSortOption: StateFlow<ArtistSortOption> = settings.artistSortOption
    val folderSortOption: StateFlow<FolderSortOption> = settings.folderSortOption
    val artworkStyle: StateFlow<NowPlayingArtworkStyle> = settings.artworkStyle
    val showListTrackThumbnails: StateFlow<Boolean> = settings.showListTrackThumbnails

    val deduplicatedSongs: StateFlow<List<DeduplicatedTrackGroup>> = settings.songSortOption
        .flatMapLatest { sort -> repository.getDeduplicatedSongsFlow(sort) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albums: StateFlow<List<Album>> = settings.albumSortOption
        .flatMapLatest { sort -> repository.getAlbumsFlow(sort) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val artists: StateFlow<List<Artist>> = settings.artistSortOption
        .flatMapLatest { sort -> repository.getArtistsFlow(sort) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentSubfolders: StateFlow<List<FolderNode>> = combine(
        _currentFolderPath,
        settings.folderSortOption
    ) { path, sort -> Pair(path, sort) }
        .flatMapLatest { (path, sort) -> repository.getSubfoldersFlow(path, sort) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentFolderRawTracks: StateFlow<List<Track>> = _currentFolderPath
        .flatMapLatest { path -> repository.getRawTracksInFolderFlow(path) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedAlbumTracks: StateFlow<List<DeduplicatedTrackGroup>> = _selectedAlbumDetail
        .flatMapLatest { albumTitle ->
            if (albumTitle == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else repository.getDeduplicatedTracksForAlbumFlow(albumTitle)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedArtistTracks: StateFlow<List<DeduplicatedTrackGroup>> = _selectedArtistDetail
        .flatMapLatest { artistName ->
            if (artistName == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else repository.getDeduplicatedTracksForArtistFlow(artistName)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val webMetadataMap: StateFlow<Map<String, WebMetadataEntity>> = repository.getAllWebMetadataFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        viewModelScope.launch {
            _currentFolderPath.collect { path ->
                _currentFolderNode.value = repository.getFolderNode(path)
            }
        }
    }

    fun selectTab(tab: MusikaTab) {
        _selectedTab.value = tab
    }

    fun setSongSort(option: SongSortOption) = settings.setSongSortOption(option)
    fun setAlbumSort(option: AlbumSortOption) = settings.setAlbumSortOption(option)
    fun setArtistSort(option: ArtistSortOption) = settings.setArtistSortOption(option)
    fun setFolderSort(option: FolderSortOption) = settings.setFolderSortOption(option)
    fun setArtworkStyle(style: NowPlayingArtworkStyle) = settings.setArtworkStyle(style)
    fun setShowListTrackThumbnails(enabled: Boolean) = settings.setShowListTrackThumbnails(enabled)

    fun setMusicalRootFolder(newRootPath: String) {
        settings.setMusicalRootPath(newRootPath)
        _currentFolderPath.value = settings.musicalRootPath.value
        triggerLibraryScan()
    }

    fun setAlwaysScanEntirePhone(enabled: Boolean) {
        settings.setAlwaysScanEntirePhone(enabled)
        triggerLibraryScan()
    }

    fun markInitialPermissionsRequested() {
        settings.markInitialPermissionsRequested()
    }

    fun triggerLibraryScan() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                val root = settings.resolveEffectiveScanRoot()
                if (!root.exists()) {
                    root.mkdirs()
                }
                val result = repository.rescanLibrary(root)
                _currentFolderPath.value = result.rootPath
                _currentFolderNode.value = repository.getFolderNode(result.rootPath)
                _statusBannerMessage.value =
                    "Scanned ${result.tracks.size} audio files in ${result.folders.size} folders"
            } finally {
                _isScanning.value = false
            }
        }
    }

    // =========================================================================
    // BIDIRECTIONAL CROSS-NAVIGATION (1-2 TAPS — SPECIFICATION #4)
    // =========================================================================

    fun jumpToAlbum(albumTitle: String) {
        _selectedArtistDetail.value = null
        _selectedAlbumDetail.value = albumTitle
        _selectedTab.value = MusikaTab.ALBUMS
    }

    fun closeAlbumDetail() {
        _selectedAlbumDetail.value = null
    }

    fun jumpToArtist(artistName: String) {
        _selectedAlbumDetail.value = null
        _selectedArtistDetail.value = artistName
        _selectedTab.value = MusikaTab.ARTISTS
    }

    fun closeArtistDetail() {
        _selectedArtistDetail.value = null
    }

    /**
     * Jumps to the folder(s) associated with a Song, Album, or Artist.
     * - If 1 folder: navigates directly to that folder in the Folders tab.
     * - If 2+ folders: opens the Compact Bottom Sheet by default (with toggleable expanded path view).
     */
    fun jumpToFolders(folderPaths: List<String>, sourceTitle: String) {
        val cleanPaths = folderPaths.filter { it.isNotBlank() }.distinct()
        if (cleanPaths.isEmpty()) return
        if (cleanPaths.size == 1) {
            openSingleFolderInDirectoryView(cleanPaths.first())
        } else {
            _multiFolderSheetState.value = MultiFolderSheetState(
                sourceTitle = sourceTitle,
                folderPaths = cleanPaths,
                showExpandedPaths = false // Compact Bottom Sheet by default!
            )
        }
    }

    fun toggleMultiFolderSheetExpanded() {
        _multiFolderSheetState.value = _multiFolderSheetState.value?.let {
            it.copy(showExpandedPaths = !it.showExpandedPaths)
        }
    }

    fun dismissMultiFolderSheet() {
        _multiFolderSheetState.value = null
    }

    fun openSingleFolderInDirectoryView(folderPath: String) {
        _multiFolderSheetState.value = null
        _selectedAlbumDetail.value = null
        _selectedArtistDetail.value = null
        val norm = MusicalRootScanner.normalizePath(folderPath)
        _currentFolderPath.value = norm
        _selectedTab.value = MusikaTab.FOLDERS
        viewModelScope.launch {
            _currentFolderNode.value = repository.getFolderNode(norm)
        }
    }

    fun canNavigateUpFolder(): Boolean {
        val current = MusicalRootScanner.normalizePath(_currentFolderPath.value)
        val root = MusicalRootScanner.normalizePath(settings.resolveEffectiveScanRoot().absolutePath)
        return !current.equals(root, ignoreCase = true) && File(current).parentFile != null
    }

    fun navigateUpFolder(): Boolean {
        val current = MusicalRootScanner.normalizePath(_currentFolderPath.value)
        val root = MusicalRootScanner.normalizePath(settings.resolveEffectiveScanRoot().absolutePath)
        if (current.equals(root, ignoreCase = true)) return false
        val parent = File(current).parentFile?.absolutePath ?: return false
        val normParent = MusicalRootScanner.normalizePath(parent)
        _currentFolderPath.value = normParent
        viewModelScope.launch {
            _currentFolderNode.value = repository.getFolderNode(normParent)
        }
        return true
    }

    // =========================================================================
    // PLAYBACK & DUAL QUEUE + MULTI-DISC + FORMAT VARIANT SELECTION
    // =========================================================================

    fun playSongGroup(group: DeduplicatedTrackGroup, contextGroups: List<DeduplicatedTrackGroup>, contextLabel: String) {
        val contextPrimaryTracks = contextGroups.map { it.primaryTrack }
        dualQueueEngine.playFromContext(
            selectedTrack = group.primaryTrack,
            contextTracks = contextPrimaryTracks,
            contextName = contextLabel
        )
    }

    fun playRawTrackInFolder(track: Track, folderTracks: List<Track>, folderName: String) {
        dualQueueEngine.playFromContext(
            selectedTrack = track,
            contextTracks = folderTracks,
            contextName = "Folder: $folderName"
        )
    }

    fun openFormatVariantPicker(group: DeduplicatedTrackGroup) {
        _variantPickerGroup.value = group
    }

    fun dismissFormatVariantPicker() {
        _variantPickerGroup.value = null
    }

    fun playSelectedFormatVariant(group: DeduplicatedTrackGroup, chosenVariant: Track) {
        _variantPickerGroup.value = null
        val currentContext = playbackState.value.standardQueue.ifEmpty { listOf(chosenVariant) }
        val updatedContext = currentContext.map {
            if (it.normalizedKey == group.normalizedKey) chosenVariant else it
        }
        dualQueueEngine.playFromContext(
            selectedTrack = chosenVariant,
            contextTracks = updatedContext,
            contextName = playbackState.value.activeContextName ?: "Manual Format Selection"
        )
    }

    fun playAllDiscsFromFolder(folderPath: String, folderName: String) {
        viewModelScope.launch {
            val allDiscTracks = repository.getTracksAcrossSiblingDiscFolders(folderPath)
            dualQueueEngine.playAllDiscs(allDiscTracks, folderName)
        }
    }

    fun playAllDiscsForAlbum(albumTitle: String) {
        viewModelScope.launch {
            val groups = repository.getDeduplicatedTracksForAlbumFlow(albumTitle).first()
            val primaryTracks = groups.map { it.primaryTrack }
            dualQueueEngine.playAllDiscs(primaryTracks, albumTitle)
        }
    }

    fun enqueuePlayNext(track: Track) {
        dualQueueEngine.enqueueImmediate(track)
        _statusBannerMessage.value = "Added '${track.title}' to Play Next (FIFO Priority Queue)"
    }

    fun enqueueAddAtEnd(track: Track) {
        dualQueueEngine.enqueueAtEnd(track)
        _statusBannerMessage.value = "Added '${track.title}' to end of Queue"
    }

    fun cycleRandomMode(): RandomMode = dualQueueEngine.cycleRandomMode()
    fun cycleLoopMode(): LoopMode = dualQueueEngine.cycleLoopMode()
    fun skipNext() = dualQueueEngine.skipToNext(autoTriggered = false)
    fun skipPrevious() = dualQueueEngine.skipToPrevious()
    fun togglePlayPause() = dualQueueEngine.togglePlayPause()
    fun seekTo(positionMs: Long) = dualQueueEngine.seekTo(positionMs)

    fun setShowNowPlayingOverlay(visible: Boolean) {
        _showNowPlayingOverlay.value = visible
    }

    fun setShowQueueSheet(visible: Boolean) {
        _showQueueSheet.value = visible
    }

    fun setShowSettingsDialog(visible: Boolean) {
        _showSettingsDialog.value = visible
    }

    fun clearStatusBanner() {
        _statusBannerMessage.value = null
    }

    // =========================================================================
    // ON-DEMAND INTERNET METADATA & MULTI-STEP WRITE CONFIRMATION (SPEC #7)
    // =========================================================================

    fun fetchInternetMetadataForTrack(track: Track) {
        viewModelScope.launch {
            val candidate = repository.fetchInternetMetadataForTrack(track)
            _statusBannerMessage.value =
                "Web Match — Unsaved: ${candidate.candidateTitle} (${candidate.candidateArtist})"
        }
    }

    fun fetchInternetMetadataForAlbum(albumTitle: String, tracks: List<Track>) {
        viewModelScope.launch {
            repository.fetchInternetMetadataForAlbum(tracks)
            _statusBannerMessage.value =
                "Web Match — Unsaved fetched for ${tracks.size} tracks in '$albumTitle'"
        }
    }

    fun startTrackWriteConfirmation(track: Track) {
        viewModelScope.launch {
            if (webMetadataMap.value[track.filePath] == null) {
                repository.fetchInternetMetadataForTrack(track)
            }
            val dialog = repository.buildWriteConfirmationDialog(
                tracks = listOf(track),
                scope = ConfirmationScope.PER_TRACK,
                targetLabel = track.title
            )
            _writeConfirmationDialogState.value = dialog
        }
    }

    fun startAlbumWriteConfirmation(albumTitle: String, tracks: List<Track>) {
        viewModelScope.launch {
            val missing = tracks.filter { webMetadataMap.value[it.filePath] == null }
            if (missing.isNotEmpty()) {
                repository.fetchInternetMetadataForAlbum(missing)
            }
            val dialog = repository.buildWriteConfirmationDialog(
                tracks = tracks,
                scope = ConfirmationScope.PER_ALBUM,
                targetLabel = albumTitle
            )
            _writeConfirmationDialogState.value = dialog
        }
    }

    fun confirmNextWriteStep() {
        val current = _writeConfirmationDialogState.value ?: return
        val advanced = current.confirmCurrentStep()
        _writeConfirmationDialogState.value = advanced
        if (advanced.currentStep == WriteConfirmationStep.COMPLETED_READY_TO_WRITE) {
            viewModelScope.launch {
                repository.executeConfirmedMetadataWrite(advanced)
                _writeConfirmationDialogState.value = null
                _statusBannerMessage.value =
                    "Wrote approved metadata to ${advanced.diffs.size} local file(s)."
            }
        }
    }

    fun stepBackWriteConfirmation() {
        _writeConfirmationDialogState.value = _writeConfirmationDialogState.value?.stepBack()
    }

    fun dismissWriteConfirmation() {
        _writeConfirmationDialogState.value = null
    }

    // =========================================================================
    // STAGE 2 DESKTOP SYNC CONTRACT SERIALIZATION
    // =========================================================================

    fun exportRemoteSyncSnapshotJson(lanHost: String? = null): String {
        return RemoteSyncContract.serializeSnapshot(dualQueueEngine.toPlaybackSnapshot(lanHost))
    }

    fun applyRemoteSyncCommandJson(payload: String, availableTracks: List<Track>) {
        val command = RemoteSyncContract.deserializeCommand(payload)
        dualQueueEngine.applySyncCommand(command, availableTracks)
    }

    class Factory(
        private val repository: LibraryRepository,
        private val settings: SettingsPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MusikaViewModel(repository, settings) as T
        }
    }
}
