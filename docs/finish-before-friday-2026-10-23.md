# Finish-before-Friday task plan

Prepared Tuesday **October 20, 2026**, Asia/Manila. Target: integrated, tested handoff
by **Thursday October 22 evening**; Friday October 23 is the deadline/buffer, not the
first integration day. These are proposed assignments, not confirmed availability.
Carll should attach actual names to A/B/C today. If fewer people are available, combine
owners explicitly; do not silently drop proposal requirements.

“Ready for demonstration” and “proposal complete” are separate claims. All modules
remain in scope. Email, portal hosting and club validation can prevent full acceptance
even if the Android demonstration works. Do not promise completion before evidence exists.

## Ownership and acceptance checklist

| Proposed owner | Priority / modules | To do | Evidence required to close |
|---|---|---|---|
| **Carll — integration/backend owner** | P0, M14 and shared backend | [ ] Assign names and branches. [ ] Give new groupmate setup/client configuration and passwords privately. [ ] Review signup confirmation mismatch and approve/coordinate its correction. [ ] Decide whether to unblock SMTP and portal hosting. [ ] Keep one person responsible for migration deployments. | Everyone builds the same connected app; Auth configuration verified; external-service decisions recorded. Never send management/service-role secrets in group chat. |
| **Existing groupmate A — applicant/member workflows** | P0, M1/M2/M3/M4/M6/M7/M8 | [ ] Run applicant draft → required documents → submit → correction → resubmit → officer approval → same-account membership. [ ] Run renewal/review and profile edit/restart. [ ] Test announcements/calendar, event registration/cancellation/capacity, QR/manual attendance and corrections. [ ] Reconcile dashboard/tasks with records. | Both valid and invalid cases, cross-user denial, saved changes after relaunch, status visible to the correct actor. Use separate applicant fixture or demonstrate conversion last. |
| **Existing groupmate B — contributed officer modules** | P0, M5/M9/M10/M12/M13 | [ ] Inspect/retry election timeout safely. [ ] Test draft edits, stale edits, candidacy/eligibility, digital/external results, duplicate voting/privacy, turnout, leadership and archive. [ ] Complete equipment UI request → review → release → return plus conflicts/condition/compatibility. [ ] Run receipt → correction/resubmission → separate approvals → verification. [ ] Reconcile/export reports and goals. | Repeatable live Android workflows and denial tests; exported CSV/JSON opens with correct totals. No self-approval or manual fabricated goal actuals. |
| **Existing groupmate C — training/interclub workflows** | P0, M4/M11/M15 | [ ] Inspect interclub rejection and rerun corrected sequence using fresh dates. [ ] Test invitations, own delegation, a second partner's isolation, eligibility, assignments/conflicts, substitution, check-in, verified results, concerns and archive locks. [ ] Test CSV imports/exports, training feedback/corrections and actual camera upload including >10-second rejection. | Host and partner observe correct shared state, unauthorized edits fail, archive remains immutable, video and CSV output verified on a device. |
| **Carll + new groupmate — independent QA** | P0, all modules/M14 | [ ] Install on Pixel 7 and physical phone. [ ] Walk through all five accounts. [ ] Test sign-out/relaunch, expired sessions, airplane mode/retry, duplicate taps and stale updates. [ ] Check each operational permission separately, account disable/role turnover, storage boundaries. [ ] Keep a defect/evidence list. | Each failure includes role, device, steps, expected/actual, record ID and retest result. No screenshots containing passwords/tokens. Five combined demo roles alone are insufficient for all narrow-role checks. |
| **Carll + whole group** | P1, delivery/acceptance | [ ] Validate actual club requirements/rules. [ ] Test applicant/partner portal locally; host only if newly approved. [ ] If email is unblocked, configure approved sender and test signup/recovery/invitation delivery. [ ] Prepare clean presentation fixtures and rehearsal. [ ] Produce final APK, setup guide, matrix and evidence. | Every proposal requirement has a pass or explicit unresolved blocker. Complete required stakeholder/UAT/SUS work; internal tests cannot substitute for stakeholder acceptance. |

P0 means resolve before calling the integrated app demo-ready. P1 remains required for
full delivery; the label is scheduling priority, not permission to omit it.

## Tuesday, October 20 — establish a reliable baseline

- [ ] Carll assigns A/B/C, shares the repository and confirms everyone can run the connected app.
- [ ] Review the October 16 reports; preserve passing evidence and reproduce only unresolved cases.
- [ ] Read existing records before retries. The election write may have succeeded despite timeout;
  the interclub test left partial records. Old event windows have now expired.
- [ ] Agree on clearly labelled future test events, eligible election fixtures and isolated users.
  Do not change official records or bypass workflow protections to make tests pass.
- [ ] Make SMTP/hosting decisions today. They remain deferred until Carll approves a change.
- [ ] Open a shared defect list with columns: module, owner, steps, expected, actual, severity,
  record/test ID, commit and verification result.

## Wednesday, October 21 — finish workflows and merge fixes

- [ ] A/B/C complete their checklist flows with another person acting as the other role.
- [ ] Fix reproducible defects and add meaningful regression coverage where appropriate.
- [ ] Merge reviewed changes throughout the day; avoid a Thursday-night bulk merge.
- [ ] New groupmate retests on the integrated build and audits denials/ownership boundaries.
- [ ] Verify camera, uploads, CSV, reports and interclub/election paths early: these are open risks.
- [ ] Carll deploys only new reviewed migrations if fixes require them, then repeats affected tests.

## Thursday, October 22 — release candidate and rehearsal

- [ ] Freeze feature changes by midday; accept only fixes needed for agreed acceptance criteria.
- [ ] Run Android assembly, unit tests and lint, plus backend tests and affected hosted scenarios.
- [ ] Retest on a physical phone and Pixel 7 using the same commit and hosted schema.
- [ ] Rehearse applicant, member, officer, President/admin and partner flows with usable dates/data.
- [ ] Update requirements matrix with implementation and verification tracked separately.
- [ ] Produce/share the debug APK and matching commit, setup instructions, role guide and known issues.
- [ ] Carll confirms by evening whether all required checks passed. If blocked, state precisely what
  remains; do not label the app finished just because it launches.

## Completion gate

Close a module only when its proposal criteria have been checked for: authorized success,
invalid inputs, unauthorized access, persistence after app restart, cross-module effects,
required outputs, and relevant network/error recovery. Record device/API evidence separately.

Before operational release, also resolve sender/portal configuration, validate club rules,
complete stakeholder acceptance and define signing, backup/recovery and release ownership.
Do not distribute the development APK as a claim of production readiness.

## Safe test and merge rules

- Use one branch per workstream; pull/review before merging. Avoid editing the same shared
  form/catalogue files simultaneously without coordinating.
- Never rewrite a deployed migration, run a database reset on shared Supabase, or apply
  `supabase/reference/member_backend_202610020001.sql` to the existing schema.
- The hosted harness can create data. Read its flags and existing reports before running it;
  `--continue-demo`/`--resume` are not guaranteed safe after changed records or expired dates.
- Keep private test-login JSON outside the repository and remove it after use. Test scripts
  do not replace manual Android acceptance and should not silently reset user identities.
- Keep raw test failures. A correction to a test sequence is not a passed retest.
- Continue treating parent `sources/` as read-only.
