Development Rules

Build vertically.

Never over engineer.

Never rewrite stable code.

Never create placeholder implementations.

Never modify unrelated modules.

Always compile.

Always preserve backward compatibility.

Always implement complete user stories.

Always build after changes.

Always test before committing.

Feature Rules

Each feature owns:

Repository

ViewModel

UI

Tests

Never tightly couple features.

Architecture

One Executive Orchestrator.

Everything else is a Tool.

Not Agents.

Documentation

Keep documentation minimal.

Feature contracts belong in Kotlin interfaces.

Markdown explains purpose.

Code defines behavior.

Development Cycle

Read Feature Spec

↓

Implement one slice

↓

Build

↓

Run

↓

Commit

↓

Update Roadmap

------------------------------------------------

The 5 Golden Rules

1. Inspect before modifying — never assume an entity, field, interface, repository, or service exists; open the real file first.
2. Reuse before creating — inspect the existing Entity / Dao / Repository / Manager before adding a new one.
3. One slice at a time — never touch multiple unrelated subsystems in one change.
4. Build immediately — compile after every change; fix the first real Kotlin error, not the last Gradle line.
5. Preserve working code — never replace a working implementation with new architecture without a demonstrated problem.

------------------------------------------------

Feature → Files map

To change a feature, edit only its files below. Web files live under `src/`; Android files under
`android/app/src/main/java/com/jax/assistant/`. Do not modify other features' files.

| Feature | Web (src/) | Android UI | Android data/logic |
|---|---|---|---|
| F01 Conversation | components/ChatCaptureScreen.tsx | ui/screens/OmniChatScreen.kt | ai/GeminiBrain.kt, ai/AIRouter.kt, ai/PromptBuilder.kt |
| F02 Tasks | components/TaskDashboard.tsx | ui/screens/TaskDashboardScreen.kt | data/TaskRepository.kt, db/TaskDao.kt, db/TaskEntity.kt |
| F03 Calendar | components/CalendarViewScreen.tsx | ui/screens/CalendarScreen.kt, ui/screens/calendar/ | — |
| F04 Briefing | components/DailyBriefingModal.tsx | ui/screens/DailyBriefingScreen.kt | worker/DailyAgentWorker.kt |
| F05 Voice | (in ChatCaptureScreen.tsx) | — | voice/VoiceManager.kt |
| F06 Memory & Knowledge (Notes) | components/NotesScreen.tsx | ui/screens/KnowledgeWorkspaceScreen.kt, ui/screens/MemoryVaultScreen.kt | data/NotesRepository.kt, data/MemoryRepository.kt, db/NoteDao.kt, db/NotePageEntity.kt, db/NoteBlockEntity.kt, db/PageLinkEntity.kt, db/FactDao.kt, db/FactEntity.kt |
| F07 Executive Intelligence | — | ui/screens/ExecutiveDashboardScreen.kt | data/GoalRepository.kt, data/ProjectRepository.kt, data/HabitRepository.kt, worker/WeeklyReviewWorker.kt |
| F08 Device Control | — | — | device/DeviceController.kt, device/DeviceCommand.kt |

Shared (not feature-specific): web storage `src/db/roomDatabase.ts` + types `src/types.ts`;
Android `data/JaxRepository.kt` (facade), `data/ServiceLocator.kt` (DI), `db/AppDatabase.kt`.
Full per-feature specs live in `docs/features/F01…F08`.

------------------------------------------------

Reference docs

Full engineering rules: android/rules.md
Build status + order of work: android/PLAN.md
Setup / how-to: android/INSTRUCTIONS.md

Build environment: edit on any OS; the real build/run is on Mac (Android Studio). "Compile-clean" is NOT "device-tested".