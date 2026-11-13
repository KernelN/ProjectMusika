package com.musika.player.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import com.musika.player.BuildConfig
import com.musika.player.core.model.AlbumSortOption
import com.musika.player.core.model.ArtistSortOption
import com.musika.player.core.model.FolderSortOption
import com.musika.player.core.model.LoopMode
import com.musika.player.core.model.NowPlayingArtworkStyle
import com.musika.player.core.model.RandomMode
import com.musika.player.core.model.SongSortOption
import java.io.File
import java.security.SecureRandom
import java.util.UUID
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

    // Stage 2: Stable device ID, Supabase overridable defaults, & LAN streaming flags
    val localDeviceId: String = prefs.getString(KEY_LOCAL_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
        ?: "android-${UUID.randomUUID().toString().take(8)}".also {
            prefs.edit().putString(KEY_LOCAL_DEVICE_ID, it).apply()
        }

    val defaultDeviceName: String = runCatching {
        "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
    }.getOrDefault("Android Phone")

    private val _supabaseUrl = MutableStateFlow(
        prefs.getString(KEY_SUPABASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_SUPABASE_URL
    )
    val supabaseUrl: StateFlow<String> = _supabaseUrl.asStateFlow()

    private val _supabaseAnonKey = MutableStateFlow(
        prefs.getString(KEY_SUPABASE_ANON_KEY, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_SUPABASE_ANON_KEY
    )
    val supabaseAnonKey: StateFlow<String> = _supabaseAnonKey.asStateFlow()

    // OFF by default per Stage 2 Q5 specification: transfer strictly gates on local metadata match;
    // when OFF, missing queued items skip locally. Only streams over LAN if user explicitly enables.
    private val _allowLanStreamingForMissingTracks = MutableStateFlow(
        prefs.getBoolean(KEY_ALLOW_LAN_STREAMING, false)
    )
    val allowLanStreamingForMissingTracks: StateFlow<Boolean> =
        _allowLanStreamingForMissingTracks.asStateFlow()

    // OFF by default per Stage 2 Q6 specification: Synced Mode avoids echo with single audio output
    private val _listenLocallyWhenSyncedTargetIsRemote = MutableStateFlow(
        prefs.getBoolean(KEY_LISTEN_LOCALLY_IN_SYNCED, false)
    )
    val listenLocallyWhenSyncedTargetIsRemote: StateFlow<Boolean> =
        _listenLocallyWhenSyncedTargetIsRemote.asStateFlow()

    private val _cachedAuthUserId = MutableStateFlow(prefs.getString(KEY_AUTH_USER_ID, "") ?: "")
    val cachedAuthUserId: StateFlow<String> = _cachedAuthUserId.asStateFlow()

    private val _cachedAuthUsername = MutableStateFlow(prefs.getString(KEY_AUTH_USERNAME, "") ?: "")
    val cachedAuthUsername: StateFlow<String> = _cachedAuthUsername.asStateFlow()

    private val _cachedAccessToken = MutableStateFlow(prefs.getString(KEY_AUTH_ACCESS_TOKEN, "") ?: "")
    val cachedAccessToken: StateFlow<String> = _cachedAccessToken.asStateFlow()

    private val _cachedRefreshToken = MutableStateFlow(prefs.getString(KEY_AUTH_REFRESH_TOKEN, "") ?: "")
    val cachedRefreshToken: StateFlow<String> = _cachedRefreshToken.asStateFlow()

    private val _cachedPairSecretHmac = MutableStateFlow(
        prefs.getString(KEY_PAIR_SECRET_HMAC, null)?.takeIf { it.isNotBlank() }
            ?: generateRandomHexSecret().also {
                prefs.edit().putString(KEY_PAIR_SECRET_HMAC, it).apply()
            }
    )
    val cachedPairSecretHmac: StateFlow<String> = _cachedPairSecretHmac.asStateFlow()

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

    fun setSupabaseConfig(url: String, anonKey: String) {
        val cleanUrl = url.trim().trimEnd('/')
        val cleanKey = anonKey.trim()
        prefs.edit()
            .putString(KEY_SUPABASE_URL, cleanUrl)
            .putString(KEY_SUPABASE_ANON_KEY, cleanKey)
            .apply()
        _supabaseUrl.value = cleanUrl.ifBlank { BuildConfig.DEFAULT_SUPABASE_URL }
        _supabaseAnonKey.value = cleanKey.ifBlank { BuildConfig.DEFAULT_SUPABASE_ANON_KEY }
    }

    fun setAllowLanStreamingForMissingTracks(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_LAN_STREAMING, enabled).apply()
        _allowLanStreamingForMissingTracks.value = enabled
    }

    fun setListenLocallyWhenSyncedTargetIsRemote(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LISTEN_LOCALLY_IN_SYNCED, enabled).apply()
        _listenLocallyWhenSyncedTargetIsRemote.value = enabled
    }

    fun saveAuthSession(
        userId: String,
        username: String,
        accessToken: String,
        refreshToken: String,
        pairSecretHmac: String? = null
    ) {
        val editor = prefs.edit()
            .putString(KEY_AUTH_USER_ID, userId)
            .putString(KEY_AUTH_USERNAME, username)
            .putString(KEY_AUTH_ACCESS_TOKEN, accessToken)
            .putString(KEY_AUTH_REFRESH_TOKEN, refreshToken)
        if (!pairSecretHmac.isNullOrBlank()) {
            editor.putString(KEY_PAIR_SECRET_HMAC, pairSecretHmac)
            _cachedPairSecretHmac.value = pairSecretHmac
        }
        editor.apply()
        _cachedAuthUserId.value = userId
        _cachedAuthUsername.value = username
        _cachedAccessToken.value = accessToken
        _cachedRefreshToken.value = refreshToken
    }

    fun clearAuthSession() {
        prefs.edit()
            .remove(KEY_AUTH_USER_ID)
            .remove(KEY_AUTH_USERNAME)
            .remove(KEY_AUTH_ACCESS_TOKEN)
            .remove(KEY_AUTH_REFRESH_TOKEN)
            .apply()
        _cachedAuthUserId.value = ""
        _cachedAuthUsername.value = ""
        _cachedAccessToken.value = ""
        _cachedRefreshToken.value = ""
    }

    private fun generateRandomHexSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
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
        const val KEY_LOCAL_DEVICE_ID = "stage2_local_device_id"
        const val KEY_SUPABASE_URL = "stage2_supabase_url"
        const val KEY_SUPABASE_ANON_KEY = "stage2_supabase_anon_key"
        const val KEY_ALLOW_LAN_STREAMING = "stage2_allow_lan_streaming"
        const val KEY_LISTEN_LOCALLY_IN_SYNCED = "stage2_listen_locally_synced"
        const val KEY_AUTH_USER_ID = "stage2_auth_user_id"
        const val KEY_AUTH_USERNAME = "stage2_auth_username"
        const val KEY_AUTH_ACCESS_TOKEN = "stage2_auth_access_token"
        const val KEY_AUTH_REFRESH_TOKEN = "stage2_auth_refresh_token"
        const val KEY_PAIR_SECRET_HMAC = "stage2_pair_secret_hmac"

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
