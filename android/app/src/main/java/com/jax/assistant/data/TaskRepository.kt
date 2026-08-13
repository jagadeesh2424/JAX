package com.jax.assistant.data

import com.jax.assistant.db.TaskDao
import com.jax.assistant.db.TaskEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// Owns task persistence and creation logic.
class TaskRepository(private val taskDao: TaskDao) {

    fun getAllTasks(): Flow<List<TaskEntity>> = taskDao.getAllTasks()

    suspend fun getAllTasksSnapshot(): List<TaskEntity> = taskDao.getAllTasksList()

    suspend fun getOpenHighPriorityTasks(): List<TaskEntity> = taskDao.getOpenHighPriorityTasks()

    suspend fun insertTask(task: TaskEntity) = taskDao.insertTask(task)

    suspend fun updateTask(task: TaskEntity) = taskDao.updateTask(task)

    suspend fun deleteTask(task: TaskEntity) = taskDao.deleteTask(task)

    suspend fun createManualTask(title: String, category: String, priority: String, deadline: String?): TaskEntity {
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            category = if (category.isBlank()) "General" else category,
            priority = if (priority.isBlank()) "MED" else priority,
            deadline = if (deadline.isNullOrBlank()) null else deadline,
            isCompleted = false
        )
        taskDao.insertTask(task)
        return task
    }
}
