package org.levimc.launcher.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

public final class UpdateApkInstaller {
    private static final String TAG = "UpdateApkInstaller";
    private static final int REQ_SHIZUKU_PERMISSION = 1001;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onSuccess();

        void onFailure(int messageRes);
    }

    private UpdateApkInstaller() {
    }

    public static void installWithRoot(File apkFile, Callback callback) {
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
            Log.e(TAG, "root install exit=" + exit + " out=" + output);
            boolean success = exit == 0;
            MAIN.post(() -> {
                if (success) callback.onSuccess();
                else callback.onFailure(R.string.root_failed_message);
            });
        }).start();
    }

    public static void installWithShizuku(Context context, File apkFile, Callback callback) {
        if (!Shizuku.pingBinder()) {
            MAIN.post(() -> callback.onFailure(R.string.shizuku_not_running));
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.OnRequestPermissionResultListener listener =
                    new Shizuku.OnRequestPermissionResultListener() {
                        @Override
                        public void onRequestPermissionResult(int requestCode, int grantResult) {
                            Shizuku.removeRequestPermissionResultListener(this);
                            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                bindAndInstall(context, apkFile, callback);
                            } else {
                                MAIN.post(() -> callback.onFailure(R.string.shizuku_failed));
                            }
                        }
                    };
            Shizuku.addRequestPermissionResultListener(listener);
            Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION);
            return;
        }
        bindAndInstall(context, apkFile, callback);
    }

    private static void bindAndInstall(Context context, File apkFile, Callback callback) {
        ComponentName componentName = new ComponentName(
                context.getPackageName(), UpdateInstallerService.class.getName());
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(componentName)
                .daemon(false)
                .version(1)
                .tag("update-installer")
                .processNameSuffix("update_installer");
        AtomicBoolean settled = new AtomicBoolean(false);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!settled.compareAndSet(false, true)) {
                    try {
                        Shizuku.unbindUserService(args, this, true);
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                IUpdateInstallerService service = IUpdateInstallerService.Stub.asInterface(binder);
                new Thread(() -> {
                    int exit = -1;
                    String output = "";
                    try (ParcelFileDescriptor apk = ParcelFileDescriptor.open(
                            apkFile, ParcelFileDescriptor.MODE_READ_ONLY)) {
                        exit = service.install(apk, apkFile.length());
                        output = service.getOutput();
                    } catch (Throwable t) {
                        output = String.valueOf(t.getMessage());
                    } finally {
                        try {
                            Shizuku.unbindUserService(args, this, true);
                        } catch (Throwable ignored) {
                        }
                    }
                    Log.e(TAG, "shizuku install exit=" + exit + " out=" + output);
                    boolean success = exit == 0;
                    MAIN.post(() -> {
                        if (success) callback.onSuccess();
                        else callback.onFailure(R.string.shizuku_failed);
                    });
                }).start();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        Shizuku.bindUserService(args, connection);
        Runnable timeout = () -> {
            if (settled.compareAndSet(false, true)) {
                try {
                    Shizuku.unbindUserService(args, connection, true);
                } catch (Throwable ignored) {
                }
                callback.onFailure(R.string.shizuku_not_running);
            }
        };
        MAIN.postDelayed(timeout, 15_000L);
    }
}
