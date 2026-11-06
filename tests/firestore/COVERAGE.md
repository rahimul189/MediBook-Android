# Java Security Rules replacement coverage

The baseline contained 37 JavaScript test cases: 15 Doctor/Patient cases and 22 Schedule cases.
The Java classes below preserve those cases and their invalid-value loops, not merely their test
counts. Four additional client/transport canaries distinguish actual Rules checks from fixture
bypass. Validation results are recorded in README.md after the complete replacement passes.

## DoctorRulesTest — baseline 15 cases

| Java method | Retained security assertions |
| --- | --- |
| `anonymousDoctorAccessDenied` | Anonymous get, unrestricted/active-filtered query and create denied |
| `patientReadsActiveDoctorsWithFilteredQueriesOnly` | Active get/query allowed; inactive/unfiltered/inactive-filtered reads denied |
| `missingProfilesAndProfileOnlyAdminsDenied` | No-profile and profile-role-only Admin accounts cannot read/write Doctors |
| `malformedAdminClaimsGrantNeitherRole` | String/numeric/false/null claims do not grant Admin or Patient doctor access |
| `booleanClaimAdminReadsBothStatusesAndLists` | Boolean Admin end-user token allows active/inactive gets and unfiltered query |
| `patientDoctorWritesDenied` | Patient create, edit, disable, enable and delete denied |
| `adminCreatesEightFieldSchemaWithServerTimestamps` | Correct fields, integer hundredths and equal resolved create/update timestamps |
| `adminEditsAndChangesStatusWithoutChangingIdentity` | Stable document ID/createdAt, advancing updatedAt, disable/enable, delete denied |
| `requiredAndAllowedDoctorFields` | Every missing field and unknown id/role/ownerUid/schedule rejected |
| `doctorTypesTextControlsLengthsAndFeeBounds` | Wrong types, Unicode blanks, controls, lengths, negative/overflow/fraction/string/NaN/infinite fee rejected |
| `doctorValidBoundaryValues` | Text limits, maximum/zero fee and normal biography line breaks accepted |
| `doctorTimestampAndImmutableFieldValidation` | Forged/missing timestamps, changed createdAt, deleted fields, extra IDs and nonboolean status rejected |
| `patientProfileOwnershipAndRoleProtection` | Valid own creation/get allowed; other-owner access, listing, role changes and deletion denied |
| `patientProfileValidationAndAdminWriteDenial` | Profile role escalation, extra claim fields, mismatched email, phone/name/time errors and Admin profile mutations denied |
| `unrelatedCollectionsAndDoctorSubcollectionsDenied` | Patient/Admin/anonymous cannot read/write appointments, top-level schedules, unrelated or private subcollections |

## Step 78 Patient live-query addition — 1 case

| Java method | Added assertion |
| --- | --- |
| `patientProfileQueryUsesActiveStatusAndStableDocumentId` | Exact `active=true` + document-ID profile query reads an active doctor; inactive/missing/deactivated paths are denied, not returned empty |

Current suite total: **42 tests** (16 Doctor/Patient, 22 Schedule, 4 canaries). The original 37
security cases and their invalid-value loops remain intact.

## ScheduleRulesTest — baseline 22 cases

| Java method | Retained security assertions |
| --- | --- |
| `anonymousScheduleAccessDenied` | Anonymous get/list/active-filtered query/create/update/delete denied |
| `booleanClaimAdminReadsAllSchedules` | Boolean Admin reads both statuses and directories under active/inactive parents |
| `validPatientReadsOnlyActiveSchedulesOfActiveDoctor` | Active get and active-filtered query allowed; inactive/unfiltered reads denied |
| `patientCannotReadInactiveOrMissingParents` | Active schedule of inactive/missing parent denied, including filtered query |
| `patientAccessTracksDoctorAndScheduleStatus` | Admin parent/schedule changes immediately affect Patient reads |
| `patientScheduleWritesDenied` | Patient create/edit/disable/enable/delete denied |
| `missingProfilesAndProfileOnlyAdminsDenied` | No-profile and profile-role-only Admin cannot read/query/create schedules |
| `malformedAdminClaimsGrantNeitherRole` | False/string/numeric/null/object claims denied for reads/queries/create/update/delete |
| `patientProfileMustBeCompleteAndValid` | Every missing profile field, extra fields, invalid values/types and role/email mismatches rejected |
| `tokenEmailMustMatchProfile` | Missing/wrong token email cannot use profile for schedule access |
| `adminCreatesSixFieldMinuteSchema` | Exactly six fields, ISO day, integer minutes/duration and resolved server updatedAt |
| `adminEditsStatusesWithoutChangingParentOrIdentity` | Stable schedule ID, retained disabled record, unchanged Doctor document |
| `adminCannotDeleteOrAtomicallyMoveSchedule` | Admin deletion and delete+copy batch denied atomically |
| `requiredAndAllowedScheduleFields` | Every missing field and extra/mutable IDs/timezone/createdAt/role/appointments rejected |
| `weekdayTimeTypeRangeAndOrderingValidation` | Invalid days, fractional/string/minute limits, equal/reversed/overnight windows rejected |
| `durationPolicyAndWindowFit` | Nonpositive/out-of-range/fractional/string/NaN/infinite/non-five-multiple/window-too-short rejected |
| `validMinuteWeekdayDurationBoundariesAndRemainder` | First/last minute, ISO endpoints, minimum/maximum slot, remainder and inactive accepted |
| `wrongTypesAndNullsForEveryScheduleField` | Null/map for every field and string/numeric active rejected |
| `serverTimestampAndVersionValidation` | Forged/missing/nonadvancing timestamps and deletion of every required field rejected |
| `parentExistenceAndSafeIdentifierBounds` | Missing parent and unsafe/overlong IDs rejected; 128-char/underscore/dash IDs and inactive parent Admin create accepted |
| `unrelatedCollectionsAndCollectionGroupDenied` | Patient/Admin/anonymous forbidden paths and schedule collection-group queries denied |
| `overlappingNewIdsRemainAllowedByRules` | Boolean Admin token can commit valid overlapping new IDs; no global overlap exclusion claim |

## Assertion integrity and limits

- Every denial must be **HTTP 403 with canonical `PERMISSION_DENIED`**, not a network failure,
  malformed request, missing index, 400, 401, 404 or arbitrary exception.
- Allow assertions use anonymous/no-header or synthetic Firebase ID-token clients only. The token's
  Boolean `admin` custom claim is an application role, **not** an Admin SDK or emulator-owner bypass.
- Emulator-owner requests are restricted to fixture clear/seed helpers, which return no assertion
  response. No real credentials, OAuth service accounts, ADC or Firebase Auth account/claim mutations
  are needed. Mock unsigned ID tokens are usable only against the owned emulator.
- Java uses Firestore's documented REST v1 client API. It does not test Android SDK caching, rendering,
  lifecycle, repository transactions or production IAM/ID-token signature validation. The old suite
  also did not execute Android repository/Activity code. REST field representations and server-time
  transforms are explicit, so invalid data is sent to Rules rather than rejected by a Java model.
- The emulator does not enforce every production index/limit/transaction behavior. The overlap test
  proves a policy limitation, not an Android concurrency stress test or booking guarantee.
- No Rules file change, production operation, deployment, commit or push is part of this migration.
