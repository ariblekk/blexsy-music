package com.blek.blexsy

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.blek.blexsy.data.ApiCache
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.LibraryRepository
import com.blek.blexsy.data.PrefsLibraryStorage

class MainActivity : ComponentActivity() {
    private var audioPlayer: AndroidBlexsyAudioPlayer? = null
    private var library: LibraryRepository? = null

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
        val savedLang = prefs.getString("app_language", null)
        val langCode = when (savedLang) {
            "INDONESIAN" -> "in"
            "ENGLISH" -> "en"
            else -> null
        }
        if (!langCode.isNullOrEmpty()) {
            val locale = java.util.Locale(langCode)
            java.util.Locale.setDefault(locale)
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            applyOverrideConfiguration(config)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so force light system-bar icons and transparent bars
        // instead of letting auto() follow the device light/dark setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val prefs = getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
        com.blek.blexsy.ui.BlexsyThemeConfig.init(this)
        val savedName = prefs.getString("user_name", null)

        val api = BlexsyApiClient(
            cache = ApiCache(this)
        )
        // A stable anonymous visitor identity personalises the YouTube home feed; audio streams
        // are the only other thing YouTube is used for.
        com.blek.blexsy.data.InnerTubeService.init(this)

        // Fully local library: likes, playlists and recent spins live on the device only.
        val libraryRepository = LibraryRepository(PrefsLibraryStorage(this)).also {
            library = it
        }

        val player = BlexsyMediaService.sharedPlayer ?: AndroidBlexsyAudioPlayer(this, libraryRepository).also {
            BlexsyMediaService.sharedPlayer = it
            BlexsyMediaService.start(this)
        }
        player.library = libraryRepository
        audioPlayer = player

        setContent {
            App(
                audioPlayer = player,
                initialUserName = savedName,
                library = libraryRepository,
                apiClient = api
            )
        }
    }

    override fun onDestroy() {
        val prefs = getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
        val keepPlayingClosed = prefs.getBoolean("pref_keep_playing_closed", true)
        if (!isChangingConfigurations && (!keepPlayingClosed || audioPlayer?.isPlaying?.value != true)) {
            BlexsyMediaService.sharedPlayer = null
            audioPlayer?.release()
            audioPlayer = null
        }
        library?.shutdown()
        library = null
        super.onDestroy()
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
