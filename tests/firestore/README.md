# Java/JUnit Firestore Security Rules integration tests

## Run without JavaScript, Node or the Firebase CLI

Prerequisites: **Java 21+**, the repository Gradle wrapper, and the pinned official Firestore
emulator JAR **1.22.0**. Gradle resolves only JUnit 4.13.2 and Gson 2.14.0 for this separate Java
test module. It adds no Android application dependencies and needs no Android device/AVD.

From the Android repository root:

```powershell
.\gradlew.bat :firestore-rules-tests:test --console=plain
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

The integration task always runs afresh, starts **its own** Java emulator, runs the serial suite,
exports Rules evaluation coverage and shuts the process down. It refuses an occupied port instead
of attaching to or clearing an unknown emulator. The unchanged `firebase.emulators.json` must still
specify `127.0.0.1:8080`, single-project mode, disabled UI and the root `firestore.rules` file.
All data requests are hard-fixed to **`demo-medibook`**, database **`(default)`** on that loopback
endpoint. A different `FIRESTORE_EMULATOR_HOST` is rejected, never silently overridden.

### Provision the Java emulator once

An existing Firebase emulator cache is reused at
`$HOME/.cache/firebase/emulators/cloud-firestore-emulator-v1.22.0.jar`.
Alternatively set `MEDIBOOK_FIRESTORE_EMULATOR_JAR` to a copy outside the repository. Every run
checks its SHA-256 against the official pinned artifact:

```text
9b6498b7f62714d67f48f59b3818883cd682dbcd46b9f59511de81c97bb5166c
```

Official public download (no sign-in/private key/Firebase project access):

```text
https://storage.googleapis.com/firebase-preview-drop/emulator/cloud-firestore-emulator-v1.22.0.jar
```

For example, download to a new external file, verify it, then set its path:

```powershell
# Choose a NEW filename; do not overwrite an existing cache or backup.
$jar = Join-Path $env:TEMP 'medibook-firestore-emulator-v1.22.0.jar'
if (Test-Path -LiteralPath $jar) { throw 'Choose a new file, or verify/reuse the existing file separately.' }
Invoke-WebRequest -Uri 'https://storage.googleapis.com/firebase-preview-drop/emulator/cloud-firestore-emulator-v1.22.0.jar' -OutFile $jar
if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne '9B6498B7F62714D67F48F59B3818883CD682DBCD46B9F59511DE81C97BB5166C') { throw 'Checksum mismatch' }
$env:MEDIBOOK_FIRESTORE_EMULATOR_JAR = $jar
```

Missing JAR, wrong checksum, wrong environment/configuration, unavailable port, startup failure or
Rules compilation failure is a **failed** test run, not a skipped/passing integration test.

## Why these requests actually exercise Security Rules

The Firebase Admin/Google Cloud Java server libraries bypass Firestore Rules and are **not**
permission test clients here. Java's `HttpClient` calls the **documented Firestore REST v1 API**.
Firebase documents that Firebase ID-token and unauthenticated REST requests use Security Rules,
unlike service-account/OAuth requests. This is the supported client API path appropriate to a
plain-JVM test runner; it does not require embedding the Android SDK or a JavaScript test SDK.

- Patient/Admin requests carry synthetic, unsigned emulator Firebase ID tokens with explicit UID,
  project and claims. Admin's Boolean `admin=true` is a tested end-user claim, not an IAM/Admin SDK
  bypass. Anonymous requests have **no Authorization header**.
- No Auth emulator accounts, real Authentication accounts, real custom claims, credentials,
  `google-services.json`, ADC, service-account keys or production resources are read/modified.
- Privileged **emulator-only** clear/seed requests use a separate fixture helper with the emulator
  owner credential. They never count as allow/deny evidence and return no assertion response.
- Canary cases fail if anonymous requests bypass Rules, Patient identity is ignored, malformed
  claims grant access or Admin end-user tokens bypass unrelated-collection/delete restrictions.
- Denials require exactly **403 + `PERMISSION_DENIED`**. Transport errors, invalid arguments,
  authentication errors, missing documents/indexes and arbitrary exceptions cannot pass a denial.
- Data transport has no credentials provider, proxy or redirect fallback. It rejects non-loopback,
  different-project/database/port and unexpected targets before sending requests.
- Server timestamps use REST `REQUEST_TIME` field transforms; deletions use update masks. Explicit
  REST field types permit Rules to inspect invalid/null/noninteger/NaN/infinite inputs directly.

References:

- https://firebase.google.com/docs/firestore/use-rest-api#authentication_and_authorization
- https://firebase.google.com/docs/emulator-suite/connect_firestore#cloud_firestore_rest_api
- https://firebase.google.com/docs/firestore/reference/rest/v1/Write

## Coverage and results

See [COVERAGE.md](COVERAGE.md) for the one-for-one map of all 37 previous security cases and the
additional canaries. Coverage includes Patient profile ownership/role protection, Doctor/Schedule
permissions, filtered reads, schema/types/ranges/timestamps/IDs, deletion and atomic-move denial,
malformed profiles/claims, denied unrelated paths/groups and the existing overlapping-new-ID
limitation. Values and identities are synthetic; no sample application data is installed.

Generated files stay under ignored `tests/firestore/build/`:

- `test-results/test/TEST-*.xml`: JUnit test counts/failures.
- `emulator/firestore-emulator.log`: owned-server startup and evaluation diagnostics.
- `emulator/rule-coverage.json`: raw Rules evaluation coverage.

HTML test reports are disabled for this module, avoiding generated JavaScript report assets. The
Android/Gradle tools can still produce ignored HTML/JavaScript reports under their normal build
directories; **do not include build outputs, caches or `.git` history in an academic source ZIP**.
Prior Git history is intentionally not rewritten. Until reviewed/committed separately, archiving
the old HEAD would still contain the previous tests and omit uncommitted Patient UI/migration work.

## Limits

These are actual Rules integration tests, not Android SDK/Activity/authentication end-to-end tests.
They do not test real ID-token signature verification, production IAM, mobile offline cache,
repository transaction races, production indexes or every emulator/production limit difference.
The emulator accepts unsigned fake tokens only for local testing. Never use them in an app.

The overlap case proves Rules allow valid overlapping windows at new IDs; it does **not** claim
global concurrency exclusion or booking safety. Android mapping, validation and ViewModel tests
remain separate. Existing behavior/checklists are in `docs/admin-doctor-firestore.md`,
`docs/admin-doctor-schedule-firestore.md` and `docs/patient-doctor-directory-ui.md`.

`firestore.rules` stays byte-identical. No deployments, real account/claim changes, production data
operations, Realtime Database changes, commits or pushes are part of this migration.

## Verified migration results (2026-11-06)

- The four canaries passed first. All **41 Java integration tests** then passed before any legacy
  test was deleted: **15 Doctor/Patient + 22 Schedule + 4 canaries**, zero failed/errored/skipped.
  The one-for-one coverage review is in `COVERAGE.md`, including the original invalid-value loops.
- Only then were the two legacy script tests, both Node package manifests, their obsolete dependency
  directory and legacy emulator debug log removed. The combined Android/Java command was rerun
  successfully **after Node tooling removal**; the integration task ran afresh, not from cache.
- Historical Step 77 Android result: debug assembly passed, **178 Android unit tests** passed, and
  lint reported **0 errors, 11 existing warnings**.
- Source checks found **0 nonignored JavaScript/TypeScript files and 0 Node package files**.
  One existing, ignored Gradle-generated `app/build/reports/tests/testDebugUnitTest/js/report.js`
  remains on disk; exclude generated build directories from the submitted source repository/ZIP.
- Hashes verified all **136 protected pre-existing files** unchanged, including every Android
  source/resource/test, all uncommitted Step 75 UI, the local rules/configuration and the untracked
  IDE file. Local `firestore.rules` SHA-256 remains
  `37EDD2E12363C60A3900CA1AA5CB62D5F9D117FA9F6B668A5848829344707BD0`.

## Step 77 file inventory (separate from pre-existing Step 75 work)

Added under `tests/firestore/`:

- `build.gradle.kts`, `.gitignore`, `COVERAGE.md`.
- Under `src/test/java/com/example/medibook/rules/`: `RulesIntegrationSuite.java`,
  `LocalFirestoreEmulator.java`, `RulesClient.java`, `RulesFixtures.java`, `ClientRulesCanaryTest.java`,
  `DoctorRulesTest.java`, `ScheduleRulesTest.java`.

Modified: root `settings.gradle.kts` (independent Java test module), root `.gitignore` (retired Node
tooling ignores), this README, and testing guidance in `docs/admin-doctor-firestore.md`,
`docs/admin-doctor-schedule-firestore.md`, `docs/firebase-auth-setup.md`.

Removed under `tests/firestore/`: the former `doctor.rules.test.mjs`, `schedule.rules.test.mjs`,
`package.json`, `package-lock.json`, generated `node_modules/` and the old `firestore-debug.log`.
Java emulator logs now live only under the module's ignored `build/emulator/` directory.

Existing Patient UI modifications/untracked files remain untouched. Nothing is staged, committed
or pushed; Git history and the external backup are preserved.

## Step 78 Patient live-query regression

`DoctorRulesTest.patientProfileQueryUsesActiveStatusAndStableDocumentId` adds the exact Patient
profile lookup shape: `active == true` combined with the document path identity. An active doctor
is readable; an inactive, deleted or missing doctor yields canonical `PERMISSION_DENIED` from
Rules, not an empty result. The Android profile therefore treats either a denial or a missing
result as unavailable and clears the displayed doctor and schedules.

Current suite: **42 Java integration tests** (16 Doctor/Patient, 22 Schedule, 4 canaries). This is
**not** a fifth canary and does not change or deploy `firestore.rules`.
