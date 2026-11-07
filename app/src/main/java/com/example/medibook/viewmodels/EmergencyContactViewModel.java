package com.example.medibook.viewmodels;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.medibook.local.ContactPickerReader;
import com.example.medibook.local.ContactSelection;
import com.example.medibook.local.LocalPatientServices;
import com.google.android.gms.tasks.Tasks;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EmergencyContactViewModel extends AndroidViewModel {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<ContactSelection.Status> result = new MutableLiveData<>();
    public String resultOwner, resultEpoch;
    public boolean reading;
    public EmergencyContactViewModel(@NonNull Application application) { super(application); }
    public LiveData<ContactSelection.Status> result() { return result; }
    public void read(String uid, String token, boolean success, String uri) {
        if (reading) return;
        reading = true; resultOwner = uid; resultEpoch = token; result.setValue(null);
        Tasks.call(executor, () -> {
            ContactSelection selection = ContactPickerReader.read(getApplication().getContentResolver(), success, uri);
            if (selection.contact != null) LocalPatientServices.get().saveContact(uid, token, selection.contact);
            return selection.status;
        }).addOnCompleteListener(task -> {
            reading = false; result.setValue(task.isSuccessful() ? task.getResult() : ContactSelection.Status.UNAVAILABLE);
        });
    }
    @Override protected void onCleared() { executor.shutdown(); }
}
