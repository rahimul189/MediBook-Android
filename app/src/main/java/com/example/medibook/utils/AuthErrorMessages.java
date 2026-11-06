package com.example.medibook.utils;

import androidx.annotation.StringRes;

import com.example.medibook.R;
import com.example.medibook.repositories.AuthRepository;

/** Maps safe error categories to display text without exposing Firebase exception details. */
public final class AuthErrorMessages {

    private AuthErrorMessages() {
    }

    @StringRes
    public static int forError(AuthRepository.Error error) {
        switch (error) {
            case NETWORK: return R.string.auth_error_network;
            case TOO_MANY_REQUESTS: return R.string.auth_error_rate_limit;
            case EMAIL_IN_USE: return R.string.auth_error_email_in_use;
            case WEAK_PASSWORD: return R.string.auth_error_password_policy;
            case INVALID_CREDENTIALS: return R.string.auth_error_credentials;
            case AUTH_UNAVAILABLE: return R.string.auth_error_unavailable;
            case PERMISSION_DENIED: return R.string.auth_error_permission;
            case PROFILE_MISSING: return R.string.auth_error_profile_missing;
            case INVALID_PROFILE: return R.string.auth_error_profile_invalid;
            case ROLE_MISMATCH: return R.string.auth_error_role_mismatch;
            case PROFILE_FAILED_ACCOUNT_REMOVED: return R.string.auth_error_profile_rolled_back;
            case PROFILE_FAILED_ACCOUNT_REMAINS: return R.string.auth_error_profile_cleanup_failed;
            default: return R.string.auth_error_unknown;
        }
    }
}
