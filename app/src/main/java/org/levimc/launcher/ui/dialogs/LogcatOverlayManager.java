package org.levimc.launcher.ui.dialogs;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.levimc.launcher.R;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.core.minecraft.MinecraftLoadingActivity;
import org.levimc.launcher.ui.activities.SplashActivity;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LogcatOverlayManager {

    private static volatile LogcatOverlayManager instance;
    private final Application app;
    private LogcatOverlay overlay;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean onSplash = false;
    private volatile boolean onLoading = false;

    // SAF export: one picker launcher per activity, registered at CREATED so
    // the registry can deliver the result after the file manager closes.
    private final Map<Activity, ActivityResultLauncher<String>> exportLaunchers = new HashMap<>();
    private Activity currentActivity;
    private List<String> pendingExportLines;

    private LogcatOverlayManager(Application app) {
        this.app = app;
        registerLifecycleCallbacks();
        registerSettingsListener();
    }

    public static void init(Application app) {
        if (instance == null) {
            synchronized (LogcatOverlayManager.class) {
                if (instance == null) instance = new LogcatOverlayManager(app);
            }
        }
    }

    public static LogcatOverlayManager getInstance() { return instance; }

    private void attachTo(Activity act) {
        if (overlay == null) overlay = new LogcatOverlay(act.getApplicationContext());
        ViewGroup root = act.findViewById(android.R.id.content);
        if (root == null) return;
        if (overlay.getParent() != root) {
            if (overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
            root.addView(overlay);
        }
        updateVisibility();
    }

    private void updateVisibility() {
        boolean show = FeatureSettings.getInstance().isLogcatOverlayEnabled() && !onSplash && !onLoading;
        mainHandler.post(() -> {
            if (overlay == null) return;
            if (show) overlay.show(); else overlay.hide();
        });
    }

    private void registerLifecycleCallbacks() {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                onSplash = activity instanceof SplashActivity;
                onLoading = activity instanceof MinecraftLoadingActivity;
                currentActivity = activity;
                attachTo(activity);
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {
                registerExportLauncher(a);
            }
            @Override public void onActivityDestroyed(Activity a) {
                exportLaunchers.remove(a);
                if (currentActivity == a) currentActivity = null;
            }
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        });
    }

    private void registerExportLauncher(Activity act) {
        if (exportLaunchers.containsKey(act)) return;
        if (!(act instanceof androidx.activity.ComponentActivity)) return;
        androidx.activity.ComponentActivity ca = (androidx.activity.ComponentActivity) act;
        exportLaunchers.put(act, ca.getActivityResultRegistry().register(
                "logcat_export",
                ca,
                new ActivityResultContracts.CreateDocument("text/plain"),
                this::handleExportResult));
    }

    // Opens the system file manager to pick the export location; falls back
    // to the share sheet when no picker launcher is available.
    public void requestExport(List<String> lines) {
        pendingExportLines = lines;
        Activity act = currentActivity;
        ActivityResultLauncher<String> launcher = act == null ? null : exportLaunchers.get(act);
        if (launcher != null) {
            String name = "levilogcat_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".log";
            launcher.launch(name);
        } else {
            shareFallback(lines);
        }
    }

    private void handleExportResult(Uri uri) {
        List<String> lines = pendingExportLines;
        pendingExportLines = null;
        if (uri == null || lines == null) return; // user cancelled
        new Thread(() -> {
            boolean ok;
            try (OutputStream os = app.getContentResolver().openOutputStream(uri);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
                for (String line : lines) {
                    writer.write(line.replace('\r', ' ').replace('\n', ' '));
                    writer.newLine();
                }
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean success = ok;
            mainHandler.post(() -> Toast.makeText(app,
                    success ? R.string.logcat_export_done : R.string.logcat_export_failed,
                    Toast.LENGTH_SHORT).show());
        }, "LogcatExport").start();
    }

    private void shareFallback(List<String> lines) {
        new Thread(() -> {
            try {
                File dir = new File(app.getCacheDir(), "exports");
                if (!dir.exists()) dir.mkdirs();
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File file = new File(dir, "levilogcat_" + stamp + ".log");
                try (java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        new FileOutputStream(file), StandardCharsets.UTF_8))) {
                    for (String line : lines) {
                        writer.write(line.replace('\r', ' ').replace('\n', ' '));
                        writer.newLine();
                    }
                }
                Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        app, app.getPackageName() + ".fileprovider", file);
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                mainHandler.post(() -> {
                    try {
                        app.startActivity(Intent.createChooser(send, app.getString(R.string.logcat_export)));
                    } catch (Exception e) {
                        Toast.makeText(app, R.string.logcat_export_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(app,
                        R.string.logcat_export_failed, Toast.LENGTH_SHORT).show());
            }
        }, "LogcatExport").start();
    }

    private void registerSettingsListener() {
        android.content.SharedPreferences sp = app.getSharedPreferences("feature_settings", Context.MODE_PRIVATE);
        sp.registerOnSharedPreferenceChangeListener((prefs, key) -> {
            if ("settings_json".equals(key)) updateVisibility();
        });
    }

    public void refreshVisibility() { updateVisibility(); }


}
