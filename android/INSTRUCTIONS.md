# JAX — Instructions & Developer Guide

> How to build, run, configure, and extend the JAX Android app.
> Package: `com.jax.assistant` · App module: `android/app`

---

## 1. Build & run

Requirements: **Android Studio** (Giraffe+), **JDK 17**, an emulator or device (Android 8.0 / API 26+).

**Android Studio (easiest):**
1. Open the `android/` folder as a project.
2. Let Gradle sync finish.
3. Pick a device/emulator → press **Run** ▶.

**Command line** (from `android/`):
```powershell
./gradlew.bat assembleDebug        # build the debug APK
./gradlew.bat installDebug         # build + install on the connected device
./gradlew.bat test                 # run unit tests
```
The APK lands in `app/build/outputs/apk/debug/`.

> The debug APK has been verified with a real Gradle build on Java 21. Device testing
> remains the next validation step.

---

## 2. First-time setup: Gemini API key

JAX talks to Google Gemini. Provide a key in **either** way:
- In-app: **Settings tab → API key field** (stored in `SharedPreferences` `jax_prefs`).
- Or set an environment variable `GEMINI_API_KEY` before launching.

Default model is `gemini-2.0-flash` (change in Settings, or the default in `config/AppConfig.kt`).

---

## 3. Changing settings — `config/AppConfig.kt`

**This is the one file to edit for common tweaks.** Path:
`app/src/main/java/com/jax/assistant/config/AppConfig.kt`

| Variable | What it controls |
|----------|------------------|
| `DEFAULT_MODEL` | Gemini model used until you pick another in Settings |
| `CONVERSATION_CONTEXT_TURNS` | How many recent messages are sent to the AI as context |
| `WAKE_PHRASES` | Spoken prefixes stripped before sending (e.g. "Hey JAX …") |
| `DAILY_BRIEFING_HOUR` / `_MINUTE` | When the daily briefing notification fires |
| `WEEKLY_REVIEW_DAY` / `_HOUR` / `_MINUTE` | When the weekly review notification fires |
| `DAILY_CHANNEL_ID` / `WEEKLY_CHANNEL_ID` | Notification channel IDs |
| `DATABASE_NAME` | Room database file name |

Change a value, rebuild, done — it applies everywhere.

---

## 4. Screens (bottom tabs + top bar)

| Tab | Screen | Purpose |
|-----|--------|---------|
| 0 | OmniChat | Chat + voice with JAX |
| 1 | Tasks | Task list |
| 2 | Calendar | Task deadlines by day |
| 3 | Memory Vault | Saved facts, typed life-category filters |
| 4 | Briefing | Daily briefing view (TTS) |
| 5 | Settings | API key, model, developer mode |
| 6 | Notes | Block-editor knowledge pages |
| 7 | Executive Dashboard | Goals, projects, habits, AI daily plan — open via the dashboard icon in the top bar |

---

## 5. Using voice

- Tap the **mic** in chat to speak one turn. Tap again to stop.
- Tap the **hands-free** icon (voice-wave) to enter continuous conversation mode: after JAX
  replies aloud, the mic reopens automatically for your next turn.
- You can prefix commands naturally: "**Hey JAX**, add a task" — the wake phrase is stripped.

Mic permission is requested at runtime the first time you use voice.

---

## 6. Device commands (Personal OS)

Type or say any of these — JAX runs them on-device (no AI round-trip):

| Say / type | Action |
|------------|--------|
| "open WhatsApp" / "launch Chrome" | Launch an installed app by name |
| "search for weather" / "google …" | Web search |
| "call 555 123 4567" / "dial …" | Open the dialer |
| "navigate to airport" / "take me to …" | Open Maps directions |
| "set an alarm for 7:30 am" | Create an alarm |
| "set a timer for 5 minutes" | Start a timer |
| "open camera" / "open settings" | System shortcuts |

Logic lives in `device/DeviceCommandParser` (pattern → command) and `device/DeviceController` (command → intent).
To add a command: add a case to the sealed `DeviceCommand`, a regex in the parser, and a handler in the controller.

Permissions used: `QUERY_ALL_PACKAGES` (app launcher), `SET_ALARM` (alarms/timers) — declared in `AndroidManifest.xml`.

---

## 7. Project structure (key folders under `app/src/main/java/com/jax/assistant/`)

```
ai/         Gemini provider, router, brain, model catalog
config/     AppConfig.kt  ← central settings
data/       ServiceLocator (DI), repositories, JaxRepository facade
db/         Room entities, DAOs, AppDatabase (+ migrations)
device/     DeviceCommand / DeviceController (Phase 6)
ui/         MainViewModel, screens/, theme/
voice/      VoiceManager (STT + TTS)
worker/     DailyAgentWorker (9 AM), WeeklyReviewWorker (Sun 6 PM)
MainActivity.kt   wiring, permissions, deep links
```

---

## 8. Common "how do I…"

- **Add a DB field/table:** edit the entity/DAO → bump `AppDatabase` version → add a `MIGRATION_x_y` → register it in `addMigrations(...)`. Room uses **KSP**.
- **Change the daily briefing time:** edit `DAILY_BRIEFING_HOUR` in `AppConfig.kt`.
- **Change the default AI model:** edit `DEFAULT_MODEL` in `AppConfig.kt` (or pick it in Settings at runtime).
- **Add a wake phrase:** add a lower-case string to `WAKE_PHRASES` in `AppConfig.kt`.
- **Add a new screen/tab:** add a branch in `MainScreen.kt`'s tab `when`, pass state from `MainViewModel` via `MainActivity`.

---

## 9. Manual test checklist (on device)

- [ ] Enter API key in Settings → send a chat message → JAX replies.
- [ ] Tap mic → speak → transcription appears → JAX replies aloud.
- [ ] Enable hands-free → speak → JAX replies → mic reopens automatically.
- [ ] Say "open <an installed app>" → the app launches.
- [ ] Say "set a timer for 2 minutes" → the clock app opens with the timer.
- [ ] Add a habit on the dashboard → toggle it → streak increments.
- [ ] Create a note with blocks + tags → reopen app → it persists.
