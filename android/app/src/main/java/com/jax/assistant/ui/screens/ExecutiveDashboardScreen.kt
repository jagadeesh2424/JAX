package com.jax.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jax.assistant.db.GoalEntity
import com.jax.assistant.db.HabitEntity
import com.jax.assistant.db.ProjectEntity
import com.jax.assistant.db.ProjectStatus
import com.jax.assistant.db.TaskEntity
import com.jax.assistant.ui.theme.CyanAccent
import com.jax.assistant.ui.theme.GoldAccent
import com.jax.assistant.ui.theme.JAXAssistantTheme
import com.jax.assistant.ui.theme.PureDark
import com.jax.assistant.ui.theme.SurfaceDark
import java.time.LocalDate

@Composable
fun ExecutiveDashboardScreen(
    tasks: List<TaskEntity>,
    goals: List<GoalEntity>,
    projects: List<ProjectEntity>,
    habits: List<HabitEntity> = emptyList(),
    dailyPlan: String,
    isPlanning: Boolean,
    onGeneratePlan: () -> Unit,
    onToggleTask: (TaskEntity) -> Unit,
    onAddGoal: (title: String, category: String, target: Int, deadline: String?) -> Unit,
    onIncrementGoal: (GoalEntity, Int) -> Unit,
    onToggleGoalComplete: (GoalEntity) -> Unit,
    onDeleteGoal: (GoalEntity) -> Unit,
    onAddProject: (name: String, description: String) -> Unit,
    onCycleProjectStatus: (ProjectEntity) -> Unit,
    onDeleteProject: (ProjectEntity) -> Unit,
    onAddHabit: (name: String, category: String) -> Unit = { _, _ -> },
    onToggleHabit: (HabitEntity) -> Unit = {},
    onDeleteHabit: (HabitEntity) -> Unit = {}
) {
    val today = remember { LocalDate.now().toString() }
    val openTasks = tasks.filter { !it.isCompleted }
    val highPriority = openTasks.count { it.priority.equals("HIGH", ignoreCase = true) }
    val todaysTasks = openTasks
        .filter { it.deadline?.trim()?.startsWith(today) == true }
        .sortedByDescending { priorityWeight(it.priority) }
    val goalsInProgress = goals.count { !it.isCompleted }
    val activeProjects = projects.count { it.status == ProjectStatus.ACTIVE }
    val insights = remember(tasks, goals, habits, today) {
        buildDashboardInsights(tasks, goals, habits, today)
    }

    var showGoalDialog by remember { mutableStateOf(false) }
    var showProjectDialog by remember { mutableStateOf(false) }
    var showHabitDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PureDark)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text(
                text = "Executive Dashboard",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(text = today, color = Color.Gray, fontSize = 12.sp)
        }

        // Stat cards
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Open", openTasks.size.toString(), CyanAccent, Modifier.weight(1f))
                StatCard("High", highPriority.toString(), Color(0xFFFF5A5A), Modifier.weight(1f))
                StatCard("Goals", goalsInProgress.toString(), GoldAccent, Modifier.weight(1f))
                StatCard("Projects", activeProjects.toString(), Color(0xFF00FF66), Modifier.weight(1f))
            }
        }

        // AI Plan-my-day
        item { PlanCard(dailyPlan = dailyPlan, isPlanning = isPlanning, onGeneratePlan = onGeneratePlan) }

        // Insights derived from current tasks/goals/habits
        if (insights.isNotEmpty()) {
            item { InsightsCard(insights) }
        }

        // Today's focus
        item { SectionHeader("Today's Focus") }
        if (todaysTasks.isEmpty()) {
            item { EmptyHint("Nothing due today. Enjoy the clear runway.") }
        } else {
            items(todaysTasks, key = { it.id }) { task ->
                FocusTaskRow(task = task, onToggleTask = onToggleTask)
            }
        }

        // Goals
        item {
            SectionHeaderWithAction("Goals") { showGoalDialog = true }
        }
        if (goals.isEmpty()) {
            item { EmptyHint("No goals yet. Add one to start tracking progress.") }
        } else {
            items(goals, key = { it.id }) { goal ->
                GoalRow(
                    goal = goal,
                    onIncrement = onIncrementGoal,
                    onToggleComplete = onToggleGoalComplete,
                    onDelete = onDeleteGoal
                )
            }
        }

        // Projects
        item {
            SectionHeaderWithAction("Projects") { showProjectDialog = true }
        }
        if (projects.isEmpty()) {
            item { EmptyHint("No active projects. Add one to track its status.") }
        } else {
            items(projects, key = { it.id }) { project ->
                ProjectRow(
                    project = project,
                    onCycleStatus = onCycleProjectStatus,
                    onDelete = onDeleteProject
                )
            }
        }

        // Habits
        item {
            SectionHeaderWithAction("Habits") { showHabitDialog = true }
        }
        if (habits.isEmpty()) {
            item { EmptyHint("No habits yet. Build a streak by adding one.") }
        } else {
            items(habits, key = { it.id }) { habit ->
                HabitRow(
                    habit = habit,
                    today = today,
                    onToggle = onToggleHabit,
                    onDelete = onDeleteHabit
                )
            }
        }
    }

    if (showGoalDialog) {
        AddGoalDialog(
            onDismiss = { showGoalDialog = false },
            onConfirm = { title, category, target, deadline ->
                onAddGoal(title, category, target, deadline)
                showGoalDialog = false
            }
        )
    }

    if (showProjectDialog) {
        AddProjectDialog(
            onDismiss = { showProjectDialog = false },
            onConfirm = { name, description ->
                onAddProject(name, description)
                showProjectDialog = false
            }
        )
    }

    if (showHabitDialog) {
        AddHabitDialog(
            onDismiss = { showHabitDialog = false },
            onConfirm = { name, category ->
                onAddHabit(name, category)
                showHabitDialog = false
            }
        )
    }
}

private fun priorityWeight(priority: String): Int = when (priority.uppercase()) {
    "HIGH" -> 3
    "MED", "MEDIUM" -> 2
    else -> 1
}

// Honest aggregation of existing data (no ML/no persistence): each line is only added when it applies.
private fun buildDashboardInsights(
    tasks: List<TaskEntity>,
    goals: List<GoalEntity>,
    habits: List<HabitEntity>,
    today: String
): List<String> {
    val insights = mutableListOf<String>()

    if (tasks.isNotEmpty()) {
        val done = tasks.count { it.isCompleted }
        val rate = (done * 100) / tasks.size
        insights += "You've completed $rate% of your tasks ($done/${tasks.size})."
    }

    val openTasks = tasks.filter { !it.isCompleted }

    val overdue = openTasks.count { t ->
        val d = t.deadline?.trim().orEmpty()
        d.length >= 10 && d.substring(0, 10) < today
    }
    if (overdue > 0) {
        insights += "$overdue task${if (overdue > 1) "s" else ""} overdue \u2014 worth revisiting."
    }

    if (openTasks.isNotEmpty()) {
        val busiest = openTasks.groupingBy { it.category.ifBlank { "Uncategorized" } }
            .eachCount().maxByOrNull { it.value }
        if (busiest != null) {
            insights += "Most open work is in ${busiest.key} (${busiest.value})."
        }
    }

    val inProgressGoals = goals.filter { !it.isCompleted }
    if (inProgressGoals.isNotEmpty()) {
        val avg = inProgressGoals.map { g ->
            if (g.targetValue > 0) (g.currentValue.coerceAtMost(g.targetValue) * 100) / g.targetValue else 0
        }.average().toInt()
        insights += "Your goals are $avg% complete on average."
    }

    if (habits.isNotEmpty()) {
        val doneToday = habits.count { it.lastCompletedDate == today }
        insights += "You've kept up $doneToday of ${habits.size} habit${if (habits.size > 1) "s" else ""} today."
        val best = habits.maxByOrNull { it.streak }
        if (best != null && best.streak > 0) {
            insights += "Best streak: ${best.streak} day${if (best.streak > 1) "s" else ""} (${best.name})."
        }
    }

    return insights
}

@Composable
private fun InsightsCard(insights: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Insights,
                contentDescription = null,
                tint = GoldAccent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("Insights", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        insights.forEach { line ->
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Text("\u2022", color = GoldAccent, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(line, color = Color(0xFFDDDDDD), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = value, color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable
private fun PlanCard(dailyPlan: String, isPlanning: Boolean, onGeneratePlan: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Plan My Day", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Button(
                onClick = onGeneratePlan,
                enabled = !isPlanning,
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = PureDark)
            ) {
                if (isPlanning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = PureDark, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Generate", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (dailyPlan.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(text = dailyPlan, color = Color(0xFFDDDDDD), fontSize = 13.sp)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = CyanAccent,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun SectionHeaderWithAction(title: String, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, color = CyanAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        IconButton(onClick = onAdd, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Add, contentDescription = "Add $title", tint = CyanAccent)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text = text, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun FocusTaskRow(task: TaskEntity, onToggleTask: (TaskEntity) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = task.isCompleted,
            onCheckedChange = { onToggleTask(task) },
            colors = CheckboxDefaults.colors(checkedColor = CyanAccent)
        )
        Spacer(Modifier.width(6.dp))
        Text(text = task.title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        PriorityPill(task.priority)
    }
}

@Composable
private fun PriorityPill(priority: String) {
    val color = when (priority.uppercase()) {
        "HIGH" -> Color(0xFFFF5A5A)
        "MED", "MEDIUM" -> GoldAccent
        else -> Color(0xFF00FF66)
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text = priority.uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GoalRow(
    goal: GoalEntity,
    onIncrement: (GoalEntity, Int) -> Unit,
    onToggleComplete: (GoalEntity) -> Unit,
    onDelete: (GoalEntity) -> Unit
) {
    val fraction = if (goal.targetValue <= 0) 0f else (goal.currentValue.toFloat() / goal.targetValue).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = goal.title,
                    color = if (goal.isCompleted) Color.Gray else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = "${goal.category} • ${goal.currentValue}/${goal.targetValue}", color = Color.Gray, fontSize = 11.sp)
            }
            IconButton(onClick = { onIncrement(goal, -5) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = Color.Gray)
            }
            IconButton(onClick = { onIncrement(goal, 5) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Increase", tint = CyanAccent)
            }
            IconButton(onClick = { onDelete(goal) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5A5A))
            }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = if (goal.isCompleted) Color(0xFF00FF66) else GoldAccent,
            trackColor = PureDark
        )
    }
}

@Composable
private fun ProjectRow(
    project: ProjectEntity,
    onCycleStatus: (ProjectEntity) -> Unit,
    onDelete: (ProjectEntity) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = project.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (project.description.isNotBlank()) {
                Text(text = project.description, color = Color.Gray, fontSize = 11.sp)
            }
        }
        StatusChip(status = project.status, onClick = { onCycleStatus(project) })
        IconButton(onClick = { onDelete(project) }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5A5A))
        }
    }
}

@Composable
private fun StatusChip(status: String, onClick: () -> Unit) {
    val color = when (status) {
        ProjectStatus.ACTIVE -> Color(0xFF00FF66)
        ProjectStatus.ON_HOLD -> GoldAccent
        else -> Color.Gray
    }
    Box(
        modifier = Modifier
            .clickable { onClick() }
            .background(color.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = status.replace('_', ' '), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HabitRow(
    habit: HabitEntity,
    today: String,
    onToggle: (HabitEntity) -> Unit,
    onDelete: (HabitEntity) -> Unit
) {
    val doneToday = habit.lastCompletedDate == today
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(SurfaceDark, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        IconButton(onClick = { onToggle(habit) }) {
            Icon(
                imageVector = if (doneToday) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = "Toggle today",
                tint = if (doneToday) CyanAccent else Color.Gray
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(habit.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(habit.category, color = Color.Gray, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.LocalFireDepartment,
                contentDescription = "Streak",
                tint = if (habit.streak > 0) GoldAccent else Color.Gray,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text("${habit.streak}", color = if (habit.streak > 0) GoldAccent else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        IconButton(onClick = { onDelete(habit) }) {
            Icon(Icons.Default.Delete, contentDescription = "Delete habit", tint = Color.Gray, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AddHabitDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, category: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Health") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(name, category) }) { Text("Add", color = CyanAccent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } },
        title = { Text("New Habit", color = Color.White) },
        containerColor = SurfaceDark,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Habit (e.g. Meditate)") }, singleLine = true)
                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, singleLine = true)
            }
        }
    )
}

@Composable
private fun AddGoalDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, category: String, target: Int, deadline: String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Personal") }
    var targetText by remember { mutableStateOf("100") }
    var deadline by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(title, category, targetText.toIntOrNull() ?: 100, deadline.ifBlank { null })
            }) { Text("Add", color = CyanAccent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } },
        title = { Text("New Goal", color = Color.White) },
        containerColor = SurfaceDark,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, singleLine = true)
                OutlinedTextField(value = targetText, onValueChange = { targetText = it.filter { c -> c.isDigit() } }, label = { Text("Target (e.g. 100)") }, singleLine = true)
                OutlinedTextField(value = deadline, onValueChange = { deadline = it }, label = { Text("Deadline (YYYY-MM-DD, optional)") }, singleLine = true)
            }
        }
    )
}

@Composable
private fun AddProjectDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(name, description) }) { Text("Add", color = CyanAccent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } },
        title = { Text("New Project", color = Color.White) },
        containerColor = SurfaceDark,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description (optional)") })
            }
        }
    )
}

@Preview(showBackground = true, name = "Executive Dashboard Preview")
@Composable
fun ExecutiveDashboardScreenPreview() {
    val sampleTasks = listOf(
        TaskEntity("1", "Finalize Q3 Budget", "Finance", "HIGH", LocalDate.now().toString(), false, 0L),
        TaskEntity("2", "Review PR", "Work", "MED", LocalDate.now().toString(), false, 0L)
    )
    val sampleGoals = listOf(
        GoalEntity("g1", "Read 12 books", "Personal", 12, 4, null, false, 0L),
        GoalEntity("g2", "Ship JAX v1", "Career", 100, 60, "2026-12-31", false, 0L)
    )
    val sampleProjects = listOf(
        ProjectEntity("p1", "JAX Assistant", "Android build", ProjectStatus.ACTIVE, 0L, 0L),
        ProjectEntity("p2", "Home Automation", "", ProjectStatus.ON_HOLD, 0L, 0L)
    )
    val sampleHabits = listOf(
        HabitEntity("h1", "Meditate", "Health", 5, LocalDate.now().toString(), 0L),
        HabitEntity("h2", "Read", "Personal", 0, null, 0L)
    )
    JAXAssistantTheme {
        ExecutiveDashboardScreen(
            tasks = sampleTasks,
            goals = sampleGoals,
            projects = sampleProjects,
            habits = sampleHabits,
            dailyPlan = "Now: Finalize Q3 Budget\nNext: Review PR\nLater: plan sprint",
            isPlanning = false,
            onGeneratePlan = {},
            onToggleTask = {},
            onAddGoal = { _, _, _, _ -> },
            onIncrementGoal = { _, _ -> },
            onToggleGoalComplete = {},
            onDeleteGoal = {},
            onAddProject = { _, _ -> },
            onCycleProjectStatus = {},
            onDeleteProject = {},
            onAddHabit = { _, _ -> },
            onToggleHabit = {},
            onDeleteHabit = {}
        )
    }
}
