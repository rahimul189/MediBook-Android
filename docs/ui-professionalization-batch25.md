# Batch 2.5 — usability and fixed slot numbers

## Audit and UI

Source audit covered all 13 concrete Activities and their session base, 27 layouts, adapters,
menus, resource strings, confirmations/loading/empty/error states and navigation. Findings fixed:
duplicate Schedule publishing/Patient appointments toolbar actions, redundant Find Doctor card,
repeated profile Back buttons, oversized welcome/technical cards, developer diagnostics in normal
copy, incorrect “currency not configured” wording, persistent architecture commentary and filter
state lost on return/rotation. Forms remain scrollable; no new screen, asset or Batch 3 component.

The Patient header has one My Appointments card and accessible directory search/filter controls.
Alert cards are compact and hidden when empty/unconfigured; successful live updates hide their
manual retry button. Booking shows Doctor/date/fee/available times/number/confirmation. Patient
self-cancel and Admin individual cancel, retained history and eligible release remain intact.
Technical paths, SDK failures and verification details stay in Logcat, not normal UI messages.

## Repeated session loading

Cause: every protected Activity called `checkSession()` onStart, which always reloaded Auth,
forced a token refresh and fetched the profile, publishing a null-user loading state. Directory
ViewModels also refreshed on every re-entry. The shared appointment loader used match_parent
height inside a vertical layout, potentially consuming all available content space.

Fix: a maximum **2-minute in-process lease** for the same UID and a non-near-expiry SDK token,
established ONLY after refreshed signed claims + server profile pass the existing SessionPolicy.
Cold/expired/token-changed sessions still reverify. Account changes/logout invalidate the lease;
generation + UID checks reject late callbacks. SDK notification ordering is guarded during own
sign-in/registration/rollback. No Admin privilege is persisted to preferences, intents or disk.
Cold checks use a compact inline indicator. Admin directory reads reuse up to 30 seconds of
same-owner state; explicit refresh and confirmed editor changes still reload. Server Rules and
online booking/cancellation checks remain authoritative. This is not immediate claim revocation.

## Durable slot numbers (Firestore only)

- Existing inventory/appointment/lock schemas remain **12 / 17 / 6 fields**. No reservation or
  cancellation write shape changed. Transient model numbers are not written into appointments.
- Counter: `doctorSlotInventory/{doctorId}/dates/{dateKey}` with exactly `lastSerial`, `lastSlotId`,
  `updatedAt`. Immutable map: the same date's `serials/{slotId}` with `number`, `createdAt`.
- After publishing inventory, Admin assigns missing mappings in chronological order. Every
  allocation transaction reads mapping/counter/slot and atomically creates the map + advances the
  counter by exactly one. Rules require both writes and a real inventory slot; prevent duplicate,
  skipped/reset/reused numbers; and forbid mapping updates/deletes and Patient writes.
- First publication is chronological. Later additions, even earlier clock times, APPEND numbers.
  Cancellation/rebooking/completion/restart never renumber existing mappings; gaps are acceptable.
  Different doctors/dates have independent sequences. Concurrent disjoint publishes can interleave
  assignment order, but cannot duplicate or change an assigned number. Existing overlap prevention
  remains a client publisher responsibility; numbering does not claim to eliminate that boundary.
- Publication is two-phase. If interrupted, some inventory/mappings may already exist; the error
  tells the Admin to retry the same date. Retry preserves all assignments and reservations.
- Legacy appointments remain readable. Re-publish an existing future date with the new APK to
  assign missing maps (including booked slots) without altering appointments/locks. Unassigned past
  dates, old-app publications or failed lookups show an explicit **Time slot start–end** identifier,
  never a fabricated #rank. Once a #number exists it is durable.
- Normal UI uses **Appointment Slot No. #01**. RTDB keeps its old Q-based wire schema/keys for
  compatibility; UI joins Firestore mappings for display. RTDB never allocates/calculates numbers.

## Manual setup and verification

Manually redeploy the ENTIRE updated `firestore.rules` in `medibook-2c3a4` before new publication
can finish numbering. No RTDB Rules redeployment or data reset is required for this batch.
No production data, rules or claims were changed by the development agent.

Run Android unit tests, the Firestore and RTDB Java emulator suites, assembleDebug and lintDebug.
New regressions cover lease ownership/expiry, numbering/legacy presentation, strict allocation
schema/atomicity, concurrent writes, different doctors/dates, incremental earlier slots, booked
legacy migration, both cancellations/rebooking and unchanged appointment/slot/lock field counts.

Device pending (no ADB device): sign in both roles; navigate/rotate/back; test >2-minute lease and
offline/error/retry/logout; publish then incrementally add an earlier slot; book/cancel/rebook the
same slot and verify its #number after restart; check historical fallback, photo and live alerts
on a small display/large font. No screenshot/report/video preparation is part of this batch.
