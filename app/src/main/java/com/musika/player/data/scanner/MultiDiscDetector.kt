package com.musika.player.data.scanner

import com.musika.player.core.model.RandomMode
import com.musika.player.core.model.Track
import kotlin.random.Random

/**
 * Multi-Disc Recognition & Playback Helper.
 * Detects albums split across 2+ disc folders (CD1/CD2, Disc 1/Disc 2, Disk 1/Disk 2)
 * or DISCNUMBER tags, links sibling disc folders, and orders tracks for Play All Discs.
 */
object MultiDiscDetector {

    private val DISC_FOLDER_REGEX = Regex(
        pattern = "^(?:cd|disc|disk|disco)\\s*[-_.]?\\s*0*([1-9][0-9]*)(?:\\b|[-_\\s].*)?$",
        option = RegexOption.IGNORE_CASE
    )

    /**
     * Returns the 1-based disc number if the folder name matches a disc naming pattern
     * (e.g., "CD1", "CD 2", "Disc 1", "Disc 02 - Live", "Disk 3"), or null otherwise.
     */
    fun extractDiscNumberFromFolderName(folderName: String): Int? {
        val trimmed = folderName.trim()
        val match = DISC_FOLDER_REGEX.matchEntire(trimmed) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    fun isDiscFolder(folderName: String): Boolean {
        return extractDiscNumberFromFolderName(folderName) != null
    }

    /**
     * Given a map of parentPath -> list of child folder paths, identifies sibling disc folders
     * that belong to the same multi-disc release.
     * Only treats folders as sibling disc folders when there are 2+ disc folders under the same parent
     * or when a disc folder has a disc number >= 1.
     */
    fun findSiblingDiscFolders(childFolderPaths: List<String>, childFolderNames: Map<String, String>): Map<String, List<String>> {
        val discFolders = childFolderPaths.mapNotNull { path ->
            val name = childFolderNames[path] ?: path.substringAfterLast('/').substringAfterLast('\\')
            val discNum = extractDiscNumberFromFolderName(name)
            if (discNum != null) Pair(path, discNum) else null
        }.sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })

        if (discFolders.size < 2) {
            return emptyMap()
        }

        val orderedPaths = discFolders.map { it.first }
        return orderedPaths.associateWith { currentPath ->
            orderedPaths.filter { it != currentPath }
        }
    }

    /**
     * Determines if a collection of tracks for an album represents a multi-disc album
     * via either DISCNUMBER tags (2+ distinct disc numbers or totalDiscs > 1) or 2+ disc folders.
     */
    fun isMultiDiscAlbum(tracks: List<Track>): Boolean {
        if (tracks.isEmpty()) return false
        val distinctDiscNumbers = tracks.map { it.discNumber }.toSet()
        if (distinctDiscNumbers.size > 1) return true
        if (tracks.any { it.totalDiscs > 1 || it.discNumber > 1 }) return true
        val distinctDiscParents = tracks.mapNotNull { track ->
            val parentName = track.parentFolderPath.substringAfterLast('/').substringAfterLast('\\')
            if (isDiscFolder(parentName)) track.parentFolderPath else null
        }.toSet()
        return distinctDiscParents.size > 1
    }

    /**
     * Orders tracks for "Play All Discs" according to the Locked Specification:
     * - If Random is OFF: play sequentially from Disc 1 (Track 1..N) -> Disc 2 (Track 1..N).
     * - If Random is active (SHUFFLE_POOL or TRUE_RANDOM): randomize across all discs combined.
     */
    fun orderTracksForPlayAllDiscs(
        tracks: List<Track>,
        randomMode: RandomMode,
        random: Random = Random.Default
    ): List<Track> {
        val sequential = tracks.sortedWith(
            compareBy<Track> { it.discNumber }
                .thenBy { it.trackNumber }
                .thenBy { it.title.lowercase() }
        )
        return when (randomMode) {
            RandomMode.OFF -> sequential
            RandomMode.SHUFFLE_POOL, RandomMode.TRUE_RANDOM -> sequential.shuffled(random)
        }
    }
}
