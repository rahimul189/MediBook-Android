package com.example.medibook.rules;

import org.junit.Test;

import java.net.URI;

import static com.example.medibook.rules.RulesClient.*;
import static org.junit.Assert.*;

/** These would fail with open rules, missing auth, an Admin SDK or accidental owner-token assertions. */
public class ClientRulesCanaryTest extends RulesFixtures {
    @Test public void anonymousRequestsReallyEncounterRules() {
        denied(anonymous().get("doctors/active"));
        denied(anonymous().set("doctors/new", newDoctor()));
    }

    @Test public void customClaimAdminIsAnEndUserNotTheFixtureBypass() {
        allowed(admin().set("doctors/new", newDoctor()));
        denied(admin().set("unrelated/canary", map("value", "fixture")));
        denied(admin().delete("doctors/active"));
        denied(authenticated("patient-a", map("admin", "true")).set("doctors/forged", newDoctor()));
    }

    @Test public void patientIdentityIsEnforcedNotJustAnAuthenticatedFlag() {
        allowed(patient().get("users/patient-a"));
        allowed(patient().get("doctors/active"));
        denied(authenticated("different-patient", map("email", "different@example.invalid")).get("users/patient-a"));
        denied(patient().update("doctors/active", map("active", false, "updatedAt", Special.SERVER_TIME)));
    }

    @Test public void transportRejectsProductionDifferentDatabasesAndAlternateEndpointsBeforeSending() {
        for (String target : new String[]{
                "https://firestore.googleapis.com/v1/" + DOCUMENTS,
                "http://localhost:8080/v1/" + DOCUMENTS,
                "http://127.0.0.1:8081/v1/" + DOCUMENTS,
                ORIGIN + "/v1/projects/medibook-2c3a4/databases/(default)/documents",
                ORIGIN + "/v1/projects/demo-medibook/databases/other/documents",
                ORIGIN + "/v1/" + DOCUMENTS + "-other",
                ORIGIN + "/v1/" + DOCUMENTS + "/../other",
                ORIGIN + "/v1/" + DOCUMENTS + "?project=other"}) {
            assertThrows(IllegalArgumentException.class, () -> requireLocal(URI.create(target)));
        }
        requireLocal(URI.create(ORIGIN + "/v1/" + DOCUMENTS + ":commit"));
    }
}
