# Development setup

The approved proposal governs all 15 modules. The user approved Kotlin/Compose Android
with Supabase instead of the proposal's React/Prisma stack. The browser companion in
`portal/` supplies applicant and partner access from phones, tablets and computers.

This is development work. Passing local tests does not establish hosted readiness or
stakeholder acceptance. Consult `requirements-matrix.md` and `progress-checkpoint.md`.

## Android

Open this repository in Android Studio. Use its bundled JDK and install Android SDK 37.
Copy `backend.properties.example` to ignored `backend.properties`, and set the project URL
and **publishable** key. The supplied development project is
`https://cidxuzrzgexkejssoglk.supabase.co`. Its publishable key is already in the local ignored
configuration; each teammate supplies their own configuration file. No privileged secret
belongs in the app, browser, Git repository, or chat.

Run `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
The installable APK is `app/build/outputs/apk/debug/app-debug.apk`. Both build variants
now open the connected system. The earlier sample repository remains in the source as
reference and for its old tests, but it is not the app entry point.

## Hosted development database

**Current project deployed 9 October 2026:** all twelve versioned migrations and both Edge
Functions are installed in `cidxuzrzgexkejssoglk`. The five owner-authorized demo accounts
passed hosted login/refresh/permissions/storage checks. Do not run the fresh-project SQL bundle
or initial bootstrap again on this project. Future schema changes use new migrations.
The procedures below also describe setup of another fresh development environment.

Run `node tools/check_hosted_backend.mjs` for non-mutating public endpoint checks and
`supabase db query --linked --file supabase/verify-development.sql` for owner-only structural
evidence. `tools/setup_hosted_demo.mjs` is restricted to this exact project and the five approved
October demo identities. It requires private credential input and a signed-in CLI; it refuses
to overwrite unrelated accounts and removes its private password input on success.

Use a fresh Supabase development project. Existing projects with records need a reviewed
migration plan first. Do not run a database reset against hosted data.

Preferred: install the official Supabase CLI, authenticate under your own account,
link project `cidxuzrzgexkejssoglk`, then use `supabase db push` to apply the migrations
in `supabase/migrations/` in order. The CLI may ask for the database password directly;
do not put it in a source file or message.

Alternative when the CLI is unavailable: generate `supabase/deploy-development.sql`
with `python3 tools/build_sql_bundle.py`, review it, and run the complete file once
in the project's SQL Editor. The bundle is atomic: an error rolls back all its changes.
It does not create an administrator or delete existing records. This alternative does
not populate the CLI's migration-history table; before later switching to CLI deployment,
reconcile its history with the applied migration filenames using `migration repair`.
Do not blindly push the same migrations again.

Expose only `public` through the Data API. `app_private` contains private tables and
must **not** be added as an exposed schema. The public functions authorize commands;
the workspace query uses row-level security. Ordinary clients have no direct table
write access. The `club-documents` bucket is private with a 20 MB file limit.

## Authentication and first administrator

Enable email signup and email confirmation. Configure an approved SMTP sender before
group testing; the default development email service may restrict delivery.
Use verification/recovery email templates containing `{{ .Token }}` so users can enter
the code in Android or the browser portal. Set the site's URL to the deployed portal and
allow only its actual redirect URLs. Test signup, expired/wrong codes, recovery, refresh,
restart, sign-out and disabled-account access on the hosted project.

Deploy `supabase/functions/create-account` for M14's administrator account-creation form.
Configure the invitation email template to include `{{ .Token }}` too. The recipient uses
the invitation-code option and chooses their own password. This creates an ordinary account;
it never grants membership or officer authority. Email invitations are sent only when an
authorized administrator submits that form. The server checks current administration access
before using its privileged Auth API; neither client receives that privilege.

The project owner must select the first administrator's email. Register and verify that
account normally. Replace the placeholder in `supabase/bootstrap-admin.sql`, review the
one-year development assignment, then execute it through trusted database administration.
This refuses a second initial bootstrap. Subsequent assignments, positions, retirement
and disabling belong to M14. Administrator permission does not automatically grant all
operational roles. Assign approved responsibilities separately in M14.

Use M14 to enter club-approved application/renewal requirements, membership categories,
retention policy and role terms. Leave `rules_validated` false until the club approves
them. Configure and validate academic terms/election rules in M9 and equipment
specifications/safety decisions in M10. Never substitute fixtures for those decisions.

## Training video verification

Deploy `supabase/functions/verify-training-video` using the CLI. The function checks the
user with Supabase Auth, downloads using that user's storage permission, and records a
server-calculated duration through a service-role-only database function. Privileged
credentials are read only from the function's server environment.

It accepts nonfragmented MP4 with bounded movie, track, sample and presentation timings,
at most 10 seconds and 20 MB. Unsupported/truncated containers and altered short duration
headers with longer sample timelines are rejected. Export unsupported recordings as a
normal MP4 clip. Test actual Android camera recordings on the deployed function before
claiming the video workflow verified. The parser is not a video transcoder or malware scanner.

## Browser portal

Run `python3 tools/generate_catalog.py` after changing the form catalogue.
Copy `portal/config.example.js` to ignored `portal/config.js` and supply only the HTTPS
project URL and publishable key. Serve `portal/` over HTTPS; for local inspection,
`python3 -m http.server 8080 --directory portal --bind 127.0.0.1` suffices.
Do not open the HTML as a filesystem URL. Publish the portal only to an approved host,
then print/share a QR code containing its actual HTTPS URL. No public deployment has
been made automatically. The browser stores the session in sessionStorage for the current
tab and clears it at sign-out. Rendered user data uses text nodes, not HTML injection.

## Local verification

`backend-tests/` uses PGlite PostgreSQL with lightweight Auth/Storage schema fixtures.
Install its locked dependencies with pnpm, then run `pnpm test` there. These tests check
transactional workflow logic and row-level permissions; they do not stand in for real
Supabase services. `video.test.mjs` checks bounded MP4 parsing.

The isolated CLI test project is `archery-proposal-test`, API port 55421, database 55422,
mail inbox 55424. These ports deliberately avoid the older local `ArcheryClub` project's
existing records. `supabase start` applies migrations to the isolated project. Never use
`db reset` or remove volumes belonging to the older project.

### Run the local backend in Android Studio's emulator

The ordinary Android Studio **app** configuration uses the hosted URL in `backend.properties`.
For the isolated local database, save `supabase status -o json` to a private temporary file,
then run `python3 tools/configure_local_test.py /path/to/private-status.json`. This creates
ignored `backend.local.properties`; never share the status file because it includes server secrets.
With Pixel 7 running in Android Studio, use its Terminal to run
`./gradlew -PlocalBackend=true :app:installDebug`, then open the installed local app on Pixel 7.
Its application ID is `ph.capstone.archeryclub.local`, separate from hosted `.dev`.

On Android 17 or later, select **Allow local connection** and allow the system's nearby-device
permission. If declined, the app stays on its explanation screen; it does not attempt login.
Permission can also be restored with **Open app permissions**. The local endpoint is restricted
to `http://10.0.2.2:55421` in debug builds. The normal hosted app and release app use HTTPS and
do not ask for this permission. Keep the isolated Supabase services running during a local demo.

See [Android's local network permission](https://developer.android.com/privacy-and-security/local-network-permission).

## Required release evidence

Before calling the system complete, record hosted tests for every matrix acceptance
criterion, Android device/restart checks, browser applicant/partner checks, private
document access, election privacy, role turnover, and cross-module reconciliation.
Run the proposal's human UAT/SUS and pilot timing evaluation separately. A passing build
or fixture test alone is not acceptance.

References: [Supabase local development](https://supabase.com/docs/guides/local-development/cli/getting-started),
[API keys](https://supabase.com/docs/guides/getting-started/api-keys),
[Edge Function authentication](https://supabase.com/docs/guides/functions/auth).
