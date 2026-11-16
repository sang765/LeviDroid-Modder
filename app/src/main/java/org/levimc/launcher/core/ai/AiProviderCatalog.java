package org.levimc.launcher.core.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class AiProviderCatalog {

    public static final String FORMAT_OPENAI = "openai";
    public static final String FORMAT_ANTHROPIC = "anthropic";
    public static final String FORMAT_GEMINI = "gemini";

    private static final String TAG = "AiProviderCatalog";
    private static final String SOURCE = "https://models.dev/api.json";
    private static final String CACHE_FILE = "ai_providers.json";
    private static final String LOCAL_OLLAMA = "http://localhost:11434/v1";
    private static final String LOCAL_LM_STUDIO = "http://localhost:1234/v1";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public static final class Provider {
        public final String id;
        public final String name;
        public final String baseUrl;
        public final String format;

        Provider(String id, String name, String baseUrl, String format) {
            this.id = id;
            this.name = name;
            this.baseUrl = baseUrl;
            this.format = format;
        }
    }

    public interface Callback {
        void onDone(List<Provider> providers);
    }

    private AiProviderCatalog() {
    }

    public static void load(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        List<Provider> cached = readCache(app);
        if (!cached.isEmpty()) {
            MAIN.post(() -> callback.onDone(cached));
            refresh(app, callback, true);
            return;
        }
        refresh(app, callback, false);
    }

    private static void refresh(Context context, Callback callback, boolean hasCache) {
        new Thread(() -> {
            List<Provider> fetched = fetch();
            if (fetched.isEmpty()) {
                if (!hasCache) {
                    List<Provider> fallback = builtIn();
                    MAIN.post(() -> callback.onDone(fallback));
                }
                return;
            }
            writeCache(context, fetched);
            List<Provider> delivered = new ArrayList<>(fetched);
            MAIN.post(() -> callback.onDone(delivered));
        }).start();
    }

    public static List<String> names(List<Provider> providers) {
        List<String> names = new ArrayList<>(providers.size());
        for (Provider provider : providers) names.add(provider.name);
        return names;
    }

    public static Provider byName(List<Provider> providers, String name) {
        for (Provider provider : providers) if (provider.name.equals(name)) return provider;
        return null;
    }

    private static List<Provider> fetch() {
        List<Provider> providers = new ArrayList<>();
        try {
            okhttp3.Request request = new Request.Builder().url(SOURCE).build();
            try (Response response = CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return providers;
                parse(new JSONObject(response.body().string()), providers);
            }
        } catch (Exception error) {
            Log.w(TAG, "Failed to fetch the provider list", error);
        }
        return providers;
    }

    private static void parse(JSONObject root, List<Provider> out) {
        List<Provider> collected = new ArrayList<>();
        JSONArray ids = root.names();
        if (ids == null) return;
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i);
            JSONObject entry = root.optJSONObject(id);
            if (entry == null) continue;
            String baseUrl = entry.optString("api").trim();
            if (baseUrl.isEmpty()) baseUrl = knownBaseUrl(id);
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) continue;
            collected.add(new Provider(id, displayName(entry, id), trimSlash(baseUrl), format(entry.optString("npm"))));
        }
        if (collected.isEmpty()) return;
        collected.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        out.add(new Provider("ollama-local", "Ollama (local)", LOCAL_OLLAMA, FORMAT_OPENAI));
        out.add(new Provider("lm-studio-local", "LM Studio (local)", LOCAL_LM_STUDIO, FORMAT_OPENAI));
        out.addAll(collected);
        out.add(new Provider("custom", "Custom", "", FORMAT_OPENAI));
    }

    private static String displayName(JSONObject entry, String fallback) {
        String name = entry.optString("name").replaceAll("\\s+", " ").trim();
        return name.isEmpty() ? fallback : name;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String format(String npm) {
        String value = npm == null ? "" : npm.toLowerCase(Locale.ROOT);
        if (value.contains("anthropic")) return FORMAT_ANTHROPIC;
        if (value.contains("google")) return FORMAT_GEMINI;
        return FORMAT_OPENAI;
    }

    private static String normalizeFormat(String format) {
        if (FORMAT_ANTHROPIC.equals(format)) return FORMAT_ANTHROPIC;
        if (FORMAT_GEMINI.equals(format)) return FORMAT_GEMINI;
        return FORMAT_OPENAI;
    }

    private static String knownBaseUrl(String id) {
        switch (id) {
            case "openai":
                return "https://api.openai.com/v1";
            case "anthropic":
                return "https://api.anthropic.com/v1";
            case "google":
                return "https://generativelanguage.googleapis.com/v1beta";
            case "groq":
                return "https://api.groq.com/openai/v1";
            case "mistral":
                return "https://api.mistral.ai/v1";
            case "xai":
                return "https://api.x.ai/v1";
            case "togetherai":
                return "https://api.together.xyz/v1";
            case "cerebras":
                return "https://api.cerebras.ai/v1";
            case "deepseek":
                return "https://api.deepseek.com/v1";
            default:
                return "";
        }
    }

    private static List<Provider> builtIn() {
        List<Provider> providers = new ArrayList<>();
        providers.add(new Provider("ollama-local", "Ollama (local)", LOCAL_OLLAMA, FORMAT_OPENAI));
        providers.add(new Provider("lm-studio-local", "LM Studio (local)", LOCAL_LM_STUDIO, FORMAT_OPENAI));
        providers.add(new Provider("openai", "OpenAI", "https://api.openai.com/v1", FORMAT_OPENAI));
        providers.add(new Provider("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1", FORMAT_ANTHROPIC));
        providers.add(new Provider("google", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", FORMAT_GEMINI));
        providers.add(new Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", FORMAT_OPENAI));
        providers.add(new Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", FORMAT_OPENAI));
        providers.add(new Provider("custom", "Custom", "", FORMAT_OPENAI));
        return providers;
    }

    private static File cacheFile(Context context) {
        return new File(context.getFilesDir(), CACHE_FILE);
    }

    private static List<Provider> readCache(Context context) {
        List<Provider> providers = new ArrayList<>();
        File file = cacheFile(context);
        if (!file.isFile()) return providers;
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) buffer.write(chunk, 0, read);
            JSONArray array = new JSONArray(buffer.toString("UTF-8"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.getJSONObject(i);
                String id = entry.optString("id");
                String name = entry.optString("name");
                if (id.isEmpty() || name.isEmpty()) continue;
                providers.add(new Provider(
                        id,
                        name,
                        entry.optString("baseUrl"),
                        normalizeFormat(entry.optString("format"))));
            }
        } catch (Exception error) {
            Log.w(TAG, "Failed to read the cached provider list", error);
        }
        return providers;
    }

    private static void writeCache(Context context, List<Provider> providers) {
        try {
            JSONArray array = new JSONArray();
            for (Provider provider : providers) {
                JSONObject entry = new JSONObject();
                entry.put("id", provider.id);
                entry.put("name", provider.name);
                entry.put("baseUrl", provider.baseUrl);
                entry.put("format", provider.format);
                array.put(entry);
            }
            FileOutputStream out = context.openFileOutput(CACHE_FILE, Context.MODE_PRIVATE);
            out.write(array.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }
}
