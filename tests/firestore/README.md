# Local Firestore Security Rules tests

This suite never uses production Firebase. It fixes the project to `demo-medibook`, the host
to `127.0.0.1:8080`, and uses only synthetic emulator identities. `clearFirestore()` clears
that **demo emulator** only. It does not read ADC, `google-services.json` or private keys.

Prerequisites: Node 24.12+ and Java 21+ for the pinned Firebase CLI/Firestore Emulator.
Commands from the Android repository root:

```powershell
npm --prefix tests/firestore install --no-audit --no-fund
npm --prefix tests/firestore run test:rules
```

The pinned local Firebase CLI starts only the Firestore emulator, executes the Node tests,
and stops it. The root `firebase.emulators.json` references the actual local rules file.
A first run may download an emulator JAR. No login or deploy command is used.
Keep port 8080 free and do not change this suite to use the production project ID or endpoint.
With an already running local demo emulator, `npm --prefix tests/firestore test` runs just
the tests. Any configured `FIRESTORE_EMULATOR_HOST` must equal `127.0.0.1:8080`.

Coverage (`doctor.rules.test.mjs` and `schedule.rules.test.mjs`, run serially):

- Anonymous, Patient, Boolean-claim Admin, profile-only Admin, missing-profile accounts and
  malformed `admin` claims (string/numeric/false/null).
- Patient active-only direct reads and filtered queries; denied unfiltered/inactive queries.
- Admin create/edit/deactivate/reactivate, immutable ID/`createdAt`, server `updatedAt`.
- Denied deletion even for Admin; extra/missing/wrong-type fields, text/control/length/fee
  boundaries, forged timestamps and schema-breaking updates.
- Regression checks for Patient profile create/owner-get, no profile mutation/listing/deletion,
  no role escalation, and denied unrelated/nested collections.
- Schedule Admin add/list/edit/enable/disable, stable path IDs, retained disabled records,
  deletion/atomic-move denial, and unchanged parent Doctor fields.
- Schedule Patient reads require the complete valid profile, matching token email, an active
  doctor and an active schedule; queries require `where('active', '==', true)`. Missing/malformed
  profiles, malformed claims, inactive/missing parents, writes and collection-group reads are denied.
- Exact six-field schedule schema, ISO weekdays, integer minutes `0..1439`, same-day ordering,
  duration `5..180` in multiples of five fitting the window, safe ID bounds, server `updatedAt`,
  and denied missing/extra/null/wrong-type fields or forged/nonadvancing timestamps.
- An explicit limitation test proves that Rules accept valid overlapping windows with different
  new IDs. Passing tests are **not** a global overlap-prevention or booking guarantee.

Android `DoctorMapperTest` separately checks exact integer-hundredths conversions, all schema
fields/types, timestamp mapping and write-field allowlists. Device/repository integration
checks (including concurrent editor transactions and lifecycle interruptions) are documented
in `docs/admin-doctor-firestore.md`; Rules tests do not exercise Android Activities.
Schedule behavior and the physical-device review checklist are in
`docs/admin-doctor-schedule-firestore.md`. Java schedule tests exercise mapping, validation,
half-open conflicts, immutable sorted directories and retained ViewModel Tasks with offline fixtures;
they do not run Android Firestore transactions or actual Activity lifecycle events.

Generated node modules, emulator state and debug logs are ignored. No actual accounts or
custom claims are created/modified by these fake emulator contexts. Never run `firebase deploy`
as part of this test command or treat a passing test as evidence of deployed production rules.
