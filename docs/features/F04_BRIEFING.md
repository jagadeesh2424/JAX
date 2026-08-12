9 AM

↓

Tasks

↓

Calendar

↓

Memory

↓

Briefing

------------------------------------------------

Status (2026-08) — 🟡 Built, verify scheduling on device

DailyAgentWorker at 9 AM (delay-to-next-9AM + backoff); notification deep-links to the Briefing screen + optional TTS.
Weekly review: Sunday 6 PM (WeeklyReviewWorker).

Config: config/AppConfig.kt (DAILY_BRIEFING_HOUR/MINUTE, WEEKLY_REVIEW_DAY/HOUR/MINUTE)
Code: worker/DailyAgentWorker.kt, worker/WeeklyReviewWorker.kt, ui/screens/DailyBriefingScreen.kt