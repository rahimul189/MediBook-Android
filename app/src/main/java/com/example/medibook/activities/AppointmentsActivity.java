package com.example.medibook.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.medibook.R;
import com.example.medibook.adapters.AppointmentAdapter;
import com.example.medibook.databinding.ActivityAppointmentsBinding;
import com.example.medibook.models.Appointment;
import com.example.medibook.models.UserProfile;
import com.example.medibook.repositories.AppointmentRepository;
import com.example.medibook.utils.AppointmentDisplayStatus;
import com.example.medibook.utils.AppointmentUi;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.DhakaDateTime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Own-only Patient history or Admin appointment list. Never queries a patient directory. */
public final class AppointmentsActivity extends AppointmentSessionActivity {
    private ActivityAppointmentsBinding binding;
    private AppointmentAdapter adapter;
    private final AppointmentRepository repository = new AppointmentRepository();
    private List<Appointment> entries = Collections.emptyList();
    private boolean admin, initialized, loading;
    private long date = -1;
    private int error;
    private androidx.appcompat.app.AlertDialog reminderDialog;
    private String permissionOwner, permissionEpoch;
    private Boolean permissionResult;
    private final androidx.activity.result.ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(), granted -> {
                permissionResult = granted; consumePermissionResult();
            });
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clock = new Runnable() {
        @Override public void run() { if (canAccess()) render(); handler.postDelayed(this, 30_000); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); binding = ActivityAppointmentsBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        binding.toolbar.setNavigationOnClickListener(view -> finish());
        adapter = new AppointmentAdapter(a -> {
            if (canAccess()) startActivity(new Intent(this, AppointmentDetailsActivity.class).putExtra("appointmentId", a.getId()));
        });
        binding.list.setLayoutManager(new LinearLayoutManager(this)); binding.list.setAdapter(adapter);
        binding.statusFilter.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                getString(R.string.appointment_all), getString(R.string.appointment_upcoming),
                getString(R.string.appointment_completed), getString(R.string.appointment_cancelled)}));
        binding.statusFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { if (canAccess()) render(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        binding.doctorFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { if (canAccess()) render(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        binding.refreshButton.setOnClickListener(view -> { if (canAccess() && !loading) refresh(); });
        binding.remindersButton.setOnClickListener(view -> manageReminders());
        binding.dateButton.setOnClickListener(view -> {
            if (!canAccess() || !admin) return;
            AppointmentUi.pickDate(this, date < 0 ? AppointmentUi.today() : date, (y, m, d) -> {
                if (!canAccess()) return;
                date = DhakaDateTime.localDateAtMidnight(y, m, d); binding.dateButton.setText(AppointmentUi.date(date)); render();
            });
        });
        binding.clearDateButton.setOnClickListener(view -> {
            date = -1; binding.dateButton.setText(R.string.appointment_all_dates); if (canAccess()) render();
        });
        if (state != null) {
            permissionOwner = state.getString("permissionOwner"); permissionEpoch = state.getString("permissionEpoch");
            date = state.getLong("filterDate", -1);
            if (date >= 0) binding.dateButton.setText(AppointmentUi.date(date));
            binding.doctorFilter.setText(state.getString("filterDoctor", ""));
            binding.statusFilter.setSelection(state.getInt("filterStatus", 0)); initialized = true;
        }
    }
    @Override protected View protectedContent() { return binding.content; }
    @Override protected View sessionLoading() { return binding.sessionLoading.getRoot(); }
    @Override protected void onVerifiedSession() {
        admin = session.getRole() == UserProfile.Role.ADMIN;
        binding.toolbar.setTitle(admin ? R.string.appointment_manage : R.string.appointment_my);
        binding.doctorFilterLayout.setVisibility(admin ? View.VISIBLE : View.GONE);
        binding.dateFilters.setVisibility(admin ? View.VISIBLE : View.GONE);
        renderReminderButton(); consumePermissionResult();
        if (!initialized) { initialized = true; binding.statusFilter.setSelection(admin ? 0 : 1); }
        refresh();
    }
    @Override protected void clearSessionViews() {
        entries = Collections.emptyList(); if (adapter != null) adapter.submit(entries, false);
        binding.message.setText(null);
        if (reminderDialog != null) { reminderDialog.dismiss(); reminderDialog = null; }
    }
    private void renderReminderButton() {
        binding.remindersButton.setVisibility(admin ? View.GONE : View.VISIBLE);
        binding.remindersButton.setText(com.example.medibook.local.LocalPatientServices.get().remindersEnabled(owner)
                && com.example.medibook.reminders.ReminderNotifications.allowed(this) ? R.string.reminder_enabled : R.string.reminder_enable);
    }
    private void manageReminders() {
        if (!canAccess() || admin) return;
        com.example.medibook.local.LocalPatientServices local = com.example.medibook.local.LocalPatientServices.get();
        permissionOwner = owner; permissionEpoch = local.epoch(owner);
        boolean enabled = local.remindersEnabled(owner) && com.example.medibook.reminders.ReminderNotifications.allowed(this);
        reminderDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(enabled ? R.string.reminder_turn_off : R.string.reminder_permission_title)
                .setMessage(enabled ? R.string.reminder_off_message : R.string.reminder_explanation)
                .setNegativeButton(R.string.cancel_button, null)
                .setPositiveButton(enabled ? R.string.reminder_turn_off : R.string.reminder_allow, (dialog, which) -> {
                    if (!canAccess() || !local.matches(permissionOwner, permissionEpoch)) return;
                    if (enabled) {
                        local.enableReminders(owner, false); renderReminderButton(); notice(R.string.reminder_disabled, false);
                    } else if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                            this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS);
                    } else if (!com.example.medibook.reminders.ReminderNotifications.allowed(this)) {
                        reminderDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                                .setTitle(R.string.reminder_settings).setMessage(R.string.reminder_settings_explanation)
                                .setNegativeButton(R.string.cancel_button, null)
                                .setPositiveButton(R.string.reminder_settings, (d, w) -> openNotificationSettings()).show();
                    } else enableReminders();
                }).show();
    }
    private void consumePermissionResult() {
        if (permissionResult == null || !canAccess() || admin) return;
        boolean granted = permissionResult; permissionResult = null;
        if (!com.example.medibook.local.LocalPatientServices.get().matches(permissionOwner, permissionEpoch)) return;
        if (granted && com.example.medibook.reminders.ReminderNotifications.allowed(this)) enableReminders();
        else { renderReminderButton(); notice(R.string.reminder_denied, true); }
    }
    private void enableReminders() {
        if (!canAccess() || admin || !com.example.medibook.local.LocalPatientServices.get().matches(permissionOwner, permissionEpoch)) return;
        com.example.medibook.local.LocalPatientServices.get().enableReminders(owner, true);
        renderReminderButton(); notice(R.string.reminder_on_message, false);
    }
    private void notice(int text, boolean settings) {
        com.google.android.material.snackbar.Snackbar bar = com.google.android.material.snackbar.Snackbar.make(binding.getRoot(), text,
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG);
        if (settings) bar.setAction(R.string.reminder_settings, view -> openNotificationSettings());
        bar.show();
    }
    private void openNotificationSettings() {
        if (!canAccess() || admin) return;
        Intent intent = android.os.Build.VERSION.SDK_INT >= 26
                ? new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())
                : new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName()));
        try { startActivity(intent); } catch (android.content.ActivityNotFoundException unavailable) { notice(R.string.reminder_denied, false); }
    }
    private void refresh() {
        loading = true; error = 0; render();
        int version = ++requestVersion;
        repository.list(owner, admin).addOnCompleteListener(this, task -> {
            if (!canAccess() || version != requestVersion) return;
            loading = false;
            if (task.isSuccessful()) {
                entries = task.getResult();
                new com.example.medibook.repositories.SlotSerialRepository().appointments(owner, entries).addOnSuccessListener(this, values -> {
                    if (canAccess() && version == requestVersion) { entries = values; render(); }
                });
            } else { error = AppointmentUi.error(task.getException()); AppointmentUi.errorText(this, task.getException()); }
            render();
        });
    }
    private void render() {
        List<Appointment> shown = new ArrayList<>();
        int filter = binding.statusFilter.getSelectedItemPosition();
        String doctor = binding.doctorFilter.getText() == null ? "" : binding.doctorFilter.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (Appointment a : entries) {
            AppointmentDisplayStatus status = AppointmentDisplayStatus.of(a, System.currentTimeMillis());
            if (filter == 1 && status != AppointmentDisplayStatus.CONFIRMED
                    || filter == 2 && status != AppointmentDisplayStatus.COMPLETED
                    || filter == 3 && status != AppointmentDisplayStatus.CANCELLED) continue;
            if (admin && (!a.getDoctorName().toLowerCase(Locale.ROOT).contains(doctor)
                    || date >= 0 && a.getAppointmentDate().toDate().getTime() != date)) continue;
            shown.add(a);
        }
        adapter.submit(shown, admin); binding.refreshButton.setEnabled(!loading);
        binding.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        int message = loading && entries.isEmpty() ? R.string.appointment_loading : error != 0 ? error
                : shown.isEmpty() ? entries.isEmpty() ? admin ? R.string.appointment_admin_empty : R.string.appointment_list_empty
                : R.string.appointment_no_matches : 0;
        binding.message.setVisibility(message == 0 ? View.GONE : View.VISIBLE);
        if (message != 0) binding.message.setText(message);
    }
    @Override protected void onStart() { super.onStart(); handler.post(clock); }
    @Override protected void onStop() { handler.removeCallbacks(clock); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("permissionOwner", permissionOwner); state.putString("permissionEpoch", permissionEpoch);
        state.putLong("filterDate", date);
        state.putString("filterDoctor", binding.doctorFilter.getText() == null ? "" : binding.doctorFilter.getText().toString());
        state.putInt("filterStatus", binding.statusFilter.getSelectedItemPosition());
        super.onSaveInstanceState(state);
    }
}
