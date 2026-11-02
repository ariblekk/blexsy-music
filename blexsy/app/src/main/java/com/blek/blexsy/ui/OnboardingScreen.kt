package com.blek.blexsy.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.blek.blexsy.R
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.model.ArtistSummary
import com.blek.blexsy.model.toHighResArtwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class OnboardingStep {
    Welcome,
    ProfileName,
    ChooseArtists,
    Login
}

// =========================================================================================
// ONBOARDING ARTIST SHOWCASE & PICKS
// To add more artists/images to the picker grid:
// Add an ArtistSummary(id = "...", name = "...", image = "https://...") to this list.
// =========================================================================================
val DEFAULT_TOP_ARTISTS = listOf(
    ArtistSummary(id = "bernadya", name = "Bernadya", image = "https://lh3.googleusercontent.com/hxROE1fvLWSxUYAFV3IgMp5vvjN91Jx6oS6uwSVyYN9_fb3I6PLaRa3Ufb4A0awxq4J5UoPFQAQM-Q=w500-h500-p-l90-rj"),
    ArtistSummary(id = "hindia", name = "Hindia", image = "https://yt3.googleusercontent.com/8ImMAMQSD4FA6-gdqCZWSFaB-drHvkdfiFcFAk7Mcyy56ctfWD-Xxno-CHfGC4L6Ql8aR61XT0vX0F4b=w500-h500-p-l90-rj"),
    ArtistSummary(id = "tulus", name = "Tulus", image = "https://yt3.googleusercontent.com/h8P1jEIZLM8lkMxNA6Nbq98b43wcqllJSNmcZTCRPAB-F6rG_0Nqw5w7fwou0PN1QGwSW5viwWD5NV0=w500-h500-p-l90-rj"),
    ArtistSummary(id = "sheila", name = "Sheila On 7", image = "https://lh3.googleusercontent.com/ZBI5XEhdUvkE59Kr7HKZYIjoopuA_kC0uPAWNsEw-EmLfTtRaXuDEv-qYWTv_lahkpA8Yuhe7P-sO2Y=w500-h500-p-l90-rj"),
    ArtistSummary(id = "niki", name = "NIKI", image = "https://yt3.googleusercontent.com/gKAVVgmPq8rRVjGXzkiaSxnHjfBdOBbl3kf1Nz2cel7vobVD5yzSVPE-Xr4c3brFq2nZ9xbbloRbPg=w500-h500-p-l90-rj"),
    ArtistSummary(id = "juicyluicy", name = "Juicy Luicy", image = "https://yt3.googleusercontent.com/DDebW5VciXI_oMRQC1cRIWlpDIWVaS8c_CbcCkf89YeHVziP9lkgA0xUZmmVRxGKmC3qmppuMtvIsa5U=w500-h500-p-l90-rj"),
    ArtistSummary(id = "mahalini", name = "Mahalini", image = "https://yt3.googleusercontent.com/VdgLqr3Sno_U1IXj9qzk43azloCsjBeDy6MpFjfD8kMmco0AeL81qow0cpHynDfpaVlujCY11O7QO4d6=w500-h500-p-l90-rj"),
    ArtistSummary(id = "salpriadi", name = "Sal Priadi", image = "https://lh3.googleusercontent.com/wmItRT4hTCJrmnlsh_JBgOeBXww9mquXhrNR0oW3_hPW9LsZ5Z2grMij01EaENdt6ensOJfKm-OCBKqJ=w500-h500-p-l90-rj"),
    ArtistSummary(id = "feast", name = ".Feast", image = "https://lh3.googleusercontent.com/v78O_zDgEMvUp35uR0s0fU9pyvRJkELYJKKfYS33Kg1NpQrE4W-dzuS3CHuwWTdYsIs5RzwNpkiekcE=w500-h500-p-l90-rj"),
    ArtistSummary(id = "pamungkas", name = "Pamungkas", image = "https://yt3.googleusercontent.com/taxtgIghV9GbhxPOJ2_Utqh8T72EEBcGynoNX2dw9z-sSurU2GIW7vEM06ZYHJLOtcIOXvQZTpkfoJc=w500-h500-p-l90-rj"),
    ArtistSummary(id = "taylorswift", name = "Taylor Swift", image = "https://yt3.googleusercontent.com/RCpTA6EXJQyjVFDosWOKa2SMmqkua_lA9mHPDWWciLwgqpZLz-k8rXWRF_367trrQ7up9BUwCbk6kRk=w500-h500-p-l90-rj"),
    ArtistSummary(id = "theweeknd", name = "The Weeknd", image = "https://lh3.googleusercontent.com/U-SAmNOu4TynE818gLCfKsuHZ0U5YNEtO9mrjSI9WCCKERs98LzrCal5kajBBTQNwdcisoB2Bn-pHp4=w500-h500-p-l90-rj"),
    ArtistSummary(id = "brunomars", name = "Bruno Mars", image = "https://lh3.googleusercontent.com/hnefGBrazRhn4Z92bdSZBUENl40ONjRiVDsmZKZh-WZ2iCKE-2c7KKR7SNcZfzLHoRyB3E6as8L87YA=w500-h500-p-l90-rj"),
    ArtistSummary(id = "billieeilish", name = "Billie Eilish", image = "https://lh3.googleusercontent.com/tQC4rOL6xz6FhmFr0ggQExxyGbYSOsyveXVSnPBh2WjEyIzQ9pMHablLJ-0GlMBrLBlBrbWQGmzrV6KN=w500-h500-p-l90-rj"),
    ArtistSummary(id = "coldplay", name = "Coldplay", image = "https://lh3.googleusercontent.com/IOKuXtp8PCQ_Fc-vaRKm3sKIXBxFV51gZheLTH5br-YGnWHFQf_Jywcuk7wbprYRoEbQyS_XZY6-nMJX=w500-h500-p-l90-rj"),
    ArtistSummary(id = "kendricklamar", name = "Kendrick Lamar", image = "https://yt3.googleusercontent.com/uB8Magh99SvDyT_mcDYeNYxlVZ_F9WN-cJtAFMHw_Q-_N_8y5-uZiay8-EZSKKloNoWxymBzVehSF4PN=w500-h500-p-l90-rj"),
    ArtistSummary(id = "arcticmonkeys", name = "Arctic Monkeys", image = "https://yt3.googleusercontent.com/kbPRnnOmWPXIb35ygxKvXt2a_745AVUkAUeFMqOUxbKx8T_I0f1JUfK3G43-_xUldK16-KrU2cj43i0=w500-h500-p-l90-rj"),
    ArtistSummary(id = "ariblekk", name = "Ari Haryanto", image = "https://yt3.googleusercontent.com/XwNE1fH-gV85Qez3WhW99DOUDVAlBnZbJXwtbPU7kajANjusPjf_n1LIWURCArY5BbIrCtQjuh6YpMs=w500-h500-p-l90-rj")
)

@Composable
fun OnboardingScreen(
    api: BlexsyApiClient? = null,
    onLoginSuccess: ((token: String?) -> Unit)? = null,
    onComplete: (name: String, favoriteArtists: Set<String>) -> Unit
) {
    var step by remember { mutableStateOf(OnboardingStep.Welcome) }
    var name by remember { mutableStateOf("") }
    var selectedArtists by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLeavingWelcome by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val joinedText = remember {
        val date = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH).format(Date())
        "joined at $date"
    }

    AnimatedContent(
        targetState = step,
        transitionSpec = {
            if (targetState.ordinal > initialState.ordinal) {
                slideInHorizontally(
                    animationSpec = spring(
                        dampingRatio = 0.82f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) { width -> (width * 0.45f).toInt() } + fadeIn(tween(320)) togetherWith
                    slideOutHorizontally(
                        animationSpec = spring(
                            dampingRatio = 0.82f,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) { width -> -(width * 0.45f).toInt() } + fadeOut(tween(220))
            } else {
                slideInHorizontally(
                    animationSpec = spring(
                        dampingRatio = 0.82f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) { width -> -(width * 0.45f).toInt() } + fadeIn(tween(320)) togetherWith
                    slideOutHorizontally(
                        animationSpec = spring(
                            dampingRatio = 0.82f,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) { width -> (width * 0.45f).toInt() } + fadeOut(tween(220))
            }
        },
        label = "OnboardingTransition"
    ) { currentStep ->
        when (currentStep) {
            OnboardingStep.Welcome -> {
                WelcomeView(
                    isHiding = isLeavingWelcome,
                    onNext = {
                        isLeavingWelcome = true
                        scope.launch {
                            delay(350)
                            step = OnboardingStep.ProfileName
                            isLeavingWelcome = false
                        }
                    }
                )
            }
            OnboardingStep.ProfileName -> {
                ProfileNameView(
                    name = name,
                    onNameChange = { name = it },
                    joinedText = joinedText,
                    onBack = {
                        step = OnboardingStep.Welcome
                    },
                    onLogin = {
                        step = OnboardingStep.Login
                    },
                    onNext = {
                        val trimmed = name.trim()
                        if (trimmed.isNotBlank()) {
                            step = OnboardingStep.ChooseArtists
                        }
                    }
                )
            }
            OnboardingStep.ChooseArtists -> {
                ChooseArtistsView(
                    api = api,
                    selectedArtists = selectedArtists,
                    onToggleArtist = { artistName ->
                        selectedArtists = if (selectedArtists.any { it.equals(artistName, ignoreCase = true) }) {
                            selectedArtists.filterNot { it.equals(artistName, ignoreCase = true) }.toSet()
                        } else {
                            selectedArtists + artistName
                        }
                    },
                    onBack = {
                        step = OnboardingStep.ProfileName
                    },
                    onDone = {
                        val trimmed = name.trim()
                        if (trimmed.isNotBlank()) {
                            onComplete(trimmed, selectedArtists)
                        }
                    }
                )
            }
            OnboardingStep.Login -> {
                AuthScreen(
                    api = api ?: remember { BlexsyApiClient() },
                    suggestedName = name.takeIf { it.isNotBlank() },
                    onBack = {
                        step = OnboardingStep.ProfileName
                    },
                    onAuthenticated = { token ->
                        if (onLoginSuccess != null) {
                            onLoginSuccess(token)
                        } else {
                            val trimmed = name.trim().ifEmpty { "User Blexsy" }
                            onComplete(trimmed, selectedArtists)
                        }
                    },
                    onContinueGuest = {
                        step = OnboardingStep.ProfileName
                    }
                )
            }
        }
    }
}

@Composable
private fun WelcomeView(
    isHiding: Boolean,
    onNext: () -> Unit
) {
    val heroAlpha by animateFloatAsState(
        targetValue = if (isHiding) 0f else 1f,
        animationSpec = tween(350),
        label = "hero_hide_alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BlexsyColors.Background)
            .clipToBounds()
    ) {
        // Single Hero Image extending down to the welcome text (artist profile style)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    alpha = heroAlpha
                    scaleX = if (isHiding) 0.94f else 1f
                    scaleY = if (isHiding) 0.94f else 1f
                }
        ) {
            Image(
                painter = painterResource(id = R.drawable.collapse),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )

            // Top scrim for status bar readability
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.50f),
                            1f to Color.Transparent
                        )
                    )
            )

            // Bottom gradient overlay blending image smoothly into background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Transparent,
                            0.38f to Color.Transparent,
                            0.65f to BlexsyColors.Background.copy(alpha = 0.55f),
                            0.86f to BlexsyColors.Background.copy(alpha = 0.92f),
                            1.00f to BlexsyColors.Background
                        )
                    )
            )
        }

        // Welcome text & Next button
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 20.dp)
        ) {
            Text(
                text = stringResource(R.string.onboarding_welcome_title),
                fontFamily = DeezerExtraBoldFont,
                fontSize = 48.sp,
                lineHeight = 54.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                letterSpacing = 0.2.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_welcome_desc),
                fontSize = 16.sp,
                color = Color(0xFFB3B3B3),
                lineHeight = 22.sp,
                letterSpacing = (-0.5).sp
            )
            Spacer(modifier = Modifier.height(28.dp))
            BlexsyButton(
                text = stringResource(R.string.onboarding_next),
                onClick = onNext,
                variant = ButtonVariant.Primary,
                size = ButtonSize.Lg,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ChooseArtistsView(
    api: BlexsyApiClient?,
    selectedArtists: Set<String>,
    onToggleArtist: (String) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var artistList by remember { mutableStateOf(DEFAULT_TOP_ARTISTS) }
    var searchResults by remember { mutableStateOf<List<ArtistSummary>>(emptyList()) }

    LaunchedEffect(Unit) {
        if (api != null) {
            val live = runCatching { api.topArtists(24) }.getOrNull()
            if (!live.isNullOrEmpty()) {
                artistList = (DEFAULT_TOP_ARTISTS + live).distinctBy { it.name.lowercase() }
            }
        }
    }

    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.length < 2) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(400)
        val res = if (api != null) {
            runCatching { api.searchArtists(q, limit = 9) }.getOrNull().orEmpty()
        } else emptyList()
        searchResults = res
    }

    val displayedArtists = remember(searchQuery, artistList, searchResults) {
        val q = searchQuery.trim()
        if (q.isBlank()) {
            artistList
        } else if (searchResults.isNotEmpty()) {
            (searchResults + artistList.filter { it.name.contains(q, ignoreCase = true) })
                .distinctBy { it.name.lowercase() }
        } else {
            artistList.filter { it.name.contains(q, ignoreCase = true) }
        }
    }

    val canProceed = selectedArtists.size >= 3

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Surface(
                shape = CircleShape,
                color = if (canProceed) BlexsyColors.Primary.copy(alpha = 0.2f) else Color(0xFF262626),
                border = if (canProceed) BorderStroke(1.dp, BlexsyColors.Primary) else null
            ) {
                Text(
                    text = stringResource(R.string.onboarding_artists_selected_count, selectedArtists.size),
                    color = if (canProceed) BlexsyColors.Primary else Color(0xFF9E9E9E),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Title & Search
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.onboarding_artists_title),
                color = Color.White,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = DeezerExtraBoldFont,
                letterSpacing = 0.2.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.onboarding_artists_desc),
                color = Color(0xFF9E9E9E),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            BlexsyInput(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = stringResource(R.string.onboarding_search_artist_placeholder),
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.search_title),
                        tint = Color(0xFF8C8C8C),
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingContent = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.a11y_clear),
                                tint = Color(0xFF8C8C8C),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else null
            )
        }

        // Grid & Sticky Bottom Action
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 86.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(displayedArtists, key = { it.name.lowercase() }) { artist ->
                    val isSelected = selectedArtists.any { it.equals(artist.name, ignoreCase = true) }
                    ArtistPickCard(
                        artist = artist,
                        isSelected = isSelected,
                        onClick = { onToggleArtist(artist.name) }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.4f to Color(0xFF121212).copy(alpha = 0.92f),
                            1f to Color(0xFF121212)
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                BlexsyButton(
                    text = if (canProceed) {
                        stringResource(R.string.onboarding_button_proceed, selectedArtists.size)
                    } else {
                        stringResource(R.string.onboarding_button_pick_min, selectedArtists.size)
                    },
                    enabled = canProceed,
                    variant = ButtonVariant.Primary,
                    size = ButtonSize.Lg,
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ArtistPickCard(
    artist: ArtistSummary,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "artist_card_scale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) BlexsyColors.Primary else Color.Transparent,
        animationSpec = tween(220),
        label = "artist_card_border"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(86.dp)
                .clip(CircleShape)
                .background(Color(0xFF242426))
                .border(2.5.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = artist.name.take(1).uppercase(),
                style = BlexsyTypography.Title.copy(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.35f)
                )
            )
            if (!artist.image.isNullOrBlank()) {
                AsyncImage(
                    model = artist.image,
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            this@Column.AnimatedVisibility(
                visible = isSelected,
                enter = scaleIn(spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(180)),
                exit = scaleOut(spring(dampingRatio = 0.8f)) + fadeOut(tween(150))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(BlexsyColors.Primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.a11y_selected),
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = artist.name,
            color = if (isSelected) BlexsyColors.Primary else Color.White,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ProfileNameView(
    name: String,
    onNameChange: (String) -> Unit,
    joinedText: String,
    onBack: (() -> Unit)? = null,
    onLogin: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141414))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = Color.White
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = onLogin
            ) {
                Text(
                    text = stringResource(R.string.onboarding_lock_in),
                    color = BlexsyColors.Primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = if (onBack != null) 12.dp else 28.dp, bottom = 16.dp)
        ) {
            // Badge icon
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD9D9D9)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = Color(0xFF141414),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.onboarding_profile_title),
                color = Color.White,
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = DeezerExtraBoldFont,
                letterSpacing = 0.2.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            BlexsyInput(
                value = name,
                onValueChange = { onNameChange(it.take(32)) },
                label = stringResource(R.string.onboarding_name_label),
                placeholder = stringResource(R.string.onboarding_name_placeholder),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Profile Preview (flat, unlayered without card border/background)
            ProfilePreview(
                name = name,
                joinedText = joinedText
            )
        }

        // Pinned Footer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.onboarding_privacy_note),
                color = Color(0xFF6A6A6A),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            BlexsyButton(
                text = stringResource(R.string.onboarding_next_artists),
                enabled = name.trim().isNotBlank(),
                variant = ButtonVariant.Primary,
                size = ButtonSize.Lg,
                onClick = onNext,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            BlexsyButton(
                text = stringResource(R.string.onboarding_already_account),
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Md,
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ProfilePreview(
    name: String,
    joinedText: String,
    modifier: Modifier = Modifier
) {
    val displayName = name.trim().ifEmpty { "{name}" }
    var debouncedSeed by remember { mutableStateOf(name.trim().ifEmpty { "Blexsy" }) }

    LaunchedEffect(name) {
        val clean = name.trim().ifEmpty { "Blexsy" }
        delay(200)
        debouncedSeed = clean
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color(0xFF2B2C32))
                .border(
                    1.5.dp,
                    BlexsyColors.Primary.copy(alpha = 0.65f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayName.take(1).uppercase(),
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            AsyncImage(
                model = dicebearAvatarUrl(debouncedSeed),
                contentDescription = stringResource(R.string.a11y_profile_avatar),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.onboarding_preview_label),
                color = Color(0xFF8E8E95),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = displayName,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = DeezerExtraBoldFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.onboarding_member_since, joinedText.uppercase()),
                color = Color(0xFFAAAAAF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}
