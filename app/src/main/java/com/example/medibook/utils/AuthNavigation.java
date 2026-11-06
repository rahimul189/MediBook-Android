package com.example.medibook.utils;

import android.app.Activity;
import android.content.Intent;

import com.example.medibook.activities.admin.AdminDashboardActivity;
import com.example.medibook.activities.auth.LoginActivity;
import com.example.medibook.activities.patient.PatientHomeActivity;
import com.example.medibook.models.UserProfile;

/** Navigation only: each protected Activity must independently recheck the repository session. */
public final class AuthNavigation {
    private AuthNavigation() {
    }

    public static void openDestination(Activity activity, UserProfile verifiedUser) {
        Class<?> destination;
        if (verifiedUser == null) {
            destination = LoginActivity.class;
        } else if (verifiedUser.getRole() == UserProfile.Role.ADMIN) {
            destination = AdminDashboardActivity.class;
        } else if (verifiedUser.getRole() == UserProfile.Role.PATIENT) {
            destination = PatientHomeActivity.class;
        } else {
            destination = LoginActivity.class;
        }
        Intent intent = new Intent(activity, destination);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
    }
}
