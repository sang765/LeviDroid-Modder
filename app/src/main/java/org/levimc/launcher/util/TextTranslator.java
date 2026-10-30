package org.levimc.launcher.util;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class TextTranslator {
    private static final String TAG = "TextTranslator";
    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static final String ENDPOINT =
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t&q=";

    public interface Callback {
        void onDone(List<String> translated);

        void onError();
    }

    private TextTranslator() {
    }

    public static void translate(List<String> texts, Callback callback) {
        String target = Locale.getDefault().getLanguage();
        Handler main = new Handler(Looper.getMainLooper());
        List<String> urls = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null || text.trim().isEmpty()) {
                urls.add(null);
                continue;
            }
            try {
                urls.add(ENDPOINT + URLEncoder.encode(text, "UTF-8") + "&tl=" + target);
            } catch (UnsupportedEncodingException e) {
                main.post(callback::onError);
                return;
            }
        }

        List<String> results = new ArrayList<>(urls.size());
        for (int i = 0; i < urls.size(); i++) results.add("");
        AtomicInteger pending = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicBoolean notified = new AtomicBoolean(false);
        int urlCount = 0;
        for (String url : urls) if (url != null) urlCount++;
        final int total = urlCount;

        if (total == 0) {
            main.post(() -> callback.onDone(results));
            return;
        }
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            if (url == null) continue;
            int index = i;
            Request request = new Request.Builder().url(url).build();
            CLIENT.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.w(TAG, "Translate request failed: " + e.getMessage());
                    finish(main, callback, results, pending, failed, total, notified, index, null);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String translated = null;
                    try {
                        JSONArray root = new JSONArray(response.body().string());
                        JSONArray segments = root.getJSONArray(0);
                        StringBuilder builder = new StringBuilder();
                        for (int s = 0; s < segments.length(); s++) {
                            builder.append(segments.getJSONArray(s).getString(0));
                        }
                        translated = builder.toString();
                    } catch (Exception e) {
                        Log.w(TAG, "Translate parse failed", e);
                    }
                    finish(main, callback, results, pending, failed, total, notified, index, translated);
                }
            });
        }
    }

    private static void finish(Handler main, Callback callback, List<String> results,
                               AtomicInteger pending, AtomicInteger failed, int total,
                               AtomicBoolean notified, int index, String translated) {
        if (translated == null) failed.incrementAndGet();
        else results.set(index, translated);
        if (pending.incrementAndGet() < total) return;
        if (!notified.compareAndSet(false, true)) return;
        boolean hasFailure = failed.get() > 0;
        main.post(() -> {
            if (hasFailure) callback.onError();
            else callback.onDone(results);
        });
    }
}
