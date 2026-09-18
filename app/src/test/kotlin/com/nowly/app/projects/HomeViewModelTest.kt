package com.nowly.app.projects

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun databaseEmissionsUpdateTheObservedScreen() = runTest(dispatcher) {
        val projects = MutableStateFlow<List<RecordProject>>(emptyList())
        val repository = FakeRepository(projects)
        val viewModel = HomeViewModel(repository)
        assertEquals(HomeUiState.Loading, viewModel.uiState.value)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(HomeUiState.Ready(emptyList()), viewModel.uiState.value)

        val project = RecordProject("p1", "心情", "此刻心情如何？", true, 1L)
        projects.value = listOf(project)
        advanceUntilIdle()
        assertEquals(HomeUiState.Ready(listOf(project)), viewModel.uiState.value)
    }

    @Test
    fun readFailureIsNotPresentedAsAnEmptyDatabase() = runTest(dispatcher) {
        val viewModel = HomeViewModel(FakeRepository(flow { throw IllegalStateException("storage unavailable") }))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(HomeUiState.ReadError, viewModel.uiState.value)
    }

    private class FakeRepository(private val projects: Flow<List<RecordProject>>) : ProjectRepository {
        override fun observeProjects() = projects
        override suspend fun saveProject(project: RecordProject): Unit = error("Not used by the read-only screen")
    }
}
