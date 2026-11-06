package com.example.medibook.activities.admin;

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

/** Minimal Admin landing screen. No doctor/appointment data or privileged writes yet. */
public class AdminDashboardActivity extends AppCompatActivity implements AuthRepository.Listener {
    private ActivityAdminDashboardBinding binding;
    private AuthRepository repository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAdminDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        ViewCompat.setAccessibilityHeading(binding.dashboardHeading, true);
        repository = AuthRepository.getInstance();
        binding.logoutButton.setOnClickListener(view -> repository.signOut());
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
        repository.removeListener(this);
        binding.adminContent.setVisibility(View.GONE);
        binding.welcomeMessage.setText(null);
        super.onStop();
    }

    @Override
    public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        boolean authorized = !state.loading && state.user != null
                && state.user.getRole() == UserProfile.Role.ADMIN;
        binding.loadingIndicator.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        binding.sessionStatus.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        binding.adminContent.setVisibility(authorized ? View.VISIBLE : View.GONE);
        // Logout stays available during a pending session check; late callbacks are ignored.
        if (state.loading) return;

        if (!authorized) {
            // An authenticated Patient is redirected to Patient Home without Admin content.
            // A failed verification already signed out in the repository and routes to Login.
            AuthNavigation.openDestination(this, state.user);
        } else {
            binding.welcomeMessage.setText(getString(R.string.admin_welcome, state.user.getFullName()));
        }
    }
}
