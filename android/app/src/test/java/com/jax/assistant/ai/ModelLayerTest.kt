package com.jax.assistant.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// JVM tests for the model layer: Gemini wire format, streamed responses, error mapping, and the
// router's backend choice and fallback (with fake clients, no network).
class ModelLayerTest {

    // ---------------------------------------------------------------- wire format

    private val createTask = ModelToolSpec(
        "create_task", "Create a task",
        listOf(
            ModelToolParam("title", "string", "Title", true),
            ModelToolParam("remove", "boolean", "Delete it", false),
            ModelToolParam("count", "number", "How many", false)
        )
    )

    @Test
    fun requestCarriesSystemInstructionToolsAndJsonMode() {
        val body = GeminiWire.requestBody(
            ModelRequest(
                messages = listOf(ModelMessage.user("Add milk")),
                systemInstruction = "You are J.A.X.",
                tools = listOf(createTask),
                json = true,
                temperature = 0.2
            )
        )

        val system = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0)
        assertEquals("You are J.A.X.", system.getString("text"))
        val content = body.getJSONArray("contents").getJSONObject(0)
        assertEquals("user", content.getString("role"))
        assertEquals("Add milk", content.getJSONArray("parts").getJSONObject(0).getString("text"))

        val declaration = body.getJSONArray("tools").getJSONObject(0)
            .getJSONArray("functionDeclarations").getJSONObject(0)
        assertEquals("create_task", declaration.getString("name"))
        val parameters = declaration.getJSONObject("parameters")
        assertEquals("OBJECT", parameters.getString("type"))
        val properties = parameters.getJSONObject("properties")
        assertEquals("STRING", properties.getJSONObject("title").getString("type"))
        assertEquals("BOOLEAN", properties.getJSONObject("remove").getString("type"))
        assertEquals("NUMBER", properties.getJSONObject("count").getString("type"))
        val required = parameters.getJSONArray("required")
        assertEquals(1, required.length())
        assertEquals("title", required.getString(0))

        val config = body.getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("responseMimeType"))
        assertEquals(0.2, config.getDouble("temperature"), 1e-9)
    }

    @Test
    fun plainRequestSendsOnlyTheConversation() {
        val body = GeminiWire.requestBody(ModelRequest(messages = listOf(ModelMessage.user("Hi"))))
        assertFalse(body.has("systemInstruction"))
        assertFalse(body.has("tools"))
        assertFalse(body.has("generationConfig"))
        assertFalse(GeminiWire.declaration(ModelToolSpec("get_datetime", "Time", emptyList())).has("parameters"))
    }

    @Test
    fun modelTurnIsReplayedVerbatimAndToolResultsGoBackAsFunctionResponses() {
        // The raw turn keeps parts the API needs on the next call (e.g. thought signatures).
        val raw = JSONObject(
            """{"role":"model","parts":[{"functionCall":{"name":"create_task","args":{"title":"Milk"}},"thoughtSignature":"sig-1"}]}"""
        )
        val body = GeminiWire.requestBody(
            ModelRequest(
                messages = listOf(
                    ModelMessage.user("Add milk"),
                    ModelMessage(ModelRole.MODEL, raw = raw),
                    ModelMessage.toolResults(
                        listOf(ModelFunctionResponse("create_task", JSONObject().put("status", "SUCCESS"), "call-1"))
                    )
                )
            )
        )

        val contents = body.getJSONArray("contents")
        assertEquals(3, contents.length())
        assertEquals("sig-1", contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("thoughtSignature"))
        val toolTurn = contents.getJSONObject(2)
        assertEquals("user", toolTurn.getString("role"))
        val response = toolTurn.getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("create_task", response.getString("name"))
        assertEquals("call-1", response.getString("id"))
        assertEquals("SUCCESS", response.getJSONObject("response").getString("status"))
    }

    @Test
    fun responseParsingSkipsThoughtsAndCollectsCallsAndUsage() {
        val parsed = GeminiWire.parseResponse(
            JSONObject(
                """
                {"candidates":[{"content":{"role":"model","parts":[
                    {"text":"thinking about it","thought":true},
                    {"text":"Adding it now."},
                    {"functionCall":{"id":"c1","name":"create_task","args":{"title":"Milk"}}}
                  ]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":30,"thoughtsTokenCount":10}}
                """.trimIndent()
            )
        )

        assertEquals("Adding it now.", parsed.text)
        val call = parsed.functionCalls.single()
        assertEquals("create_task", call.name)
        assertEquals("Milk", call.args.getString("title"))
        assertEquals("c1", call.id)
        assertEquals(TokenUsage(120, 40), parsed.usage)
        assertEquals("STOP", parsed.finishReason)
        // The replayable turn keeps every part, the thought included.
        assertEquals(3, parsed.modelTurn.raw!!.getJSONArray("parts").length())
    }

    @Test
    fun blockedOrEmptyResponseIsAnErrorNotAnAnswer() {
        val blocked = ModelAttempt.from(GeminiWire.parseResponse(JSONObject("""{"promptFeedback":{"blockReason":"SAFETY"}}""")))
        assertFalse(blocked.isSuccess)
        assertEquals("SAFETY", blocked.errorCode)

        assertEquals(ModelAttempt.EMPTY_RESPONSE, ModelAttempt.from(ModelResponse(text = "")).errorCode)
        assertTrue(ModelAttempt.from(ModelResponse(text = "ok")).isSuccess)
        assertTrue(ModelAttempt.from(ModelResponse(text = "", functionCalls = listOf(ModelFunctionCall("get_datetime", JSONObject())))).isSuccess)
    }

    @Test
    fun errorBodiesYieldStatusAndMessage() {
        val (code, message) = GeminiWire.parseError(
            """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded"}}""", 429
        )
        assertEquals("RESOURCE_EXHAUSTED", code)
        assertEquals("Quota exceeded", message)
        assertEquals("HTTP_502", GeminiWire.parseError("<html>Bad gateway</html>", 502).first)
        assertEquals("HTTP 500", GeminiWire.parseError("", 500).second)
    }

    @Test
    fun streamedChunksAssembleTextCallsAndUsage() {
        val stream = SseAccumulator()
        assertTrue(stream.accept("""data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Hel"}]}}]}"""))
        assertTrue(stream.accept("""data: {"candidates":[{"content":{"role":"model","parts":[{"text":"lo"}]}}]}"""))
        assertEquals("Hello", stream.text)
        assertFalse(stream.accept(""))
        assertFalse(stream.accept(": keep-alive"))
        assertFalse(stream.accept("data: not json"))
        assertFalse(
            stream.accept(
                """data: {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get_datetime","args":{}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":50,"candidatesTokenCount":5}}"""
            )
        )

        val result = stream.result()
        assertEquals("Hello", result.text)
        assertEquals("get_datetime", result.functionCalls.single().name)
        assertEquals(TokenUsage(50, 5), result.usage)
        assertEquals("STOP", result.finishReason)
    }

    @Test
    fun firebaseErrorsAreClassified() {
        class ServiceDisabledException(message: String) : Exception(message)
        class QuotaExceededException(message: String) : Exception(message)
        class PromptBlockedException(message: String) : Exception(message)

        assertEquals(ModelAttempt.BACKEND_NOT_CONFIGURED, FirebaseAiClient.classify(ServiceDisabledException("disabled")).errorCode)
        assertEquals(
            ModelAttempt.BACKEND_NOT_CONFIGURED,
            FirebaseAiClient.classify(RuntimeException("Firebase AI Logic API has not been used in project 123")).errorCode
        )
        assertEquals(429, FirebaseAiClient.classify(QuotaExceededException("too many")).httpStatus)
        assertEquals("BLOCKED", FirebaseAiClient.classify(PromptBlockedException("unsafe")).errorCode)
        assertEquals(ModelAttempt.NETWORK, FirebaseAiClient.classify(java.io.IOException("Unable to resolve host")).errorCode)
        assertEquals(408, FirebaseAiClient.classify(RuntimeException("Request timed out")).httpStatus)
        assertEquals("FIREBASE_ERROR", FirebaseAiClient.classify(IllegalStateException("odd")).errorCode)
    }

    @Test
    fun restClientNeverCallsWithoutAKey() = runBlocking<Unit> {
        val attempt = GeminiRestClient().generate(ModelRequest(listOf(ModelMessage.user("hi")), model = "gemini-2.5-flash"), "  ")
        assertEquals("MISSING_API_KEY", attempt.errorCode)
        assertEquals(401, attempt.httpStatus)
    }

    @Test
    fun tokenUsageAddsUp() {
        assertEquals(TokenUsage(3, 7), TokenUsage(1, 2) + TokenUsage(2, 5))
        assertEquals(10, TokenUsage(3, 7).total)
    }

    // ---------------------------------------------------------------- router: backend choice and fallback

    private class FakeClient(
        override val name: String,
        override val supportsTools: Boolean,
        override val requiresApiKey: Boolean,
        private val answer: (ModelRequest) -> ModelAttempt
    ) : ModelClient {
        val requests = mutableListOf<ModelRequest>()

        override suspend fun generate(request: ModelRequest, apiKey: String, onText: ((String) -> Unit)?): ModelAttempt {
            requests += request
            val attempt = answer(request)
            attempt.response?.let { onText?.invoke(it.text) }
            return attempt
        }
    }

    private fun ok(text: String, usage: TokenUsage = TokenUsage()) = ModelAttempt.from(ModelResponse(text, usage = usage))

    private val notConfigured =
        ModelAttempt(httpStatus = 403, errorCode = ModelAttempt.BACKEND_NOT_CONFIGURED, errorMessage = "disabled")

    private fun fakeRest(answer: (ModelRequest) -> ModelAttempt) = FakeClient("Gemini API", true, true, answer)

    private fun fakeFirebase(answer: (ModelRequest) -> ModelAttempt) = FakeClient("Firebase AI Logic", false, false, answer)

    private fun router(rest: ModelClient, firebase: ModelClient) =
        AIRouter(null, restClient = rest, firebaseClient = firebase, discoverModels = { emptyList() })

    private fun ask(text: String) = ModelRequest(messages = listOf(ModelMessage.user(text)))

    @Test
    fun autoFallsBackToTheApiKeyOnceWhenFirebaseIsNotSetUp() = runBlocking<Unit> {
        val firebase = fakeFirebase { notConfigured }
        val rest = fakeRest { ok("hi", TokenUsage(10, 2)) }
        val r = router(rest, firebase)
        val usage = mutableListOf<TokenUsage>()

        val first = withContext(UsageRecorder { usage += it }) { r.routeRequest(ask("hello"), apiKey = "key") }
        r.routeRequest(ask("again"), apiKey = "key")

        assertEquals("hi", first.text)
        assertEquals(1, firebase.requests.size) // not tried again once known to be unavailable
        assertEquals(2, rest.requests.size)
        assertEquals(listOf(TokenUsage(10, 2)), usage)
        assertEquals("Gemini API", r.backendName("key"))
    }

    @Test
    fun missingFirebaseSetupWithoutAKeyExplainsTheFix() = runBlocking<Unit> {
        val firebase = fakeFirebase { notConfigured }
        val rest = fakeRest { ok("unused") }

        val error = runCatching { router(rest, firebase).routeRequest(ask("hello"), apiKey = "") }.exceptionOrNull()

        assertTrue((error as AIException).error.userFriendlyMessage.contains("Firebase AI Logic is not set up"))
        assertEquals(1, firebase.requests.size) // fails fast instead of trying every model
        assertTrue(rest.requests.isEmpty())
    }

    @Test
    fun invalidKeyFailsFastWithoutBlamingTheModel() = runBlocking<Unit> {
        val rest = fakeRest { ModelAttempt(httpStatus = 400, errorCode = "INVALID_API_KEY", errorMessage = "API key not valid") }
        val r = router(rest, fakeFirebase { ok("unused") }).apply { backend = AiBackend.DIRECT }

        val error = runCatching { r.routeRequest(ask("hello"), apiKey = "bad") }.exceptionOrNull()

        assertEquals(AIError.InvalidApiKey, (error as AIException).error)
        assertEquals(1, rest.requests.size)
        assertTrue(r.getModels().all { it.failureCount == 0 })
    }

    @Test
    fun serverErrorFallsBackToAnotherModelAndCoolsTheFailedOneDown() = runBlocking<Unit> {
        var calls = 0
        val rest = fakeRest { request ->
            calls++
            if (calls == 1) ModelAttempt(httpStatus = 503, errorCode = "UNAVAILABLE", errorMessage = "overloaded")
            else ok("answer from ${request.model}")
        }
        val r = router(rest, fakeFirebase { ok("unused") }).apply { backend = AiBackend.DIRECT }

        val response = r.routeRequest(ask("hello"), apiKey = "key")

        val failedModel = rest.requests[0].model
        val usedModel = rest.requests[1].model
        assertNotEquals(failedModel, usedModel)
        assertEquals("answer from $usedModel", response.text)
        val failed = r.getModels().single { it.id == failedModel }
        assertEquals(1, failed.failureCount)
        assertTrue(failed.cooldownUntil > System.currentTimeMillis())
    }

    @Test
    fun nativeToolsNeedTheGeminiApiPath() {
        val r = router(fakeRest { ok("x") }, fakeFirebase { ok("x") })
        assertTrue(r.nativeToolsAvailable("key"))  // AUTO with a key: tool calls use the Gemini API
        assertFalse(r.nativeToolsAvailable(""))    // AUTO without a key: Firebase with the JSON tool protocol
        assertEquals("Firebase AI Logic", r.backendName("key"))
        r.backend = AiBackend.FIREBASE
        assertFalse(r.nativeToolsAvailable("key"))
    }

    @Test
    fun streamedTextReachesTheCaller() = runBlocking<Unit> {
        val r = router(fakeRest { ok("Hello there") }, fakeFirebase { ok("x") }).apply { backend = AiBackend.DIRECT }
        val seen = mutableListOf<String>()

        r.routeRequest(ask("hi"), apiKey = "key", onText = { seen += it })

        assertEquals(listOf("Hello there"), seen)
    }
}
