package com.example.medibook.activities.patient;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import com.example.medibook.R;
import com.example.medibook.databinding.ActivityDoctorProfileBinding;
import com.example.medibook.models.Doctor;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.repositories.DoctorMapper;
import com.example.medibook.repositories.DoctorRepository;
import com.example.medibook.repositories.PatientDoctorRepository;
import com.example.medibook.utils.AppConstants;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.DoctorScheduleUi;
import com.example.medibook.utils.PatientDoctorDirectory;
import com.example.medibook.utils.PatientDoctorProfileContract;
import com.example.medibook.utils.PatientScheduleDisplay;
import com.example.medibook.utils.PatientScreenAccess;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.ListenerRegistration;

/** Live Patient profile and weekly schedules; no booking or writes. */
public class DoctorProfileActivity extends AppCompatActivity implements AuthRepository.Listener {
    private ActivityDoctorProfileBinding binding;
    private AuthRepository repository;
    private String owner;
    private Doctor doctor;
    private String doctorId;
    private boolean verified;
    private int generation;
    private int scheduleGeneration;
    private ListenerRegistration doctorListener, scheduleListener;
    private final PatientDoctorRepository live = new PatientDoctorRepository();
    private boolean validContext;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDoctorProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        repository = AuthRepository.getInstance();
        try {
            owner = getIntent().getStringExtra(PatientDoctorProfileContract.EXTRA_OWNER);
            doctorId = getIntent().getStringExtra(PatientDoctorProfileContract.EXTRA_ID);
            validContext = owner != null && !owner.isEmpty() && DoctorMapper.isValidId(doctorId);
        } catch (RuntimeException malformed) { validContext = false; }
        binding.toolbar.setNavigationOnClickListener(view -> finish());
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.actionLogout) return false;
            repository.signOut();
            return true;
        });
        binding.backToDirectoryButton.setOnClickListener(view -> finish());
        binding.errorBackButton.setOnClickListener(view -> finish());
        binding.retryButton.setOnClickListener(view -> { if (canRead() && validContext) startProfile(); });
        binding.scheduleRetryButton.setOnClickListener(view -> { if (canRead() && doctor != null) startSchedules(); });
        // Intentionally no Book Appointment click handler; it remains disabled and labelled as future work.
        for (View heading : new View[]{binding.doctorName, binding.qualificationsHeading, binding.feeHeading,
                binding.aboutHeading, binding.scheduleHeading, binding.errorHeading}) {
            ViewCompat.setAccessibilityHeading(heading, true);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        repository.checkSession();
        repository.addListener(this);
    }

    @Override protected void onStop() {
        verified = false;
        stopProfile();
        repository.removeListener(this);
        binding.patientContent.setVisibility(View.GONE);
        binding.sessionLoading.getRoot().setVisibility(View.VISIBLE);
        binding.doctorName.setText(null);
        binding.specialization.setText(null);
        binding.qualifications.setText(null);
        binding.consultationFee.setText(null);
        binding.biography.setText(null);
        super.onStop();
    }

    @Override public void onAuthStateChanged(AuthRepository.State state) {
        if (isFinishing()) return;
        String uid = currentUid();
        boolean patient = PatientScreenAccess.authorized(state.loading, state.user, uid);
        verified = patient;
        binding.sessionLoading.getRoot().setVisibility(state.loading ? View.VISIBLE : View.GONE);
        binding.patientContent.setVisibility(patient ? View.VISIBLE : View.GONE);
        if (state.loading) { stopProfile(); return; }
        if (!patient || (validContext && !PatientScreenAccess.sameOwner(owner, uid))) {
            stopProfile();
            binding.patientContent.setVisibility(View.GONE);
            AuthNavigation.openDestination(this, uid == null ? null : state.user);
            return;
        }
        if (validContext && doctorListener == null) startProfile();
        else if (!validContext) showFailure(R.string.patient_profile_error_message);
    }

    private String currentUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? null : user.getUid();
    }

    private void renderProfile() {
        binding.profileStatus.setText(R.string.doctor_active_status);
        binding.profileNotice.setVisibility(View.GONE);
        binding.timezoneHint.setText(getString(R.string.patient_profile_timezone, AppConstants.HOSPITAL_TIME_ZONE_ID));
        binding.doctorName.setText(doctor.getName());
        binding.specialization.setText(doctor.getSpecialization());
        binding.qualifications.setText(doctor.getQualifications());
        binding.consultationFee.setText(PatientDoctorDirectory.feeText(doctor));
        binding.biography.setText(doctor.getBiography().isEmpty() ? getString(R.string.patient_profile_biography_empty) : doctor.getBiography());
    }

    private boolean canRead() { return verified && !isFinishing() && PatientScreenAccess.sameOwner(owner, currentUid()); }
    private void stopSchedules() {
        scheduleGeneration++;
        if (scheduleListener != null) scheduleListener.remove();
        scheduleListener = null;
        binding.scheduleText.setText(null);
    }
    private void stopProfile() {
        generation++;
        stopSchedules();
        if (doctorListener != null) doctorListener.remove();
        doctorListener = null;
        doctor = null;
    }
    private void showLoading() {
        binding.errorHeading.setText(R.string.patient_profile_loading_title);
        binding.profileContent.setVisibility(View.GONE);
        binding.errorContent.setVisibility(View.VISIBLE);
        binding.errorMessage.setText(R.string.patient_profile_loading_message);
        binding.retryButton.setVisibility(View.GONE);
    }
    private void showFailure(int message) {
        binding.errorHeading.setText(R.string.patient_profile_error_title);
        binding.profileContent.setVisibility(View.GONE);
        binding.errorContent.setVisibility(View.VISIBLE);
        binding.errorMessage.setText(message);
        binding.retryButton.setVisibility(validContext ? View.VISIBLE : View.GONE);
    }
    private void startProfile() {
        stopProfile();
        final int request = generation;
        showLoading();
        doctorListener = live.doctor(owner, doctorId, (value, error) -> {
            if (request != generation || !canRead()) return;
            if (error != DoctorRepository.Error.NONE || value == null || !value.isActive()) {
                doctor = null;
                stopSchedules();
                showFailure(error == DoctorRepository.Error.PERMISSION ? R.string.patient_live_permission
                        : error == DoctorRepository.Error.NONE ? R.string.patient_live_unavailable : R.string.patient_live_error);
                return;
            }
            doctor = value;
            binding.errorContent.setVisibility(View.GONE);
            binding.profileContent.setVisibility(View.VISIBLE);
            renderProfile();
            if (scheduleListener == null) startSchedules();
        });
    }
    private void startSchedules() {
        stopSchedules();
        final int request = scheduleGeneration;
        binding.scheduleText.setText(R.string.patient_live_schedule_loading);
        binding.scheduleRetryButton.setVisibility(View.GONE);
        scheduleListener = live.schedules(owner, doctorId, (values, error) -> {
            if (request != scheduleGeneration || !canRead() || doctor == null) return;
            binding.scheduleRetryButton.setVisibility(error == DoctorRepository.Error.NONE ? View.GONE : View.VISIBLE);
            if (error != DoctorRepository.Error.NONE) {
                binding.scheduleText.setText(error == DoctorRepository.Error.PERMISSION ? R.string.patient_live_permission : R.string.patient_live_error);
                return;
            }
            if (values.isEmpty()) { binding.scheduleText.setText(R.string.patient_live_schedule_empty); return; }
            StringBuilder text = new StringBuilder();
            for (com.example.medibook.models.DoctorSchedule schedule : values) {
                if (text.length() > 0) text.append("\n\n");
                String interval = getResources().getQuantityString(R.plurals.patient_live_schedule_interval,
                        schedule.getSlotMinutes(), schedule.getSlotMinutes());
                text.append(getString(R.string.patient_live_schedule_row, DoctorScheduleUi.day(this, schedule.getDayOfWeek()),
                        PatientScheduleDisplay.time(schedule.getStartMinute()), PatientScheduleDisplay.time(schedule.getEndMinute()), interval));
            }
            binding.scheduleText.setText(text.toString());
        });
    }
}
