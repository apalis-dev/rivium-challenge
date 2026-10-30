package io.github.samuel0kello.mchoro.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.samuel0kello.mchoro.designsystem.theme.MchoroTheme

/**
 * Single-select connected button group (M3 Expressive's replacement for segmented buttons).
 * It spans the width it is given; the selected button morphs from round to square.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MchoroVariantGroup(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            ToggleButton(
                checked = selected,
                onCheckedChange = { onSelect(index) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    labels.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                // My decision: tighter than the default padding so five labels fit a phone width.
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(
                    text = label,
                    // Emphasized type marks the selection (M3 Expressive typography guidance).
                    style = if (selected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview(name = "Variants, Vivid selected", showBackground = true, widthDp = 360)
@Composable
private fun MchoroVariantGroupPreview() {
    MchoroTheme(dynamicColor = false) {
        MchoroVariantGroup(
            labels = listOf("Soft", "Vivid", "Playful", "True", "Pencil"),
            selectedIndex = 1,
            onSelect = {},
        )
    }
}

@Preview(name = "Variants, dark", showBackground = true, widthDp = 360, backgroundColor = 0xFF141218)
@Composable
private fun MchoroVariantGroupDarkPreview() {
    MchoroTheme(darkTheme = true, dynamicColor = false) {
        MchoroVariantGroup(
            labels = listOf("Soft", "Vivid", "Playful", "True", "Pencil"),
            selectedIndex = 4,
            onSelect = {},
        )
    }
}
