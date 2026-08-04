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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jax.assistant.ui.theme.CyanAccent
import com.jax.assistant.ui.theme.GoldAccent
import com.jax.assistant.ui.theme.PureDark
import com.jax.assistant.ui.theme.SurfaceDark

@Composable
fun SettingsScreen(
    apiKey: String,
    onUpdateApiKey: (String) -> Unit,
    taskCount: Int,
    factCount: Int,
    onClearAllData: () -> Unit
) {
    var inputKey by remember(apiKey) { mutableStateOf(apiKey) }
    var isKeyVisible by remember { mutableStateOf(false) }
    var voiceResponsesEnabled by remember { mutableStateOf(true) }
    var dailyWorkerEnabled by remember { mutableStateOf(true) }
    var showSavedToast by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureDark)
            .padding(16.dp)
    ) {
        Text(
            text = "J.A.X. Settings & Configuration",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // API Key Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, shape = RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GEMINI AI API KEY",
                    color = CyanAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                if (apiKey.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF00FF66).copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = "KEY ACTIVE", color = Color(0xFF00FF66), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .background(Color.Red.copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = "KEY MISSING", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Provide your Google Gemini API key to enable AI intelligence on physical devices.",
                color = Color.Gray,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = inputKey,
                onValueChange = { inputKey = it },
                label = { Text("Gemini API Key (AIzaSy...)", color = Color.Gray) },
                singleLine = true,
                visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanAccent,
                    unfocusedBorderColor = Color.DarkGray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { isKeyVisible = !isKeyVisible }) {
                    Text(
                        text = if (isKeyVisible) "Hide Key" else "Show Key",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = {
                        onUpdateApiKey(inputKey)
                        showSavedToast = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(text = "Save API Key", color = PureDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            if (showSavedToast) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "✓ API Key updated and saved securely.",
                    color = Color(0xFF00FF66),
                    fontSize = 11.sp
                )
            }
        }

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

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.DarkGray)

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
                Text(text = "$factCount", color = GoldAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
