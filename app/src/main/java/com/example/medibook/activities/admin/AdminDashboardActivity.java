package com.example.medibook.activities.admin;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import com.example.medibook.R;
import com.example.medibook.databinding.ActivityAdminDashboardBinding;
import com.example.medibook.models.UserProfile;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;

/** Verified Admin landing screen with secure Doctor Management navigation. */
public class AdminDashboardActivity extends AppCompatActivity implements AuthRepository.Listener {
    private ActivityAdminDashboardBinding binding;
    private AuthRepository repository;
    private boolean adminVerified;
    private com.example.medibook.repositories.RealtimeDisplayRepository.Subscription liveDisplay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAdminDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        ViewCompat.setAccessibilityHeading(binding.dashboardHeading, true);
        repository = AuthRepository.getInstance();
        binding.liveUpdates.liveRetry.setOnClickListener(view -> { if (adminVerified) startLive(); });
        binding.logoutButton.setOnClickListener(view -> repository.signOut());
        binding.manageDoctorsButton.setOnClickListener(view -> {
            if (adminVerified) startActivity(new Intent(this, ManageDoctorsActivity.class));
        });
        binding.manageAppointmentsButton.setOnClickListener(view -> {
            if (adminVerified) startActivity(new Intent(this, com.example.medibook.activities.AppointmentsActivity.class));
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Reload account, force-refresh claims and read the owner's profile from the server.
        // No Intent extra, saved role or profile-only decision may open Admin content.
        repository.checkSession();
        repository.addListener(this);
    }

    @Override
    protected void onStop() {
        stopLive();
        repository.removeListener(this);
        adminVerified = false;
        binding.adminContent.setVisibility(View.GONE);
        binding.welcomeMessage.setText(null);
        super.onStop();
    }

    @Override
    public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        boolean authorized = !state.loading && state.user != null
                && state.user.getRole() == UserProfile.Role.ADMIN;
        adminVerified = authorized;
        binding.loadingIndicator.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        binding.sessionStatus.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        binding.adminContent.setVisibility(authorized ? View.VISIBLE : View.GONE);
        // Logout stays available during a pending session check; late callbacks are ignored.
        if (state.loading) { stopLive(); return; }

        if (!authorized) {
            stopLive();
            // An authenticated Patient is redirected to Patient Home without Admin content.
            // A failed verification already signed out in the repository and routes to Login.
            AuthNavigation.openDestination(this, state.user);
        } else {
            binding.welcomeMessage.setText(getString(R.string.admin_welcome, state.user.getFullName()));
            if (liveDisplay == null) startLive();
        }
    }

    private void stopLive() {
        if (liveDisplay != null) liveDisplay.close();
        liveDisplay = null; binding.liveUpdates.liveMessage.setText(null);
    }
    private void startLive() {
        stopLive();
        String owner = com.example.medibook.repositories.DoctorRepository.currentUserId();
        liveDisplay = com.example.medibook.repositories.RealtimeDisplayRepository.dashboard(owner, true, (status, appointment) -> {
            if (adminVerified && com.example.medibook.repositories.DoctorRepository.sameUser(owner)) {
                binding.liveUpdates.getRoot().setVisibility(appointment == null && (status == R.string.live_empty
                        || status == R.string.live_not_configured || status == R.string.live_connecting) ? View.GONE : View.VISIBLE);
                binding.liveUpdates.liveRetry.setVisibility(appointment != null && status == R.string.live_verified ? View.GONE : View.VISIBLE);
                binding.liveUpdates.liveMessage.setText(com.example.medibook.utils.RealtimeDisplayUi.dashboard(this, status, appointment, true));
            }
        });
    }
}
