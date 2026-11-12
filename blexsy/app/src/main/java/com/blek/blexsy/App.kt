package com.blek.blexsy

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.LibraryRepository
import com.blek.blexsy.player.BlexsyAudioPlayer
import com.blek.blexsy.player.DefaultBlexsyAudioPlayer
import com.blek.blexsy.ui.BlexsyTheme
import com.blek.blexsy.ui.HomeScreen
import androidx.compose.runtime.remember

@Composable
@Preview
fun App(
    audioPlayer: BlexsyAudioPlayer? = null,
    initialUserName: String? = null,
    library: LibraryRepository? = null,
    apiClient: BlexsyApiClient? = null
) {
    val api = remember(apiClient) { apiClient ?: BlexsyApiClient() }
    val libraryRepo = remember(library) { library ?: LibraryRepository() }
    val player = remember(audioPlayer) { audioPlayer ?: DefaultBlexsyAudioPlayer() }

    BlexsyTheme {
        val defaultUserName = stringResource(R.string.profile_default_user)
        HomeScreen(
            api = api,
            player = player,
            library = libraryRepo,
            initialUserName = initialUserName?.takeIf { it.isNotBlank() } ?: defaultUserName
        )
    }
}
