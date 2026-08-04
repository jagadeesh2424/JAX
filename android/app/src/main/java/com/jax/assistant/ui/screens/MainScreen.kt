package com.jax.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.TaskEntity
import com.jax.assistant.ui.theme.CyanAccent
import com.jax.assistant.ui.theme.PureDark
import com.jax.assistant.ui.theme.SurfaceDark

@Composable
fun MainScreen(
    messages: List<ComposeChatMessage>,
    tasks: List<TaskEntity>,
    facts: List<FactEntity>,
    onSendMessage: (String) -> Unit,
    onToggleTask: (TaskEntity) -> Unit,
    onSearchFacts: (String) -> Unit,
    onMicClick: () -> Unit,
    onSpeakBriefing: (String) -> Unit
) {
    var activeTab by remember { mutableStateOf(0) } // 0: Chat, 1: Tasks, 2: Calendar, 3: Memory Vault, 4: Briefing

    Scaffold(
        topBar = {
            // Minimalist Header: "J.A.X. AI" + Active Dot
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PureDark)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "J.A.X. AI",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(Color(0xFF00FF66), CircleShape)
                )
            }
        },
        bottomBar = {
            // Material 3 Navigation Bar supporting 6 main tabs
            NavigationBar(
                containerColor = SurfaceDark,
                contentColor = Color.White
            ) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Default.Chat, contentDescription = "Chat") },
                    label = { Text("Chat", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = CyanAccent, indicatorColor = CyanAccent.copy(alpha = 0.2f))
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Default.Checklist, contentDescription = "Tasks") },
                    label = { Text("Tasks", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = CyanAccent, indicatorColor = CyanAccent.copy(alpha = 0.2f))
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Default.DateRange, contentDescription = "Calendar") },
                    label = { Text("Calendar", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = CyanAccent, indicatorColor = CyanAccent.copy(alpha = 0.2f))
                )
                NavigationBarItem(
                    selected = activeTab == 3,
                    onClick = { activeTab = 3 },
                    icon = { Icon(Icons.Default.Description, contentDescription = "Memory Vault") },
                    label = { Text("Memory", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = CyanAccent, indicatorColor = CyanAccent.copy(alpha = 0.2f))
                )
                NavigationBarItem(
                    selected = activeTab == 4,
                    onClick = { activeTab = 4 },
                    icon = { Icon(Icons.Default.WbSunny, contentDescription = "Briefing") },
                    label = { Text("Briefing", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = CyanAccent, indicatorColor = CyanAccent.copy(alpha = 0.2f))
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeTab) {
                0 -> OmniChatScreen(messages = messages, onSendMessage = onSendMessage, onMicClick = onMicClick)
                1 -> TaskDashboardScreen(tasks = tasks, onToggleTask = onToggleTask)
                2 -> CalendarScreen(tasks = tasks, onToggleTask = onToggleTask)
                3 -> MemoryVaultScreen(facts = facts, onSearch = onSearchFacts)
                4 -> DailyBriefingScreen(tasks = tasks, facts = facts, onSpeakBriefing = onSpeakBriefing)
            }
        }
    }
}
