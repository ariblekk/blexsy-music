package com.blek.blexsy

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.LibraryRepository
import com.blek.blexsy.player.BlexsyAudioPlayer
import com.blek.blexsy.player.DefaultBlexsyAudioPlayer
import com.blek.blexsy.ui.BlexsyTheme
import com.blek.blexsy.ui.HomeScreen
import com.blek.blexsy.ui.OnboardingScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
@Preview
fun App(
    audioPlayer: BlexsyAudioPlayer? = null,
    initialToken: String? = null,
    initialUserName: String? = null,
    initialFavoriteArtists: Set<String> = emptySet(),
    library: LibraryRepository? = null,
    apiClient: BlexsyApiClient? = null,
    onSaveToken: ((String?) -> Unit)? = null,
    onSaveUserName: ((String?) -> Unit)? = null,
    onSaveFavoriteArtists: ((Set<String>) -> Unit)? = null
) {
    val api = remember(apiClient) {
        apiClient ?: BlexsyApiClient().apply {
            token = initialToken
        }
    }
    val libraryRepo = remember(library) { library ?: LibraryRepository(api) }
    val player = remember(audioPlayer) { audioPlayer ?: DefaultBlexsyAudioPlayer() }

    var currentUserName by remember { mutableStateOf(initialUserName) }
    var currentFavoriteArtists by remember { mutableStateOf(initialFavoriteArtists) }
    var hasCompletedOnboarding by remember {
        mutableStateOf(!initialUserName.isNullOrBlank() || (!initialToken.isNullOrBlank() && initialToken != "guest"))
    }
    val scope = rememberCoroutineScope()

    BlexsyTheme {
        if (!hasCompletedOnboarding) {
            OnboardingScreen(
                api = api,
                onLoginSuccess = { token ->
                    api.token = token
                    onSaveToken?.invoke(token)
                    libraryRepo.refresh()
                    scope.launch {
                        runCatching { api.me() }.onSuccess { me ->
                            val u = me["user"]?.jsonObject
                            val fetchedName = u?.get("name")?.jsonPrimitive?.content
                            if (!fetchedName.isNullOrBlank()) {
                                currentUserName = fetchedName
                                onSaveUserName?.invoke(fetchedName)
                            }
                        }
                    }
                    hasCompletedOnboarding = true
                },
                onComplete = { name, favoriteArtists ->
                    currentUserName = name
                    currentFavoriteArtists = favoriteArtists
                    onSaveUserName?.invoke(name)
                    onSaveFavoriteArtists?.invoke(favoriteArtists)
                    if (api.token == null) {
                        api.token = "guest"
                        onSaveToken?.invoke("guest")
                    }
                    hasCompletedOnboarding = true
                }
            )
        } else {
            val defaultUserName = stringResource(R.string.profile_default_user)
            HomeScreen(
                api = api,
                player = player,
                library = libraryRepo,
                initialUserName = currentUserName ?: defaultUserName,
                favoriteArtists = currentFavoriteArtists,
                onSaveToken = onSaveToken,
                onLogout = {
                    libraryRepo.signOut()
                    api.token = "guest"
                    onSaveToken?.invoke("guest")
                    currentUserName = defaultUserName
                    onSaveUserName?.invoke(null)
                }
            )
        }
    }
}
