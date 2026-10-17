package com.example.scoreledger.ui
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
private val Light = lightColorScheme(primary=Color(0xFF356A5A),onPrimary=Color.White,
    background=Color(0xFFF7F8FA),surface=Color.White,onSurface=Color(0xFF17242C))
private val Dark = darkColorScheme(primary=Color(0xFF9FD2BA),background=Color(0xFF101817),
    surface=Color(0xFF1B2623),onSurface=Color(0xFFE2ECE7))
@Composable fun ScoreLedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme=if(isSystemInDarkTheme()) Dark else Light, content=content)
}
