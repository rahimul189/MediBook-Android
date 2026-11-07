package com.example.medibook.local;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.UserProfile;
import com.example.medibook.reminders.AppointmentReminderWorker;
import com.example.medibook.reminders.ReminderNotifications;
import com.example.medibook.reminders.ReminderPolicy;
import com.example.medibook.repositories.AppointmentMapper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Application-only coordinator. Never retains Activities or treats auth loading as logout.
 * One lock serializes account changes, contact writes and the last notification eligibility check. */
public final class LocalPatientServices {
    public static final String WORK_TAG = "medibook.appointment.reminders";
    private static volatile LocalPatientServices instance;
    private final Context context;
    private final PrivatePatientStore store;
    private long lastSync;

    private LocalPatientServices(Context context) {
        this.context = context.getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences(PrivatePatientStore.FILE, Context.MODE_PRIVATE);
        store = new PrivatePatientStore(new PrivatePatientStore.Storage() {
            @Override public Map<String, String> read() {
                Map<String, String> values = new HashMap<>();
                for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                    if (entry.getValue() instanceof String) values.put(entry.getKey(), (String) entry.getValue());
                }
                return values;
            }
            @Override public void replace(Map<String, String> values) {
                SharedPreferences.Editor editor = preferences.edit().clear();
                for (Map.Entry<String, String> entry : values.entrySet()) editor.putString(entry.getKey(), entry.getValue());
                editor.apply();
            }
        });
    }

    public static synchronized void initialize(Context context) {
        if (instance != null) return;
        instance = new LocalPatientServices(context);
        instance.bindActualAccount();
        FirebaseAuth.getInstance().addAuthStateListener(auth -> instance.bindActualAccount());
    }
    public static LocalPatientServices get() { return instance; }
    private String actualUid() {
        com.google.firebase.auth.FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? null : user.getUid();
    }
    private synchronized void bindActualAccount() {
        try { bind(actualUid()); } catch (RuntimeException unavailable) { logFailure(); }
    }
    private void bind(String uid) {
        String oldEpoch = store.epoch();
        Set<String> oldIds = new HashSet<>(store.reminderRecords().keySet());
        if (!store.bindAccount(uid)) return;
        lastSync = 0;
        try {
            if (oldEpoch != null) work().cancelAllWorkByTag(sessionTag(oldEpoch));
        } finally {
            for (String id : oldIds) ReminderNotifications.cancel(context, id);
        }
    }
    public synchronized void clearForSignOut() { bind(null); }
    public synchronized String epoch(String uid) { return uid != null && uid.equals(actualUid()) && uid.equals(store.owner()) ? store.epoch() : null; }
    public synchronized boolean matches(String uid, String epoch) {
        return uid != null && uid.equals(actualUid()) && store.matches(uid, epoch);
    }
    public synchronized EmergencyContact contact(String uid) { return epoch(uid) == null ? null : store.contact(uid); }
    public synchronized boolean saveContact(String uid, String epoch, EmergencyContact contact) {
        return matches(uid, epoch) && store.saveContact(uid, epoch, contact);
    }
    public synchronized void removeContact(String uid) { if (epoch(uid) != null) store.removeContact(uid); }
    public synchronized boolean remindersEnabled(String uid) { return epoch(uid) != null && store.remindersEnabled(); }

    public synchronized void enableReminders(String uid, boolean enabled) {
        if (epoch(uid) == null) return;
        store.enableReminders(enabled);
        if (!enabled) {
            work().cancelAllWorkByTag(sessionTag(store.epoch()));
            for (String id : store.reminderRecords().keySet()) ReminderNotifications.cancel(context, id);
        } else {
            ReminderNotifications.createChannel(context);
            lastSync = 0;
            verifiedAccount(uid, UserProfile.Role.PATIENT);
        }
    }

    /** Called only after the existing server/claims role gate succeeds. */
    public synchronized void verifiedAccount(String uid, UserProfile.Role role) {
        if (epoch(uid) == null) return;
        if (role != UserProfile.Role.PATIENT) {
            store.removeContact(uid);
            if (store.remindersEnabled()) enableReminders(uid, false);
            return;
        }
        if (!store.remindersEnabled()) return;
        long elapsed = android.os.SystemClock.elapsedRealtime();
        if (lastSync != 0 && elapsed - lastSync < 60_000) return;
        lastSync = elapsed;
        String token = store.epoch();
        // Startup/login reconciliation. All reads are authoritative; failed reads never mean empty.
        FirebaseFirestore.getInstance().collection("appointments").whereEqualTo("patientUid", uid)
                .get(Source.SERVER).addOnCompleteListener(task -> {
                    if (!task.isSuccessful() || task.getResult().getMetadata().isFromCache()
                            || task.getResult().getMetadata().hasPendingWrites()) return;
                    try {
                        List<Appointment> appointments = new ArrayList<>();
                        for (DocumentSnapshot doc : task.getResult()) appointments.add(AppointmentMapper.fromSnapshot(doc));
                        synchronized (LocalPatientServices.this) {
                            if (matches(uid, token)) reconcile(uid, appointments);
                        }
                    } catch (RuntimeException unavailable) { logFailure(); }
                });
    }

    /** Best effort only: local scheduling can never change a successful booking/cancellation Task. */
    public static void appointmentBestEffort(String uid, Appointment appointment) {
        if (instance == null) return;
        try { instance.update(uid, appointment); } catch (RuntimeException unavailable) { logFailure(); }
    }
    public static void listBestEffort(String uid, List<Appointment> appointments) {
        if (instance == null) return;
        try { instance.reconcile(uid, appointments); } catch (RuntimeException unavailable) { logFailure(); }
    }
    public static void verifiedBestEffort(String uid, UserProfile.Role role) {
        if (instance == null) return;
        try { instance.verifiedAccount(uid, role); } catch (RuntimeException unavailable) { logFailure(); }
    }
    public static void signOutBestEffort() {
        if (instance == null) return;
        try { instance.clearForSignOut(); } catch (RuntimeException unavailable) { logFailure(); }
    }
    private static void logFailure() { android.util.Log.w("MediBookLocal", "Local reminder operation unavailable"); }

    private synchronized void reconcile(String uid, List<Appointment> appointments) {
        if (epoch(uid) == null) return;
        for (Appointment a : appointments) update(uid, a);
        // Appointment deletes are not part of this app's contract. Do not infer deletion from a
        // missing ID: an overlapping full-query response may predate a just-committed booking.
        // Workers still suppress missing documents using their final server read.
        for (Map.Entry<String, String> entry : store.reminderRecords().entrySet()) {
            ReminderPolicy.Record record = ReminderPolicy.Record.decode(entry.getValue());
            if (record != null && record.start <= System.currentTimeMillis()) {
                cancel(entry.getKey()); store.putReminder(entry.getKey(), null);
            }
        }
    }
    private synchronized void update(String uid, Appointment a) {
        if (epoch(uid) == null || a == null || !uid.equals(a.getPatientUid())) return;
        ReminderPolicy.Record previous = ReminderPolicy.Record.decode(store.reminderRecords().get(a.getId()));
        if (!store.remindersEnabled() && previous == null && a.getStatus() != com.example.medibook.models.AppointmentStatus.CANCELLED) return;
        ReminderPolicy.Record next = ReminderPolicy.reconcile(uid, a, previous, System.currentTimeMillis());
        String encoded = next == null ? null : next.encode();
        if (!java.util.Objects.equals(encoded, previous == null ? null : previous.encode())) store.putReminder(a.getId(), encoded);
        if (next == null || !ReminderPolicy.SCHEDULED.equals(next.state)) {
            if (next != null || previous != null) cancel(a.getId());
            return;
        }
        if (!store.remindersEnabled()) return;
        Data data = new Data.Builder().putString("uid", uid).putString("epoch", store.epoch()).putString("appointment", a.getId())
                .putLong("start", next.start).putLong("due", next.due).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AppointmentReminderWorker.class)
                .setInputData(data).setInitialDelay(Math.max(0, next.due - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .addTag(WORK_TAG).addTag(sessionTag(store.epoch())).build();
        if (previous != null && previous.start != next.start) cancel(a.getId());
        work().enqueueUniqueWork(workName(store.epoch(), a.getId()), ExistingWorkPolicy.KEEP, request);
    }
    private void cancel(String id) {
        work().cancelUniqueWork(workName(store.epoch(), id)); ReminderNotifications.cancel(context, id);
    }
    private WorkManager work() { return WorkManager.getInstance(context); }
    private static String sessionTag(String epoch) { return WORK_TAG + "." + epoch; }
    private static String workName(String epoch, String id) { return sessionTag(epoch) + "." + id; }

    public synchronized boolean workerAllowed(String uid, String token, String id, long start, long due, long now) {
        return matches(uid, token) && store.remindersEnabled() && ReminderPolicy.mayDeliver(
                ReminderPolicy.Record.decode(store.reminderRecords().get(id)), start, due, now);
    }
    /** Called AFTER a fresh Firestore read, on the WorkManager thread. */
    public synchronized void deliver(String uid, String token, Appointment a, long start, long due) {
        long now = System.currentTimeMillis();
        if (!workerAllowed(uid, token, a.getId(), start, due, now) || !ReminderPolicy.eligible(uid, a, now)
                || a.getStartAt().toDate().getTime() != start || !ReminderNotifications.allowed(context)) return;
        // Mark first for at-most-once local delivery even if the process dies during notify().
        store.putReminder(a.getId(), new ReminderPolicy.Record(start, due, ReminderPolicy.SENT).encode());
        ReminderNotifications.show(context, uid, token, a);
    }
}
