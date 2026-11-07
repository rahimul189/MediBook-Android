package com.example.medibook.activities;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.example.medibook.models.UserProfile;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.repositories.DoctorRepository;
import com.example.medibook.utils.AuthNavigation;
import com.google.firebase.auth.FirebaseAuth;

/** Shared online role gate for the three appointment screens, never an Intent-supplied role. */
public abstract class AppointmentSessionActivity extends AppCompatActivity implements AuthRepository.Listener {
    protected String owner;
    protected UserProfile session;
    protected int requestVersion;
    private boolean started;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        owner = savedInstanceState == null ? (FirebaseAuth.getInstance().getCurrentUser() == null ? null
                : FirebaseAuth.getInstance().getCurrentUser().getUid()) : savedInstanceState.getString("appointmentOwner");
    }
    protected abstract View protectedContent();
    protected abstract View sessionLoading();
    protected abstract void onVerifiedSession();
    protected abstract void clearSessionViews();
    protected UserProfile.Role requiredRole() { return null; }
    protected final boolean canAccess() { return started && session != null && DoctorRepository.sameUser(owner) && !isFinishing(); }

    @Override protected void onStart() {
        super.onStart(); started = true;
        AuthRepository.getInstance().checkSession();
        AuthRepository.getInstance().addListener(this);
    }
    @Override protected void onStop() {
        started = false; session = null; requestVersion++;
        AuthRepository.getInstance().removeListener(this);
        protectedContent().setVisibility(View.GONE); sessionLoading().setVisibility(View.VISIBLE);
        clearSessionViews(); super.onStop();
    }
    @Override public final void onAuthStateChanged(AuthRepository.State state) {
        if (!started || isFinishing()) return;
        boolean allowed = !state.loading && state.user != null && DoctorRepository.sameUser(owner)
                && (requiredRole() == null || requiredRole() == state.user.getRole());
        session = allowed ? state.user : null;
        protectedContent().setVisibility(allowed ? View.VISIBLE : View.GONE);
        sessionLoading().setVisibility(state.loading ? View.VISIBLE : View.GONE);
        if (state.loading) { requestVersion++; clearSessionViews(); return; }
        if (!allowed) {
            clearSessionViews(); AuthNavigation.openDestination(this, state.user); return;
        }
        onVerifiedSession();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("appointmentOwner", owner); super.onSaveInstanceState(state);
    }
}
