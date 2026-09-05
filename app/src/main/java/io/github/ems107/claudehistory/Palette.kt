package io.github.ems107.claudehistory

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * claude-history's own colours, so the app and the pages inside it agree.
 *
 * The schemes are written out in full rather than derived from one seed
 * because Material's defaults are purple, and every role left unstated shows
 * it -- the floating button and the containers arrive lilac in the middle of a
 * warm grey app. Stating them costs less than explaining them.
 */
val Accent = Color(0xFFD97757)
val LightGround = Color(0xFFFAF9F5)
val DarkGround = Color(0xFF1F1E1C)

private val Ink = Color(0xFF1F1E1C)
private val Paper = Color(0xFFFAF9F5)

val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6DED4),
    onPrimaryContainer = Color(0xFF3B1B0E),
    secondary = Color(0xFF6F5B52),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1E7E1),
    onSecondaryContainer = Color(0xFF2A1C16),
    tertiary = Color(0xFF5C6252),
    onTertiary = Color.White,
    background = LightGround,
    onBackground = Ink,
    surface = LightGround,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDEAE2),
    onSurfaceVariant = Color(0xFF57534E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F3EC),
    surfaceContainer = Color(0xFFF0EEE6),
    surfaceContainerHigh = Color(0xFFEAE7DE),
    surfaceContainerHighest = Color(0xFFE4E1D7),
    outline = Color(0xFFB6B0A6),
    outlineVariant = Color(0xFFDDD8CE),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF2B1509),
    primaryContainer = Color(0xFF5A3323),
    onPrimaryContainer = Color(0xFFFAD9CC),
    secondary = Color(0xFFD6C3B9),
    onSecondary = Color(0xFF3A2A22),
    secondaryContainer = Color(0xFF4A3A32),
    onSecondaryContainer = Color(0xFFF1E7E1),
    tertiary = Color(0xFFC3C9B4),
    onTertiary = Color(0xFF2C3125),
    background = DarkGround,
    onBackground = Paper,
    surface = DarkGround,
    onSurface = Paper,
    surfaceVariant = Color(0xFF33312D),
    onSurfaceVariant = Color(0xFFC5C0B8),
    surfaceContainerLowest = Color(0xFF141312),
    surfaceContainerLow = Color(0xFF262523),
    surfaceContainer = Color(0xFF2A2927),
    surfaceContainerHigh = Color(0xFF34322F),
    surfaceContainerHighest = Color(0xFF3E3C38),
    outline = Color(0xFF6C6862),
    outlineVariant = Color(0xFF44413C),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)
