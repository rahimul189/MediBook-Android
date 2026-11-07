package com.example.medibook.adapters;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medibook.R;
import com.example.medibook.databinding.ItemPatientDirectoryHeaderBinding;
import com.example.medibook.viewmodels.PatientDoctorDirectoryViewModel;

import java.util.ArrayList;
import java.util.List;

/** A separate ConcatAdapter header keeps search focus stable when only result rows change. */
public final class PatientHomeHeaderAdapter extends RecyclerView.Adapter<PatientHomeHeaderAdapter.Holder> {
    public interface Listener {
        void onSearchChanged(String query);
        void onSpecializationChanged(String specialization);
        void onSearchSubmitted();
        void onOpenAppointments();
        void onRetryLive();
    }

    private final Listener listener;
    private PatientDoctorDirectoryViewModel.State state;
    private String patientName;
    private boolean handlingInput;
    private Holder bound;
    private String liveDisplay;
    private boolean liveRetry;

    public void setLiveDisplay(String text) {
        setLiveDisplay(text, true);
    }
    public void setLiveDisplay(String text, boolean retry) {
        liveDisplay = text;
        liveRetry = retry;
        // Avoid re-binding the search header (and interrupting its keyboard) on live events.
        if (bound != null) bound.renderLive();
    }

    public PatientHomeHeaderAdapter(Listener listener, PatientDoctorDirectoryViewModel.State initial) {
        this.listener = listener;
        state = initial;
    }

    public void submit(PatientDoctorDirectoryViewModel.State next) {
        state = next;
        if (!handlingInput) notifyItemChanged(0);
        else if (bound != null) bound.renderSummary();
    }

    public void setPatientName(String name) { patientName = name; notifyItemChanged(0); }

    public void closeMenus() {
        if (bound != null) {
            bound.binding.specializationInput.dismissDropDown();
            bound.binding.searchInput.clearFocus();
        }
    }

    @Override public int getItemCount() { return 1; }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemPatientDirectoryHeaderBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) { bound = holder; holder.bind(); }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        holder.binding.specializationInput.dismissDropDown();
        if (bound == holder) bound = null;
        super.onViewRecycled(holder);
    }

    public final class Holder extends RecyclerView.ViewHolder {
        private final ItemPatientDirectoryHeaderBinding binding;
        private boolean rendering;
        private List<String> options = new ArrayList<>();

        Holder(ItemPatientDirectoryHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            ViewCompat.setAccessibilityHeading(binding.welcomeMessage, true);
            ViewCompat.setAccessibilityHeading(binding.directoryHeading, true);
            binding.appointmentsButton.setOnClickListener(view -> listener.onOpenAppointments());
            binding.liveUpdates.liveRetry.setOnClickListener(view -> listener.onRetryLive());
            binding.searchInput.setOnEditorActionListener((view, action, event) -> {
                if (action != EditorInfo.IME_ACTION_SEARCH) return false;
                view.clearFocus();
                listener.onSearchSubmitted();
                return true;
            });
            binding.searchInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                    if (rendering) return;
                    handlingInput = true;
                    try { listener.onSearchChanged(text.toString()); }
                    finally { handlingInput = false; }
                }
                @Override public void afterTextChanged(Editable text) { }
            });
            binding.specializationInput.setOnItemClickListener((parent, view, position, id) -> {
                if (rendering || position < 0 || position > options.size()) return;
                handlingInput = true;
                try { listener.onSpecializationChanged(position == 0 ? "" : options.get(position - 1)); }
                finally { handlingInput = false; }
            });
        }

        void bind() {
            rendering = true;
            try {
                binding.welcomeMessage.setVisibility(patientName == null ? View.GONE : View.VISIBLE);
                renderLive();
                binding.welcomeMessage.setText(patientName == null ? null
                        : itemView.getContext().getString(R.string.patient_welcome, patientName));
                String current = binding.searchInput.getText() == null ? "" : binding.searchInput.getText().toString();
                if (!current.equals(state.query)) binding.searchInput.setText(state.query);
                options = state.specializations;
                String[] labels = new String[options.size() + 1];
                labels[0] = itemView.getContext().getString(R.string.patient_specialization_all);
                for (int index = 0; index < options.size(); index++) labels[index + 1] = options.get(index);
                binding.specializationInput.setSimpleItems(labels);
                binding.specializationInput.setText(state.specialization.isEmpty() ? labels[0] : state.specialization, false);
                binding.specializationLayout.setEnabled(!options.isEmpty()
                        && state.status == PatientDoctorDirectoryViewModel.Status.READY);
                boolean loadingSpecializations = options.isEmpty()
                        && state.status == PatientDoctorDirectoryViewModel.Status.LOADING;
                binding.specializationLayout.setHelperText(options.isEmpty()
                        ? itemView.getContext().getString(loadingSpecializations
                        ? R.string.patient_specialization_loading : R.string.patient_specialization_pending) : null);
                binding.searchLayout.setEnabled(state.status != PatientDoctorDirectoryViewModel.Status.LOADING);
                renderSummary();
            } finally { rendering = false; }
        }

        void renderSummary() {
            boolean ready = state.status == PatientDoctorDirectoryViewModel.Status.READY;
            binding.resultCount.setVisibility(ready ? View.VISIBLE : View.GONE);
            if (ready) binding.resultCount.setText(itemView.getContext().getResources().getQuantityString(
                    R.plurals.patient_directory_result_count, state.doctors.size(), state.doctors.size()));
        }
        void renderLive() {
            binding.liveUpdates.getRoot().setVisibility(liveDisplay == null ? View.GONE : View.VISIBLE);
            binding.liveUpdates.liveMessage.setText(liveDisplay);
            binding.liveUpdates.liveRetry.setVisibility(liveRetry ? View.VISIBLE : View.GONE);
        }
    }
}
