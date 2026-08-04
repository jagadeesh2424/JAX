package com.jax.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.TaskEntity
import com.jax.assistant.ui.theme.CyanAccent
import com.jax.assistant.ui.theme.GoldAccent
import com.jax.assistant.ui.theme.PureDark
import com.jax.assistant.ui.theme.SurfaceDark

@Composable
fun DailyBriefingScreen(
    tasks: List<TaskEntity>,
    facts: List<FactEntity>,
    onSpeakBriefing: (String) -> Unit
) {
    val pendingTasks = tasks.filter { !it.isCompleted }
    val highPriorityCount = pendingTasks.count { it.priority.lowercase() == "high" }

    val briefingText = remember(tasks, facts) {
        val taskSummary = if (pendingTasks.isNotEmpty()) {
            "You have ${pendingTasks.size} pending tasks ($highPriorityCount high priority)."
        } else {
            "All tasks completed! Excellent work."
        }
        val memorySummary = if (facts.isNotEmpty()) {
            " Memory vault holds ${facts.size} saved facts."
        } else ""

        "Good day, Jagadeesh. $taskSummary$memorySummary J.A.X. stands ready to assist."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureDark)
            .padding(16.dp)
    ) {
        Text(
            text = "Daily AI Briefing",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Executive Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(CyanAccent, shape = RoundedCornerShape(5.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "J.A.X. EXECUTIVE SUMMARY",
                        color = CyanAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = briefingText,
                    color = Color.White,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { onSpeakBriefing(briefingText) },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Listen to Audio Briefing",
                        color = PureDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Briefing Highlights",
            color = Color.LightGray,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Stat Box 1
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Text(text = "Pending", color = Color.Gray, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "${pendingTasks.size}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }

            // Stat Box 2
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Text(text = "High Priority", color = Color.Gray, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "$highPriorityCount", color = GoldAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }

            // Stat Box 3
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(SurfaceDark, shape = RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Text(text = "Memories", color = Color.Gray, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "${facts.size}", color = CyanAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
