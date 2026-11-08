package com.musika.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.musika.player.MusikaApplication
import com.musika.player.data.repository.LibraryRepository
import com.musika.player.data.repository.SettingsPreferences
import com.musika.player.ui.theme.MusikaTheme

/**
 * Main Entry Activity for Musika.
 * Requests MANAGE_EXTERNAL_STORAGE (with fallback to READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE)
 * once at initial setup (Specification #2) and hosts the Samsung Music + Winamp Hybrid UI.
 */
class MainActivity : ComponentActivity() {

    private val settingsPreferences by lazy { SettingsPreferences(applicationContext) }

    private val libraryRepository by lazy {
        val db = (application as MusikaApplication).database
        LibraryRepository(
            trackDao = db.trackDao(),
            folderDao = db.folderDao(),
            webMetadataDao = db.webMetadataDao()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request MANAGE_EXTERNAL_STORAGE (with fallback to READ_MEDIA_AUDIO) once at setup
        if (!settingsPreferences.hasRequestedInitialPermissions.value) {
            settingsPreferences.markInitialPermissionsRequested()
            requestStoragePermissionsWithFallback()
        } else {
            viewModel.triggerLibraryScan()
        }

        setContent {
            MusikaTheme {
                MusikaMainScreen(
                    viewModel = viewModel,
                    onRequestStoragePermissions = ::requestStoragePermissionsWithFallback
                )
            }
        }
    }

    private fun requestStoragePermissionsWithFallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (_: Exception) {
                    // Fallback to READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE
                    requestFallbackAudioPermissions()
                }
            } else {
                viewModel.triggerLibraryScan()
            }
        } else {
            requestFallbackAudioPermissions()
        }
    }

    private fun requestFallbackAudioPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(arrayOf(permission))
        } else {
            viewModel.triggerLibraryScan()
        }
    }
}
