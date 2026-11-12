package com.musika.player.data.repository

import com.musika.player.core.model.Album
import com.musika.player.core.model.AlbumSortOption
import com.musika.player.core.model.Artist
import com.musika.player.core.model.ArtistSortOption
import com.musika.player.core.model.DeduplicatedTrackGroup
import com.musika.player.core.model.FolderNode
import com.musika.player.core.model.FolderSortOption
import com.musika.player.core.model.Playlist
import com.musika.player.core.model.SongSortOption
import com.musika.player.core.model.Track
import com.musika.player.data.local.dao.FolderDao
import com.musika.player.data.local.dao.PlaylistDao
import com.musika.player.data.local.dao.TrackDao
import com.musika.player.data.local.dao.WebMetadataDao
import com.musika.player.data.local.entity.FolderEntity
import com.musika.player.data.local.entity.PlaylistEntity
import com.musika.player.data.local.entity.PlaylistEntryEntity
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Central Clean-Architecture Repository coordinating:
 * - Scoped Musical Root Folder scanning (with incremental O(1) reuse + multi-core cold parsing)
 * - Smart Format Deduplication in Songs, Albums, and Artists views (and raw files in Directory View)
 * - Multi-Disc recognition & sibling disc folder discovery
 * - Bidirectional cross-navigation (Song <-> Album <-> Artist <-> Folder)
 * - Playlists CRUD, reordering, and Queue-to-Playlist conversion
 * - On-demand web metadata fetching & multi-step local file write confirmation enforcement
 */
class LibraryRepository(
    private val trackDao: TrackDao,
    private val folderDao: FolderDao,
    private val webMetadataDao: WebMetadataDao,
    private val playlistDao: PlaylistDao? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Performs a recursive scan strictly starting at [rootFolder].
     * Reuses unchanged TrackEntities in O(1) and upserts in chunks without wiping existing Room rows first,
     * eliminating startup freezes and unnecessary full re-parses.
     */
    suspend fun rescanLibrary(rootFolder: File): MusicalRootScanner.ScanResult = withContext(Dispatchers.IO) {
        val existingTracks = trackDao.getAllTracksSnapshot()
        val existingByPath = existingTracks.associateBy { it.filePath }
        val pendingPaths = existingTracks
            .filter { it.webMatchPending }
            .map { it.filePath }
            .toSet()

        val result = MusicalRootScanner.scan(
            musicalRootFolder = rootFolder,
            existingPendingWebPaths = pendingPaths,
            existingTracksByPath = existingByPath
        )

        if (result.tracks.isEmpty()) {
            trackDao.clearAll()
            folderDao.clearAll()
            return@withContext result
        }

        val activeTrackPaths = result.tracks.map { it.filePath }.toHashSet()
        val staleTrackPaths = existingByPath.keys.filter { it !in activeTrackPaths }
        for (chunk in staleTrackPaths.chunked(500)) {
            trackDao.deleteTracksByPaths(chunk)
        }

        // Only insert/replace tracks that are new or whose metadata/timestamp changed
        val tracksToUpsert = result.tracks.filter { scanned ->
            existingByPath[scanned.filePath] != scanned
        }
        for (chunk in tracksToUpsert.chunked(500)) {
            trackDao.insertTracks(chunk)
        }

        val existingFolders = folderDao.getAllFolders().associateBy { it.absolutePath }
        val activeFolderPaths = result.folders.map { it.absolutePath }.toHashSet()
        val staleFolderPaths = existingFolders.keys.filter { it !in activeFolderPaths }
        for (chunk in staleFolderPaths.chunked(500)) {
            folderDao.deleteFoldersByPaths(chunk)
        }

        val foldersToUpsert = result.folders.filter { scanned ->
            existingFolders[scanned.absolutePath] != scanned
        }
        for (chunk in foldersToUpsert.chunked(500)) {
            folderDao.insertFolders(chunk)
        }

        result
    }

    /**
     * Streams deduplicated track groups sorted according to [sortOption].
     * Used in the Songs tab (Specification #3 & #6).
     */
    fun getDeduplicatedSongsFlow(sortOption: SongSortOption): Flow<List<DeduplicatedTrackGroup>> {
        val sourceFlow = when (sortOption) {
            SongSortOption.ALPHABETICAL, SongSortOption.DIRECTORY -> trackDao.getAllTracksAlphabetical()
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
                SongSortOption.DIRECTORY -> groups.sortedWith { a, b ->
                    val pathA = a.primaryTrack.relativePath.ifBlank { a.primaryTrack.filePath }
                    val pathB = b.primaryTrack.relativePath.ifBlank { b.primaryTrack.filePath }
                    val cmp = MusicalRootScanner.HierarchicalPathComparator.compare(pathA, pathB)
                    if (cmp != 0) cmp else a.primaryTrack.title.lowercase(Locale.US).compareTo(b.primaryTrack.title.lowercase(Locale.US))
                }
            }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Streams deduplicated Albums sorted by Alphabetical, Artist, or Directory (Specification #3, #4, #5, #6).
     * Automatically detects multi-disc albums and aggregates all folder paths spanned by the album.
     */
    fun getAlbumsFlow(sortOption: AlbumSortOption): Flow<List<Album>> {
        return trackDao.getAllTracksByAlbum().map { entities ->
            val rawTracks = entities.map { it.toDomainModel() }
            val groupedByAlbum = rawTracks.groupBy { it.album.trim().lowercase(Locale.US) }

            data class AlbumWithPrimaryRelPath(val album: Album, val earliestRelPath: String)

            val albumsWithPaths = groupedByAlbum.values.mapNotNull { albumRawTracks ->
                if (albumRawTracks.isEmpty()) return@mapNotNull null
                val dedupedGroups = FormatDeduplicator.deduplicateTracks(albumRawTracks)
                val primaryTracks = dedupedGroups.map { it.primaryTrack }
                val first = primaryTracks.first()
                val folderPaths = albumRawTracks.map { it.parentFolderPath }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sortedWith(MusicalRootScanner.HierarchicalPathComparator)
                val earliestRelPath = albumRawTracks
                    .map { it.relativePath.ifBlank { it.filePath } }
                    .minWithOrNull(MusicalRootScanner.HierarchicalPathComparator)
                    ?: folderPaths.firstOrNull().orEmpty()
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

                AlbumWithPrimaryRelPath(
                    album = Album(
                        title = first.album,
                        artist = first.albumArtist ?: first.artist,
                        trackCount = primaryTracks.size,
                        totalDurationMs = primaryTracks.sumOf { it.durationMs },
                        discCount = distinctDiscCount,
                        discFolders = discFolders,
                        folderPaths = folderPaths,
                        year = primaryTracks.firstNotNullOfOrNull { it.year },
                        artworkUri = primaryTracks.firstNotNullOfOrNull { it.artworkUri }
                    ),
                    earliestRelPath = earliestRelPath
                )
            }

            when (sortOption) {
                AlbumSortOption.ALPHABETICAL -> albumsWithPaths
                    .sortedBy { it.album.title.lowercase(Locale.US) }
                    .map { it.album }
                AlbumSortOption.ARTIST -> albumsWithPaths
                    .sortedWith(
                        compareBy<AlbumWithPrimaryRelPath> { it.album.artist.lowercase(Locale.US) }
                            .thenBy { it.album.title.lowercase(Locale.US) }
                    )
                    .map { it.album }
                AlbumSortOption.DIRECTORY -> albumsWithPaths
                    .sortedWith { a, b ->
                        val cmp = MusicalRootScanner.HierarchicalPathComparator.compare(a.earliestRelPath, b.earliestRelPath)
                        if (cmp != 0) cmp else a.album.title.lowercase(Locale.US).compareTo(b.album.title.lowercase(Locale.US))
                    }
                    .map { it.album }
            }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Streams Artists sorted Alphabetically or by Directory (Specification #3 & #4).
     * Aggregates all folderPaths spanned by the artist for 1-2 tap cross-navigation.
     */
    fun getArtistsFlow(sortOption: ArtistSortOption = ArtistSortOption.ALPHABETICAL): Flow<List<Artist>> {
        return trackDao.getAllTracksByArtist().map { entities ->
            val rawTracks = entities.map { it.toDomainModel() }
            val groupedByArtist = rawTracks.groupBy { it.artist.trim().lowercase(Locale.US) }

            data class ArtistWithPrimaryRelPath(val artist: Artist, val earliestRelPath: String)

            val artistsWithPaths = groupedByArtist.values.mapNotNull { artistRawTracks ->
                if (artistRawTracks.isEmpty()) return@mapNotNull null
                val deduped = FormatDeduplicator.deduplicateTracks(artistRawTracks)
                val first = deduped.first().primaryTrack
                val albumCount = deduped.map { it.primaryTrack.album.trim().lowercase(Locale.US) }
                    .distinct()
                    .size
                val folderPaths = artistRawTracks.map { it.parentFolderPath }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sortedWith(MusicalRootScanner.HierarchicalPathComparator)
                val earliestRelPath = artistRawTracks
                    .map { it.relativePath.ifBlank { it.filePath } }
                    .minWithOrNull(MusicalRootScanner.HierarchicalPathComparator)
                    ?: folderPaths.firstOrNull().orEmpty()

                ArtistWithPrimaryRelPath(
                    artist = Artist(
                        name = first.artist,
                        albumCount = albumCount,
                        trackCount = deduped.size,
                        folderPaths = folderPaths,
                        artworkUri = deduped.firstNotNullOfOrNull { it.primaryTrack.artworkUri }
                    ),
                    earliestRelPath = earliestRelPath
                )
            }

            when (sortOption) {
                ArtistSortOption.ALPHABETICAL -> artistsWithPaths
                    .sortedBy { it.artist.name.lowercase(Locale.US) }
                    .map { it.artist }
                ArtistSortOption.DIRECTORY -> artistsWithPaths
                    .sortedWith { a, b ->
                        val cmp = MusicalRootScanner.HierarchicalPathComparator.compare(a.earliestRelPath, b.earliestRelPath)
                        if (cmp != 0) cmp else a.artist.name.lowercase(Locale.US).compareTo(b.artist.name.lowercase(Locale.US))
                    }
                    .map { it.artist }
            }
        }.flowOn(Dispatchers.Default)
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
        }.flowOn(Dispatchers.Default)
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
        }.flowOn(Dispatchers.Default)
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
        }.flowOn(Dispatchers.Default)
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
        }.flowOn(Dispatchers.Default)
    }

    // -------------------------------------------------------------------------
    // Playlists CRUD, Reordering & Queue-to-Playlist Conversion
    // -------------------------------------------------------------------------

    fun getPlaylistsFlow(): Flow<List<Playlist>> {
        val dao = playlistDao ?: return flowOf(emptyList())
        return combine(
            dao.getAllPlaylistsFlow(),
            dao.getAllPlaylistEntriesFlow(),
            trackDao.getAllTracksAlphabetical()
        ) { playlistEntities, entryEntities, trackEntities ->
            val tracksByPath = trackEntities.associate { it.filePath to it.toDomainModel() }
            val entriesByPlaylist = entryEntities.groupBy { it.playlistId }
            playlistEntities.map { pl ->
                val orderedTracks = entriesByPlaylist[pl.id]
                    .orEmpty()
                    .sortedBy { it.positionIndex }
                    .mapNotNull { entry -> tracksByPath[entry.trackFilePath] }
                Playlist(
                    id = pl.id,
                    name = pl.name,
                    trackCount = orderedTracks.size,
                    totalDurationMs = orderedTracks.sumOf { it.durationMs },
                    artworkUri = orderedTracks.firstNotNullOfOrNull { it.artworkUri },
                    trackFilePaths = orderedTracks.map { it.filePath },
                    createdAt = pl.createdAt
                )
            }
        }.flowOn(Dispatchers.Default)
    }

    fun getTracksForPlaylistFlow(playlistId: Long): Flow<List<Track>> {
        val dao = playlistDao ?: return flowOf(emptyList())
        return combine(
            dao.getEntriesForPlaylistFlow(playlistId),
            trackDao.getAllTracksAlphabetical()
        ) { entries, trackEntities ->
            val tracksByPath = trackEntities.associate { it.filePath to it.toDomainModel() }
            entries.sortedBy { it.positionIndex }.mapNotNull { entry ->
                tracksByPath[entry.trackFilePath]
            }
        }.flowOn(Dispatchers.Default)
    }

    suspend fun createPlaylist(name: String, initialTracks: List<Track> = emptyList()): Long =
        withContext(Dispatchers.IO) {
            val dao = playlistDao ?: return@withContext -1L
            val trimmed = name.trim().ifEmpty { "New Playlist" }
            val playlistId = dao.insertPlaylist(PlaylistEntity(name = trimmed))
            if (initialTracks.isNotEmpty()) {
                val entries = initialTracks.mapIndexed { idx, track ->
                    PlaylistEntryEntity(
                        playlistId = playlistId,
                        trackFilePath = track.filePath,
                        positionIndex = idx
                    )
                }
                dao.insertEntries(entries)
            }
            playlistId
        }

    suspend fun renamePlaylist(playlistId: Long, newName: String) = withContext(Dispatchers.IO) {
        val dao = playlistDao ?: return@withContext
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) {
            dao.renamePlaylist(playlistId, trimmed)
        }
    }

    suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        playlistDao?.deletePlaylist(playlistId)
    }

    suspend fun addTracksToPlaylist(playlistId: Long, tracks: List<Track>) = withContext(Dispatchers.IO) {
        val dao = playlistDao ?: return@withContext
        if (tracks.isEmpty()) return@withContext
        val startIdx = dao.getMaxPositionIndex(playlistId) + 1
        val entries = tracks.mapIndexed { offset, track ->
            PlaylistEntryEntity(
                playlistId = playlistId,
                trackFilePath = track.filePath,
                positionIndex = startIdx + offset
            )
        }
        dao.insertEntries(entries)
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, positionIndex: Int) = withContext(Dispatchers.IO) {
        val dao = playlistDao ?: return@withContext
        val current = dao.getEntriesForPlaylistSnapshot(playlistId)
            .sortedBy { it.positionIndex }
            .map { it.trackFilePath }
            .toMutableList()
        if (positionIndex in current.indices) {
            current.removeAt(positionIndex)
            dao.replaceEntriesForPlaylist(playlistId, current)
        }
    }

    suspend fun moveTrackInPlaylist(playlistId: Long, fromIndex: Int, toIndex: Int) = withContext(Dispatchers.IO) {
        val dao = playlistDao ?: return@withContext
        val current = dao.getEntriesForPlaylistSnapshot(playlistId)
            .sortedBy { it.positionIndex }
            .map { it.trackFilePath }
            .toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices && fromIndex != toIndex) {
            val moved = current.removeAt(fromIndex)
            current.add(toIndex, moved)
            dao.replaceEntriesForPlaylist(playlistId, current)
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
