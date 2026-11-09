# Consultation demo — workflows, not screens

Branch `capstone-workflows`. Written for the consultation after the 9 November 2026 feedback.
Before saying "done" for any item below, run it end to end on the device yourself. Until then
the honest wording is "implemented and backend-tested; device walkthrough pending".

## 0. Before the consultation (must do)

1. **Officer roles expired on 1 Nov 2026.** Sign in as the administrator → Modules → Administration
   → *Assign time-limited officer role* for `testOfficer` (activities, equipment, membership) and the
   President account, ending after your defense date. Without this, every officer step fails.
2. **Apply migration 017** to the hosted project:
   `supabase migration list` (confirm 001–016 are applied; 016 is from 5 Nov) → `supabase db push` (applies 017–020).
   Then run `npm test` in `backend-tests` locally (50 workflow tests, all passing on this branch).
3. **Build** in Android Studio (`app` → Run). Copy your `backend.properties` into this folder first.
4. **Seed demo data** through the app (not SQL) so the audit trail is real:
   - Officer: create 3 activities — one open (capacity 2), one closing within 24 h, one whose
     registration already closed. Publish all three.
   - Officer: add 3 equipment items — a right-handed 24 lb recurve, a left-handed 20 lb recurve,
     and one marked *maintenance*.
   - Member: fill in the profile (right hand, recurve, 26 lb max draw weight).
   - Use a second member account for the waitlist story.

5. **Applicant sign-up** (needed for Story 4 on the hosted project): in Supabase → Authentication → Sign In / Providers,
   turn **Allow new users to sign up** ON and **Confirm email** OFF, then add `ENABLE_SIGNUP=true` to `backend.properties`
   and rebuild. Sign-up never grants a role; officers still screen every application.
6. **Optional: membership fee.** As Treasurer: Fees module → *Set membership fee* (e.g. 500). New approvals then stay
   *Pending payment* until the fee payment is verified and linked.

## 1. The demo stories (show these, in this order)

Keep two emulators/phones side by side: **Officer** on the left, **Member** on the right.
After each step, refresh the other device to show the change crossing roles.

### Story 1 — Activity lifecycle (registration → attendance → history)
1. Officer: Home dashboard → *Activities this week* → **Create activity** (date/time pickers) → save → it shows as **Draft**.
2. Officer: open it → **Publish to members** → confirm. Dashboard counts update.
3. Member: **Activities** tab → *Open* shows the activity with **Open · 2 spots left**.
   The already-closed activity is **not listed** (adviser point: closed registrations disappear).
4. Member: open it → **Register** → confirmation dialog shows date, venue, slots → **You're in!**
   The activity moves to *My schedule*; the home screen shows it as *Your next session*.
5. Officer: activity → **Manage participants (1)** → the member is listed instantly with *Registered at*.
6. Officer: tap **Present** on the member → attendance recorded; *Not yet marked* drops to 0.
7. Member: *History* tab → activity shows **Attended**; attendance rate and *Sessions attended* update.

Talking point: one registration row drives the member's schedule, the officer roster,
attendance, the member's participation rate and the M13 report.

### Story 2 — Capacity, waitlist and automatic promotion
1. Capacity-2 activity: Member A and Member B register → bar shows *2 of 2 · full*.
2. Member C opens it → button now says **Join waitlist** → *You're #1 in line*.
3. Officer roster shows a **Waitlist (1)** section in order.
4. Member A → **Cancel registration** → confirm.
5. Member C refreshes → status **Registered**, notice *"A slot opened. You are now registered…"*.

Rule shown: seats never exceed capacity; promotion happens in the server transaction, first come first served,
and only while registration is open.

### Story 3 — Equipment borrowing (request → approve → hand over → return)
1. Member: Home → **Borrow equipment** → catalog with live states (*Available*, *Maintenance*…).
2. Member: open the **left-handed** bow → the form warns *"This is a left-handed item; your profile says right-handed"*
   and the request button stays disabled. Open the right-handed bow → choose pick-up/return with the pickers → purpose → **Send request**.
3. Officer: dashboard **Borrowing requests · 1 waiting** → request → **Approve request** (must tick *I checked fit and safety*, remark).
4. Officer: **Hand over to member** → inventory tile *Available* drops by one; the item shows **Borrowed · Free <date>**.
5. Member: request timeline shows *Requested → Approved → Handed over*; return reminder notice appears.
6. Officer: **Record return** → choose condition **Needs maintenance** → item disappears from borrowable stock and shows *Maintenance*.
7. Officer: item → *Condition log* and *Borrowing history* show the full trail.

### Story 4 — Registration: new account → application → member → first activity
1. **Sign-up.** On the sign-in screen tap *New applicant? Create an account*. Show that a bad email, a short or
   letters-only password and a mismatched confirmation are each explained under the field before anything is sent.
2. **Application** (*Membership application* screen, three numbered steps with a progress list):
   save details → each required document has its own row (*Missing → Waiting for verification → Verified*) →
   tick the confirmation → *Submit*. The button stays disabled and lists exactly what is missing.
3. **Officer** (dashboard tile *Applications to review* → queue, oldest first). Open the applicant, open the file,
   *Return for correction* with a reason ("ID photo is blurry"). The reason is required.
4. **Applicant** sees *Changes requested* with that reason at the top, taps *Replace* on the ID, submits again.
   The old file stays on record but is marked replaced, so it never blocks approval.
5. **Officer** — *Approve* is disabled with "Verify 1 uploaded document first" until the new file is verified;
   then approve with a membership category. With a fee set, the applicant sees *Pay membership fee* instead.
6. Same account is now an **Active member**: register for a capacity-1 activity, then show a second member
   joining the waitlist and being promoted when the first cancels.

### Story 5 — Profile that the system actually uses
Member: **My profile** (completeness bar) → **Edit** → mobile, emergency contact (both required together), program,
year level, hand, bow type, draw weight, arrow length. Show validation (bad number, missing pair).
Then repeat Story 3 step 2 to show the borrowing check now uses the saved draw weight.
Then show the **verified name**: the Full name is locked with a *Verified* label. Tap **Request name correction**
(new name + reason). Officer: dashboard *Name corrections · 1 waiting* → compare current → requested → **Approve**
with a note. Member refreshes: the new name appears everywhere (roster, borrowings), and the change is in the audit log.

### Story 6 — Officer dashboard from real transactions
Officer home: active members, activities this week, attendance rate (last 30 days), equipment available/total,
and *Needs your action* queues (requests, hand-overs, overdue returns, corrections). Tap each tile → it opens the queue.

## 2. Business rules enforced (server first, mirrored in the app)

| Rule | Where enforced |
|---|---|
| Register only while published, after open and before close | `registration_command` |
| No double registration; joining twice returns the same record | unique keys + idempotent return |
| Full → waitlist; cancellation promotes the earliest waitlisted member | `registration_command` |
| Cannot cancel after start or after attendance is recorded | `registration_command` |
| Closed registrations hidden from members unless they hold a place | `memberActivities()` |
| Check-in requires a confirmed seat (waitlisted cannot check in) | `activity_command` |
| Capacity cannot be reduced below current registrations | `activity_command` |
| Only serviceable items can be requested or approved | `equipment_command` |
| Overlapping approved/released bookings refused | `equipment_command` |
| Hand, draw weight and setup must fit; officer must confirm fit | `equipment_command` + review dialog |
| Borrowing transitions: pending → approved → released → returned only | `equipment_command` |
| Members cancel only before hand-over; borrow period ≤ 14 days | migration 017 |
| Return records condition; non-serviceable items leave the catalog | `equipment_command` + `equipment_status()` |
| Phone formats, emergency contact pair, draw weight 5–80 lb, arrow 20–34 in | `save_member_profile` |
| Approved members cannot rename themselves; one pending correction at a time; officers cannot approve their own | migration 018 |
| Membership fee before Active (when a fee is set): approval → *Pending payment* → verified payment activates | migration 019 |
| Every uploaded application document verified individually before approval; applicants cannot verify their own | migration 019 |
| Rejected applicants can start a new application; earlier document verifications are cleared | migrations 019, 020 |
| Replacing a document supersedes the old file; only current files must be verified | migration 020 |
| Sign-up: valid email, ≥8 characters with letters and a number, confirmed password; sign-up never grants a role | app + Supabase Auth + `initialize_account` |
| Applicants edit details/documents only while Draft or Changes requested; Submitted is locked | `membership_command` + storage RLS |
| Return for correction, incomplete and reject each require a reason the applicant sees | `membership_command` |
| Waitlist promotion continues until the activity starts; walk-ins recorded by officers around the activity time | migration 019 |
| Archiving an upcoming activity notifies registrants/waitlist and cancels linked equipment requests | migration 019 |
| Overdue equipment blocks new requests; double bookings refused at request time; members confirm receipt; damaged returns alert finance | migration 019 |
| Election results confirmed by a different COMELEC member than the one who closed voting or entered paper tallies | migration 019 |
| Interclub results verified by a different officer than the one who recorded them | migration 019 |
| One attendance rate everywhere: present ÷ (registered − excused) | migration 019 + app |
| Bulk fee assessment for all active members (no duplicates) | migration 019 |
| Officer actions require a time-limited role with the right permission | `require_permission` + RLS |
| Members never see other members' registrations or waitlist entries; counts come from `event_stats()` | RLS + read model |

## 3. Script — "What has been done"

**Improved since the previous consultation**
- "Registration is now fully in-app with no QR. A member opens an activity, sees how many slots are left and when
  registration closes, and registers. The record appears immediately on the officer's participant list."
- "Closed registrations no longer show as available. The member only sees activities they can still join, plus
  the ones they're already registered for."
- "We added a waitlist. When an activity is full, members can join the waitlist, and when someone cancels, the next person is
  registered automatically and notified."
- "Officers now have a participant roster for each activity, where they mark present, absent or excused directly.
  Those marks feed the member's history and attendance rate."
- "Equipment borrowing is now a complete transaction. The member requests from a catalog, the system checks fit and dates,
  the officer approves, hands over and records the return with the item's condition, and inventory status changes at each step."
- "Profile editing now covers contact, emergency contact, program and archery details, and the borrowing check uses the archery details."
- "Forms use proper date and time pickers instead of typed timestamps."

**Completed earlier (already demonstrated or tested)**: applicant submission/screening/approval with the same identity,
role-based access and turnover, announcements with notices, election workflow, document storage.

**Currently being completed**: device walkthrough of the new screens; deploying migration 017 to the hosted project.

**Remaining**: training scorecard screens (backend exists), finance screens (backend exists), report charts and export UX,
interclub partner UI polish, SMTP email, UAT/SUS with club officers.

## 4. Likely adviser questions — short answers

- **Where is the 80%?** "All 15 modules have server-side workflows with tests (48 backend tests). The core member transactions
  are activities, equipment and membership, and they now have complete purpose-built screens on both the member and officer sides. The remaining work
  is mostly screens for training and finance, whose backend transactions already exist."
- **What is transactional here?** "Registering, joining a waitlist, cancelling, marking attendance, requesting, approving, handing over and
  returning equipment, application review and approval. Each one changes records, enforces rules and notifies the other role."
- **What happens after the user presses Register?** "The server locks the activity row, checks it's published and within the window,
  checks for an existing registration, and compares the seat count with capacity. It inserts a registration or a waitlist entry,
  creates the member's reminder notice and writes an audit entry. The officer roster and counts update on the next refresh."
- **How are the modules connected?** "A registration feeds attendance, then the member's history and attendance rate, then the officer
  dashboard and reports. A borrowing changes the equipment's state, which affects what's borrowable, the dashboard queues and the condition log."
- **Where do the reports come from?** "From the same tables the transactions write. Nothing is entered separately for reports."
- **What is still missing?** Answer from section 3 *Remaining*. Don't round up.
- **Can you finish this?** "The backend for the remaining modules already exists, so the remaining work is UI on top of tested commands.
  We'll follow the same pattern we used for activities and equipment."
