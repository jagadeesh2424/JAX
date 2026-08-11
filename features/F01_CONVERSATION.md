User

↓

Request

↓

Intent Detection

↓

Slot Filling

↓

Confirmation

↓

Action

------------------------------------------------

Status (2026-08) — 🟡 Built, verify on device

Built: multi-turn context (last 8 messages) sent to Gemini; PromptBuilder slot rules + current date.
Pending: reliable follow-up questions for missing slots (sometimes creates the task without asking When/Priority).

Code: ai/PromptBuilder.kt, ai/GeminiBrain.kt, ui/MainViewModel.kt
Config: config/AppConfig.kt (CONVERSATION_CONTEXT_TURNS)