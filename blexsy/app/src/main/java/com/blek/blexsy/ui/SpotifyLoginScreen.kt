package com.blek.blexsy.ui

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.blek.blexsy.R
import com.blek.blexsy.data.LibraryRepository
import com.blek.blexsy.data.SpotifyAuthManager
import com.blek.blexsy.data.SpotifySyncState
import kotlinx.coroutines.launch

@Composable
fun SpotifyLoginScreen(
    library: LibraryRepository,
    onBack: () -> Unit,
    onLoginSuccess: (displayName: String, emailOrHandle: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(context) {
        SpotifyAuthManager.init(context)
    }

    val session by SpotifyAuthManager.session.collectAsState()
    val syncState by SpotifyAuthManager.syncState.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Cookie sp_dc, 1: Web Login
    var cookieInput by remember { mutableStateOf("") }
    var isAuthenticating by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    fun handleCookieConnect(rawCookie: String, directToken: String? = null) {
        if (isAuthenticating) return
        val cleaned = rawCookie.trim()
        if (cleaned.isBlank() && directToken.isNullOrBlank()) {
            authError = "Masukkan cookie sp_dc terlebih dahulu."
            return
        }
        isAuthenticating = true
        authError = null
        scope.launch {
            val result = SpotifyAuthManager.loginWithCookieOrToken(
                rawInput = cleaned,
                directAccessToken = directToken,
                context = context
            )
            isAuthenticating = false
            result.onSuccess { sess ->
                onLoginSuccess(
                    sess.displayName,
                    sess.email ?: "Spotify • @${sess.userId}"
                )
                SpotifyAuthManager.syncLibrary(library, context)
            }.onFailure { err ->
                authError = err.message ?: "Gagal menghubungkan akun Spotify."
            }
        }
    }

    Scaffold(
        containerColor = BlexsyColors.Background,
        topBar = {
            DetailAppBar(
                title = "SPOTIFY ACCOUNT",
                onBack = onBack
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            if (session != null) {
                val activeSession = session!!
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(BlexsyColors.SurfaceContainerHigh)
                                .border(1.dp, BlexsyColors.Primary.copy(alpha = 0.30f), RoundedCornerShape(18.dp))
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(84.dp)
                                    .clip(CircleShape)
                                    .background(BlexsyColors.SurfaceContainerHighest),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = activeSession.displayName.take(1).uppercase(),
                                    style = BlexsyTypography.Display.copy(fontSize = 30.sp),
                                    color = BlexsyColors.Primary
                                )
                                if (!activeSession.avatarUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = activeSession.avatarUrl,
                                        contentDescription = activeSession.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = activeSession.displayName,
                                    style = BlexsyTypography.Hero.copy(fontSize = 22.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                BlexsyChip(
                                    text = (activeSession.product ?: "SPOTIFY").uppercase(),
                                    active = true
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = activeSession.email ?: "Spotify ID: @${activeSession.userId}",
                                style = BlexsyTypography.Caption.copy(fontSize = 13.sp),
                                color = BlexsyColors.TextSecondary
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            when (val st = syncState) {
                                is SpotifySyncState.Syncing -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    ) {
                                        ExpressiveLoadingIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = BlexsyColors.Primary
                                        )
                                        Text(
                                            text = st.status,
                                            style = BlexsyTypography.BodyMuted.copy(color = BlexsyColors.Primary)
                                        )
                                    }
                                }
                                is SpotifySyncState.Success -> {
                                    Text(
                                        text = "Tersinkronisasi: ${st.summary.likedTracksCount} Liked • ${st.summary.playlistsCount} Playlists • ${st.summary.madeForYouCount} Made For You • ${st.summary.savedAlbumsCount} Albums • ${st.summary.followedArtistsCount} Artists",
                                        style = BlexsyTypography.Caption.copy(
                                            color = BlexsyColors.Primary,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        textAlign = TextAlign.Center
                                    )
                                }
                                is SpotifySyncState.Error -> {
                                    Text(
                                        text = st.message,
                                        style = BlexsyTypography.Caption.copy(color = BlexsyColors.Danger),
                                        textAlign = TextAlign.Center
                                    )
                                }
                                SpotifySyncState.Idle -> {}
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                BlexsyButton(
                                    text = "Sync Library",
                                    leadingIcon = IcRefresh,
                                    isLoading = syncState is SpotifySyncState.Syncing,
                                    onClick = {
                                        scope.launch {
                                            SpotifyAuthManager.syncLibrary(library, context)
                                        }
                                    },
                                    variant = ButtonVariant.Primary,
                                    size = ButtonSize.Md,
                                    modifier = Modifier.weight(1f)
                                )
                                BlexsyButton(
                                    text = "Putuskan Akun",
                                    leadingIcon = IcSignOut,
                                    onClick = {
                                        SpotifyAuthManager.logout()
                                        cookieInput = ""
                                    },
                                    variant = ButtonVariant.Secondary,
                                    size = ButtonSize.Md,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BlexsyTabs(
                            tabs = listOf("Cookie sp_dc", "Web Login"),
                            selectedIndex = selectedTab,
                            onSelect = {
                                selectedTab = it
                                authError = null
                            },
                            fillMaxWidth = false
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (selectedTab == 0) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(bottom = 32.dp)
                        ) {
                            item {
                                Text(
                                    text = "Login dengan Cookie sp_dc",
                                    style = BlexsyTypography.Hero.copy(fontSize = 22.sp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Tempel nilai cookie sp_dc dari akun Spotify kamu untuk menyinkronkan profil, Liked Songs, dan seluruh Playlist pribadi.",
                                    style = BlexsyTypography.BodyMuted
                                )
                            }

                            item {
                                BlexsyInput(
                                    value = cookieInput,
                                    onValueChange = {
                                        cookieInput = it
                                        authError = null
                                    },
                                    label = "COOKIE SP_DC ATAU BEARER TOKEN",
                                    placeholder = "AQB... atau sp_dc=AQB...",
                                    shape = InputShape.Rounded,
                                    singleLine = true,
                                    isError = authError != null
                                )
                            }

                            if (authError != null) {
                                item {
                                    Text(
                                        text = authError!!,
                                        style = BlexsyTypography.Caption.copy(
                                            color = BlexsyColors.Danger,
                                            fontSize = 13.sp
                                        )
                                    )
                                }
                            }

                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    BlexsyButton(
                                        text = "Tempel Clipboard",
                                        onClick = {
                                            val cm = context.getSystemService(ClipboardManager::class.java)
                                            val clip = cm?.primaryClip
                                                ?.takeIf { it.itemCount > 0 }
                                                ?.getItemAt(0)
                                                ?.coerceToText(context)
                                                ?.toString()
                                                ?.trim()
                                                .orEmpty()
                                            if (clip.isNotBlank()) {
                                                cookieInput = clip
                                                authError = null
                                            }
                                        },
                                        variant = ButtonVariant.Secondary,
                                        size = ButtonSize.Md
                                    )

                                    BlexsyButton(
                                        text = "Hubungkan & Sync",
                                        onClick = { handleCookieConnect(cookieInput) },
                                        isLoading = isAuthenticating,
                                        variant = ButtonVariant.Primary,
                                        size = ButtonSize.Md,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(BlexsyColors.SurfaceContainerHigh)
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Cara mengambil cookie sp_dc:",
                                        style = BlexsyTypography.Title.copy(
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BlexsyColors.Primary
                                        )
                                    )
                                    Text(
                                        text = "1. Buka open.spotify.com di browser (Chrome / Edge / Kiwi Browser) dan login.\n" +
                                            "2. Buka Developer Tools (F12) ➔ tab Application (atau Storage).\n" +
                                            "3. Pilih Cookies ➔ https://open.spotify.com.\n" +
                                            "4. Cari cookie bernama sp_dc, salin kolom Value-nya (biasanya diawali huruf AQ...), lalu tempel di atas.\n" +
                                            "5. Atau pindah ke tab \"Web Login\" di atas untuk login langsung lewat browser di dalam aplikasi.",
                                        style = BlexsyTypography.BodyMuted.copy(
                                            fontSize = 13.sp,
                                            lineHeight = 20.sp
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        // Tab 1: Embedded WebView Login that captures sp_dc & Bearer token automatically
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = "Login ke akun Spotify kamu di bawah. Begitu berhasil masuk, Blexsy otomatis menangkap sesi & menyinkronkan library kamu.",
                                style = BlexsyTypography.Caption.copy(fontSize = 12.sp),
                                color = BlexsyColors.TextSecondary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            if (authError != null) {
                                Text(
                                    text = authError!!,
                                    style = BlexsyTypography.Caption.copy(
                                        color = BlexsyColors.Danger,
                                        fontSize = 12.sp
                                    ),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }

                            if (isAuthenticating) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        ExpressiveLoadingIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = BlexsyColors.Primary
                                        )
                                        Text(
                                            text = "Menghubungkan akun Spotify...",
                                            style = BlexsyTypography.BodyMuted.copy(color = BlexsyColors.Primary)
                                        )
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(bottom = 16.dp)
                                    .border(1.dp, BlexsyColors.Border, RoundedCornerShape(12.dp))
                            ) {
                                SpotifyLoginWebView(
                                    onSessionCaptured = { capturedSpDc, capturedBearer ->
                                        handleCookieConnect(
                                            rawCookie = capturedSpDc,
                                            directToken = capturedBearer
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val SPOTIFY_LOGIN_URL =
    "https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SpotifyLoginWebView(
    onSessionCaptured: (spDc: String, accessToken: String?) -> Unit
) {
    val capturedRef = remember { AtomicBoolean(false) }
    val latestSpDcRef = remember { AtomicReference<String?>(null) }
    val webViewRef = remember { AtomicReference<WebView?>(null) }
    val currentCallback by rememberUpdatedState(onSessionCaptured)

    var currentUrl by remember { mutableStateOf("accounts.spotify.com") }
    var loadProgress by remember { mutableIntStateOf(10) }
    var isPageLoading by remember { mutableStateOf(true) }
    var webError by remember { mutableStateOf<String?>(null) }

    fun extractSpDcFromCookies(): String? = runCatching {
        val cm = CookieManager.getInstance()
        val cookies = listOfNotNull(
            cm.getCookie("https://open.spotify.com"),
            cm.getCookie("https://accounts.spotify.com"),
            cm.getCookie("https://spotify.com"),
            cm.getCookie("https://www.spotify.com")
        ).joinToString("; ")
        val match = Regex("""(?:^|;\s*)sp_dc=([^;\s]+)""").find(cookies)
        match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length > 20 }
    }.getOrNull()

    fun tryDeliverSpDcIfPresent(view: WebView?, directToken: String? = null): Boolean {
        val spDc = extractSpDcFromCookies() ?: latestSpDcRef.get()
        if (!spDc.isNullOrBlank()) {
            latestSpDcRef.set(spDc)
            if (capturedRef.compareAndSet(false, true)) {
                if (view != null) {
                    view.post { currentCallback(spDc, directToken) }
                } else {
                    currentCallback(spDc, directToken)
                }
                return true
            }
        }
        return false
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef.getAndSet(null)?.apply {
                stopLoading()
                destroy()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Mini Browser Toolbar (URL + Reload button)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BlexsyColors.SurfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = IcGlobe,
                    contentDescription = null,
                    tint = BlexsyColors.Primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = currentUrl,
                    style = BlexsyTypography.Caption.copy(
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                        color = BlexsyColors.TextSecondary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = {
                    webError = null
                    capturedRef.set(false)
                    val wv = webViewRef.get()
                    if (wv != null) {
                        if (wv.url.isNullOrBlank() || wv.url == "about:blank") {
                            wv.loadUrl(SPOTIFY_LOGIN_URL)
                        } else {
                            wv.reload()
                        }
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = IcRefresh,
                    contentDescription = "Muat ulang",
                    tint = BlexsyColors.TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (isPageLoading) {
            LinearProgressIndicator(
                progress = { (loadProgress / 100f).coerceIn(0.05f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = BlexsyColors.Primary,
                trackColor = BlexsyColors.SurfaceContainer
            )
        }

        if (webError != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BlexsyColors.SurfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = webError!!,
                    style = BlexsyTypography.Caption.copy(
                        color = BlexsyColors.Danger,
                        fontSize = 12.sp
                    )
                )
            }
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                WebView(ctx).apply {
                    webViewRef.set(this)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.parseColor("#121212"))
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus(View.FOCUS_DOWN)

                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        javaScriptCanOpenWindowsAutomatically = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        // Use clean mobile Chrome UA (strip '; wv' so Google/Spotify login never blocks WebView,
                        // and avoid desktop UA so open.spotify.com doesn't hang loading Widevine desktop DRM)
                        val defaultUa = runCatching { WebSettings.getDefaultUserAgent(ctx) }
                            .getOrDefault("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                        userAgentString = defaultUa
                            .replace("; wv", "")
                            .replace(Regex("""Version/\d+\.\d+\s*"""), "")
                    }

                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onToken(token: String) {
                                if (token.startsWith("BQ") && token.length > 60) {
                                    val spDc = extractSpDcFromCookies() ?: latestSpDcRef.get().orEmpty()
                                    if (capturedRef.compareAndSet(false, true)) {
                                        post {
                                            currentCallback(spDc, token)
                                        }
                                    }
                                }
                            }
                        },
                        "BlexsyLoginBridge"
                    )

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            loadProgress = newProgress
                            isPageLoading = newProgress < 100
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val uri = request?.url ?: return false
                            val scheme = uri.scheme?.lowercase().orEmpty()
                            // Check if sp_dc cookie was just set during login redirect
                            CookieManager.getInstance().flush()
                            val host = uri.host?.lowercase().orEmpty()
                            if (host.contains("open.spotify.com") || host.contains("spotify.com/en/status")) {
                                if (tryDeliverSpDcIfPresent(view)) {
                                    return false
                                }
                            }
                            if (scheme != "http" && scheme != "https") {
                                // Spotify tried to launch spotify:// or intent:// after login
                                if (!tryDeliverSpDcIfPresent(view)) {
                                    view?.loadUrl("https://open.spotify.com/")
                                }
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            isPageLoading = true
                            webError = null
                            if (!url.isNullOrBlank()) {
                                currentUrl = url.removePrefix("https://").removePrefix("http://")
                            }
                            val spDc = extractSpDcFromCookies()
                            if (!spDc.isNullOrBlank()) {
                                latestSpDcRef.set(spDc)
                                if (url?.contains("open.spotify.com") == true ||
                                    url?.contains("/status") == true
                                ) {
                                    tryDeliverSpDcIfPresent(view)
                                }
                            }
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView?,
                            url: String?,
                            isReload: Boolean
                        ) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            if (!url.isNullOrBlank()) {
                                currentUrl = url.removePrefix("https://").removePrefix("http://")
                            }
                            CookieManager.getInstance().flush()
                            val spDc = extractSpDcFromCookies()
                            if (!spDc.isNullOrBlank()) {
                                latestSpDcRef.set(spDc)
                                if (url?.contains("open.spotify.com") == true ||
                                    url?.contains("/status") == true
                                ) {
                                    tryDeliverSpDcIfPresent(view)
                                }
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            // Runs on background thread: NEVER mutate Compose state directly here!
                            val spDc = extractSpDcFromCookies()
                            if (!spDc.isNullOrBlank()) {
                                latestSpDcRef.set(spDc)
                            }
                            val auth = request?.requestHeaders?.entries
                                ?.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                                ?.value
                            if (!capturedRef.get() && auth != null && auth.startsWith("Bearer BQ", ignoreCase = true)) {
                                val token = auth.substringAfter(" ").trim()
                                val currentSpDc = latestSpDcRef.get()
                                if (token.length > 60 && !currentSpDc.isNullOrBlank()) {
                                    if (capturedRef.compareAndSet(false, true)) {
                                        view?.post {
                                            currentCallback(currentSpDc, token)
                                        }
                                    }
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isPageLoading = false
                            CookieManager.getInstance().flush()
                            val spDc = extractSpDcFromCookies()
                            if (!spDc.isNullOrBlank()) {
                                latestSpDcRef.set(spDc)
                                // As soon as sp_dc is set (either on open.spotify.com or accounts.spotify.com post-login), capture it!
                                if (url?.contains("open.spotify.com") == true ||
                                    url?.contains("/status") == true ||
                                    url?.contains("/login") == false
                                ) {
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            try {
                                                var s = document.getElementById('session');
                                                if (s) {
                                                    var d = JSON.parse(s.textContent);
                                                    if (d && d.accessToken && !d.isAnonymous) {
                                                        BlexsyLoginBridge.onToken(d.accessToken);
                                                        return;
                                                    }
                                                }
                                            } catch (e) {}
                                        })();
                                        """.trimIndent(),
                                        null
                                    )
                                    view?.postDelayed({
                                        tryDeliverSpDcIfPresent(view)
                                    }, 800L)
                                }
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                isPageLoading = false
                                val desc = error?.description?.toString().orEmpty()
                                // Ignore if we already captured sp_dc on redirect
                                if (!tryDeliverSpDcIfPresent(view) && desc.isNotBlank()) {
                                    webError = "Gagal memuat halaman ($desc). Tekan ikon refresh di kanan atas."
                                }
                            }
                        }
                    }

                    loadUrl(SPOTIFY_LOGIN_URL)
                }
            }
        )
    }
}
