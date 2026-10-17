package com.example.scoreledger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary=Color(0xFF356A5A),onPrimary=Color.White,primaryContainer=Color(0xFFE3EFE8),onPrimaryContainer=Color(0xFF173D2F),
    secondary=Color(0xFF56635B),onSecondary=Color.White,secondaryContainer=Color(0xFFEEF2EE),onSecondaryContainer=Color(0xFF26352C),
    tertiary=Color(0xFF7A5E22),onTertiary=Color.White,tertiaryContainer=Color(0xFFF5E9C9),onTertiaryContainer=Color(0xFF49350A),
    background=Color(0xFFF7F8F4),onBackground=Color(0xFF202823),surface=Color.White,onSurface=Color(0xFF202823),
    surfaceVariant=Color(0xFFEEF2EE),onSurfaceVariant=Color(0xFF56635B),outline=Color(0xFF748178),outlineVariant=Color(0xFFCCD5CE),
    error=Color(0xFFB3261E),onError=Color.White,errorContainer=Color(0xFFF9DEDC),onErrorContainer=Color(0xFF410E0B),
    inverseSurface=Color(0xFF29332D),inverseOnSurface=Color(0xFFE6EEE8),inversePrimary=Color(0xFF9ECDB6),
    surfaceDim=Color(0xFFD9DFD9),surfaceBright=Color.White,surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFF3F6F1),
    surfaceContainer=Color(0xFFEEF2EE),surfaceContainerHigh=Color(0xFFE8EDE8),surfaceContainerHighest=Color(0xFFE2E8E2))
private val Dark = darkColorScheme(
    primary=Color(0xFF9ECDB6),onPrimary=Color(0xFF12382B),primaryContainer=Color(0xFF294E3F),onPrimaryContainer=Color(0xFFC0EAD3),
    secondary=Color(0xFFAFBDB3),onSecondary=Color(0xFF23342A),secondaryContainer=Color(0xFF34483B),onSecondaryContainer=Color(0xFFD5E6D9),
    tertiary=Color(0xFFE1C681),onTertiary=Color(0xFF3F2E00),tertiaryContainer=Color(0xFF594619),onTertiaryContainer=Color(0xFFF7E2AD),
    background=Color(0xFF111915),onBackground=Color(0xFFE6EEE8),surface=Color(0xFF1B2520),onSurface=Color(0xFFE6EEE8),
    surfaceVariant=Color(0xFF29382E),onSurfaceVariant=Color(0xFFAFBDB3),outline=Color(0xFF87988C),outlineVariant=Color(0xFF44564A),
    error=Color(0xFFFFB4AB),onError=Color(0xFF690005),errorContainer=Color(0xFF93000A),onErrorContainer=Color(0xFFFFDAD6),
    inverseSurface=Color(0xFFE6EEE8),inverseOnSurface=Color(0xFF26342B),inversePrimary=Color(0xFF356A5A),
    surfaceDim=Color(0xFF111915),surfaceBright=Color(0xFF35423A),surfaceContainerLowest=Color(0xFF0C140F),surfaceContainerLow=Color(0xFF17211B),
    surfaceContainer=Color(0xFF1B2520),surfaceContainerHigh=Color(0xFF253128),surfaceContainerHighest=Color(0xFF303D33))
@Composable fun ScoreLedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme=if(isSystemInDarkTheme()) Dark else Light,
        shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(20.dp)),content=content)
}
