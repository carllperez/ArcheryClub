# Group contribution integration — 16 October 2026

## Current result: integrated in the main app

The follow-up replaces the separate-demo delivery with adapted main-app screens in
`connected/ClubModuleScreens.kt`. **Archery Club Dev is the only launcher.** Sign in
normally, then select the exact proposal module allowed by the server's role permissions.
The fictional store is not instantiated by any main-app path. Its original source and
tests remain as historical reference only.

| Module | Integrated main-app behavior |
|---|---|
| M5 | Own borrowing/financial records, equipment selection, availability checks, protected requests, receipt/document detail and submission |
| M7 | Real application/renewal queues, search/status filters, membership counts, document inspection, protected screening and standing forms |
| M9 | Real election cards and status filters leading to the existing candidacy/eligibility, private ballot, turnout/results and leadership workflows |
| M10 | Real inventory, condition/hand filters, edit forms, borrowing calendar, conflict-aware availability and review/release/return forms |
| M12 | Real submission/review queues, private documents, displayed approval history, authorized office selection and protected verification |
| M13 | Server-derived goals/reports with period selection and CSV export alongside JSON; no manual fictional progress counter |

All writes still flow through `SystemViewModel.command` → `SupabaseGateway` →
`club_command`. Forms preserve record IDs and versions for protected transitions.
Borrowing choices match the record's current stage; finance office choices match
permissions and prior approvals. UI filtering supplements, never replaces, server checks.
The same-identity membership conversion, document storage and role separation use the
existing implementation and migrations. This change introduces no new schema.

Final checks: debug assembly, lint, 32 Android unit tests and 19 local backend tests
passed. The five new Android tests cover ownership, self-review/office restrictions,
borrowing transitions and exact report export. Merged-manifest checks confirm a single
main launcher. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

The adapted screens have not yet received a live-device/hosted walkthrough. Existing
migration 013 still awaits hosted deployment (including draft-election edits); SMTP
and portal hosting remain deferred. No proposal module is upgraded to fully accepted.
This follow-up is local and has not been pushed to GitHub.

## Earlier source-only merge (superseded launcher arrangement)

The integration combines the reviewed GitHub commit `2b758c2` with our existing
`704a16d` implementation and the five-file `ArcheryClub_Officer_Modules_Changes_Only`
handoff. The two contributions have separate purposes; the officer folder is not a
replacement application. Later remote commits were not fetched during this integration.

## What was retained

- GitHub history and compatible build changes: Android Gradle Plugin 9.4.1, Java 17
  daemon configuration, Foojay toolchain resolver, and placeholder-key checks.
- Earlier member-screen contributions remain available in `ui/` and `backend/`.
- All six officer/demo modules and their seven rule tests were added to debug source
  sets. Their original fictional-data/no-backend disclosures remain visible.
- The officer demo now uses the exact proposal module titles and elects President
  and Vice President. Its corresponding tests were updated with the same correction.

## Integration decisions

The normal debug and release app continue to launch `ConnectedEntry`, using the
existing authenticated Supabase gateway and proposal-wide module navigation. The
older `BackendApp` is not selected: it expects a different public-schema database.
The remote fallback that manufactured Active membership and empty lists on failed
backend reads was not retained; the historical repository keeps its prior error
handling. No competing schema was installed.

The original training-video FileProvider, camera declaration, local-backend Android
permission handling and normal **Archery Club Dev** label were preserved. The new
unhandled login-callback intent filter was not retained as a working authentication
feature. Existing connected authentication remains unchanged.

Debug builds also provide **Archery Officer Demo** in the device app drawer. This
opens the contributed M5/M7/M9/M10/M12/M13 prototype. It requires no account, uses
fictional in-memory data and cannot change club records. It is excluded from release
builds. Normal app use and live workflows belong in **Archery Club Dev**.

This is source integration, not conversion of the prototype into production screens.
Reusing its controls in the normal app still requires binding them to the existing
gateway, authorized identities, persistent records and proposal workflow states.
Do not replace the real connected workflows with `OfficerDemoStore`.

## Verification

Integrated `assembleDebug`, all **27 unit tests** (20 existing plus seven officer-demo
tests), `lintDebug` and `processReleaseMainManifest` passed. The merged manifests
confirm that the demo launcher is included only in debug and that both variants
retain the training-video provider. Both normal entry points select `ConnectedEntry`.
The installable debug APK is `app/build/outputs/apk/debug/app-debug.apk`.
No hosted database changes, device walkthrough or production acceptance is claimed.

## Remaining work

All fifteen modules retain their existing **In progress** acceptance status. The
prototype still lacks persistence, authentication, distinct actor permissions,
uploads, notifications and full proposal workflows. M13's manually incremented goal
values remain explicitly fictional. Migration 013 remains pending hosted deployment;
SMTP and portal hosting remain deferred by the user. See `requirements-matrix.md`
and `OFFICER_MODULE_DEMO_HANDOFF.md` for scope and limitations.
