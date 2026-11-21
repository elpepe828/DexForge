package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
  primary = CyanElectric,
  onPrimary = ForgeObsidian,
  primaryContainer = CyanMuted,
  onPrimaryContainer = CyanOnContainer,
  secondary = AmberForge,
  onSecondary = ForgeObsidian,
  secondaryContainer = AmberContainer,
  onSecondaryContainer = AmberOnContainer,
  tertiary = EmeraldVerified,
  onTertiary = ForgeObsidian,
  tertiaryContainer = EmeraldContainer,
  onTertiaryContainer = EmeraldOnContainer,
  background = ForgeObsidian,
  onBackground = TextPrimaryDark,
  surface = ForgeSlateDark,
  onSurface = TextPrimaryDark,
  surfaceVariant = ForgeSurfaceElevated,
  onSurfaceVariant = TextSecondaryDark,
  surfaceContainerLow = ForgeSlateDark,
  surfaceContainer = ForgeSurfaceElevated,
  surfaceContainerHigh = ForgeSurfaceHighest,
  outline = ForgeOutline,
  outlineVariant = ForgeOutline,
  error = RoseError,
  errorContainer = RoseContainer,
  onErrorContainer = TextPrimaryDark,
)

private val LightColorScheme = lightColorScheme(
  primary = CyanPrimaryLight,
  onPrimary = LightSurface,
  primaryContainer = CyanOnContainer,
  onPrimaryContainer = CyanMuted,
  secondary = AmberSecondaryLight,
  onSecondary = LightSurface,
  secondaryContainer = AmberOnContainer,
  onSecondaryContainer = AmberContainer,
  tertiary = EmeraldTertiaryLight,
  onTertiary = LightSurface,
  tertiaryContainer = EmeraldOnContainer,
  onTertiaryContainer = EmeraldContainer,
  background = LightBg,
  onBackground = TextPrimaryLight,
  surface = LightSurface,
  onSurface = TextPrimaryLight,
  surfaceVariant = LightSurfaceVariant,
  onSurfaceVariant = TextSecondaryLight,
  outline = LightOutline,
  outlineVariant = LightOutline,
)

val DexForgeShapes = Shapes(
  small = RoundedCornerShape(8.dp),
  medium = RoundedCornerShape(12.dp),
  large = RoundedCornerShape(16.dp),
  extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun DexForgeTheme(
  darkTheme: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = DexForgeTypography,
    shapes = DexForgeShapes,
    content = content,
  )
}
