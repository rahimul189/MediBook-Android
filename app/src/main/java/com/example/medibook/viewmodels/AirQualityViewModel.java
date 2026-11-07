package com.example.medibook.viewmodels;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.medibook.airquality.AirQualityException;
import com.example.medibook.airquality.AirQualityReport;
import com.example.medibook.airquality.AirQualityRepository;
import com.google.android.gms.tasks.Tasks;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AirQualityViewModel extends AndroidViewModel {
    public static final class State {
        public final AirQualityReport report;
        public final boolean loading, cached;
        public final AirQualityException.Kind error;
        State(AirQualityReport report, boolean loading, boolean cached, AirQualityException.Kind error) {
            this.report = report; this.loading = loading; this.cached = cached; this.error = error;
        }
    }
    private final AirQualityRepository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>(new State(null, false, false, null));
    private boolean initialized;
    public AirQualityViewModel(@NonNull Application application) { super(application); repository = new AirQualityRepository(application); }
    public LiveData<State> state() { return state; }
    public void load(boolean force) {
        if (state.getValue().loading || initialized && !force) return;
        initialized = true;
        // Even disk-cache parsing happens off the UI thread.
        State previous = state.getValue();
        state.setValue(new State(previous.report, true, previous.cached, null));
        Tasks.call(executor, () -> {
            AirQualityReport cached = repository.cached();
            if (!force && cached != null && cached.cacheFresh(System.currentTimeMillis())) return new State(cached, false, true, null);
            if (cached != null) state.postValue(new State(cached, true, true, null));
            try { return new State(repository.fetch(), false, false, null); }
            catch (AirQualityException failure) { return new State(cached, false, true, failure.kind); }
        }).addOnCompleteListener(task -> state.setValue(task.isSuccessful() ? task.getResult()
                : new State(previous.report, false, true, AirQualityException.Kind.NETWORK)));
    }
    @Override protected void onCleared() { executor.shutdown(); }
}
