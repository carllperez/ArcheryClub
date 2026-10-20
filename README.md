# Archery Club — CAPSTONE 1

Kotlin/Jetpack Compose Android application with Supabase Auth, PostgreSQL, private storage,
and a browser companion for applicants and partner representatives.

The approved proposal is the authoritative specification. **All 15 modules are in scope**.
The earlier README's officer/interclub deferral and separate sample identities are superseded.
The user approved Kotlin/Compose + Supabase as the technology exception to React/Prisma.

## Start here

- [New groupmate overview — October 20](docs/team-handoff-2026-10-20.md)
- [Owners, checklist and schedule before Friday October 23](docs/finish-before-friday-2026-10-23.md)

- [Group setup and integration notes](docs/team-setup.md)
- [October 16 contribution integration and officer demo](docs/group-contribution-integration.md)
- [Setup and deployment](docs/backend-setup.md)
- [Hosted deployment evidence and remaining checks](docs/hosted-deployment.md)
- [Exact module requirements and verification matrix](docs/requirements-matrix.md)
- [Current checkpoint and remaining work](docs/progress-checkpoint.md)
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
| `app/src/debug/java/ph/capstone/archeryclub/prototype/` | Historical fictional prototype reference; not registered as an Activity |
| `app/src/testDebug/` | Local-preview and officer-demo regression tests |

The earlier preview domain and repository code is retained for reference; it is not used by
the connected entry point. Everything under the parent project's `sources/` remains read-only.

## Verification and limits

The connected Android build and automated checks have passed during development. Local
database tests cover key successful and denied workflows across the proposal modules.
Consult the matrix for test coverage and unresolved criteria.

**Hosted status last verified October 16, 2026:** thirteen migrations are deployed,
including workflow completion migration 013. Both server functions are installed. Five
owner-approved demo accounts passed login, refresh and role-boundary checks. Additional
live tests passed borrowing, attendance, training validation, receipt approvals, report
totals and goal calculations. Full Android workflows are not yet accepted.

Open findings: signup email confirmation was observed **disabled** and its correction is
unapplied; the post-deployment election test timed out; the corrected interclub test sequence
has not been rerun. SMTP delivery/recovery, device workflows, portal deployment, human
UAT/SUS and club-rule validation remain outstanding. Email setup and portal hosting are
still deferred by the owner. See the October 20 handoff for precise evidence and priorities.

The normal Android app connects to the hosted backend; local fallback uses a separate build.
Demo identities do not establish real email ownership or screened club membership.

This repository is a development implementation in progress, not a claim of production readiness.
