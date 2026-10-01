package com.jax.assistant.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DevDiagnosticsDashboard(
    selectedModel: String,
    modelCatalog: List<ModelInfo>,
    requestLogs: List<RequestLog>,
    onRunHealthCheck: ((onResult: (String) -> Unit) -> Unit)?
) {
    var isHealthCheckRunning by remember { mutableStateOf(false) }
    var healthCheckResultMsg by remember { mutableStateOf<String?>(null) }
    var selectedRequest by remember { mutableStateOf<RequestLog?>(null) }
    val requestSummaries = requestLogs.filter { it.isRequestSummary }.ifEmpty { requestLogs }

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

        Text(text = "LAST REQUEST", color = GoldAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        requestSummaries.firstOrNull()?.let { log ->
            DiagnosticsRequestCard(log, prominent = true, onClick = { selectedRequest = log })
            Spacer(modifier = Modifier.height(6.dp))
            DiagnosticRow("Request ID", log.requestId ?: "—")
            DiagnosticRow("Why", log.routingReason ?: "Not recorded")
            DiagnosticRow("Model calls", if (log.modelCallCount > 0) log.modelCallCount.toString() else "Not recorded")
            DiagnosticRow("Tool calls", if (log.isRequestSummary) log.toolCallCount.toString() else "Not recorded")
            DiagnosticRow("Memory", memoryLabel(log.memoryStatus))
            DiagnosticRow("Fallback", log.fallbackChain ?: "None")
            DiagnosticRow("Fallback reason", log.fallbackReason ?: "—")
            DiagnosticRow("Tokens", tokenLabel(log))
        } ?: Text(text = "No request logs recorded yet.", color = Color.Gray, fontSize = 11.sp)

        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "USAGE SUMMARY — CURRENT SESSION", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        DiagnosticsUsageSummary(requestSummaries)

        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "RECENT REQUESTS", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        requestSummaries.take(20).forEach { log ->
            DiagnosticsRequestCard(log, onClick = { selectedRequest = log })
            Spacer(modifier = Modifier.height(4.dp))
        }
    }

    selectedRequest?.let { log ->
        RequestDetailsDialog(log = log, onDismiss = { selectedRequest = null })
    }
}

@Composable
private fun DiagnosticsRequestCard(log: RequestLog, prominent: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(PureDark.copy(alpha = if (prominent) 1f else 0.8f), shape = RoundedCornerShape(8.dp))
            .padding(if (prominent) 11.dp else 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (log.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (log.isSuccess) Color(0xFF00FF66) else Color.Red,
                modifier = Modifier.size(if (prominent) 17.dp else 14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = "${formatDiagnosticTime(log.timestamp)}  ${log.route ?: "MODEL"}",
                    color = Color.White,
                    fontSize = if (prominent) 12.sp else 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${log.provider}  •  ${log.modelUsed}  •  ${log.latencyMs} ms" +
                        (if (!log.fallbackChain.isNullOrBlank() && log.fallbackChain!!.contains("→")) "  •  FALLBACK" else ""),
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Text(
            text = if (log.isSuccess) "✓" else "✗",
            color = if (log.isSuccess) Color(0xFF00FF66) else Color.Red,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DiagnosticsUsageSummary(logs: List<RequestLog>) {
    if (logs.isEmpty()) {
        Text("No request logs recorded yet.", color = Color.Gray, fontSize = 11.sp)
        return
    }
    val providers = logs.groupBy { it.provider }
    val models = logs.filter { it.modelUsed != "None" }.groupBy { it.modelUsed }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PureDark.copy(alpha = 0.7f), shape = RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        providers.entries.sortedByDescending { it.value.size }.forEach { (provider, entries) ->
            val tokens = entries.sumOf { it.promptTokens + it.outputTokens }
            Text(
                text = "$provider: ${entries.size} requests • avg ${entries.map { it.latencyMs }.average().toLong()} ms" +
                    if (tokens > 0) " • $tokens tokens" else "",
                color = Color.White,
                fontSize = 11.sp
            )
        }
        if (models.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text("MODEL USAGE", color = GoldAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            models.entries.sortedByDescending { it.value.size }.forEach { (model, entries) ->
                Text("$model: ${entries.size} requests", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun RequestDetailsDialog(log: RequestLog, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("REQUEST DETAILS", color = GoldAccent, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DiagnosticRow("Time", formatDiagnosticTime(log.timestamp))
                DiagnosticRow("Request ID", log.requestId ?: "—")
                DiagnosticRow("Route", log.route ?: "—")
                DiagnosticRow("Provider", log.provider.ifBlank { "—" })
                DiagnosticRow("Model", log.modelUsed.ifBlank { "—" })
                DiagnosticRow("Latency", "${log.latencyMs} ms")
                DiagnosticRow("HTTP status", log.httpStatus?.toString() ?: "Not recorded")
                DiagnosticRow("Retries", log.retryCount.toString())
                DiagnosticRow("Model calls", if (log.modelCallCount > 0) log.modelCallCount.toString() else "Not recorded")
                DiagnosticRow("Tool calls", if (log.isRequestSummary) log.toolCallCount.toString() else "Not recorded")
                DiagnosticRow("Memory", memoryLabel(log.memoryStatus))
                DiagnosticRow("Fallback", log.fallbackChain ?: "None")
                DiagnosticRow("Fallback reason", log.fallbackReason ?: "—")
                DiagnosticRow("Tokens", tokenLabel(log))
                DiagnosticRow("Why", log.routingReason ?: "Not recorded")
                DiagnosticRow("Status", if (log.isSuccess) "SUCCESS" else "FAILURE")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } },
        containerColor = SurfaceDark,
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

private fun memoryLabel(status: String): String = when (status) {
    "LOOKUP_MATCH" -> "Used — fact found"
    "LOOKUP_NO_MATCH" -> "Used — no matching fact"
    "USED_IN_CONTEXT" -> "Used in context"
    else -> "Not used"
}

private fun tokenLabel(log: RequestLog): String = when {
    log.promptTokens + log.outputTokens == 0 -> "Not available"
    else -> "Input ${log.promptTokens} • Output ${log.outputTokens} • Total ${log.promptTokens + log.outputTokens}"
}

private fun formatDiagnosticTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
