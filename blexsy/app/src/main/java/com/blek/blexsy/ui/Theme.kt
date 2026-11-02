package com.blek.blexsy.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blek.blexsy.R
import java.util.Random

enum class ThemeMode(
    val labelResId: Int,
    val descriptionResId: Int
) {
    DARK(R.string.theme_mode_dark, R.string.theme_mode_dark_desc),
    AMOLED(R.string.theme_mode_amoled, R.string.theme_mode_amoled_desc)
}

enum class AppLanguage(
    val code: String,
    val titleResId: Int
) {
    SYSTEM("", R.string.settings_language_system),
    ENGLISH("en", R.string.settings_language_en),
    INDONESIAN("in", R.string.settings_language_id)
}

/**
 * Dynamic app branding and primary color configuration persisted to SharedPreferences.
 * ponytail: shared preferences storage with instant Compose State updates; upgrade to DataStore if complex themes are added.
 */
object BlexsyThemeConfig {
    var appName by mutableStateOf("Blexsy")
    var primaryColor by mutableStateOf(Color(0xFF1DB954))
    var themeMode by mutableStateOf(ThemeMode.DARK)
    var appLanguage by mutableStateOf(AppLanguage.SYSTEM)

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
        appName = prefs.getString("custom_app_name", "Blexsy")?.ifBlank { "Blexsy" } ?: "Blexsy"
        val argb = prefs.getInt("custom_primary_color", 0xFF1DB954.toInt())
        primaryColor = Color(argb)
        val savedTheme = prefs.getString("theme_mode", ThemeMode.DARK.name)
        themeMode = runCatching { ThemeMode.valueOf(savedTheme ?: ThemeMode.DARK.name) }.getOrDefault(ThemeMode.DARK)
        val savedLang = prefs.getString("app_language", AppLanguage.SYSTEM.name)
        appLanguage = runCatching { AppLanguage.valueOf(savedLang ?: AppLanguage.SYSTEM.name) }.getOrDefault(AppLanguage.SYSTEM)
    }

    fun updateAppName(context: Context, name: String) {
        val clean = name.trim().ifBlank { "Blexsy" }
        appName = clean
        context.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("custom_app_name", clean)
            .apply()

        val matchingAlias = LauncherAliasManager.ALIASES.firstOrNull {
            it.label.equals(clean, ignoreCase = true)
        }
        if (matchingAlias != null) {
            LauncherAliasManager.setLauncherAlias(context, matchingAlias.id)
        }
    }

    fun updatePrimaryColor(context: Context, color: Color) {
        primaryColor = color
        context.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("custom_primary_color", color.toArgb())
            .apply()
    }

    fun updateThemeMode(context: Context, mode: ThemeMode) {
        themeMode = mode
        context.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("theme_mode", mode.name)
            .apply()
    }

    fun updateAppLanguage(context: Context, language: AppLanguage) {
        appLanguage = language
        context.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("app_language", language.name)
            .apply()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
            val tag = when (language.code) {
                "in" -> "id"
                else -> language.code
            }
            val localeList = if (tag.isEmpty()) {
                android.os.LocaleList.getEmptyLocaleList()
            } else {
                android.os.LocaleList.forLanguageTags(tag)
            }
            localeManager?.applicationLocales = localeList
        }
    }
}

/**
 * Spotify-inspired color palette with dynamic Primary accent.
 */
object BlexsyColors {
    val isAmoled: Boolean get() = BlexsyThemeConfig.themeMode == ThemeMode.AMOLED

    // Core Spotify surfaces
    val Background: Color get() = if (isAmoled) Color(0xFF000000) else Color(0xFF121212)
    val BackgroundDeep: Color = Color(0xFF000000)
    val SpotifyBlack: Color = Color(0xFF000000)

    val Surface: Color get() = if (isAmoled) Color(0xFF0A0A0A) else Color(0xFF181818)
    val SurfaceElevated: Color get() = if (isAmoled) Color(0xFF121212) else Color(0xFF1F1F1F)
    val SurfaceContainerLow: Color get() = if (isAmoled) Color(0xFF0A0A0A) else Color(0xFF181818)
    val SurfaceContainer: Color get() = if (isAmoled) Color(0xFF121212) else Color(0xFF1F1F1F)
    val SurfaceContainerHigh: Color get() = if (isAmoled) Color(0xFF161616) else Color(0xFF242424)
    val SurfaceContainerHighest: Color get() = if (isAmoled) Color(0xFF202020) else Color(0xFF2A2A2A)

    val Border: Color get() = if (isAmoled) Color(0xFF1D1D1D) else Color(0xFF2A2A2A)
    val Hairline: Color get() = if (isAmoled) Color(0x14FFFFFF) else Color(0x1FFFFFFF)

    // Dynamic Primary accent
    val Primary: Color get() = BlexsyThemeConfig.primaryColor
    val OnPrimary = Color(0xFF000000)
    val PrimaryContainer: Color get() = BlexsyThemeConfig.primaryColor
    val OnPrimaryContainer = Color(0xFF000000)
    val PrimaryDim: Color get() = BlexsyThemeConfig.primaryColor.copy(alpha = 0.20f)
    val Accent: Color get() = BlexsyThemeConfig.primaryColor

    // Secondary / tertiary greys
    val Secondary = Color(0xFFB3B3B3)
    val SecondaryContainer: Color get() = if (isAmoled) Color(0xFF161616) else Color(0xFF242424)
    val OnSecondaryContainer = Color(0xFFFFFFFF)
    val Tertiary = Color(0xFFFFFFFF)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextMuted = Color(0xFFA7A7A7)
    val TextTertiary = Color(0xFF6A6A6A)

    val Danger = Color(0xFFE91429)
    val Warning = Color(0xFFFFA42B)
    val Success = Color(0xFF1DB954)
}

/**
 * Spotify uses tight, consistent corner radii. Album art sits at ~4dp, cards at 8dp.
 */
val BlexsyShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

/**
 * Faint dither grain over glass and gradients.
 * Breaks up 8-bit quantization banding lines without looking noisy.
 */
const val FrostGrainAlpha = 0.035f
private const val FrostGrainTileSize = 64

val FrostGrainBrush: ShaderBrush by lazy {
    val random = Random(0x6A55)
    val pixels = IntArray(FrostGrainTileSize * FrostGrainTileSize) {
        val alpha = random.nextInt(256) shl 24
        if (random.nextBoolean()) alpha or 0xFFFFFF else alpha
    }
    val tile = Bitmap.createBitmap(
        pixels, FrostGrainTileSize, FrostGrainTileSize, Bitmap.Config.ARGB_8888
    )
    ShaderBrush(ImageShader(tile.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

internal val LocalBlexsyFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

val DeezerFont = FontFamily(
    Font(R.font.deezer_bold, FontWeight.Bold),
    Font(R.font.deezer_extrabold, FontWeight.ExtraBold)
)
val DeezerBoldFont = FontFamily(Font(R.font.deezer_bold, FontWeight.Bold))
val DeezerExtraBoldFont = FontFamily(Font(R.font.deezer_extrabold, FontWeight.ExtraBold))

@Composable
private fun poppinsFontFamily(): FontFamily = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_extrabold, FontWeight.ExtraBold)
)

private fun poppinsTypography(fontFamily: FontFamily): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = fontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = fontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = fontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = fontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = fontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = fontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = fontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = fontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = fontFamily)
    )
}

object BlexsyTypography {
    val Display: TextStyle
        @Composable get() = TextStyle(
            fontFamily = DeezerExtraBoldFont,
            fontSize = 32.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.2.sp,
            color = BlexsyColors.TextPrimary
        )

    val Hero: TextStyle
        @Composable get() = TextStyle(
            fontFamily = DeezerExtraBoldFont,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.2.sp,
            color = BlexsyColors.TextPrimary
        )

    val SectionTitle: TextStyle
        @Composable get() = TextStyle(
            fontFamily = DeezerExtraBoldFont,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.2.sp,
            color = BlexsyColors.TextPrimary
        )

    val Eyebrow: TextStyle
        @Composable get() = TextStyle(
            fontFamily = LocalBlexsyFontFamily.current,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = BlexsyColors.Primary
        )

    val Title: TextStyle
        @Composable get() = TextStyle(
            fontFamily = LocalBlexsyFontFamily.current,
            fontSize = 16.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp,
            color = BlexsyColors.TextPrimary
        )

    val TitleActive: TextStyle
        @Composable get() = TextStyle(
            fontFamily = LocalBlexsyFontFamily.current,
            fontSize = 16.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
            color = BlexsyColors.Primary
        )

    val Subtitle: TextStyle
        @Composable get() = TextStyle(
            fontFamily = LocalBlexsyFontFamily.current,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Normal,
            color = BlexsyColors.TextSecondary
        )

    val Body: TextStyle
        @Composable get() = TextStyle(
            fontFamily = LocalBlexsyFontFamily.current,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Normal,
            color = BlexsyColors.TextPrimary
        )

    val BodyMuted: TextStyle
        @Composable get() = TextStyle(
            fontFamily = LocalBlexsyFontFamily.current,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal,
            color = BlexsyColors.TextSecondary
        )

    val Caption: TextStyle
        @Composable get() = TextStyle(
            fontFamily = LocalBlexsyFontFamily.current,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.1.sp,
            color = BlexsyColors.TextMuted
        )

    val Label: TextStyle
        @Composable get() = TextStyle(
            fontFamily = LocalBlexsyFontFamily.current,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp,
            color = BlexsyColors.TextPrimary
        )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BlexsyTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(context) {
        BlexsyThemeConfig.init(context)
    }

    val currentLang = BlexsyThemeConfig.appLanguage
    val localizedContext = remember(context, currentLang) {
        if (currentLang.code.isEmpty()) {
            context
        } else {
            val locale = java.util.Locale(currentLang.code)
            java.util.Locale.setDefault(locale)
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(locale)
            context.createConfigurationContext(config)
        }
    }
    val localizedConfiguration = remember(localizedContext) {
        localizedContext.resources.configuration
    }

    val poppins = poppinsFontFamily()
    val primary = BlexsyThemeConfig.primaryColor
    val themeMode = BlexsyThemeConfig.themeMode
    val colorScheme = remember(primary, themeMode) {
        darkColorScheme(
            primary = primary,
            onPrimary = BlexsyColors.OnPrimary,
            primaryContainer = primary,
            onPrimaryContainer = BlexsyColors.OnPrimaryContainer,
            secondary = BlexsyColors.Secondary,
            onSecondary = Color.Black,
            secondaryContainer = BlexsyColors.SecondaryContainer,
            onSecondaryContainer = BlexsyColors.OnSecondaryContainer,
            tertiary = BlexsyColors.Tertiary,
            onTertiary = Color.Black,
            background = BlexsyColors.Background,
            onBackground = BlexsyColors.TextPrimary,
            surface = BlexsyColors.Surface,
            onSurface = BlexsyColors.TextPrimary,
            surfaceVariant = BlexsyColors.SurfaceElevated,
            onSurfaceVariant = BlexsyColors.TextSecondary,
            surfaceContainerLowest = BlexsyColors.BackgroundDeep,
            surfaceContainerLow = BlexsyColors.SurfaceContainerLow,
            surfaceContainer = BlexsyColors.SurfaceContainer,
            surfaceContainerHigh = BlexsyColors.SurfaceContainerHigh,
            surfaceContainerHighest = BlexsyColors.SurfaceContainerHighest,
            outline = BlexsyColors.Border,
            outlineVariant = BlexsyColors.Hairline,
            error = BlexsyColors.Danger
        )
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        androidx.compose.ui.platform.LocalConfiguration provides localizedConfiguration,
        LocalBlexsyFontFamily provides poppins
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = BlexsyShapes,
            typography = poppinsTypography(poppins),
            content = content
        )
    }
}
