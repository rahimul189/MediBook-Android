package com.example.medibook.models;

/** Display data for a verified session, not an independent source of Admin authorization. */
public final class UserProfile {

    public enum Role {
        PATIENT("Patient"), ADMIN("Admin");

        private final String profileValue;

        Role(String profileValue) {
            this.profileValue = profileValue;
        }

        public String getProfileValue() {
            return profileValue;
        }
    }

    private final String fullName;
    private final String email;
    private final String phone;
    private final Role role;

    public UserProfile(String fullName, String email, String phone, Role role) {
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.role = role;
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

    public Role getRole() {
        return role;
    }
}
