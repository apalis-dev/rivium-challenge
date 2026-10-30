package io.github.samuel0kello.mchoro.designsystem.component

import android.graphics.Matrix
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import io.github.samuel0kello.mchoro.designsystem.theme.MchoroSpacing
import io.github.samuel0kello.mchoro.designsystem.theme.MchoroTheme

/**
 * One colour picked from the photo. Unselected it is a circle; selected it morphs into a
 * 9-sided cookie and turns a little, on spatial springs (shape and rotation may bounce).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MchoroSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "swatch-morph",
    )
    val rotation by animateFloatAsState(
        targetValue = if (selected) 40f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "swatch-turn",
    )
    Box(
        modifier = modifier
            .size(SwatchTarget)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(SwatchSize)
                .scale(1f + 0.08f * progress)
                .graphicsLayer { rotationZ = rotation }
                .clip(MorphShape(morph, progress))
                .background(color),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                // The seed can be any colour, so pick whichever of black/white reads on it.
                tint = if (color.luminance() > 0.4f) Color.Black else Color.White,
            )
        }
    }
}

/** A row of swatches with radio-group semantics. */
@Composable
fun MchoroSwatchRow(
    colors: List<Color>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    contentDescription: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(MchoroSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEachIndexed { index, color ->
            MchoroSwatch(
                color = color,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                contentDescription = contentDescription(index),
            )
        }
    }
}

/** Draws a [Morph] at [progress], scaled from its unit square to the component size. */
private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress)
        path.transform(Matrix().apply { setScale(size.width, size.height) })
        return Outline.Generic(path.asComposePath())
    }
}

// From M3: 48dp minimum touch target. My decision: 40dp swatch inside it.
private val SwatchTarget = 48.dp
private val SwatchSize = 40.dp

@Preview(name = "Swatches, second selected", showBackground = true)
@Composable
private fun MchoroSwatchRowPreview() {
    MchoroTheme(dynamicColor = false) {
        MchoroSwatchRow(
            colors = listOf(Color(0xFFDE3709), Color(0xFF2B6D41), Color(0xFF444038), Color(0xFF3B4F66)),
            selectedIndex = 1,
            onSelect = {},
            contentDescription = { "Colour ${it + 1}" },
        )
    }
}

@Preview(name = "Single swatch, light seed", showBackground = true)
@Composable
private fun MchoroSwatchLightPreview() {
    MchoroTheme(dynamicColor = false) {
        MchoroSwatch(color = Color(0xFFF9AC03), selected = true, onClick = {}, contentDescription = "Sunflower yellow")
    }
}
