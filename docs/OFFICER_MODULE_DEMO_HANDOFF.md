# Officer module UI prototype handoff — M5, M7, M9, M10, M12, M13

**Historical handoff:** the instructions below describe the original standalone
prototype. The later main-app integration removed its launcher. Use Archery Club Dev
and its authenticated modules instead; see `group-contribution-integration.md`.

## IMPORTANT: This is NOT a deployed feature or a Supabase change

This handoff adds a **separate debug-only Android launcher** named **Archery Officer Demo**.
It is an interactive prototype using fictional records held in the app's memory.
It neither reads nor writes Supabase and does not need a login.

**Unchanged:** production and debug `AppEntry.kt`, normal `MainActivity`, member screens,
existing connected workflows, `backend.properties.example`, SQL migrations, database permissions,
Edge Functions, and all other server/portal sources.

**New files:**
- `app/src/debug/java/ph/capstone/archeryclub/prototype/OfficerDemoActivity.kt` — Compose UI for six modules, plus CSV export using Android's file picker.
- `app/src/debug/java/ph/capstone/archeryclub/prototype/OfficerDemoState.kt` — fictional in-memory state and validation rules.
- `app/src/testDebug/java/ph/capstone/archeryclub/prototype/OfficerDemoStoreTest.kt` — frontend-only unit tests.
- `docs/OFFICER_MODULE_DEMO_HANDOFF.md` — this document.

**Modified file:**
- `app/src/debug/AndroidManifest.xml` — registers one additional launcher Activity in **debug builds only**. Normal application entry and intent filters stay unchanged.

## Testing on Android Studio (no Supabase credentials needed)

1. Open the root folder containing `settings.gradle.kts` in Android Studio.
2. Allow Gradle to sync; use the same JDK/SDK setup that already works for the group's project.
3. Select `app` and run the **debug** build on an emulator or Android device.
4. Open the Android app drawer and tap **Archery Officer Demo** (a second launcher icon). The existing **Archery Club Dev** application icon still opens the normal app.
5. Select M5, M7, M9, M10, M12, or M13. Move between modules using `← Modules`.
6. All changes last in memory only; fully close/relaunch to reset the demo fixtures. The CSV is an explicit on-device export and contains only fictional values.

You can also run the frontend rules tests: `./gradlew :app:testDebugUnitTest` (Windows: `gradlew.bat :app:testDebugUnitTest`).

## Test scenarios

| Module | Scenario | Expected behavior |
|---|---|---|
| M10 | Filter handedness and condition; mark ready item for maintenance; add a fictional bow | List updates and M5 only offers ready compatible equipment |
| M5 | Request EQ-002 (left-handed) on Oct 17-18, 2026 | Prevented: existing approved allocation conflicts |
| M5 | Request EQ-001 (right-handed) on Oct 19, 2026, then approve another request for same item/date | Second approval prevented until first is returned |
| M7 | Approve Jamie Santos; reject Taylor Lim; submit a new fictional application | Approved member appears in directory; rejected member does not; new review appears |
| M9 | Try incomplete ballot, submit valid choices, and attempt another; close election | Rejects incomplete/duplicate ballots; displays simulated results only when closed |
| M12 | Try verification before approval, then approve and verify | Verification blocked until approval; status changes are local |
| M13 | Review KPIs, update a goal, export a CSV | Numbers update in session; generated file is explicitly fictional |

## Restrictions and integration checklist

- This is a **frontend sandbox**, not acceptance evidence for any of the six modules.
- It doesn't provide persistent records, Auth, RLS, realtime updates, actual uploads, notifications, or a real actor/role separation. Those are already handled or require integration with the group's connected backend flow.
- Date-only borrowing reservations approximate activity time slots; production allocations must use the approved event/session times, member requirements, compatible bow setups, and concurrency-safe backend rules.
- The election demo models just one fictional eligible voter. Real eligibility, anonymous/private ballot handling, tally integrity and audit logs must be enforced by the backend.
- The finance demo validates workflow order but does not make or verify actual payments, reconcile bank data, or bypass finance-role authorization.
- The reports demo's CSV is derived from local fixtures, not official data, and should never be presented as an actual club report.
- Integration decision (16 October 2026): the normal debug/release entry points retain `connected/ConnectedApp.kt`, the existing authenticated proposal-wide app. The historical `backend/BackendApp.kt` remains source for reuse only. This demo has its own debug launcher and does not replace either entry point.
- If the group approves integrating a prototype component into the real app, wire it into that agreed screen and use the existing authenticated gateway/repository. **Do not introduce a second Supabase schema or apply migrations from this prototype.**
- Keep `backend.properties`, `local.properties`, passwords and any other local configuration private; none are needed to inspect this handoff.

## Suggested merge workflow for the shared `main` branch

Please test this ZIP in a **new folder** first. Compare the four additions/one manifest change against current `main`; merge just those files (or use the companion changes-only ZIP). Ask someone who owns the shared repository to review and commit them. Do not wholesale replace an updated `main` with an older ZIP.

## Verification performed for this handoff

- Compiled `OfficerDemoState.kt` with the installed Kotlin compiler.
- Parsed the debug AndroidManifest as XML.
- Added debug unit tests for compatibility, double booking, membership approval, single voting, financial review sequence and report changes.
- **Full Android Gradle assemble and emulator/device execution were not available in this environment.** Your group should run the debug build and test the Activity before merging.

## Integration adjustments — 16 October 2026

Elected positions were corrected to President and Vice President, and module headings restored to their exact proposal names. The demo remains fictional and memory-only; this is not evidence of completed backend workflows. See `group-contribution-integration.md` for final integrated verification.
