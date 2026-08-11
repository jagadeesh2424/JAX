# JAX — Engineering Rules

> Hard-won rules from real failures on this project. Read before any AI-assisted change.
> Native Android (Kotlin + Jetpack Compose), package `com.jax.assistant`, module `android/app`.

---

## The 5 Golden Rules (never break these)

1. **Inspect before modifying.** Never assume an entity, field, interface, repository, or
   service exists or has a given shape. Open the real file and confirm first.
2. **Reuse before creating.** Don't add a new `Entity`, `Dao`, `Repository`, `Manager`, or
   `PromptBuilder` until you've inspected the existing one. Extend, don't duplicate.
3. **One slice at a time.** Never modify multiple unrelated subsystems in a single change.
4. **Build immediately.** Compile after every meaningful change. Fix the *first* real error, not the last.
5. **Preserve working code.** Never swap a working implementation for a new architecture without a
   demonstrated problem that requires it. Make a known-good restore point before large changes.

---

## Room / Database (highest crash risk)

- Every schema change = **bump `AppDatabase.version`** AND add a matching `MIGRATION_x_y`, AND
  register it in `addMigrations(...)`. Missing any one = runtime crash.
- The migration SQL must match the entity **exactly**, including Room's auto-generated index names.
  For `@Index("colName")` on table `t`, Room expects an index named `index_t_colName`. Create that
  exact name in the migration or the app throws `IllegalStateException: Migration didn't properly handle`
  on first launch.
- Column types: `String → TEXT`, `Int/Long/Boolean → INTEGER`, nullable = omit `NOT NULL`.
  Non-null Kotlin field = `NOT NULL` in SQL.
- Room uses **KSP**, not kapt.
- Static analysis (get_errors) does **not** validate migration-vs-entity schema. Only a real build +
  first app launch does. Always hand-check the migration against the entity.
- Current DB: `jax_room_db`, **version 6**, migrations 1→6.

## AI / Gemini layer

- **Never hardcode a single model as truth.** Model availability changes (models get retired; keys hit
  `429 RESOURCE_EXHAUSTED / limit: 0`). JAX uses runtime discovery: `AIRouter.ensureModelDiscovery()` →
  `ModelCatalog.discoverModels(apiKey)` → rank → health-track → pick best enabled.
- `AppConfig.DEFAULT_MODEL` is only a **seed/fallback**, not the active model. Discovery overrides it.
- Model ranking lives in `ModelCatalog.calculatePriority` (3.6-flash best → 2.0-flash lower). Update
  ranking there, not scattered literals.
- Router already handles **404 → disable model** and **429 → 60s cooldown → try next candidate**. Reuse
  it; don't reinvent failover.
- Correct REST shape (for reference / smoke tests):
  `POST /v1beta/models/{model}:generateContent?key=KEY` with body
  `{"contents":[{"parts":[{"text":"..."}]}]}`. Missing `contents` → "contents is not specified".
- **Never log the full API key.** Mask it in diagnostics. (Client-side key storage is a known issue to
  fix before public distribution.)
- Test the AI layer via `GeminiSmokeTest.kt` / unit tests, not full APK cycles.

## Compose / UI

- Reuse existing theme tokens only: `CyanAccent`, `GoldAccent`, `PureDark`, `SurfaceDark`, plus standard
  `Color.*`. Do **not** invent colors/resources (past failure: `Unresolved reference: DarkCardBg`).
- Icons come from `androidx.compose.material:material-icons-extended` (wildcard `Icons.Default.*`).
  If an icon won't resolve, confirm that dependency is present before adding new ones.
- Keep brace/scope balance: verify `{}`, `()`, `[]`, `when` branches, and Compose lambdas. A single
  missing `}` mis-nests every scope below it.
- Performance: add `remember` for derived/filtered lists; give `LazyColumn` items a stable
  `key = { it.id }`; keep large screens modularized into dedicated files.

## Build environment

- **Windows (VS Code)** = edit + static analysis only. Cannot run Gradle here.
- **Mac** = the real build/run environment. Keep `local.properties` (`sdk.dir=/Users/jagadeesh/...`) —
  never overwrite it with a Windows path.
- Gradle JVM compatibility: Gradle 8.5 supports Java ≤ 21. Do **not** select Java 25 as the Gradle JDK.
- `:kspDebugKotlin FAILED` usually means a **Kotlin source error**, not a Gradle problem. Scroll up to
  the first compiler error.
- Mental model: **AI Studio = drafting · Git = sync · Android Studio (Mac) = build/debug/run.**

## Workflow

- Before major AI-generated changes, create a known-good restore point (commit / AI Studio "Restore
  Version"). AI can make large changes fast.
- After copying changes to Mac: **Sync Project → clean build → run**. First verify DB migration, then
  test the feature.
- When something is "compile-clean" here, state clearly it is **not yet Gradle-built** — the Mac build
  is the source of truth.

---

## Known open items (do not assume these work)

- Voice pipeline (SpeechRecognizer → transcript → Gemini → TTS): verify on device.
- Calendar sync, 9 AM briefing scheduling, Notion-style notes UX, conversational slot filling:
  behavior not fully proven; inspect before changing.
- Memory is keyword/category matching, not semantic embeddings (future upgrade).
