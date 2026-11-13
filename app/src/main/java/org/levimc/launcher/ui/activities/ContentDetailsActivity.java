package org.levimc.launcher.ui.activities;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.view.ViewGroup;

import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.ContentImporter;
import org.levimc.launcher.core.curseforge.CurseForgeClient;
import org.levimc.launcher.core.discord.DiscordRpcManager;
import org.levimc.launcher.core.curseforge.models.Content;
import org.levimc.launcher.core.curseforge.models.ContentCategory;
import org.levimc.launcher.core.curseforge.models.ContentFile;
import org.levimc.launcher.core.curseforge.models.ContentScreenshot;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.adapter.CurseForgeGalleryAdapter;
import org.levimc.launcher.ui.adapter.CurseForgeScreenshotPageAdapter;
import org.levimc.launcher.ui.adapter.CurseForgeScreenshotThumbAdapter;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.util.LauncherStorage;
import org.levimc.launcher.util.UIHelper;



import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ContentDetailsActivity extends BaseActivity {

    public static final String EXTRA_CONTENT = "extra_content";

    private Content content;
    private ImageView icon;
    private TextView title;
    private TextView author;
    private TextView statDownloads;
    private TextView statLikes;
    private TextView statUpdated;
    private TextView detailCategories;

    private WebView summary;
    
    private MaterialButton btnInstall;
    private MaterialButton btnBrowser;
    private ProgressBar progressBar;
    private RadioGroup detailTabs;
    private RecyclerView galleryRecycler;
    private TextView galleryEmpty;
    private CurseForgeGalleryAdapter galleryAdapter;
    private Dialog viewerDialog;
    private final List<ContentScreenshot> videoItems = new ArrayList<>();

    private ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private ContentImporter contentImporter;
    private VersionManager versionManager;
    private CurseForgeClient client;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_content_details);

        content = (Content) getIntent().getSerializableExtra(EXTRA_CONTENT);
        if (content == null) {
            finish();
            return;
        }

        contentImporter = new ContentImporter(this);
        versionManager = VersionManager.get(this);
        client = CurseForgeClient.getInstance();

        initViews();
        bindData();
        loadDescription();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DiscordRpcManager.getInstance().reportScreen(DiscordRpcManager.SCREEN_CURSEFORGE, R.string.rpc_curseforge_project,
                content != null ? content.name : null);
        if (summary != null) {
            summary.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        DiscordRpcManager.getInstance().clearScreen(DiscordRpcManager.SCREEN_CURSEFORGE);
        if (summary != null) {
            summary.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        if (summary != null) {
            ViewGroup parent = (ViewGroup) summary.getParent();
            if (parent != null) parent.removeView(summary);
            summary.destroy();
            summary = null;
        }
        super.onDestroy();
    }

    private void initWebView() {
        summary.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        WebSettings settings = summary.getSettings();
        // Descriptions come from CurseForge project pages; embedded YouTube
        // players are iframes that need JS, so it stays enabled here.
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setTextZoom(100);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setLoadsImagesAutomatically(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        UIHelper.applyYoutubeCompatibleUserAgent(summary);
        summary.setVerticalScrollBarEnabled(false);
        summary.setHorizontalScrollBarEnabled(false);
        summary.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            private boolean handleUrl(Uri uri) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(ContentDetailsActivity.this,
                            getString(R.string.cannot_open_link, uri.toString()), Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {
                }
                return true;
            }
        });
    }

    private void initViews() {

        icon = findViewById(R.id.detail_icon);
        title = findViewById(R.id.detail_title);
        author = findViewById(R.id.detail_author);
        statDownloads = findViewById(R.id.stat_downloads);
        statLikes = findViewById(R.id.stat_likes);
        statUpdated = findViewById(R.id.stat_updated);
        detailCategories = findViewById(R.id.detail_categories);
        summary = findViewById(R.id.detail_summary);
        btnInstall = findViewById(R.id.btn_install_header);
        btnBrowser = findViewById(R.id.btn_browser);
        progressBar = findViewById(R.id.download_progress);
        detailTabs = findViewById(R.id.detail_tabs);
        galleryRecycler = findViewById(R.id.gallery_recycler);
        galleryEmpty = findViewById(R.id.gallery_empty);

        galleryRecycler.setLayoutManager(new GridLayoutManager(this, 3));
        galleryRecycler.setItemAnimator(null);
        galleryRecycler.setNestedScrollingEnabled(false);
        galleryAdapter = new CurseForgeGalleryAdapter(this::openScreenshot);
        galleryRecycler.setAdapter(galleryAdapter);
        applyGridSpan();

        detailTabs.setOnCheckedChangeListener((group, checkedId) -> {
            boolean gallery = checkedId == R.id.tab_gallery;
            summary.setVisibility(gallery ? View.GONE : View.VISIBLE);
            boolean hasShots = galleryAdapter.getItemCount() > 0;
            galleryRecycler.setVisibility(gallery && hasShots ? View.VISIBLE : View.GONE);
            galleryEmpty.setVisibility(gallery && !hasShots ? View.VISIBLE : View.GONE);
        });

        initWebView();

        btnInstall.setOnClickListener(v -> onInstallClick());
        btnBrowser.setOnClickListener(v -> onBrowserClick());
        btnBrowser.setOnLongClickListener(v -> {
            if (content.links == null || content.links.websiteUrl == null) return false;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("url", content.links.websiteUrl));
            Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void applyGridSpan() {
        galleryRecycler.post(() -> {
            int width = galleryRecycler.getWidth();
            if (width == 0) return;
            int cell = Math.round(110 * getResources().getDisplayMetrics().density);
            int span = Math.max(3, Math.min(6, width / cell));
            ((GridLayoutManager) galleryRecycler.getLayoutManager()).setSpanCount(span);
        });
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyGridSpan();
    }

    private void bindData() {
        title.setText(content.name != null ? content.name : "");
        if (content.authors != null && !content.authors.isEmpty() && content.authors.get(0) != null) {
            author.setText(getString(R.string.mod_author_byline, content.authors.get(0).name));
            author.setVisibility(View.VISIBLE);
        } else {
            author.setVisibility(View.GONE);
        }

        statDownloads.setText(getString(R.string.stat_downloads_format, formatCount(content.downloadCount)));
        View likesRow = findViewById(R.id.stat_likes_row);
        if (content.thumbsUpCount > 0) {
            likesRow.setVisibility(View.VISIBLE);
            statLikes.setText(getString(R.string.stat_likes_format, formatCount(content.thumbsUpCount)));
        } else {
            likesRow.setVisibility(View.GONE);
        }
        View updatedRow = findViewById(R.id.stat_updated_row);
        if (content.dateModified != null && content.dateModified.length() >= 10) {
            updatedRow.setVisibility(View.VISIBLE);
            statUpdated.setText(getString(R.string.stat_updated_format, content.dateModified.substring(0, 10)));
        } else {
            updatedRow.setVisibility(View.GONE);
        }
        if (content.categories != null && !content.categories.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < content.categories.size(); i++) {
                ContentCategory category = content.categories.get(i);
                if (category == null || category.name == null) continue;
                if (names.length() > 0) names.append(" · ");
                names.append(category.name);
            }
            if (names.length() > 0) {
                detailCategories.setVisibility(View.VISIBLE);
                detailCategories.setText(names.toString());
            } else {
                detailCategories.setVisibility(View.GONE);
            }
        } else {
            detailCategories.setVisibility(View.GONE);
        }
        org.levimc.launcher.util.PersonalizationManager personalization =
                new org.levimc.launcher.util.PersonalizationManager(this);
        if (personalization.hasCustomAccent()) {
            btnInstall.setBackgroundTintList(android.content.res.ColorStateList.valueOf(personalization.getAccentColor()));
            int onAccent = android.graphics.Color.luminance(personalization.getAccentColor()) > 0.42f
                    ? android.graphics.Color.BLACK : android.graphics.Color.WHITE;
            btnInstall.setTextColor(onAccent);
            btnInstall.setIconTint(android.content.res.ColorStateList.valueOf(onAccent));
        }
        if (content.logo != null && content.logo.thumbnailUrl != null) {
            Glide.with(this)
                    .load(content.logo.thumbnailUrl)
                    .centerCrop()
                    .placeholder(R.drawable.ic_minecraft_cube)
                    .error(R.drawable.ic_minecraft_cube)
                    .into(icon);
        }

        applyGalleryItems();
    }

    private String formatCount(double count) {
        if (count >= 1000000) {
            return String.format(java.util.Locale.getDefault(), "%.1fM", count / 1000000.0);
        }
        if (count >= 1000) {
            return String.format(java.util.Locale.getDefault(), "%.1fK", count / 1000.0);
        }
        return String.valueOf((long) count);
    }

    private void applyGalleryItems() {
        if (viewerDialog != null && viewerDialog.isShowing()) return;
        List<ContentScreenshot> items = new ArrayList<>();
        if (content.screenshots != null) items.addAll(content.screenshots);
        items.addAll(videoItems);
        galleryAdapter.setScreenshots(items);

        boolean hasItems = !items.isEmpty();
        RadioButton tabGallery = detailTabs.findViewById(R.id.tab_gallery);
        tabGallery.setVisibility(hasItems ? View.VISIBLE : View.GONE);
        RadioButton tabDescription = detailTabs.findViewById(R.id.tab_description);
        LinearLayout.LayoutParams chipParams = (LinearLayout.LayoutParams) tabDescription.getLayoutParams();
        chipParams.weight = hasItems ? 1f : 2f;
        tabDescription.setLayoutParams(chipParams);
    }

    private void extractVideos(String html) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(
                "(?:youtube\\.com/(?:embed/|watch\\?v=|shorts/)|youtu\\.be/)([A-Za-z0-9_-]{6,})").matcher(html);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        videoItems.clear();
        int n = 1;
        for (String id : ids) {
            videoItems.add(ContentScreenshot.video(id, getString(R.string.screenshot_video_title, n++)));
        }
    }

    private void loadDescription() {
        client.getContentDescription(content.id, new CurseForgeClient.CurseForgeCallback<String>() {
            @Override
            public void onSuccess(String result) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || summary == null || result == null || result.isEmpty()) return;
                    extractVideos(result);
                    applyGalleryItems();
                    String html = "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                            + "<style>body{color:" + getHexColor(R.color.on_surface)
                            + ";font-family:sans-serif;font-size:13px;line-height:1.5;overflow-wrap:anywhere;"
                            + "background:transparent;margin:0;padding:0}"
                            + "a{color:" + getHexColor(R.color.primary) + ";text-decoration:none}"
                            + "img{display:block;max-width:100%;height:auto;margin:8px auto;border-radius:7px}"
                            + "iframe{max-width:100%;width:100%;border:0;aspect-ratio:16/9}"
                            + "p{margin:7px 0}pre{white-space:pre-wrap;overflow-wrap:anywhere}"
                            + "table{max-width:100%;display:block;overflow-x:auto}</style></head><body>"
                            + result + "</body></html>";
                    summary.loadDataWithBaseURL("https://www.curseforge.com", html, "text/html", "utf-8", null);
                });
            }

            @Override
            public void onError(Throwable error) {
            }
        });
    }

    private void openScreenshot(int position) {
        List<ContentScreenshot> items = galleryAdapter.getScreenshots();
        if (items == null || items.isEmpty()) return;
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        viewerDialog = dialog;
        dialog.setContentView(R.layout.dialog_screenshot_viewer);

        ViewPager2 pager = dialog.findViewById(R.id.viewer_pager);
        TextView counter = dialog.findViewById(R.id.viewer_counter);
        TextView title = dialog.findViewById(R.id.viewer_title);
        ImageButton prev = dialog.findViewById(R.id.viewer_prev);
        ImageButton next = dialog.findViewById(R.id.viewer_next);
        RecyclerView thumbs = dialog.findViewById(R.id.viewer_thumbs);
        dialog.findViewById(R.id.viewer_close).setOnClickListener(v -> dialog.dismiss());
        CurseForgeScreenshotPageAdapter pageAdapter = new CurseForgeScreenshotPageAdapter(items);
        dialog.setOnDismissListener(d -> {
            viewerDialog = null;
            pageAdapter.stopPlayback();
            pager.post(pageAdapter::destroyWebViews);
        });

        pager.setAdapter(pageAdapter);
        CurseForgeScreenshotThumbAdapter thumbAdapter = new CurseForgeScreenshotThumbAdapter(items,
                pos -> pager.setCurrentItem(pos, true));
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int pos) {
                updateViewerChrome(counter, title, prev, next, items, pos);
                thumbAdapter.setSelected(pos);
                thumbs.scrollToPosition(pos);
            }
        });

        prev.setOnClickListener(v -> pager.setCurrentItem(pager.getCurrentItem() - 1, true));
        next.setOnClickListener(v -> pager.setCurrentItem(pager.getCurrentItem() + 1, true));

        thumbs.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        thumbs.setItemAnimator(null);
        thumbs.setAdapter(thumbAdapter);

        pager.setCurrentItem(position, false);
        updateViewerChrome(counter, title, prev, next, items, position);
        dialog.show();
    }

    private void updateViewerChrome(TextView counter, TextView title, ImageButton prev, ImageButton next,
                                    List<ContentScreenshot> items, int pos) {
        counter.setText(getString(R.string.page_counter, pos + 1, items.size()));
        ContentScreenshot screenshot = items.get(pos);
        boolean hasTitle = screenshot.title != null && !screenshot.title.isEmpty();
        title.setVisibility(hasTitle ? View.VISIBLE : View.GONE);
        title.setText(hasTitle ? screenshot.title : null);
        prev.setVisibility(pos > 0 ? View.VISIBLE : View.GONE);
        next.setVisibility(pos < items.size() - 1 ? View.VISIBLE : View.GONE);
    }

    private void onInstallClick() {
        loadAllFiles();
    }

    private void loadAllFiles() {
        progressBar.setVisibility(View.VISIBLE);
        btnInstall.setEnabled(false);

        client.getModFiles(content.id, 0, 50, new CurseForgeClient.CurseForgeCallback<>() {
            @Override
            public void onSuccess(org.levimc.launcher.core.curseforge.models.ModFilesResponse result) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnInstall.setEnabled(true);
                    if (result != null && result.data != null && !result.data.isEmpty()) {
                        showFilesDialog(result.data);
                    } else {
                        Toast.makeText(ContentDetailsActivity.this, R.string.no_files_found, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(Throwable t) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnInstall.setEnabled(true);
                    Toast.makeText(ContentDetailsActivity.this, getString(R.string.failed_to_load_files, t.getMessage()), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showFilesDialog(java.util.List<ContentFile> files) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_file_list);
        org.levimc.launcher.ui.dialogs.LauncherDialogs.bind(dialog, 560);
        dialog.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());

        org.levimc.launcher.util.PersonalizationManager pm = new org.levimc.launcher.util.PersonalizationManager(this);
        int accent = pm.hasCustomAccent() ? pm.getAccentColor() : androidx.core.content.ContextCompat.getColor(this, R.color.primary);

        RecyclerView recycler = dialog.findViewById(R.id.recycler_files);

        org.levimc.launcher.ui.adapter.FileListAdapter adapter = new org.levimc.launcher.ui.adapter.FileListAdapter(file -> {
            dialog.dismiss();
            downloadAndImport(file);
        }, accent);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        adapter.setFiles(files);

        int maxListHeight = Math.round(getResources().getDisplayMetrics().heightPixels * 0.62f);
        int estimatedHeight = Math.round(getResources().getDisplayMetrics().density * 62f * files.size());
        ViewGroup.LayoutParams listParams = recycler.getLayoutParams();
        listParams.height = Math.min(maxListHeight, Math.max(1, estimatedHeight));
        recycler.setLayoutParams(listParams);
        recycler.setItemAnimator(null);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }

        dialog.show();
        
        if (dialog.getWindow() != null) {
            android.view.View decorView = dialog.getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                dialog.getWindow().setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController controller = decorView.getWindowInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                }
            }
            dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }

        org.levimc.launcher.ui.dialogs.LauncherDialogs.style(dialog, 560);
    }

    private void onBrowserClick() {
        if (content.links != null && content.links.websiteUrl != null) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(content.links.websiteUrl));
            startActivity(intent);
        } else {
            Toast.makeText(this, R.string.no_website_link_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void downloadAndImport(ContentFile file) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(true);
            btnInstall.setEnabled(false);
            Toast.makeText(this, getString(R.string.curseforge_downloading), Toast.LENGTH_SHORT).show();
        });
        
        downloadExecutor.execute(() -> {
            try {
                File cacheDir = getCacheDir();
                File outputFile = new File(cacheDir, file.fileName);
                
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder().url(file.downloadUrl).build();
                
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) throw new IOException("Failed to download");
                    
                    try (InputStream is = response.body().byteStream();
                         FileOutputStream fos = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, read);
                        }
                    }
                }

                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.curseforge_importing), Toast.LENGTH_SHORT).show();
                    importFile(outputFile);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnInstall.setEnabled(true);
                    Toast.makeText(this, getString(R.string.curseforge_download_failed) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void importFile(File file) {
        Uri uri = Uri.fromFile(file);
        
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) {
            progressBar.setVisibility(View.GONE);
            btnInstall.setEnabled(true);
            Toast.makeText(this, R.string.not_found_version, Toast.LENGTH_SHORT).show();
            return;
        }
        
        File worldsDir = getWorldsDirectory();
        File resourcePacksDir = getPackDirectory("resource_packs");
        File behaviorPacksDir = getPackDirectory("behavior_packs");
        File skinPacksDir = getPackDirectory("skin_packs");

        contentImporter.importContent(java.util.Collections.singletonList(uri), resourcePacksDir, behaviorPacksDir, skinPacksDir, worldsDir,
            new ContentImporter.ImportCallback() {
                @Override
                public void onSuccess(String message) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        btnInstall.setEnabled(true);
                        Toast.makeText(ContentDetailsActivity.this, message, Toast.LENGTH_SHORT).show();
                        if (file.exists()) file.delete();
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        btnInstall.setEnabled(true);
                        Toast.makeText(ContentDetailsActivity.this, error, Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onProgress(int progress) {
                }
            });
    }
    private File getPackDirectory(String packType) {
        File gameDataDir = getGameDataDirForSavedStorageType();
        return gameDataDir == null ? null : new File(gameDataDir, packType);
    }

    private File getWorldsDirectory() {
        File gameDataDir = getGameDataDirForSavedStorageType();
        return gameDataDir == null ? null : new File(gameDataDir, "minecraftWorlds");
    }

    private File getGameDataDirForSavedStorageType() {
        android.content.SharedPreferences prefs = getSharedPreferences("content_management", MODE_PRIVATE);
        String savedType = prefs.getString("storage_type", "INTERNAL");
        FeatureSettings.StorageType currentStorageType = parseStorageType(savedType);
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) return null;
        currentStorageType = LauncherStorage.normalizeContentStorageType(
                currentStorageType,
                currentVersion.versionIsolation
        );
        return LauncherStorage.getContentGameDataDir(this, currentVersion.getStorageProfileId(), currentStorageType);
    }

    private FeatureSettings.StorageType parseStorageType(String value) {
        try {
            return FeatureSettings.StorageType.valueOf(value);
        } catch (Exception ignored) {
            return FeatureSettings.StorageType.INTERNAL;
        }
    }

    private String getHexColor(int colorResId) {
        int color = getResources().getColor(colorResId, getTheme());
        return String.format("#%06X", (0xFFFFFF & color));
    }
}
