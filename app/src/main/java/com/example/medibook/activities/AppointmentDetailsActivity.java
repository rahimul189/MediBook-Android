package com.example.medibook.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.lifecycle.ViewModelProvider;

import com.example.medibook.R;
import com.example.medibook.databinding.ActivityAppointmentDetailsBinding;
import com.example.medibook.models.Appointment;
import com.example.medibook.repositories.AppointmentRepository;
import com.example.medibook.repositories.DoctorMapper;
import com.example.medibook.utils.AppointmentPolicy;
import com.example.medibook.utils.AppointmentUi;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.viewmodels.AppointmentOperationViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class AppointmentDetailsActivity extends AppointmentSessionActivity {
    private ActivityAppointmentDetailsBinding binding;
    private final AppointmentRepository repository = new AppointmentRepository();
    private AppointmentOperationViewModel operation;
    private Appointment current;
    private String id;
    private boolean busy, observing;
    private androidx.appcompat.app.AlertDialog confirmation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clock = new Runnable() {
        @Override public void run() { if (canAccess()) render(); handler.postDelayed(this, 30_000); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); binding = ActivityAppointmentDetailsBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot()); id = getIntent().getStringExtra("appointmentId");
        operation = new ViewModelProvider(this).get(AppointmentOperationViewModel.class);
        binding.toolbar.setNavigationOnClickListener(view -> finish());
        binding.refreshButton.setOnClickListener(view -> { if (canAccess() && !busy) load(); });
        binding.cancelButton.setOnClickListener(view -> {
            if (!canAccess() || busy || !AppointmentPolicy.canCancel(current, System.currentTimeMillis())) return;
            confirmation = new MaterialAlertDialogBuilder(this).setTitle(R.string.appointment_cancel_title)
                    .setMessage(R.string.appointment_cancel_message).setNegativeButton(R.string.cancel_button, null)
                    .setPositiveButton(R.string.appointment_cancel, (dialog, which) -> {
                        if (!canAccess() || busy || !AppointmentPolicy.canCancel(current, System.currentTimeMillis())) return;
                        requestVersion++; busy = true; render(); binding.message.setText(R.string.appointment_working);
                        operation.operation = repository.cancel(owner, id); observe();
                    }).show();
        });
    }
    @Override protected View protectedContent() { return binding.content; }
    @Override protected View sessionLoading() { return binding.sessionLoading.getRoot(); }
    @Override protected void onVerifiedSession() {
        if (getIntent().hasExtra("reminderOwner") && !com.example.medibook.local.LocalPatientServices.get().matches(
                getIntent().getStringExtra("reminderOwner"), getIntent().getStringExtra("reminderEpoch"))) {
            // Stale notification taps after logout/account switching never load its appointment.
            finish(); return;
        }
        if (!DoctorMapper.isValidId(id)) { binding.message.setText(R.string.appointment_invalid); return; }
        if (operation.operation != null) { busy = true; render(); observe(); } else load();
    }
    @Override protected void clearSessionViews() {
        current = null; observing = false; binding.details.setText(null); binding.message.setText(null);
        binding.cancelButton.setVisibility(View.GONE); if (confirmation != null) confirmation.dismiss();
    }
    private void load() {
        busy = true; current = null; render(); binding.message.setText(R.string.appointment_loading);
        int version = ++requestVersion;
        repository.get(owner, id).addOnCompleteListener(this, task -> {
            if (!canAccess() || version != requestVersion) return;
            busy = false;
            if (task.isSuccessful()) { current = task.getResult(); binding.message.setText(null); loadSerial(version); }
            else binding.message.setText(AppointmentUi.errorText(this, task.getException()));
            render();
        });
    }
    private void render() {
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE); binding.refreshButton.setEnabled(!busy);
        binding.details.setText(current == null ? null : AppointmentUi.summary(this, current, true));
        binding.cancelButton.setVisibility(!busy && AppointmentPolicy.canCancel(current, System.currentTimeMillis()) ? View.VISIBLE : View.GONE);
    }
    private void loadSerial(int version) {
        if (current == null) return;
        new com.example.medibook.repositories.SlotSerialRepository().appointments(owner, java.util.Collections.singletonList(current))
                .addOnSuccessListener(this, values -> {
                    if (canAccess() && version == requestVersion && !busy) { current = values.get(0); render(); }
                });
    }
    private void observe() {
        if (observing || operation.operation == null) return;
        observing = true;
        operation.operation.addOnCompleteListener(this, task -> {
            if (!canAccess()) return;
            observing = false; operation.operation = null; busy = false;
            if (task.isSuccessful()) { current = task.getResult(); binding.message.setText(R.string.appointment_cancellation_saved); loadSerial(requestVersion); }
            else binding.message.setText(AppointmentUi.errorText(this, task.getException()));
            render();
        });
    }
    @Override protected void onStart() { super.onStart(); handler.post(clock); }
    @Override protected void onStop() { handler.removeCallbacks(clock); super.onStop(); }
}
