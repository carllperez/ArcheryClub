# Archery Club — Android project

A new Kotlin + Jetpack Compose project for the CAPSTONE applicant and member experience.
This is a working **local development preview**, not a deployed service or a production-ready app.
No code from BasicFrontEnd was reused. Build versions match the available Android toolchain.

## Open and run

1. In Android Studio, choose **Open** and select this `ArcheryClub` directory.
2. Let Gradle sync. Use the bundled Android Studio JDK (JDK 17 or newer compatible with Gradle 9.6).
3. Select the **debug** build variant and an Android device/emulator with API 26 or later.
4. Run `app`. The launcher label is **Archery Club Preview**.
5. Choose **Explore applicant preview** or **Explore sample member**.

Build requirements: Android SDK 37, Android Gradle Plugin 9.4.0, Gradle 9.6.0,
Kotlin Compose compiler plugin 2.2.10, Compose BOM 2026.02.01.
AGP 9 uses built-in Kotlin; do not add the old `org.jetbrains.kotlin.android` plugin.
`local.properties` contains this machine's SDK location and is ignored by Git.

## Working in milestone 01

- **M2:** edit/save an application draft, validate a submission, select a local PDF/JPG/PNG,
  submit to a local Pending review state, and view a locked submitted record.
  Domain support includes under-review, incomplete, correction, approved and rejected states;
  club decisions are deliberately absent from the applicant interface.
- **M1:** view a separate sample member, update permitted contact fields, and request renewal.
  Official membership fields are read-only. A renewal request does not approve membership.
- **M6:** display the sample member's standing, pending renewal and profile shortcut.
- Device-local saved state, form validation, duplicate-action protection, unsaved-change prompts,
  light/dark themes, scrolling forms, and error feedback.

The sample member and sample applicant are independent fixtures. Switching previews is not
authentication, approval, or applicant-to-member conversion. Use fictional data only.
Documents remain on the device: only their selected URI and metadata are saved, not uploaded.
Final required application fields, supporting documents, renewal rules and editable member fields
still require club validation. Current form choices are development assumptions.

To repeat a submitted application or renewal from a clean slate, clear **Archery Club Preview**
storage in Android Settings. This deletes only the preview app's saved data.

## Structure

```text
app/src/main/java/ph/capstone/archeryclub/
  domain/        Records, statuses and form validation
  data/          Repository and persistence contracts
  ui/            Compose screens, theme and ViewModel
app/src/debug/   Sample account entry point and local preview repository/storage
app/src/release/ Service-not-connected entry point; no preview account access
app/src/testDebug/  Workflow regression tests
```

The screens use a ViewModel and repository contract. A future Supabase implementation replaces
the local repository; the app must derive the current user from an authenticated session.
The local checks are **not server-side authorization**. Sample storage is excluded from release
source sets; the release build intentionally displays a service-not-connected screen.

## Next development milestones

1. **Connected identity and membership:** Supabase development project; registration, email
   verification, login, password recovery/session lifecycle; database migrations and ownership
   policies; private document uploads; persisted applications; authoritative membership lookup.
2. **Member activities (M3):** announcements, event details, deadlines and eligible registrations.
3. **Member records (M4):** attendance/training history, personal-versus-official records and
   coach feedback, with official records read-only.
4. **Member requests (M5) and extended M6:** equipment/reimbursement submissions, attachments,
   pending actions and summaries connected to their authoritative records.

Club officer interfaces (M7–M14) and interclub coordination (M15) are deferred. Basic backend
authentication and authorization cannot be deferred once real user data is connected. Review
decisions must come from a trusted officer workflow; applicants never choose their own roles.
Before the officer interface exists, use controlled development fixtures for review decisions.

## Before deployment

- Choose the final application ID before first publication; `ph.capstone.archeryclub` is provisional.
- Agree with the team on one shared schema, account identity and status model before integration.
- Implement Supabase Auth and database/file policies, then test with two separate user accounts
  to prove neither can read or change the other's application, documents or member records.
- Enforce ownership and column restrictions in the backend, including protection of roles,
  membership status, reviewer notes and decisions. App-only checks are insufficient.
- Use only a publishable Supabase key in the app. Never bundle a secret/service-role key.
- Separate development and production projects. Keep database migrations in version control.
- Complete privacy/data-retention decisions, failure/retry testing, accessibility checks,
  device testing, signing setup and release distribution testing.
- Connect the production entry point only after the backend workflow and permissions pass testing.

## Verification

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The tests cover invalid submissions, persistence/recreation, locked statuses, correction
resubmission, protected membership fields, duplicate renewals and failed writes.
They verify the local workflow; they do not establish production backend security.

Verified on 23 September 2026: debug and unsigned release builds succeed; all 10 workflow
tests pass; Android lint reports no errors. The remaining nine lint warnings concern newer
dependency versions and a preferences-helper suggestion. Dependency versions are intentionally
pinned; preference writes explicitly check the synchronous commit result for save failures.

An emulator smoke check confirmed application draft persistence, submission and persistence
after process restart, the separate member profile, and pending renewal. The preview installed
in the emulator contains fictional test records (Taylor Sample and Alex Reyes).
The release build is a service-not-connected placeholder, not a production deployment.

References: [Android architecture](https://developer.android.com/topic/architecture/recommendations),
[Supabase Kotlin quickstart](https://supabase.com/docs/guides/getting-started/quickstarts/kotlin),
[Supabase row-level security](https://supabase.com/docs/guides/database/postgres/row-level-security).
