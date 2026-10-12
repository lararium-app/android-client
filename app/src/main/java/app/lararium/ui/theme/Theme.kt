package app.lararium.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = AIDarkPrimary,
    onPrimary = AIDarkOnPrimary,
    primaryContainer = AIDarkPrimaryContainer,
    onPrimaryContainer = AIDarkOnPrimaryContainer,
    secondary = AIDarkSecondary,
    onSecondary = AIDarkOnSecondary,
    secondaryContainer = AIDarkSecondaryContainer,
    onSecondaryContainer = AIDarkOnSecondaryContainer,
    tertiary = AIDarkTertiary,
    onTertiary = AIDarkOnTertiary,
    tertiaryContainer = AIDarkTertiaryContainer,
    onTertiaryContainer = AIDarkOnTertiaryContainer,
    background = AIDarkBackground,
    onBackground = AIDarkOnBackground,
    surface = AIDarkSurface,
    onSurface = AIDarkOnSurface,
    surfaceVariant = AIDarkSurfaceVariant,
    onSurfaceVariant = AIDarkOnSurfaceVariant,
    outline = AIDarkOutline,
    error = AIDarkError,
    onError = AIDarkOnError,
    errorContainer = AIDarkErrorContainer,
    onErrorContainer = AIDarkOnErrorContainer
)

private val LightColorScheme = lightColorScheme(
    primary = AILightPrimary,
    onPrimary = AILightOnPrimary,
    primaryContainer = AILightPrimaryContainer,
    onPrimaryContainer = AILightOnPrimaryContainer,
    secondary = AILightSecondary,
    onSecondary = AILightOnSecondary,
    secondaryContainer = AILightSecondaryContainer,
    onSecondaryContainer = AILightOnSecondaryContainer,
    tertiary = AILightTertiary,
    onTertiary = AILightOnTertiary,
    tertiaryContainer = AILightTertiaryContainer,
    onTertiaryContainer = AILightOnTertiaryContainer,
    background = AILightBackground,
    onBackground = AILightOnBackground,
    surface = AILightSurface,
    onSurface = AILightOnSurface,
    surfaceVariant = AILightSurfaceVariant,
    onSurfaceVariant = AILightOnSurfaceVariant,
    outline = AILightOutline,
    error = AILightError,
    onError = AILightOnError,
    errorContainer = AILightErrorContainer,
    onErrorContainer = AILightOnErrorContainer
)

val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AiintegratedintoandroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
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
        shapes = ExpressiveShapes,
        content = content
    )
}
