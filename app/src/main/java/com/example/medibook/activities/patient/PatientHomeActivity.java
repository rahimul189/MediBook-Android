package com.example.medibook.activities.patient;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.example.medibook.R;
import com.example.medibook.databinding.ActivityPatientHomeBinding;
import com.example.medibook.models.UserProfile;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;

/** Temporary patient-only landing screen, not a doctor/appointment dashboard. */
public class PatientHomeActivity extends AppCompatActivity implements AuthRepository.Listener {

    private ActivityPatientHomeBinding binding;
    private AuthRepository repository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPatientHomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        repository = AuthRepository.getInstance();
        binding.logoutButton.setOnClickListener(view -> repository.signOut());
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Recheck on re-entry, not just after login. Never trust an Intent or cached role.
        repository.checkSession();
        repository.addListener(this);
    }

    @Override
    protected void onStop() {
        repository.removeListener(this);
        // Hide patient data in the stopped Activity while a future session check is pending.
        binding.welcomeMessage.setVisibility(View.GONE);
        binding.welcomeMessage.setText(null);
        binding.patientDescription.setVisibility(View.GONE);
        super.onStop();
    }

    @Override
    public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        binding.loadingIndicator.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        binding.sessionStatus.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        boolean authorized = !state.loading && state.user != null
                && state.user.getRole() == UserProfile.Role.PATIENT;
        binding.welcomeMessage.setVisibility(authorized ? View.VISIBLE : View.GONE);
        binding.patientDescription.setVisibility(authorized ? View.VISIBLE : View.GONE);
        if (state.loading) return;

        if (!authorized) {
            // A verified Admin belongs in the Admin flow, never in Patient Home.
            AuthNavigation.openDestination(this, state.user);
        } else {
            binding.welcomeMessage.setText(getString(R.string.patient_welcome, state.user.getFullName()));
        }
    }
}
