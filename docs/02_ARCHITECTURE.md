UI

↓

ViewModel

↓

Executive Orchestrator

↓

Tools

↓

Repositories

↓

Room

↓

Firebase

↓

Gemini

===============================

# Actual implementation (2026-08)

Compose UI
   ↓
MainViewModel (StateFlows)
   ↓
JaxRepository (thin facade)
   ↓
Feature repositories: Task · Memory · Notes · Goal · Project · Habit · Ai · UserPreferences
   ↓
ServiceLocator (manual DI, lazy singletons — Hilt deferred)
   ↓
Room (jax_room_db, v10, migrations 1→10)

AI path:
MainViewModel → AiRepository → AIService → GeminiBrain
   → PromptBuilder (slots + memory + multi-turn)
   → AIRouter (model discovery + ranking + health; 404 disable, 429 cooldown)
   → Gemini provider (SDK + REST fallback) → Gemini API

Background: WorkManager — DailyAgentWorker (9 AM) · WeeklyReviewWorker (Sun 6 PM)
Voice: VoiceManager — SpeechRecognizer (STT) + TextToSpeech (TTS) + hands-free loop
Device: DeviceCommandParser → DeviceController (Android intents)
Config: config/AppConfig.kt (single place for tunables)
Firebase: Google Auth + user-scoped Firestore mirror.

Note: Android debug Gradle build is clean on Java 21; device behavior is not yet verified.
