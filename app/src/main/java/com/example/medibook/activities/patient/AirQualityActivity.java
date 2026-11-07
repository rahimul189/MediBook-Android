package com.example.medibook.activities.patient;

import android.os.Bundle;
import android.view.View;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.medibook.R;
import com.example.medibook.activities.AppointmentSessionActivity;
import com.example.medibook.adapters.AirQualityAdapter;
import com.example.medibook.airquality.AirQualityException;
import com.example.medibook.databinding.ActivityAirQualityBinding;
import com.example.medibook.models.UserProfile;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.viewmodels.AirQualityViewModel;

public final class AirQualityActivity extends AppointmentSessionActivity {
    private ActivityAirQualityBinding binding;
    private AirQualityViewModel model;
    private AirQualityAdapter adapter;
    private final android.os.Handler clock = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable freshness = new Runnable() {
        @Override public void run() { if (canAccess()) adapter.refreshFreshness(); clock.postDelayed(this, 60_000); }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); binding = ActivityAirQualityBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot()); binding.toolbar.setNavigationOnClickListener(v -> finish());
        adapter = new AirQualityAdapter(); binding.list.setLayoutManager(new LinearLayoutManager(this)); binding.list.setAdapter(adapter);
        model = new ViewModelProvider(this).get(AirQualityViewModel.class);
        binding.retryButton.setOnClickListener(v -> { if (canAccess()) model.load(true); });
        model.state().observe(this, value -> { if (canAccess()) render(value); });
    }
    @Override protected UserProfile.Role requiredRole() { return UserProfile.Role.PATIENT; }
    @Override protected View protectedContent() { return binding.content; }
    @Override protected View sessionLoading() { return binding.sessionLoading.getRoot(); }
    @Override protected void onVerifiedSession() { model.load(false); render(model.state().getValue()); }
    @Override protected void clearSessionViews() { adapter.submit(null, false); binding.message.setText(null); }
    @Override protected void onStart() { super.onStart(); clock.post(freshness); }
    @Override protected void onStop() { clock.removeCallbacks(freshness); super.onStop(); }
    private void render(AirQualityViewModel.State state) {
        if (state == null) return;
        adapter.submit(state.report, state.cached);
        binding.progress.setVisibility(state.loading ? View.VISIBLE : View.GONE); binding.retryButton.setEnabled(!state.loading);
        int text = state.loading ? R.string.air_quality_loading : state.error == AirQualityException.Kind.NETWORK ? R.string.air_quality_network_error
                : state.error == AirQualityException.Kind.HTTP ? R.string.air_quality_http_error
                : state.error == AirQualityException.Kind.FORMAT ? R.string.air_quality_format_error
                : state.report == null || !state.report.hasMeasurements() ? R.string.air_quality_empty : 0;
        binding.message.setVisibility(text == 0 ? View.GONE : View.VISIBLE);
        if (text != 0) binding.message.setText(text);
    }
}
