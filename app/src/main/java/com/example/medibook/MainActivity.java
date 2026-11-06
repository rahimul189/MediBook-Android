package com.example.medibook;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.example.medibook.databinding.ActivityMainBinding;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.utils.AuthNavigation;
import com.example.medibook.utils.AuthUi;

public class MainActivity extends AppCompatActivity implements AuthRepository.Listener {

    private AuthRepository repository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        repository = AuthRepository.getInstance();
    }

    @Override
    protected void onStart() {
        super.onStart();
        repository.checkSession();
        repository.addListener(this);
    }

    @Override
    protected void onStop() {
        repository.removeListener(this);
        super.onStop();
    }

    @Override
    public void onAuthStateChanged(AuthRepository.State state) {
        if (state.loading || isFinishing()) return;
        AuthNavigation.openDestination(this, state.user);
    }
}
