package com.nowly.app.projects

import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    fun observeProjects(): Flow<List<RecordProject>>
    suspend fun saveProject(project: RecordProject)
}
