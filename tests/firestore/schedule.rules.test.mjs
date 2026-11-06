import { readFile } from 'node:fs/promises';
import { before, beforeEach, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { collection, collectionGroup, doc, getDoc, getDocs, query, where, setDoc, updateDoc,
  deleteDoc, serverTimestamp, Timestamp, deleteField, writeBatch, setLogLevel } from 'firebase/firestore';

// Fixed local demo emulator only. These identities never create Auth accounts or real claims.
setLogLevel('silent');
const projectId = 'demo-medibook';
const host = '127.0.0.1';
const port = 8080;
const oldTime = Timestamp.fromMillis(1000);
let environment;
const doctor = (active = true) => ({ name: 'Fixture doctor', specialization: 'Fixture specialty',
  qualifications: 'Fixture qualification', consultationFee: 50000, biography: '', active,
  createdAt: oldTime, updatedAt: oldTime });
const profile = () => ({ fullName: 'Fixture patient', email: 'patient@example.invalid',
  phone: '1234567', role: 'Patient', createdAt: oldTime });
const schedule = (active = true) => ({ dayOfWeek: 1, startTime: 540, endTime: 660,
  slotDurationMinutes: 20, active, updatedAt: oldTime });
const creation = (patch = {}) => ({ ...schedule(), ...patch, updatedAt: serverTimestamp() });
const admin = () => environment.authenticatedContext('admin-a', { admin: true }).firestore();
const patient = () => environment.authenticatedContext('patient-a', { email: 'patient@example.invalid' }).firestore();
const reference = (db, id = 'enabled', doctorId = 'active') => doc(db, 'doctors', doctorId, 'schedules', id);
const directory = (db, doctorId = 'active') => collection(db, 'doctors', doctorId, 'schedules');

before(async () => {
  if (process.env.FIRESTORE_EMULATOR_HOST && process.env.FIRESTORE_EMULATOR_HOST !== `${host}:${port}`) {
    throw new Error('Refusing a non-approved emulator endpoint');
  }
  environment = await initializeTestEnvironment({ projectId,
    firestore: { host, port, rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8') } });
});

beforeEach(async () => {
  await environment.clearFirestore();
  await environment.withSecurityRulesDisabled(async context => {
    const db = context.firestore();
    await setDoc(doc(db, 'users/patient-a'), profile());
    await setDoc(doc(db, 'users/fake-admin'), { ...profile(), role: 'Admin' });
    await setDoc(doc(db, 'doctors/active'), doctor());
    await setDoc(doc(db, 'doctors/inactive'), doctor(false));
    await setDoc(reference(db, 'enabled'), schedule());
    await setDoc(reference(db, 'disabled'), schedule(false));
    await setDoc(reference(db, 'enabled', 'inactive'), schedule());
    await setDoc(reference(db, 'orphan', 'missing'), schedule());
  });
});

after(async () => { if (environment) await environment.cleanup(); });

test('anonymous schedule get/list/create/update/delete are denied', async () => {
  const db = environment.unauthenticatedContext().firestore();
  await assertFails(getDoc(reference(db)));
  await assertFails(getDocs(directory(db)));
  await assertFails(getDocs(query(directory(db), where('active', '==', true))));
  await assertFails(setDoc(reference(db, 'new'), creation()));
  await assertFails(updateDoc(reference(db), { active: false, updatedAt: serverTimestamp() }));
  await assertFails(deleteDoc(reference(db)));
});

test('Boolean-claim Admin reads active/inactive schedules and unfiltered directories of either doctor', async () => {
  const db = admin();
  await assertSucceeds(getDoc(reference(db)));
  await assertSucceeds(getDoc(reference(db, 'disabled')));
  await assertSucceeds(getDoc(reference(db, 'enabled', 'inactive')));
  assert.equal((await assertSucceeds(getDocs(directory(db)))).size, 2);
  assert.equal((await assertSucceeds(getDocs(directory(db, 'inactive')))).size, 1);
});

test('valid Patient reads only active schedules of an active doctor using an active-filtered query', async () => {
  const db = patient();
  await assertSucceeds(getDoc(reference(db)));
  await assertFails(getDoc(reference(db, 'disabled')));
  const result = await assertSucceeds(getDocs(query(directory(db), where('active', '==', true))));
  assert.deepEqual(result.docs.map(value => value.id), ['enabled']);
  await assertFails(getDocs(directory(db)));
  await assertFails(getDocs(query(directory(db), where('active', '==', false))));
});

test('Patient cannot read schedules of inactive or absent doctors, even with active filtering', async () => {
  const db = patient();
  await assertFails(getDoc(reference(db, 'enabled', 'inactive')));
  await assertFails(getDocs(query(directory(db, 'inactive'), where('active', '==', true))));
  await assertFails(getDoc(reference(db, 'orphan', 'missing')));
  await assertFails(getDocs(query(directory(db, 'missing'), where('active', '==', true))));
});

test('Patient access changes when Admin changes doctor or schedule status', async () => {
  const patientDb = patient();
  const adminDb = admin();
  await assertSucceeds(updateDoc(doc(adminDb, 'doctors/active'), { active: false, updatedAt: serverTimestamp() }));
  await assertFails(getDoc(reference(patientDb)));
  await assertSucceeds(updateDoc(doc(adminDb, 'doctors/active'), { active: true, updatedAt: serverTimestamp() }));
  await assertSucceeds(getDoc(reference(patientDb)));
  await assertSucceeds(updateDoc(reference(adminDb), { active: false, updatedAt: serverTimestamp() }));
  await assertFails(getDoc(reference(patientDb)));
  assert.equal((await assertSucceeds(getDocs(query(directory(patientDb), where('active', '==', true))))).size, 0);
});

test('Patient cannot create, edit, enable, disable, or delete schedule records', async () => {
  const db = patient();
  await assertFails(setDoc(reference(db, 'new'), creation()));
  await assertFails(updateDoc(reference(db), { startTime: 555, updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(reference(db), { active: false, updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(reference(db, 'disabled'), { active: true, updatedAt: serverTimestamp() }));
  await assertFails(deleteDoc(reference(db)));
});

test('missing profiles and profile-only Admin roles never grant schedule access', async () => {
  for (const uid of ['missing-profile', 'fake-admin']) {
    const db = environment.authenticatedContext(uid, { email: 'patient@example.invalid' }).firestore();
    await assertFails(getDoc(reference(db)));
    await assertFails(getDocs(query(directory(db), where('active', '==', true))));
    await assertFails(setDoc(reference(db, 'new'), creation()));
  }
});

test('false, string, numeric, null and object Admin claims grant neither Admin nor Patient schedule access', async () => {
  for (const claim of [false, 'true', 1, null, { value: true }]) {
    const db = environment.authenticatedContext('patient-a', { admin: claim, email: 'patient@example.invalid' }).firestore();
    await assertFails(getDoc(reference(db)));
    await assertFails(getDocs(query(directory(db), where('active', '==', true))));
    await assertFails(setDoc(reference(db, 'new'), creation()));
    await assertFails(updateDoc(reference(db), { active: false, updatedAt: serverTimestamp() }));
    await assertFails(deleteDoc(reference(db)));
  }
});

test('schedule Patient reads require every valid profile field and reject extra fields or bad types', async () => {
  const invalid = [
    ...Object.keys(profile()).map(field => { const value = profile(); delete value[field]; return value; }),
    ...[{ fullName: '' }, { fullName: '  ' }, { fullName: 'N'.repeat(101) }, { fullName: 123 },
      { email: 'other@example.invalid' }, { email: 123 }, { phone: 'short' }, { phone: 123 },
      { role: 'Admin' }, { createdAt: 'not-a-timestamp' }, { admin: true }].map(patch => ({ ...profile(), ...patch })),
  ];
  for (const value of invalid) {
    await environment.withSecurityRulesDisabled(context => setDoc(doc(context.firestore(), 'users/patient-a'), value));
    await assertFails(getDoc(reference(patient())));
  }
});

test('mismatched or missing token email cannot use another profile for schedule reads', async () => {
  for (const claims of [{ email: 'other@example.invalid' }, {}]) {
    const db = environment.authenticatedContext('patient-a', claims).firestore();
    await assertFails(getDoc(reference(db)));
  }
});

test('Admin creates exactly the six-field minute-based schema with a server timestamp', async () => {
  const db = admin();
  await assertSucceeds(setDoc(reference(db, 'new-id'), creation()));
  const saved = await getDoc(reference(db, 'new-id'));
  assert.equal(saved.id, 'new-id');
  assert.deepEqual(Object.keys(saved.data()).sort(), Object.keys(schedule()).sort());
  assert.equal(saved.data().dayOfWeek, 1);
  assert.equal(saved.data().startTime, 540);
  assert.equal(saved.data().endTime, 660);
  assert.equal(saved.data().slotDurationMinutes, 20);
  assert.ok(saved.data().updatedAt instanceof Timestamp);
  assert.ok(saved.data().updatedAt.toMillis() > oldTime.toMillis());
});

test('Admin edits, disables and enables one stable schedule ID without touching the doctor', async () => {
  const db = admin();
  const beforeDoctor = (await getDoc(doc(db, 'doctors/active'))).data();
  await assertSucceeds(updateDoc(reference(db), { dayOfWeek: 7, startTime: 600, endTime: 780,
    slotDurationMinutes: 30, updatedAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(reference(db), { active: false, updatedAt: serverTimestamp() }));
  assert.equal((await getDoc(reference(db))).data().active, false);
  await assertSucceeds(updateDoc(reference(db), { active: true, updatedAt: serverTimestamp() }));
  const saved = await getDoc(reference(db));
  assert.equal(saved.id, 'enabled');
  assert.equal(saved.data().dayOfWeek, 7);
  assert.equal(saved.data().active, true);
  assert.equal((await getDocs(directory(db))).size, 2);
  assert.deepEqual((await getDoc(doc(db, 'doctors/active'))).data(), beforeDoctor);
});

test('even Admin cannot permanently delete or atomically move a schedule to another ID', async () => {
  const db = admin();
  await assertFails(deleteDoc(reference(db)));
  await assertFails(deleteDoc(reference(db, 'disabled')));
  const batch = writeBatch(db);
  batch.delete(reference(db));
  batch.set(reference(db, 'moved-id'), creation());
  await assertFails(batch.commit());
  assert.equal((await getDoc(reference(db))).exists(), true);
  assert.equal((await getDoc(reference(db, 'moved-id'))).exists(), false);
});

test('all six fields are required and editable IDs/foreign schema fields are rejected', async () => {
  const db = admin();
  for (const field of Object.keys(schedule())) {
    const value = creation();
    delete value[field];
    await assertFails(setDoc(reference(db, 'candidate'), value));
  }
  for (const field of ['id', 'doctorId', 'scheduleId', 'createdAt', 'timezone', 'role', 'appointments']) {
    await assertFails(setDoc(reference(db, 'candidate'), { ...creation(), [field]: 'unexpected' }));
    await assertFails(updateDoc(reference(db), { [field]: 'unexpected', updatedAt: serverTimestamp() }));
  }
});

test('invalid weekdays, noninteger time representations, reversed and overnight ranges are denied', async () => {
  const db = admin();
  for (const patch of [{ dayOfWeek: 0 }, { dayOfWeek: 8 }, { dayOfWeek: -1 }, { dayOfWeek: 1.5 },
    { dayOfWeek: 'Monday' }, { startTime: -1 }, { startTime: 1440 }, { startTime: 540.5 },
    { startTime: '09:00' }, { endTime: 1440 }, { endTime: -1 }, { endTime: 660.5 },
    { endTime: '11:00' }, { startTime: 600, endTime: 600 }, { startTime: 1380, endTime: 60 }]) {
    await assertFails(setDoc(reference(db, 'candidate'), creation(patch)));
  }
});

test('duration bounds, five-minute increments, whole numbers and same-window fit are enforced', async () => {
  const db = admin();
  for (const slotDurationMinutes of [0, -5, 4, 7, 181, 185, 999, 15.5, '15', NaN, Infinity]) {
    await assertFails(setDoc(reference(db, 'candidate'), creation({ slotDurationMinutes })));
  }
  await assertFails(setDoc(reference(db, 'candidate'), creation({ startTime: 540, endTime: 550, slotDurationMinutes: 15 })));
});

test('valid minute/weekday/duration boundaries and a remainder window are accepted', async () => {
  const db = admin();
  for (const [index, patch] of [
    { dayOfWeek: 1, startTime: 0, endTime: 5, slotDurationMinutes: 5 },
    { dayOfWeek: 7, startTime: 1434, endTime: 1439, slotDurationMinutes: 5 },
    { startTime: 0, endTime: 180, slotDurationMinutes: 180 },
    { startTime: 540, endTime: 605, slotDurationMinutes: 30 },
    { active: false },
  ].entries()) await assertSucceeds(setDoc(reference(db, `boundary-${index}`), creation(patch)));
});

test('wrong types and nulls are denied for every schedule field', async () => {
  const db = admin();
  for (const field of Object.keys(schedule())) {
    for (const value of [null, { invalid: true }]) {
      await assertFails(setDoc(reference(db, 'candidate'), { ...creation(), [field]: value }));
    }
  }
  for (const active of ['true', 1, 0]) await assertFails(setDoc(reference(db, 'candidate'), creation({ active })));
});

test('forged timestamps, nonadvancing versions, missing timestamp and field deletion are denied', async () => {
  const db = admin();
  await assertFails(setDoc(reference(db, 'candidate'), { ...creation(), updatedAt: oldTime }));
  await assertFails(updateDoc(reference(db), { active: false }));
  await assertFails(updateDoc(reference(db), { updatedAt: oldTime }));
  await assertFails(updateDoc(reference(db), { updatedAt: Timestamp.fromMillis(0) }));
  for (const field of Object.keys(schedule())) {
    await assertFails(updateDoc(reference(db), { updatedAt: serverTimestamp(), [field]: deleteField() }));
  }
});

test('parent existence and bounded safe path identifiers are enforced on create and update', async () => {
  const db = admin();
  await assertFails(setDoc(reference(db, 'new', 'missing'), creation()));
  await assertFails(updateDoc(reference(db, 'orphan', 'missing'), { active: false, updatedAt: serverTimestamp() }));
  for (const id of ['invalid.id', 'space id', 'x'.repeat(129), 'বাংলা']) {
    await assertFails(setDoc(reference(db, id), creation()));
  }
  await assertSucceeds(setDoc(reference(db, 'x'.repeat(128)), creation()));
  await assertSucceeds(setDoc(reference(db, 'schedule_1-AbC'), creation()));
  await environment.withSecurityRulesDisabled(context => setDoc(doc(context.firestore(), 'doctors/invalid.id'), doctor()));
  await assertFails(setDoc(reference(db, 'new', 'invalid.id'), creation()));
  await assertSucceeds(setDoc(reference(db, 'new', 'inactive'), creation()));
});

test('unrelated collections, other subcollections and collection-group reads stay denied', async () => {
  for (const db of [admin(), patient(), environment.unauthenticatedContext().firestore()]) {
    for (const path of ['schedules/fixture', 'appointments/fixture', 'unrelated/fixture',
      'doctors/active/private/fixture', 'doctors/active/schedules/enabled/appointments/fixture']) {
      await assertFails(getDoc(doc(db, path)));
      await assertFails(setDoc(doc(db, path), { value: 'fixture' }));
    }
    await assertFails(getDocs(query(collectionGroup(db, 'schedules'), where('active', '==', true))));
  }
});

test('limitation: valid overlapping new IDs are accepted by Rules, not globally serialized', async () => {
  const db = admin();
  const batch = writeBatch(db);
  batch.set(reference(db, 'race-a'), creation({ startTime: 900, endTime: 1020, slotDurationMinutes: 30 }));
  batch.set(reference(db, 'race-b'), creation({ startTime: 960, endTime: 1080, slotDurationMinutes: 20 }));
  await assertSucceeds(batch.commit());
  assert.equal((await getDoc(reference(db, 'race-a'))).data().active, true);
  assert.equal((await getDoc(reference(db, 'race-b'))).data().active, true);
});
