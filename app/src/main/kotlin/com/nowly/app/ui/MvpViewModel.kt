package com.nowly.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nowly.app.AppContainer
import com.nowly.app.projects.HomeUiState
import com.nowly.app.projects.ProjectDraft
import com.nowly.app.data.transfer.ProjectArchiveCodec
import com.nowly.app.data.transfer.ProjectArchiveException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MvpViewModel(val container: AppContainer) : ViewModel() {
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val _transferBusy = MutableStateFlow(false)
    val transferBusy = _transferBusy.asStateFlow()
    private val _importPreview = MutableStateFlow<ProjectImportPreview?>(null)
    val importPreview = _importPreview.asStateFlow()
    private val _projectExport = MutableStateFlow<PreparedProjectExport?>(null)
    val projectExport = _projectExport.asStateFlow()
    private var exportPickerOpened = false

    private fun transfer(failure: String, block: suspend () -> Unit) {
        if (_transferBusy.value) return
        _transferBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { errors.emit((error as? ProjectArchiveException)?.message ?: failure) }
            finally { _transferBusy.value = false }
        }
    }

    fun readProject(open: () -> InputStream?) = transfer("读取失败，请重新选择项目文件") {
        val archive = checkNotNull(open()).use(ProjectArchiveCodec::read)
        val existing = container.repository.dao.project(archive.project.id)
        val count = if (existing == null) 0 else container.repository.dao.allSamples(existing.id).size
        _importPreview.value = ProjectImportPreview(archive, existing?.name, count)
    }

    fun dismissImport() { if (!_transferBusy.value) _importPreview.value = null }

    fun confirmImport() {
        val preview = _importPreview.value ?: return
        transfer("导入失败，原有数据已保留，请重试") {
            container.reminders.importProject(preview.archive, replace = preview.existingName != null)
            _importPreview.value = null
            val permissions = runCatching {
                when {
                    !container.reminderGateway.canNotify() -> "；系统通知未开启，请在项目设置中开启"
                    !container.reminderGateway.canSchedulePrecisely() -> "；准时提醒未授权，提醒可能延迟"
                    else -> ""
                }
            }.getOrDefault("；请在项目设置中检查系统提醒权限")
            errors.emit("项目已导入$permissions")
        }
    }

    fun prepareProjectExport(id: String) {
        if (_projectExport.value != null) return
        transfer("无法导出项目，请重试") {
            val archive = container.reminders.exportProject(id)
            exportPickerOpened = false
            _projectExport.value = PreparedProjectExport(ProjectArchiveCodec.fileName(archive.project.name), ProjectArchiveCodec.encode(archive))
        }
    }

    fun claimExportPicker(): Boolean {
        if (exportPickerOpened) return false
        exportPickerOpened = true
        return true
    }

    fun cancelProjectExport() { _projectExport.value = null }

    fun writeProject(open: () -> OutputStream?) {
        val prepared = _projectExport.value ?: run {
            errors.tryEmit("导出已中断，请重新导出项目")
            return
        }
        transfer("保存失败，请重新导出项目") {
            try {
                checkNotNull(open()).use { it.write(prepared.bytes) }
                errors.emit("完整项目已保存，包含全部历史记录")
            } finally { _projectExport.value = null }
        }
    }
    init { viewModelScope.launch { container.reminders.issues.collect { errors.emit(it) } } }
    val projects: StateFlow<HomeUiState> = flow {
        container.reminders.reconcile()
        emitAll(container.repository.observeProjects())
    }.map< List<com.nowly.app.projects.RecordProject>, HomeUiState> { HomeUiState.Ready(it) }
        .catch { emit(HomeUiState.ReadError) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    fun refresh() { viewModelScope.launch { runCatching { container.reminders.reconcile() }.onFailure { errors.emit("提醒安排暂未更新，请重新打开应用") } } }
    suspend fun save(draft: ProjectDraft) = container.reminders.save(draft)
    suspend fun delete(id: String) = container.reminders.delete(id)
    suspend fun answer(id: String, optionId: String, at: Long, zone: java.time.ZoneId) = container.reminders.answer(id, optionId, at, zone)
    fun sample(id: String) = container.repository.dao.observeSample(id)
    fun latestNumeric(id: String, excluded: String = "") = container.repository.dao.latestNumeric(id, excluded)
    suspend fun saveNumeric(projectId: String, value: String, measuredAt: Long, zone: java.time.ZoneId,
        entryId: String, pendingId: String? = null, editing: com.nowly.app.data.local.SampleEntity? = null) =
        container.reminders.saveNumeric(projectId, value, measuredAt, zone, entryId, pendingId, editing)
    suspend fun deleteNumeric(expected: com.nowly.app.data.local.SampleEntity) = container.reminders.deleteNumeric(expected)
    fun range(id: String, start: String, end: String) = container.repository.dao.observeRange(id, start, end)
}
