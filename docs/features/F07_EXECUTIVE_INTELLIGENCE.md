# Feature — Executive Intelligence

Purpose

Give me an at-a-glance command center: what's due, how I'm tracking, and honest insights.

------------------------------------------------

User Story

As a user I can see today's focus, goals, projects and habits on one dashboard, generate an AI
"Plan My Day", read auto-generated insights, and get a weekly review.

------------------------------------------------

Architecture

Dashboard
   ↓
Tasks · Goals · Projects · Habits (Room)
   ↓
Insights (aggregation of existing data — no ML, no persistence)
   ↓
Plan My Day (AI) · Weekly Review (WorkManager, Sun 6 PM)

------------------------------------------------

Contract

Reference Kotlin: GoalDao, ProjectDao, HabitDao, GoalRepository, ProjectRepository, HabitRepository.

------------------------------------------------

Files

db/GoalEntity.kt, db/ProjectEntity.kt, db/HabitEntity.kt (+ DAOs)
data/GoalRepository.kt, data/ProjectRepository.kt, data/HabitRepository.kt
ui/screens/ExecutiveDashboardScreen.kt (Insights panel lives here)
worker/WeeklyReviewWorker.kt

------------------------------------------------

Current Status — 🟡 Built, verify on device

Dashboard (tab 7, opened from the top-bar dashboard icon) with stat cards, Insights, Plan My Day,
Goals, Projects, Habits (streaks). Weekly review worker scheduled.

Insights lines: completion %, overdue tasks, busiest open-task category, average goal progress,
habits kept up today, best streak. Each line only appears when it applies.

------------------------------------------------

Acceptance Criteria

Add a goal → progress shows. Complete tasks → completion % updates. Habit streak increments daily.
Insights reflect real numbers, never a card of zeros.

------------------------------------------------

Known Issues

Weekly review scheduling reliability to confirm on device.

------------------------------------------------

Future Improvements

True preference/routine learning from usage history (currently honest aggregation only).
