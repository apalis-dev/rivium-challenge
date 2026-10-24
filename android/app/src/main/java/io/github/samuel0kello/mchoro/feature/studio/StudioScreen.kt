package io.github.samuel0kello.mchoro.feature.studio

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.samuel0kello.mchoro.R
import io.github.samuel0kello.mchoro.appContainer
import io.github.samuel0kello.mchoro.data.model.SchemeVariant
import io.github.samuel0kello.mchoro.designsystem.component.MchoroMessage
import io.github.samuel0kello.mchoro.designsystem.component.MchoroMessageDefaults
import io.github.samuel0kello.mchoro.designsystem.component.MchoroSketch
import io.github.samuel0kello.mchoro.designsystem.component.MchoroSwatchRow
import io.github.samuel0kello.mchoro.designsystem.component.MchoroVariantGroup
import io.github.samuel0kello.mchoro.designsystem.theme.DrawingTheme
import io.github.samuel0kello.mchoro.designsystem.theme.ink
import io.github.samuel0kello.mchoro.designsystem.theme.paper
import io.github.samuel0kello.mchoro.designsystem.theme.MchoroSpacing
import io.github.samuel0kello.mchoro.designsystem.theme.MchoroTheme
import io.github.samuel0kello.mchoro.presentation.ObserveAsEvents
import io.github.samuel0kello.mchoro.presentation.ViewState
import kotlinx.coroutines.launch

@Composable
fun StudioRoute(drawingId: String, onBack: () -> Unit) {
    StudioScreen(drawingId = drawingId, onBack = onBack)
}

@Composable
internal fun StudioScreen(
    drawingId: String,
    onBack: () -> Unit,
    viewModel: StudioViewModel = studioViewModel(drawingId),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val chooserTitle = stringResource(R.string.studio_share_chooser)

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            StudioEvent.NavigateBack -> onBack()
            is StudioEvent.ShareImage -> {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", event.file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, chooserTitle))
            }
            is StudioEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message.asString(context))
        }
    }
    StudioContent(state = state, onAction = viewModel::onAction, snackbarHostState = snackbarHostState)
}

@Composable
private fun studioViewModel(drawingId: String): StudioViewModel {
    val repository = LocalContext.current.appContainer.drawingRepository
    return viewModel(key = drawingId) { StudioViewModel(drawingId, repository) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StudioContent(
    state: StudioState,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    when (val content = state.content) {
        ViewState.Loading -> StudioScaffold(onAction, snackbarHostState, modifier) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        }
        is ViewState.Error -> StudioScaffold(onAction, snackbarHostState, modifier) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MchoroMessage(
                    icon = Icons.Filled.Warning,
                    title = content.message.asString(),
                    body = "",
                    badgeShape = MchoroMessageDefaults.errorShape,
                )
            }
        }
        // The whole studio wears the drawing's own colours and glides between them.
        is ViewState.Success -> DrawingTheme(content.data.scheme) {
            StudioScaffold(
                onAction = onAction,
                snackbarHostState = snackbarHostState,
                modifier = modifier,
                showActions = true,
                isSharing = state.isSharing,
            ) {
                StudioEditor(content.data, onAction)
            }
        }
    }

    if (state.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { onAction(StudioAction.DeleteDismissed) },
            title = { Text(stringResource(R.string.studio_delete_title)) },
            text = { Text(stringResource(R.string.studio_delete_body)) },
            confirmButton = {
                TextButton(onClick = { onAction(StudioAction.DeleteConfirmed) }) {
                    Text(stringResource(R.string.studio_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(StudioAction.DeleteDismissed) }) {
                    Text(stringResource(R.string.studio_delete_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StudioScaffold(
    onAction: (StudioAction) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    showActions: Boolean = false,
    isSharing: Boolean = false,
    body: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { onAction(StudioAction.BackClicked) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.studio_back))
                    }
                },
                actions = {
                    if (showActions) {
                        IconButton(onClick = { onAction(StudioAction.DeleteClicked) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.studio_delete))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            if (showActions) {
                // Share is the one thing to do here, so it gets the primary-coloured FAB.
                MediumExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.studio_share)) },
                    icon = {
                        if (isSharing) LoadingIndicator(Modifier.padding(0.dp)) else Icon(Icons.Filled.Share, contentDescription = null)
                    },
                    onClick = { onAction(StudioAction.ShareClicked) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) { body() }
    }
}

@Composable
private fun StudioEditor(content: StudioContent, onAction: (StudioAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MchoroSpacing.l),
        verticalArrangement = Arrangement.spacedBy(MchoroSpacing.xl),
    ) {
        SketchHero(content)

        Section(title = stringResource(R.string.studio_ink)) {
            val descriptions = content.swatches.indices.map {
                stringResource(R.string.studio_swatch_description, it + 1, content.swatches.size)
            }
            MchoroSwatchRow(
                colors = content.swatches,
                selectedIndex = content.style.swatchIndex,
                onSelect = { onAction(StudioAction.SwatchSelected(it)) },
                contentDescription = { descriptions[it] },
            )
        }

        Section(title = stringResource(R.string.studio_style)) {
            val variants = SchemeVariant.entries
            MchoroVariantGroup(
                labels = variants.map { stringResource(it.label) },
                selectedIndex = variants.indexOf(content.style.variant),
                onSelect = { onAction(StudioAction.VariantSelected(variants[it])) },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = content.style.darkPaper,
                    role = Role.Switch,
                    onValueChange = { onAction(StudioAction.PaperToggled) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.studio_night_paper),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = content.style.darkPaper, onCheckedChange = null)
        }

        // Keeps the last control clear of the FAB.
        Spacer(Modifier.height(FabClearance))
    }
}

/**
 * The hero moment: the first time a drawing opens, the paper settles in on a slow spatial
 * spring while the ink "sketches itself", boldest strokes first, then the shading.
 */
@Composable
private fun SketchHero(content: StudioContent) {
    val inPreview = LocalInspectionMode.current
    var played by rememberSaveable { mutableStateOf(inPreview) }
    val reveal = remember { Animatable(if (played) 1f else 0f) }
    val paperScale = remember { Animatable(if (played) 1f else 0.9f) }
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()

    LaunchedEffect(Unit) {
        if (played) return@LaunchedEffect
        launch { paperScale.animateTo(1f, spatial) }
        // M3 publishes no choreography timings: this duration and easing are app decisions.
        reveal.animateTo(1f, tween(durationMillis = RevealMillis, easing = EaseOutCubic))
        played = true
    }

    MchoroSketch(
        mask = content.sketch,
        // Read from the animated theme, so changing swatch or variant recolours the drawing smoothly.
        ink = MaterialTheme.colorScheme.ink,
        paper = MaterialTheme.colorScheme.paper,
        contentDescription = stringResource(R.string.studio_sketch_description),
        reveal = reveal.value,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().scale(paperScale.value),
    )
}

@Composable
private fun Section(title: String, body: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MchoroSpacing.s)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        body()
    }
}

private val SchemeVariant.label: Int
    get() = when (this) {
        SchemeVariant.TonalSpot -> R.string.variant_tonal_spot
        SchemeVariant.Vibrant -> R.string.variant_vibrant
        SchemeVariant.Expressive -> R.string.variant_expressive
        SchemeVariant.Fidelity -> R.string.variant_fidelity
        SchemeVariant.Monochrome -> R.string.variant_monochrome
    }

private const val RevealMillis = 1800
private val FabClearance = 96.dp

@Preview(name = "Loading")
@Composable
private fun StudioLoadingPreview() {
    MchoroTheme(dynamicColor = false) { StudioContent(state = StudioState(), onAction = {}) }
}

@Preview(name = "Not found")
@Composable
private fun StudioErrorPreview() {
    MchoroTheme(dynamicColor = false) { StudioContent(state = StudioPreviewData.notFound, onAction = {}) }
}

@Preview(name = "Drawing, soft ink", heightDp = 900)
@Composable
private fun StudioDrawingPreview() {
    MchoroTheme(dynamicColor = false) { StudioContent(state = StudioPreviewData.drawing(), onAction = {}) }
}

@Preview(name = "Drawing, night paper", heightDp = 900)
@Composable
private fun StudioNightPaperPreview() {
    MchoroTheme(dynamicColor = false) { StudioContent(state = StudioPreviewData.drawing(darkPaper = true), onAction = {}) }
}

@Preview(name = "Sharing in progress", heightDp = 900)
@Composable
private fun StudioSharingPreview() {
    MchoroTheme(dynamicColor = false) {
        StudioContent(state = StudioPreviewData.drawing().copy(isSharing = true), onAction = {})
    }
}

@Preview(name = "Delete dialog", heightDp = 900)
@Composable
private fun StudioDeleteDialogPreview() {
    MchoroTheme(dynamicColor = false) {
        StudioContent(state = StudioPreviewData.drawing().copy(showDeleteDialog = true), onAction = {})
    }
}
