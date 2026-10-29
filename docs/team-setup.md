# Group development setup

Clone https://github.com/carllperez/ArcheryClub and open its root folder in Android Studio.
Install Android SDK 37 and allow Gradle to synchronize. Copy `backend.properties.example`
to `backend.properties` and obtain the development project URL and publishable key from
the project owner. These client values are sufficient to connect; never put a service-role
key or Supabase management token in the application. Obtain test-account credentials
privately from the owner. Do not commit passwords.

Use the normal **app / Run** configuration. It opens `connected/ConnectedApp.kt` against
the hosted backend. Do not rerun database setup on the existing shared project. Local
testing is optional and uses a separate database and `-PlocalBackend=true` build.

## Integration of the parallel GitHub changes

The October 10 integration preserves commits 123414d, a75f50a and a97bfb0, including their
announcements UI, member screens, models, repository and regression tests. The active
entry point remains the proposal-wide connected application.

The unused preview/backend code and separate fictional officer prototype were removed on
28 October after their compatible features had been integrated into `connected/`. Git history
retains the original contributions.

The parallel SQL package was moved to `supabase/reference/member_backend_202610020001.sql`.
It creates a different public-schema data model with its own signup behavior. It must
not be applied alongside the deployed `app_private` schema or treated as migration 13.
All fifteen migrations in `supabase/migrations/` are deployed, including workflow completion,
announcement editing and non-retryable version conflicts. Configuration
comes from `backend.properties`, not the historical `local.properties` backend example.
The accidental self-referencing Git link `ArcheryClub-git` is excluded from the merged tree;
the actual source and its Git history are retained.

## Current verification

See `requirements-matrix.md` and `hosted-deployment.md` for evidence and open acceptance
criteria. A successful build or a module screen is not proof of a complete workflow.
All fifteen proposal modules remain in scope and In progress. Coordinate shared test-data
changes with the group; use separate fixtures for destructive or irreversible workflow tests.

## Shared-account and release checks

A Git clone does not include ignored `backend.properties`. Use the existing hosted project
URL and its publishable key, then rebuild; do not choose the emulator-local build. The five
demo identities exist online, not only on the owner's Mac. Obtain their exact passwords
privately; do not guess or reset shared passwords. Sign-out now targets the current session.

Email remains disabled and portal hosting is deferred. Test officer/President role assignments
currently end on 1 November 2026 at 00:00 Asia/Manila. Approved successors or a new term must
be assigned through M14 by the owner; do not remove expiry enforcement to keep a demo working.
Use the current requirements matrix rather than older dated checkpoint documents for status.
