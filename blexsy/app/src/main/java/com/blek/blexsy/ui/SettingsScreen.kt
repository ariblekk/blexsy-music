package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.blek.blexsy.BuildConfig
import com.blek.blexsy.data.AudioCacheManager
import com.blek.blexsy.data.ContributorsService
import com.blek.blexsy.eq.BlexsyEqualizer
import com.blek.blexsy.player.BlexsyAudioPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    player: BlexsyAudioPlayer? = null,
    onBack: () -> Unit,
    onOpenEqualizer: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE) }
    val eqState by BlexsyEqualizer.state.collectAsState()

    var autoplayEnabled by remember { mutableStateOf(prefs.getBoolean("autoplay_enabled", true)) }
    var keepPlayingClosed by remember { mutableStateOf(prefs.getBoolean("pref_keep_playing_closed", true)) }
    var saveLastState by remember { mutableStateOf(prefs.getBoolean("pref_save_last_state", true)) }
    var skipSilence by remember { mutableStateOf(prefs.getBoolean("pref_skip_silence", false)) }
    var translateLyrics by remember { mutableStateOf(prefs.getBoolean("pref_translate_lyrics", false)) }
    var keepAwakeLyrics by remember { mutableStateOf(prefs.getBoolean("pref_keep_screen_awake", false)) }
    var audioQuality by remember { mutableStateOf(AudioCacheManager.getAudioQuality(context)) }
    var cacheLimitGb by remember { mutableIntStateOf(AudioCacheManager.getCacheLimitGb(context)) }
    var cacheSizeBytes by remember { mutableLongStateOf(0L) }

    var showQualityDialog by remember { mutableStateOf(false) }
    var showLimitDialog by remember { mutableStateOf(false) }
    var showAppNameDialog by remember { mutableStateOf(false) }
    var showThemeModeDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showAboutSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            cacheSizeBytes = AudioCacheManager.getCurrentCacheSizeBytes(context)
        }
    }

    Scaffold(
        containerColor = BlexsyColors.Background,
        topBar = {
            DetailAppBar(
                title = stringResource(R.string.settings_title),
                onBack = onBack,
                trailing = {
                    IconButton(
                        onClick = { showAboutSheet = true },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = stringResource(R.string.settings_about_desc),
                            tint = BlexsyColors.TextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Audio Quality
            item {
                SettingsGroup(title = stringResource(R.string.settings_group_sound)) {
                    val qualityLabel = when (audioQuality.lowercase()) {
                        "low" -> stringResource(R.string.settings_bitrate_data_saver)
                        "normal" -> stringResource(R.string.settings_bitrate_balanced)
                        else -> stringResource(R.string.settings_bitrate_crisp)
                    }
                    SettingsActionRow(
                        title = stringResource(R.string.settings_bitrate_title),
                        subtitle = qualityLabel,
                        icon = Icons.Rounded.HighQuality,
                        onClick = { showQualityDialog = true }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        thickness = 0.5.dp
                    )
                    val eqPresetCustom = stringResource(R.string.eq_preset_custom)
                    val eqPresetName = BlexsyEqualizer.Presets.firstOrNull { it.id == eqState.presetId }?.name ?: eqPresetCustom
                    SettingsActionRow(
                        title = stringResource(R.string.settings_equalizer_title),
                        subtitle = if (eqState.enabled) stringResource(R.string.settings_equalizer_tuned, eqPresetName) else stringResource(R.string.settings_equalizer_flat),
                        icon = Icons.Rounded.GraphicEq,
                        onClick = onOpenEqualizer
                    )
                }
            }

            // Customization
            item {
                SettingsGroup(title = stringResource(R.string.settings_group_looks)) {
                    SettingsActionRow(
                        title = stringResource(R.string.settings_app_tag_title),
                        subtitle = BlexsyThemeConfig.appName,
                        icon = Icons.Rounded.Edit,
                        onClick = { showAppNameDialog = true }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        thickness = 0.5.dp
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.settings_theme_shade_title),
                        subtitle = "${stringResource(BlexsyThemeConfig.themeMode.labelResId)} • ${stringResource(BlexsyThemeConfig.themeMode.descriptionResId)}",
                        icon = Icons.Rounded.DarkMode,
                        onClick = { showThemeModeDialog = true }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        thickness = 0.5.dp
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.settings_accent_drip_title),
                        subtitle = stringResource(R.string.settings_accent_drip_desc),
                        icon = Icons.Rounded.Palette,
                        trailing = {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(BlexsyColors.Primary)
                                    .border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                            )
                        },
                        onClick = { showColorDialog = true }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        thickness = 0.5.dp
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.settings_language_title),
                        subtitle = stringResource(BlexsyThemeConfig.appLanguage.titleResId),
                        icon = Icons.Rounded.Language,
                        onClick = { showLanguageDialog = true }
                    )
                }
            }

            // Storage & Cache
            item {
                SettingsGroup(title = stringResource(R.string.settings_group_cache)) {
                    SettingsActionRow(
                        title = stringResource(R.string.settings_cache_cap_title),
                        subtitle = stringResource(R.string.settings_cache_cap_desc, cacheLimitGb),
                        icon = Icons.Rounded.Storage,
                        onClick = { showLimitDialog = true }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        thickness = 0.5.dp
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.settings_flush_cache_title),
                        subtitle = stringResource(R.string.settings_flush_cache_holding, AudioCacheManager.formatBytes(cacheSizeBytes)),
                        icon = Icons.Rounded.DeleteOutline,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { showClearCacheDialog = true }
                    )
                }
            }

            // Playback Preferences
            item {
                SettingsGroup(title = stringResource(R.string.settings_group_playback)) {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_autoplay_title),
                        description = stringResource(R.string.settings_autoplay_desc),
                        checked = autoplayEnabled,
                        onCheckedChange = {
                            autoplayEnabled = it
                            prefs.edit().putBoolean("autoplay_enabled", it).apply()
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        thickness = 0.5.dp
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_keep_playing_title),
                        description = stringResource(R.string.settings_keep_playing_desc),
                        checked = keepPlayingClosed,
                        onCheckedChange = {
                            keepPlayingClosed = it
                            prefs.edit().putBoolean("pref_keep_playing_closed", it).apply()
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        thickness = 0.5.dp
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_save_state_title),
                        description = stringResource(R.string.settings_save_state_desc),
                        checked = saveLastState,
                        onCheckedChange = {
                            saveLastState = it
                            prefs.edit().putBoolean("pref_save_last_state", it).apply()
                            if (!it) {
                                context.getSharedPreferences("blexsy_player", Context.MODE_PRIVATE).edit().clear().apply()
                            }
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        thickness = 0.5.dp
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_skip_silence_title),
                        description = stringResource(R.string.settings_skip_silence_desc),
                        checked = skipSilence,
                        onCheckedChange = {
                            skipSilence = it
                            prefs.edit().putBoolean("pref_skip_silence", it).apply()
                            player?.setSkipSilence(it)
                        }
                    )
                }
            }

            // Lyrics Experience
            item {
                SettingsGroup(title = stringResource(R.string.settings_group_lyrics)) {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_translate_lyrics_title),
                        description = stringResource(R.string.settings_translate_lyrics_desc),
                        checked = translateLyrics,
                        onCheckedChange = {
                            translateLyrics = it
                            prefs.edit().putBoolean("pref_translate_lyrics", it).apply()
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        thickness = 0.5.dp
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_keep_screen_lit_title),
                        description = stringResource(R.string.settings_keep_screen_lit_desc),
                        checked = keepAwakeLyrics,
                        onCheckedChange = {
                            keepAwakeLyrics = it
                            prefs.edit().putBoolean("pref_keep_screen_awake", it).apply()
                        }
                    )
                }
            }

            // App Version
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${BlexsyThemeConfig.appName} v${BuildConfig.VERSION_NAME}",
                        style = BlexsyTypography.Caption.copy(
                            fontSize = 12.sp,
                            color = BlexsyColors.TextTertiary
                        )
                    )
                }
            }
        }
    }

    // Quality Selection BottomSheet
    if (showQualityDialog) {
        val options = listOf(
            "high" to stringResource(R.string.settings_bitrate_crisp),
            "normal" to stringResource(R.string.settings_bitrate_balanced),
            "low" to stringResource(R.string.settings_bitrate_data_saver)
        )
        ModalBottomSheet(
            onDismissRequest = { showQualityDialog = false },
            containerColor = BlexsyColors.SurfaceContainerHigh,
            contentColor = BlexsyColors.TextPrimary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.2f)) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_bitrate_title),
                    style = BlexsyTypography.Title.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                    color = BlexsyColors.TextPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                options.forEach { (key, label) ->
                    val selected = audioQuality.equals(key, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                audioQuality = key
                                AudioCacheManager.setAudioQuality(context, key)
                                showQualityDialog = false
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = BlexsyColors.Primary)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = label,
                            style = BlexsyTypography.Body,
                            color = if (selected) BlexsyColors.Primary else BlexsyColors.TextPrimary
                        )
                    }
                }
            }
        }
    }

    // Cache Limit Selection BottomSheet
    if (showLimitDialog) {
        val limits = listOf(1, 2, 4, 8, 16)
        ModalBottomSheet(
            onDismissRequest = { showLimitDialog = false },
            containerColor = BlexsyColors.SurfaceContainerHigh,
            contentColor = BlexsyColors.TextPrimary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.2f)) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_cache_cap_title),
                    style = BlexsyTypography.Title.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                    color = BlexsyColors.TextPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                limits.forEach { gb ->
                    val selected = cacheLimitGb == gb
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                cacheLimitGb = gb
                                AudioCacheManager.setCacheLimitGb(context, gb)
                                showLimitDialog = false
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = BlexsyColors.Primary)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "$gb GB",
                            style = BlexsyTypography.Body,
                            color = if (selected) BlexsyColors.Primary else BlexsyColors.TextPrimary
                        )
                    }
                }
            }
        }
    }

    // App Name / Launcher Selection BottomSheet
    if (showAppNameDialog) {
        val currentAlias = LauncherAliasManager.getCurrentAliasId(context)
        ModalBottomSheet(
            onDismissRequest = { showAppNameDialog = false },
            containerColor = BlexsyColors.SurfaceContainerHigh,
            contentColor = BlexsyColors.TextPrimary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.2f)) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_app_tag_dialog_title),
                    style = BlexsyTypography.Title.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                    color = BlexsyColors.TextPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LauncherAliasManager.ALIASES.forEach { alias ->
                    val selected = currentAlias.equals(alias.id, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                BlexsyThemeConfig.updateAppName(context, alias.label)
                                showAppNameDialog = false
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = BlexsyColors.Primary)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(alias.labelResId),
                            style = BlexsyTypography.Body,
                            color = if (selected) BlexsyColors.Primary else BlexsyColors.TextPrimary
                        )
                    }
                }
            }
        }
    }

    // Theme Mode Selection BottomSheet
    if (showThemeModeDialog) {
        val currentMode = BlexsyThemeConfig.themeMode
        ModalBottomSheet(
            onDismissRequest = { showThemeModeDialog = false },
            containerColor = BlexsyColors.SurfaceContainerHigh,
            contentColor = BlexsyColors.TextPrimary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.2f)) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_theme_shade_dialog_title),
                    style = BlexsyTypography.Title.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                    color = BlexsyColors.TextPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ThemeMode.entries.forEach { mode ->
                    val selected = currentMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                BlexsyThemeConfig.updateThemeMode(context, mode)
                                showThemeModeDialog = false
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = BlexsyColors.Primary)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(mode.labelResId),
                                style = BlexsyTypography.Body.copy(fontWeight = FontWeight.SemiBold),
                                color = if (selected) BlexsyColors.Primary else BlexsyColors.TextPrimary
                            )
                            Text(
                                text = stringResource(mode.descriptionResId),
                                style = BlexsyTypography.Caption,
                                color = BlexsyColors.TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    // App Language Selection BottomSheet
    if (showLanguageDialog) {
        val currentLang = BlexsyThemeConfig.appLanguage
        ModalBottomSheet(
            onDismissRequest = { showLanguageDialog = false },
            containerColor = BlexsyColors.SurfaceContainerHigh,
            contentColor = BlexsyColors.TextPrimary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.2f)) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_language_dialog_title),
                    style = BlexsyTypography.Title.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                    color = BlexsyColors.TextPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                AppLanguage.entries.forEach { lang ->
                    val selected = currentLang == lang
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                BlexsyThemeConfig.updateAppLanguage(context, lang)
                                showLanguageDialog = false
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = BlexsyColors.Primary)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(lang.titleResId),
                            style = BlexsyTypography.Body,
                            color = if (selected) BlexsyColors.Primary else BlexsyColors.TextPrimary
                        )
                    }
                }
            }
        }
    }

    // Clear Cache Confirmation Alert Dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            containerColor = BlexsyColors.SurfaceContainerHigh,
            title = {
                Text(
                    text = stringResource(R.string.settings_flush_cache_dialog_title),
                    style = BlexsyTypography.Title.copy(fontWeight = FontWeight.Bold),
                    color = BlexsyColors.TextPrimary
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_flush_cache_dialog_desc, AudioCacheManager.formatBytes(cacheSizeBytes)),
                    style = BlexsyTypography.Body.copy(color = BlexsyColors.TextSecondary)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheDialog = false
                        scope.launch(Dispatchers.IO) {
                            AudioCacheManager.clearCache(context)
                            cacheSizeBytes = AudioCacheManager.getCurrentCacheSizeBytes(context)
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.settings_flush_cache_dialog_confirm),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(
                        text = stringResource(R.string.settings_flush_cache_dialog_dismiss),
                        color = BlexsyColors.TextSecondary
                    )
                }
            }
        )
    }

    // Primary Color Selection Dialog
    if (showColorDialog) {
        val currentPrimary = BlexsyColors.Primary
        AlertDialog(
            onDismissRequest = { showColorDialog = false },
            containerColor = BlexsyColors.SurfaceContainerHigh,
            title = {
                Text(
                    text = stringResource(R.string.settings_accent_drip_dialog_title),
                    style = BlexsyTypography.Title.copy(fontWeight = FontWeight.Bold),
                    color = BlexsyColors.TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PRESET_COLORS.chunked(4).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            row.forEach { (color, _) ->
                                val isSelected = currentPrimary == color
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (isSelected) 2.5.dp else 1.dp,
                                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.2f),
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            BlexsyThemeConfig.updatePrimaryColor(context, color)
                                            showColorDialog = false
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = stringResource(R.string.a11y_selected),
                                            tint = Color.Black,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // About BottomSheet
    if (showAboutSheet) {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        )
        val openUrl = { url: String ->
            if (url.isNotBlank()) {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        }
        val contributorsData by produceState(initialValue = ContributorsService.fallback) {
            value = ContributorsService.getContributors(context)
        }

        val consumeOverscrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource
                ): Offset {
                    // Consume downward overshoot so parent bottom sheet doesn't cancel clicks
                    return if (available.y < 0f) Offset(0f, available.y) else Offset.Zero
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity
                ): Velocity = available
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showAboutSheet = false },
            sheetState = sheetState,
            containerColor = BlexsyColors.SurfaceContainerHigh,
            contentColor = BlexsyColors.TextPrimary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.2f)) }
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(consumeOverscrollConnection),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = BlexsyThemeConfig.appName.uppercase(),
                            style = BlexsyTypography.Display.copy(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold),
                            color = BlexsyColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                            style = BlexsyTypography.Caption.copy(color = BlexsyColors.TextSecondary, fontSize = 12.sp)
                        )
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.settings_about_tagline),
                        style = BlexsyTypography.Body.copy(
                            color = BlexsyColors.Primary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        ),
                        textAlign = TextAlign.Center
                    )
                }

                item {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                }

                // The Squad (Contributors)
                if (contributorsData.contributors.isNotEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_about_squad),
                                style = BlexsyTypography.Caption.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                    fontSize = 11.sp,
                                    color = BlexsyColors.TextSecondary
                                ),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(BlexsyColors.SurfaceContainerHighest)
                            ) {
                                contributorsData.contributors.forEachIndexed { index, person ->
                                    Surface(
                                        onClick = { openUrl(person.githubUrl) },
                                        enabled = person.githubUrl.isNotBlank(),
                                        color = Color.Transparent,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AsyncImage(
                                                model = person.avatarUrl.ifBlank { dicebearAvatarUrl(person.name) },
                                                contentDescription = person.name,
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(BlexsyColors.SurfaceContainerHigh)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = person.name,
                                                    style = BlexsyTypography.Body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                                                    color = BlexsyColors.TextPrimary
                                                )
                                                Text(
                                                    text = person.role,
                                                    style = BlexsyTypography.Caption.copy(fontSize = 12.sp, color = BlexsyColors.TextSecondary)
                                                )
                                            }
                                            if (person.githubUrl.isNotBlank()) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                                                    contentDescription = stringResource(R.string.a11y_view_profile),
                                                    tint = BlexsyColors.TextTertiary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                    if (index < contributorsData.contributors.size - 1) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 62.dp),
                                            color = Color.White.copy(alpha = 0.06f),
                                            thickness = 0.5.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Props & Credits
                if (contributorsData.credits.isNotEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_about_credits),
                                style = BlexsyTypography.Caption.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                    fontSize = 11.sp,
                                    color = BlexsyColors.TextSecondary
                                ),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(BlexsyColors.SurfaceContainerHighest)
                            ) {
                                contributorsData.credits.forEachIndexed { index, credit ->
                                    Surface(
                                        onClick = { openUrl(credit.url) },
                                        enabled = credit.url.isNotBlank(),
                                        color = Color.Transparent,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = credit.name,
                                                    style = BlexsyTypography.Body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                                                    color = BlexsyColors.TextPrimary
                                                )
                                                Text(
                                                    text = credit.role,
                                                    style = BlexsyTypography.Caption.copy(fontSize = 12.sp, color = BlexsyColors.TextSecondary)
                                                )
                                            }
                                            if (credit.url.isNotBlank()) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                                                    contentDescription = stringResource(R.string.a11y_open_link),
                                                    tint = BlexsyColors.TextTertiary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                    if (index < contributorsData.credits.size - 1) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 14.dp),
                                            color = Color.White.copy(alpha = 0.06f),
                                            thickness = 0.5.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.settings_about_footer),
                        style = BlexsyTypography.Caption.copy(
                            color = BlexsyColors.TextTertiary,
                            fontSize = 11.sp
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

private val PRESET_COLORS = listOf(
    Color(0xFF1DB954) to "Spotify Green",
    Color(0xFFA238FF) to "Deezer Purple",
    Color(0xFFFA243C) to "Apple Music Red",
    Color(0xFF00E5FF) to "Tidal Cyan",
    Color(0xFFFF0000) to "YouTube Music Red",
    Color(0xFF0038E2) to "Amazon Music Blue",
    Color(0xFFFF5500) to "SoundCloud Orange",
    Color(0xFFCCF05B) to "Blek Yellow",
)

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = BlexsyTypography.Caption.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = BlexsyColors.TextSecondary
            ),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(BlexsyColors.SurfaceContainerHigh)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                style = BlexsyTypography.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = BlexsyColors.TextPrimary
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = BlexsyTypography.Caption.copy(fontSize = 12.sp, color = BlexsyColors.TextSecondary)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = BlexsyColors.Primary,
                uncheckedThumbColor = BlexsyColors.TextSecondary,
                uncheckedTrackColor = BlexsyColors.SurfaceContainerHighest,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tint: Color = BlexsyColors.TextPrimary,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = BlexsyTypography.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    color = tint
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = BlexsyTypography.Caption.copy(fontSize = 12.sp, color = BlexsyColors.TextSecondary)
                    )
                }
            }
            if (trailing != null) {
                trailing()
            }
        }
    }
}
