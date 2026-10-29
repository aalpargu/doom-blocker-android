package dev.personal.doomblocker.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Warm neutral surfaces, botanical accents and generous, consistent shapes. */
internal object DoomTokens {
    val spaceXs = 4.dp
    val spaceSm = 8.dp
    val spaceMd = 12.dp
    val spaceLg = 16.dp
    val spaceXl = 18.dp
    val space2Xl = 22.dp
    val space3Xl = 28.dp

    val radiusSm = 10.dp
    val radiusMd = 18.dp
    val radiusLg = 24.dp
    val radiusXl = 28.dp

    val controlHeight = 52.dp
    val maxContentWidth = 720.dp
}

internal val FocusGreen = Color(0xFF246B53)
internal val FocusGreenDark = Color(0xFFA1DFBF)
internal val WarningAmber = Color(0xFF99610A)
internal val WarningAmberDark = Color(0xFFFFC86A)

private val LightColors = lightColorScheme(
    primary = FocusGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EFDF),
    onPrimaryContainer = Color(0xFF073C2C),
    secondary = Color(0xFF52655D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1EAE5),
    onSecondaryContainer = Color(0xFF20372E),
    tertiary = WarningAmber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEBC8),
    onTertiaryContainer = Color(0xFF3F2A04),
    background = Color(0xFFF6F5F0),
    onBackground = Color(0xFF18201C),
    surface = Color(0xFFFCFBF7),
    onSurface = Color(0xFF18201C),
    surfaceVariant = Color(0xFFECEDE5),
    onSurfaceVariant = Color(0xFF56615B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F0E8),
    surfaceContainer = Color(0xFFECEDE5),
    surfaceContainerHigh = Color(0xFFE5E7DC),
    outline = Color(0xFF78847D),
    outlineVariant = Color(0xFFD9DED3),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = FocusGreenDark,
    onPrimary = Color(0xFF003827),
    primaryContainer = Color(0xFF234F3B),
    onPrimaryContainer = Color(0xFFB5F2D5),
    secondary = Color(0xFFBACCC2),
    onSecondary = Color(0xFF25362F),
    secondaryContainer = Color(0xFF33473E),
    onSecondaryContainer = Color(0xFFD7E8DE),
    tertiary = WarningAmberDark,
    onTertiary = Color(0xFF4F3500),
    tertiaryContainer = Color(0xFF684900),
    onTertiaryContainer = Color(0xFFFFDEA3),
    background = Color(0xFF101713),
    onBackground = Color(0xFFE1E8E3),
    surface = Color(0xFF171F19),
    onSurface = Color(0xFFE1E8E3),
    surfaceVariant = Color(0xFF27302B),
    onSurfaceVariant = Color(0xFFBBC6BF),
    surfaceContainerLowest = Color(0xFF0B0F0D),
    surfaceContainerLow = Color(0xFF181E1A),
    surfaceContainer = Color(0xFF202B23),
    surfaceContainerHigh = Color(0xFF252D28),
    outline = Color(0xFF89958E),
    outlineVariant = Color(0xFF3D4942),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val DoomTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.35).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.15.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.25.sp,
    ),
)

private val DoomShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(DoomTokens.radiusSm),
    small = androidx.compose.foundation.shape.RoundedCornerShape(DoomTokens.radiusMd),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(DoomTokens.radiusLg),
    large = androidx.compose.foundation.shape.RoundedCornerShape(DoomTokens.radiusXl),
)

@Composable
internal fun DoomBlockerTheme(
    amoledBlack: Boolean = false,
    accent: AccentOption = AccentOption.MINT,
    content: @Composable () -> Unit,
) {
    val dark = shouldUseDarkTheme(isSystemInDarkTheme(), amoledBlack)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.let { activity ->
                WindowCompat.getInsetsController(activity.window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    val base = if (dark) DarkColors else LightColors
    val accented = when (accent) {
        AccentOption.MINT -> base
        AccentOption.BLUE -> if (dark) base.copy(
            primary = Color(0xFF8CCBFF), onPrimary = Color(0xFF003351),
            primaryContainer = Color(0xFF154C6D), onPrimaryContainer = Color(0xFFCDE9FF),
        ) else base.copy(
            primary = Color(0xFF006398), onPrimary = Color.White,
            primaryContainer = Color(0xFFCDE9FF), onPrimaryContainer = Color(0xFF001E31),
        )
        AccentOption.PURPLE -> if (dark) base.copy(
            primary = Color(0xFFD1BCFF), onPrimary = Color(0xFF38265D),
            primaryContainer = Color(0xFF503D75), onPrimaryContainer = Color(0xFFEBDDFF),
        ) else base.copy(
            primary = Color(0xFF67508D), onPrimary = Color.White,
            primaryContainer = Color(0xFFEBDDFF), onPrimaryContainer = Color(0xFF23123F),
        )
    }
    val colors = if (dark && amoledBlack) accented.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color(0xFF070B08),
        surfaceContainerLow = Color(0xFF0C110E),
        surfaceContainer = Color(0xFF141B17),
        surfaceContainerHigh = Color(0xFF1C2520),
    ) else accented
    MaterialTheme(
        colorScheme = colors,
        typography = DoomTypography,
        shapes = DoomShapes,
        content = content,
    )
}

internal fun shouldUseDarkTheme(systemDark: Boolean, amoledBlack: Boolean): Boolean =
    systemDark || amoledBlack

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
