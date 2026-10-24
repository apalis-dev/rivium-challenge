package io.github.samuel0kello.mchoro.feature.gallery

import io.github.samuel0kello.mchoro.data.model.EngineState
import io.github.samuel0kello.mchoro.data.model.SketchingPhoto
import io.github.samuel0kello.mchoro.fake.FakeDrawingRepository
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {
    private val repository = FakeDrawingRepository()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `engine failure shows its reason`() = runTest {
        repository.engine.value = EngineState.Failed("code 78")
        val viewModel = GalleryViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }

        assertEquals(ViewState.Error(UiText.Raw("code 78")), viewModel.state.value.content)
    }

    @Test
    fun `no drawings is an empty success`() = runTest {
        val viewModel = GalleryViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }

        assertEquals(ViewState.Success(emptyList<GalleryItem>()), viewModel.state.value.content)
    }

    @Test
    fun `queued photos come first and finished ones are not shown twice`() = runTest {
        repository.drawings.value = listOf(testDrawing("owl"), testDrawing("parrot"))
        // "parrot" is done but its source file hasn't been removed yet.
        repository.sketching.value = listOf(SketchingPhoto("zebra", "/inbox/zebra.jpg"), SketchingPhoto("parrot", "/pending/parrot.jpg"))
        val viewModel = GalleryViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }

        val items = (viewModel.state.value.content as ViewState.Success).data
        assertEquals(listOf("zebra", "owl", "parrot"), items.map { it.id })
        assertEquals(GalleryItem.Sketching::class, items.first()::class)
    }

    @Test
    fun `clicks become one-time events`() = runTest {
        val viewModel = GalleryViewModel(repository)
        val events = mutableListOf<GalleryEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.onAction(GalleryAction.AddPhotosClicked)
        viewModel.onAction(GalleryAction.DrawingClicked("owl"))

        assertEquals(listOf(GalleryEvent.OpenPhotoPicker, GalleryEvent.OpenStudio("owl")), events)
    }

    @Test
    fun `retry restarts the engine`() = runTest {
        val viewModel = GalleryViewModel(repository)

        viewModel.onAction(GalleryAction.RetryClicked)

        assertEquals(1, repository.startCalls)
    }
}
