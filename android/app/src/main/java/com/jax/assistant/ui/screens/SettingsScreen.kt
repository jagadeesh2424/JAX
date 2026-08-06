package com.jax.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jax.assistant.ai.ModelInfo
import com.jax.assistant.ai.RequestLog
import com.jax.assistant.ai.TestConnectionResult
import com.jax.assistant.ui.theme.CyanAccent
import com.jax.assistant.ui.theme.GoldAccent
import com.jax.assistant.ui.theme.JAXAssistantTheme
import com.jax.assistant.ui.theme.PureDark
import com.jax.assistant.ui.theme.SurfaceDark

import com.jax.assistant.ui.screens.settings.DevDiagnosticsDashboard
import com.jax.assistant.ui.screens.settings.ProviderDiagnosticsCard

@Composable
fun SettingsScreen(
    apiKey: String,
    onUpdateApiKey: (String) -> Unit,
    selectedModel: String = "gemini-2.0-flash",
    onUpdateSelectedModel: (String) -> Unit = {},
    onTestConnection: ((onResult: (TestConnectionResult) -> Unit) -> Unit)? = null,
    developerMode: Boolean = false,
    onToggleDeveloperMode: (Boolean) -> Unit = {},
    onOpenDevConsole: () -> Unit = {},
    modelCatalog: List<ModelInfo> = emptyList(),
    requestLogs: List<RequestLog> = emptyList(),
    onRunHealthCheck: ((onResult: (String) -> Unit) -> Unit)? = null,
    taskCount: Int,
    factCount: Int,
    onClearAllData: () -> Unit
) {
    var inputKey by remember(apiKey) { mutableStateOf(apiKey) }
    var isKeyVisible by remember { mutableStateOf(false) }
    var voiceResponsesEnabled by remember { mutableStateOf(true) }
    var dailyWorkerEnabled by remember { mutableStateOf(true) }
    var showSavedToast by remember { mutableStateOf(false) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var testResultMsg by remember { mutableStateOf<String?>(null) }
    var isTestSuccess by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureDark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "J.A.X. Settings & Configuration",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // PROVIDER DIAGNOSTICS CARD
        ProviderDiagnosticsCard(
            requestLogs = requestLogs,
            modelCatalog = modelCatalog,
            selectedModel = selectedModel,
            apiKey = apiKey,
            onOpenDevConsole = onOpenDevConsole
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Automatic AI Model Routing & Selection Card
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = "Auto Routing",
                        tint = CyanAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI MODEL ROUTING",
                        color = CyanAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .background(CyanAccent.copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(text = "AUTOMATIC", color = CyanAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "J.A.X. automatically selects the optimal Gemini model based on task capability, health status, speed, and failover health.",
                color = Color.Gray,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Preferred Provider
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PureDark.copy(alpha = 0.5f), shape = RoundedCornerShape(10.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Preferred Provider", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = "Google Gemini Engine (Auto-Failover)", color = Color.LightGray, fontSize = 11.sp)
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(Color(0xFF00FF66), CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Developer Mode Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.BugReport,
                        contentDescription = "Developer Mode",
                        tint = if (developerMode) GoldAccent else Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(text = "Developer Mode", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(text = "Show real-time routing metrics, retries, & logs", color = Color.Gray, fontSize = 11.sp)
                    }
                }

                Switch(
                    checked = developerMode,
                    onCheckedChange = { onToggleDeveloperMode(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = PureDark, checkedTrackColor = GoldAccent)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // DEVELOPER DIAGNOSTICS DASHBOARD (Visible ONLY when Developer Mode is enabled)
        if (developerMode) {
            DevDiagnosticsDashboard(
                selectedModel = selectedModel,
                modelCatalog = modelCatalog,
                requestLogs = requestLogs,
                onRunHealthCheck = onRunHealthCheck
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

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
                text = "Provide your Google Gemini API key to enable AI intelligence across routed models.",
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

        // Connection Diagnostics & Test Connection Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, shape = RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "CONNECTION DIAGNOSTICS",
                color = CyanAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Verify API key, automated router health, network connectivity, and AI response:",
                color = Color.Gray,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    isTestingConnection = true
                    testResultMsg = null
                    onTestConnection?.invoke { result ->
                        isTestingConnection = false
                        isTestSuccess = result.isSuccess
                        testResultMsg = result.message
                    }
                },
                enabled = !isTestingConnection,
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTestingConnection) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = PureDark,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Testing Connection...", color = PureDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                } else {
                    Text(text = "⚡ Test AI Router Connection", color = PureDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            testResultMsg?.let { msg ->
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isTestSuccess) Color(0xFF00FF66).copy(alpha = 0.15f) else Color.Red.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .border(
                            1.dp,
                            if (isTestSuccess) Color(0xFF00FF66) else Color.Red,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = msg,
                        color = if (isTestSuccess) Color(0xFF00FF66) else Color(0xFFFF6B6B),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
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

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = value,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Preview(showBackground = true, name = "Settings Screen Preview")
@Composable
fun SettingsScreenPreview() {
    val sampleModelCatalog = listOf(
        ModelInfo(
            id = "gemini-2.0-flash",
            displayName = "Gemini 2.0 Flash (Fast)",
            priority = 1,
            supportedMethods = listOf("generateContent"),
            enabled = true,
            cooldownUntil = 0L,
            averageLatency = 340L
        ),
        ModelInfo(
            id = "gemini-1.5-pro",
            displayName = "Gemini 1.5 Pro (Deep)",
            priority = 2,
            supportedMethods = listOf("generateContent"),
            enabled = true,
            cooldownUntil = 0L,
            averageLatency = 820L
        )
    )

    JAXAssistantTheme {
        SettingsScreen(
            apiKey = "AIzaSyPreviewMockKey123456789",
            onUpdateApiKey = {},
            selectedModel = "gemini-2.0-flash",
            developerMode = true,
            modelCatalog = sampleModelCatalog,
            taskCount = 12,
            factCount = 8,
            onClearAllData = {}
        )
    }
}


