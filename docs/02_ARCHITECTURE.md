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
   → RequestPipeline (local tools + memory-first gate)
   → AIRouter / ModelClient
      → Firebase AI Logic (primary)
      → Gemini REST (configured fallback)
      → Groq OpenAI-compatible REST (configured fallback)

Auto routing is capability-aware: deterministic tools and high-confidence memory facts make zero
model calls; normal chat uses Firebase → Gemini Direct → Groq. Each configured provider gets at most
one attempt per request. Retryable provider failures (429/quota, network, timeout, server, or
temporary provider unavailability) advance to the next provider; invalid requests, safety failures,
and malformed tool arguments do not create a retry loop. Groq uses `openai/gpt-oss-20b` by default,
but its model ID is stored in settings. Provider attempts record provider, model, fallback reason,
latency, token usage, success, and error category without user content or API keys.

Background: WorkManager — DailyAgentWorker (9 AM) · WeeklyReviewWorker (Sun 6 PM)
Voice: VoiceManager — SpeechRecognizer (STT) + TextToSpeech (TTS) + hands-free loop
Device: DeviceCommandParser → DeviceController (Android intents)
Config: config/AppConfig.kt (single place for tunables)
Firebase: Google Auth + user-scoped Firestore mirror.

Note: Android debug Gradle build is clean on Java 21; device behavior is not yet verified.
