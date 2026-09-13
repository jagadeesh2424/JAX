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
