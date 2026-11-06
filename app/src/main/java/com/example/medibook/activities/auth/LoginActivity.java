package com.example.medibook.activities.auth;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;

import androidx.appcompat.app.AppCompatActivity;

import com.example.medibook.R;
import com.example.medibook.databinding.ActivityLoginBinding;
import com.example.medibook.utils.AuthUi;
import com.google.android.material.snackbar.Snackbar;

public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());

        binding.createAccountButton.setOnClickListener(view ->
                startActivity(new Intent(this, RegisterActivity.class)));
        binding.loginButton.setOnClickListener(view -> validateForm());
    }

    private void validateForm() {
        binding.emailLayout.setError(null);
        binding.passwordLayout.setError(null);

        String email = binding.emailInput.getText().toString().trim();
        String password = binding.passwordInput.getText().toString();
        boolean valid = true;

        if (email.isEmpty()) {
            binding.emailLayout.setError(getString(R.string.error_email_required));
            valid = false;
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailLayout.setError(getString(R.string.error_email_invalid));
            valid = false;
        }
        if (password.isEmpty()) {
            binding.passwordLayout.setError(getString(R.string.error_password_required));
            valid = false;
        }

        if (valid) {
            Snackbar.make(binding.getRoot(), R.string.auth_not_connected, Snackbar.LENGTH_LONG).show();
        } else if (binding.emailLayout.getError() != null) {
            binding.emailInput.requestFocus();
        } else {
            binding.passwordInput.requestFocus();
        }
    }
}
