package com.nowly.app.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(val projects: List<RecordProject>) : HomeUiState
    data object ReadError : HomeUiState
}

class HomeViewModel(repository: ProjectRepository) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = repository.observeProjects()
        .map<List<RecordProject>, HomeUiState> { HomeUiState.Ready(it) }
        .catch { emit(HomeUiState.ReadError) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)
}
