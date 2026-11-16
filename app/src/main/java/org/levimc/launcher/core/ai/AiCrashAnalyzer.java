package org.levimc.launcher.core.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.core.mods.ModManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class AiCrashAnalyzer {

    public static final String FORMAT_OPENAI = AiProviderCatalog.FORMAT_OPENAI;
    public static final String FORMAT_ANTHROPIC = AiProviderCatalog.FORMAT_ANTHROPIC;
    public static final String FORMAT_GEMINI = AiProviderCatalog.FORMAT_GEMINI;

    private static final String PREFS_NAME = "ai_analysis_prefs";
    private static final String KEY_PROVIDER = "provider";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";
    private static final String KEY_FORMAT = "format";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final String PROMPT_TEMPLATE =
            "LeviLauncher Modder is an open-source launcher for Minecraft Bedrock Edition on Android, "
                    + "a fork of LeviLaunchroid (LiteLDev). The game runs in the same process as the launcher; "
                    + "the launcher supports installing mods (.levipack / mod APKs); crashes are captured by "
                    + "xCrash and by the engine's own dump files.\n"
                    + "Launcher source code: https://github.com/sang765/LeviDroid-Modder\n"
                    + "Enabled mods:\n{mods}\n"
                    + "Analyze the crash log below: the most likely cause, the specific culprit "
                    + "(mod/library if any), and suggested fixes. Be concise and structured.\n"
                    + "Respond in the language the app is currently using.\n"
                    + "Language: {lang}\n"
                    + "Crash log:\n{log}";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onDone(String response);

        void onError(String message);
    }

    public interface ModelsCallback {
        void onDone(List<String> models);

        void onError(String message);
    }

    private AiCrashAnalyzer() {
    }

    public static boolean isConfigured(Context context) {
        SharedPreferences prefs = prefs(context);
        return !prefs.getString(KEY_BASE_URL, "").trim().isEmpty()
                && !prefs.getString(KEY_MODEL, "").trim().isEmpty();
    }

    public static String getProvider(Context context) {
        return prefs(context).getString(KEY_PROVIDER, "").trim();
    }

    public static String getBaseUrl(Context context) {
        return prefs(context).getString(KEY_BASE_URL, "").trim();
    }

    public static String getApiKey(Context context) {
        return prefs(context).getString(KEY_API_KEY, "").trim();
    }

    public static String getModel(Context context) {
        return prefs(context).getString(KEY_MODEL, "").trim();
    }

    public static String getFormat(Context context) {
        return prefs(context).getString(KEY_FORMAT, FORMAT_OPENAI);
    }

    public static void setConfig(Context context, String provider, String baseUrl,
                                 String apiKey, String model, String format) {
        prefs(context).edit()
                .putString(KEY_PROVIDER, provider.trim())
                .putString(KEY_BASE_URL, baseUrl.trim())
                .putString(KEY_API_KEY, apiKey.trim())
                .putString(KEY_MODEL, model.trim())
                .putString(KEY_FORMAT, format)
                .apply();
    }

    public static void analyze(Context context, String crashLog, Callback callback) {
        String baseUrl = getBaseUrl(context);
        String model = getModel(context);
        if (baseUrl.isEmpty() || model.isEmpty()) {
            MAIN.post(() -> callback.onError(context.getString(org.levimc.launcher.R.string.ai_not_configured)));
            return;
        }
        String prompt = PROMPT_TEMPLATE
                .replace("{lang}", Locale.getDefault().getDisplayLanguage(Locale.getDefault()))
                .replace("{mods}", enabledModsSummary())
                .replace("{log}", crashLog == null ? "" : crashLog);
        String format = getFormat(context);
        if (FORMAT_ANTHROPIC.equals(format)) {
            callAnthropic(context, baseUrl, model, prompt, callback);
        } else if (FORMAT_GEMINI.equals(format)) {
            callGemini(context, baseUrl, model, prompt, callback);
        } else {
            callOpenAiCompatible(context, baseUrl, model, prompt, callback);
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl.trim().replaceAll("/+$", "");
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "https://" + trimmed;
        }
        return trimmed;
    }

    private static void callOpenAiCompatible(Context context, String baseUrl, String model,
                                             String prompt, Callback callback) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            JSONArray messages = new JSONArray();
            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", prompt);
            messages.put(message);
            body.put("messages", messages);

            Request.Builder builder = new Request.Builder()
                    .url(normalizeBaseUrl(baseUrl) + "/chat/completions")
                    .post(RequestBody.create(body.toString(), JSON));
            String apiKey = getApiKey(context);
            if (!apiKey.isEmpty()) builder.header("Authorization", "Bearer " + apiKey);

            CLIENT.newCall(builder.build()).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    reportFailure(callback, e);
                }

                @Override
                public void onResponse(okhttp3.Call call, Response response) {
                    String text = parseOpenAiResponse(response);
                    finishResponse(callback, response, text);
                }
            });
        } catch (Exception e) {
            reportFailure(callback, e);
        }
    }

    private static void callAnthropic(Context context, String baseUrl, String model,
                                      String prompt, Callback callback) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 2048);
            JSONArray messages = new JSONArray();
            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", prompt);
            messages.put(message);
            body.put("messages", messages);

            Request.Builder builder = new Request.Builder()
                    .url(normalizeBaseUrl(baseUrl) + "/messages")
                    .header("x-api-key", getApiKey(context))
                    .header("anthropic-version", "2023-06-01")
                    .post(RequestBody.create(body.toString(), JSON));

            CLIENT.newCall(builder.build()).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    reportFailure(callback, e);
                }

                @Override
                public void onResponse(okhttp3.Call call, Response response) {
                    String text = parseAnthropicResponse(response);
                    finishResponse(callback, response, text);
                }
            });
        } catch (Exception e) {
            reportFailure(callback, e);
        }
    }

    private static void callGemini(Context context, String baseUrl, String model,
                                   String prompt, Callback callback) {
        try {
            JSONObject body = new JSONObject();
            JSONArray contents = new JSONArray();
            JSONObject content = new JSONObject();
            JSONArray parts = new JSONArray();
            JSONObject part = new JSONObject();
            part.put("text", prompt);
            parts.put(part);
            content.put("role", "user");
            content.put("parts", parts);
            contents.put(content);
            body.put("contents", contents);

            String modelPath = model.startsWith("models/") ? model : "models/" + model;
            Request.Builder builder = new Request.Builder()
                    .url(normalizeBaseUrl(baseUrl) + "/" + modelPath + ":generateContent")
                    .header("x-goog-api-key", getApiKey(context))
                    .post(RequestBody.create(body.toString(), JSON));

            CLIENT.newCall(builder.build()).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    reportFailure(callback, e);
                }

                @Override
                public void onResponse(okhttp3.Call call, Response response) {
                    String text = parseGeminiResponse(response);
                    finishResponse(callback, response, text);
                }
            });
        } catch (Exception e) {
            reportFailure(callback, e);
        }
    }

    private static String parseOpenAiResponse(Response response) {
        try {
            if (!response.isSuccessful() || response.body() == null) return null;
            JSONObject json = new JSONObject(response.body().string());
            JSONArray choices = json.getJSONArray("choices");
            if (choices.length() == 0) return null;
            return choices.getJSONObject(0).getJSONObject("message").optString("content", null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String parseAnthropicResponse(Response response) {
        try {
            if (!response.isSuccessful() || response.body() == null) return null;
            JSONObject json = new JSONObject(response.body().string());
            JSONArray content = json.getJSONArray("content");
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if ("text".equals(block.optString("type"))) builder.append(block.optString("text", ""));
            }
            return builder.length() == 0 ? null : builder.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String parseGeminiResponse(Response response) {
        try {
            if (!response.isSuccessful() || response.body() == null) return null;
            JSONObject json = new JSONObject(response.body().string());
            JSONArray candidates = json.getJSONArray("candidates");
            if (candidates.length() == 0) return null;
            JSONArray parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                builder.append(parts.getJSONObject(i).optString("text", ""));
            }
            return builder.length() == 0 ? null : builder.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void finishResponse(Callback callback, Response response, String text) {
        String error = text == null ? "HTTP " + response.code() : null;
        response.close();
        if (error != null) {
            MAIN.post(() -> callback.onError(error));
        } else {
            MAIN.post(() -> callback.onDone(text));
        }
    }

    private static void reportFailure(Callback callback, Exception e) {
        MAIN.post(() -> callback.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }

    public static void fetchModels(Context context, String baseUrl, String apiKey,
                                   String format, ModelsCallback callback) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            MAIN.post(() -> callback.onError(context.getString(org.levimc.launcher.R.string.ai_not_configured)));
            return;
        }
        baseUrl = baseUrl.trim();
        boolean gemini = FORMAT_GEMINI.equals(format);
        boolean anthropic = FORMAT_ANTHROPIC.equals(format);
        try {
            Request.Builder builder = new Request.Builder()
                    .url(normalizeBaseUrl(baseUrl) + "/models")
                    .get();
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                if (gemini) {
                    builder.header("x-goog-api-key", apiKey.trim());
                } else if (anthropic) {
                    builder.header("x-api-key", apiKey.trim())
                            .header("anthropic-version", "2023-06-01");
                } else {
                    builder.header("Authorization", "Bearer " + apiKey.trim());
                }
            }
            CLIENT.newCall(builder.build()).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    MAIN.post(() -> callback.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                }

                @Override
                public void onResponse(okhttp3.Call call, Response response) {
                    List<String> models = extractModelIds(response, gemini);
                    String error = models == null ? "HTTP " + response.code() : null;
                    response.close();
                    if (error != null) {
                        MAIN.post(() -> callback.onError(error));
                    } else {
                        MAIN.post(() -> callback.onDone(models));
                    }
                }
            });
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            MAIN.post(() -> callback.onError(message));
        }
    }

    private static List<String> extractModelIds(Response response, boolean gemini) {
        try {
            if (!response.isSuccessful() || response.body() == null) return null;
            JSONObject json = new JSONObject(response.body().string());
            JSONArray data = gemini ? json.getJSONArray("models") : json.getJSONArray("data");
            List<String> models = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject entry = data.getJSONObject(i);
                String id = gemini ? entry.optString("name") : entry.optString("id");
                if (id == null || id.isEmpty()) continue;
                if (gemini) {
                    id = id.replaceFirst("^models/", "");
                }
                models.add(id);
            }
            Collections.sort(models);
            return models;
        } catch (Exception e) {
            return null;
        }
    }

    private static String enabledModsSummary() {
        try {
            StringBuilder builder = new StringBuilder();
            for (Mod mod : ModManager.getInstance().getMods()) {
                if (mod == null || !mod.isEnabled()) continue;
                builder.append("- ").append(mod.getDisplayName());
                if (mod.getVersion() != null && !mod.getVersion().isEmpty()) {
                    builder.append(' ').append(mod.getVersion());
                }
                builder.append('\n');
            }
            if (builder.length() == 0) return "None";
            return builder.toString().trim();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
