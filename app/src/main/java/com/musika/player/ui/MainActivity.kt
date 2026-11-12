package com.musika.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.musika.player.MusikaApplication
import com.musika.player.data.repository.LibraryRepository
import com.musika.player.data.repository.SettingsPreferences
import com.musika.player.player.MusikaPlaybackService
import com.musika.player.ui.theme.MusikaTheme

/**
 * Main Entry Activity for Musika.
 * - Requests MANAGE_EXTERNAL_STORAGE (with fallback to READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE)
 *   and POST_NOTIFICATIONS (Android 13+) for the Foreground MediaSession notification.
 * - Provides Android's native system folder selector (ACTION_OPEN_DOCUMENT_TREE).
 * - Hosts the Samsung Music + Winamp Hybrid UI with full edge-to-edge safe window insets.
 */
class MainActivity : ComponentActivity() {

    private val settingsPreferences by lazy { SettingsPreferences(applicationContext) }

    private val libraryRepository by lazy {
        val db = (application as MusikaApplication).database
        LibraryRepository(
            trackDao = db.trackDao(),
            folderDao = db.folderDao(),
            webMetadataDao = db.webMetadataDao(),
            playlistDao = db.playlistDao()
        )
    }

    val viewModel: MusikaViewModel by viewModels {
        MusikaViewModel.Factory(libraryRepository, settingsPreferences)
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.triggerLibraryScan()
    }

    private val nativeFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            val resolvedPath = SettingsPreferences.resolveDocumentTreeUriToPath(treeUri)
            if (!resolvedPath.isNullOrBlank()) {
                viewModel.setMusicalRootFolder(resolvedPath)
                viewModel.setShowSettingsDialog(false)
                Toast.makeText(
                    this,
                    "Musical Root set to: $resolvedPath",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Could not resolve folder path from selection",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Attach DualQueueEngine to the real ExoPlayer / MediaSessionService bridge
        MusikaPlaybackService.attachEngineToExoPlayer(applicationContext, viewModel.dualQueueEngine)

        // Request MANAGE_EXTERNAL_STORAGE (with fallback to READ_MEDIA_AUDIO) + POST_NOTIFICATIONS once at setup
        if (!settingsPreferences.hasRequestedInitialPermissions.value) {
            settingsPreferences.markInitialPermissionsRequested()
            requestStorageAndNotificationPermissions()
        } else {
            requestNotificationPermissionIfNeeded()
            viewModel.triggerLibraryScan()
        }

        setContent {
            MusikaTheme {
                MusikaMainScreen(
                    viewModel = viewModel,
                    onRequestStoragePermissions = ::requestStorageAndNotificationPermissions,
                    onLaunchNativeFolderPicker = ::launchNativeFolderPicker,
                    onExitApp = { finish() }
                )
            }
        }
    }

    private fun launchNativeFolderPicker() {
        try {
            nativeFolderPickerLauncher.launch(null)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "System folder picker is not available on this device; use manual path input.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                audioPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    }

    private fun requestStorageAndNotificationPermissions() {
        requestFallbackAndNotificationPermissions()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (_: Exception) {
                }
            } else {
                viewModel.triggerLibraryScan()
            }
        }
    }

    private fun requestFallbackAndNotificationPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            audioPermissionLauncher.launch(missing.toTypedArray())
        } else {
            viewModel.triggerLibraryScan()
        }
    }
}
