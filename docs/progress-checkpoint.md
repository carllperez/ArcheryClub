# Development checkpoint — 9 October 2026

## Hosted deployment — 9 October (latest)

The owner authorized Supabase CLI access and deployment, then explicitly approved recreating
the five demo accounts online with the existing supplied passwords and roles. They are tagged
as owner-provisioned test identities; confirmation is a demo fixture, not email ownership proof.
No verification or invitation email was sent. Ordinary signup still requires email confirmation.

- Project `cidxuzrzgexkejssoglk`: all 12 migrations installed through CLI migration tracking.
- `create-account` and `verify-training-video` deployed. Both enforce caller access in their handlers.
- All 42 private app tables have RLS. Authenticated clients have no direct insert/update/delete
  table privileges. Anonymous workspace access is denied. Document bucket is private, 20 MB.
- All five hosted logins, refresh/re-login persistence and exact permission sets passed.
  Non-admin role assignment, officer President approval, administrator Treasurer verification,
  partner delegate review and coordinator invitation were denied. Private storage owner access
  passed and cross-user access was denied; its temporary test file was removed.
- Hosted demo event ID: `7723c883-760e-43bd-829a-eaef30bf4280`.
  Partner club ID: `a917f5b2-f91b-4b5c-918f-b7d9c032abe1`.
- President and officer roles expire 1 November 2026 00:00 Asia/Manila. Hosted administrator
  has a one-year development assignment. Event grants remain separately revocable.
- 16 backend regression tests passed. Hosted Android assembleDebug, unit tests and lint passed.
  APK: `app/build/outputs/apk/debug/app-debug.apk`; package `ph.capstone.archeryclub.dev`.
  Preserved copy: `app/build/outputs/apk/hosted-demo/ArcheryClub-online-dev.apk`.
  On October 9 the user manually signed in on Pixel 7 API 37.2 after earlier keyboard issues.
  Visually verified Your club and M1's Test Member profile. Android Studio Stop followed by
  Run restored Your club without credentials: hosted member app-restart persistence passed.
  Left signed in. Hosted device sign-out persistence and other role workflows remain pending.
- Authenticated Data API access to `app_private` returned 406/PGRST106 (unexposed), and
  both deployed server functions rejected unauthenticated calls. Ordinary signup requires
  confirmation. Full evidence and current limitations: `docs/hosted-deployment.md`.
- Provisioning: `tools/setup_hosted_demo.mjs`; private account input removed after success.
  Read-only evidence queries: `supabase/verify-development.sql`; endpoint checks:
  `tools/check_hosted_backend.mjs`. Privileged credentials were never written to the app or repo.
- SMTP email delivery/recovery, deployed browser portal, real camera-video validation,
  complete module/device acceptance and human UAT/SUS remain unfinished. Club rules are unvalidated.

The local database remains a separate fallback. Its records do not synchronize with hosted data.
Source changes are still uncommitted/unpushed. Historical statements below about the hosted
backend being unavailable are superseded by this section.

## Historical local milestone — 7 October

**Latest: approved five-account role plan applied on 7 October.** Officer and partner accounts
were created with owner-supplied passwords. Existing admin now has President approval access;
officer has active membership plus eight operational roles, without administration/President.
Existing member now coordinates one fictional October 10 interclub event; partner access is
restricted to that event and Demonstration Partner Club. Applicant remains a draft applicant.
All five logins, exact permissions and key denied actions passed against the real local backend.
New officer/President assignments last through October 31; original admin term is unchanged.
Event grants remain until retired. Details and IDs are in `role-access-proposal.md`.
Private password input was removed; no hosted changes or external emails were made.

Account setup completed later on 7 October: the project owner's requested administrator,
test member and test applicant now exist in isolated local Supabase. Each supplied password
was verified with Auth. Administrator access is M14 administration only; the member has an
active demo membership; the applicant has a draft and no membership. Member/applicant role
assignment attempts returned permission denial and their private profile reads were scoped
to themselves. `tools/setup_local_demo_users.mjs` performs this strictly local setup; the
private password input was removed after verification, and no passwords were committed.
Local confirmation is a test fixture; hosted signup/verification/roles are still outstanding.

The user presents on Saturday, 10 October: first on this Mac using Android Studio, later
on a physical Android phone. All 15 proposal modules remain in scope and In progress.

- Twelve migrations are installed in isolated local Supabase `archery-proposal-test`
  (API 55421, database 55422). The older `ArcheryClub` project on 543xx was not modified.
- Today's real local Auth/PostgREST/Storage tests passed: login, refresh, application approval,
  same identity, persistent records, private files, concurrent registration and booking limits.
- Android 17 requires `ACCESS_LOCAL_NETWORK` for the local connection. The debug app now
  explains and requests permission before initializing Supabase. Declining it returns to the
  explanation; allowing it successfully opened **Your club** with the fictional member account.
- Device checks passed: process restart restored the signed-in member workspace; sign-out
  remained effective after another restart. Wrong-password rejection and successful repeat login
  also passed in `tools/verify_android_session.py`. The local member is left signed in on Pixel 7.
- Local `assembleDebug`, all 17 unit tests (10 legacy, 4 input, 3 auth-error) and lint passed
  after the final changes. Login errors now remain visible and use safe, actionable wording.
- The tested local APK is `app/build/outputs/apk/local-demo/ArcheryClub-local-demo.apk`.
  Its fictional credentials are saved in ignored, owner-readable `local-test-account.json`.
  The regular debug APK is rebuilt separately for the hosted configuration.
- Ordinary Android Studio **app / Run** uses the hosted configuration. Use
  `./gradlew -PlocalBackend=true :app:installDebug` for the separately installed local app,
  `ph.capstone.archeryclub.local`. Keep this Mac's isolated backend running for the demo.
- Hosted Supabase has not been deployed or verified. Prior dashboard access was blocked by an
  unavailable browser security-policy check; no alternative was used to bypass that restriction.
  A physical phone running independently requires hosted deployment and device testing.
- The chosen future hosted administrator is `carll_perez@dlsu.edu.ph`; registration,
  email verification and trusted assignment are still needed. Local fictional users are separate.
- Browser portal deployment, real video/CSV checks, complete elections/interclub workflows,
  dashboards/reports and remaining role/device acceptance checks are unfinished. Club rules
  await validation; human UAT/SUS cannot be replaced by developer tests.
- Latest source changes remain local and uncommitted/unpushed. Preserve the IDE-generated
  Gradle daemon file. Sources under the parent `sources/` remain read-only.

Next: rehearse the Mac demonstration using `presentation-demo.md`, then configure hosted
deployment and the first administrator when authorized access is available. No claim of
all-module or hosted readiness is made by this completed local sign-in milestone.

## Historical checkpoint — 1 October 2026

## Stopping point

The approved proposal has been read completely. The requirements and verification matrix is at
`docs/requirements-matrix.md`. It preserves the exact §1.4 module names and numbers M1–M15,
records the proposal's React/Prisma versus Kotlin/Compose discrepancy, and records the user's
explicit approval to retain Kotlin/Jetpack Compose Android with Supabase.

The user supplied the Supabase development project configuration:

- Project URL: `https://cidxuzrzgexkejssoglk.supabase.co`
- Client key: stored locally in ignored `backend.properties`; it is a publishable key.

The project has not yet been deployed to Supabase. Do not commit `backend.properties`, a database
password, a service-role/secret key, or any Auth token.

## Completed in the working tree

- Added eight versioned Supabase/PostgreSQL migrations under `supabase/migrations/`.
- Added RLS, explicit grants, security-definer RPC commands, optimistic versions, audit records,
  notifications, private storage policies, and role/turnover rules.
- Added operations for all fifteen proposal modules: identity and membership, applications,
  activities/attendance, elections, equipment, training, finance, reports/goals, administration,
  and event-scoped interclub coordination.
- Added a PGlite database workflow test harness under `backend-tests/`; the four initial M14/M2/M1
  permission and lifecycle tests passed locally.
- Added Supabase Kotlin SDK wiring, authenticated session state, sign-up/sign-in/recovery,
  workspace/RPC gateway, private document upload, signed downloads, and module catalogue asset.
- Added proposal traceability and setup template files. Existing local preview remains available
  only in the debug source set; live/release setup does not use fictional sample accounts.

## Immediate next actions

1. Run the migrations against the supplied Supabase project and fix any hosted PostgreSQL errors.
2. Add the required `verify-training-video` Edge Function or temporarily disable MP4 upload until
   that server-side 10-second check is deployed.
3. Compile and fix the newly added connected Compose UI, then run the full Gradle and backend tests.
4. Provision the first administrator through a trusted setup path; public signup never grants roles.
5. Register two test accounts and verify ownership, officer permissions, storage access, and restart
   persistence against the development project.
6. Only after those checks, connect the release build and push the reviewed changes to GitHub.

## Known limitations at this checkpoint

- The migrations are prepared but not hosted-verified.
- Club-specific application/renewal requirements, election rules, equipment compatibility rules,
  academic terms, and retention policy remain configurable and awaiting club validation.
- Final stakeholder UAT, SUS, pilot timing, and proposal acceptance criteria are not complete.
- The connected UI was added after the last successful Kotlin compile; compile it before the next
  milestone is called working.
