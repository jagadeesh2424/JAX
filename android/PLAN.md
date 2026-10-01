# JAX — Build Plan & Status

> Jarvis-style personal AI assistant. Native Android (Kotlin + Jetpack Compose).
> Package: `com.jax.assistant` · App module: `android/app`

This file tracks **what is built, what is deferred, and the order of remaining work**.

---

## Status at a glance

| Phase | Feature area | Status |
|-------|--------------|--------|
| 0 | Architecture foundation (repo split, manual DI, Room migrations) | ✅ Done |
| 1 | Conversational core (multi-turn context, memory, voice STT/TTS) | ✅ Done |
| 2 | Executive Intelligence (goals, projects, AI daily plan, weekly review) | ✅ Done |
| 3 | Personal Knowledge System (block notes, tags, search) | ✅ Done |
| 4 | Personal Intelligence (habit tracking + streaks, typed life categories) | ✅ Done |
| 5 | Voice Assistant (hands-free mode, wake-phrase, listening UI) | ✅ Done |
| 6 | Personal OS (app launcher + device commands) | ✅ Done |
| + | Knowledge-graph edges (page↔page links) | ✅ Done |
| 7 | Agent Reliability & Autonomy (durable exec, memory fusion, permissions, planning, proactivity, Live reliability) | 🟡 Static clean, needs device test |
| 8 | Stability review: build blockers, crash fixes, 3-way sync, DB v11 | 🟡 Needs Gradle build + device test |
| 9–10 | Reliable personal agent: pipeline, tools, planning, web research, working memory (DB v13) | 🟡 Not compiled here |
| 11 | v2 steps 0–1: CI build, schema export, Firebase AI Logic / Gemini API backends, native tools, streaming, tokens | 🟡 Not compiled here |

> ✅ **Android debug APK builds with Gradle 8.5, Java 21, and Kotlin 1.9.22.**
> Device validation and Firebase sync testing remain pending.
> 🟡 **Phase 7 (Agent Reliability & Autonomy) is static-analysis clean but NOT YET Gradle-built or device-tested** — see below.

---

## What each phase delivered

**Phase 0 — Foundation**
- `ServiceLocator` manual DI, `JaxRepository` facade over feature repositories.
- Room DB (`jax_room_db`), migrations 1→6.

**Phase 1 — Conversational core**
- Rolling multi-turn context sent to Gemini; memory facts injected.
- `VoiceManager` (SpeechRecognizer STT + TextToSpeech TTS), runtime mic permission.

**Phase 2 — Executive Intelligence**
- Goals + Projects (Room), `ExecutiveDashboardScreen` (tab 7, opened from the top-bar dashboard icon).
- AI "Plan My Day"; `WeeklyReviewWorker` (Sunday 6 PM notification).

**Phase 3 — Knowledge System**
- Block editor notes (text/heading/bullet/checklist/code/quote/divider), tags, LIKE-based search.

**Phase 4 — Personal Intelligence**
- Habit tracking with streaks (`HabitEntity`), typed life-category filters in Memory Vault.

**Phase 5 — Voice Assistant**
- Hands-free conversation mode (mic auto-reopens after JAX speaks), wake-phrase stripping ("Hey JAX …"),
  pulsing mic + "Listening…" banner.

**Phase 6 — Personal OS**
- Device commands from chat/voice: open app, web search, dial, navigate, set alarm/timer, open camera/settings.
  See `device/DeviceCommandParser` + `device/DeviceController`.

**Knowledge-graph edges (follow-up)**
- Link any note page to related pages (`page_links` table). Open a page → "Related pages" strip shows
  linked pages as chips (tap to jump, × to unlink) plus a **+ Link** picker. Bidirectional, deduped.
  See `db/PageLinkEntity`, `NoteDao.getRelatedPages`, `NotesRepository.linkPages`.

**Phase 7 — Agent Reliability & Autonomy** (2026-08-31 → 2026-09-01, static-analysis clean; needs Gradle/device validation)
Six-priority hardening pass over the agent loop, memory, and voice reliability. No DB migrations.
1. **Durable agent execution** — `DurableEventSink` persists agent events to Room at each checkpoint (was in-memory, lost on crash). `AgentRunRepository.reconcileInterruptedRuns()` marks orphaned RUNNING runs INTERRUPTED at startup. Per-run duplicate-tool guard (stable signature) skips repeated identical tool+args. Transient tool exceptions retried (2 attempts). *Not done:* replay/resume of an interrupted run; post-action verification read-back for HIGH-risk tools.
2. **Strong memory retrieval** — `MemoryEngine` now does whole-word tokenized scoring (fixed a substring bug where "cat" matched "category") weighting title/category above details. `JaxRepository.selectRelevantFactsHybrid` fuses semantic (embeddings) + lexical rankings via Reciprocal Rank Fusion instead of a naive list union.
3. **Tool & permission system** — `ToolRisk{READ,LOW_WRITE,SENSITIVE,DESTRUCTIVE}` on every `JaxTool`. `AgentController.authorize()` returns ALLOW/CONFIRM/DENY; SENSITIVE/DESTRUCTIVE tools pause the agent loop and show an Allow/Deny dialog (`MainScreen` + `MainViewModel.pendingConfirmation`) before executing. `ToolRegistry.toolsJsonSchema()` exports an MCP `tools/list`-shaped catalog.
4. **Agent planning & recovery** — the model can emit `{"action":"plan","steps":[...]}` once before executing; the plan is echoed back into every subsequent prompt. After 2 consecutive tool failures the loop injects a `REPLAN NOTICE` telling the model to pick a different tool or finalize instead of grinding to `maxSteps`.
5. **Proactive JAX autonomy ladder** — `AutonomyLevel{OFF,NOTIFY,RECOMMEND,ASK,ACT}` + `ProactiveInsightEngine.suggest()` returns structured suggestions gated by level. Persisted preference, default NOTIFY (current behavior unchanged). *Not done:* no UI toggle yet, no auto-execution at ACT, `DailyAgentWorker` still uses the older `dailyBriefing()` text path.
6. **Gemini Live reliability** — explicit `ConnectionState` lifecycle (DISCONNECTED/CONNECTING/CONNECTED/RECONNECTING/ERROR) and exponential-backoff reconnect (250ms → ×1.5 → cap 10s) on server-initiated disconnects in `GeminiLiveVoiceProvider`.

Tests added throughout `AiLayerTest.kt` for each item above (duplicate-tool skip, retry, RRF fusion, whole-word scoring, confirm/decline/allow flows, MCP schema export, plan-then-execute, fallback-after-failure, replan-directive, autonomy levels, Live provider start/stop).

**⚠️ Not yet validated:** everything in Phase 7 is static-analysis clean (VS Code diagnostics) only. It has **not** been built with Gradle or run on a device/emulator. Run `./gradlew assembleDebug test` and exercise: a normal chat tool call, a SENSITIVE tool (e.g. "navigate to ...") to see the confirm dialog, a DESTRUCTIVE tool (e.g. delete a task) to see it blocked on decline, and a multi-step request to see planning in the chat's underlying event log (Developer Console → agent runs).

**Phase 8 — Stability review (2026-09-29)** — full-codebase audit after on-device crashes. **DB v10 → v11.**
- *Build blockers fixed:* stray `}` in `MainActivity.startVoiceInput()` closed the class early; `topK = 40f` (SDK expects `Int`) in `AIProvider`; `ContextAssembler.buildFast()` passed entities where strings were expected.
- *Crashes fixed:* duplicate chat `LazyColumn` keys (ids were millisecond timestamps + a fixed greeting id) → UUID ids + monotonic timestamps; Room schema-hash mismatch on `fact_embeddings` (redundant index added without a version bump) → removed index + `MIGRATION_10_11` + `fallbackToDestructiveMigrationOnDowngrade`; `ConcurrentModificationException` in `AIRouter` (health check vs. discovery) → copy-on-write list + discovery mutex; voice objects accessed before `setContent` composed (activity-result callbacks after process restart) → created in `onCreate`; any uncaught ViewModel error → `launchSafely` + toast notice.
- *Data bugs fixed:* Firebase sync only uploaded *new* records, then `restore` overwrote local edits with stale cloud copies → rewritten as a per-record three-way merge (local / remote / last-synced fingerprint) that propagates edits **and deletions** both ways; Memory Vault search replaced the facts the agent saw; "mark X done" replied without completing anything; agent runs that threw stayed RUNNING forever (now FAILED); notes saved on every keystroke with a stale block copy (now debounced, column-level updates).
- *Reliability:* `CancellationException` no longer swallowed (agent loop, provider, brain, ViewModel); chat turns serialized so a pending confirmation can't be orphaned; Gemini Live failures now reset to a restartable state, retry up to 5× with backoff that resets on success, and marshal SDK callbacks to the main thread; `POST_NOTIFICATIONS` requested on Android 13+.
- *Features finished:* Proactivity selector in Settings (Off / Notify / Recommend) now drives the daily briefing; chat auto-scrolls and shows a "thinking" indicator; agent routing uses whole-word verbs ("address" no longer triggers "add").
- *Tests:* real `org.json` on the unit-test classpath (Android stubs silently returned defaults); removed two JVM-unsafe Live tests; added backoff and fast-context tests; fixed a wrong suggestion-count assertion.
- **Validate on device:** install **over the existing app** (exercises `MIGRATION_10_11` + first three-way sync), then: chat several quick messages + "New Chat" (key crash), edit a task on device A and sync to B, delete a task and sync, Developer Console health check while chatting, rotate during a voice turn, toggle Proactivity and "Run briefing now".

**Phase 9 — Reliable personal agent (2026-09-30)** — incremental, no dependency/Gradle/schema changes (DB stays v11).
- *Pipeline:* every typed/spoken request → `InputNormalizer` → `FastIntentRouter` → Direct tool (0 Gemini calls) | Capabilities (0) | Chat (1) | Agent loop | Planner (2). `RequestPipeline` logs `requestId/intent/route/tools/geminiCalls/embeddingCalls/geminiMs/toolMs/totalMs/success` (no user text).
- *Tools:* `ToolRegistry` is the single capability system (READ vs ACTION, `describeCapabilities()`). New: `get_datetime`, `get_weather` (live Open-Meteo, no key), `create_reminder` (task + WorkManager notification), `open_camera`, `open_settings`. Device tools now report real launch failures.
- *Controller:* `ToolExecutor` is the only path to a tool: input validation → memory policy → risk gate → timeout (20s) → bounded retries (max 2; writes never retried on business errors or contradicted verification) → independent `verify()`. HIGH risk (SENSITIVE/DESTRUCTIVE) always needs confirmation; args like `"confirmed":true` are ignored. Maps/dialer are LOW (`LAUNCH`); deleting a task is HIGH, completing it MEDIUM.
- *State:* `WorkingMemory` (in-session goal/slots/notes, cleared on New Chat) → `ContextAssembler`. `MemoryPolicy`: only explicit user facts become durable memory (tool and chat paths); episodic = completed runs; procedural = `WorkflowLearner` (evidence counted once per run).
- *Planning/recovery:* `Planner` + `AgentOrchestrator.runPlanned` — structured steps (id/tool/input/dependsOn/status/result/error/retryCount), dependency-aware skipping, duplicate-step guard, deterministic summary if Gemini fails; malformed plan → existing agent loop. Plan JSON checkpointed in `agent_runs.plan`; on restart the user is told the exact step a run stopped at (no auto-replay).
- *Proactive:* suggestions carry confidence/importance/urgency/reason/dedupKey/cooldown; `InsightGate` filters repeats/low confidence (max 3); rain-vs-due-tasks insight; phrased as "Would you like…", never executed.
- *Tests:* `AgentPipelineTest` (37 tests) + existing suites. **Not compiled or run here (no JDK)** — run `./gradlew testDebugUnitTest assembleDebug`.

**Phase 9.1 — Focused corrections (2026-09-30)** — no refactor, no Gradle/schema changes.
- *Plan dependencies:* `Planner.parse` keeps forward/out-of-order `dependsOn` (was silently dropped); blank ids → `auto-N`; duplicate ids skipped as "invalid step id". `AgentPlan.markUnresolvableSteps()` skips missing and circular dependencies. `AgentOrchestrator.executePlan()` runs the first PENDING step whose dependencies are all COMPLETED (bounded by step count, always terminates); failed/skipped dependencies propagate as SKIPPED with a reason; COMPLETED steps are never re-run.
- *Verification states:* `PlanStep.verification` + `ToolOutcome.verificationStatus` keep VERIFIED / UNVERIFIED / FAILED distinct in traces (`tool:VERIFIED`), plan summaries (`COMPLETED/UNVERIFIED`), synthesis/agent prompts, direct replies (`userMessage()`), and the no-Gemini summary.
- *Fewer Gemini calls:* `TaskParser` sends "Create a task to X [day]" and "Remind me to X <day>" straight to `create_task` (0 calls); anything with judgement words (decide/which/should/best/…) or a question still goes to Gemini/planner. Route labels: DIRECT_TOOL / DIRECT / GEMINI / AGENT / PLANNER; log line has `requestId intent route geminiCallCount toolsUsed … totalLatencyMs`.
- *Retry safety:* exceptions/timeouts are retried only for READ tools; a write that threw is reported "not retried". Any step that actually ran claims its tool+input, so an identical later step is skipped as a duplicate.
- *Tests:* +18 in `AgentPipelineTest` (routing, log line, dependency order/missing/circular/completed, verification states, write-once, bounded read retry). **Not compiled or run here (no JDK).**

**Phase 10 — Reliable personal agent, continued (2026-09-30)** — no dependency/Gradle changes. **DB v12 → v13** (`MIGRATION_12_13`: `agent_runs.updatedAt`; `facts.memoryType/confidence/source/updatedAt`).
- *Web research:* `web_search` (READ, DuckDuckGo HTML → Wikipedia fallback, returns titles/URLs/snippets) and `web_fetch` (READ, SSRF-guarded, HTML→text, deterministic passage extraction). The old browser launcher is now `open_web_search` (weather fallback). "Search the web for/Find X" → result list, 0 Gemini. "Research/latest/compare/X vs Y" → `WebResearcher`: ≤2 searches, ≤3 pages (one per site), 1 Gemini call; answer split into "From the sources" / "JAX's inference"; `Sources:` appended from retrieved data only; web text marked untrusted in the prompt.
- *Working memory + entities:* `WorkingMemory` now tracks typed entities (person/place/task/reminder/project/date/time/app/document/website/item) with source, timestamp, confidence and recency-decayed relevance; last results (candidates), topic, last intent/args, pending clarification; TTLs (entities 30 min, pending 5 min, session 2 h). `EntityExtractor` (user words) + `ConversationTracker` (tool results) feed it. `ReferenceResolver` runs in `RequestPipeline` before routing: "there", "it/that", "make it 6 PM", "the second one", "which ones are near X", "how about tomorrow". Missing/ambiguous referent → clarification question, never a guess.
- *Ask → Act → Verify:* `Route.Clarify` ("Remind me tomorrow." → "What time tomorrow?"; answer completes the reminder). New `reschedule_reminder` tool (same task id → notification replaced, not duplicated).
- *Persistent runs:* statuses CREATED → PLANNING → RUNNING ⇄ WAITING (HIGH-risk approval) → COMPLETED/COMPLETED_WITH_ERRORS/FAILED; CANCELLED on coroutine cancellation; INTERRUPTED after process death. "resume" → `RunResumer` (reads repeat; writes repeat only if `JaxTool.alreadyDone` says no; unknown → skipped) → `AgentOrchestrator.resumePlanned`.
- *Memory:* `MemoryType` SEMANTIC/EPISODIC/PROCEDURAL; `MemoryRepository.upsertFact` dedupes by title similarity and updates stale facts; `MemoryEngine.rankForContext` = relevance (semantic or keyword) + recency + confidence, irrelevant facts excluded (no more padding with random facts).
- *Depth:* `Route.depth` 0 local / 1 network tool / 2 one Gemini call / 3 planner-agent; weather now parses location + today/tomorrow.
- *Proactive:* due-tomorrow and trip-planned-without-weather insights (reason + confidence + recommended action; gated by `InsightGate`).
- *Notifications:* `notify/NotificationPolicy` (pure: dedupe window, update-in-place by key, preferences) + `NotificationDispatcher` (only Android poster); reminders and daily briefing use it.
- *Voice:* same pipeline for text and voice; `VoiceRecovery` (one offline retry on network errors), `VoiceModeSelector` (Live → device STT for 5 min after a Live failure, hands-free stays on), barge-in, mic released in `onStop`, `VoiceLatencyTracker` log line.
- *Observability:* `[Request]` line adds depth, toolCalls, verification, errorType, fallback; `RequestMetrics` logs `[Metrics]` every 20 requests (avg/p95 latency, Gemini/tools per request, failure/fallback rate, research and voice latency).
- *Tests:* +20 in `AgentPipelineTest`, new `ContextAndServicesTest` (22). **Not compiled or run here (no JDK).** Validate on device: install over the existing app (exercises `MIGRATION_12_13`), then try the follow-up flows above, a research question, and "resume" after force-stopping mid-plan.

**Phase 11 (v2 plan, steps 0–1) — Build safety + model layer (2026-09-30)** — no dependency changes, no schema change (DB stays v13).
- *Build safety (v2 Phase 0):* `.github/workflows/build_apk.yml` runs `testDebugUnitTest` + `assembleDebug` (JDK 21, Gradle 8.5) and uploads the APK, test reports and Room schemas. Room `exportSchema = true` with KSP `room.schemaLocation` → `android/app/schemas/` (commit these so future migrations can be tested). `.gitignore` covers Android build output and keystores.
- *Model layer:* `ModelClient` with two backends: `FirebaseAiClient` (Firebase AI Logic: the Gemini key stays in the Firebase project, not on the phone) and `GeminiRestClient` (your own key, sent in the `x-goog-api-key` header rather than the URL). Settings → **AI BACKEND**: Auto / Firebase / API key. Auto uses Firebase and switches to the key for the session if Firebase AI Logic isn't enabled. `AIRouter.routeRequest` keeps model health, cooldowns and bounded fallback; key or backend errors fail fast and no longer count against a model's health.
- *Native function calling:* with the Gemini API path, chat and agent requests use Gemini tool declarations (`AgentOrchestrator.runNative`). Every call still goes through `ToolExecutor`, with the same validation, memory policy, risk gate, verification and duplicate guard. Chat is offered tools whose default use isn't HIGH risk. On the Firebase path the JSON tool protocol is used.
- *Chat safety:* the single-call chat brain now only proposes an action (`ChatDraft`); the pipeline runs it through `ToolExecutor`. Previously chat wrote tasks, facts and completions directly. An inferred (non-explicit) fact is kept as a working-memory note, not saved permanently.
- *Streaming + persona:* answers stream into the chat bubble as they're generated. The J.A.X. persona and rules are sent as a system instruction, and Gemini Live gets the same persona plus the current conversation context. Plan and research synthesis now use plain text, not JSON mode.
- *Observability:* token usage per request (`tokensIn`/`tokensOut` in the `[Request]` line; `tokensPerRequest`/`totalTokens` in `[Metrics]`; prompt/output tokens in request logs).
- *Tests:* new `ModelLayerTest` (wire format, streaming assembly, error mapping, backend fallback with fake clients) and +10 in `AgentPipelineTest` (native chat/agent, confirmation, duplicate and unknown tools, streaming, tokens, chat-proposed actions). **Not compiled or run here (no JDK).**
- *Setup needed:* enable **Firebase AI Logic** (Gemini Developer API) in the Firebase console for project jax-839d7, or pick "API key" in Settings.
- *Still to do:* native tools on the Firebase path and Gemini Live tool calls (both need kotlinx-serialization types); Play Integrity App Check for release builds (new dependency); embeddings still use the API key.

---

## Remaining work — do these one by one

Ordered by value ÷ risk. Items marked **(needs build)** should wait until a Gradle build works.

1. ~~**Gradle build pass** — run `./gradlew assembleDebug`, fix anything static analysis can't catch.~~ ✅ Done. *Foundational.*
2. **Durable agent recovery** *(implemented; needs device interruption test)* — running status, step checkpoints, recovery state, and Room v10 migration.
3. **FTS4 notes search** — replace LIKE search with Room FTS4 for speed/ranking. Self-contained, schema change. Still skipped; whole-word tokenized lexical scoring (Phase 7 item 2) is the interim lexical half.
3. ~~**Knowledge-graph edges** — link notes to each other; show related items.~~ ✅ Done (page↔page links).
4. **Hilt DI migration** *(deferred)* — would replace the working `ServiceLocator`. No demonstrated problem, and the Gradle plugin + KSP wiring can't be verified without a real build. Revisit only if manual DI becomes painful.
5. **Porcupine wake word** *(needs build + SDK key)* — always-on background wake word via a foreground service.
6. **Notification intelligence** — `NotificationListenerService` to summarize/act on incoming notifications (special grant).
7. **Firebase multi-device sync** *(implemented; needs device verification)* — Google Auth + user-scoped Firestore mirror.
8. ~~**ML preference learning**~~ — grounded version ✅ Done: **Insights** panel on the Executive Dashboard aggregates existing tasks/goals/habits (completion %, overdue, busiest area, goal progress, habit streaks). True embedding-based learning is still future.
9. **Phase 7 device validation** *(next, highest priority)* — build + install, then exercise: normal tool call, SENSITIVE-tool confirm dialog, DESTRUCTIVE-tool decline, multi-step plan, a forced app-kill mid-agent-run (crash recovery), and a Gemini Live session drop (auto-reconnect).
10. **Priority #1 remainder** *(deferred)* — post-action verification/read-back after HIGH-risk tool calls (e.g. re-read task list after a delete) to confirm the action actually took effect.
11. **Proactive autonomy UI** *(deferred)* — Settings toggle for `AutonomyLevel`; wiring `ProactiveInsightEngine.suggest()`'s ASK/ACT levels into `DailyAgentWorker` and the confirm-dialog flow already built for tools.

---

## Deferred decisions (why)
- **FTS4** was skipped earlier for build-safety (needs exported schema + trigger migrations). Now #2.
- **Hilt** deferred because DI wiring can't be verified without a Gradle build, and there's no demonstrated problem with `ServiceLocator`. Item #4.
- **Porcupine/Firebase** need external SDKs / config files that can't be validated here.

---

## Conventions
- New tunable values go in `config/AppConfig.kt` (see INSTRUCTIONS.md → "Changing settings").
- Every DB schema change = bump `AppDatabase` version + add a `MIGRATION_x_y`.
- Room uses **KSP** (not kapt). Prefer editing existing files over adding new ones.
