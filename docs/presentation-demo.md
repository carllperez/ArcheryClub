# Presentation setup — Saturday, 10 October 2026

## Current setup: hosted Supabase (9 October)

The online development backend is deployed. Use this setup for the presentation and for
sharing an APK with groupmates. The device needs an internet connection; this Mac's Docker
services do not need to stay running for the hosted app.

1. Open this ArcheryClub directory in Android Studio.
2. Select **app** and **Pixel 7**, then click **Run**. The normal configuration reads the
   hosted URL and publishable key from ignored `backend.properties`.
3. Sign in using an approved account below and its owner-supplied password.

| Account | Hosted demo access |
|---|---|
| `carll_perez@dlsu.edu.ph` | Administrator plus President approval |
| `testMember@dlsu.edu.ph` | Active member plus coordinator for the demo event |
| `testApplicant@dlsu.edu.ph` | Draft applicant; no membership or approval privileges |
| `testOfficer@dlsu.edu.ph` | Active member plus eight approved officer responsibilities |
| `testPartner@dlsu.edu.ph` | Partner representative for the designated demo club/event |

All five hosted logins, session refresh and role checks passed. They are owner-provisioned
test identities; no verification email was sent and no real club membership is represented.
Public signup still requires email confirmation. SMTP delivery, recovery and full module
acceptance remain outstanding. Passwords are not stored in this guide or the repository.

Installable hosted APK: `app/build/outputs/apk/debug/app-debug.apk`, package
`ph.capstone.archeryclub.dev`. Share this APK privately with the intended testers; the local
fallback APK below connects to this Mac instead. Do not share an administrator password with
general testers. A physical phone can use the hosted APK over the internet; repeat login,
restart, sign-out and relevant workflow checks on that phone before presenting it.

A preserved online copy is `app/build/outputs/apk/hosted-demo/ArcheryClub-online-dev.apk`.
On October 9 the user manually signed in on Pixel 7 after earlier emulator keyboard issues.
Your club and M1's Test Member profile loaded. Android Studio Stop then Run restored the
signed-in workspace without credentials. Hosted device sign-out persistence and the other
role workflows still need rehearsal; hosted server-side login tests passed for all five accounts.

President/officer assignments last through October 31. The fictional interclub event is
October 10, 09:00–17:00 Asia/Manila; registration closes at 09:00. Partner and coordinator
grants are separately revocable. Do not claim registration remains open after that deadline.

## Local fallback: this Mac and Android Studio

This is a development demonstration using real Supabase services running locally on this Mac.
The member account is fictional, and records are stored in the isolated test database. It is
not evidence that the hosted system or every proposal module is complete.

1. Open ArcheryClub in Android Studio and start **Pixel 7** from Device Manager.
2. Keep the local Supabase services running. The project is `archery-proposal-test` on port 55421;
   do not reset the separate older `ArcheryClub` database on port 54321.
3. In Android Studio's Terminal, run `./gradlew -PlocalBackend=true :app:installDebug`.
4. Open the installed local app on Pixel 7. If necessary, run
   `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am start -n ph.capstone.archeryclub.local/ph.capstone.archeryclub.MainActivity`.
5. Select **Allow local connection**, then **Allow** on Android's nearby-device prompt.
6. Sign in with one of the five approved demonstration accounts:
   administrator/President, test member/coordinator, test applicant, test officer or test partner. If the earlier temporary member is still
   signed in, select **Sign out** first. Use the passwords supplied by the owner; they are
   not stored in this guide or the repository. The older random test account in ignored
   `local-test-account.json` remains only for regression tests.

The normal **app / Run** configuration uses the deployed hosted `backend.properties`.
Use the local command above only for the local fallback. The local application ID
is `ph.capstone.archeryclub.local`, while the hosted development application is `.dev`.
The tested local APK is saved at `app/build/outputs/apk/local-demo/ArcheryClub-local-demo.apk`;
you can drag it onto the running Pixel 7 to reinstall it without rebuilding. This APK targets
the Mac's local backend and is not the standalone-phone build.

The approved roles are applied. See `role-access-proposal.md` for the five email addresses and
permission boundaries. The officer and President demo roles last through October 31. Partner
and coordinator access is limited to **CAPSTONE 1 Demonstration Interclub Event**, scheduled
for October 10, 09:00–17:00 Asia/Manila, with **Demonstration Partner Club**. All event details
are fictional and local. Account/role changes are backend data; no app rebuild is needed.
Sign out and sign in as the intended account, or refresh an already signed-in account's records.

On 7 October, local sign-in, process restart persistence, sign-out persistence, incorrect-password
rejection and login after the rejection all passed on Pixel 7. The account was left signed in.

Later on 7 October, the three owner-requested accounts were created and their supplied passwords
verified against local Auth. The administrator has M14 account/role administration; officer
responsibilities remain separate. The member has an active test membership seeded explicitly
for demonstration, without inventing a screened application. The applicant has a draft application
and no membership. Both test accounts were denied role assignment and could not read other
private profiles. Local email confirmation was set as test setup; no email was sent and no
hosted email ownership verification is claimed. This describes the original October 7 local
setup; the separate online accounts were explicitly approved and created on October 9.

Demonstrate successful sign-in, the member's available modules, closing/reopening the app
without re-entering the password, and signing out. Club rules remain explicitly labelled
awaiting validation. Demonstrate additional workflows only after their device checks pass.

Rehearse before presenting, including after restarting the Mac. If Supabase is stopped,
start the isolated project according to `backend-setup.md` before opening the app. Do not
reset its database: that would remove the fictional account and its demonstration records.

See `requirements-matrix.md` for remaining proposal acceptance work.
