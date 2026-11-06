package com.example.medibook.activities.admin;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.medibook.R;
import com.example.medibook.adapters.DoctorScheduleAdapter;
import com.example.medibook.databinding.ActivityManageDoctorScheduleBinding;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.models.UserProfile;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.repositories.DoctorRepository;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.DoctorScheduleContract;
import com.example.medibook.utils.DoctorScheduleValidation;
import com.example.medibook.viewmodels.DoctorScheduleListViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

/** Admin-gated server directory; no local schedule results or optimistic status changes. */
public class ManageDoctorScheduleActivity extends AppCompatActivity implements AuthRepository.Listener,
        DoctorScheduleListViewModel.Listener, DoctorScheduleAdapter.Listener {
    private ActivityManageDoctorScheduleBinding binding;
    private AuthRepository authRepository;
    private DoctorScheduleListViewModel model;
    private DoctorScheduleAdapter adapter;
    private ActivityResultLauncher<Intent> editorLauncher;
    private String doctorId;
    private String doctorName;
    private String owner;
    private int pendingChange;
    private String pendingSavedId;
    private boolean validContext;
    private boolean adminVerified;
    private boolean openingEditor;
    private AlertDialog statusDialog;
    private Snackbar message;
    private final OnBackPressedCallback blockPendingBack = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() { showMessage(R.string.schedule_wait_for_write); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityManageDoctorScheduleBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        authRepository = AuthRepository.getInstance();
        try {
            doctorId = getIntent().getStringExtra(DoctorScheduleContract.EXTRA_DOCTOR_ID);
            doctorName = getIntent().getStringExtra(DoctorScheduleContract.EXTRA_DOCTOR_NAME);
            owner = getIntent().getStringExtra(DoctorScheduleContract.EXTRA_OWNER);
            validContext = DoctorScheduleContract.validContext(doctorId, doctorName, owner);
        } catch (RuntimeException ignored) { validContext = false; }
        model = new ViewModelProvider(this).get(DoctorScheduleListViewModel.class);
        model.initialize(doctorId);
        if (savedInstanceState != null) {
            pendingChange = savedInstanceState.getInt("pendingChange");
            pendingSavedId = savedInstanceState.getString("pendingSavedId");
        }
        adapter = new DoctorScheduleAdapter(this);
        binding.adminContent.setLayoutManager(new LinearLayoutManager(this));
        binding.adminContent.setAdapter(adapter);
        getOnBackPressedDispatcher().addCallback(this, blockPendingBack);
        binding.toolbar.setNavigationOnClickListener(view -> {
            if (model.getState().writing) showMessage(R.string.schedule_wait_for_write);
            else finish();
        });
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.actionLogout) return false;
            authRepository.signOut();
            return true;
        });
        editorLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            openingEditor = false;
            if (result.getResultCode() != RESULT_OK || result.getData() == null || !validContext) return;
            try {
                Intent data = result.getData();
                String id = data.getStringExtra(DoctorScheduleContract.EXTRA_SCHEDULE_ID);
                int change = data.getIntExtra(DoctorScheduleContract.EXTRA_CHANGE, 0);
                if (doctorId.equals(data.getStringExtra(DoctorScheduleContract.EXTRA_DOCTOR_ID))
                        && owner.equals(data.getStringExtra(DoctorScheduleContract.EXTRA_OWNER))
                        && DoctorScheduleValidation.validId(id)
                        && (change == DoctorScheduleContract.ADDED || change == DoctorScheduleContract.EDITED)) {
                    pendingSavedId = id;
                    pendingChange = change;
                }
            } catch (RuntimeException ignored) { /* Malformed results never become records or success. */ }
            // A real server-confirmed editor result can arrive before the fresh Admin check completes.
            if (canAccess()) {
                model.refresh();
                onSchedulesChanged(model.getState());
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        openingEditor = false;
        model.addListener(this);
        authRepository.checkSession();
        authRepository.addListener(this);
    }

    @Override
    protected void onStop() {
        authRepository.removeListener(this);
        model.removeListener(this);
        adminVerified = false;
        binding.adminContent.setVisibility(View.GONE);
        if (statusDialog != null) statusDialog.dismiss();
        if (message != null) message.dismiss();
        super.onStop();
    }

    @Override
    public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        adminVerified = !state.loading && state.user != null && state.user.getRole() == UserProfile.Role.ADMIN;
        binding.adminContent.setVisibility(adminVerified ? View.VISIBLE : View.GONE);
        binding.sessionLoading.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        if (state.loading) {
            if (statusDialog != null) statusDialog.dismiss();
            return;
        }
        if (!adminVerified || (validContext && !DoctorRepository.sameUser(owner))) {
            adminVerified = false;
            binding.adminContent.setVisibility(View.GONE);
            model.endSession();
            pendingChange = 0;
            pendingSavedId = null;
            AuthNavigation.openDestination(this, state.user);
            return;
        }
        if (validContext && !model.startSession(owner)) {
            model.endSession();
            AuthNavigation.openDestination(this, state.user);
            return;
        }
        onSchedulesChanged(model.getState());
    }

    private boolean canAccess() {
        return adminVerified && validContext && DoctorRepository.sameUser(owner) && !isFinishing();
    }

    private boolean canAct() {
        return canAccess() && !openingEditor && model.getState().canAct();
    }

    @Override
    public void onSchedulesChanged(DoctorScheduleListViewModel.State state) {
        if (!adminVerified || isFinishing()) return;
        blockPendingBack.setEnabled(state.writing);
        if (state.doctorName != null) doctorName = state.doctorName;
        adapter.submit(doctorName, validContext, state.schedules, state.loading, state.writing, state.busyId, state.error);
        if (model.takeStatusSaved()) showMessage(R.string.schedule_status_saved);
        if (pendingChange != 0 && canAccess()) {
            if (pendingChange == DoctorScheduleContract.ADDED) showMessage(R.string.schedule_added);
            else if (pendingChange == DoctorScheduleContract.EDITED) showMessage(R.string.schedule_saved);
            pendingChange = 0;
        }
        if (!state.loading && state.loaded && pendingSavedId != null) {
            for (int index = 0; index < state.schedules.size(); index++) {
                if (state.schedules.get(index).getId().equals(pendingSavedId)) {
                    binding.adminContent.scrollToPosition(index + 1);
                    break;
                }
            }
            pendingSavedId = null;
        }
    }

    @Override
    public void onAddSchedule() {
        if (!canAct()) return;
        launchEditor(null);
    }

    @Override
    public void onEditSchedule(DoctorSchedule entry) {
        if (canAct()) launchEditor(entry);
    }

    private void launchEditor(DoctorSchedule entry) {
        openingEditor = true;
        Intent intent = new Intent(this, AddEditDoctorScheduleActivity.class);
        DoctorScheduleContract.putContext(intent, doctorId, doctorName, owner);
        // Only path identities cross Activities. The editor independently loads the server version.
        if (entry != null) intent.putExtra(DoctorScheduleContract.EXTRA_SCHEDULE_ID, entry.getId());
        editorLauncher.launch(intent);
    }

    @Override
    public void onToggleSchedule(DoctorSchedule entry) {
        if (!canAct() || (statusDialog != null && statusDialog.isShowing())) return;
        statusDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(entry.isActive() ? R.string.schedule_disable_title : R.string.schedule_enable_title)
                .setMessage(R.string.schedule_status_message)
                .setNegativeButton(R.string.cancel_button, null)
                .setPositiveButton(entry.isActive() ? R.string.schedule_disable_button
                        : R.string.schedule_enable_button, (dialog, which) -> {
                    if (canAct()) model.setActive(entry, !entry.isActive());
                }).show();
    }

    @Override
    public void onRetrySchedules() {
        if (!adminVerified) return;
        if (!validContext) { finish(); return; }
        if (canAccess()) model.refresh();
    }

    private void showMessage(int resource) {
        if (message != null) message.dismiss();
        message = Snackbar.make(binding.getRoot(), resource, Snackbar.LENGTH_LONG);
        message.show();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt("pendingChange", pendingChange);
        outState.putString("pendingSavedId", pendingSavedId);
        super.onSaveInstanceState(outState);
    }
}
