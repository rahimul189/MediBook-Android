# Patient authentication setup and review

This step uses Firebase Authentication and the **default Cloud Firestore database**.
It does not use Realtime Database. No Console changes or rules deployments are performed by the app.

## Manual Firebase Console prerequisites

1. Open https://console.firebase.google.com/ and select the Firebase project associated
   with this app's local `app/google-services.json`. Do not upload that file or service-account keys.
2. Open **Build > Authentication > Sign-in method** (first-time projects may show
   **Get started**). Enable **Email/Password**, then **Save**. Email-link login is not needed.
3. Open **Build > Firestore Database**. If the default database has not been created,
   create it in **production mode**, reviewing the location before confirming.
4. Publish the reviewed rules below before testing registration. Do not use open/test-mode rules.

## Exactly how to publish the local rules manually

1. Open the project's **Build > Firestore Database** page and select the **(default)** database.
2. Open the **Rules** tab.
3. Save a copy of the currently deployed rules outside the repository if they need to be retained.
   Review any existing applications using this database: these rules intentionally deny every
   collection except a user's own profile and will replace the entire current ruleset.
4. Open the repository-root `firestore.rules` locally. Review its complete contents.
5. Replace the Console Rules editor contents with the **entire** local file. Do not append it
   to existing permissive rules: overlapping allow rules are combined with OR, not denied by precedence.
6. Review the changes and use the Console's rules testing tools where available.
7. Click **Publish**, and confirm the Console shows the new version as published.
8. Allow time for rules propagation before exercising the device test checklist.

These are **Firestore** rules only. Do not paste them into Realtime Database Rules.
No Firebase CLI deployment or service-account credential is required for this manual process.

## Security boundaries

- Signup creates only `users/{auth.uid}`, with exactly `fullName`, `email`, `phone`,
  `role: "Patient"`, and a server-generated `createdAt` timestamp.
- Names are nonblank and bounded to 100 characters. Email must match the authenticated token
  and be at most 254 characters. Phones are normalized to 7–15 digits with an optional leading `+`.
- Reads are owner-only direct document reads. Collection listing, profile updates, profile
  deletion, extra fields, forged timestamps, and role changes are denied.
- No Admin access is enabled. The reserved `admin == true` **custom claim** must only ever be
  assigned by a trusted future administrative process. A profile role is never an Admin privilege.
- The app rejects Admin claims and non-Patient profiles. The backend rules, not UI checks,
  enforce data access. Publish the reviewed rules before relying on these protections.
- Passwords are passed only to Firebase Authentication; they are not stored in Firestore,
  model objects, preferences, instance-state bundles, application logs, or repository state.
- Firebase manages persistent auth credentials. Each launch and patient-screen re-entry reloads
  the account, refreshes claims, and fetches the profile from the **server**, never from cached
  profile data for authorization. Offline/unverifiable sessions fail closed and are signed out.
- Logout clears Firebase Auth and the navigation task. Late session-check callbacks cannot
  reopen Patient Home. Existing theme and fixed `Asia/Dhaka` configuration are unchanged.

## Partial registration and interruption handling

Authentication account creation and a Firestore write cannot be one atomic transaction:

- A server-accepted profile write is required before registration can enter Patient Home.
- If the profile write fails, the repository tries to delete **only the newly created Auth
  account**. After deletion succeeds, the UI explains the failure and allows a fresh registration.
- If deletion fails, the user is signed out and explicitly told that the account may remain.
  Do not repeatedly register with the same email. A trusted administrator must review it.
- If the profile was saved but the subsequent verification fails, the account/profile are
  retained and the user is signed out. Retry ordinary Login after resolving the problem.
- A process kill between the two services can also leave a missing profile. Login never
  silently creates one or grants access; it signs out and explains the recovery requirement.
- For an incomplete account, the administrator should inspect both **Authentication > Users**
  and the matching **Firestore > Data > users/{uid}**. If the profile is genuinely absent,
  remove the incomplete Auth account manually (or repair it through a separately reviewed,
  trusted administrative process), then let the patient register again. Do not delete a valid
  existing patient account as a workaround for a network or permissions error.
- A Firestore write interrupted by loss of connectivity can remain pending until the server
  responds. Loading/back-navigation guards stay active while the outcome is unknown; this
  intentionally avoids deleting an account while a write may still succeed. Restore connectivity.
  If Android terminates the process, use the incomplete-account recovery above if necessary.

Requests survive Activity rotation in an in-process repository, with listeners attached only
while Activities are started. Process death does not automatically resubmit registration.

## Device / test-project checks (not executed automatically)

Use test accounts in a development Firebase project; never real patient information.

1. Submit empty/invalid forms; confirm inline errors and no network submission.
2. Register a new patient. Verify the Auth user and exact five profile fields, including a
   server timestamp. Verify no password is in Firestore. Patient Home should show the name.
3. Rapidly tap submit and rotate during the request. Confirm no duplicate submission.
4. Logout, press Back, and reopen the app. It must remain on Login.
5. Login, close/reopen the app, and background/foreground Patient Home. Confirm server-backed
   session restoration. Wrong passwords and unavailable networks must not open Patient Home.
6. In a development project, deny profile writes temporarily to test partial-registration
   cleanup; restore the reviewed rules afterwards. Test failed cleanup/network interruption
   separately. No failure may be displayed as successful registration.
7. Test missing profiles, malformed profiles, owner-read permission denial, disabled/deleted
   Auth accounts, and profiles with `role: "Admin"`. All must fail closed.
8. Through a trusted test environment only, use a test account with the `admin: true` custom
   claim. It must not enter this patient flow, even if its profile says Patient.

### Rules checks before production use

Use the Firestore rules simulator or a separately configured Firebase Emulator Suite harness
(the repository does not install one in this step). SDK client tests should cover:

| Request | Expected |
| --- | --- |
| Unauthenticated profile read/create | Denied |
| Authenticated own exact Patient profile create, matching email, server timestamp | Allowed |
| Create another UID's profile | Denied |
| Create role Admin, add extra field, forge timestamp, use wrong type/email | Denied |
| Blank/oversized name or invalid phone | Denied |
| Read own profile / read another profile | Allowed / Denied |
| Query/list users, update any profile field, delete profile | Denied |
| Read/write unrelated collection, including with Admin claim | Denied in this step |

Rules have not been deployed or emulator-tested by the coding agent. A successful Android
build verifies compilation only, not live Firebase configuration or authorization behavior.
