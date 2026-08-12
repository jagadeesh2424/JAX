package com.jax.assistant.data

import com.jax.assistant.db.GoalDao
import com.jax.assistant.db.GoalEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// Owns goal persistence and progress logic.
class GoalRepository(private val goalDao: GoalDao) {

    fun getAllGoals(): Flow<List<GoalEntity>> = goalDao.getAllGoals()

    suspend fun getOpenGoals(): List<GoalEntity> = goalDao.getOpenGoals()

    suspend fun createGoal(title: String, category: String, targetValue: Int, deadline: String?): GoalEntity {
        val goal = GoalEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            category = if (category.isBlank()) "Personal" else category,
            targetValue = if (targetValue <= 0) 100 else targetValue,
            currentValue = 0,
            deadline = if (deadline.isNullOrBlank()) null else deadline,
            isCompleted = false
        )
        goalDao.insertGoal(goal)
        return goal
    }

    suspend fun updateGoal(goal: GoalEntity) = goalDao.updateGoal(goal)

    suspend fun incrementProgress(goal: GoalEntity, delta: Int) {
        val next = (goal.currentValue + delta).coerceIn(0, goal.targetValue)
        goalDao.updateGoal(goal.copy(currentValue = next, isCompleted = next >= goal.targetValue))
    }

    suspend fun deleteGoal(goal: GoalEntity) = goalDao.deleteGoal(goal)
}
