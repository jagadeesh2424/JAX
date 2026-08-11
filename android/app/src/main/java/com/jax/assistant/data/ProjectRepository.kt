package com.jax.assistant.data

import com.jax.assistant.db.ProjectDao
import com.jax.assistant.db.ProjectEntity
import com.jax.assistant.db.ProjectStatus
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// Owns project persistence and status transitions.
class ProjectRepository(private val projectDao: ProjectDao) {

    fun getAllProjects(): Flow<List<ProjectEntity>> = projectDao.getAllProjects()

    suspend fun createProject(name: String, description: String): ProjectEntity {
        val project = ProjectEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            status = ProjectStatus.ACTIVE
        )
        projectDao.insertProject(project)
        return project
    }

    suspend fun updateProject(project: ProjectEntity) =
        projectDao.updateProject(project.copy(updatedAt = System.currentTimeMillis()))

    // Cycles ACTIVE -> ON_HOLD -> DONE -> ACTIVE.
    suspend fun cycleStatus(project: ProjectEntity) {
        val next = when (project.status) {
            ProjectStatus.ACTIVE -> ProjectStatus.ON_HOLD
            ProjectStatus.ON_HOLD -> ProjectStatus.DONE
            else -> ProjectStatus.ACTIVE
        }
        projectDao.updateProject(project.copy(status = next, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteProject(project: ProjectEntity) = projectDao.deleteProject(project)
}
