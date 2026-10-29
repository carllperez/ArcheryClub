# Archery Club — CAPSTONE 1

Kotlin/Jetpack Compose Android application with Supabase Auth, PostgreSQL, private storage,
and a browser companion for applicants and partner representatives.

The approved proposal is the authoritative specification. **All 15 modules are in scope**.
The earlier README's officer/interclub deferral and separate sample identities are superseded.
The user approved Kotlin/Compose + Supabase as the technology exception to React/Prisma.

## Start here

- [Group setup and integration notes](docs/team-setup.md)
- [Group contribution integration history](docs/group-contribution-integration.md)
- [Setup and deployment](docs/backend-setup.md)
- [Hosted deployment evidence and remaining checks](docs/hosted-deployment.md)
- [Exact module requirements and verification matrix](docs/requirements-matrix.md)
- [Mac presentation and later phone setup](docs/presentation-demo.md)

Open this directory in Android Studio with its bundled JDK and Android SDK 37.
Copy `backend.properties.example` to ignored `backend.properties` and supply the development
URL and publishable key. Build with `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
The debug APK is `app/build/outputs/apk/debug/app-debug.apk`, labelled **Archery Club Dev**.

The group's M5/M7/M9/M10/M12/M13 layouts are integrated into **Archery Club Dev**.
Sign in and open the modules allowed by your role. Cards, filters, reviews and exports
use the existing authenticated backend workflows. There is no separate demo launcher.

Both app variants now open the connected system. Users sign in with real Supabase accounts.
Membership approval preserves the applicant's account identity. Role assignments and protected
transitions are enforced by database functions and row-level permissions.

## Proposal modules

1. Member Profile and Status
2. Applicant Submission and Screening Status
3. Activity Information and Registration
4. Personal Participation and Training Records
5. Member Operations and Equipment Borrowing
6. Member Dashboard and Personal Monitoring
7. Membership and Applicant Processing
8. Announcement, Event, Registration, and Attendance Management
9. Election Management
10. Equipment Inventory and Allocation Support
11. Training Performance Monitoring and Feedback
12. Financial Record Verification and Tracking
13. Reports, Dashboard Analytics, and Goal Monitoring
14. Cross-Layer System Administration and Role-Based Access Control
15. Shared Interclub Event Coordination

## Source map

| Location | Purpose |
|---|---|
| `app/src/main/java/ph/capstone/archeryclub/connected/` | Connected Compose screens, forms, session state and Supabase gateway |
| `tools/generate_catalog.py` | Shared exact module/form catalogue |
| `app/src/main/assets/modules.json` | Generated Android catalogue |
| `portal/` | Responsive applicant and partner browser interface |
| `supabase/migrations/` | Database tables, constraints, permissions and workflow functions |
| `supabase/functions/verify-training-video/` | Server-side MP4 duration verification |
| `supabase/bootstrap-admin.sql` | Trusted initial administrator setup |
| `backend-tests/` | Database workflow/security and video-parser tests |
| `app/src/test/` | Connected input/CSV regression tests |
| `app/src/main/java/ph/capstone/archeryclub/connected/ClubModuleScreens.kt` | Integrated borrowing, officer queues, inventory, election and report controls |

The unused local preview and separate officer prototype were removed on 28 October. Their
integrated connected features remain in the normal app; historical source is available in Git.
Everything under the parent project's `sources/` remains read-only.

## Verification and limits — 28 October 2026

The existing hosted development project has **15 versioned migrations** and both server
functions. Five approved test accounts passed online sign-in, refresh, fresh-session
persistence and role-boundary checks. Announcement updates and stale election edits passed
hosted regression tests. Backend tests cover the fifteen modules' protected workflows;
the matrix distinguishes these checks from device and stakeholder acceptance.

The app uses the hosted backend by default. `-PlocalBackend=true` is an isolated debug-only
option. Release builds reject local or placeholder backend configuration. Build both variants
with `./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:lintRelease`.
The debug APK is installable; the optimized release APK remains unsigned until the owner
configures a release signing key. No public app release or portal hosting is authorized.

Email signup, recovery and invitations are **disabled in the clients** (`ENABLE_EMAIL=false`,
portal `emailEnabled:false`) at the owner's request. Public signup is closed on the hosted development backend; existing users still sign in.
The invitation function also requires server-side `ARCHERY_EMAIL_ENABLED=true` before any
email can be sent (currently false). Hosted email confirmation is not currently enforced,
so provisioned test identities are not proof of email ownership. SMTP and the hosted Auth/redirect settings must be reviewed together before
public signup is enabled. Account passwords were not changed.

Club rules remain awaiting validation. Full device/portal workflows, real camera MP4
verification, physical-phone testing and proposal-required human UAT/SUS remain outstanding.
Android Studio UI inspection timed out during the October 28 verification attempt; the latest
build's device checks are not claimed. The current result is a tested development build,
not final production or stakeholder acceptance.

Repeatable checks (after installing the locked backend-test dependencies):

```sh
python3 tools/generate_catalog.py --check
(cd backend-tests && node --test workflow.test.mjs video.test.mjs portal.test.mjs account.test.mjs)
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:lintRelease
```

Hosted checks use owner-approved private test-account input and write evidence under ignored
`build/verification/`. Do not commit credentials or rerun initial database bootstrap on the
shared project. Synthetic hosted records are explicitly labelled QA and are not club approvals.
