package com.example.medibook.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medibook.R;
import com.example.medibook.airquality.AirQualityReport;
import com.example.medibook.databinding.ItemAirQualityHeaderBinding;
import com.example.medibook.databinding.ItemAirQualityMetricBinding;
import com.example.medibook.utils.AppConstants;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** One scroll, including the city/timestamps/attribution, for small screens and large font sizes. */
public final class AirQualityAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private AirQualityReport report;
    private boolean cached;
    public void submit(AirQualityReport report, boolean cached) {
        int before = getItemCount();
        boolean changed = this.report != report;
        this.report = report; this.cached = cached;
        notifyItemChanged(0);
        int after = getItemCount();
        if (after > before) notifyItemRangeInserted(1, after - before);
        else if (before > after) notifyItemRangeRemoved(1, before - after);
        else if (changed && after > 1) notifyItemRangeChanged(1, after - 1);
    }
    public void refreshFreshness() { notifyItemChanged(0); }
    @Override public int getItemCount() { return report == null ? 1 : 5; }
    @Override public int getItemViewType(int position) { return position == 0 ? 0 : 1; }
    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return type == 0 ? new Header(ItemAirQualityHeaderBinding.inflate(inflater, parent, false))
                : new Metric(ItemAirQualityMetricBinding.inflate(inflater, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Context context = holder.itemView.getContext();
        if (holder instanceof Header) {
            Header header = (Header) holder;
            header.binding.freshness.setVisibility(report == null ? View.GONE : View.VISIBLE);
            header.binding.timestamp.setVisibility(report == null ? View.GONE : View.VISIBLE);
            if (report == null) return;
            long now = System.currentTimeMillis();
            boolean stale = report.staleEstimate(now) || !report.cacheFresh(now);
            header.binding.freshness.setText(stale ? R.string.air_quality_stale : cached ? R.string.air_quality_cached : R.string.air_quality_live);
            SimpleDateFormat date = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH);
            date.setTimeZone(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
            header.binding.timestamp.setText(context.getString(R.string.air_quality_timestamp,
                    date.format(new Date(report.estimatedAt)), date.format(new Date(report.fetchedAt))));
            return;
        }
        Metric metric = (Metric) holder;
        int[] titles = {R.string.air_quality_aqi, R.string.air_quality_pm25, R.string.air_quality_pm10, R.string.air_quality_uv};
        Double[] values = {report.usAqi, report.pm25, report.pm10, report.uv};
        int index = position - 1;
        Double value = values[index];
        metric.binding.title.setText(titles[index]);
        String formatted = value == null ? context.getString(R.string.air_quality_unavailable)
                : String.format(Locale.ENGLISH, index == 0 ? "%.0f" : "%.1f", value);
        metric.binding.value.setText(value != null && (index == 1 || index == 2) ? context.getString(R.string.air_quality_particle_unit, formatted) : formatted);
        metric.binding.guidance.setVisibility(value == null ? View.GONE : View.VISIBLE);
        if (value != null) metric.binding.guidance.setText(index == 0 ? aqiGuidance(value)
                : index == 3 ? value < 3 ? R.string.air_quality_uv_low : R.string.air_quality_uv_protect : R.string.air_quality_particle_note);
    }
    private static int aqiGuidance(double value) {
        if (value <= 50) return R.string.air_quality_good;
        if (value <= 100) return R.string.air_quality_moderate;
        if (value <= 150) return R.string.air_quality_sensitive;
        if (value <= 200) return R.string.air_quality_unhealthy;
        if (value <= 300) return R.string.air_quality_very_unhealthy;
        return R.string.air_quality_hazardous;
    }
    private static final class Header extends RecyclerView.ViewHolder {
        final ItemAirQualityHeaderBinding binding;
        Header(ItemAirQualityHeaderBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }
    private static final class Metric extends RecyclerView.ViewHolder {
        final ItemAirQualityMetricBinding binding;
        Metric(ItemAirQualityMetricBinding binding) {
            super(binding.getRoot()); this.binding = binding; ViewCompat.setAccessibilityHeading(binding.title, true);
        }
    }
}
