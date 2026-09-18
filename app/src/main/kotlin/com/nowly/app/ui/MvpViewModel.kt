package com.nowly.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nowly.app.AppContainer
import com.nowly.app.projects.HomeUiState
import com.nowly.app.projects.ProjectDraft
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MvpViewModel(val container: AppContainer) : ViewModel() {
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    init { viewModelScope.launch { container.reminders.issues.collect { errors.emit(it) } } }
    val projects: StateFlow<HomeUiState> = flow {
        container.reminders.reconcile()
        emitAll(container.repository.observeProjects())
    }.map< List<com.nowly.app.projects.RecordProject>, HomeUiState> { HomeUiState.Ready(it) }
        .catch { emit(HomeUiState.ReadError) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    fun refresh() { viewModelScope.launch { runCatching { container.reminders.reconcile() }.onFailure { errors.emit("提醒安排暂未更新，请重新打开应用") } } }
    fun enable(id: String, enabled: Boolean) { viewModelScope.launch {
        runCatching { container.reminders.enable(id, enabled) }.onFailure { errors.emit("未能保存提醒开关，请重试") }
    } }
    suspend fun save(draft: ProjectDraft) = container.reminders.save(draft)
    suspend fun delete(id: String) = container.reminders.delete(id)
    suspend fun answer(id: String, optionId: String, at: Long, zone: java.time.ZoneId) = container.reminders.answer(id, optionId, at, zone)
    fun history(id: String, limit: Int) = container.repository.dao.observeHistory(id, limit)
    fun sample(id: String) = container.repository.dao.observeSample(id)
    fun range(id: String, start: String, end: String) = container.repository.dao.observeRange(id, start, end)
}
