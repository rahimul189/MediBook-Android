import { readFile } from 'node:fs/promises';
import { before, beforeEach, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { collection, doc, getDoc, getDocs, query, where, setDoc, updateDoc, deleteDoc,
  serverTimestamp, Timestamp, deleteField, setLogLevel } from 'firebase/firestore';

// Emulator-only fake identities/data. Never uses google-services.json, ADC or production accounts.
// Expected-denial cases are asserted below; suppress their repetitive SDK warning output.
setLogLevel('silent');
const projectId = 'demo-medibook';
const host = '127.0.0.1';
const port = 8080;
let environment;
const oldTime = Timestamp.fromMillis(1000);
const fixture = (active = true) => ({
  name: 'Rules fixture', specialization: 'Fixture specialty', qualifications: 'Fixture qualification',
  consultationFee: 50025, biography: '', active, createdAt: oldTime, updatedAt: oldTime,
});
const creation = () => ({ ...fixture(), createdAt: serverTimestamp(), updatedAt: serverTimestamp() });
const patient = () => environment.authenticatedContext('patient-a', { email: 'patient@example.invalid' }).firestore();
const admin = () => environment.authenticatedContext('admin-a', { admin: true }).firestore();

before(async () => {
  if (process.env.FIRESTORE_EMULATOR_HOST && process.env.FIRESTORE_EMULATOR_HOST !== `${host}:${port}`) {
    throw new Error('Refusing a non-approved emulator endpoint');
  }
  environment = await initializeTestEnvironment({
    projectId,
    firestore: { host, port, rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8') },
  });
});

beforeEach(async () => {
  await environment.clearFirestore(); // Only the fixed local demo project.
  await environment.withSecurityRulesDisabled(async context => {
    const db = context.firestore();
    await setDoc(doc(db, 'users/patient-a'), { fullName: 'Patient fixture', email: 'patient@example.invalid',
      phone: '1234567', role: 'Patient', createdAt: oldTime });
    await setDoc(doc(db, 'users/fake-admin'), { fullName: 'Untrusted role fixture', role: 'Admin' });
    await setDoc(doc(db, 'doctors/active'), fixture(true));
    await setDoc(doc(db, 'doctors/inactive'), fixture(false));
  });
});

after(async () => { if (environment) await environment.cleanup(); });

test('unauthenticated doctor get, list and create are denied', async () => {
  const db = environment.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(db, 'doctors/active')));
  await assertFails(getDocs(collection(db, 'doctors')));
  await assertFails(getDocs(query(collection(db, 'doctors'), where('active', '==', true))));
  await assertFails(setDoc(doc(db, 'doctors/new'), creation()));
});

test('Patient gets only active doctors and must filter list queries by active == true', async () => {
  const db = patient();
  await assertSucceeds(getDoc(doc(db, 'doctors/active')));
  await assertFails(getDoc(doc(db, 'doctors/inactive')));
  const result = await assertSucceeds(getDocs(query(collection(db, 'doctors'), where('active', '==', true))));
  assert.equal(result.size, 1);
  await assertFails(getDocs(collection(db, 'doctors')));
  await assertFails(getDocs(query(collection(db, 'doctors'), where('active', '==', false))));
});

test('authenticated accounts without a Patient profile, and profile-only Admins, get no doctor access', async () => {
  for (const uid of ['no-profile', 'fake-admin']) {
    const db = environment.authenticatedContext(uid).firestore();
    await assertFails(getDoc(doc(db, 'doctors/active')));
    await assertFails(setDoc(doc(db, 'doctors/new'), creation()));
  }
});

test('string, numeric, false and null admin claims grant neither Admin nor Patient doctor access', async () => {
  for (const claim of ['true', 1, false, null]) {
    const db = environment.authenticatedContext('patient-a', { admin: claim }).firestore();
    await assertFails(getDoc(doc(db, 'doctors/active')));
    await assertFails(setDoc(doc(db, 'doctors/new'), creation()));
    await assertFails(updateDoc(doc(db, 'doctors/active'), { active: false, updatedAt: serverTimestamp() }));
  }
});

test('Boolean-claim Admin can read active, inactive and an unfiltered list', async () => {
  const db = admin();
  await assertSucceeds(getDoc(doc(db, 'doctors/active')));
  await assertSucceeds(getDoc(doc(db, 'doctors/inactive')));
  assert.equal((await assertSucceeds(getDocs(collection(db, 'doctors')))).size, 2);
});

test('Patient cannot create, edit, deactivate, reactivate or delete', async () => {
  const db = patient();
  await assertFails(setDoc(doc(db, 'doctors/new'), creation()));
  await assertFails(updateDoc(doc(db, 'doctors/active'), { name: 'Changed', updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(db, 'doctors/active'), { active: false, updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(db, 'doctors/inactive'), { active: true, updatedAt: serverTimestamp() }));
  await assertFails(deleteDoc(doc(db, 'doctors/active')));
});

test('Admin creates with both server timestamps and integer hundredths', async () => {
  const db = admin();
  const reference = doc(db, 'doctors/new');
  await assertSucceeds(setDoc(reference, creation()));
  const data = (await getDoc(reference)).data();
  assert.equal(data.consultationFee, 50025);
  assert.ok(data.createdAt instanceof Timestamp);
  assert.ok(data.createdAt.isEqual(data.updatedAt));
});

test('Admin edit and status updates preserve ID and createdAt; deletion remains denied', async () => {
  const db = admin();
  const reference = doc(db, 'doctors/active');
  await assertSucceeds(updateDoc(reference, { name: 'Edited fixture', updatedAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(reference, { active: false, updatedAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(reference, { active: true, updatedAt: serverTimestamp() }));
  const saved = await getDoc(reference);
  assert.equal(saved.id, 'active');
  assert.ok(saved.data().createdAt.isEqual(oldTime));
  assert.ok(saved.data().updatedAt.toMillis() > oldTime.toMillis());
  await assertFails(deleteDoc(reference));
});

test('every field is required and unknown fields including id/role are rejected on create', async () => {
  const db = admin();
  for (const field of Object.keys(creation())) {
    const invalid = creation();
    delete invalid[field];
    await assertFails(setDoc(doc(db, 'doctors/candidate'), invalid));
  }
  for (const field of ['id', 'role', 'ownerUid', 'schedule']) {
    await assertFails(setDoc(doc(db, 'doctors/candidate'), { ...creation(), [field]: 'unexpected' }));
  }
});

test('field types, blank values, control characters and length/fee bounds are enforced', async () => {
  const db = admin();
  const invalid = [
    { name: 12 }, { specialization: 12 }, { qualifications: null }, { biography: false }, { active: 'true' },
    { name: '' }, { name: '  ' }, { name: '\u00a0\u2003' }, { specialization: '\n' },
    { qualifications: 'Q\u0000Q' }, { name: 'A\nB' }, { biography: 'line one\nline\u0007two' },
    { name: 'N'.repeat(101) }, { specialization: 'S'.repeat(101) },
    { qualifications: 'Q'.repeat(201) }, { biography: 'B'.repeat(1001) },
    { consultationFee: -1 }, { consultationFee: 100000000 }, { consultationFee: 1.5 },
    { consultationFee: '50025' }, { consultationFee: NaN }, { consultationFee: Infinity },
  ];
  for (const patch of invalid) await assertFails(setDoc(doc(db, 'doctors/candidate'), { ...creation(), ...patch }));
});

test('valid boundaries, zero fee and ordinary biography line breaks are accepted', async () => {
  const db = admin();
  await assertSucceeds(setDoc(doc(db, 'doctors/boundary'), { ...creation(), name: 'N'.repeat(100),
    specialization: 'S'.repeat(100), qualifications: 'Q'.repeat(200), biography: 'B'.repeat(1000),
    consultationFee: 99999999 }));
  await assertSucceeds(setDoc(doc(db, 'doctors/free'), { ...creation(), consultationFee: 0, biography: 'First\r\nSecond' }));
});

test('forged timestamps, mutable createdAt, missing updatedAt and extra update fields are denied', async () => {
  const db = admin();
  await assertFails(setDoc(doc(db, 'doctors/new'), { ...creation(), createdAt: oldTime }));
  await assertFails(setDoc(doc(db, 'doctors/new'), { ...creation(), updatedAt: oldTime }));
  const reference = doc(db, 'doctors/active');
  await assertFails(updateDoc(reference, { createdAt: serverTimestamp(), updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(reference, { name: 'Changed' }));
  await assertFails(updateDoc(reference, { updatedAt: oldTime }));
  await assertFails(updateDoc(reference, { biography: deleteField(), updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(reference, { id: 'other', updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(reference, { active: 1, updatedAt: serverTimestamp() }));
});

test('Patient profile create and owner-only read rules remain unchanged', async () => {
  const db = environment.authenticatedContext('new-patient', { email: 'new@example.invalid' }).firestore();
  const reference = doc(db, 'users/new-patient');
  const profile = { fullName: 'Patient fixture', email: 'new@example.invalid', phone: '1234567',
    role: 'Patient', createdAt: serverTimestamp() };
  await assertSucceeds(setDoc(reference, profile));
  await assertSucceeds(getDoc(reference));
  await assertFails(getDoc(doc(db, 'users/patient-a')));
  await assertFails(getDocs(collection(db, 'users')));
  await assertFails(updateDoc(reference, { role: 'Admin' }));
  await assertFails(deleteDoc(reference));
  await assertFails(setDoc(doc(db, 'users/someone-else'), profile));
});

test('Patient profile validation, no privilege escalation and Admin profile write denial regressions', async () => {
  const db = environment.authenticatedContext('fresh', { email: 'fresh@example.invalid' }).firestore();
  const profile = { fullName: 'Patient fixture', email: 'fresh@example.invalid', phone: '1234567',
    role: 'Patient', createdAt: serverTimestamp() };
  for (const patch of [{ role: 'Admin' }, { admin: true }, { email: 'wrong@example.invalid' },
    { phone: 'short' }, { fullName: '  ' }, { fullName: 'N'.repeat(101) }, { createdAt: oldTime }]) {
    await assertFails(setDoc(doc(db, 'users/fresh'), { ...profile, ...patch }));
  }
  await assertFails(setDoc(doc(admin(), 'users/admin-a'), { ...profile, email: 'admin@example.invalid' }));
  await assertFails(updateDoc(doc(admin(), 'users/patient-a'), { role: 'Admin' }));
  await assertFails(deleteDoc(doc(admin(), 'users/patient-a')));
});

test('unrelated collections and doctor subcollections remain denied, including for Admin', async () => {
  for (const db of [patient(), admin(), environment.unauthenticatedContext().firestore()]) {
    for (const path of ['appointments/fixture', 'schedules/fixture', 'unrelated/fixture', 'doctors/active/private/fixture']) {
      await assertFails(getDoc(doc(db, path)));
      await assertFails(setDoc(doc(db, path), { value: 'fixture' }));
    }
  }
});
