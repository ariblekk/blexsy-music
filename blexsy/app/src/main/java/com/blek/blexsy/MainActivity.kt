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
        val savedToken = prefs.getString("auth_token", null)
        val savedName = prefs.getString("user_name", null)
        val savedFavoriteArtists = prefs.getStringSet("favorite_artists", emptySet()) ?: emptySet()

        val api = BlexsyApiClient(
            pbUrl = "https://pb.blek.my.id",
            cache = ApiCache(this),
            artistDb = com.blek.blexsy.data.ArtistDatabase(this)
        ).apply {
            token = savedToken
            onUnauthorized = {
                prefs.edit().remove("auth_token").apply()
                token = "guest"
            }
        }

        // Local-first library: likes, playlists, and recent spins are saved on device first
        // and synced to the server in the background.
        val libraryRepository = LibraryRepository(api, PrefsLibraryStorage(this)).also {
            library = it
        }

        val player = BlexsyMediaService.sharedPlayer ?: AndroidBlexsyAudioPlayer(this, api, libraryRepository).also {
            BlexsyMediaService.sharedPlayer = it
            BlexsyMediaService.start(this)
        }
        player.library = libraryRepository
        audioPlayer = player

        setContent {
            App(
                audioPlayer = player,
                initialToken = savedToken,
                initialUserName = savedName,
                initialFavoriteArtists = savedFavoriteArtists,
                library = libraryRepository,
                apiClient = api,
                onSaveToken = { token ->
                    prefs.edit().apply {
                        if (token == null) remove("auth_token") else putString("auth_token", token)
                        apply()
                    }
                },
                onSaveUserName = { name ->
                    prefs.edit().apply {
                        if (name == null) remove("user_name") else putString("user_name", name)
                        apply()
                    }
                },
                onSaveFavoriteArtists = { artists ->
                    prefs.edit().putStringSet("favorite_artists", artists).apply()
                }
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
