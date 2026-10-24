package io.github.samuel0kello.mchoro.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Which roles of a drawing's scheme paint it. One definition, used on screen, in the gallery
 * and in the shared PNG, so all three match.
 * My decision: ink = primary (the most important accent); paper = surface container low,
 * a tinted paper that still stands apart from the `surface` page behind it.
 */
val ColorScheme.ink: Color get() = primary
val ColorScheme.paper: Color get() = surfaceContainerLow
