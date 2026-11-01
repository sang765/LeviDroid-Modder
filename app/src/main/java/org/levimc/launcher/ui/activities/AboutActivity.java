package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.levimc.launcher.R;
import org.levimc.launcher.ui.animation.DynamicAnim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AboutActivity extends BaseActivity {

    private static final String AVATAR_URL = "https://avatars.githubusercontent.com/u/62042544?v=4";
    private static final String URL_AFDIAN = "https://afdian.com/a/DreamGuXiang";
    private static final String URL_PATREON = "https://www.patreon.com/c/DreamGuXiang";
    private static final String URL_REPO = "https://github.com/sang765/LeviDroid-Modder";
    private static final String URL_ORG = "https://github.com/sang765";
    private static final String URL_ISSUES = "https://github.com/sang765/LeviDroid-Modder/issues";
    private static final String MAINTAINER_AVATAR_URL = "https://yt3.googleusercontent.com/ft4khqXZ_fQn-DbSLg91kQy3_JUQ_73rbg18nOcmMtunX5bq25jzrThWQAk9YsFkTKFesUL7sg8=s160-c-k-c0x00ffffff-no-rj";
    private static final String MODDER_AVATAR_URL = "https://avatars.githubusercontent.com/sang765";
    private static final String URL_YOUTUBE = "https://www.youtube.com/c/mrpokeg";
    private static final String URL_CONTRIBUTORS = "https://api.github.com/repos/sang765/LeviDroid-Modder/contributors";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        setupNavBar();

        loadAvatars();
        loadContributors();
        setupLinks();
        styleBadges();

        DynamicAnim.applyPressScaleRecursively(findViewById(android.R.id.content));
    }

    private void styleBadges() {
        TextView authorBadge = findViewById(R.id.author_badge);
        TextView maintainerBadge = findViewById(R.id.maintainer_badge);
        TextView modderBadge = findViewById(R.id.modder_badge);

        org.levimc.launcher.util.PersonalizationManager pm = new org.levimc.launcher.util.PersonalizationManager(this);
        int accent = pm.getAccentColor();
        if (accent != 0) {
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            gd.setColor(accent);
            gd.setCornerRadius(4 * getResources().getDisplayMetrics().density);
            if (authorBadge != null) {
                authorBadge.setBackground(gd);
                authorBadge.setTextColor(android.graphics.Color.WHITE);
            }
            if (maintainerBadge != null) {
                maintainerBadge.setBackground(gd);
                maintainerBadge.setTextColor(android.graphics.Color.WHITE);
            }
            if (modderBadge != null) {
                modderBadge.setBackground(gd);
                modderBadge.setTextColor(android.graphics.Color.WHITE);
            }
        }
    }

    private void setupNavBar() {
        setActiveNavTab(R.id.nav_tab_about);
        findViewById(R.id.nav_tab_about).setOnClickListener(v -> {});
    }

    private void loadAvatars() {
        com.microsoft.xbox.idp.toolkit.CircleImageView avatar = findViewById(R.id.author_avatar);
        com.microsoft.xbox.idp.toolkit.CircleImageView maintainerAvatar = findViewById(R.id.maintainer_avatar);
        com.microsoft.xbox.idp.toolkit.CircleImageView modderAvatar = findViewById(R.id.modder_avatar);

        executor.execute(() -> {
            try {
                if (avatar != null) {
                    Response resp = client.newCall(new Request.Builder().url(AVATAR_URL).build()).execute();
                    if (resp.isSuccessful() && resp.body() != null) {
                        Bitmap bmp = BitmapFactory.decodeStream(resp.body().byteStream());
                        runOnUiThread(() -> {
                            if (bmp != null) avatar.setImageBitmap(bmp);
                        });
                    }
                }
            } catch (Exception ignored) {}

            try {
                if (maintainerAvatar != null) {
                    Response resp = client.newCall(new Request.Builder().url(MAINTAINER_AVATAR_URL).build()).execute();
                    if (resp.isSuccessful() && resp.body() != null) {
                        Bitmap bmp = BitmapFactory.decodeStream(resp.body().byteStream());
                        runOnUiThread(() -> {
                            if (bmp != null) maintainerAvatar.setImageBitmap(bmp);
                        });
                    }
                }
            } catch (Exception ignored) {}

            try {
                if (modderAvatar != null) {
                    Response resp = client.newCall(new Request.Builder().url(MODDER_AVATAR_URL).build()).execute();
                    if (resp.isSuccessful() && resp.body() != null) {
                        Bitmap bmp = BitmapFactory.decodeStream(resp.body().byteStream());
                        runOnUiThread(() -> {
                            if (bmp != null) modderAvatar.setImageBitmap(bmp);
                        });
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void loadContributors() {
        executor.execute(() -> {
            try {
                Response resp = client.newCall(new Request.Builder().url(URL_CONTRIBUTORS).build()).execute();
                if (!resp.isSuccessful() || resp.body() == null) return;
                JSONArray arr = new JSONArray(resp.body().string());
                List<JSONObject> items = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    String login = item.optString("login", "");
                    if (login.endsWith("[bot]") || login.equals("ImgBotApp")) continue;
                    items.add(item);
                }
                if (items.isEmpty()) return;
                Map<JSONObject, Bitmap> avatars = new LinkedHashMap<>();
                for (JSONObject item : items) {
                    String avatarUrl = item.optString("avatar_url", "");
                    if (avatarUrl.isEmpty()) continue;
                    try {
                        Response avatarResp = client.newCall(new Request.Builder().url(avatarUrl).build()).execute();
                        if (avatarResp.isSuccessful() && avatarResp.body() != null) {
                            Bitmap bmp = decodeScaled(avatarResp.body().bytes(), 128);
                            if (bmp != null) avatars.put(item, bmp);
                        }
                    } catch (Exception ignored) {}
                }
                runOnUiThread(() -> {
                    findViewById(R.id.contributors_card).setVisibility(View.VISIBLE);
                    LinearLayout list = findViewById(R.id.contributors_list);
                    for (JSONObject item : items) addContributorRow(list, item, avatars.get(item));
                });
            } catch (Exception ignored) {}
        });
    }

    private void addContributorRow(LinearLayout list, JSONObject item, Bitmap avatar) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_contributor, list, false);
        ((TextView) row.findViewById(R.id.contributor_name)).setText(item.optString("login", ""));
        ((TextView) row.findViewById(R.id.contributor_commits)).setText(getString(R.string.about_contributor_commits, item.optInt("contributions", 0)));
        if (avatar != null) {
            com.microsoft.xbox.idp.toolkit.CircleImageView avatarView = row.findViewById(R.id.contributor_avatar);
            if (avatarView != null) avatarView.setImageBitmap(avatar);
        }
        String profileUrl = item.optString("html_url", "");
        if (!profileUrl.isEmpty()) {
            row.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(profileUrl)));
                } catch (Exception ignored) {}
            });
            DynamicAnim.applyPressScale(row);
        }
        list.addView(row);
    }

    private Bitmap decodeScaled(byte[] raw, int targetSize) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetSize && bounds.outHeight / (sample * 2) >= targetSize) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
    }

    private void setupLinks() {
        setupLinkButton(R.id.btn_afdian, URL_AFDIAN);
        setupLinkButton(R.id.btn_patreon, URL_PATREON);
        setupLinkButton(R.id.btn_github_repo, URL_REPO);
        setupLinkButton(R.id.btn_github_org, URL_ORG);
        setupLinkButton(R.id.btn_issues, URL_ISSUES);
        setupLinkButton(R.id.btn_star_fork, URL_REPO);
        setupLinkButton(R.id.btn_youtube_maintainer, URL_YOUTUBE);
        setupLinkButton(R.id.btn_github_modder, URL_ORG);
    }

    private void setupLinkButton(int viewId, String url) {
        TextView btn = findViewById(viewId);
        if (btn == null) return;
        btn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {}
        });
        DynamicAnim.applyPressScale(btn);
    }
}
