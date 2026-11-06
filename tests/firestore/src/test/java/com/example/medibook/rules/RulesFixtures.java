package com.example.medibook.rules;

import org.junit.Before;

import java.util.Map;

import static com.example.medibook.rules.RulesClient.*;

/** Synthetic setup uses emulator bypass; ALL permission assertions use separate end-user clients. */
public abstract class RulesFixtures {
    static Map<String, Object> profile() {
        return map("fullName", "Patient fixture", "email", "patient@example.invalid", "phone", "1234567",
                "role", "Patient", "createdAt", OLD_TIME);
    }
    static Map<String, Object> doctor(boolean active) {
        return map("name", "Rules fixture", "specialization", "Fixture specialty", "qualifications", "Fixture qualification",
                "consultationFee", 50025, "biography", "", "active", active, "createdAt", OLD_TIME, "updatedAt", OLD_TIME);
    }
    static Map<String, Object> newDoctor() {
        return merge(doctor(true), map("createdAt", Special.SERVER_TIME, "updatedAt", Special.SERVER_TIME));
    }
    static Map<String, Object> schedule(boolean active) {
        return map("dayOfWeek", 1, "startTime", 540, "endTime", 660, "slotDurationMinutes", 20, "active", active, "updatedAt", OLD_TIME);
    }
    static Map<String, Object> newSchedule() { return merge(schedule(true), map("updatedAt", Special.SERVER_TIME)); }
    static String schedulePath(String doctor, String id) { return "doctors/" + doctor + "/schedules/" + id; }
    static final String ENABLED = "doctors/active/schedules/enabled";
    static final String DISABLED = "doctors/active/schedules/disabled";

    @Before public void seedOnlyTheOwnedDemoEmulator() {
        clearFixtures();
        seed("users/patient-a", profile());
        seed("users/fake-admin", merge(profile(), map("role", "Admin")));
        seed("doctors/active", doctor(true));
        seed("doctors/inactive", doctor(false));
        seed(ENABLED, schedule(true));
        seed(DISABLED, schedule(false));
        seed("doctors/inactive/schedules/enabled", schedule(true));
        seed("doctors/missing/schedules/orphan", schedule(true));
    }
}
