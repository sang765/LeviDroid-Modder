package org.levimc.launcher.core.mods;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.levimc.launcher.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Reads GitHub release metadata for mods distributed through GitHub releases.
 */
public final class GitHubModRepository {

    public interface ReleaseCallback {
        void onRelease(GitHubRelease release);
        void onError(String message);
    }

    public static final class GitHubRelease {
        public String tag_name;
        public String name;
        public boolean draft;
        public boolean prerelease;
        public List<GitHubAsset> assets = new ArrayList<>();

        public List<GitHubAsset> supportedAssets() {
            List<GitHubAsset> result = new ArrayList<>();
            if (assets != null) {
                for (GitHubAsset asset : assets) {
                    if (asset != null && asset.isSupported()) {
                        result.add(asset);
                    }
                }
            }
            return result;
        }
    }

    public static final class GitHubAsset {
        public String name;
        public String browser_download_url;

        public boolean isSupported() {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".so") || lower.endsWith(".levipack") || lower.endsWith(".zip");
        }
    }

    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/%s/releases/latest";
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final Gson GSON = new Gson();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, CachedRelease> CACHE = new HashMap<>();

    private static final class CachedRelease {
        final GitHubRelease release;
        final long fetchedAt;

        CachedRelease(GitHubRelease release, long fetchedAt) {
            this.release = release;
            this.fetchedAt = fetchedAt;
        }
    }

    private GitHubModRepository() {
    }

    /**
     * Normalizes the supported input formats to "owner/repo":
     * a repository URL, "owner/repo", or "owner repo" (space separated).
     */
    public static String parseRepoRef(String input) {
        if (input == null) return null;
        String value = input.trim();
        if (value.isEmpty()) return null;
        value = value.replaceAll("(?i)^https?://", "")
                .replaceAll("(?i)^(www\\.)?github\\.com/", "");
        int fragment = value.indexOf('#');
        if (fragment >= 0) value = value.substring(0, fragment);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        value = value.replaceAll("/+$", "");
        if (value.contains(" ")) {
            String[] tokens = value.split("\\s+");
            if (tokens.length < 2) return null;
            value = tokens[0] + "/" + tokens[1];
        }
        if (value.toLowerCase(Locale.ROOT).endsWith(".git")) {
            value = value.substring(0, value.length() - 4);
        }
        String[] segments = value.split("/");
        if (segments.length < 2) return null;
        for (int i = 0; i < 2; i++) {
            String segment = segments[i];
            if (segment.isEmpty() || !segment.matches("[A-Za-z0-9._-]+")) return null;
        }
        return segments[0] + "/" + segments[1];
    }

    /**
     * Fetches the latest published release. Results are cached for a few minutes
     * so the per-screen update check does not burn the unauthenticated API quota.
     */
    public static void fetchLatestRelease(Context context, String ownerRepo, ReleaseCallback callback) {
        EXECUTOR.execute(() -> {
            CachedRelease cached = CACHE.get(ownerRepo);
            if (cached != null && System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MS) {
                deliverRelease(callback, cached.release);
                return;
            }
            String url = String.format(Locale.ROOT, LATEST_RELEASE_API, ownerRepo);
            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .build();
            try (Response response = HTTP.newCall(request).execute()) {
                if (response.code() == 404) {
                    deliverError(callback, context.getString(R.string.github_import_no_releases));
                    return;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    deliverError(callback, context.getString(R.string.github_import_rate_limited));
                    return;
                }
                GitHubRelease release = GSON.fromJson(response.body().string(), GitHubRelease.class);
                if (release == null || release.tag_name == null) {
                    deliverError(callback, context.getString(R.string.github_import_failed));
                    return;
                }
                CACHE.put(ownerRepo, new CachedRelease(release, System.currentTimeMillis()));
                deliverRelease(callback, release);
            } catch (IOException | RuntimeException error) {
                deliverError(callback, context.getString(R.string.github_import_failed));
            }
        });
    }

    private static void deliverRelease(ReleaseCallback callback, GitHubRelease release) {
        MAIN.post(() -> callback.onRelease(release));
    }

    private static void deliverError(ReleaseCallback callback, String message) {
        MAIN.post(() -> callback.onError(message));
    }
}
