package com.example.medibook.activities.auth;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.example.medibook.R;
import com.example.medibook.databinding.ActivityLoginBinding;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.utils.AuthErrorMessages;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;

public class LoginActivity extends AppCompatActivity implements AuthRepository.Listener {

    private ActivityLoginBinding binding;
    private AuthRepository repository;
    private final OnBackPressedCallback blockBackWhileLoading = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            // Finish the authentication/profile operation before navigating away.
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        repository = AuthRepository.getInstance();
        getOnBackPressedDispatcher().addCallback(this, blockBackWhileLoading);

        binding.createAccountButton.setOnClickListener(view ->
                startActivity(new Intent(this, RegisterActivity.class)));
        binding.loginButton.setOnClickListener(view -> validateForm());
    }

    @Override
    protected void onStart() {
        super.onStart();
        repository.addListener(this);
    }

    @Override
    protected void onStop() {
        repository.removeListener(this);
        super.onStop();
    }

    @Override
    public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        binding.emailLayout.setEnabled(!state.loading);
        binding.passwordLayout.setEnabled(!state.loading);
        binding.loginButton.setEnabled(!state.loading);
        binding.createAccountButton.setEnabled(!state.loading);
        binding.loginButton.setText(state.loading ? R.string.signing_in : R.string.login_button);
        binding.loadingIndicator.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        blockBackWhileLoading.setEnabled(state.loading);
        binding.statusMessage.setVisibility(state.error == AuthRepository.Error.NONE ? View.GONE : View.VISIBLE);
        if (state.error != AuthRepository.Error.NONE) {
            binding.statusMessage.setText(AuthErrorMessages.forError(state.error));
        }
        if (state.user != null) {
            binding.passwordInput.setText(null);
            AuthNavigation.openDestination(this, state.user);
        }
    }

    private void validateForm() {
        binding.statusMessage.setVisibility(View.GONE);
        binding.emailLayout.setError(null);
        binding.passwordLayout.setError(null);

        String email = binding.emailInput.getText().toString().trim();
        String password = binding.passwordInput.getText().toString();
        boolean valid = true;

        if (email.isEmpty()) {
            binding.emailLayout.setError(getString(R.string.error_email_required));
            valid = false;
        } else if (email.length() > 254 || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailLayout.setError(getString(R.string.error_email_invalid));
            valid = false;
        }
        if (password.isEmpty()) {
            binding.passwordLayout.setError(getString(R.string.error_password_required));
            valid = false;
        }

        if (valid) {
            repository.signIn(email, password);
        } else if (binding.emailLayout.getError() != null) {
            binding.emailInput.requestFocus();
        } else {
            binding.passwordInput.requestFocus();
        }
    }
}
