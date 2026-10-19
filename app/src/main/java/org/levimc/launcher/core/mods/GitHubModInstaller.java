package org.levimc.launcher.core.mods;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.GitHubModRepository.GitHubAsset;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Downloads a GitHub release asset and feeds it into the regular mod import
 * pipeline, tagging the installed manifest with the GitHub source so the mod
 * list can check for updates later.
 */
public final class GitHubModInstaller {

    public interface Callback {
        /** Compatibility guard before the first install; call proceed() to continue. */
        void onWarning(String message, Runnable proceed);
        void onSuccess();
        void onError(String message);
    }

    private static final String MINECRAFT_VERSIONS_FIELD = "minecraft_versions";
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final Gson GSON = new Gson();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final Context context;
    private final FileHandler fileHandler;

    public GitHubModInstaller(Context context, FileHandler fileHandler) {
        this.context = context;
        this.fileHandler = fileHandler;
    }

    /**
     * @param name explicit display name; when null it is derived from the asset file name.
     *             Updates must pass the installed display name so the mod keeps its directory.
     * @param currentVersionCode selected Minecraft version used for the compatibility warning.
     */
    public void install(String ownerRepo, GitHubAsset asset, String versionTag, String name,
                        String currentVersionCode, boolean forceOverwrite, Callback callback) {
        EXECUTOR.execute(() -> {
            File downloadedFile = null;
            try {
                File downloadDir = new File(context.getCacheDir(), "github_mod_downloads");
                if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                    throw new IllegalStateException("Could not prepare download folder");
                }
                downloadedFile = new File(downloadDir,
                        safeName(ownerRepo) + "-" + safeName(versionTag) + "-" + safeName(asset.name));
                Request request = new Request.Builder().url(asset.browser_download_url).build();
                try (Response response = HTTP.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IllegalStateException(context.getString(R.string.github_import_download_failed)
                                + " (HTTP " + response.code() + ")");
                    }
                    try (InputStream input = response.body().byteStream();
                         FileOutputStream output = new FileOutputStream(downloadedFile)) {
                        byte[] buffer = new byte[16384];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                        }
                        output.getFD().sync();
                    }
                }

                File file = downloadedFile;
                List<String> minecraftVersions = peekMinecraftVersions(file);
                boolean incompatible = !forceOverwrite
                        && currentVersionCode != null
                        && !minecraftVersions.isEmpty()
                        && !ModNativeLoader.isCompatibleWithMinecraftVersion(minecraftVersions, currentVersionCode);
                if (incompatible) {
                    String warning = context.getString(R.string.github_import_compat_warning,
                            String.join(", ", minecraftVersions), currentVersionCode);
                    context.getMainExecutor().execute(() -> callback.onWarning(warning,
                            () -> context.getMainExecutor().execute(
                                    () -> importFile(file, ownerRepo, asset, versionTag, name, forceOverwrite, callback))));
                    return;
                }
                context.getMainExecutor().execute(
                        () -> importFile(file, ownerRepo, asset, versionTag, name, forceOverwrite, callback));
            } catch (Exception error) {
                if (downloadedFile != null) downloadedFile.delete();
                deliverError(callback, message(error));
            }
        });
    }

    private void importFile(File file, String ownerRepo, GitHubAsset asset, String versionTag,
                            String name, boolean forceOverwrite, Callback callback) {
        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String displayName = (name != null && !name.isEmpty())
                    ? name
                    : displayNameFor(asset.name);
            String author = ownerRepo.substring(0, ownerRepo.indexOf('/'));
            fileHandler.processCatalogModDirectly(intent, displayName, author, versionTag,
                    peekMinecraftVersions(file), ownerRepo, asset.name, forceOverwrite,
                    new FileHandler.FileOperationCallback() {
                        @Override
                        public void onSuccess(int processedFiles) {
                            file.delete();
                            callback.onSuccess();
                        }

                        @Override
                        public void onError(String errorMessage) {
                            file.delete();
                            callback.onError(errorMessage);
                        }

                        @Override
                        public void onProgressUpdate(int progress) {
                        }
                    });
        } catch (Exception error) {
            file.delete();
            deliverError(callback, message(error));
        }
    }

    /** Reads "minecraft_versions" from a packaged mod zip manifest; empty for bare .so files. */
    private static List<String> peekMinecraftVersions(File file) {
        List<String> versions = new ArrayList<>();
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".zip") && !lower.endsWith(".levipack")) {
            return versions;
        }
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName();
                boolean manifestEntry = entryName.equals("manifest.json")
                        || entryName.endsWith("/manifest.json");
                if (!entry.isDirectory() && manifestEntry) {
                    JsonObject manifest = GSON.fromJson(
                            new InputStreamReader(zip, StandardCharsets.UTF_8), JsonObject.class);
                    addMinecraftVersions(versions, manifest);
                    break;
                }
            }
        } catch (Exception ignored) {
            // A peek failure is not fatal: the import pipeline validates the package itself.
        }
        return versions;
    }

    private static void addMinecraftVersions(List<String> versions, JsonObject manifest) {
        if (manifest == null || !manifest.has(MINECRAFT_VERSIONS_FIELD)) return;
        JsonElement value = manifest.get(MINECRAFT_VERSIONS_FIELD);
        if (value != null && value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            for (JsonElement element : array) {
                if (element.isJsonPrimitive()) {
                    String version = element.getAsString().trim();
                    if (!version.isEmpty()) versions.add(version);
                }
            }
        } else if (value != null && value.isJsonPrimitive()) {
            String version = value.getAsString().trim();
            if (!version.isEmpty()) versions.add(version);
        }
    }

    private static String displayNameFor(String assetName) {
        String name = assetName == null ? "mod" : assetName;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String safeName(String value) {
        String safe = value == null ? "mod" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "mod" : safe;
    }

    private void deliverError(Callback callback, String message) {
        context.getMainExecutor().execute(() -> callback.onError(message));
    }

    private static String message(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
