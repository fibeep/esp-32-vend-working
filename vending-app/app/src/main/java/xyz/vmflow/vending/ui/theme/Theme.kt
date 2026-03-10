package xyz.vmflow.vending.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material3 light color scheme for the VMflow Vending App.
 *
 * Uses the blue-toned VMflow brand palette defined in [Color.kt].
 */
private val LightColorScheme = lightColorScheme(
    primary = VmflowPrimary,
    onPrimary = VmflowOnPrimary,
    primaryContainer = VmflowPrimaryContainer,
    onPrimaryContainer = VmflowOnPrimaryContainer,
    secondary = VmflowSecondary,
    onSecondary = VmflowOnSecondary,
    secondaryContainer = VmflowSecondaryContainer,
    onSecondaryContainer = VmflowOnSecondaryContainer,
    tertiary = VmflowTertiary,
    onTertiary = VmflowOnTertiary,
    tertiaryContainer = VmflowTertiaryContainer,
    onTertiaryContainer = VmflowOnTertiaryContainer,
    error = VmflowError,
    onError = VmflowOnError,
    errorContainer = VmflowErrorContainer,
    onErrorContainer = VmflowOnErrorContainer,
    background = VmflowBackground,
    onBackground = VmflowOnBackground,
    surface = VmflowSurface,
    onSurface = VmflowOnSurface,
    surfaceVariant = VmflowSurfaceVariant,
    onSurfaceVariant = VmflowOnSurfaceVariant,
    outline = VmflowOutline,
    outlineVariant = VmflowOutlineVariant
)

/**
 * Material3 dark color scheme for the VMflow Vending App.
 *
 * Provides a dark variant of the brand palette for use in dark mode.
 */
private val DarkColorScheme = darkColorScheme(
    primary = VmflowPrimaryDark,
    onPrimary = VmflowOnPrimaryDark,
    primaryContainer = VmflowPrimaryContainerDark,
    onPrimaryContainer = VmflowOnPrimaryContainerDark,
    background = VmflowBackgroundDark,
    onBackground = VmflowOnBackgroundDark,
    surface = VmflowSurfaceDark,
    onSurface = VmflowOnSurfaceDark
)

/**
 * The root Material3 theme for the VMflow Vending App.
 *
 * Supports:
 * - **Dynamic Color** (Material You) on Android 12+ devices
 * - **Light/Dark mode** based on system preference
 * - **Custom VMflow palette** as fallback on older devices
 *
 * Wrap all Composable screens with this theme to ensure consistent styling.
 *
 * @param darkTheme Whether to use the dark color scheme. Defaults to system setting.
 * @param dynamicColor Whether to use Material You dynamic colors on Android 12+.
 *                     Defaults to true, falling back to the static VMflow palette.
 * @param content The composable content to render within the theme.
 */
@Composable
fun VendingAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Use Material You dynamic colors on Android 12+ (API 31+)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // Fall back to static VMflow palette
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VmflowTypography,
        content = content
    )
}
