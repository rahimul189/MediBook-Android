package com.example.medibook.activities.patient;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.ViewModelProvider;

import com.example.medibook.R;
import com.example.medibook.activities.AppointmentSessionActivity;
import com.example.medibook.databinding.ActivityEmergencyContactBinding;
import com.example.medibook.local.ContactSelection;
import com.example.medibook.local.EmergencyContact;
import com.example.medibook.local.LocalPatientServices;
import com.example.medibook.models.UserProfile;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.DialIntents;
import com.example.medibook.viewmodels.EmergencyContactViewModel;

public final class EmergencyContactActivity extends AppointmentSessionActivity {
    private ActivityEmergencyContactBinding binding;
    private EmergencyContactViewModel model;
    private String pickerOwner, pickerEpoch;
    private boolean pending, pendingSuccess;
    private String pendingUri;
    private final ActivityResultLauncher<Intent> picker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        pending = true; pendingSuccess = result.getResultCode() == Activity.RESULT_OK;
        pendingUri = result.getData() == null || result.getData().getData() == null ? null : result.getData().getData().toString();
        consumeResult();
    });
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); binding = ActivityEmergencyContactBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot()); binding.toolbar.setNavigationOnClickListener(v -> finish());
        model = new ViewModelProvider(this).get(EmergencyContactViewModel.class);
        if (state != null) { pickerOwner = state.getString("pickerOwner"); pickerEpoch = state.getString("pickerEpoch"); }
        binding.changeButton.setOnClickListener(v -> {
            if (!canAccess() || model.reading) return;
            pickerOwner = owner; pickerEpoch = LocalPatientServices.get().epoch(owner);
            try {
                picker.launch(new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI));
            } catch (ActivityNotFoundException | SecurityException unavailable) { binding.message.setText(R.string.contact_picker_unavailable); }
        });
        binding.callButton.setOnClickListener(v -> {
            if (!canAccess()) return;
            EmergencyContact contact = LocalPatientServices.get().contact(owner);
            if (contact != null) DialIntents.dial(this, contact.phone);
        });
        binding.removeButton.setOnClickListener(v -> {
            if (!canAccess()) return;
            LocalPatientServices.get().removeContact(owner); render(); binding.message.setText(R.string.contact_removed);
        });
        model.result().observe(this, status -> { if (canAccess()) { render(); showResult(status); } });
    }
    @Override protected UserProfile.Role requiredRole() { return UserProfile.Role.PATIENT; }
    @Override protected View protectedContent() { return binding.content; }
    @Override protected View sessionLoading() { return binding.sessionLoading.getRoot(); }
    @Override protected void onVerifiedSession() { consumeResult(); render(); showResult(model.result().getValue()); }
    @Override protected void clearSessionViews() {
        binding.contactName.setText(null); binding.contactPhone.setText(null); binding.message.setText(null);
    }
    private void consumeResult() {
        if (!pending || !canAccess()) return;
        pending = false;
        if (LocalPatientServices.get().matches(pickerOwner, pickerEpoch) && owner.equals(pickerOwner)) {
            model.read(pickerOwner, pickerEpoch, pendingSuccess, pendingUri);
        }
        pendingUri = null;
    }
    private void render() {
        EmergencyContact contact = LocalPatientServices.get().contact(owner);
        binding.contactName.setText(contact == null ? getString(R.string.contact_empty)
                : contact.name.isEmpty() ? getString(R.string.contact_unnamed) : contact.name);
        binding.contactPhone.setText(contact == null ? null : contact.phone);
        binding.changeButton.setText(contact == null ? R.string.contact_choose : R.string.contact_change);
        binding.changeButton.setEnabled(!model.reading);
        binding.callButton.setVisibility(contact == null ? View.GONE : View.VISIBLE);
        binding.removeButton.setVisibility(contact == null ? View.GONE : View.VISIBLE);
        binding.removeButton.setEnabled(!model.reading);
        if (model.reading) binding.message.setText(R.string.contact_reading);
    }
    private void showResult(ContactSelection.Status status) {
        if (status == null || !LocalPatientServices.get().matches(model.resultOwner, model.resultEpoch)) return;
        if (status == ContactSelection.Status.CANCELLED) binding.message.setText(null);
        else binding.message.setText(status == ContactSelection.Status.SELECTED ? R.string.contact_saved
                : status == ContactSelection.Status.NO_NUMBER ? R.string.contact_no_number : R.string.contact_unavailable);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("pickerOwner", pickerOwner); state.putString("pickerEpoch", pickerEpoch); super.onSaveInstanceState(state);
    }
}
