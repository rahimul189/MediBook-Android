package com.example.medibook.utils;

import com.example.medibook.models.UserProfile;
import com.google.firebase.Timestamp;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic session validation, separate from network/navigation for local unit testing.
 * Call only with refreshed Firebase token claims and the owner's server-fetched profile.
 */
public final class SessionPolicy {
    private static final Set<String> PROFILE_FIELDS = new HashSet<>(
            Arrays.asList("fullName", "email", "phone", "role", "createdAt"));

    public enum Failure { NONE, INVALID_PROFILE, ROLE_MISMATCH }

    public static final class Result {
        public final UserProfile user;
        public final Failure failure;

        private Result(UserProfile user, Failure failure) {
            this.user = user;
            this.failure = failure;
        }
    }

    private SessionPolicy() {
    }

    public static Result evaluate(Map<String, Object> claims, Map<String, Object> profile,
                                  String authenticatedEmail) {
        if (claims == null || profile == null || !PROFILE_FIELDS.equals(profile.keySet())) {
            return denied(Failure.INVALID_PROFILE);
        }
        Object name = profile.get("fullName");
        Object email = profile.get("email");
        Object phone = profile.get("phone");
        if (!(name instanceof String) || !PatientProfileValidation.isValidName((String) name)
                || !(email instanceof String) || ((String) email).trim().isEmpty()
                || ((String) email).length() > 254 || !email.equals(authenticatedEmail)
                || !(phone instanceof String) || !PatientProfileValidation.isValidPhone((String) phone)
                || !(profile.get("createdAt") instanceof Timestamp)) {
            return denied(Failure.INVALID_PROFILE);
        }

        UserProfile.Role role;
        if (Boolean.TRUE.equals(claims.get("admin"))
                && UserProfile.Role.ADMIN.getProfileValue().equals(profile.get("role"))) {
            role = UserProfile.Role.ADMIN;
        } else if (!claims.containsKey("admin")
                && UserProfile.Role.PATIENT.getProfileValue().equals(profile.get("role"))) {
            role = UserProfile.Role.PATIENT;
        } else {
            // A profile role alone, or an explicit malformed/false Admin claim, grants nothing.
            return denied(Failure.ROLE_MISMATCH);
        }
        return new Result(new UserProfile((String) name, (String) email, (String) phone, role), Failure.NONE);
    }

    public static Result evaluatePatientRegistration(Map<String, Object> claims,
                                                     Map<String, Object> profile,
                                                     String authenticatedEmail) {
        Result result = evaluate(claims, profile, authenticatedEmail);
        if (result.user != null && result.user.getRole() != UserProfile.Role.PATIENT) {
            return denied(Failure.ROLE_MISMATCH);
        }
        return result;
    }

    private static Result denied(Failure failure) {
        return new Result(null, failure);
    }
}
