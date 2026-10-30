package io.github.samuel0kello.mchoro.feature.studio

import io.github.samuel0kello.mchoro.R
import io.github.samuel0kello.mchoro.data.model.SchemeVariant
import io.github.samuel0kello.mchoro.fake.FakeDrawingRepository
import io.github.samuel0kello.mchoro.fake.primaryFor
import io.github.samuel0kello.mchoro.fake.testDrawing
import io.github.samuel0kello.mchoro.presentation.UiText
import io.github.samuel0kello.mchoro.presentation.ViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StudioViewModelTest {
    private val repository = FakeDrawingRepository().apply { drawings.value = listOf(testDrawing("owl")) }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun StudioViewModel.content() = (state.value.content as ViewState.Success).data

    @Test
    fun `opens with the first swatch, soft variant, light paper`() = runTest {
        val viewModel = StudioViewModel("owl", repository)

        assertEquals(primaryFor(0, SchemeVariant.TonalSpot, dark = false), viewModel.content().scheme.primary)
    }

    @Test
    fun `swatch, variant and paper choices recolour the scheme and are saved`() = runTest {
        val viewModel = StudioViewModel("owl", repository)

        viewModel.onAction(StudioAction.SwatchSelected(1))
        viewModel.onAction(StudioAction.VariantSelected(SchemeVariant.Vibrant))
        viewModel.onAction(StudioAction.PaperToggled)

        assertEquals(primaryFor(1, SchemeVariant.Vibrant, dark = true), viewModel.content().scheme.primary)
        assertEquals(repository.styles.value["owl"], viewModel.content().style)
    }

    @Test
    fun `missing drawing is an error`() = runTest {
        val viewModel = StudioViewModel("ghost", repository)

        assertEquals(ViewState.Error(UiText.Resource(R.string.studio_not_found)), viewModel.state.value.content)
    }

    @Test
    fun `confirming delete removes the drawing and goes back`() = runTest {
        val viewModel = StudioViewModel("owl", repository)
        val events = mutableListOf<StudioEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.onAction(StudioAction.DeleteClicked)
        assertTrue(viewModel.state.value.showDeleteDialog)
        viewModel.onAction(StudioAction.DeleteConfirmed)

        assertEquals(listOf("owl"), repository.deleted)
        assertEquals(false, viewModel.state.value.showDeleteDialog)
        assertEquals(listOf<StudioEvent>(StudioEvent.NavigateBack), events)
    }

    @Test
    fun `share sends the exported file`() = runTest {
        val file = File("shared/owl.png")
        repository.export = { file }
        val viewModel = StudioViewModel("owl", repository)
        val events = mutableListOf<StudioEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.onAction(StudioAction.ShareClicked)

        assertEquals(listOf<StudioEvent>(StudioEvent.ShareImage(file)), events)
        assertEquals(false, viewModel.state.value.isSharing)
    }

    @Test
    fun `failed export shows a message`() = runTest {
        repository.export = { error("disk full") }
        val viewModel = StudioViewModel("owl", repository)
        val events = mutableListOf<StudioEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.onAction(StudioAction.ShareClicked)

        assertEquals(listOf<StudioEvent>(StudioEvent.ShowMessage(UiText.Resource(R.string.studio_share_failed))), events)
    }
}
