package com.example.medibook.local;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrivatePatientStoreTest {
    private static final class Memory implements PrivatePatientStore.Storage {
        Map<String, String> disk = new HashMap<>();
        @Override public Map<String, String> read() { return new HashMap<>(disk); }
        @Override public void replace(Map<String, String> values) { disk = new HashMap<>(values); }
    }
    @Test public void storageStartsEmptyAndNoAnonymousContactCanBeSaved() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory());
        assertNull(store.owner()); assertNull(store.contact(null)); assertFalse(store.saveContact(null, null, EmergencyContact.from("Family", "999")));
        store.enableReminders(true); store.putReminder("anonymous", "123:100:scheduled");
        assertFalse(store.remindersEnabled()); assertTrue(store.reminderRecords().isEmpty());
    }
    @Test public void onlyCurrentOwnerAndEpochCanSave() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a");
        assertFalse(store.saveContact("b", store.epoch(), EmergencyContact.from("B", "999")));
        assertFalse(store.saveContact("a", "stale", EmergencyContact.from("A", "999")));
        assertTrue(store.saveContact("a", store.epoch(), EmergencyContact.from("A", "999")));
        assertNull(store.contact("b")); assertEquals("A", store.contact("a").name);
    }
    @Test public void sameUidReverificationDoesNotDeleteContactOrChangeEpoch() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a"); String token = store.epoch();
        store.saveContact("a", token, EmergencyContact.from("A", "999"));
        assertFalse(store.bindAccount("a")); assertEquals(token, store.epoch()); assertNotNull(store.contact("a"));
    }
    @Test public void accountSwitchClearsContactRemindersConsentAndLedger() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a"); String old = store.epoch();
        store.saveContact("a", old, EmergencyContact.from("A", "999")); store.enableReminders(true); store.putReminder("booking", "123:100:scheduled");
        store.bindAccount("b"); assertNull(store.contact("a")); assertNull(store.contact("b"));
        assertFalse(store.remindersEnabled()); assertTrue(store.reminderRecords().isEmpty()); assertNotEquals(old, store.epoch());
    }
    @Test public void logoutActuallyErasesPrivateFieldsFromStorage() {
        Memory memory = new Memory(); PrivatePatientStore store = new PrivatePatientStore(memory); store.bindAccount("a");
        store.saveContact("a", store.epoch(), EmergencyContact.from("A", "999")); store.enableReminders(true);
        store.bindAccount(null); assertTrue(memory.disk.isEmpty()); assertNull(store.epoch());
    }
    @Test public void sameAccountReloginCannotAcceptOldPickerOrNotificationToken() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a"); String old = store.epoch();
        store.bindAccount(null); store.bindAccount("a"); assertFalse(store.matches("a", old));
        assertFalse(store.saveContact("a", old, EmergencyContact.from("Late result", "999")));
    }
    @Test public void restartKeepsOnlyTheCurrentAccountsMinimalContact() {
        Memory memory = new Memory(); PrivatePatientStore first = new PrivatePatientStore(memory); first.bindAccount("a");
        first.saveContact("a", first.epoch(), EmergencyContact.from("A", "999"));
        PrivatePatientStore restarted = new PrivatePatientStore(memory); assertFalse(restarted.bindAccount("a"));
        assertEquals("999", restarted.contact("a").phone); assertEquals(first.epoch(), restarted.epoch());
        assertFalse(memory.disk.containsKey("uri")); assertFalse(memory.disk.containsKey("photo"));
    }
    @Test public void coldStartUnderDifferentUidErasesPersistedContact() {
        Memory memory = new Memory(); PrivatePatientStore old = new PrivatePatientStore(memory); old.bindAccount("a");
        old.saveContact("a", old.epoch(), EmergencyContact.from("A", "999"));
        PrivatePatientStore restarted = new PrivatePatientStore(memory); restarted.bindAccount("b"); assertNull(restarted.contact("b"));
        assertFalse(memory.disk.containsKey("name")); assertFalse(memory.disk.containsKey("phone"));
    }
    @Test public void removingContactLeavesReminderPreferenceIntact() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a"); store.enableReminders(true);
        store.saveContact("a", store.epoch(), EmergencyContact.from("A", "999"));
        store.removeContact("b"); assertNotNull(store.contact("a"));
        store.removeContact("a"); assertNull(store.contact("a")); assertTrue(store.remindersEnabled());
    }
    @Test public void togglingRemindersRetainsSentAndCancelledTombstones() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a");
        store.putReminder("booking", "123:100:sent"); store.enableReminders(false); store.enableReminders(true);
        assertEquals("123:100:sent", store.reminderRecords().get("booking"));
    }
    @Test public void ledgerSnapshotsAreNotMutableStorageAliases() {
        PrivatePatientStore store = new PrivatePatientStore(new Memory()); store.bindAccount("a"); store.putReminder("id", "x");
        store.reminderRecords().clear(); assertEquals("x", store.reminderRecords().get("id"));
    }
}
