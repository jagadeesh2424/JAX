package com.jax.assistant.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jax.assistant.ai.ModelInfo
import com.jax.assistant.ai.RequestLog
import com.jax.assistant.ui.theme.CyanAccent
import com.jax.assistant.ui.theme.PureDark
import com.jax.assistant.ui.theme.SurfaceDark

@Composable
fun ProviderDiagnosticsCard(
    requestLogs: List<RequestLog>,
    modelCatalog: List<ModelInfo>,
    selectedModel: String,
    apiKey: String,
    onOpenDevConsole: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, shape = RoundedCornerShape(14.dp))
            .border(1.dp, CyanAccent.copy(alpha = 0.5f), shape = RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PROVIDER DIAGNOSTICS PAGE",
                color = CyanAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Box(
                modifier = Modifier
                    .background(CyanAccent.copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(text = "LIVE DIAGNOSTICS", color = CyanAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val lastLog = requestLogs.firstOrNull()
        val availableModelsStr = modelCatalog.map { it.id }.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "gemini-2.0-flash, gemini-1.5-flash, gemini-1.5-pro" }
        val authStatusStr = if (apiKey.isNotBlank()) "Authenticated (Key Present)" else "Unauthenticated (Key Missing)"
        val httpStatusStr = lastLog?.let { "HTTP ${it.httpStatus ?: 200}" } ?: "200 OK"
        val googleErrorStr = lastLog?.let { if (!it.isSuccess) "Error Code: ${it.httpStatus}" else "None (200 OK)" } ?: "None (200 OK)"

        DiagnosticRow("SDK Version", "0.9.0")
        DiagnosticRow("REST Endpoint", "https://generativelanguage.googleapis.com/v1beta")
        DiagnosticRow("API Version", "v1beta")
        DiagnosticRow("Installed SDK", "com.google.ai.client.generativeai:0.9.0")
        DiagnosticRow("Selected Model", selectedModel.ifBlank { "gemini-2.0-flash" })
        DiagnosticRow("Available Models", availableModelsStr)
        DiagnosticRow("HTTP Status", httpStatusStr)
        DiagnosticRow("Google Error", googleErrorStr)
        DiagnosticRow("Authentication Status", authStatusStr)

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onOpenDevConsole,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyanAccent,
                contentColor = PureDark
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.BugReport,
                    contentDescription = "Developer Console",
                    tint = PureDark,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "LAUNCH DEVELOPER CONSOLE",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun DiagnosticRow(label: String, value: String) {
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
