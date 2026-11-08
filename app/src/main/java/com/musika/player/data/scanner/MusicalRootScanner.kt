package com.musika.player.data.scanner

import com.musika.player.core.model.FolderNode
import com.musika.player.core.model.FolderSortOption
import com.musika.player.data.local.entity.FolderEntity
import com.musika.player.data.local.entity.TrackEntity
import java.io.File
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Scoped Musical Root Folder Scanner (Specifications #2, #3, #5).
 * - Traverses strictly from the configured Musical Root Folder downward (or storage root only
 *   if "Always scan entire phone for music" is explicitly enabled; OFF by default).
 * - Computes exact directTrackCount and recursiveTrackCount (sum of audio files across the
 *   entire subfolder branch) for every directory node so parent Artist/Album folders sort
 *   accurately under "Most Music Files".
 * - Detects multi-disc folders (CD1/CD2, Disc 1/Disc 2) and populates siblingDiscFolders.
 */
object MusicalRootScanner {

    data class ScanResult(
        val rootPath: String,
        val tracks: List<TrackEntity>,
        val folders: List<FolderEntity>
    )

    private val json = Json { encodeDefaults = true }

    fun scan(
        musicalRootFolder: File,
        existingPendingWebPaths: Set<String> = emptySet()
    ): ScanResult {
        if (!musicalRootFolder.exists() || !musicalRootFolder.isDirectory) {
            return ScanResult(
                rootPath = musicalRootFolder.absolutePath,
                tracks = emptyList(),
                folders = emptyList()
            )
        }

        val rootAbsolutePath = normalizePath(musicalRootFolder.absolutePath)
        AudioMetadataParser.clearFolderArtworkMemoCache()
        val visitedCanonicalPaths = mutableSetOf<String>()
        val discoveredTracks = mutableListOf<TrackEntity>()
        val directTrackCounts = LinkedHashMap<String, Int>()
        val childrenMap = LinkedHashMap<String, MutableList<String>>()
        val parentMap = LinkedHashMap<String, String?>()
        val folderNameMap = LinkedHashMap<String, String>()

        fun traverse(dir: File, parentPath: String?) {
            val canonical = try {
                dir.canonicalPath
            } catch (_: Exception) {
                dir.absolutePath
            }
            if (!visitedCanonicalPaths.add(canonical)) {
                return // Prevent symlink loops
            }

            val dirPath = normalizePath(dir.absolutePath)
            parentMap[dirPath] = parentPath
            folderNameMap[dirPath] = dir.name.ifEmpty { dirPath }
            val subDirs = mutableListOf<File>()
            var directAudioCount = 0

            val children = try {
                dir.listFiles()
            } catch (_: SecurityException) {
                null
            } ?: emptyArray()

            val childAudioTracks = mutableListOf<TrackEntity>()
            for (entry in children) {
                if (entry.name.startsWith(".")) continue
                if (entry.isDirectory) {
                    subDirs.add(entry)
                } else if (AudioMetadataParser.isSupportedAudioFile(entry)) {
                    directAudioCount++
                    val parsed = AudioMetadataParser.parse(entry)
                    val trackFilePath = normalizePath(entry.absolutePath)
                    val relTrackPath = computeRelativePath(rootAbsolutePath, trackFilePath)
                    val normKey = "${parsed.artist.trim().lowercase(Locale.US)}|${parsed.album.trim().lowercase(Locale.US)}|${parsed.title.trim().lowercase(Locale.US)}"

                    childAudioTracks.add(
                        TrackEntity(
                            filePath = trackFilePath,
                            relativePath = relTrackPath,
                            fileName = entry.name,
                            fileExtension = entry.extension.lowercase(Locale.US),
                            title = parsed.title,
                            artist = parsed.artist,
                            album = parsed.album,
                            albumArtist = parsed.albumArtist,
                            discNumber = parsed.discNumber,
                            totalDiscs = parsed.totalDiscs,
                            trackNumber = parsed.trackNumber,
                            durationMs = parsed.durationMs,
                            format = parsed.audioQuality.format,
                            bitrateKbps = parsed.audioQuality.bitrateKbps,
                            sampleRateHz = parsed.audioQuality.sampleRateHz,
                            bitDepth = parsed.audioQuality.bitDepth,
                            isLossless = parsed.audioQuality.isLossless,
                            parentFolderPath = dirPath,
                            dateAdded = entry.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                            year = parsed.year,
                            genre = parsed.genre,
                            artworkUri = parsed.artworkUri,
                            normalizedKey = normKey,
                            webMatchPending = trackFilePath in existingPendingWebPaths
                        )
                    )
                }
            }

            directTrackCounts[dirPath] = directAudioCount
            discoveredTracks.addAll(childAudioTracks)

            val childPaths = mutableListOf<String>()
            for (subDir in subDirs) {
                val subPath = normalizePath(subDir.absolutePath)
                childPaths.add(subPath)
                traverse(subDir, dirPath)
            }
            childrenMap[dirPath] = childPaths
        }

        traverse(musicalRootFolder, parentPath = null)

        // Compute recursiveTrackCount bottom-up (post-order memoized) across the entire subfolder branch
        val recursiveCounts = HashMap<String, Int>()
        fun computeRecursiveCount(dirPath: String): Int {
            recursiveCounts[dirPath]?.let { return it }
            val direct = directTrackCounts[dirPath] ?: 0
            val children = childrenMap[dirPath] ?: emptyList()
            var total = direct
            for (childPath in children) {
                total += computeRecursiveCount(childPath)
            }
            recursiveCounts[dirPath] = total
            return total
        }

        for (dirPath in directTrackCounts.keys) {
            computeRecursiveCount(dirPath)
        }

        // Compute sibling disc folders per parent directory
        val siblingDiscMap = HashMap<String, List<String>>()
        for ((_, childPaths) in childrenMap) {
            if (childPaths.size >= 2) {
                val detectedSiblings = MultiDiscDetector.findSiblingDiscFolders(childPaths, folderNameMap)
                siblingDiscMap.putAll(detectedSiblings)
            }
        }

        // Update totalDiscs on tracks that reside in multi-disc sibling folders
        val updatedTracks = discoveredTracks.map { track ->
            val siblings = siblingDiscMap[track.parentFolderPath]
            if (!siblings.isNullOrEmpty()) {
                val folderName = folderNameMap[track.parentFolderPath] ?: ""
                val folderDiscNum = MultiDiscDetector.extractDiscNumberFromFolderName(folderName) ?: track.discNumber
                val totalDiscCount = siblings.size + 1
                track.copy(
                    discNumber = folderDiscNum,
                    totalDiscs = maxOf(track.totalDiscs, totalDiscCount)
                )
            } else {
                track
            }
        }

        val folderEntities = directTrackCounts.keys.map { dirPath ->
            val name = folderNameMap[dirPath] ?: dirPath
            val discNum = MultiDiscDetector.extractDiscNumberFromFolderName(name)
            val siblings = siblingDiscMap[dirPath] ?: emptyList()
            val isDisc = discNum != null && (siblings.isNotEmpty() || discNum >= 1)
            val childPaths = childrenMap[dirPath] ?: emptyList()

            FolderEntity(
                absolutePath = dirPath,
                folderName = name,
                parentPath = parentMap[dirPath],
                relativePathFromRoot = computeRelativePath(rootAbsolutePath, dirPath),
                directTrackCount = directTrackCounts[dirPath] ?: 0,
                recursiveTrackCount = recursiveCounts[dirPath] ?: 0,
                hasSubfolders = childPaths.isNotEmpty(),
                isDiscFolder = isDisc,
                discNumber = discNum,
                siblingDiscFoldersJson = json.encodeToString(siblings)
            )
        }

        return ScanResult(
            rootPath = rootAbsolutePath,
            tracks = updatedTracks,
            folders = folderEntities
        )
    }

    /**
     * Sorts hierarchical child folders according to the user's selected FolderSortOption:
     * - SYSTEM_DEFAULT: preserves natural scan order
     * - ALPHABETICAL: sorts by folderName ascending (case-insensitive)
     * - MOST_MUSIC_FILES: sorts by recursiveTrackCount DESC (sum of audio files across the entire subfolder branch)
     */
    fun sortFolders(folders: List<FolderNode>, sortOption: FolderSortOption): List<FolderNode> {
        return when (sortOption) {
            FolderSortOption.SYSTEM_DEFAULT -> folders
            FolderSortOption.ALPHABETICAL -> folders.sortedBy { it.folderName.lowercase(Locale.US) }
            FolderSortOption.MOST_MUSIC_FILES -> folders.sortedWith(
                compareByDescending<FolderNode> { it.recursiveTrackCount }
                    .thenBy { it.folderName.lowercase(Locale.US) }
            )
        }
    }

    fun normalizePath(path: String): String {
        return path.replace('\\', '/').trimEnd('/').ifEmpty { "/" }
    }

    fun computeRelativePath(rootPath: String, targetPath: String): String {
        val normRoot = normalizePath(rootPath)
        val normTarget = normalizePath(targetPath)
        if (normTarget.equals(normRoot, ignoreCase = true)) {
            return ""
        }
        val prefix = "$normRoot/"
        return if (normTarget.startsWith(prefix, ignoreCase = true)) {
            normTarget.substring(prefix.length)
        } else {
            normTarget
        }
    }

    /**
     * Compares two paths hierarchically from the topmost folder segment down to the leaf file/folder name
     * (case-insensitively, with deterministic tie-breaker).
     */
    val HierarchicalPathComparator: Comparator<String> = Comparator { pathA, pathB ->
        val segsA = normalizePath(pathA).trimStart('/').split('/').filter { it.isNotEmpty() }
        val segsB = normalizePath(pathB).trimStart('/').split('/').filter { it.isNotEmpty() }
        val minSize = minOf(segsA.size, segsB.size)
        for (i in 0 until minSize) {
            val cmp = segsA[i].lowercase(Locale.US).compareTo(segsB[i].lowercase(Locale.US))
            if (cmp != 0) return@Comparator cmp
        }
        segsA.size.compareTo(segsB.size)
    }
}
