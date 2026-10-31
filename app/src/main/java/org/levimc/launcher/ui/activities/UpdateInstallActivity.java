package org.levimc.launcher.ui.activities;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.levimc.launcher.R;
import org.levimc.launcher.util.UpdateApkInstaller;
import org.levimc.launcher.util.GithubReleaseUpdater;
import org.levimc.launcher.util.PersonalizationManager;

import java.io.File;

public class UpdateInstallActivity extends BaseActivity {

    private static final String EXTRA_APK_PATH = "apk_path";
    private static final String EXTRA_INSTALL_METHOD = "install_method";
    private static final long FINISH_DELAY_MS = 1800L;
    private static final long STATUS_ROTATE_MS = 2200L;

    private ProgressBar progressBar;
    private TextView statusText;
    private Button closeButton;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRotator = new Runnable() {
        @Override
        public void run() {
            int res = statusIndex % 2 == 0
                    ? R.string.update_install_still_working
                    : R.string.update_install_almost;
            statusIndex++;
            statusText.setText(res);
            statusHandler.postDelayed(this, STATUS_ROTATE_MS);
        }
    };
    private int statusIndex;
    private boolean settled;

    public static void start(Context context, File apkFile, String installMethod) {
        Intent intent = new Intent(context, UpdateInstallActivity.class);
        intent.putExtra(EXTRA_APK_PATH, apkFile.getAbsolutePath());
        intent.putExtra(EXTRA_INSTALL_METHOD, installMethod);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update_install);

        progressBar = findViewById(R.id.install_progress);
        statusText = findViewById(R.id.install_status);
        closeButton = findViewById(R.id.btn_install_close);
        closeButton.setOnClickListener(v -> finish());
        applyImmersiveUi();

        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_APK_PATH);
        String method = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_INSTALL_METHOD);
        final File apkFile = path == null ? null : new File(path);
        if (apkFile == null || !apkFile.exists() || method == null) {
            finish();
            return;
        }

        statusText.setText(R.string.update_install_installing);
        statusHandler.postDelayed(statusRotator, STATUS_ROTATE_MS);

        UpdateApkInstaller.Callback callback = new UpdateApkInstaller.Callback() {
            @Override
            public void onSuccess() {
                onInstallSettled(true, getString(R.string.update_installed_restart));
            }

            @Override
            public void onFailure(int messageRes) {
                onInstallSettled(false, getString(messageRes));
            }
        };
        if (GithubReleaseUpdater.METHOD_ROOT.equals(method)) {
            UpdateApkInstaller.installWithRoot(apkFile, callback);
        } else {
            UpdateApkInstaller.installWithShizuku(this, apkFile, callback);
        }
    }

    private void onInstallSettled(boolean success, String message) {
        if (settled || isFinishing() || isDestroyed()) return;
        settled = true;
        statusHandler.removeCallbacks(statusRotator);
        statusText.setText(message);
        if (success) {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(100);
            statusHandler.postDelayed(this::finish, FINISH_DELAY_MS);
        } else {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(0);
            closeButton.setVisibility(View.VISIBLE);
        }
    }

    private void applyImmersiveUi() {
        int accent = new PersonalizationManager(this).getAccentColor();
        int baseBackground = ContextCompat.getColor(this, R.color.background);
        int secondaryText = ContextCompat.getColor(this, R.color.text_secondary);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(baseBackground);
        getWindow().setNavigationBarColor(baseBackground);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        View ring = findViewById(R.id.install_logo_ring);
        GradientDrawable ringBackground = new GradientDrawable();
        ringBackground.setShape(GradientDrawable.OVAL);
        ringBackground.setColor(withAlpha(accent, isDarkMode() ? 44 : 32));
        ringBackground.setStroke(dp(1), withAlpha(accent, isDarkMode() ? 110 : 92));
        ring.setBackground(ringBackground);

        TextView appName = findViewById(R.id.install_app_name);
        appName.setTextColor(accent);

        progressBar.setProgressTintList(ColorStateList.valueOf(accent));
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(accent));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(accent, 42)));

        statusText.setTextColor(blendColors(secondaryText, accent, 0.35f));
    }

    private boolean isDarkMode() {
        int mode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(Math.min(Math.max(alpha, 0), 255), Color.red(color), Color.green(color), Color.blue(color));
    }

    private int blendColors(int base, int blend, float ratio) {
        float inverse = 1f - ratio;
        float a = 1f;
        float r = Color.red(base) * inverse + Color.red(blend) * ratio;
        float g = Color.green(base) * inverse + Color.green(blend) * ratio;
        float b = Color.blue(base) * inverse + Color.blue(blend) * ratio;
        return Color.argb((int) a, (int) r, (int) g, (int) b);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        statusHandler.removeCallbacks(statusRotator);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (settled) super.onBackPressed();
    }
}
