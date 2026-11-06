package com.example.medibook.rules;

import org.junit.ClassRule;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({ClientRulesCanaryTest.class, DoctorRulesTest.class, ScheduleRulesTest.class})
public class RulesIntegrationSuite {
    @ClassRule public static final LocalFirestoreEmulator EMULATOR = new LocalFirestoreEmulator();
}
