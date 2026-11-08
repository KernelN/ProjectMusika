package com.musika.player.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import com.musika.player.core.model.AlbumSortOption
import com.musika.player.core.model.ArtistSortOption
import com.musika.player.core.model.FolderSortOption
import com.musika.player.core.model.LoopMode
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

    private val _artistSortOption = MutableStateFlow(ArtistSortOption.ALPHABETICAL)
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

    fun setFolderSortOption(option: FolderSortOption) {
        prefs.edit().putString(KEY_FOLDER_SORT, option.name).apply()
        _folderSortOption.value = option
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
        const val KEY_FOLDER_SORT = "folder_sort"
    }
}
