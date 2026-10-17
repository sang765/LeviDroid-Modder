package org.levimc.launcher.util;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.levimc.launcher.R;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;

import rikka.shizuku.Shizuku;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class GithubReleaseUpdater {
    private static final String TAG = "GithubReleaseUpdater";
    private static final String GITHUB_LATEST_API = "https://api.github.com/repos/%s/%s/releases/latest";
    private static final String APK_ASSET_KEYWORD = ".apk";
    private static final String PREF_IGNORED_VERSION = "update_ignored_version";
    private final Activity activity;
    private final String owner;
    private final String repo;
    private final OkHttpClient client = new OkHttpClient();
    private ActivityResultLauncher<Intent> permissionResultLauncher;

    public GithubReleaseUpdater(Activity activity, String owner, String repo,
                                ActivityResultLauncher<Intent> permissionResultLauncher) {
        this.activity = activity;
        this.owner = owner;
        this.repo = repo;
        this.permissionResultLauncher = permissionResultLauncher;
    }

    public static int compareVersion(String a, String b) {
        String[] x = tokenizeVersion(a);
        String[] y = tokenizeVersion(b);
        int len = Math.max(x.length, y.length);
        for (int i = 0; i < len; i++) {
            int vi = i < x.length ? Integer.parseInt(x[i]) : 0;
            int vj = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (vi > vj) return 1;
            if (vi < vj) return -1;
        }
        return 0;
    }

    // Tags like "v1.5.27-modder.21" carry non-numeric suffixes that crash a
    // plain dot-split parseInt; compare the embedded numeric tokens instead.
    private static String[] tokenizeVersion(String v) {
        String[] raw = v.split("\\D+");
        int start = 0;
        while (start < raw.length && raw[start].isEmpty()) start++;
        return java.util.Arrays.copyOfRange(raw, start, raw.length);
    }

    public void checkUpdate() {
        String url = String.format(GITHUB_LATEST_API, owner, repo);
        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Request failed: " + e.getMessage());
                activity.runOnUiThread(() ->
                        Toast.makeText(activity, activity.getString(R.string.update_check_failed, e.getMessage()), Toast.LENGTH_LONG).show());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    String latestVersion = json.getString("tag_name");
                    JSONArray assets = json.getJSONArray("assets");
                    String downloadUrl = pickApkUrl(assets);
                    if (downloadUrl == null) {
                        Log.e(TAG, "No APK asset found in release.");
                        return;
                    }
                    String localVersion = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
                    if (compareVersion(latestVersion, localVersion) > 0) {
                        showUpdateDialog(latestVersion, downloadUrl);
                    } else {
                        activity.runOnUiThread(() ->
                                Toast.makeText(activity, activity.getString(R.string.already_latest_version, localVersion), Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Parse error: " + e.getMessage());
                    activity.runOnUiThread(() ->
                            Toast.makeText(activity, activity.getString(R.string.update_check_failed, e.getMessage()), Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    public void checkUpdateOnLaunch() {
        String url = String.format(GITHUB_LATEST_API, owner, repo);
        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Request failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    String latestVersion = json.getString("tag_name");
                    JSONArray assets = json.getJSONArray("assets");
                    String downloadUrl = pickApkUrl(assets);
                    if (downloadUrl == null) {
                        Log.e(TAG, "No APK asset found in release.");
                        return;
                    }
                    String localVersion = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
                    SharedPreferences prefs = activity.getSharedPreferences("no_update_version", Context.MODE_PRIVATE);
                    String ignoredVersion = prefs.getString(PREF_IGNORED_VERSION, "");

                    if (compareVersion(latestVersion, localVersion) > 0
                            && !ignoredVersion.equals(latestVersion)) {
                        showUpdateDialogWithIgnore(latestVersion, downloadUrl);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Parse error: " + e.getMessage());
                }
            }
        });
    }

    // Releases carry per-ABI APKs plus a universal one; pick the one matching
    // this device and fall back to universal so old single-APK releases keep working.
    private static String pickApkUrl(JSONArray assets) {
        String fallback = null;
        String universal = null;
        String[] supportedAbis = Build.SUPPORTED_ABIS;
        String deviceAbi = supportedAbis.length == 0 ? "" : supportedAbis[0];
        for (int i = 0; i < assets.length(); i++) {
            try {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.getString("name");
                if (!name.endsWith(APK_ASSET_KEYWORD)) continue;
                String url = asset.getString("browser_download_url");
                if (fallback == null) fallback = url;
                if (name.contains("universal")) universal = url;
                if (!deviceAbi.isEmpty() && name.contains(deviceAbi)) return url;
            } catch (Exception e) {
                Log.e(TAG, "Asset parse error: " + e.getMessage());
            }
        }
        return universal != null ? universal : fallback;
    }

    private void showUpdateDialog(String version, String url) {
        activity.runOnUiThread(() -> {
            CustomAlertDialog dialog = new CustomAlertDialog(activity);
            dialog.setTitleText(activity.getString(R.string.new_version_found, version));
            dialog.setMessage(activity.getString(R.string.update_question));
            dialog.setPositiveButton(activity.getString(R.string.download_update), (d) -> downloadApk(url));
            dialog.setNegativeButton(activity.getString(R.string.cancel), null);
            dialog.show();
        });
    }

    private void showUpdateDialogWithIgnore(String version, String url) {
        activity.runOnUiThread(() -> {
            CustomAlertDialog dialog = new CustomAlertDialog(activity);
            dialog.setTitleText(activity.getString(R.string.new_version_found, version));
            dialog.setMessage(activity.getString(R.string.update_question));
            dialog.setPositiveButton(activity.getString(R.string.download_update), (d) -> downloadApk(url));
            dialog.setNegativeButton(activity.getString(R.string.cancel), null);
            dialog.setNeutralButton(activity.getString(R.string.ignore_this_version), (d) -> ignoreThisVersion(version));
            dialog.show();
        });
    }

    private void ignoreThisVersion(String version) {
        SharedPreferences prefs = activity.getSharedPreferences("no_update_version", Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_IGNORED_VERSION, version).apply();
        activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.version_ignored), Toast.LENGTH_SHORT).show());
    }

    private void downloadApk(String url) {
        Request request = new Request.Builder().url(url).build();
        Call call = client.newCall(request);
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean backgrounded = new AtomicBoolean(false);
        AtomicReference<CustomAlertDialog> dialogRef = new AtomicReference<>();
        AtomicReference<ProgressBar> barRef = new AtomicReference<>();
        AtomicReference<TextView> textRef = new AtomicReference<>();

        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            View progressView = activity.getLayoutInflater().inflate(R.layout.dialog_update_progress, null);
            ProgressBar progressBar = progressView.findViewById(R.id.update_progress_bar);
            TextView progressText = progressView.findViewById(R.id.update_progress_text);
            CustomAlertDialog dialog = new CustomAlertDialog(activity);
            dialog.setTitleText(activity.getString(R.string.downloading_update));
            dialog.setCustomView(progressView);
            dialog.setPositiveButton(activity.getString(R.string.update_download_background),
                    v -> backgrounded.set(true));
            dialog.setNegativeButton(activity.getString(R.string.cancel), v -> {
                cancelled.set(true);
                call.cancel();
            });
            // Any dismissal that is neither a background nor a completed download
            // (back key, activity teardown) must not leave a headless download running.
            dialog.setOnDismissAnimationEndListener(() -> {
                if (!backgrounded.get() && !finished.get()) {
                    cancelled.set(true);
                    call.cancel();
                }
            });
            barRef.set(progressBar);
            textRef.set(progressText);
            dialogRef.set(dialog);
            dialog.show();
        });

        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (call.isCanceled() || cancelled.get()) return;
                dismissProgressDialog(dialogRef);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    Toast.makeText(activity, activity.getString(R.string.update_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) {
                InputStream is = null;
                FileOutputStream fos = null;
                try {
                    byte[] buf = new byte[4096];
                    int len;
                    long downloaded = 0;
                    long total = response.body().contentLength();
                    is = response.body().byteStream();
                    File apkFile = new File(activity.getExternalCacheDir(), "update_apk.apk");
                    fos = new FileOutputStream(apkFile);

                    long lastUiUpdate = 0;
                    while ((len = is.read(buf)) > 0) {
                        if (cancelled.get()) break;
                        fos.write(buf, 0, len);
                        downloaded += len;

                        long now = System.currentTimeMillis();
                        if (now - lastUiUpdate > 250) {
                            lastUiUpdate = now;
                            updateProgressDialog(barRef, textRef, downloaded, total, cancelled, backgrounded);
                        }
                    }
                    fos.flush();
                    if (cancelled.get() || call.isCanceled()) return;
                    finished.set(true);
                    dismissProgressDialog(dialogRef);
                    installApk(apkFile);
                } catch (Exception e) {
                    if (call.isCanceled() || cancelled.get()) return;
                    dismissProgressDialog(dialogRef);
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        Toast.makeText(activity, activity.getString(R.string.update_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                    });
                } finally {
                    try {
                        if (is != null) is.close();
                    } catch (Exception ignored) {
                    }
                    try {
                        if (fos != null) fos.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void dismissProgressDialog(AtomicReference<CustomAlertDialog> dialogRef) {
        activity.runOnUiThread(() -> {
            CustomAlertDialog dialog = dialogRef.get();
            if (dialog != null && dialog.isShowing()) {
                dialog.dismissImmediately();
            }
        });
    }

    private void updateProgressDialog(AtomicReference<ProgressBar> barRef, AtomicReference<TextView> textRef,
                                      long downloaded, long total,
                                      AtomicBoolean cancelled, AtomicBoolean backgrounded) {
        activity.runOnUiThread(() -> {
            if (cancelled.get() || backgrounded.get()) return;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            ProgressBar bar = barRef.get();
            TextView text = textRef.get();
            if (bar == null || text == null) return;
            if (total <= 0) {
                if (!bar.isIndeterminate()) bar.setIndeterminate(true);
                text.setText(formatBytes(downloaded));
            } else {
                if (bar.isIndeterminate()) bar.setIndeterminate(false);
                int percent = (int) (downloaded * 100 / total);
                bar.setProgress(percent);
                text.setText(activity.getString(R.string.update_download_progress,
                        percent, formatBytes(downloaded), formatBytes(total)));
            }
        });
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    public static final String METHOD_SYSTEM = "system";
    public static final String METHOD_SHIZUKU = "shizuku";
    public static final String METHOD_ROOT = "root";

    private static final int REQ_SHIZUKU_PERMISSION = 1001;
    // shortcut: bindUserService has no failure callback — a dead or wedged
    // Shizuku server would leave the "installing" dialog up forever, so give
    // the bind a deadline and fail into the help dialog (pattern from
    // InstallerX Revived, which instead relies on the user backing out).
    private static final long SHIZUKU_BIND_TIMEOUT_MS = 15_000L;
    private final AtomicReference<CustomAlertDialog> installingDialogRef = new AtomicReference<>();

    public static String getInstallMethod(Context context) {
        return prefs(context).getString("install_method", METHOD_SYSTEM);
    }

    public static void setInstallMethod(Context context, String method) {
        prefs(context).edit().putString("install_method", method).apply();
    }

    public static int getInstallMethodLabel(Context context) {
        String method = getInstallMethod(context);
        if (METHOD_SHIZUKU.equals(method)) return R.string.install_method_shizuku;
        if (METHOD_ROOT.equals(method)) return R.string.install_method_root;
        return R.string.install_method_system;
    }

    /**
     * Called right after the user picks Shizuku so the permission prompt shows
     * immediately, instead of silently deferring to install time. No listener is
     * registered here — we only want the system dialog; the install path re-checks
     * grant state later.
     */
    public static void ensureShizukuPermission() {
        if (!Shizuku.pingBinder()) return;
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE);
    }

    private void installApk(File apkFile) {
        String method = getInstallMethod(activity);
        if (METHOD_SHIZUKU.equals(method)) {
            shizukuInstallApk(apkFile);
        } else if (METHOD_ROOT.equals(method)) {
            rootInstallApk(apkFile);
        } else {
            systemInstallApk(apkFile);
        }
    }

    private void shizukuInstallApk(File apkFile) {
        if (!Shizuku.pingBinder()) {
            showShizukuHelpDialog(R.string.shizuku_not_running);
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.OnRequestPermissionResultListener listener =
                    new Shizuku.OnRequestPermissionResultListener() {
                        @Override
                        public void onRequestPermissionResult(int requestCode, int grantResult) {
                            Shizuku.removeRequestPermissionResultListener(this);
                            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                shizukuBindAndInstall(apkFile);
                            } else {
                                showShizukuHelpDialog(R.string.shizuku_failed);
                            }
                        }
                    };
            Shizuku.addRequestPermissionResultListener(listener);
            Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION);
            return;
        }
        shizukuBindAndInstall(apkFile);
    }

    private void shizukuBindAndInstall(File apkFile) {
        showInstallingDialog();
        ComponentName componentName = new ComponentName(
                activity.getPackageName(), UpdateInstallerService.class.getName());
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(componentName)
                .daemon(false)
                .version(1)
                .tag("update-installer");
        // Guards against a connect that arrives after the bind timeout fired.
        AtomicBoolean connected = new AtomicBoolean(false);
        Handler mainHandler = new Handler(Looper.getMainLooper());
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!connected.compareAndSet(false, true)) {
                    try {
                        Shizuku.unbindUserService(args, this, true);
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                mainHandler.removeCallbacksAndMessages(null);
                IUpdateInstallerService service = IUpdateInstallerService.Stub.asInterface(binder);
                new Thread(() -> {
                    int exit = -1;
                    String output = "";
                    try {
                        exit = service.install(apkFile.getAbsolutePath());
                        output = service.getOutput();
                    } catch (Throwable t) {
                        output = String.valueOf(t.getMessage());
                    } finally {
                        try {
                            Shizuku.unbindUserService(args, this, true);
                        } catch (Throwable ignored) {
                        }
                    }
                    Log.e("GithubReleaseUpdater", "shizuku install exit=" + exit + " out=" + output);
                    int code = exit;
                    activity.runOnUiThread(() -> {
                        if (code == 0) {
                            Toast.makeText(activity, R.string.update_installed_restart, Toast.LENGTH_LONG).show();
                        } else {
                            showShizukuHelpDialog(R.string.shizuku_failed);
                        }
                    });
                }).start();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        Shizuku.bindUserService(args, connection);
        mainHandler.postDelayed(() -> {
            if (connected.compareAndSet(false, true)) {
                try {
                    Shizuku.unbindUserService(args, connection, true);
                } catch (Throwable ignored) {
                }
                dismissInstallingDialog();
                showShizukuHelpDialog(R.string.shizuku_not_running);
            }
        }, SHIZUKU_BIND_TIMEOUT_MS);
    }

    private void rootInstallApk(File apkFile) {
        showInstallingDialog();
        new Thread(() -> {
            int exit = -1;
            String output = "";
            try {
                Process process = Runtime.getRuntime().exec(new String[]{
                        "su", "-c", "pm install -r '" + apkFile.getAbsolutePath() + "' 2>&1"});
                output = UpdateInstallerService.readAll(process);
                exit = process.waitFor();
            } catch (Exception e) {
                output = String.valueOf(e.getMessage());
            }
            Log.e("GithubReleaseUpdater", "root install exit=" + exit + " out=" + output);
            int code = exit;
            activity.runOnUiThread(() -> {
                if (code == 0) {
                    Toast.makeText(activity, R.string.update_installed_restart, Toast.LENGTH_LONG).show();
                } else {
                    showRootFailedDialog();
                }
            });
        }).start();
    }

    private void showInstallingDialog() {
        activity.runOnUiThread(() -> {
            CustomAlertDialog dialog = new CustomAlertDialog(activity)
                    .setMessage(activity.getString(R.string.update_installing_message))
                    .setPositiveButton(activity.getString(R.string.close), null);
            installingDialogRef.set(dialog);
            dialog.show();
        });
    }

    private void dismissInstallingDialog() {
        activity.runOnUiThread(() -> {
            CustomAlertDialog dialog = installingDialogRef.getAndSet(null);
            if (dialog != null && dialog.isShowing()) {
                dialog.dismissImmediately();
            }
        });
    }

    private void showShizukuHelpDialog(int messageRes) {
        activity.runOnUiThread(() -> new CustomAlertDialog(activity)
                .setTitleText(activity.getString(R.string.shizuku_failed_title))
                .setMessage(activity.getString(messageRes))
                .setPositiveButton(activity.getString(R.string.enable_usb_install),
                        v -> activity.startActivity(
                                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)))
                .setNegativeButton(activity.getString(R.string.cancel), null)
                .show());
    }

    private void showRootFailedDialog() {
        activity.runOnUiThread(() -> new CustomAlertDialog(activity)
                .setTitleText(activity.getString(R.string.root_failed_title))
                .setMessage(activity.getString(R.string.root_failed_message))
                .setPositiveButton(activity.getString(R.string.close), null)
                .show());
    }

    private void systemInstallApk(File apkFile) {
        PermissionsHandler handler = PermissionsHandler.getInstance();
        handler.setActivity(activity, permissionResultLauncher);
        handler.requestPermission(PermissionsHandler.PermissionType.UNKNOWN_SOURCES,
                new PermissionsHandler.PermissionResultCallback() {
                    @Override
                    public void onPermissionGranted(PermissionsHandler.PermissionType type) {
                        activity.runOnUiThread(() -> {
                            try {
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                Uri apkUri;
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    apkUri = FileProvider.getUriForFile(
                                            activity,
                                            activity.getPackageName() + ".fileprovider",
                                            apkFile
                                    );
                                    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                                } else {
                                    apkUri = Uri.fromFile(apkFile);
                                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                }
                                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                                activity.startActivity(intent);
                            } catch (Exception e) {
                                Toast.makeText(activity, activity.getString(R.string.install_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                            }
                        });
                    }

                    @Override
                    public void onPermissionDenied(PermissionsHandler.PermissionType type, boolean permanentlyDenied) {
                        activity.runOnUiThread(() ->
                                Toast.makeText(activity, activity.getString(R.string.unknown_sources_permission_message), Toast.LENGTH_LONG).show()
                        );
                    }
                }
        );
    }
}