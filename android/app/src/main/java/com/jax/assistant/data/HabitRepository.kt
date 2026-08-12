package com.jax.assistant.data

import com.jax.assistant.db.HabitDao
import com.jax.assistant.db.HabitEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.util.UUID

// Owns habit persistence and streak logic.
class HabitRepository(private val habitDao: HabitDao) {

    fun getAllHabits(): Flow<List<HabitEntity>> = habitDao.getAllHabits()

    suspend fun createHabit(name: String, category: String): HabitEntity {
        val habit = HabitEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            category = if (category.isBlank()) "Personal" else category
        )
        habitDao.insertHabit(habit)
        return habit
    }

    // Toggles today's completion; recomputes streak based on the previous completion date.
    suspend fun toggleToday(habit: HabitEntity) {
        val today = LocalDate.now()
        val todayStr = today.toString()
        if (habit.lastCompletedDate == todayStr) {
            // Undo today's completion.
            habitDao.updateHabit(
                habit.copy(
                    lastCompletedDate = null,
                    streak = (habit.streak - 1).coerceAtLeast(0)
                )
            )
            return
        }
        val continued = habit.lastCompletedDate == today.minusDays(1).toString()
        habitDao.updateHabit(
            habit.copy(
                lastCompletedDate = todayStr,
                streak = if (continued) habit.streak + 1 else 1
            )
        )
    }

    suspend fun deleteHabit(habit: HabitEntity) = habitDao.deleteHabit(habit)
}
