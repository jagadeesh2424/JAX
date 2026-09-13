Legend: ⬜ not started · 🟡 built (compile-clean, device test pending) · ✅ verified on device
Note: "built" = static-analysis clean, NOT yet Gradle-built or device-tested. Mac is the source of truth.

# Phase 1

Reliable Executive Loop

Goal

Create one complete assistant workflow.

Slices

🟡 Slice 1
Conversational Task Creation  (slot-filling reliability to verify)

🟡 Slice 2
Reminder Engine  (deadline + notification workers)

🟡 Slice 3
Calendar Synchronization  (dated tasks → calendar; ISO matching fixed)

🟡 Slice 4
Executive Morning Briefing  (9 AM worker + deep-link + TTS)

🟡 Slice 5
Voice Task Creation  (STT/TTS + hands-free + wake-phrase)

-------------------------

Phase 2

Memory & Knowledge

🟡 Save Memory  (facts + typed life categories)

🟡 Retrieve Memory  (keyword + category + best-effort embeddings)

🟡 Workspace  (Notion-style block editor: pages/blocks/tags/search)

🟡 Knowledge Graph  (page ↔ page links)

🟡 AI Summary  (Plan My Day + briefing)

-------------------------

Phase 3

Executive Intelligence

🟡 Dashboard  (+ Insights panel)

🟡 Weekly Review  (Sunday 6 PM worker)

🟡 Suggestions  (Insights: completion %, overdue, busiest area, streaks)

🟡 Goals  (progress tracking)

🟡 Projects  (status cycle)

🟡 Habits  (streaks, done-today)

-------------------------

Phase 4

Personal Operating System

⬜ Wake Word  (on-device Porcupine — not started; wake-phrase stripping only)

⬜ Automation  (not started)

🟡 Device Control  (open app, search, dial, navigate, alarm/timer, camera, settings)

⬜ Context Awareness  (not started)

-------------------------

# Phase 5

Agent Reliability & Autonomy (2026-08-31 → 2026-09-01)

🟡 Durable Agent Execution  (event persistence + crash reconciliation + duplicate-tool guard + retry)

🟡 Strong Memory Retrieval  (whole-word lexical scoring + Reciprocal Rank Fusion of semantic/lexical)

🟡 Tool & Permission System  (ToolRisk taxonomy + ALLOW/CONFIRM/DENY gate + confirm dialog + MCP JSON schema)

🟡 Agent Planning & Recovery  (explicit plan step, re-plan directive after repeated tool failures)

🟡 Proactive Autonomy Ladder  (OFF/NOTIFY/RECOMMEND/ASK/ACT suggestion levels; UI wiring pending)

🟡 Gemini Live Reliability  (connection-state lifecycle + exponential-backoff reconnect)

All six items are static-analysis clean (VS Code diagnostics + new unit tests in `AiLayerTest.kt`) but
**not yet Gradle-built or device-tested**. See `android/PLAN.md` Phase 7 for detail and the device test checklist.

-------------------------

# Deferred / needs external setup

⬜ Hilt DI  (deferred — ServiceLocator works; plugin/KSP wiring unverifiable without a build)
⬜ FTS4 notes search  (whole-word lexical scoring is the interim lexical half, see Phase 5)
🟡 Firebase multi-device sync  (Google Auth + user-scoped Firestore; device verification pending)

Next action: Gradle-build + device-test Phase 5 (agent reliability/autonomy), Phase 1 conversational core, and Firebase sync; then add FTS4 notes search and the proactive-autonomy Settings UI.
