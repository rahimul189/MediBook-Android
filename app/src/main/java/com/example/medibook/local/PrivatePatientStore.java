package com.example.medibook.local;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Single-account, private local state. No Firebase/network dependency; temporary auth loading is
 * deliberately NOT an account transition. Call bindAccount only with the actual Firebase UID. */
public final class PrivatePatientStore {
    public static final String FILE = "patient_private";
    public interface Storage {
        Map<String, String> read();
        void replace(Map<String, String> values);
    }
    private final Storage storage;
    private final Map<String, String> values;

    public PrivatePatientStore(Storage storage) { this.storage = storage; values = new HashMap<>(storage.read()); }
    public String owner() { return values.get("owner"); }
    public String epoch() { return values.get("epoch"); }
    public boolean matches(String uid, String token) {
        return uid != null && token != null && uid.equals(owner()) && token.equals(epoch());
    }
    public boolean bindAccount(String uid) {
        if (Objects.equals(uid, owner())) return false;
        values.clear();
        if (uid != null) { values.put("owner", uid); values.put("epoch", UUID.randomUUID().toString()); }
        persist(); return true;
    }
    public EmergencyContact contact(String uid) {
        return uid != null && uid.equals(owner()) ? EmergencyContact.from(values.get("name"), values.get("phone")) : null;
    }
    public boolean saveContact(String uid, String token, EmergencyContact contact) {
        if (!matches(uid, token) || contact == null) return false;
        values.put("name", contact.name); values.put("phone", contact.phone); persist(); return true;
    }
    public void removeContact(String uid) {
        if (uid == null || !uid.equals(owner())) return;
        values.remove("name"); values.remove("phone"); persist();
    }
    public boolean remindersEnabled() { return "true".equals(values.get("reminders")); }
    public void enableReminders(boolean enabled) {
        if (owner() == null) return;
        values.put("reminders", Boolean.toString(enabled));
        // Retain sent/cancelled tombstones when toggling, preventing duplicate reminders.
        persist();
    }
    public Map<String, String> reminderRecords() {
        Map<String, String> records = new HashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith("reminder.")) records.put(entry.getKey().substring(9), entry.getValue());
        }
        return records;
    }
    public void putReminder(String id, String record) {
        if (owner() == null) return;
        if (record == null) values.remove("reminder." + id); else values.put("reminder." + id, record);
        persist();
    }
    private void persist() { storage.replace(new HashMap<>(values)); }
}
