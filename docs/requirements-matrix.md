# Approved proposal requirements and verification matrix

Baseline: 1 October 2026. Source: `2528-PROPOSAL (2).pdf`, 176 PDF pages,
July 2026, “Development of a Centralized Mobile-Based Club Management System for Archery Clubs.”
All pages were reviewed, including the framework diagram, Gantt chart, survey charts,
and interviews. References below use **PDF page / printed page**; printed page = PDF page - 6.
The original document is read-only and is not copied into the source repository.

## Authority and discrepancies

- Section 1.4 is the canonical module catalogue. All fifteen modules are in scope.
- The previous README deferred M7–M15 and used disconnected sample identities. That is
  superseded by the user's full-system instruction and §3.2.1.1's same-identity conversion.
- On 1 October 2026 the user explicitly approved **Kotlin/Jetpack Compose Android + Supabase**
  as the exception to React/Prisma in §§2.6 and 3.2.3.2. PostgreSQL, Auth, RLS, storage,
  and server-side operations remain required. Native Android alone does not satisfy the
  phone/tablet/computer applicant portal in §1.4.2.1; accessible browser entry remains required.
- §3.2.1.3 calls M11 “Training Performance Documentation and Feedback”; Figure 1 calls it
  “Training Performance Monitoring and Feedback.” Use §1.4.2.5's latter title.
  Figure 1 abbreviates M13 and M15; use the full §1.4 titles below. Preserve this discrepancy.
- M4's official records are read-only (§1.4.3.4), but §§1.7.1–1.7.2 explicitly allow
  separately labelled member-entered training. Implement both, without overwriting official records.
- Literature mentions brackets and other systems' features. The explicit §1.7 limitations
  govern: no professional live-scoring/tournament platform, SMS gateway, public social site,
  medical/academic records, payment processing, or automatic institutional approval.
- Appendix interviews are requirement evidence, not permission to access accounts, publish
  contact information, or adopt historical competition rules as current validated rules.

## Status definitions

Implementation: **Absent**, **Local preview**, **In progress**, **Implemented**.
Verification: **Not run**, **Local automated**, **Device checked**, **Hosted integration checked**,
**Stakeholder accepted**. These are independent. A screen or passing build is not completion.
All acceptance criteria below additionally require invalid-input handling, permission-denial,
failure/retry behavior, persistence after restart, and relevant cross-module tests.

## Exact module catalogue and traceability

### Hosted evidence — 9 October 2026

All modules remain **In progress**. The baseline gap column below is historical; deployment
does not by itself complete any module's acceptance criteria.

| Scope | Hosted evidence | Remaining verification |
|---|---|---|
| M1/M2 | Member standing and applicant draft persist across fresh login; own private profiles only; private upload/signing allowed for owner and denied to another account | Full renewal, correction/submission and device workflows |
| M7/M8 | Officer permissions present; demo event created and published through protected RPC | Complete processing/attendance workflows on devices |
| M11 | Duration-verification function deployed; unauthenticated execution rejected; 3 parser tests pass | Real camera MP4 end-to-end verification |
| M12 | Officer cannot perform President approval; administrator cannot perform Treasurer verification | Complete hosted receipt/approval/ledger reconciliation |
| M14 | Five approved demo logins, refresh and fresh-login persistence; exact permission sets; non-admin role assignments denied; public signup confirmation remains required; Pixel 7 hosted member login and Stop/Run session persistence visually passed October 9 | Email delivery/recovery, hosted Android sign-out persistence and other role workflows, disabled/expired/turnover hosted scenarios |
| M15 | Partner/coordinator see the designated event only; partner cannot review delegates; coordinator cannot invite users | Full delegation, scheduling, substitutions, results and archive scenarios |
| Infrastructure | 12 tracked migrations, 42 RLS-protected tables, no direct authenticated table writes, private 20 MB bucket, two deployed functions; authenticated request for app_private rejected as unexposed | Broader operational and acceptance tests |
| Android | Online APK builds; 17 unit tests and lint pass; Pixel 7 API 37.2: manual member login, Your club, M1 Test Member profile retrieval, and session restoration after Android Studio Stop/Run visually passed October 9 | Earlier keyboard instability required manual entry; hosted sign-out persistence, other accounts' device workflows, profile editing/restart persistence and full module acceptance remain pending |

All 16 backend regression tests also passed on 9 October. These are separate from hosted
checks. Hosted fixtures are explicitly owner-approved tests, not verified email ownership,
screened membership or real club appointments. Source: `tools/setup_hosted_demo.mjs`,
`tools/check_hosted_backend.mjs`, `supabase/verify-development.sql` and `docs/hosted-deployment.md`.

| Module (exact §1.4 title) | Reference PDF/printed, section | Required features, roles, screens and actions | Backend records, operations and permissions | Acceptance criteria / outputs | Baseline gaps; implementation; verification |
|---|---|---|---|---|---|
| **M1: Member Profile and Status** | 25/19 §1.4.3.1; 36–40/30–34; 88/82 | Registered member: secure login, profile retrieval/edit of permitted personal fields, category/status indicators, renewal form/history. | Auth identity → profile → membership; own permitted updates; renewal submission → M7; member cannot set official standing/category. | M1-01 profile survives restart; M1-02 another user's profile inaccessible; M1-03 renewal information reaches M7 and does not approve itself; M1-04 officer decision reflected in same member. | ProfileScreen + local sample only; **Local preview / Not run for backend**. |
| **M2: Applicant Submission and Screening Status** | 25/19 §1.4.3.2; 19/13; 88/82 | Applicant: responsive form, required-field validation, requirement uploads, draft/submission, correction notices, pending/under review/incomplete/returned for correction/approved/rejected tracking, automatic notifications. | Own application and private documents; revision-controlled saves/submissions; officer-only review in M7; history and notification records. | M2-01 incomplete submit rejected; M2-02 permitted correction/resubmit; M2-03 private files inaccessible to others; M2-04 status mirrors M7; M2-05 submission and documents persist. | ApplicationScreen selects local URI only; **Local preview / Not run for backend**. |
| **M3: Activity Information and Registration** | 26/20 §1.4.3.3; 88–89/82–83 | Member: activity feed, digital calendar, announcements, event details/venue/requirements/deadlines, authenticated registration, QR-linked access, reminders. | Published M8 events and announcements; verified member registration associated with event; deadline/capacity/eligibility validation; deduplicate registration. | M3-01 latest published details accessible; M3-02 eligible registration persists; M3-03 invalid/late/duplicate registration handled; M3-04 QR link opens correct event; M3-05 reminder links source. | **Absent / Not run**. |
| **M4: Personal Participation and Training Records** | 26/20 §1.4.3.4; 36–38/30–32; 89–90/83–84 | Member: own attendance/participation history, scorecards, comparable progress trends, feedback and reviewed videos, correction request; separately labelled personal sessions/scores/conditions/observations. | M8 attendance and M11 training records; source/encoder/date/validator/date/correction history; personal submissions kept distinct; officers approve official corrections. | M4-01 own history only; M4-02 no direct official edit; M4-03 personal entry visibly unvalidated; M4-04 approved correction reflected; M4-05 trends compare equivalent scoring conditions. | **Absent / Not run**. |
| **M5: Member Operations and Equipment Borrowing** | 26–27/20–21 §1.4.3.5; 90/84 | Member: equipment availability, borrowing dates and expected return, request/status/history; reimbursement form with receipts/supporting documents, notices. | Requests connect M10 inventory and M12 financial review; pending/approved/released/returned/overdue/rejected/returned for correction; owner read; no automatic assignment/financial approval. | M5-01 valid request reaches officer; M5-02 invalid period rejected; M5-03 status/history persists; M5-04 receipt accessible only to authorized reviewers/owner; M5-05 corrections resubmittable. | **Absent / Not run**. |
| **M6: Member Dashboard and Personal Monitoring** | 27/21 §1.4.3.6; 91/85 | Member: status cards, renewal, upcoming activities, registrations, attendance, training, borrowing, payment/reimbursement status, pending requirements/reminders, quick links and personal documents. | Derived queries across source modules, filtered by current identity; notification acknowledgement and completion; no independent authoritative copies. | M6-01 every count links to source; M6-02 empty/incomplete data labelled; M6-03 another member's records excluded; M6-04 changes propagate; M6-05 pending task acknowledged without falsely completing workflow. | Sample standing card only; **Local preview / Not run for backend**. |
| **M7: Membership and Applicant Processing** | 18–19/12–13 §1.4.2.1; 87–88/81–82 | Authorized membership officers: application access QR/portal usable by phone/tablet/computer, searchable/filterable review queue, requirements verification, correction/incomplete notices, screening decisions, member profile maintenance, renewals, accepted-applicant conversion. | Officer permission, same Auth user → membership; transactional decisions, protected transitions, reviewer history; configurable required fields/documents; applicant and responsible-officer notifications. | M7-01 submission→return→resubmit→approve produces one same-identity member; M7-02 no self-approval; M7-03 duplicate/stale review prevented; M7-04 renewal decision recorded; M7-05 QR portal functional; M7-06 histories retained through turnover. | **Absent / Not run**. |
| **M8: Announcement, Event, Registration, and Attendance Management** | 19–20/13–14 §1.4.2.2; 38/32; 88–89/82–83 | Authorized activity officers: publish/update announcements/events, schedules/venues/requirements/assigned personnel, registration windows, live participant list, attendance-period QR, manual fallback, review corrections, reminders. | Event-linked registration/attendance; authorized period, registered user, unique attendance; encoder/validation/correction history; notifications and event updates. | M8-01 publish→register→QR/manual attendance→M4; M8-02 reject wrong/expired QR and duplicate; M8-03 manual correction audited; M8-04 unauthorized edits denied; M8-05 roster updates and reports reconcile. | **Absent / Not run**. |
| **M9: Election Management** | 20–21/14–15 §1.4.2.3; 36/30; 39/33; 89/83 | Election officers/Commission on Elections: validated rules, schedules, announcements, nominations/candidacy documents, candidate/voter eligibility review, restricted voting, turnout, tallies, result confirmation/dashboard/archive; eligible members nominate/file/vote; appointment/deliberation, vacancies/succession/special elections. | Rules and term records; default DLSU annual final-term election, announcement four weeks before third-term end, candidacy +3 days, voting +7 days, results within +3 days; active +2 consecutive terms voter check, final officer confirmation; ≥60% turnout validity; anonymous ballots separate from participation; external/paper verified turnout/results option; confirmed results → explicit M14 role assignment. | M9-01 rules validated before opening; M9-02 ineligible/duplicate/outside-period voting denied; M9-03 no voter-choice link in accessible records/logs; M9-04 under-threshold requires re-vote; M9-05 President/VP elected, remaining positions appointed; M9-06 no automatic role grant; M9-07 results/history archived. | **Absent / Not run**. |
| **M10: Equipment Inventory and Allocation Support** | 21–22/15–16 §1.4.2.4; 38/32; 52/46; 89–90/83–84 | Equipment/logistics officer or armorer: inventory/specification/condition/acquisition record, QR/barcode labels and scanning, availability calendar, borrowing/usage/maintenance history, activity/member requirements, conflict flags, substitutes, explicit allocation/release/return/condition update. | Equipment, compatibility rules (handedness/draw weight/setup), reservations/assignments tied to members/events, overlapping periods and unavailable/damaged flags; officer confirms compatibility/safety; no automatic allocation or physical sensor tracking. | M10-01 overlapping booking blocked/flagged atomically; M10-02 incompatible/damaged items flagged; M10-03 suitable available alternatives shown; M10-04 request→approve→release→return updates history/condition; M10-05 overdue derived from time; M10-06 QR retrieves right item. | **Absent / Not run**. |
| **M11: Training Performance Monitoring and Feedback** | 22–23/16–17 §1.4.2.5; 38/32; 90/84 | Coach/training officer: structured scorecards, training date/session, distance/target/category/scoring format/score/grouping/level/observations, compatible CSV import/export, validate personal entries, correction history, coach feedback, record/review video capped at 10 seconds. | Typed score and context constraints, transactional CSV validation, personal versus official source, validator/encoder/timestamps; private video with enforced duration; member reads authorized own official records; no sensors/biomechanics/arrow velocity. | M11-01 encode/import→review→M4/M13; M11-02 malformed/duplicate CSV handled without partial corrupt import; M11-03 >10s video rejected server-side; M11-04 member cannot alter official feedback; M11-05 trends group compatible formats. | **Absent / Not run**. |
| **M12: Financial Record Verification and Tracking** | 23–24/17–18 §1.4.2.6; 38–39/32–33; 90/84 | Member/applicant payment reference/proof submission; responsible-user expense purpose/amount/documents; member reimbursement receipt; Treasurer/finance review, President/Treasurer prior approvals for club funds, status/remarks, ledger and periodic summaries. | Payments, fees, expense/reimbursement requests, private proof/receipts, separate approval records, pending verification/verified/incomplete/rejected/returned for correction; immutable decision history; exact decimal amounts; no money transfer/payment gateway. | M12-01 valid proof→verification→ledger; M12-02 missing/invalid documents/amount rejected; M12-03 both required approvals before processing expense; M12-04 correction/resubmit; M12-05 ledger reconciles verified records; M12-06 finance access never implied by election role. | **Absent / Not run**. |
| **M13: Reports, Dashboard Analytics, and Goal Monitoring** | 24/18 §1.4.2.7; 40/34; 91/85 | Authorized officers: search/filter reports, charts, exports, measurable goals/targets and comparisons across membership/applicants/activity/attendance/equipment/training/finance/elections/interclub. | Permission-filtered source queries; attendance rate, active-member ratio, applicant conversion, utilization/overdue, comparable score trends, collection completion, outstanding reimbursements; goals with metric/period/target; incomplete-data caveats. | M13-01 fixtures reconcile numerator/denominator and exports; M13-02 zero denominator displayed as unavailable; M13-03 restricted domains excluded; M13-04 goals derive actuals from records; M13-05 source drill-down and history retained. | **Absent / Not run**. |
| **M14: Cross-Layer System Administration and Role-Based Access Control** | 17–18/11–12 §1.4.1.1; 36–40/30–34; 87/81 | Authorized administrator/officers: account management/status, organizational positions, roles and granular permissions, access logs, terms/turnover, retire/reassign access without deleting records; all users authenticate/recover/sign out. | Supabase Auth; server-derived identity and stored privileges; account disabling; time-bound assignments; audit/access logs; protected admin server functions; no privileged key in app; partner event grants distinct from internal roles. | M14-01 anonymous/disabled/expired access denied; M14-02 role spoofing denied; M14-03 outgoing access retired while successor retains records; M14-04 session refresh/logout/restart verified; M14-05 least privilege and separate duties; M14-06 last-admin protection and audited changes. | **Absent / Not run**. |
| **M15: Shared Interclub Event Coordination** | 28/22 §1.4.4.1; 39–40/33–34; 91–92/85–86; interviews 162–176/156–170 | Host authorized officer, event-authorized partner representative, coordinator: shared-event portal/invitations, own delegation registration, requirements/eligibility review, shared participant lists, schedules/pairings/target/group assignments, conflict flags, check-ins, approved substitutions, concerns, official result review/publish, archive/retrieve templates/procedures/minutes/agreements/approval refs/evaluations/recommendations. | Official M8 event + event-specific partner grants; explicit sharing; delegation ownership; host review of changes; unverified results hidden; archive immutable to routine operations; partner cannot query internal memberships/finance/election/equipment/training/admin. | M15-01 invite→delegate→validate→assign→check-in→verify results→archive; M15-02 other delegation edits denied; M15-03 unshared event/internal data denied; M15-04 schedule/assignment conflicts flagged; M15-05 results invisible before verification; M15-06 archive reusable after turnover. | **Absent / Not run**. |

## Cross-module acceptance register

| ID | Source | Requirement and acceptance evidence needed |
|---|---|---|
| X01 | §1.4.5, PDF29–30 | Stable identifiers across M2→M7→M1; M8→M3/M4; M5→M10/M12; M11→M4/M6/M13; M9→M14; M15→M13. Test each full chain with separate roles. |
| X02 | §1.7.2 PDF39 | Reminders record type/source/task/responsible user or role/due date/notified date/acknowledgment/completion. Automatic notification and pending-task views must survive restart; notification is not proof of task completion. |
| X03 | §1.7.2 PDF38–40 | Creator/modifier/reviewer/validation/correction history retained. Private documents tied to operational records; authorized retrieval; retention/archive/removal only under validated policy. |
| X04 | §1.7 PDF35–40 | No bank credentials/cards/medical/academic/private-chat data, automatic money movement, institutional approvals, live officiating, GPS/RFID, sensors or automated coaching. Enforce collection scope in forms/schema. |
| X05 | §3.2.2.4 PDF94–96 | Functional, integration, negative, role, regression, failure/retry, recovery, and device testing; core workflows have no unresolved critical/high defects. |
| X06 | §3.2.2.6 PDF97–98; §3.5.3 PDF105–106 | Eight full integration scenarios; outgoing-officer access retired, incoming roles assigned, historical records preserved; 100% critical UAT and ≥90% overall UAT. |
| X07 | §3.5 PDF104–109 | Human evaluation required: ≥85% independent completion, assistance ≤20%, median time within pilot limits, SUS ≥68, acceptance ≥4/5. Must never fabricate participants, scores or acceptance. |
| X08 | §3.2.2.4 PDF95–96 | Pilot timing targets: login60s, membership3m, activity2m, attendance45s, borrowing2m, review3m, training3m, ballot90s, report2m, interclub4m. Freeze after pilot; measure actuals. |
| X09 | Appendix PDF122–159 | Survey evidence (10 total respondents, varying 4–8 answers by area) supports search, tiered permissions, role-specific views and accessible UI. Missing chart legends must not be invented. |
| X10 | §1.7 and Appendix PDF162–176 | Partner channels and event contacts limited to authorized sharing; historical anecdotes do not override validated club rules or institutional policy. |

## Milestones (proposal §3.2.1 order)

1. M14 + M7 + M1 + M2: identity, permissions, application/review/conversion/renewal.
2. M8 + M9 + M3 + M4: activities, participation and governance.
3. M10 + M11 + M12 + M5, complete M4 training integration.
4. M13 + M6 + M15, all-module integration, continuity, delivery and evaluation.

This sequence is implementation order, not permission to omit any module.

## Current implementation and verification (2 October 2026)

The baseline-gap column above records the original state. This table supersedes its
implementation/verification labels. **All modules remain In progress** until the
remaining acceptance criteria are demonstrated; none is marked fully functioning.

| Modules | Current source and implemented paths | Verification evidence | Remaining verification / gaps |
|---|---|---|---|
| M1, M2, M7, M14 | Identity/membership migration 001, checked command API, private storage; real auth and Android forms; browser application/renewal forms; trusted administrator bootstrap | Workflow tests: anonymous denial, role spoof denial, application correction/approval with same identity, version conflict, renewal draft/document lock, role turnover and last-admin protection | Hosted auth/SMTP, restart/refresh/recovery, first administrator, browser portal deployment/QR and UAT; broader account provisioning interfaces |
| M3, M8 | Activities migration 003; publish/register/attendance QR/manual correction; Android event and roster screens | Workflow test: unpublished denied, duplicate registration idempotent, full event rejected, unregistered QR denied, correction reflected, other-member history hidden | Hosted QR/device checks, visual calendar and reminder delivery checks, roster refresh under concurrent use |
| M4, M11 | Training migration 005; personal/official distinction, correction, transactional CSV, feedback, private documents; server video parser/function; native score comparisons and CSV export | Workflow test: official edit denied, validation, CSV rollback and duplicate keys; 3 video parser tests including >10s concealed by short header | Real camera clips/Edge Function deployment, CSV round-trip usability, comparable-trend device checks |
| M5, M10 | Equipment migration 005; period/specification search, alternatives, review/release/return and condition history; Android forms and equipment QR | Workflow test: unauthorized approval, conflicting bookings, damage blocks approval, return state/availability | Real device scanning, calendar usability, concurrency on separate database connections, overdue scenarios |
| M6 | Live workspace-derived cards, upcoming registered events, source buttons, own notifications/documents | Shares RLS/ownership tests | Full source-to-dashboard reconciliation and device refresh/restart; richer pending-task summaries |
| M9 | Elections migration 004; candidacy/voter review, private ballots, turnout/results, leadership records; Android ballot panel; safe election projections | Workflow test: ineligible/duplicate votes denied, ballots inaccessible, no vote audit payload, unconfirmed tallies withheld | Full nomination/acceptance workflow, end-to-end deadlines/residency/revote/external tally validation and archived results UAT |
| M12 | Finance migration 005; draft/proof/submission, separate President/Treasurer approvals, verified ledger and fee matching | Workflow test: missing proof rejected, both approvals required, verified ledger amount, domain restrictions | Broader self-approval/same-person negative checks, corrections and fee reconciliation on hosted project |
| M13 | Permission-filtered reports, export, goals and source-derived comparisons, election summaries | Finance report reconciliation and unauthorized report denial | Goal metrics test added in subsequent verification; chart/source-drilldown usability, all-domain export reconciliation |
| M15 | Interclub migration 006; event invitations, own delegates, eligibility/substitution/assignment/concerns/results/archive; sanitized shared participant list; browser representative interface | Workflow test: no internal data, self-review denied, shared list omits requirements, revoked grant hides event | Full conflict/substitution/check-in/results/archive scenarios, event-specific coordinator UI, shared archive document workflow and partner browser UAT |

Implementation locations: `app/src/main/java/ph/capstone/archeryclub/connected/`,
`app/src/main/assets/modules.json`, `tools/generate_catalog.py`, `portal/`,
`supabase/migrations/`, `supabase/functions/verify-training-video/`.
Tests: `backend-tests/workflow.test.mjs`, `backend-tests/video.test.mjs`,
`app/src/test/java/ph/capstone/archeryclub/connected/WorkflowInputTest.kt`.

## Open configuration and external dependencies

- Hosted URL and publishable key were supplied and saved in ignored local configuration.
  Browser access to the Supabase dashboard is blocked because its admin-enforced security
  policy check is unavailable. No hosted migration or hosted workflow verification is claimed.
- First administrator must be explicitly provisioned in trusted backend configuration, never by public signup.
- Club must validate required application/renewal fields/documents, membership categories/status rules,
  academic terms/residency and candidate eligibility, equipment compatibility limits, fees and retention.
  Supplied election defaults are labelled proposal defaults until club validation.
- Android plus browser application/partner access must be checked against the approved technology exception.
- SMTP/auth redirect/domain settings, account invitation delivery and real device connectivity require deployment configuration.
- Final stakeholder UAT, SUS, pilot timing and acceptance cannot be replaced by developer tests.

## Evidence log

### 7 October: M14 authentication device milestone

Subsequent approved role-plan application: five real local accounts authenticated successfully.
Admin has administration + President; officer has active membership and the eight approved
operational permissions; applicant remains draft; member and partner have exactly the designated
coordinator/partner event scope. Checks denied officer President approvals, admin finance
verification, partner delegate review, coordinator invitations and non-admin role assignment.
The local event is a clearly labelled October 10 demonstration fixture. This is M14/M15 access
evidence, not a claim of complete interclub or finance workflow acceptance. New term-limited
President/officer roles expire 1 November 2026 00:00 Asia/Manila. Event grants are separately revocable.

Additional M14/M1/M2 account evidence: the three explicitly requested local demonstration
accounts were provisioned and authenticated with the supplied passwords. Workspaces confirmed
administrator-only administration permissions, active member status with no officer permissions,
and a draft applicant with no membership or officer permissions. Both test accounts were denied
role assignment (`42501`) and could read only their own private profile. The initial member was
an audited test fixture, not a fabricated application approval. No hosted account or email
verification is established by this check. No password is stored in version-controlled files.

The local Android 17 build now checks and requests `ACCESS_LOCAL_NETWORK` before starting
Supabase. Denial returns to a clear explanation; allowing access enables real local sign-in.
Verified on Pixel 7: a fictional member opens the server-supplied workspace, force-stop/reopen
restores that workspace, and sign-out remains effective after another restart. These establish
the local device portion of M14-04 only; hosted email/recovery, wider role tests and final
acceptance remain outstanding. All modules retain **In progress** status.

The real local service test was rerun on 7 October and passed Auth sign-in/refresh/re-login,
private Storage access, application conversion/persistence, and simultaneous registration and
equipment-approval checks. The database is the isolated `archery-proposal-test`, not hosted data.
The final local Android build, all 17 unit tests (10 legacy preview, 4 workflow input, 3 auth
error handling) and lint passed. `tools/verify_android_session.py` passed all four checks:
authenticated process restart, persistent sign-out, wrong-password rejection, and successful
login after that rejection. Auth errors remain visible until another action and known error
codes map to safe explanations. Unknown Auth error payloads are not displayed.

Implementation evidence: debug `AppEntry.kt` and manifest for permission gating,
`ConnectedApp.kt` for persistent auth feedback, `SystemViewModel.kt` for error mapping and timeout,
`AuthErrorMessageTest.kt` for safe feedback, and the device regression script above.
The local-demo APK is saved separately under `app/build/outputs/apk/local-demo/`.

The user will present on this Mac first, then a physical Android phone. The phone must be tested
against an appropriately configured backend; emulator-local evidence is not hosted verification.

| Date | Check | Result / limitation |
|---|---|---|
| 2026-10-01 | Full proposal and current tracked source inspection | Completed before implementation; no hosted backend/schema exists in baseline. |
| 2026-09-23 | Previous local preview build/tests/emulator | Historical evidence only: 10 preview tests and local persistence; not evidence of backend or remaining modules. |
