package com.jax.assistant.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jax.assistant.ai.ModelInfo
import com.jax.assistant.ai.RequestLog
import com.jax.assistant.ui.theme.GoldAccent
import com.jax.assistant.ui.theme.PureDark
import com.jax.assistant.ui.theme.SurfaceDark

@Composable
fun DevDiagnosticsDashboard(
    selectedModel: String,
    modelCatalog: List<ModelInfo>,
    requestLogs: List<RequestLog>,
    onRunHealthCheck: ((onResult: (String) -> Unit) -> Unit)?
) {
    var isHealthCheckRunning by remember { mutableStateOf(false) }
    var healthCheckResultMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, shape = RoundedCornerShape(14.dp))
            .border(1.dp, GoldAccent.copy(alpha = 0.5f), shape = RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "DEVELOPER ROUTER DIAGNOSTICS",
            color = GoldAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Current Active Model Info
        val activeModelName = selectedModel.ifBlank { "gemini-2.0-flash" }
        val activeModelObj = modelCatalog.find { it.id.equals(activeModelName, ignoreCase = true) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PureDark, shape = RoundedCornerShape(10.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "Active Routed Model", color = Color.Gray, fontSize = 11.sp)
                Text(
                    text = activeModelObj?.displayName ?: activeModelName,
                    color = GoldAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(text = "Avg Latency", color = Color.Gray, fontSize = 11.sp)
                Text(
                    text = if ((activeModelObj?.averageLatency ?: 0L) > 0) "${activeModelObj?.averageLatency} ms" else "< 350 ms",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Model Health Catalog Table
        Text(text = "MODEL HEALTH CATALOG", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        modelCatalog.forEach { model ->
            val isCooldown = System.currentTimeMillis() < model.cooldownUntil
            val statusColor = if (!model.enabled) Color.Gray else if (isCooldown) Color.Yellow else Color(0xFF00FF66)
            val statusText = if (!model.enabled) "Disabled" else if (isCooldown) "Cooldown" else "Healthy"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .background(PureDark.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = model.id, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(
                        text = "Prio: ${model.priority} | Speed: ${model.speedScore}/10 | Reason: ${model.reasoningScore}/10",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statusColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    if (model.failureCount > 0) {
                        Text(text = "Failures: ${model.failureCount}", color = Color.Red, fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Run Health Check Button
        Button(
            onClick = {
                isHealthCheckRunning = true
                healthCheckResultMsg = null
                onRunHealthCheck?.invoke { res ->
                    isHealthCheckRunning = false
                    healthCheckResultMsg = res
                }
            },
            enabled = !isHealthCheckRunning,
            colors = ButtonDefaults.buttonColors(containerColor = GoldAccent),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isHealthCheckRunning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = PureDark, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Testing All Models...", color = PureDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            } else {
                Text(text = "🧪 Run Health Check Across Catalog", color = PureDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        healthCheckResultMsg?.let { msg ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = msg, color = GoldAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Recent Routing Request Logs
        Text(text = "RECENT ROUTING REQUEST LOGS", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        if (requestLogs.isEmpty()) {
            Text(text = "No request logs recorded yet.", color = Color.Gray, fontSize = 11.sp)
        } else {
            requestLogs.take(5).forEach { log ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .background(PureDark.copy(alpha = 0.8f), shape = RoundedCornerShape(6.dp))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (log.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (log.isSuccess) Color(0xFF00FF66) else Color.Red,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = log.modelUsed,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Retries: ${log.retryCount} | Latency: ${log.latencyMs} ms",
                                color = Color.Gray,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Text(
                        text = "HTTP ${log.httpStatus ?: 200}",
                        color = if (log.isSuccess) Color(0xFF00FF66) else Color.Red,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
