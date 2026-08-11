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

Reference docs

Full engineering rules: JAX-main/android/rules.md
Build status + order of work: JAX-main/android/PLAN.md
Setup / how-to: JAX-main/android/INSTRUCTIONS.md

Build environment: edit on any OS; the real build/run is on Mac (Android Studio). "Compile-clean" is NOT "device-tested".