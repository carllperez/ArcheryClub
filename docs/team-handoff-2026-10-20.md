# New groupmate handoff — 20 October 2026

Repository: https://github.com/carllperez/ArcheryClub

This is a connected development application with substantial implemented workflows,
not a finished production system. The latest live test evidence is from **16 October**;
this handoff does not claim a fresh October 20 backend or device test. All fifteen
proposal modules remain in scope and **In progress** until their acceptance checks pass.

## Project and specification

- CAPSTONE 1 Archery Club Management, following the approved 176-page proposal.
- The exact module numbering below comes from proposal section 1.4. Page references,
  roles and acceptance criteria are in [requirements-matrix.md](requirements-matrix.md).
- The owner explicitly approved Kotlin + Jetpack Compose Android + Supabase in place
  of the proposal's React/Prisma technology description. This does not remove modules.
- Android uses Supabase Auth, PostgreSQL, protected database functions and private storage.
  The `portal/` browser companion provides applicant/partner interfaces; it is **not hosted**.
- Club-specific requirements, term dates, election rules, compatibility rules and retention
  settings are configurable and still awaiting club validation.

## Work completed and integrated

1. Created the Android project and connected authentication, session handling, module
   navigation, forms, document handling and backend operations.
2. Added database tables, relationships, constraints, row-level protection and protected
   workflow commands. Privileged credentials stay out of the Android app.
3. Deployed thirteen migrations to the shared development project. The final migration,
   `202610100013_workflow_completion.sql`, was applied on October 16 after a dry-run.
   It adds draft-election edits, interclub workflow guards and source-driven notices.
4. Deployed `create-account` and `verify-training-video` server functions. Their presence
   and unauthorized-access rejection were checked; that alone does not validate successful
   invitation delivery or camera/video processing.
5. Provisioned five owner-approved demo accounts with separate access responsibilities.
6. Combined the existing app, reviewed GitHub contributions and the officer-module folder.
   The group's M5/M7/M9/M10/M12/M13 card/filter/queue layouts now use the main app's
   authenticated workspace and protected forms. There is **one normal app launcher**.
7. Preserved compatible member/announcement contributions. Historical preview code remains
   for reference; the launched app uses `connected/ConnectedApp.kt`. The alternative SQL
   in `supabase/reference/` must not be applied to the shared database.
8. Added local automated tests, live API test scripts, traceability and setup documentation.

## Module-by-module implementation and verification

“Implemented paths” means code exists. “Live” below means an API test against the real
development Supabase project, not a complete Android screen walkthrough or club acceptance.

| Exact proposal module | Implemented paths | Evidence and remaining work |
|---|---|---|
| **M1: Member Profile and Status** | Own profile, membership standing, renewal and history | Member profile/session seen on Pixel 7; ownership and fresh-login access passed live. Finish profile-edit persistence and full live renewal/review checks. |
| **M2: Applicant Submission and Screening Status** | Application drafts, submission, documents, correction and status tracking | Applicant login/ownership passed live; full lifecycle passed locally. Finish live document/validation/correction flow and browser/device tests. |
| **M3: Activity Information and Registration** | Published activities, calendar/details, registration and linked reminders | Live registration and duplicate/capacity controls passed. Test calendar, QR navigation, cancellation, deadlines and empty states on devices. |
| **M4: Personal Participation and Training Records** | Own attendance, personal scores, official feedback and correction paths | Live personal-score submission, validation and invalid-score rejection passed. Test correction/history/trends and device views. |
| **M5: Member Operations and Equipment Borrowing** | Availability, borrowing requests/statuses and reimbursement entry | Live borrowing through return and double-booking rejection passed; integrated M5 screen opened on Pixel 7. Test availability/compatibility forms, invalid dates and returned requests in UI. |
| **M6: Member Dashboard and Personal Monitoring** | Source-derived personal tasks/statuses and linked notices | Notification lifecycle passed locally; signed-in workspace opened on device. Reconcile each dashboard count and notice with live source changes. |
| **M7: Membership and Applicant Processing** | Officer queues, screening, correction/review, acceptance and renewal processing | Local applicant-to-member/renewal tests passed; hosted officer permissions passed. Full live processing and same-account conversion remain unverified. |
| **M8: Announcement, Event, Registration, and Attendance Management** | Announcement/event management, publication, registrations, attendance and corrections | Live publication, registration and duplicate-safe attendance passed. Test announcement editing, QR scanning, corrections and device workflow. |
| **M9: Election Management** | Draft rules/schedules, candidacy, eligibility, voting, results and leadership records | Local lifecycle/security tests passed. Migration 013 is deployed, but the subsequent live draft-edit test timed out. Voting, privacy, turnout, results and archive still need complete live/device checks. |
| **M10: Equipment Inventory and Allocation Support** | Inventory, condition/handedness filters, compatibility/conflict review, release/return | Live inventory creation, conflict rejection and borrowing lifecycle passed. Test inventory edits, stale edits, damaged equipment and UI permissions. |
| **M11: Training Performance Monitoring and Feedback** | Personal/coach records, validation, feedback, CSV and video paths | Live score validation and bounds passed; CSV/parser tests passed locally. Actual camera upload, 10-second server limit, CSV round trip and device feedback remain to verify. |
| **M12: Financial Record Verification and Tracking** | Payment/expense/reimbursement records, private receipts, separate approvals, verification and ledger | Live receipt ownership, required proof, President/Treasurer approvals and exact expense-total increase passed. Test correction/resubmission, fees/payment linkage and complete Android flow. No money is transferred by the app. |
| **M13: Reports, Dashboard Analytics, and Goal Monitoring** | Permission-filtered reports, date ranges, calculated goals, JSON/CSV export | Live access restrictions, finance totals and source-calculated goal actuals passed. Test report filters, empty data and exported files on devices. |
| **M14: Cross-Layer System Administration and Role-Based Access Control** | Auth/session, accounts, roles, account status, audit/turnover and recovery paths | Five logins, refresh, fresh-login permissions and unauthorized-action denials passed live. Email confirmation was found disabled; SMTP/recovery delivery, narrow-role boundaries and turnover/disabled-account device checks remain. |
| **M15: Shared Interclub Event Coordination** | Shared events, invitations/access, delegation, review, scheduling, results and archives | Hosted partner restrictions passed; full lifecycle passed locally. Live workflow stopped at a database rejection. The test was adjusted to check in a delegate before recording results but **has not been rerun**; do not call this fixed or complete. |

## Test evidence we can actually claim

- Last Android verification: debug APK assembly, lint, **32 unit tests** and manifest checks
  passed. MainActivity is the sole launcher. No new Android source changes were made for
  this October 20 documentation/push task.
- **19 local backend tests** passed, including successful flows and permission/validation cases.
- Initial hosted run: **21/23 checks passed**. Follow-up: **19/22 passed**. The runs overlap;
  do not add their totals or turn them into an overall completion percentage.
- Pixel 7: app installed/launched; prior login restored; workspace and integrated M5 screen
  opened. An initial loading issue recovered after Retry. This is limited device evidence.
- Reports: [first run](verification/hosted-workflows-latest.json) and
  [follow-up](verification/hosted-followup-before-retry.json). Each includes failures and
  synthetic record IDs. API sign-out checks do not prove Android sign-out persistence.

## Open issues and decisions

| Item | Current status / next action |
|---|---|
| Signup email confirmation | Last observed disabled, contrary to intended setup. Partial configuration is prepared in `tools/hosted-auth/supabase/config.toml`; **not applied**. Review/approve the change and verify actual Auth behavior. |
| Election timeout | Read the current draft/version before retrying: a timed-out write may have committed. Diagnose timeout versus workflow failure; rerun focused tests. |
| Interclub rejection | Inspect the failing record/stage and backend response. Corrected test sequence is unrun, not an established fix. Reconcile partial test data first. |
| Old QA schedules | October 10 and October 16 fixtures have time-limited windows. Create clearly labelled current test fixtures; do not reopen archived/official events or blindly repeat test scripts. |
| Email delivery | SMTP provider/sender not selected; signup, recovery and invitation delivery remain pending by owner instruction. |
| Browser portal | Source exists; hosting was explicitly deferred. Local review can proceed. Publishing requires the owner to change that instruction. |
| Demo role expiry | President and operational officer assignments end **November 1, 2026 at 00:00 Asia/Manila**. Event access is separately scoped. |
| Release/acceptance | Physical phone, full role walkthroughs, poor-network behavior, club-rule validation, stakeholder/UAT/SUS evidence and release preparation remain open. |

## Accounts and boundaries

Get passwords privately from Carll; none belong in GitHub or handoff screenshots.

| Demo account | Intended access |
|---|---|
| `carll_perez@dlsu.edu.ph` | Administrator plus separate President approval; no blanket operational/Treasurer access |
| `testMember@dlsu.edu.ph` | Active member and assigned-event coordinator access |
| `testApplicant@dlsu.edu.ph` | Own draft application; no membership before approval |
| `testOfficer@dlsu.edu.ph` | Active test member with eight operational responsibilities; no Administrator or President permission |
| `testPartner@dlsu.edu.ph` | Own partner delegation and designated shared events |

One person can hold multiple roles. These accounts are test fixtures, not evidence of real
club appointments or verified email ownership. Approving the applicant changes its standing;
schedule that test last or agree on another isolated fixture. Do not reverse approval merely
to restore a demo screen.

## Where to work

| File/folder | Responsibility |
|---|---|
| `app/src/main/java/ph/capstone/archeryclub/connected/ConnectedApp.kt` | Main authenticated UI, navigation and details |
| `.../connected/ClubModuleScreens.kt` | Integrated M5/M7/M9/M10/M12/M13 cards, queues and filters |
| `.../connected/WorkflowForm.kt`, `WorkflowPanels.kt` | Actions/validation, reports, elections and availability panels |
| `.../connected/SystemViewModel.kt`, `SupabaseGateway.kt` | Session state, API requests, persistence and error handling |
| `.../connected/MemberTasks.kt`, `RecordCalendar.kt`, `TrainingVideoCapture.kt` | Tasks, calendar and camera paths |
| `tools/generate_catalog.py` | Shared module/form definitions; regenerate catalogues when these change |
| `app/src/main/assets/modules.json`, `portal/modules.json` | Generated catalogues |
| `portal/` | Applicant/partner browser interface and local configuration example |
| `supabase/migrations/` | Authoritative ordered database changes; add new migrations rather than editing deployed history |
| `supabase/functions/` | Account provisioning and video validation functions |
| `app/src/test/`, `app/src/testDebug/`, `backend-tests/` | Automated tests |
| `tools/test_hosted_workflows.mjs`, `docs/verification/` | Hosted API test harness and evidence; writes require explicit demo flags |

The `.../connected/` paths above share the full prefix in the first row. Historical
`prototype/`, `ui/`, `backend/` and `supabase/reference/` content is not the normal connected
entry point. Parent `sources/` is read-only reference material.

## New groupmate setup

1. Clone the repository and open **ArcheryClub**, the directory containing `settings.gradle.kts`,
   in Android Studio. Use its bundled JDK and install Android SDK 37.
2. Copy `backend.properties.example` to ignored `backend.properties`. Get the existing
   development URL and publishable key from Carll. Never use a service-role or management key.
3. Sync Gradle, choose **app**, select Pixel 7 or an Android 8+ phone, and click **Run**.
   The app is labelled **Archery Club Dev** and needs internet for hosted Supabase.
4. Obtain one suitable demo login privately. GitHub access does not automatically grant
   Supabase dashboard access. The client URL/key plus authorized login suffice for app use.
5. Build/test with `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
   The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`; it is not committed.
6. Do not initialize/reset/redeploy the shared database. Do not run the fresh-project bundle
   or alternative reference SQL against it. Coordinate migrations with Carll.
7. Read [the pre-Friday task plan](finish-before-friday-2026-10-23.md), take a branch for
   your assigned work, and attach reproducible evidence to each fix.
