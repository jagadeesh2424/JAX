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

🟡 Retrieve Memory  (keyword + category; NOT semantic yet)

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

# Deferred / needs external setup

⬜ Hilt DI  (deferred — ServiceLocator works; plugin/KSP wiring unverifiable without a build)
⬜ FTS4 notes search
⬜ Firebase multi-device sync  (needs google-services.json)
⬜ Semantic memory  (embeddings)

Next action: Gradle clean build on Mac → verify DB v6 migration + all slices → device-test Phase 1.