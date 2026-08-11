# Feature — Device Control (Personal OS)

Purpose

Let me control the phone from chat or voice — JAX as an operating layer, not just a chatbot.

------------------------------------------------

User Story

As a user I can say/type "open Spotify", "search flights to Goa", "call 98765...", "navigate to
office", "set a timer for 10 minutes", "set alarm for 7 am", "open camera", "open settings" and
JAX performs the action.

------------------------------------------------

Architecture

Chat / Voice input
   ↓
DeviceCommandParser  (regex → DeviceCommand)
   ↓
DeviceController  (Android Intents)
   ↓
logAssistantAction + spoken confirmation

Non-command input falls through to the normal AI conversation.

------------------------------------------------

Contract

Reference Kotlin: DeviceCommand (sealed), DeviceCommandParser, DeviceController.

------------------------------------------------

Files

device/DeviceCommand.kt, device/DeviceController.kt
MainActivity.kt (handleUserInput routes command vs AI)
AndroidManifest.xml (QUERY_ALL_PACKAGES, SET_ALARM)

------------------------------------------------

Current Status — 🟡 Built, verify on device

Open app · web search · dial · navigate · set alarm · set timer · open camera · open settings.
App launch resolves label → package via the launcher activity list.

------------------------------------------------

Acceptance Criteria

Each command opens the correct system activity; unknown phrases go to normal conversation.

------------------------------------------------

Known Issues

App-name resolution depends on installed apps; QUERY_ALL_PACKAGES visibility on Android 11+.

------------------------------------------------

Future Improvements

Automation / routines. Context awareness (location/time triggers). Toggle wifi/bluetooth is
restricted on modern Android.
