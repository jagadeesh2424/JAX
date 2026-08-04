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
import com.jax.assistant.ui.theme.CyanAccent
import com.jax.assistant.ui.theme.PureDark
import com.jax.assistant.ui.theme.SurfaceDark

@Composable
fun SettingsScreen(
    taskCount: Int,
    factCount: Int,
    onClearAllData: () -> Unit
) {
    var voiceResponsesEnabled by remember { mutableStateOf(true) }
    var dailyWorkerEnabled by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureDark)
            .padding(16.dp)
    ) {
        Text(
            text = "J.A.X. Settings",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // System Config Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, shape = RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "SYSTEM CONFIGURATION",
                color = CyanAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Voice Responses (TTS)", color = Color.White, fontSize = 14.sp)
                    Text(text = "Speak responses using text-to-speech", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = voiceResponsesEnabled,
                    onCheckedChange = { voiceResponsesEnabled = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = PureDark, checkedTrackColor = CyanAccent)
                )
            }

            Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color.DarkGray)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Daily 9:00 AM Agent Briefing", color = Color.White, fontSize = 14.sp)
                    Text(text = "Background WorkManager periodic task", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = dailyWorkerEnabled,
                    onCheckedChange = { dailyWorkerEnabled = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = PureDark, checkedTrackColor = CyanAccent)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Database Metrics Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, shape = RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "ROOM DATABASE METRICS",
                color = CyanAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Total Registered Tasks:", color = Color.LightGray, fontSize = 13.sp)
                Text(text = "$taskCount", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Total Saved Facts & Notes:", color = Color.LightGray, fontSize = 13.sp)
                Text(text = "$factCount", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // About / AI Brain Info Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, shape = RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "J.A.X. AI BRAIN",
                color = CyanAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Powered by Google Gemini 1.5 Flash SDK on Android.",
                color = Color.LightGray,
                fontSize = 12.sp
            )
        }
    }
}
