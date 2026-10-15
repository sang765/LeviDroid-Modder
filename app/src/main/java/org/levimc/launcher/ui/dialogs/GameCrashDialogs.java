package org.levimc.launcher.ui.dialogs;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.levimc.launcher.R;
import org.levimc.launcher.core.crash.PendingGameCrash;
import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.core.mods.ModCrashAttributor;
import org.levimc.launcher.core.mods.ModManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher-styled dialogs shown after a game session crashed: a generic
 * "Uh oh!" with the crash log, then a mod-impact dialog when the log
 * mentions installed mods.
 */
public class GameCrashDialogs {

    // Keep the crash header and the last logcat lines without flooding the
    // dialog with the whole file.
    private static final int MAX_HEAD = 4000;
    private static final int MAX_TAIL = 8000;

    private final Activity activity;
    private final Runnable relaunchGame;

    public GameCrashDialogs(Activity activity, Runnable relaunchGame) {
        this.activity = activity;
        this.relaunchGame = relaunchGame;
    }

    public void showIfPending() {
        String logPath = PendingGameCrash.consumeLogPath(activity);
        if (logPath == null) return;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            final String logText = readLogTail(logPath);
            executor.shutdown();
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                showCrashDialog(logText, logPath);
            });
        });
    }

    private void showCrashDialog(String logText, String logPath) {
        CustomAlertDialog dialog = new CustomAlertDialog(activity)
                .setTitleText(activity.getString(R.string.game_crash_title));
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_game_crash_content, null, false);
        TextView logView = content.findViewById(R.id.game_crash_log);
        logView.setText(logText != null ? logText : activity.getString(R.string.game_crash_log_unavailable));
        dialog.setCustomView(content);
        dialog.setPositiveButton(activity.getString(R.string.game_crash_open_log), v -> openCrashLog(logPath));
        dialog.setNeutralButton(activity.getString(R.string.game_crash_share_log), v -> shareCrashLog(logPath));
        dialog.setNegativeButton(activity.getString(R.string.game_crash_close), v -> { });
        dialog.setOnDismissAnimationEndListener(() -> analyzeModsForImpact(logText));
        dialog.show();
    }

    private void analyzeModsForImpact(final String logText) {
        if (logText == null || logText.isEmpty()) return;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            List<Mod> mods = ModManager.getInstance().getMods();
            final List<Mod> flagged = ModCrashAttributor.attribute(logText, mods);
            executor.shutdown();
            activity.runOnUiThread(() -> {
                if (flagged.isEmpty() || activity.isFinishing() || activity.isDestroyed()) return;
                showModImpactDialog(flagged);
            });
        });
    }

    private void showModImpactDialog(List<Mod> flagged) {
        CustomAlertDialog dialog = new CustomAlertDialog(activity)
                .setTitleText(activity.getString(R.string.mod_impact_title));
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_mod_impact_content, null, false);
        TextView modList = content.findViewById(R.id.mod_impact_mod_list);
        StringBuilder builder = new StringBuilder();
        for (Mod mod : flagged) {
            if (builder.length() > 0) builder.append('\n');
            builder.append("- ").append(mod.getDisplayName());
            if (mod.getVersion() != null && !mod.getVersion().isEmpty()) {
                builder.append(' ').append(mod.getVersion());
            }
        }
        modList.setText(builder);
        dialog.setCustomView(content);
        dialog.setPositiveButton(activity.getString(R.string.mod_impact_disable_flagged), v -> disableMods(flagged));
        dialog.setNeutralButton(activity.getString(R.string.mod_impact_relaunch), v -> relaunchGame.run());
        dialog.setNegativeButton(activity.getString(R.string.mod_impact_check_later), v -> { });
        dialog.show();
    }

    private void disableMods(List<Mod> flagged) {
        for (Mod mod : flagged) {
            ModManager.getInstance().setModEnabled(mod.getId(), false);
        }
        Toast.makeText(activity, R.string.mod_impact_disabled_toast, Toast.LENGTH_SHORT).show();
    }

    private void openCrashLog(String logPath) {
        File shared = copyToShareCache(logPath);
        if (shared == null) return;
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", shared);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "text/plain")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(Intent.createChooser(intent, null));
        } catch (Throwable ignored) {
        }
    }

    private void shareCrashLog(String logPath) {
        File shared = copyToShareCache(logPath);
        if (shared == null) return;
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", shared);
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(Intent.createChooser(intent, null));
        } catch (Throwable ignored) {
        }
    }

    private File copyToShareCache(String logPath) {
        try {
            File source = new File(logPath);
            if (!source.isFile()) return null;
            File dir = new File(activity.getCacheDir(), "crash_logs_share");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File target = new File(dir, source.getName());
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            return target;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readLogTail(String logPath) {
        try {
            File file = new File(logPath);
            if (!file.isFile()) return null;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (text.length() <= MAX_HEAD + MAX_TAIL) return text;
            return text.substring(0, MAX_HEAD) + "\n…\n" + text.substring(text.length() - MAX_TAIL);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
