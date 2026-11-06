package com.example.medibook.activities.auth;

import android.os.Bundle;
import android.util.Patterns;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.example.medibook.R;
import com.example.medibook.databinding.ActivityRegisterBinding;
import com.example.medibook.utils.AuthUi;
import com.google.android.material.snackbar.Snackbar;

public class RegisterActivity extends AppCompatActivity {

    private ActivityRegisterBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());

        binding.backToLoginButton.setOnClickListener(view -> finish());
        binding.registerButton.setOnClickListener(view -> validateForm());
    }

    private void validateForm() {
        binding.fullNameLayout.setError(null);
        binding.emailLayout.setError(null);
        binding.phoneLayout.setError(null);
        binding.passwordLayout.setError(null);
        binding.confirmPasswordLayout.setError(null);

        String name = binding.fullNameInput.getText().toString().trim();
        String email = binding.emailInput.getText().toString().trim();
        String phone = binding.phoneInput.getText().toString().trim();
        String password = binding.passwordInput.getText().toString();
        String confirmation = binding.confirmPasswordInput.getText().toString();
        View firstInvalid = null;

        if (name.isEmpty()) {
            binding.fullNameLayout.setError(getString(R.string.error_name_required));
            firstInvalid = binding.fullNameInput;
        }
        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailLayout.setError(getString(email.isEmpty()
                    ? R.string.error_email_required : R.string.error_email_invalid));
            if (firstInvalid == null) firstInvalid = binding.emailInput;
        }
        int digitCount = phone.replaceAll("[^0-9]", "").length();
        if (!Patterns.PHONE.matcher(phone).matches() || digitCount < 7 || digitCount > 15) {
            binding.phoneLayout.setError(getString(R.string.error_phone_invalid));
            if (firstInvalid == null) firstInvalid = binding.phoneInput;
        }
        if (password.length() < 6) {
            binding.passwordLayout.setError(getString(password.isEmpty()
                    ? R.string.error_password_required : R.string.error_password_short));
            if (firstInvalid == null) firstInvalid = binding.passwordInput;
        }
        if (confirmation.isEmpty() || !password.equals(confirmation)) {
            binding.confirmPasswordLayout.setError(getString(confirmation.isEmpty()
                    ? R.string.error_confirm_password_required : R.string.error_password_mismatch));
            if (firstInvalid == null) firstInvalid = binding.confirmPasswordInput;
        }

        if (firstInvalid != null) {
            firstInvalid.requestFocus();
        } else {
            Snackbar.make(binding.getRoot(), R.string.auth_not_connected, Snackbar.LENGTH_LONG).show();
        }
    }
}
