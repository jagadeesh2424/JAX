Voice

↓

Speech Recognition

↓

Conversation

↓

AI

↓

Speech Output

------------------------------------------------

Status (2026-08) — 🟡 Built, verify on device

SpeechRecognizer (STT) + TextToSpeech (TTS) via VoiceManager; runtime mic permission; hands-free mode
(mic reopens after JAX speaks); wake-phrase stripping ("Hey JAX …").
Pending: on-device wake word (Porcupine) — not started.

Config: config/AppConfig.kt (WAKE_PHRASES)
Code: voice/VoiceManager.kt, ui/screens/OmniChatScreen.kt, MainActivity.kt