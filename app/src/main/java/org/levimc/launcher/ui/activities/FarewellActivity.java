package org.levimc.launcher.ui.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import org.levimc.launcher.R;

public class FarewellActivity extends AppCompatActivity {

    private static final long FAREWELL_DURATION_MS = 1000L;

    private final Handler exitHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_farewell);
        exitHandler.postDelayed(this::exitApp, FAREWELL_DURATION_MS);
    }

    private void exitApp() {
        finishAffinity();
        System.exit(0);
    }
}
