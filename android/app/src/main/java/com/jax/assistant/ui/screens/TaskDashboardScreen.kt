package com.jax.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jax.assistant.db.TaskEntity
import com.jax.assistant.ui.theme.CyanAccent
import com.jax.assistant.ui.theme.GoldAccent
import com.jax.assistant.ui.theme.JAXAssistantTheme
import com.jax.assistant.ui.theme.PureDark
import com.jax.assistant.ui.theme.SurfaceDark

@Composable
fun TaskDashboardScreen(
    tasks: List<TaskEntity>,
    onToggleTask: (TaskEntity) -> Unit,
    onAddTask: (title: String, category: String, priority: String, deadline: String?) -> Unit
) {
    var selectedCategory by remember { mutableStateOf("All") }
    var showAddDialog by remember { mutableStateOf(false) }

    val categories = listOf("All", "Work", "Personal", "Finance", "General")

    val filteredTasks = remember(tasks, selectedCategory) {
        tasks.filter {
            if (selectedCategory == "All") true else it.category.equals(selectedCategory, ignoreCase = true)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Task Dashboard",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Task", tint = PureDark)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Add Task", color = PureDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Category Filter Tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    val isSelected = selectedCategory == cat
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) CyanAccent else SurfaceDark,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = cat,
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (filteredTasks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No tasks found in this category.\nTap '+ Add Task' to create one manually.",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                }
            } else {
                // Task Items List
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredTasks, key = { it.id }) { task ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = task.isCompleted,
                                onCheckedChange = { onToggleTask(task) },
                                colors = CheckboxDefaults.colors(checkedColor = CyanAccent)
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = task.title,
                                    color = if (task.isCompleted) Color.Gray else Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = task.category,
                                        color = CyanAccent,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = "• ${task.priority}",
                                        color = if (task.priority == "HIGH") Color.Red else GoldAccent,
                                        fontSize = 11.sp
                                    )
                                    val deadline = task.deadline
                                    if (!deadline.isNullOrEmpty()) {
                                        Text(
                                            text = "• Due: $deadline",
                                            color = Color.LightGray,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            AddTaskDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { title, cat, priority, deadline ->
                    onAddTask(title, cat, priority, deadline)
                    showAddDialog = false
                }
            )
        }
    }
}

@Composable
fun AddTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, category: String, priority: String, deadline: String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Work") }
    var priority by remember { mutableStateOf("HIGH") }
    var deadline by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text(text = "Create Task Directly", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Title", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = Color.DarkGray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category (Work, Personal, Finance)", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = Color.DarkGray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(text = "Priority Level", color = Color.LightGray, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("HIGH", "MED", "LOW").forEach { p ->
                        val isSel = priority == p
                        Button(
                            onClick = { priority = p },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSel) CyanAccent else Color.DarkGray
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(text = p, color = if (isSel) PureDark else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                OutlinedTextField(
                    value = deadline,
                    onValueChange = { deadline = it },
                    label = { Text("Deadline / Due Date (Optional)", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = Color.DarkGray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onConfirm(title, category, priority, deadline)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
            ) {
                Text(text = "Save Task", color = PureDark, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel", color = Color.Gray)
            }
        }
    )
}

@Preview(showBackground = true, name = "TaskDashboard Screen Preview")
@Composable
fun TaskDashboardScreenPreview() {
    val sampleTasks = listOf(
        TaskEntity(
            id = "1",
            title = "Finalize Q3 Budget Proposal",
            category = "Finance",
            priority = "HIGH",
            deadline = "2026-08-06",
            isCompleted = false,
            createdAt = System.currentTimeMillis()
        ),
        TaskEntity(
            id = "2",
            title = "Team Standup Sync",
            category = "Work",
            priority = "MED",
            deadline = "2026-08-06",
            isCompleted = true,
            createdAt = System.currentTimeMillis()
        ),
        TaskEntity(
            id = "3",
            title = "Renew Vehicle Insurance",
            category = "Personal",
            priority = "LOW",
            deadline = "2026-08-10",
            isCompleted = false,
            createdAt = System.currentTimeMillis()
        )
    )

    JAXAssistantTheme {
        TaskDashboardScreen(
            tasks = sampleTasks,
            onToggleTask = {},
            onAddTask = { _, _, _, _ -> }
        )
    }
}

