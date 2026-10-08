package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RudraXColorScheme = darkColorScheme(
    primary = RudraXCyan,
    onPrimary = RudraXBlack,
    primaryContainer = RudraXAzureContainer,
    onPrimaryContainer = RudraXCyan,
    secondary = RudraXAzure,
    onSecondary = RudraXWhite,
    secondaryContainer = RudraXSurfaceContainer,
    onSecondaryContainer = RudraXWhite,
    tertiary = RudraXEmerald,
    onTertiary = RudraXBlack,
    background = RudraXBlack,
    onBackground = RudraXWhite,
    surface = RudraXSurface,
    onSurface = RudraXWhite,
    surfaceVariant = RudraXSurfaceElevated,
    onSurfaceVariant = RudraXTextSecondary,
    outline = RudraXBorder,
    error = RudraXCrimson,
    onError = RudraXWhite
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = RudraXColorScheme,
        typography = Typography,
        content = content
    )
}
