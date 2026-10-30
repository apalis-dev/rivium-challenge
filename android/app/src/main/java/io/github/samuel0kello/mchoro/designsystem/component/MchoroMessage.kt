package io.github.samuel0kello.mchoro.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.samuel0kello.mchoro.designsystem.theme.MchoroSpacing
import io.github.samuel0kello.mchoro.designsystem.theme.MchoroTheme

/**
 * A centred message for empty and error states: an icon in an abstract shape
 * (decorative, so an M3 library shape is allowed), a headline, body text and an optional action.
 */
@Composable
fun MchoroMessage(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    badgeShape: Shape = MchoroMessageDefaults.badgeShape,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = modifier.padding(MchoroSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MchoroSpacing.l),
    ) {
        Box(
            Modifier
                .size(112.dp)
                .clip(badgeShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(48.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object MchoroMessageDefaults {
    val badgeShape: Shape @Composable get() = MaterialShapes.Flower.toShape()
    val errorShape: Shape @Composable get() = MaterialShapes.Burst.toShape()
}

@Preview(name = "Empty", showBackground = true)
@Composable
private fun MchoroMessageEmptyPreview() {
    MchoroTheme(dynamicColor = false) {
        MchoroMessage(
            icon = Icons.Filled.Create,
            title = "Nothing drawn yet",
            body = "Pick a photo, or share one from any app, and Mchoro will sketch it.",
        )
    }
}

@Preview(name = "Error with retry", showBackground = true)
@Composable
private fun MchoroMessageErrorPreview() {
    MchoroTheme(dynamicColor = false) {
        MchoroMessage(
            icon = Icons.Filled.Warning,
            title = "The sketch worker stopped",
            body = "code 78: invalid configuration",
            badgeShape = MchoroMessageDefaults.errorShape,
            actionLabel = "Try again",
        )
    }
}
