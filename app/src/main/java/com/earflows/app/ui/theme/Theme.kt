package com.earflows.app.ui.theme

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

// EarFlows brand colors — deep indigo/purple for "audio flow" vibes
private val EarFlowsPrimary = Color(0xFF6C5CE7)
private val EarFlowsSecondary = Color(0xFF00B894)
private val EarFlowsTertiary = Color(0xFFFD79A8)

private val DarkColorScheme = darkColorScheme(
    primary = EarFlowsPrimary,
    secondary = EarFlowsSecondary,
    tertiary = EarFlowsTertiary,
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    onBackground = Color(0xFFF0F6FC),
    onSurface = Color(0xFFF0F6FC),
)

private val LightColorScheme = lightColorScheme(
    primary = EarFlowsPrimary,
    secondary = EarFlowsSecondary,
    tertiary = EarFlowsTertiary,
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun EarFlowsTheme(
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
        content = content
    )
}
