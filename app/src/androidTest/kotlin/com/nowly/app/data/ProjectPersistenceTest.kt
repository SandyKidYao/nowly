package com.nowly.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nowly.app.data.local.NowlyDatabase
import com.nowly.app.data.repository.OfflineProjectRepository
import com.nowly.app.projects.RecordProject
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "nowly-persistence-test-${UUID.randomUUID()}.db"
    private lateinit var database: NowlyDatabase

    @Before
    fun setUp() { database = openDatabase() }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun savedProjectSurvivesClosingAndReopeningTheDatabase() = runBlocking {
        val project = RecordProject(UUID.randomUUID().toString(), "心情", "现在感觉如何？", true, 1000L)
        OfflineProjectRepository(database).saveProject(project)
        database.close()
        database = openDatabase()

        val restored = OfflineProjectRepository(database).observeProjects().first()
        assertEquals(listOf(project), restored)
    }

    @Test
    fun renamingAProjectKeepsItsIdentityAndCreationTime() = runBlocking {
        val repository = OfflineProjectRepository(database)
        val project = RecordProject(UUID.randomUUID().toString(), "心情", "现在感觉如何？", true, 1000L)
        repository.saveProject(project)
        repository.saveProject(project.copy(name = "每日心情"))

        assertEquals(listOf(project.copy(name = "每日心情")), repository.observeProjects().first())
    }

    private fun openDatabase(): NowlyDatabase =
        Room.databaseBuilder(context, NowlyDatabase::class.java, databaseName).build()
}
