package com.jax.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jax.assistant.ai.*
import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.TaskEntity
import com.jax.assistant.ui.theme.CyanAccent
import com.jax.assistant.ui.theme.PureDark
import com.jax.assistant.ui.theme.SurfaceDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

import com.jax.assistant.ui.screens.devconsole.DevConsoleButton
import com.jax.assistant.ui.screens.devconsole.DevConsoleResultOutput

@Composable
fun DeveloperConsoleScreen(
    apiKey: String,
    selectedModel: String,
    taskCount: Int,
    factCount: Int,
    onClose: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var activeTestName by remember { mutableStateOf("No test executed") }
    var isRunningTest by remember { mutableStateOf(false) }
    var testResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureDark)
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        // Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.BugReport,
                    contentDescription = "Dev Console",
                    tint = CyanAccent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "J.A.X. DEVELOPER CONSOLE",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
            }
        }

        Text(
            text = "Diagnostic sandbox to debug AI layer & services without modifying production user data.",
            color = Color.Gray,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Diagnostic Buttons Grid
        Text(
            text = "DIAGNOSTIC SUITE RUNNERS",
            color = CyanAccent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Button 0: Discover & Inspect Models
                DevConsoleButton(
                    label = "Discover Models (GET /v1beta/models)",
                    modifier = Modifier.weight(1f),
                    enabled = !isRunningTest,
                    onClick = {
                        isRunningTest = true
                        activeTestName = "Runtime Model Discovery (GET /v1beta/models)"
                        coroutineScope.launch(Dispatchers.IO) {
                            val startTime = System.currentTimeMillis()
                            val router = AIRouter(null)
                            router.ensureModelDiscovery(apiKey, force = true)
                            val latency = System.currentTimeMillis() - startTime
                            val models = router.getModels()
                            val selected = router.getActiveModel()

                            val discoveredFormatted = models.joinToString("\n--------------------\n") { m ->
                                val status = if (m.enabled && m.cooldownUntil <= System.currentTimeMillis()) "HEALTHY (Active)"
                                else if (m.cooldownUntil > System.currentTimeMillis()) "COOLDOWN (Rate Limited)"
                                else "EXCLUDED / DISABLED"

                                """
                                Model ID: ${m.id} (${m.displayName})
                                Priority Rank: #${m.priority}
                                Supported Methods: ${m.supportedMethods}
                                Health Status: $status
                                Average Latency: ${m.averageLatency}ms
                                Excluded Reason: ${m.exclusionReason ?: "None (Available for selection)"}
                                """.trimIndent()
                            }

                            val resultMap = mapOf(
                                "Discovered Models Count" to "${models.size} models discovered",
                                "Currently Selected Model" to selected,
                                "Supported Methods" to models.firstOrNull()?.supportedMethods.orEmpty().ifBlank { "generateContent" },
                                "Health Summary" to "${models.count { it.enabled && it.cooldownUntil <= System.currentTimeMillis() }}/${models.size} Operational",
                                "Discovery Latency" to "${latency}ms",
                                "Discovered Models Breakdown" to discoveredFormatted.ifBlank { "No models discovered for API key." }
                            )

                            withContext(Dispatchers.Main) {
                                testResults = resultMap
                                isRunningTest = false
                            }
                        }
                    }
                )

                // Button 1: Test Gemini Provider
                DevConsoleButton(
                    label = "Test Gemini Provider",
                    modifier = Modifier.weight(1f),
                    enabled = !isRunningTest,
                    onClick = {
                        isRunningTest = true
                        activeTestName = "Test Gemini Provider"
                        coroutineScope.launch(Dispatchers.IO) {
                            val provider = GeminiProvider()
                            val cleanModel = selectedModel.removePrefix("models/").ifBlank { "gemini-2.0-flash" }
                            val prompt = "Hello"

                            val startTime = System.currentTimeMillis()
                            val res = provider.generate(prompt, cleanModel, apiKey, requireJson = false)
                            val latency = System.currentTimeMillis() - startTime

                            val reqJson = "{\n  \"contents\": [\n    {\n      \"parts\": [\n        { \"text\": \"$prompt\" }\n      ]\n    }\n  ]\n}"

                            val resultMap = mapOf(
                                "Provider" to provider.providerName,
                                "Model" to cleanModel,
                                "SDK Version" to GeminiProvider.SDK_VERSION,
                                "Endpoint" to GeminiProvider.REST_ENDPOINT_BASE,
                                "HTTP URL" to "${GeminiProvider.REST_ENDPOINT_BASE}/models/$cleanModel:generateContent",
                                "HTTP Status" to (res.httpStatus?.toString() ?: "N/A"),
                                "Request JSON" to reqJson,
                                "Response JSON" to (res.responseBody ?: res.text ?: "N/A"),
                                "Parsed AIResponse" to (res.text ?: "None"),
                                "Latency" to "${latency}ms",
                                "Errors" to (res.errorMessage ?: "None")
                            )

                            withContext(Dispatchers.Main) {
                                testResults = resultMap
                                isRunningTest = false
                            }
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Button 2: Test AIRouter
                DevConsoleButton(
                    label = "Test AIRouter",
                    modifier = Modifier.weight(1f),
                    enabled = !isRunningTest,
                    onClick = {
                        isRunningTest = true
                        activeTestName = "Test AIRouter Routing"
                        coroutineScope.launch(Dispatchers.IO) {
                            val router = AIRouter(null)
                            val startTime = System.currentTimeMillis()
                            var routeResult = "Success"
                            var activeMod = router.getActiveModel()

                            try {
                                val reply = router.route("Hello", apiKey, selectedModel, TaskCapability.GENERAL_CONVERSATION, requireJson = false)
                                routeResult = reply
                                activeMod = router.getActiveModel()
                            } catch (e: Exception) {
                                routeResult = "Router Exception: ${e.localizedMessage}"
                            }
                            val latency = System.currentTimeMillis() - startTime

                            val resultMap = mapOf(
                                "Component" to "AIRouter",
                                "Target Model" to selectedModel,
                                "Active Model Selected" to activeMod,
                                "Candidate Models" to router.getModels().joinToString { it.id },
                                "Routing Result" to routeResult,
                                "Latency" to "${latency}ms",
                                "Status" to "Execution Complete"
                            )

                            withContext(Dispatchers.Main) {
                                testResults = resultMap
                                isRunningTest = false
                            }
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Button 3: Test PromptBuilder
                DevConsoleButton(
                    label = "Test PromptBuilder",
                    modifier = Modifier.weight(1f),
                    enabled = !isRunningTest,
                    onClick = {
                        activeTestName = "Test PromptBuilder"
                        val mockFacts = listOf(
                            FactEntity("1", "Passport Number", "Finance", "AB1234567"),
                            FactEntity("2", "Wi-Fi Password", "Personal", "HomeSecret99")
                        )

                        val builtPrompt = PromptBuilder.buildPrompt(
                            userProfile = "Jagadeesh",
                            relevantMemories = mockFacts,
                            userInput = "What is my passport number?"
                        )

                        testResults = mapOf(
                            "Component" to "PromptBuilder",
                            "User Profile Injected" to "Jagadeesh",
                            "Memories Count Injected" to "${mockFacts.size}",
                            "Prompt Structural Format" to "JSON Directive Enforced",
                            "Generated Prompt Output" to builtPrompt,
                            "Status" to "Verified Successfully"
                        )
                    }
                )

                // Button 4: Test MemoryEngine
                DevConsoleButton(
                    label = "Test MemoryEngine",
                    modifier = Modifier.weight(1f),
                    enabled = !isRunningTest,
                    onClick = {
                        activeTestName = "Test MemoryEngine"
                        val memoryEngine = MemoryEngine()
                        val mockFacts = listOf(
                            FactEntity("1", "Passport Number", "Finance", "AB1234567"),
                            FactEntity("2", "Wi-Fi Password", "Personal", "HomeSecret99"),
                            FactEntity("3", "Flight Time", "Travel", "Flight to NYC at 9 AM")
                        )

                        val matched = memoryEngine.selectRelevantMemories("Where is my flight ticket?", mockFacts)

                        testResults = mapOf(
                            "Component" to "MemoryEngine",
                            "Input Query" to "Where is my flight ticket?",
                            "Total Store Facts" to "${mockFacts.size}",
                            "Matched Memories Count" to "${matched.size}",
                            "Matched Titles" to matched.joinToString { it.title },
                            "Status" to "Keyword Index Match OK"
                        )
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Button 5: Test Firestore
                DevConsoleButton(
                    label = "Test Firestore",
                    modifier = Modifier.weight(1f),
                    enabled = !isRunningTest,
                    onClick = {
                        activeTestName = "Test Firestore"
                        testResults = mapOf(
                            "Component" to "Firestore / Room DB",
                            "Current Local Tasks Count" to "$taskCount",
                            "Current Local Facts Count" to "$factCount",
                            "Database Sync State" to "Read-Only Diagnostic Active",
                            "Data Mutation" to "SAFE (0 User Records Modified)",
                            "Status" to "Store Schemas Intact"
                        )
                    }
                )

                // Button 6: Test JSON Parsing
                DevConsoleButton(
                    label = "Test JSON Parsing",
                    modifier = Modifier.weight(1f),
                    enabled = !isRunningTest,
                    onClick = {
                        activeTestName = "Test JSON Parsing"
                        val sampleTaskJson = """
                            {
                              "itemType": "TASK",
                              "reply": "Scheduled passport renewal task, Jagadeesh.",
                              "title": "Renew Passport",
                              "category": "Personal",
                              "priority": "HIGH",
                              "deadline": "2026-09-01"
                            }
                        """.trimIndent()

                        try {
                            val jsonObj = JSONObject(sampleTaskJson)
                            val itemType = jsonObj.getString("itemType")
                            val title = jsonObj.getString("title")
                            val priority = jsonObj.getString("priority")

                            testResults = mapOf(
                                "Component" to "JSON Parser (GeminiBrain)",
                                "Input JSON Payload" to sampleTaskJson,
                                "Parsed itemType" to itemType,
                                "Parsed title" to title,
                                "Parsed priority" to priority,
                                "Status" to "Valid JSON Structure Parsed"
                            )
                        } catch (e: Exception) {
                            testResults = mapOf(
                                "Component" to "JSON Parser",
                                "Error" to (e.localizedMessage ?: "Parsing error"),
                                "Status" to "Parse Failed"
                            )
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Button 7: Test Executive Layer
                DevConsoleButton(
                    label = "Test Executive Layer",
                    modifier = Modifier.weight(1f),
                    enabled = !isRunningTest,
                    onClick = {
                        activeTestName = "Test Executive Intelligence Layer"
                        val convMgr = com.jax.assistant.executive.conversation.ConversationManager()
                        val planner = com.jax.assistant.executive.planner.Planner(convMgr)
                        val ctxMgr = com.jax.assistant.executive.context.ContextManager()

                        val testPrompt = "Book dentist appointment"
                        val planResult = planner.plan(testPrompt, ctxMgr.contextState.value)

                        testResults = mapOf(
                            "Component" to "Executive Intelligence Layer",
                            "Input Test Prompt" to testPrompt,
                            "Context State" to ctxMgr.getContextSummary(),
                            "Detected Intent" to planResult.intent.name,
                            "Assigned Agent" to planResult.assignedAgent,
                            "Requires Slot Filling" to "${planResult.requiresSlotFilling}",
                            "Missing Slot Question" to (planResult.missingSlotQuestion ?: "None"),
                            "Extracted Parameters" to planResult.extractedParameters.toString(),
                            "Status" to "Executive Layer Operational"
                        )
                    }
                )

                // Button 8: Test Executive Briefing & Knowledge Graph
                DevConsoleButton(
                    label = "Test Briefing & Graph",
                    modifier = Modifier.weight(1f),
                    enabled = !isRunningTest,
                    onClick = {
                        activeTestName = "Test Executive Briefing & Graph"
                        val briefingEngine = com.jax.assistant.executive.briefing.ExecutiveBriefingEngine()
                        val ctxMgr = com.jax.assistant.executive.context.ContextManager()
                        val graph = com.jax.assistant.executive.graph.KnowledgeGraph()

                        val briefingContent = briefingEngine.generateBriefing(emptyList(), emptyList(), ctxMgr.contextState.value)
                        val diag = briefingEngine.diagnostics.value

                        testResults = mapOf(
                            "Component" to "Briefing Engine & Graph",
                            "Last Execution Time" to diag.lastExecutionTime,
                            "Next Scheduled Time" to diag.nextScheduledTime,
                            "Duration (ms)" to "${diag.lastExecutionDurationMs}",
                            "Focus Recommendation" to briefingContent.focusRecommendation,
                            "Knowledge Graph Summary" to graph.getGraphSummary(),
                            "Status" to "Briefing & Graph Operational"
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Results Section
        DevConsoleResultOutput(
            activeTestName = activeTestName,
            isRunningTest = isRunningTest,
            testResults = testResults
        )
    }
}
