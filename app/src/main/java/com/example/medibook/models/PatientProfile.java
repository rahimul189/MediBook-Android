package com.example.medibook.models;

/** Patient data only. Passwords and Firebase credentials never belong in this model. */
public final class PatientProfile {

    public static final String ROLE = "Patient";

    private final String fullName;
    private final String email;
    private final String phone;

    public PatientProfile(String fullName, String email, String phone) {
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }
}
