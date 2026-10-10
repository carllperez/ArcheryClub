# Hosted development deployment — 9 October 2026

Project: `cidxuzrzgexkejssoglk`, `https://cidxuzrzgexkejssoglk.supabase.co`.
Owner explicitly authorized deployment and recreating the five approved demo accounts.
Supabase CLI is authenticated on this Mac; its credential is outside the repository.

## Installed

- All 12 migrations, through `202610020012_cross_module_reads`, using CLI migration history.
- 42 private application tables; all have row-level security enabled. No direct authenticated
  INSERT/UPDATE/DELETE grants. Protected public RPCs perform authorized operations.
- Private `club-documents` bucket; limit 20,971,520 bytes.
- `create-account` and `verify-training-video` Edge Functions, with authentication/permission
  checks inside their handlers. Only the server environment contains privileged Auth credentials.
- Five approved demonstration accounts and responsibilities from `role-access-proposal.md`.
  Account confirmation is explicitly owner-provisioned test setup, not proof of email ownership.
  Normal email signup still requires confirmation. No invitation/verification email was sent.
- Club rules remain `rules_validated=false`, awaiting actual club validation.

The local `archery-proposal-test` backend and the older unrelated local project were preserved.
Local and hosted accounts/records are independent; no synchronization or bulk database copy occurs.

## Verification evidence

`tools/setup_hosted_demo.mjs` passed against the real hosted project:

- All five supplied logins, token refresh, fresh-login persistence and exact permission sets.
- Active member/officer fixture standing; applicant remains draft and without membership.
- Member/applicant/partner private profiles limited to self. Partner/coordinator shared-event
  scope limited to the demo event.
- Non-admin role assignment, officer President approval, administrator Treasurer verification,
  partner delegate review and coordinator invitation all denied.
- Private storage upload/signing for the owner; other-user signing denied. Its temporary
  probe object was removed. No real membership document was uploaded.
- Member invocation of the administrator account function denied. Unauthenticated video
  function and anonymous workspace denied. Private password input removed on successful completion.

`tools/check_hosted_backend.mjs` passed: Auth available, ordinary signup confirmation enabled,
anonymous access denied, both functions deployed and refusing unauthenticated execution.
An authenticated member request for `app_private` returned `406 / PGRST106`, confirming it is
not exposed. The REST root description endpoint returns 401 independently; the schema check
therefore uses a concrete table endpoint instead of mistaking the root response for schema evidence.

`supabase/verify-development.sql` confirmed 12 migrations, 42 private tables, zero tables
without RLS, zero direct authenticated write grants, private bucket and five demo identities.

Local regression suite: 16/16 pass. Android hosted assembleDebug, 17/17 unit tests and lint pass.
Android Studio installed and opened the `.dev` app on Pixel 7 API 37.2. Earlier startup and
keyboard instability prevented automated entry; the user manually signed in on October 9.
Visual checks confirmed **Your club** and M1's **Test Member** profile record. Stopping the
app with Android Studio's Stop control and starting it again with Run restored **Your club**
without credential entry. Hosted member login, profile retrieval and app-restart session
persistence are verified. The app was left signed in; hosted device sign-out persistence,
other accounts' device workflows and full module acceptance remain unverified.

## Run and share

Android Studio: open this directory, select **app**, select **Pixel 7**, click **Run**.
The standard configuration uses ignored `backend.properties` for this hosted project.
The device needs internet; it does not depend on this Mac's local backend.

Build output: `app/build/outputs/apk/debug/app-debug.apk`.
Preserved online copy: `app/build/outputs/apk/hosted-demo/ArcheryClub-online-dev.apk`.
Package: `ph.capstone.archeryclub.dev`. Minimum Android version: Android 8 (API 26).
Use the owner-supplied credentials; passwords are intentionally absent from these documents.
Physical-phone installation and workflow checks remain to be performed on the chosen phone.

## Outstanding

SMTP sender configuration, verification/recovery email templates and actual delivery remain
unfinished. The deployed account-invitation function's denial was verified, but successful
email invitation was not exercised. Browser portal hosting, real camera video end-to-end
verification, complete module/device acceptance, human UAT/SUS and club-rule approval remain
outstanding. This milestone is a deployed and tested development backend, not full proposal acceptance.

President and officer demo terms end 1 November 2026 00:00 Asia/Manila. Event-specific grants
remain until revoked; administrator has a one-year development term. Demo event registration
closes 10 October 09:00 Asia/Manila; the event ends at 17:00 that day.

No GitHub commit or push was made as part of this deployment.
