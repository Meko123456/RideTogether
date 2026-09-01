package io.github.meko123456.ridetogether.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val RoadBlue = Color(0xFF1B5E9C)
private val RoadBlueLight = Color(0xFF8FC6FF)
private val HiVis = Color(0xFF6E8C10)
private val HiVisLight = Color(0xFFC6F02A)
private val Asphalt = Color(0xFF2B3238)

private val LightColors = lightColorScheme(primary = RoadBlue, secondary = HiVis, tertiary = Asphalt)
private val DarkColors = darkColorScheme(primary = RoadBlueLight, secondary = HiVisLight, tertiary = HiVisLight)

@Composable
fun RideTogetherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
