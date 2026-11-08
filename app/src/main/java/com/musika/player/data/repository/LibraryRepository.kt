package com.musika.player.data.repository

import com.musika.player.core.model.Album
import com.musika.player.core.model.AlbumSortOption
import com.musika.player.core.model.Artist
import com.musika.player.core.model.DeduplicatedTrackGroup
import com.musika.player.core.model.FolderNode
import com.musika.player.core.model.FolderSortOption
import com.musika.player.core.model.SongSortOption
import com.musika.player.core.model.Track
import com.musika.player.data.local.dao.FolderDao
import com.musika.player.data.local.dao.TrackDao
import com.musika.player.data.local.dao.WebMetadataDao
import com.musika.player.data.local.entity.FolderEntity
import com.musika.player.data.local.entity.TrackEntity
import com.musika.player.data.local.entity.WebMetadataEntity
import com.musika.player.data.metadata.ConfirmationScope
import com.musika.player.data.metadata.MetadataWriteDialogState
import com.musika.player.data.metadata.TrackMetadataDiff
import com.musika.player.data.metadata.WebMetadataFetcher
import com.musika.player.data.scanner.AudioMetadataParser
import com.musika.player.data.scanner.FormatDeduplicator
import com.musika.player.data.scanner.MultiDiscDetector
import com.musika.player.data.scanner.MusicalRootScanner
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Central Clean-Architecture Repository coordinating:
 * - Scoped Musical Root Folder scanning
 * - Smart Format Deduplication in Songs, Albums, and Artists views (and raw files in Directory View)
 * - Multi-Disc recognition & sibling disc folder discovery
 * - Bidirectional cross-navigation (Song <-> Album <-> Artist <-> Folder)
 * - On-demand web metadata fetching & multi-step local file write confirmation enforcement
 */
class LibraryRepository(
    private val trackDao: TrackDao,
    private val folderDao: FolderDao,
    private val webMetadataDao: WebMetadataDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Performs a recursive scan strictly starting at [rootFolder].
     */
    suspend fun rescanLibrary(rootFolder: File): MusicalRootScanner.ScanResult = withContext(Dispatchers.IO) {
        val pendingPaths = trackDao.getAllTracksSnapshot()
            .filter { it.webMatchPending }
            .map { it.filePath }
            .toSet()

        val result = MusicalRootScanner.scan(rootFolder, pendingPaths)
        trackDao.clearAll()
        folderDao.clearAll()
        if (result.tracks.isNotEmpty()) {
            trackDao.insertTracks(result.tracks)
        }
        if (result.folders.isNotEmpty()) {
            folderDao.insertFolders(result.folders)
        }
        result
    }

    /**
     * Streams deduplicated track groups sorted according to [sortOption].
     * Used in the Songs tab (Specification #3 & #6).
     */
    fun getDeduplicatedSongsFlow(sortOption: SongSortOption): Flow<List<DeduplicatedTrackGroup>> {
        val sourceFlow = when (sortOption) {
            SongSortOption.ALPHABETICAL -> trackDao.getAllTracksAlphabetical()
            SongSortOption.ALBUM -> trackDao.getAllTracksByAlbum()
            SongSortOption.ARTIST -> trackDao.getAllTracksByArtist()
        }
        return sourceFlow.map { entities ->
            val rawTracks = entities.map { it.toDomainModel() }
            val groups = FormatDeduplicator.deduplicateTracks(rawTracks)
            when (sortOption) {
                SongSortOption.ALPHABETICAL -> groups.sortedBy { it.primaryTrack.title.lowercase(Locale.US) }
                SongSortOption.ALBUM -> groups.sortedWith(
                    compareBy<DeduplicatedTrackGroup> { it.primaryTrack.album.lowercase(Locale.US) }
                        .thenBy { it.primaryTrack.discNumber }
                        .thenBy { it.primaryTrack.trackNumber }
                )
                SongSortOption.ARTIST -> groups.sortedWith(
                    compareBy<DeduplicatedTrackGroup> { it.primaryTrack.artist.lowercase(Locale.US) }
                        .thenBy { it.primaryTrack.album.lowercase(Locale.US) }
                        .thenBy { it.primaryTrack.discNumber }
                        .thenBy { it.primaryTrack.trackNumber }
                )
            }
        }
    }

    /**
     * Streams deduplicated Albums sorted by Alphabetical or Artist (Specification #3, #4, #5, #6).
     * Automatically detects multi-disc albums and aggregates all folder paths spanned by the album.
     */
    fun getAlbumsFlow(sortOption: AlbumSortOption): Flow<List<Album>> {
        return trackDao.getAllTracksByAlbum().map { entities ->
            val rawTracks = entities.map { it.toDomainModel() }
            val groupedByAlbum = rawTracks.groupBy { it.album.trim().lowercase(Locale.US) }

            val albums = groupedByAlbum.values.mapNotNull { albumRawTracks ->
                if (albumRawTracks.isEmpty()) return@mapNotNull null
                val dedupedGroups = FormatDeduplicator.deduplicateTracks(albumRawTracks)
                val primaryTracks = dedupedGroups.map { it.primaryTrack }
                val first = primaryTracks.first()
                val folderPaths = albumRawTracks.map { it.parentFolderPath }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                val discFolders = folderPaths.filter { path ->
                    val name = path.substringAfterLast('/').substringAfterLast('\\')
                    MultiDiscDetector.isDiscFolder(name)
                }
                val maxDiscTag = albumRawTracks.maxOfOrNull { maxOf(it.discNumber, it.totalDiscs) } ?: 1
                val distinctDiscCount = maxOf(
                    maxDiscTag,
                    albumRawTracks.map { it.discNumber }.distinct().size,
                    discFolders.size
                )

                Album(
                    title = first.album,
                    artist = first.albumArtist ?: first.artist,
                    trackCount = primaryTracks.size,
                    totalDurationMs = primaryTracks.sumOf { it.durationMs },
                    discCount = distinctDiscCount,
                    discFolders = discFolders,
                    folderPaths = folderPaths,
                    year = primaryTracks.firstNotNullOfOrNull { it.year }
                )
            }

            when (sortOption) {
                AlbumSortOption.ALPHABETICAL -> albums.sortedBy { it.title.lowercase(Locale.US) }
                AlbumSortOption.ARTIST -> albums.sortedWith(
                    compareBy<Album> { it.artist.lowercase(Locale.US) }
                        .thenBy { it.title.lowercase(Locale.US) }
                )
            }
        }
    }

    /**
     * Streams Artists sorted Alphabetically (Specification #3 & #4).
     * Aggregates all folderPaths spanned by the artist for 1-2 tap cross-navigation.
     */
    fun getArtistsFlow(): Flow<List<Artist>> {
        return trackDao.getAllTracksByArtist().map { entities ->
            val rawTracks = entities.map { it.toDomainModel() }
            val groupedByArtist = rawTracks.groupBy { it.artist.trim().lowercase(Locale.US) }

            groupedByArtist.values.mapNotNull { artistRawTracks ->
                if (artistRawTracks.isEmpty()) return@mapNotNull null
                val deduped = FormatDeduplicator.deduplicateTracks(artistRawTracks)
                val first = deduped.first().primaryTrack
                val albumCount = deduped.map { it.primaryTrack.album.trim().lowercase(Locale.US) }
                    .distinct()
                    .size
                val folderPaths = artistRawTracks.map { it.parentFolderPath }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()

                Artist(
                    name = first.artist,
                    albumCount = albumCount,
                    trackCount = deduped.size,
                    folderPaths = folderPaths
                )
            }.sortedBy { it.name.lowercase(Locale.US) }
        }
    }

    /**
     * Streams deduplicated tracks for a specific Album, ordered by discNumber ASC, trackNumber ASC.
     */
    fun getDeduplicatedTracksForAlbumFlow(albumTitle: String): Flow<List<DeduplicatedTrackGroup>> {
        return trackDao.getTracksForAlbum(albumTitle).map { entities ->
            val raw = entities.map { it.toDomainModel() }
            FormatDeduplicator.deduplicateTracks(raw).sortedWith(
                compareBy<DeduplicatedTrackGroup> { it.primaryTrack.discNumber }
                    .thenBy { it.primaryTrack.trackNumber }
                    .thenBy { it.primaryTrack.title.lowercase(Locale.US) }
            )
        }
    }

    /**
     * Streams deduplicated tracks for a specific Artist.
     */
    fun getDeduplicatedTracksForArtistFlow(artistName: String): Flow<List<DeduplicatedTrackGroup>> {
        return trackDao.getTracksForArtist(artistName).map { entities ->
            val raw = entities.map { it.toDomainModel() }
            FormatDeduplicator.deduplicateTracks(raw).sortedWith(
                compareBy<DeduplicatedTrackGroup> { it.primaryTrack.album.lowercase(Locale.US) }
                    .thenBy { it.primaryTrack.discNumber }
                    .thenBy { it.primaryTrack.trackNumber }
            )
        }
    }

    /**
     * Directory View (Folders): Streams immediate child folders under [parentPath], sorted by:
     * - SYSTEM_DEFAULT
     * - ALPHABETICAL
     * - MOST_MUSIC_FILES (recursive sum of audio files across entire subfolder branch)
     */
    fun getSubfoldersFlow(parentPath: String, sortOption: FolderSortOption): Flow<List<FolderNode>> {
        val normParent = MusicalRootScanner.normalizePath(parentPath)
        val daoFlow = when (sortOption) {
            FolderSortOption.SYSTEM_DEFAULT -> folderDao.getSubfoldersDefault(normParent)
            FolderSortOption.ALPHABETICAL -> folderDao.getSubfoldersAlphabetical(normParent)
            FolderSortOption.MOST_MUSIC_FILES -> folderDao.getSubfoldersByMusicCount(normParent)
        }
        return daoFlow.map { entities ->
            val nodes = entities.map { it.toDomainNode() }
            MusicalRootScanner.sortFolders(nodes, sortOption)
        }
    }

    suspend fun getFolderNode(folderPath: String): FolderNode? {
        val normPath = MusicalRootScanner.normalizePath(folderPath)
        return folderDao.getFolderByPath(normPath)?.toDomainNode()
    }

    /**
     * Directory View (Folders) MUST NEVER deduplicate tracks — always returns raw files.
     */
    fun getRawTracksInFolderFlow(folderPath: String): Flow<List<Track>> {
        val normPath = MusicalRootScanner.normalizePath(folderPath)
        return trackDao.getTracksInFolder(normPath).map { entities ->
            FormatDeduplicator.rawTracksForDirectoryView(entities.map { it.toDomainModel() })
        }
    }

    /**
     * Returns all tracks across a folder and its sibling disc folders (or entire album) for "Play All Discs".
     */
    suspend fun getTracksAcrossSiblingDiscFolders(folderPath: String): List<Track> = withContext(Dispatchers.IO) {
        val normPath = MusicalRootScanner.normalizePath(folderPath)
        val folderNode = getFolderNode(normPath)
        val allDiscPaths = buildList {
            add(normPath)
            if (folderNode != null) {
                addAll(folderNode.siblingDiscFolders)
            }
        }.distinct()

        val allEntities = trackDao.getAllTracksSnapshot().filter { it.parentFolderPath in allDiscPaths }
        FormatDeduplicator.deduplicateToPrimaryTracks(allEntities.map { it.toDomainModel() })
    }

    /**
     * On-demand action to fetch internet metadata (AcoustID/MusicBrainz) and mark as UNSAVED.
     */
    suspend fun fetchInternetMetadataForTrack(track: Track): WebMetadataEntity = withContext(Dispatchers.IO) {
        val candidate = WebMetadataFetcher.fetchCandidateMetadata(track)
        webMetadataDao.insertMetadata(candidate)
        trackDao.getTrackByPath(track.filePath)?.let { existing ->
            trackDao.updateTrack(existing.copy(webMatchPending = true))
        }
        candidate
    }

    suspend fun fetchInternetMetadataForAlbum(albumTracks: List<Track>): List<WebMetadataEntity> =
        withContext(Dispatchers.IO) {
            albumTracks.map { fetchInternetMetadataForTrack(it) }
        }

    fun getAllWebMetadataFlow(): Flow<Map<String, WebMetadataEntity>> {
        return webMetadataDao.getAllWebMetadataFlow().map { list ->
            list.associateBy { it.trackFilePath }
        }
    }

    /**
     * Builds the initial multi-step confirmation dialog state (Step 1 of 3) with before/after diffs.
     */
    suspend fun buildWriteConfirmationDialog(
        tracks: List<Track>,
        scope: ConfirmationScope,
        targetLabel: String
    ): MetadataWriteDialogState? = withContext(Dispatchers.IO) {
        val diffs = tracks.mapNotNull { track ->
            val candidate = webMetadataDao.getMetadataForTrack(track.filePath) ?: return@mapNotNull null
            TrackMetadataDiff.from(track, candidate)
        }
        if (diffs.isEmpty()) return@withContext null
        MetadataWriteDialogState(
            scope = scope,
            targetLabel = targetLabel,
            diffs = diffs
        )
    }

    /**
     * Commits approved web metadata into local files and Room ONLY if the multi-step
     * confirmation dialog has reached [MetadataWriteDialogState.isWriteAuthorized].
     */
    suspend fun executeConfirmedMetadataWrite(dialogState: MetadataWriteDialogState): Boolean =
        withContext(Dispatchers.IO) {
            require(dialogState.isWriteAuthorized) {
                "Cannot write tags to local files before completing all multi-step confirmation prompts."
            }

            for (diff in dialogState.diffs) {
                val candidate = webMetadataDao.getMetadataForTrack(diff.filePath)
                if (candidate != null) {
                    val file = File(diff.filePath)
                    AudioMetadataParser.writeApprovedMetadataToFile(file, candidate)
                    webMetadataDao.updateMetadata(
                        candidate.copy(status = WebMetadataEntity.STATUS_WRITTEN)
                    )
                }
                val trackEntity = trackDao.getTrackByPath(diff.filePath)
                if (trackEntity != null) {
                    val newNormKey = "${diff.afterArtist.trim().lowercase(Locale.US)}|${diff.afterAlbum.trim().lowercase(Locale.US)}|${diff.afterTitle.trim().lowercase(Locale.US)}"
                    trackDao.updateTrack(
                        trackEntity.copy(
                            title = diff.afterTitle,
                            artist = diff.afterArtist,
                            album = diff.afterAlbum,
                            year = diff.afterYear,
                            trackNumber = diff.afterTrackNumber,
                            discNumber = diff.afterDiscNumber,
                            normalizedKey = newNormKey,
                            webMatchPending = false
                        )
                    )
                }
            }
            true
        }

    private fun FolderEntity.toDomainNode(): FolderNode {
        val siblings = try {
            json.decodeFromString<List<String>>(siblingDiscFoldersJson)
        } catch (_: Exception) {
            emptyList()
        }
        return toDomain(siblings)
    }
}
