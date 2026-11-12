package com.musika.player.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.musika.player.core.model.AlbumSortOption
import com.musika.player.core.model.ArtistSortOption
import com.musika.player.core.model.FolderSortOption
import com.musika.player.core.model.LoopMode
import com.musika.player.core.model.NowPlayingArtworkStyle
import com.musika.player.core.model.RandomMode
import com.musika.player.core.model.SongSortOption
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent user settings & playback mode storage.
 * Guarantees:
 * - "Always scan entire phone for music" is OFF (false) by default.
 * - Scoped Musical Root Folder is persisted.
 * - 3-state RandomMode and 3-state LoopMode are persisted and NEVER reset across context switches.
 */
class SettingsPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val defaultMusicRoot: String = run {
        val publicMusic = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)?.absolutePath
        } catch (_: Exception) {
            null
        }
        publicMusic ?: File(context.filesDir, "Music").absolutePath
    }

    private val _musicalRootPath = MutableStateFlow(
        prefs.getString(KEY_MUSICAL_ROOT_PATH, defaultMusicRoot) ?: defaultMusicRoot
    )
    val musicalRootPath: StateFlow<String> = _musicalRootPath.asStateFlow()

    // OFF by default per Specification #2
    private val _alwaysScanEntirePhone = MutableStateFlow(
        prefs.getBoolean(KEY_ALWAYS_SCAN_ENTIRE_PHONE, false)
    )
    val alwaysScanEntirePhone: StateFlow<Boolean> = _alwaysScanEntirePhone.asStateFlow()

    private val _hasRequestedInitialPermissions = MutableStateFlow(
        prefs.getBoolean(KEY_HAS_REQUESTED_PERMISSIONS, false)
    )
    val hasRequestedInitialPermissions: StateFlow<Boolean> = _hasRequestedInitialPermissions.asStateFlow()

    private val _randomMode = MutableStateFlow(
        runCatching {
            RandomMode.valueOf(prefs.getString(KEY_RANDOM_MODE, RandomMode.OFF.name) ?: RandomMode.OFF.name)
        }.getOrDefault(RandomMode.OFF)
    )
    val randomMode: StateFlow<RandomMode> = _randomMode.asStateFlow()

    private val _loopMode = MutableStateFlow(
        runCatching {
            LoopMode.valueOf(prefs.getString(KEY_LOOP_MODE, LoopMode.NO_LOOP.name) ?: LoopMode.NO_LOOP.name)
        }.getOrDefault(LoopMode.NO_LOOP)
    )
    val loopMode: StateFlow<LoopMode> = _loopMode.asStateFlow()

    private val _songSortOption = MutableStateFlow(
        runCatching {
            SongSortOption.valueOf(
                prefs.getString(KEY_SONG_SORT, SongSortOption.ALPHABETICAL.name)
                    ?: SongSortOption.ALPHABETICAL.name
            )
        }.getOrDefault(SongSortOption.ALPHABETICAL)
    )
    val songSortOption: StateFlow<SongSortOption> = _songSortOption.asStateFlow()

    private val _albumSortOption = MutableStateFlow(
        runCatching {
            AlbumSortOption.valueOf(
                prefs.getString(KEY_ALBUM_SORT, AlbumSortOption.ALPHABETICAL.name)
                    ?: AlbumSortOption.ALPHABETICAL.name
            )
        }.getOrDefault(AlbumSortOption.ALPHABETICAL)
    )
    val albumSortOption: StateFlow<AlbumSortOption> = _albumSortOption.asStateFlow()

    private val _artistSortOption = MutableStateFlow(
        runCatching {
            ArtistSortOption.valueOf(
                prefs.getString(KEY_ARTIST_SORT, ArtistSortOption.ALPHABETICAL.name)
                    ?: ArtistSortOption.ALPHABETICAL.name
            )
        }.getOrDefault(ArtistSortOption.ALPHABETICAL)
    )
    val artistSortOption: StateFlow<ArtistSortOption> = _artistSortOption.asStateFlow()

    private val _folderSortOption = MutableStateFlow(
        runCatching {
            FolderSortOption.valueOf(
                prefs.getString(KEY_FOLDER_SORT, FolderSortOption.SYSTEM_DEFAULT.name)
                    ?: FolderSortOption.SYSTEM_DEFAULT.name
            )
        }.getOrDefault(FolderSortOption.SYSTEM_DEFAULT)
    )
    val folderSortOption: StateFlow<FolderSortOption> = _folderSortOption.asStateFlow()

    private val _artworkStyle = MutableStateFlow(
        runCatching {
            NowPlayingArtworkStyle.valueOf(
                prefs.getString(KEY_ARTWORK_STYLE, NowPlayingArtworkStyle.ROUNDED_SQUARE.name)
                    ?: NowPlayingArtworkStyle.ROUNDED_SQUARE.name
            )
        }.getOrDefault(NowPlayingArtworkStyle.ROUNDED_SQUARE)
    )
    val artworkStyle: StateFlow<NowPlayingArtworkStyle> = _artworkStyle.asStateFlow()

    private val _showListTrackThumbnails = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_LIST_THUMBNAILS, true)
    )
    val showListTrackThumbnails: StateFlow<Boolean> = _showListTrackThumbnails.asStateFlow()

    // HUD & Font Size Scale (1.0f by default; supports 0.85f Compact up to 1.40f Extra Large)
    private val _hudScale = MutableStateFlow(
        prefs.getFloat(KEY_HUD_SCALE, 1.0f).coerceIn(0.85f, 1.40f)
    )
    val hudScale: StateFlow<Float> = _hudScale.asStateFlow()

    fun setMusicalRootPath(path: String) {
        val normalized = path.replace('\\', '/').trimEnd('/').ifEmpty { "/" }
        prefs.edit().putString(KEY_MUSICAL_ROOT_PATH, normalized).apply()
        _musicalRootPath.value = normalized
    }

    fun setAlwaysScanEntirePhone(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALWAYS_SCAN_ENTIRE_PHONE, enabled).apply()
        _alwaysScanEntirePhone.value = enabled
    }

    fun markInitialPermissionsRequested() {
        prefs.edit().putBoolean(KEY_HAS_REQUESTED_PERMISSIONS, true).apply()
        _hasRequestedInitialPermissions.value = true
    }

    fun setRandomMode(mode: RandomMode) {
        prefs.edit().putString(KEY_RANDOM_MODE, mode.name).apply()
        _randomMode.value = mode
    }

    fun setLoopMode(mode: LoopMode) {
        prefs.edit().putString(KEY_LOOP_MODE, mode.name).apply()
        _loopMode.value = mode
    }

    fun setSongSortOption(option: SongSortOption) {
        prefs.edit().putString(KEY_SONG_SORT, option.name).apply()
        _songSortOption.value = option
    }

    fun setAlbumSortOption(option: AlbumSortOption) {
        prefs.edit().putString(KEY_ALBUM_SORT, option.name).apply()
        _albumSortOption.value = option
    }

    fun setArtistSortOption(option: ArtistSortOption) {
        prefs.edit().putString(KEY_ARTIST_SORT, option.name).apply()
        _artistSortOption.value = option
    }

    fun setFolderSortOption(option: FolderSortOption) {
        prefs.edit().putString(KEY_FOLDER_SORT, option.name).apply()
        _folderSortOption.value = option
    }

    fun setArtworkStyle(style: NowPlayingArtworkStyle) {
        prefs.edit().putString(KEY_ARTWORK_STYLE, style.name).apply()
        _artworkStyle.value = style
    }

    fun setShowListTrackThumbnails(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_LIST_THUMBNAILS, enabled).apply()
        _showListTrackThumbnails.value = enabled
    }

    fun setHudScale(scale: Float) {
        val clamped = scale.coerceIn(0.85f, 1.40f)
        prefs.edit().putFloat(KEY_HUD_SCALE, clamped).apply()
        _hudScale.value = clamped
    }

    fun resolveEffectiveScanRoot(): File {
        return if (_alwaysScanEntirePhone.value) {
            try {
                Environment.getExternalStorageDirectory() ?: File(_musicalRootPath.value)
            } catch (_: Exception) {
                File(_musicalRootPath.value)
            }
        } else {
            File(_musicalRootPath.value)
        }
    }

    companion object {
        const val PREFS_NAME = "musika_settings_prefs"
        const val KEY_MUSICAL_ROOT_PATH = "musical_root_path"
        const val KEY_ALWAYS_SCAN_ENTIRE_PHONE = "always_scan_entire_phone"
        const val KEY_HAS_REQUESTED_PERMISSIONS = "has_requested_permissions"
        const val KEY_RANDOM_MODE = "random_mode"
        const val KEY_LOOP_MODE = "loop_mode"
        const val KEY_SONG_SORT = "song_sort"
        const val KEY_ALBUM_SORT = "album_sort"
        const val KEY_ARTIST_SORT = "artist_sort"
        const val KEY_FOLDER_SORT = "folder_sort"
        const val KEY_ARTWORK_STYLE = "artwork_style"
        const val KEY_SHOW_LIST_THUMBNAILS = "show_list_thumbnails"
        const val KEY_HUD_SCALE = "hud_scale"

        /**
         * Resolves an Android native folder picker (ACTION_OPEN_DOCUMENT_TREE) Uri into an
         * absolute filesystem path on Internal Storage (/storage/emulated/0/...) or SD card (/storage/<UUID>/...).
         */
        fun resolveDocumentTreeUriToPath(treeUri: Uri): String? {
            return try {
                val docId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
                resolveDocumentIdToPath(docId)
            } catch (_: Exception) {
                val rawPath = treeUri.path ?: return null
                val afterTree = rawPath.substringAfter("/tree/", "")
                if (afterTree.isNotBlank()) {
                    resolveDocumentIdToPath(Uri.decode(afterTree))
                } else {
                    null
                }
            }
        }

        fun resolveDocumentIdToPath(docId: String): String {
            if (docId.startsWith("raw:")) {
                return docId.removePrefix("raw:")
            }
            val split = docId.split(":", limit = 2)
            val volumeId = split.getOrNull(0).orEmpty()
            val relPath = split.getOrNull(1).orEmpty().trim('/')
            val baseRoot = if (volumeId.equals("primary", ignoreCase = true) || volumeId.isBlank()) {
                try {
                    Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
                } catch (_: Exception) {
                    "/storage/emulated/0"
                }
            } else if (volumeId.equals("home", ignoreCase = true)) {
                val ext = try {
                    Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
                } catch (_: Exception) {
                    "/storage/emulated/0"
                }
                "$ext/Documents"
            } else {
                "/storage/$volumeId"
            }
            return if (relPath.isEmpty()) baseRoot else "$baseRoot/$relPath"
        }
    }
}
