package me.ashikoki.listea.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun ListeaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

/**
 * White on a black scrim: the palette for chrome that sits *on* the media rather than beside it.
 *
 * Not the app's scheme, dark or otherwise. What is behind these bars is 60% black over an
 * arbitrary photograph, so a control's contrast against it cannot be reasoned about from a
 * surface colour the way it can everywhere else in the app - there is no surface, and the
 * photograph underneath is a different colour every card. White is the one content colour that
 * holds against all of them, which is why every gallery and every video player converges on it.
 *
 * Named here rather than colour-by-colour at each control because these bars are built from
 * ordinary Material components: a [androidx.compose.material3.Checkbox] reads `primary` and
 * `onPrimary`, a [androidx.compose.material3.FilterChip] reads `secondaryContainer`, `outline`
 * and `onSurfaceVariant`, and neither takes a "just be white" instruction. Setting the roles they
 * actually read is what makes them all come out white without any of them being special-cased.
 *
 * Selected things invert - a white pill with a black label - rather than tinting, because a tint
 * is a hue difference and hue is exactly what an unknown photograph behind it will not preserve.
 */
private val MediaChromeColorScheme = darkColorScheme(
    // The filled half of a checked Checkbox, and its checkmark.
    primary = Color.White,
    onPrimary = Color.Black,
    // The filled half of a selected FilterChip, and its label.
    secondaryContainer = Color.White,
    onSecondaryContainer = Color.Black,
    // Labels, icons and the unchecked Checkbox's border.
    onSurface = Color.White,
    onSurfaceVariant = Color.White,
    onBackground = Color.White,
    // An unselected FilterChip's border.
    outline = Color.White,
    outlineVariant = Color.White,
    // Nothing in the bars paints its own background: the scrim is the background, and a container
    // colour here would only draw an opaque patch over the media the bar is meant to sit on.
    surface = Color.Transparent,
    surfaceVariant = Color.Transparent,
    background = Color.Transparent
)

/**
 * Wraps the contents of a media bar so every control in it comes out white.
 *
 * [LocalContentColor] is the half that the colour scheme cannot reach.
 * [androidx.compose.material3.IconButton] and an uncoloured
 * [androidx.compose.material3.Text] both take their colour from it, and it defaults to black
 * outside a [androidx.compose.material3.Surface] - which is why the back arrow, the filename and
 * the info icon were being drawn in black on a black bar while the chips beside them were fine.
 */
@Composable
fun ListeaMediaChromeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MediaChromeColorScheme, typography = Typography) {
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}
