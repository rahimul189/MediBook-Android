package com.example.medibook.activities.patient;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.medibook.R;
import com.example.medibook.adapters.PatientDoctorAdapter;
import com.example.medibook.adapters.PatientHomeHeaderAdapter;
import com.example.medibook.databinding.ActivityPatientHomeBinding;
import com.example.medibook.models.Doctor;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.PatientDoctorProfileContract;
import com.example.medibook.utils.PatientScreenAccess;
import com.example.medibook.viewmodels.PatientDoctorDirectoryViewModel;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/** Patient-only live directory. Reads start only after online session verification. */
public class PatientHomeActivity extends AppCompatActivity implements AuthRepository.Listener,
        PatientDoctorDirectoryViewModel.Listener, PatientHomeHeaderAdapter.Listener, PatientDoctorAdapter.Listener {

    private ActivityPatientHomeBinding binding;
    private AuthRepository repository;
    private PatientDoctorDirectoryViewModel model;
    private PatientHomeHeaderAdapter header;
    private PatientDoctorAdapter doctors;
    private boolean patientVerified;
    private boolean openingProfile;
    private Bundle pendingFilters;
    private final com.example.medibook.repositories.PatientDoctorRepository live = new com.example.medibook.repositories.PatientDoctorRepository();
    private com.google.firebase.firestore.ListenerRegistration subscription;
    private int generation;
    private com.example.medibook.repositories.RealtimeDisplayRepository.Subscription liveDisplay;
    private String liveOwner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPatientHomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        repository = AuthRepository.getInstance();
        model = new ViewModelProvider(this).get(PatientDoctorDirectoryViewModel.class);
        pendingFilters = savedInstanceState;
        header = new PatientHomeHeaderAdapter(this, model.getState());
        doctors = new PatientDoctorAdapter(this, model.getState());
        binding.patientContent.setLayoutManager(new LinearLayoutManager(this));
        binding.patientContent.setAdapter(new ConcatAdapter(header, doctors));
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.actionLogout) return false;
            repository.signOut();
            return true;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        openingProfile = false;
        model.addListener(this);
        // Recheck on re-entry, not just after login. Never trust an Intent or cached role.
        repository.checkSession();
        repository.addListener(this);
    }

    @Override
    protected void onStop() {
        stopLive();
        stopDirectory();
        repository.removeListener(this);
        model.removeListener(this);
        patientVerified = false;
        binding.patientContent.setVisibility(View.GONE);
        binding.sessionLoading.getRoot().setVisibility(View.VISIBLE);
        header.closeMenus();
        header.setPatientName(null);
        super.onStop();
    }

    @Override
    public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        String uid = currentUid();
        patientVerified = PatientScreenAccess.authorized(state.loading, state.user, uid);
        binding.sessionLoading.getRoot().setVisibility(state.loading ? View.VISIBLE : View.GONE);
        binding.patientContent.setVisibility(patientVerified ? View.VISIBLE : View.GONE);
        if (state.loading) { stopLive(); stopDirectory(); header.closeMenus(); header.setPatientName(null); return; }

        if (!patientVerified) {
            stopLive();
            stopDirectory();
            model.endSession();
            pendingFilters = null;
            // A verified Admin belongs in the Admin flow, never in Patient Home.
            AuthNavigation.openDestination(this, uid == null ? null : state.user);
        } else {
            header.setPatientName(state.user.getFullName());
            if (!PatientScreenAccess.sameOwner(model.getOwner(), uid)) stopDirectory();
            model.bindSession(uid);
            if (pendingFilters != null) {
                // Saved search text is UI state, never a role or an authorization decision.
                if (PatientScreenAccess.sameOwner(pendingFilters.getString("filterOwner"), uid)) {
                    model.restoreFilters(pendingFilters.getString("query", ""), pendingFilters.getString("specialization", ""));
                }
                pendingFilters = null;
            }
            onDirectoryChanged(model.getState());
            if (subscription == null) startDirectory();
            if (liveDisplay == null || !uid.equals(liveOwner)) startLive();
        }
    }

    private String currentUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? null : user.getUid();
    }

    private boolean canUseDirectory() {
        return patientVerified && PatientScreenAccess.sameOwner(model.getOwner(), currentUid()) && !isFinishing();
    }

    @Override public void onDirectoryChanged(PatientDoctorDirectoryViewModel.State state) {
        if (!canUseDirectory()) return;
        header.submit(state);
        doctors.submit(state);
    }

    @Override public void onSearchChanged(String query) { if (canUseDirectory()) model.setQuery(query); }
    @Override public void onOpenAppointments() {
        if (canUseDirectory()) startActivity(new Intent(this, com.example.medibook.activities.AppointmentsActivity.class));
    }
    @Override public void onRetryLive() { if (canUseDirectory()) startLive(); }
    private void stopLive() {
        if (liveDisplay != null) liveDisplay.close();
        liveDisplay = null; liveOwner = null; header.setLiveDisplay(null);
    }
    private void startLive() {
        stopLive(); liveOwner = model.getOwner();
        liveDisplay = com.example.medibook.repositories.RealtimeDisplayRepository.dashboard(liveOwner, false, (status, appointment) -> {
            if (canUseDirectory()) header.setLiveDisplay(appointment == null && (status == R.string.live_empty
                    || status == R.string.live_not_configured || status == R.string.live_connecting) ? null
                    : com.example.medibook.utils.RealtimeDisplayUi.dashboard(this, status, appointment, false),
                    appointment == null || status != R.string.live_verified);
        });
    }
    @Override public void onSpecializationChanged(String value) { if (canUseDirectory()) model.setSpecialization(value); }
    @Override public void onClearFilters() { if (canUseDirectory()) model.clearFilters(); }

    @Override public void onSearchSubmitted() {
        if (canUseDirectory()) WindowCompat.getInsetsController(getWindow(), binding.getRoot()).hide(WindowInsetsCompat.Type.ime());
    }

    @Override public void onRetryDirectory() {
        if (!canUseDirectory()) return;
        startDirectory();
    }

    private void stopDirectory() {
        generation++;
        if (subscription != null) subscription.remove();
        subscription = null;
    }

    private void startDirectory() {
        stopDirectory();
        final int request = generation;
        final String owner = model.getOwner();
        model.showLoading(owner);
        subscription = live.doctors(owner, (records, error) -> {
            if (request != generation || !canUseDirectory()) return;
            if (error == com.example.medibook.repositories.DoctorRepository.Error.NONE) model.showDirectory(owner, records);
            else if (error == com.example.medibook.repositories.DoctorRepository.Error.PERMISSION) model.showPermission(owner);
            else model.showError(owner);
        });
    }

    @Override public void onViewDoctor(Doctor selected) {
        if (!canUseDirectory() || openingProfile || selected == null) return;
        for (Doctor current : model.getState().doctors) {
            if (current.getId().equals(selected.getId())) { openProfile(current); return; }
        }
    }

    private void openProfile(Doctor doctor) {
        openingProfile = true;
        onSearchSubmitted();
        header.closeMenus();
        Intent intent = new Intent(this, DoctorProfileActivity.class);
        PatientDoctorProfileContract.putDoctorContext(intent, model.getOwner(), doctor);
        startActivity(intent);
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle outState) {
        PatientDoctorDirectoryViewModel.State state = model.getState();
        outState.putString("filterOwner", model.getOwner());
        outState.putString("query", state.query);
        outState.putString("specialization", state.specialization);
        super.onSaveInstanceState(outState);
    }
}
