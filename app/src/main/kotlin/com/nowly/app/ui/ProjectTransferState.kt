package com.nowly.app.ui

import com.nowly.app.data.transfer.ProjectArchive

data class ProjectImportPreview(val archive: ProjectArchive, val existingName: String?, val existingSamples: Int)
data class PreparedProjectExport(val fileName: String, val bytes: ByteArray)
