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

> ⚠️ **All phases are compile-clean via static analysis, but NOT yet Gradle-built.**
> A real `./gradlew assembleDebug` pass is the single most valuable next step (see below).

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

---

## Remaining work — do these one by one

Ordered by value ÷ risk. Items marked **(needs build)** should wait until a Gradle build works.

1. **Gradle build pass** — run `./gradlew assembleDebug`, fix anything static analysis can't catch. *Foundational.*
2. **FTS4 notes search** — replace LIKE search with Room FTS4 for speed/ranking. Self-contained, schema change.
3. ~~**Knowledge-graph edges** — link notes to each other; show related items.~~ ✅ Done (page↔page links).
4. **Hilt DI migration** *(deferred)* — would replace the working `ServiceLocator`. No demonstrated problem, and the Gradle plugin + KSP wiring can't be verified without a real build. Revisit only if manual DI becomes painful.
5. **Porcupine wake word** *(needs build + SDK key)* — always-on background wake word via a foreground service.
6. **Notification intelligence** — `NotificationListenerService` to summarize/act on incoming notifications (special grant).
7. **Firebase multi-device sync** *(needs config)* — `google-services.json` + Firestore mirror of Room data.
8. ~~**ML preference learning**~~ — grounded version ✅ Done: **Insights** panel on the Executive Dashboard aggregates existing tasks/goals/habits (completion %, overdue, busiest area, goal progress, habit streaks). True embedding-based learning is still future.

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
