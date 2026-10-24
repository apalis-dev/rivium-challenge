package io.github.samuel0kello.mchoro.feature.gallery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.samuel0kello.mchoro.R
import io.github.samuel0kello.mchoro.appContainer
import io.github.samuel0kello.mchoro.designsystem.component.MchoroMessage
import io.github.samuel0kello.mchoro.designsystem.component.MchoroMessageDefaults
import io.github.samuel0kello.mchoro.designsystem.component.MchoroSketch
import io.github.samuel0kello.mchoro.designsystem.theme.MchoroSpacing
import io.github.samuel0kello.mchoro.designsystem.theme.MchoroTheme
import io.github.samuel0kello.mchoro.presentation.ObserveAsEvents
import io.github.samuel0kello.mchoro.presentation.ViewState

/** Navigation entry: only knows how to leave this screen. */
@Composable
fun GalleryRoute(onOpenStudio: (String) -> Unit) {
    GalleryScreen(onOpenStudio = onOpenStudio)
}

@Composable
internal fun GalleryScreen(
    onOpenStudio: (String) -> Unit,
    viewModel: GalleryViewModel = galleryViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The system Photo Picker: no storage permission needed.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) viewModel.onAction(GalleryAction.PhotosPicked(uris))
    }
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            GalleryEvent.OpenPhotoPicker ->
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            is GalleryEvent.OpenStudio -> onOpenStudio(event.id)
        }
    }
    GalleryContent(state = state, onAction = viewModel::onAction)
}

@Composable
private fun galleryViewModel(): GalleryViewModel {
    val repository = LocalContext.current.appContainer.drawingRepository
    return viewModel { GalleryViewModel(repository) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GalleryContent(
    state: GalleryState,
    onAction: (GalleryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    // The FAB shows its label at the top of the list and shrinks to an icon while scrolling.
    val fabExpanded by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 } }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.gallery_title)) },
                subtitle = { Text(stringResource(R.string.gallery_subtitle)) },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (state.content !is ViewState.Error) {
                MediumExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.gallery_add_photos)) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { onAction(GalleryAction.AddPhotosClicked) },
                    expanded = fabExpanded,
                )
            }
        },
    ) { padding ->
        when (val content = state.content) {
            ViewState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                val loading = stringResource(R.string.gallery_loading)
                LoadingIndicator(Modifier.semantics { contentDescription = loading })
            }
            is ViewState.Error -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                MchoroMessage(
                    icon = Icons.Filled.Warning,
                    title = stringResource(R.string.gallery_error_title),
                    body = content.message.asString(),
                    badgeShape = MchoroMessageDefaults.errorShape,
                    actionLabel = stringResource(R.string.gallery_retry),
                    onAction = { onAction(GalleryAction.RetryClicked) },
                )
            }
            is ViewState.Success -> if (content.data.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    MchoroMessage(
                        icon = Icons.Filled.Create,
                        title = stringResource(R.string.gallery_empty_title),
                        body = stringResource(R.string.gallery_empty_body),
                    )
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = MchoroSpacing.l,
                        end = MchoroSpacing.l,
                        top = padding.calculateTopPadding(),
                        // Room for the FAB over the last row.
                        bottom = padding.calculateBottomPadding() + FabClearance,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(MchoroSpacing.m),
                    verticalItemSpacing = MchoroSpacing.m,
                ) {
                    items(content.data, key = { it.id }) { item ->
                        GalleryTile(
                            item = item,
                            onClick = { onAction(GalleryAction.DrawingClicked(item.id)) },
                            // New tiles slide into place on spatial springs (the grid's default spec).
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

/** When Rust finishes, the same tile turns from the waiting photo into the drawing. */
@Composable
private fun GalleryTile(item: GalleryItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = item,
        contentKey = { it is GalleryItem.Ready },
        transitionSpec = {
            (fadeIn(motion.slowEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.9f))
                .togetherWith(fadeOut(motion.fastEffectsSpec()))
        },
        modifier = modifier,
        label = "tile",
    ) { tile ->
        when (tile) {
            is GalleryItem.Sketching -> SketchingTile(tile)
            is GalleryItem.Ready -> MchoroSketch(
                mask = tile.sketch,
                ink = tile.ink,
                paper = tile.paper,
                contentDescription = stringResource(R.string.gallery_drawing_description, tile.id),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SketchingTile(tile: GalleryItem.Sketching) {
    val aspect = tile.photo?.let { it.width.toFloat() / it.height } ?: (3f / 4f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large),
        contentAlignment = Alignment.Center,
    ) {
        tile.photo?.let { photo ->
            Image(
                bitmap = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.35f,
                modifier = Modifier.matchParentSize().clip(MaterialTheme.shapes.large),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MchoroSpacing.s),
        ) {
            ContainedLoadingIndicator()
            Text(
                text = stringResource(R.string.gallery_sketching),
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// My decision: clearance below the grid so the medium extended FAB never covers the last tiles.
private val FabClearance = 96.dp

@Preview(name = "Loading")
@Composable
private fun GalleryLoadingPreview() {
    MchoroTheme(dynamicColor = false) {
        GalleryContent(state = GalleryState(ViewState.Loading), onAction = {})
    }
}

@Preview(name = "Empty")
@Composable
private fun GalleryEmptyPreview() {
    MchoroTheme(dynamicColor = false) {
        GalleryContent(state = GalleryPreviewData.empty, onAction = {})
    }
}

@Preview(name = "Drawings and one still sketching")
@Composable
private fun GalleryItemsPreview() {
    MchoroTheme(dynamicColor = false) {
        GalleryContent(state = GalleryPreviewData.withItems(), onAction = {})
    }
}

@Preview(name = "Drawings, dark")
@Composable
private fun GalleryItemsDarkPreview() {
    MchoroTheme(darkTheme = true, dynamicColor = false) {
        GalleryContent(state = GalleryPreviewData.withItems(), onAction = {})
    }
}

@Preview(name = "Engine failed")
@Composable
private fun GalleryErrorPreview() {
    MchoroTheme(dynamicColor = false) {
        GalleryContent(state = GalleryPreviewData.error, onAction = {})
    }
}
