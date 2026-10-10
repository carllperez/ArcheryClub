# Archery Club role and demonstration account proposal

Prepared, approved and applied locally 7 October 2026; **also approved and applied to hosted
development Supabase on 9 October**. The same five logins and role boundaries are active in
both environments, with separate records. Hosted identities are explicitly owner-provisioned
demo fixtures, not evidence of actual email ownership, screened membership or club appointments.

Hosted event ID: `7723c883-760e-43bd-829a-eaef30bf4280`; hosted partner-club ID:
`a917f5b2-f91b-4b5c-918f-b7d9c032abe1`. Hosted venue is **Development demonstration only**.
Provisioning and access checks passed through `tools/setup_hosted_demo.mjs`. No email was sent.
President/officer expiry is the same October 31 cutoff; hosted administrator has a one-year
development term. The local IDs below remain valid only for the local fallback.

## Applied setup

All five accounts below can sign in with the owner's supplied passwords. The new President and eight officer assignments end at **1 November 2026, 00:00 Asia/Manila** (valid through October 31). The original administrator assignment is unchanged. Partner/coordinator grants remain restricted to the designated event and can be retired through M15; they do not share the officer-role expiry.

- Event: **CAPSTONE 1 Demonstration Interclub Event**, 10 October 2026, 09:00–17:00 Asia/Manila.
- Venue: **Local demonstration only**. This is fictional test data, not a real tournament or institutional approval.
- Partner: **Demonstration Partner Club**.
- Coordinator: the existing test member; no global interclub permission was added.
- Event ID: `b9586c1d-cf52-4c0c-bfa9-625712cd6e75`.
- Partner-club ID: `a3692ee4-8bd0-46d9-b4ea-d2bb2438d8c9`.

Application tool: `tools/apply_local_role_plan.mjs`. It used real local Auth and protected commands, recorded role assignments, created an explicit officer-membership fixture, and removed its private password input after verification. No external email was sent.

Verified: all five accounts authenticate; exact permission sets and membership/applicant standing match the plan; partner/coordinator see only the assigned shared event and their own private profile/financial records. Officer President-approval attempts, administrator finance-verification attempts, partner delegate-review attempts and coordinator invitations were denied. Non-administrators were denied role assignment. These checks do not establish all-module or hosted acceptance.

The following rationale and tables retain the approved design; the application status above supersedes the original review wording.

## Purpose and proposal basis

The application should give each person one account and assign access according to that person's responsibilities. A module is a set of functions; it does not require a separate login. A person may be a member, an officer and an event coordinator under the same identity, while each responsibility retains its own permissions and limits.

The approved proposal defines applicants, members, administrators, club officers, election officers, finance officers, equipment officers, coaches, partner representatives and activity/tournament coordinators. It also explicitly names the President and Vice President as organizational positions. It does not prescribe eleven additional test accounts or require separate organizational positions called Membership Officer, Activity Officer and Reports Officer. Those labels in the current code group permissions for authorized officers.

This document proposes **five demonstration accounts in total: the three existing accounts plus two new accounts**. This is a practical demo arrangement, not a proposal-mandated account count or a production staffing plan. All fifteen modules remain in scope. Combining demonstration responsibilities does not merge their workflows or remove authorization checks.

Source: [2528-PROPOSAL (2).pdf](</Users/carll/Downloads/2528-PROPOSAL (2).pdf>). References below use PDF page / printed page.

| Proposal reference | Requirement informing this plan |
|---|---|
| §1.4.1.1, M14, pp. 17–18 / 11–12 | Accounts, organizational positions, assigned roles, responsibility-based permissions, access records and officer turnover |
| §1.4.2.1–§1.4.2.7, pp. 18–24 / 12–18 | Authorized officer responsibilities for M7–M13 |
| §1.4.3.1–§1.4.3.6, pp. 25–27 / 19–21 | Applicant and member self-service access through M1–M6 |
| §1.4.4.1, M15, p. 28 / 22 | Host officers, partner representatives, coordinators and event-limited sharing |
| §1.7.1–§1.7.2, pp. 36–40 / 30–34 | User groups, protected records, leadership information and access boundaries |

## 1. Minimum practical demonstration accounts

| Account | State before approval | Applied demonstration responsibilities | Boundary |
|---|---|---|---|
| `carll_perez@dlsu.edu.ph` | Administrator only | Retain Administrator; add the President approval responsibility for the demo | M14 administration and the President's M12 approval. Do not add Treasurer or blanket operational access. |
| `testMember@dlsu.edu.ph` | Active member | Retain Member. During the interclub scenario, assign Coordinator access to one designated test event | Own member records plus coordination of that event. No club-wide officer, finance or administration access. |
| `testApplicant@dlsu.edu.ph` | Applicant with a draft application | Retain Applicant | Own application, requirements, statuses and applicable payment-proof submission. No approval authority or member privileges before acceptance. |
| `testOfficer@dlsu.edu.ph` | Account did not exist | Active test member with assigned membership, activity, election, equipment, training, Treasurer, reporting and host-interclub responsibilities | Manages the assigned operational workflows. Cannot manage account roles through M14 or provide the President approval. |
| `testPartner@dlsu.edu.ph` | Account did not exist | Partner-Club Representative for the designated partner club and test event | Own delegation and deliberately shared event information. No unrelated internal club records. |

The owner approved the two new accounts and supplied their passwords. Both accounts were created; no password is included here.

The officer combines responsibilities only to make the demonstration manageable. In operational use, the club assigns each responsibility to the authorized person. A combined officer account is not sufficient evidence that each individual department's access restrictions work; those restrictions still require focused tests with narrower assignments.

The member should first be shown without coordinator access. M14/M15 can then demonstrate assigning and retiring the event responsibility without changing the member's identity. Coordinator access adds only the assigned event; it does not broaden the member's access to other people's internal records. An extra coordinator account is optional if simultaneous, permanently separate member and coordinator logins are desired.

## 2. Mapping positions and roles to the accounts

The following assignments are now active locally:

| Responsibility | Proposed account | Current implementation mapping |
|---|---|---|
| Account and role administration | Existing administrator | `administrator` → `administration` |
| President's club-fund approval | Existing administrator | Separate `president` assignment → `president` permission |
| Membership and applicant processing | Proposed officer | `membership_officer` → `membership` |
| Activities, announcements, registration and attendance | Proposed officer | `activity_officer` → `activities` |
| Election administration / COMELEC | Proposed officer | `election_officer` → `elections` |
| Equipment / logistics / armorer duties | Proposed officer | `equipment_officer` → `equipment` |
| Coach / training officer duties | Proposed officer | `training_officer` → `training` |
| Treasurer / finance duties | Proposed officer | `treasurer` → `finance` |
| Reports and goal monitoring | Proposed officer | `reports_officer` → `reports`, together with the relevant operational permissions |
| Host interclub management | Proposed officer | `interclub_officer` → `interclub` |
| Event coordinator | Existing member, only for the designated event | Event assignment `coordinator`; no global `interclub` permission |
| Partner representative | Proposed partner | Event assignment `partner` linked to its partner club |

**President and Vice President:** M9 must retain both positions, election/appointment information, terms, vacancies and succession records. The President approval assignment above is explicitly a demo fixture, not a claim that an actual election occurred. The existing member can be used as a test Vice President candidate when the validated eligibility conditions are met; no additional login is required. Confirmed office information is recorded through M9, and authorized operational permissions are assigned through M14. The Vice President title alone does not imply an invented permission to approve finances, administer elections or manage accounts.

Membership standing is separate from officer permissions. The new officer's active membership would be established explicitly as test data. Assigning President or Administrator does not automatically create a membership or make the account eligible to vote. If that person also needs member self-service, the membership must be established separately.

## 3. Role-by-module permission matrix

The matrix preserves the proposal's module numbers and names. It describes intended access, not a claim that every workflow has passed acceptance testing. **View** means authorized records only; **submit** does not mean approve. Omitted roles have no authority through that role. A person's separately assigned membership or other responsibility may give additional, explicitly scoped access.

| Proposal module | Applicant/member actions | Authorized role and officer actions | Access limits |
|---|---|---|---|
| **M1: Member Profile and Status** | Member views own profile/status, edits permitted personal information and submits renewal information | Membership-processing officer maintains official membership through M7 | Member cannot approve renewal or set official standing |
| **M2: Applicant Submission and Screening Status** | Applicant saves/submits own application, uploads requirements, makes permitted corrections and views status | Membership-processing officer reviews and decides through M7 | No other applicant's private records; no self-approval |
| **M3: Activity Information and Registration** | Member views published announcements/calendar and registers for eligible activities | Activity officer manages the source records through M8 | Registration must satisfy the applicable window, capacity and eligibility rules |
| **M4: Personal Participation and Training Records** | Member views own official history, submits separate personal training and requests corrections | Activity officer handles attendance corrections; coach/training officer handles official training validation and corrections | Personal entries remain distinct from official records; member cannot directly rewrite official scores or feedback |
| **M5: Member Operations and Equipment Borrowing** | Member views availability and submits/tracks own borrowing and reimbursement requests | Equipment officer processes borrowing through M10; finance officer processes applicable submissions through M12 | A request does not allocate equipment or authorize expenditure |
| **M6: Member Dashboard and Personal Monitoring** | Member views own summaries, pending tasks, documents and links | Responsible officers update source modules | No access to another member's dashboard; acknowledging a notice does not complete its underlying workflow |
| **M7: Membership and Applicant Processing** | Applicant/member receives the resulting statuses through M2/M1 | Officer assigned membership duties views queues, verifies requirements, returns/corrects/reviews applications and renewals, and approves/rejects decisions | Preserve the applicant's identity on conversion; record reviewer and history; prevent self-approval and stale decisions |
| **M8: Announcement, Event, Registration, and Attendance Management** | Member registers and uses authorized attendance access through the connected member functions | Officer assigned activities creates/publishes events and announcements, manages rosters and QR/manual attendance, and reviews corrections | Attendance is event-linked and constrained by authorized participation and timing |
| **M9: Election Management** | Eligible member nominates, files candidacy or votes as permitted by validated rules | Election officer/COMELEC manages schedules, confirms eligibility, administers voting/verified external results, confirms results and preserves leadership records | No voter-choice identity link in accessible records; no automatic officer authority from results; President and Vice President positions remain represented |
| **M10: Equipment Inventory and Allocation Support** | Member views permitted availability and submits borrowing through M5 | Equipment/logistics officer or armorer maintains inventory, checks compatibility/conflicts, confirms allocation and records release/return/condition | Member cannot assign equipment; responsible officer makes the final allocation decision |
| **M11: Training Performance Monitoring and Feedback** | Member submits personal training through M4 and reads own authorized training/feedback | Coach/training officer encodes/imports/exports records, validates submissions, reviews permitted videos and records feedback/corrections | Official edits require the responsible role; training video limit remains 10 seconds |
| **M12: Financial Record Verification and Tracking** | Applicant/member submits applicable own payment proof; member submits reimbursement; authorized responsible user submits an expense | Treasurer/finance officer reviews, verifies and records financial status; President and Treasurer supply their respective required club-fund approvals | Separate approval records; demo uses different approvers and a different requester. No actual payments or transfers; no self-verification |
| **M13: Reports, Dashboard Analytics, and Goal Monitoring** | Member sees personal summaries through M6 rather than unrestricted organizational reports | Authorized officer with reporting access views/filters/exports reports and manages goals within the permitted data domains | Reports permission is not unrestricted source-data access. For example, financial reporting also needs finance authorization |
| **M14: Cross-Layer System Administration and Role-Based Access Control** | All authorized users authenticate, recover access and sign out | Administrator or explicitly authorized club officer manages accounts, positions, roles, permissions, status, access records and turnover | Ordinary users cannot promote themselves; administration is not an automatic grant of every operational responsibility |
| **M15: Shared Interclub Event Coordination** | A member has no coordination authority solely through membership | Host officer creates/shares events and invites representatives; assigned coordinator handles authorized event operations; partner representative submits/updates its own delegation and views shared information | Event and delegation boundaries apply; designated officials review eligibility/substitutions/results; unverified results are withheld; archives preserve continuity |

## Demonstration boundaries and sequence

The proposal requires prior President and Treasurer approvals for club funds (§1.4.2.6, PDF 23 / printed 17). It does not prescribe a number of test accounts. Using separate people for those approvals and preventing self-approval are safeguards in the current backend and are retained by this plan.

1. **Applicant to member:** use the applicant to submit; use the officer to request correction and review; return to the same applicant account to show its new membership after approval. Run this after applicant-only demonstrations, since conversion changes that account's standing. Do not undo an approval simply to recreate an applicant screen.
2. **Member and officer workflows:** use the member for registrations, borrowing, training submissions and financial requests; use the officer for the corresponding authorized review. Do not use the combined officer to approve its own requests.
3. **Two approvals:** the member submits an expense/reimbursement; the administrator acting with the President permission supplies one approval; the officer acting as Treasurer supplies the other and performs permitted financial verification. The member is neither approver.
4. **Election and leadership:** eligibility, voting privacy and result confirmation follow M9. A test office title is not a shortcut around validated rules or role assignment. Keep the test election officer separate from the test candidate used in the scenario.
5. **Interclub:** the host officer invites the partner for one test event. The partner manages its own delegation. Grant the existing member coordinator access for that event, demonstrate coordination, then retire that grant. The coordinator cannot review its own delegation as if it were an independent host decision.
6. **Turnover:** retire an operational or event assignment and demonstrate that protected actions are denied while historical records remain. Do not remove the sole administrator.

Full permission verification also needs a second isolated partner/delegation fixture to prove that one partner cannot change another's records. Such automated test fixtures do not need to become permanent presentation accounts. The five-account arrangement supports the main walkthrough; it does not replace the proposal's complete testing and UAT requirements.

## Current status and approval scope

The approved account setup has been applied locally. The original applicant remains a draft applicant; no application was approved to establish the officer membership. The original test member remains active and now has one coordinator grant. The administrator has the additional President responsibility but no Treasurer role. The officer has eight operational roles but no administration or President role. The partner has event access but no internal role or membership.

The owner authorized this scope and supplied the new passwords before execution. The local demonstration event and partner club are identified above. In-app event notices were created by the normal workflow; no public invitations or external email were sent. To show a pure member view before interclub coordination, the host officer can retire the member's event grant and reassign it during that scenario.

These permissions remain independently reviewable and revocable through M14/M15. Approval would not mean that club rules are validated, that the proposed combined officer is a real club position, or that all modules are complete. Hosted provisioning and physical-phone verification remain separate unfinished work. The current implementation and acceptance evidence remain recorded in `requirements-matrix.md`.
