package org.levimc.launcher.ui.activities;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.levimc.launcher.R;
import org.levimc.launcher.core.ai.AiCrashAnalyzer;
import org.levimc.launcher.core.ai.AiProviderCatalog;
import org.levimc.launcher.core.crash.CrashReporter;
import org.levimc.launcher.core.discord.DiscordRpcManager;
import org.levimc.launcher.core.logging.AppLevelLogSink;
import org.levimc.launcher.core.minecraft.MinecraftActivityState;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.preloader.PreloaderSignatureRulesManager;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.dialogs.LogcatOverlayManager;
import org.levimc.launcher.ui.dialogs.SimpleTextWatcher;
import org.levimc.launcher.ui.packages.SettingsCard;
import org.levimc.launcher.ui.widget.SegmentedControl;
import org.levimc.launcher.ui.widget.SettingsNavigationView;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.util.GithubReleaseUpdater;
import org.levimc.launcher.util.LanguageManager;
import org.levimc.launcher.util.LauncherBackgroundController;
import org.levimc.launcher.util.LauncherStorage;
import org.levimc.launcher.util.PermissionsHandler;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.util.StorageRootMigrator;
import org.levimc.launcher.util.ThemeManager;
import org.levimc.launcher.util.ThemePalette;
import org.levimc.launcher.util.UiSettings;
import org.levimc.launcher.util.WallpaperColorExtractor;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class SettingsActivity extends BaseActivity {

    public static final String EXTRA_SEARCH = "org.levimc.launcher.settings.SEARCH";

    private static final String PAGE_PREFS = "settings_page_prefs";
    private static final String KEY_SELECTED_TAB = "selected_tab_index_v2";
    private static final String KEY_LEGACY_TAB = "selected_tab_index";
    private static final String KEY_PERSONALIZE_SECTION = "personalize_section_index_v2";
    private static final String KEY_LEGACY_PERSONALIZE_SECTION = "personalize_section_index";
    private static final String KEY_WAS_DARK = "settings_was_dark";
    private static final String KEY_EDIT_DARK = "edit_dark";
    private static final String ROW_TAG = "settings_row";

    private static final int TAB_PERSONALIZE = 1;
    private static final int SECTION_WALLPAPER = 1;

    private static final int[] THEME_MODES = {ThemeManager.MODE_LIGHT, ThemeManager.MODE_AMOLED,
            ThemeManager.MODE_FOLLOW_SYSTEM};
    private static final String[] BASE_MODES = {UiSettings.BASE_NONE, UiSettings.BASE_THEME, UiSettings.BASE_COLOR};

    private static final int EASTER_EGG_TAP_COUNT = 3;
    private static final long TAP_TIMEOUT_MS = 2000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private PermissionsHandler permissionsHandler;
    private ActivityResultLauncher<Intent> permissionResultLauncher;
    private ActivityResultLauncher<Intent> bgImagePickerLauncher;
    private ActivityResultLauncher<String> wallpaperColorPermissionLauncher;
    private int updateButtonTapCount = 0;
    private long lastUpdateButtonTapTime = 0;

    private PersonalizationManager personalizationManager;
    private ThemeManager themeManager;
    private SharedPreferences pagePrefs;

    private ScrollView scrollView;
    private LinearLayout scrollContent;
    private SettingsNavigationView settingsTabs;
    private View[] sections;
    private SettingsNavigationView personalizeTabs;
    private View[] personalizeGroups;
    private int selectedTabIndex = 0;
    private int personalizeSectionIndex = 0;

    private EditText searchInput;
    private TextView searchEmpty;
    private final Runnable searchRunnable = this::runSearch;
    private View highlightedRow;
    private Drawable highlightedRowBackground;

    private SegmentedControl themeModeTabs;
    private boolean editDark;
    private LinearLayout presetGrid;
    private LinearLayout moreGrid;

    private ImageView bgImagePreview;
    private TextView bgImageStatus;
    private TextView bgImageBlurValue;
    private TextView bgImageBrightnessValue;
    private TextView bgImageOpacityValue;

    private boolean wallpaperSurfaceVisible;

    private TextView preloaderSigsLastUpdateText;
    private MaterialButton preloaderSigsUpdateButton;
    private SettingsCard pkgSettingsSection;

    private Button discordAccountButton;
    private MaterialButton installMethodButton;
    private TextView aiCrashSummary;
    private TextView storageRootSummary;
    private Spinner bgAnimationSpinner;
    private LinearLayout bgAnimationOptions;
    private boolean storageMigrationRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        DynamicAnim.applyPressScaleRecursively(findViewById(android.R.id.content));
        setActiveNavTab(R.id.nav_tab_settings);

        personalizationManager = new PersonalizationManager(this);
        themeManager = new ThemeManager(this);
        pagePrefs = getSharedPreferences(PAGE_PREFS, MODE_PRIVATE);

        selectedTabIndex = pagePrefs.contains(KEY_SELECTED_TAB)
                ? pagePrefs.getInt(KEY_SELECTED_TAB, 0)
                : migrateTabIndex(pagePrefs.getInt(KEY_LEGACY_TAB, 0));
        personalizeSectionIndex = pagePrefs.contains(KEY_PERSONALIZE_SECTION)
                ? pagePrefs.getInt(KEY_PERSONALIZE_SECTION, 0)
                : migratePersonalizeSection(pagePrefs.getInt(KEY_LEGACY_PERSONALIZE_SECTION, 0));
        boolean dark = isDark();
        editDark = pagePrefs.getBoolean(KEY_WAS_DARK, dark) == dark
                ? pagePrefs.getBoolean(KEY_EDIT_DARK, dark) : dark;
        if (savedInstanceState != null) {
            selectedTabIndex = savedInstanceState.containsKey(KEY_SELECTED_TAB)
                    ? savedInstanceState.getInt(KEY_SELECTED_TAB, selectedTabIndex)
                    : migrateTabIndex(savedInstanceState.getInt(KEY_LEGACY_TAB,
                            pagePrefs.getInt(KEY_LEGACY_TAB, 0)));
            personalizeSectionIndex = savedInstanceState.containsKey(KEY_PERSONALIZE_SECTION)
                    ? savedInstanceState.getInt(KEY_PERSONALIZE_SECTION, personalizeSectionIndex)
                    : migratePersonalizeSection(savedInstanceState.getInt(KEY_LEGACY_PERSONALIZE_SECTION,
                            pagePrefs.getInt(KEY_LEGACY_PERSONALIZE_SECTION, 0)));
            if (savedInstanceState.getBoolean(KEY_WAS_DARK, dark) == dark) {
                editDark = savedInstanceState.getBoolean(KEY_EDIT_DARK, dark);
            }
        }

        permissionsHandler = PermissionsHandler.getInstance();
        permissionResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (permissionsHandler != null) {
                        permissionsHandler.onActivityResult(result.getResultCode(), result.getData());
                    }
                }
        );
        permissionsHandler.setActivity(this, permissionResultLauncher);

        bgImagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) importSingleImage(uri);
                    }
                }
        );
        wallpaperColorPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        applyWallpaperAccentColor();
                    } else {
                        Toast.makeText(this, R.string.wallpaper_color_failed, Toast.LENGTH_LONG).show();
                    }
                }
        );

        initTabs();
        setupSearch();
        setupBasicSection();
        pkgSettingsSection = new SettingsCard(this, findViewById(R.id.pkg_settings_card));
        setupThemeSection();
        setupWallpaperSection();
        setupUpdatesSection();
        setupAboutSection();

        selectTab(selectedTabIndex, false);
        selectPersonalizeSection(personalizeSectionIndex);

        String query = getIntent() != null ? getIntent().getStringExtra(EXTRA_SEARCH) : null;
        if (savedInstanceState == null && query != null && !query.trim().isEmpty()) {
            searchInput.setText(query);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_SELECTED_TAB, selectedTabIndex);
        outState.putInt(KEY_PERSONALIZE_SECTION, personalizeSectionIndex);
        outState.putBoolean(KEY_EDIT_DARK, editDark);
        outState.putBoolean(KEY_WAS_DARK, isDark());
    }

    private boolean isDark() {
        return personalizationManager.isDarkMode(this);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void initTabs() {
        scrollView = findViewById(R.id.settings_scroll);
        scrollContent = findViewById(R.id.settings_scroll_content);
        settingsTabs = findViewById(R.id.settings_tabs);
        settingsTabs.setVertical(true);
        settingsTabs.setItems(
                getString(R.string.settings_tab_basic),
                getString(R.string.settings_tab_personalize),
                getString(R.string.settings_tab_components),
                getString(R.string.settings_tab_privacy),
                getString(R.string.settings_ui_tab_updates),
                getString(R.string.settings_tab_about));
        View categoryBody = (View) settingsTabs.getParent();
        ScrollView categoryScroll = (ScrollView) categoryBody.getParent();
        View.OnLayoutChangeListener fitNavigation = (v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            int availableHeight = categoryScroll.getHeight() - categoryScroll.getPaddingTop()
                    - categoryScroll.getPaddingBottom() - settingsTabs.getTop() - categoryBody.getPaddingBottom();
            settingsTabs.fitVerticalHeight(availableHeight);
        };
        categoryScroll.addOnLayoutChangeListener(fitNavigation);
        settingsTabs.addOnLayoutChangeListener(fitNavigation);
        settingsTabs.setOnSegmentSelectedListener(index -> {
            selectTab(index, true);
            scrollView.scrollTo(0, 0);
        });

        sections = new View[]{
                findViewById(R.id.section_basic),
                findViewById(R.id.section_personalize),
                findViewById(R.id.section_components),
                findViewById(R.id.section_privacy),
                findViewById(R.id.section_updates),
                findViewById(R.id.section_about)};

        personalizeTabs = findViewById(R.id.personalize_tabs);
        personalizeTabs.setItems(
                getString(R.string.settings_ui_section_theme),
                getString(R.string.settings_ui_section_wallpaper));
        personalizeGroups = new View[]{
                findViewById(R.id.personalize_theme_group),
                findViewById(R.id.personalize_wallpaper_group)};
        personalizeTabs.setOnSegmentSelectedListener(index -> {
            selectPersonalizeSection(index);
            scrollView.scrollTo(0, 0);
        });
    }

    private static int migrateTabIndex(int index) {
        if (index == 3) return 0;
        return index > 3 ? index - 1 : index;
    }

    private static int migratePersonalizeSection(int index) {
        return index == 2 ? SECTION_WALLPAPER : 0;
    }

    private void selectTab(int index, boolean animate) {
        if (index < 0 || index >= sections.length) index = 0;
        selectedTabIndex = index;
        pagePrefs.edit().putInt(KEY_SELECTED_TAB, index).apply();
        settingsTabs.setSelectedIndex(index, false);
        for (int i = 0; i < sections.length; i++) {
            View section = sections[i];
            section.animate().cancel();
            if (i == index) {
                section.setVisibility(View.VISIBLE);
                if (animate) {
                    section.setAlpha(0f);
                    section.animate().alpha(1f).setDuration(200).start();
                } else {
                    section.setAlpha(1f);
                }
            } else {
                section.setVisibility(View.GONE);
            }
        }
    }

    private void selectPersonalizeSection(int index) {
        if (index < 0 || index >= personalizeGroups.length) index = 0;
        clearHighlight();
        personalizeSectionIndex = index;
        pagePrefs.edit().putInt(KEY_PERSONALIZE_SECTION, index).apply();
        personalizeTabs.setSelectedIndex(index, false);
        for (int i = 0; i < personalizeGroups.length; i++) {
            personalizeGroups[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        if (index == SECTION_WALLPAPER) updateBgImageUI();
    }

    private void setupSearch() {
        searchInput = findViewById(R.id.settings_search);
        searchEmpty = findViewById(R.id.settings_search_empty);
        searchInput.setSaveEnabled(false);
        searchInput.addTextChangedListener(SimpleTextWatcher.after(s -> {
            handler.removeCallbacks(searchRunnable);
            handler.postDelayed(searchRunnable, 300);
        }));
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                handler.removeCallbacks(searchRunnable);
                runSearch();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                return true;
            }
            return false;
        });
    }

    private void runSearch() {
        String query = searchInput.getText().toString().trim().toLowerCase(Locale.getDefault());
        searchEmpty.setVisibility(View.GONE);
        if (query.isEmpty()) return;

        for (int tab = 0; tab < sections.length; tab++) {
            List<View> rows = new ArrayList<>();
            collectRows(sections[tab], rows);
            for (View row : rows) {
                if (!isRowAvailable(row, sections[tab])) continue;
                if (textOf(row).contains(query)) {
                    jumpTo(tab, subSectionOf(row), row);
                    return;
                }
            }
            if (tab == TAB_PERSONALIZE) {
                for (int i = 0; i < personalizeGroups.length; i++) {
                    if (personalizeTabs.getSegment(i).getText().toString().toLowerCase(Locale.getDefault()).contains(query)) {
                        jumpTo(tab, i, null);
                        return;
                    }
                }
            }
            if (settingsTabs.getSegment(tab).getText().toString().toLowerCase(Locale.getDefault()).contains(query)) {
                jumpTo(tab, -1, null);
                return;
            }
        }
        searchEmpty.setVisibility(View.VISIBLE);
    }

    private void collectRows(View view, List<View> out) {
        if (ROW_TAG.equals(view.getTag())) {
            out.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectRows(group.getChildAt(i), out);
        }
    }

    private boolean isRowAvailable(View row, View section) {
        View v = row;
        while (v != null && v != section) {
            boolean switchable = false;
            for (View group : personalizeGroups) if (group == v) switchable = true;
            if (!switchable && v.getVisibility() == View.GONE) return false;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return true;
    }

    private int subSectionOf(View row) {
        View v = row;
        while (v != null) {
            for (int i = 0; i < personalizeGroups.length; i++) if (personalizeGroups[i] == v) return i;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return -1;
    }

    private String textOf(View view) {
        StringBuilder sb = new StringBuilder();
        appendText(view, sb);
        return sb.toString().toLowerCase(Locale.getDefault());
    }

    private void appendText(View view, StringBuilder sb) {
        if (view instanceof EditText) return;
        if (view instanceof TextView) {
            sb.append(((TextView) view).getText()).append(' ');
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) appendText(group.getChildAt(i), sb);
        }
    }

    private void jumpTo(int tab, int subSection, View row) {
        if (tab != selectedTabIndex) selectTab(tab, false);
        if (subSection >= 0 && subSection != personalizeSectionIndex) selectPersonalizeSection(subSection);
        scrollView.post(() -> {
            if (row == null) {
                scrollView.smoothScrollTo(0, 0);
                return;
            }
            Rect rect = new Rect();
            row.getDrawingRect(rect);
            scrollContent.offsetDescendantRectToMyCoords(row, rect);
            scrollView.smoothScrollTo(0, Math.max(0, rect.top - dp(16)));
            highlightRow(row);
        });
    }

    private void highlightRow(View row) {
        clearHighlight();
        highlightedRow = row;
        highlightedRowBackground = row.getBackground();
        row.setBackground(UiKit.selectedRing(this, 12));
        handler.postDelayed(this::clearHighlight, 1600);
    }

    private void clearHighlight() {
        if (highlightedRow != null) {
            highlightedRow.setBackground(highlightedRowBackground);
            highlightedRow = null;
            highlightedRowBackground = null;
        }
    }

    private void setupBasicSection() {
        LanguageManager languageManager = new LanguageManager(this);
        FeatureSettings fs = FeatureSettings.getInstance();

        String[] languageOptions = {
                getString(R.string.english),
                getString(R.string.chinese),
                getString(R.string.french),
                getString(R.string.hindi),
                getString(R.string.indonesian),
                getString(R.string.japanese),
                getString(R.string.portuguese),
                getString(R.string.russian),
                getString(R.string.spanish),
                getString(R.string.turkish),
                getString(R.string.vietnamese)
        };
        String[] languageCodes = {"en", "zh-CN", "fr", "hi", "id", "ja", "pt", "ru", "es", "tr", "vi"};

        String currentCode = Locale.forLanguageTag(languageManager.getCurrentLanguage()).getLanguage();
        int defaultIdx = switch (currentCode) {
            case "zh" -> 1;
            case "fr" -> 2;
            case "hi" -> 3;
            case "id", "in" -> 4;
            case "ja" -> 5;
            case "pt" -> 6;
            case "ru" -> 7;
            case "es" -> 8;
            case "tr" -> 9;
            case "vi" -> 10;
            default -> 0;
        };

        TextView languageCurrent = findViewById(R.id.language_current);
        languageCurrent.setText(languageOptions[defaultIdx]);
        findViewById(R.id.language_change).setOnClickListener(v -> new CustomAlertDialog(this)
                .setTitleText(getString(R.string.language))
                .setItems(languageOptions, (dialog, which) -> {
                    String code = languageCodes[which];
                    languageCurrent.setText(languageOptions[which]);
                    if (!code.equals(languageManager.getCurrentLanguage())) {
                        languageManager.setAppLanguage(code);
                    }
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show());

        ViewGroup basicBody = (ViewGroup) ((ViewGroup) ((ViewGroup) findViewById(R.id.section_basic)).getChildAt(0)).getChildAt(0);

        LinearLayout appLevelLogRow = addSettingsRow(basicBody, R.string.app_level_logging, R.string.app_level_logging_desc);
        SwitchMaterial switchAppLevelLog = new SwitchMaterial(this);
        appLevelLogRow.addView(switchAppLevelLog);
        switchAppLevelLog.setChecked(fs.isAppLevelLoggingEnabled());
        switchAppLevelLog.setEnabled(fs.isLogcatOverlayEnabled());
        switchAppLevelLog.setAlpha(fs.isLogcatOverlayEnabled() ? 1f : 0.5f);
        switchAppLevelLog.setOnCheckedChangeListener((btn, checked) -> {
            fs.setAppLevelLoggingEnabled(checked);
            if (!checked) AppLevelLogSink.teardown();
        });

        LinearLayout foregroundServiceRow = addSettingsRow(basicBody, R.string.foreground_service, R.string.foreground_service_desc);
        SwitchMaterial switchForegroundService = new SwitchMaterial(this);
        foregroundServiceRow.addView(switchForegroundService);
        switchForegroundService.setChecked(fs.isForegroundServiceEnabled());
        switchForegroundService.setOnCheckedChangeListener((btn, checked) -> fs.setForegroundServiceEnabled(checked));

        SwitchMaterial switchLogcat = findViewById(R.id.switch_logcat);
        switchLogcat.setChecked(fs.isLogcatOverlayEnabled());
        switchLogcat.setOnCheckedChangeListener((btn, checked) -> {
            fs.setLogcatOverlayEnabled(checked);
            switchAppLevelLog.setEnabled(checked);
            switchAppLevelLog.setAlpha(checked ? 1f : 0.5f);
            try {
                LogcatOverlayManager mgr = LogcatOverlayManager.getInstance();
                if (mgr != null) mgr.refreshVisibility();
            } catch (Throwable ignored) {}
        });

        SwitchMaterial switchCrashUpload = findViewById(R.id.switch_crash_upload);
        switchCrashUpload.setChecked(fs.isCrashUploadEnabled());
        switchCrashUpload.setOnCheckedChangeListener((btn, checked) -> {
            fs.setCrashUploadEnabled(checked);
            CrashReporter.refreshCrashlyticsCollection(this);
        });

        SwitchMaterial switchManagedLogin = findViewById(R.id.switch_managed_login);
        switchManagedLogin.setChecked(fs.isLauncherManagedMcLoginEnabled());
        switchManagedLogin.setOnCheckedChangeListener((btn, checked) -> fs.setLauncherManagedMcLoginEnabled(checked));

        LinearLayout storageRow = addSettingsRow(basicBody, R.string.storage_root, R.string.storage_root_dialog_message);
        storageRow.setOnClickListener(v -> showStorageRootPicker());
        storageRow.setTag(ROW_TAG);
        storageRootSummary = new TextView(this, null, 0, R.style.settings_RowDesc);
        storageRootSummary.setSingleLine(true);
        storageRootSummary.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        ((ViewGroup) storageRow.getChildAt(0)).addView(storageRootSummary);
        refreshStorageRootSummary();

        LinearLayout aiRow = addSettingsRow(basicBody, R.string.ai_crash_title, R.string.ai_crash_summary_empty);
        aiRow.setOnClickListener(v -> showAiCrashConfigDialog(aiCrashSummary));
        aiRow.setTag(ROW_TAG);
        aiCrashSummary = (TextView) ((ViewGroup) aiRow.getChildAt(0)).getChildAt(1);
        refreshAiCrashSummary(aiCrashSummary);

        SwitchMaterial switchDiscordPresence = findViewById(R.id.switch_discord_presence);
        switchDiscordPresence.setChecked(fs.isDiscordRpcEnabled());
        switchDiscordPresence.setEnabled(DiscordRpcManager.isConfigured());
        switchDiscordPresence.setOnCheckedChangeListener((btn, checked) -> {
            fs.setDiscordRpcEnabled(checked);
            DiscordRpcManager.getInstance().onEnabledChanged(this);
        });

        if (DiscordRpcManager.isConfigured()) {
            LinearLayout discordAccountRow = addSettingsRow(basicBody, R.string.discord_account, 0);
            discordAccountRow.setTag(ROW_TAG);
            discordAccountButton = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle);
            discordAccountButton.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.ui_default)));
            discordAccountButton.setTextColor(getColor(R.color.text_primary));
            discordAccountButton.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            discordAccountRow.addView(discordAccountButton);
            updateDiscordAccountButton();
            discordAccountButton.setOnClickListener(v -> {
                DiscordRpcManager manager = DiscordRpcManager.getInstance();
                if (manager.isLoggedIn()) {
                    manager.logout(this);
                    updateDiscordAccountButton();
                } else {
                    manager.beginLogin(this, new DiscordRpcManager.LoginCallback() {
                        @Override
                        public void onSuccess(String username) {
                            updateDiscordAccountButton();
                        }

                        @Override
                        public void onError(Exception error) {
                            Toast.makeText(SettingsActivity.this,
                                    error.getMessage() != null ? error.getMessage() : getString(R.string.discord_log_in),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        }
    }

    private LinearLayout addSettingsRow(ViewGroup parent, int titleRes, int descRes) {
        LinearLayout row = new LinearLayout(this, null, 0, R.style.settings_Row);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout text = new LinearLayout(this, null, 0, R.style.settings_RowText);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = new TextView(this, null, 0, R.style.settings_RowTitle);
        title.setText(titleRes);
        text.addView(title);
        if (descRes != 0) {
            TextView desc = new TextView(this, null, 0, R.style.settings_RowDesc);
            desc.setText(descRes);
            text.addView(desc);
        }
        row.addView(text);
        parent.addView(row);
        parent.addView(new View(this, null, 0, R.style.settings_Divider),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return row;
    }

    private void refreshStorageRootSummary() {
        if (storageRootSummary != null) {
            storageRootSummary.setText(LauncherStorage.getAppRoot(this).getPath());
        }
    }

    private void refreshAiCrashSummary(TextView summary) {
        String provider = AiCrashAnalyzer.getProvider(this);
        summary.setText(AiCrashAnalyzer.isConfigured(this)
                ? getString(R.string.ai_crash_summary_configured,
                        (provider.isEmpty() ? "Custom" : provider) + " · " + AiCrashAnalyzer.getModel(this))
                : getString(R.string.ai_crash_summary_empty));
    }

    private void updateDiscordAccountButton() {
        if (discordAccountButton == null) return;
        DiscordRpcManager manager = DiscordRpcManager.getInstance();
        if (manager.isLoggedIn()) {
            discordAccountButton.setText(getString(R.string.discord_log_out, manager.getUsername()));
        } else {
            discordAccountButton.setText(R.string.discord_log_in);
        }
    }

    private void setupThemeSection() {
        themeModeTabs = findViewById(R.id.theme_mode_tabs);
        themeModeTabs.setEqualWidth(false);
        themeModeTabs.addSegment(getString(R.string.settings_ui_mode_light), R.drawable.ic_sun);
        themeModeTabs.addSegment(getString(R.string.theme_amoled), R.drawable.ic_moon);
        themeModeTabs.addSegment(getString(R.string.settings_ui_mode_system), R.drawable.ic_ui_monitor);
        int mode = themeManager.getCurrentMode();
        themeModeTabs.setSelectedIndex(themeModeIndex(mode), false);
        themeModeTabs.setOnSegmentSelectedListener(index -> {
            updateThemeModePanel(THEME_MODES[index]);
            themeManager.setThemeMode(THEME_MODES[index]);
        });
        updateThemeModePanel(mode);

        SegmentedControl editTabs = findViewById(R.id.edit_mode_tabs);
        editTabs.setEqualWidth(false);
        editTabs.setItems(getString(R.string.settings_ui_mode_light), getString(R.string.settings_ui_mode_dark));
        editTabs.setSelectedIndex(editDark ? 1 : 0, false);
        editTabs.setOnSegmentSelectedListener(index -> {
            editDark = index == 1;
            pagePrefs.edit().putBoolean(KEY_EDIT_DARK, editDark).putBoolean(KEY_WAS_DARK, isDark()).apply();
            refreshEditMode();
        });

        pagePrefs.edit().putBoolean(KEY_EDIT_DARK, editDark).putBoolean(KEY_WAS_DARK, isDark()).apply();

        presetGrid = findViewById(R.id.color_preset_grid);
        moreGrid = findViewById(R.id.color_more_grid);
        View.OnLayoutChangeListener widthListener = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) v.post(this::rebuildSwatches);
        };
        presetGrid.addOnLayoutChangeListener(widthListener);
        moreGrid.addOnLayoutChangeListener(widthListener);

        SegmentedControl baseTabs = findViewById(R.id.base_mode_tabs);
        baseTabs.setItems(getString(R.string.settings_ui_base_none), getString(R.string.settings_ui_base_theme),
                getString(R.string.settings_ui_base_color));
        baseTabs.setOnSegmentSelectedListener(index -> {
            UiSettings.setBase(this, editDark, BASE_MODES[index], UiSettings.getBaseColor(this, editDark),
                    UiSettings.getBaseOpacity(this, editDark));
            onBaseChanged();
        });
        EditText baseInput = findViewById(R.id.base_color_input);
        findViewById(R.id.base_color_apply).setOnClickListener(v -> {
            Integer color = parseColorInput(baseInput.getText().toString());
            if (color == null) {
                Toast.makeText(this, R.string.invalid_color_format, Toast.LENGTH_SHORT).show();
                return;
            }
            UiSettings.setBase(this, editDark, UiSettings.BASE_COLOR, color, UiSettings.getBaseOpacity(this, editDark));
            baseInput.clearFocus();
            onBaseChanged();
        });
        SeekBar baseOpacity = findViewById(R.id.base_opacity_seek);
        baseOpacity.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setValueText(R.id.base_opacity_value, R.string.settings_ui_value_percent, progress);
                if (!fromUser) return;
                UiSettings.setBase(SettingsActivity.this, editDark, UiSettings.getBaseMode(SettingsActivity.this, editDark),
                        UiSettings.getBaseColor(SettingsActivity.this, editDark), progress);
                if (editDark == isDark()) personalizationManager.refreshBackgroundEffects(SettingsActivity.this);
            }
        });

        refreshEditMode();
    }

    private static int themeModeIndex(int mode) {
        for (int i = 0; i < THEME_MODES.length; i++) if (THEME_MODES[i] == mode) return i;
        return THEME_MODES.length - 1;
    }

    private void updateThemeModePanel(int mode) {
        View panel = findViewById(R.id.theme_mode_panel);
        TextView desc = findViewById(R.id.theme_mode_panel_desc);
        View schedule = findViewById(R.id.theme_schedule_box);
        View sun = findViewById(R.id.theme_sun_box);
        boolean showAmoled = mode == ThemeManager.MODE_AMOLED;
        boolean showSystem = mode == ThemeManager.MODE_FOLLOW_SYSTEM;
        panel.setVisibility(showAmoled || showSystem ? View.VISIBLE : View.GONE);
        schedule.setVisibility(View.GONE);
        sun.setVisibility(View.GONE);
        if (showAmoled) {
            desc.setText(R.string.theme_amoled_desc);
        } else if (showSystem) {
            desc.setText(R.string.settings_ui_mode_system_desc);
        }
    }

    private void refreshEditMode() {
        ImageView icon = findViewById(R.id.edit_mode_icon);
        icon.setImageResource(editDark ? R.drawable.ic_moon : R.drawable.ic_sun);
        icon.setImageTintList(ColorStateList.valueOf(editDark ? UiKit.INDIGO : UiKit.AMBER));
        ((TextView) findViewById(R.id.edit_mode_title))
                .setText(editDark ? R.string.settings_ui_dark_colors : R.string.settings_ui_light_colors);
        rebuildSwatches();
        refreshBaseUI();
    }

    private void rebuildSwatches() {
        if (presetGrid == null) return;
        int selected = personalizationManager.getAccentColor(editDark) | 0xFF000000;
        boolean custom = !contains(PersonalizationManager.PRESET_COLORS, selected)
                && !contains(PersonalizationManager.MORE_COLORS, selected);
        fillSwatchGrid(presetGrid, PersonalizationManager.PRESET_COLORS, false, selected, custom);
        fillSwatchGrid(moreGrid, PersonalizationManager.MORE_COLORS, true, selected, custom);
    }

    private static boolean contains(int[] colors, int color) {
        for (int c : colors) if ((c | 0xFF000000) == color) return true;
        return false;
    }

    private void fillSwatchGrid(LinearLayout grid, int[] colors, boolean withCustom, int selected, boolean custom) {
        int available = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
        if (available <= 0) return;
        grid.removeAllViews();
        int perRow = Math.max(1, available / dp(40));
        int total = colors.length + (withCustom ? 2 : 0);
        LinearLayout row = null;
        for (int i = 0; i < total; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            View cell;
            if (i < colors.length) {
                cell = makeSwatch(colors[i] | 0xFF000000, (colors[i] | 0xFF000000) == selected);
            } else if (i == total - 1) {
                cell = makeWallpaperSwatch();
            } else {
                cell = makeCustomSwatch(selected, custom);
            }
            row.addView(cell);
        }
    }

    private View makeSwatch(int color, boolean selected) {
        FrameLayout cell = swatchCell(color, selected);
        if (selected) cell.addView(swatchIcon(R.drawable.ic_check, ThemePalette.onAccent(color)));
        cell.setContentDescription(String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF));
        cell.setOnClickListener(v -> setAccent(color));
        DynamicAnim.applyPressScale(cell);
        return cell;
    }

    private View makeCustomSwatch(int selected, boolean custom) {
        int fill = custom ? selected : getColor(R.color.ui_default);
        FrameLayout cell = swatchCell(fill, custom);
        cell.addView(swatchIcon(R.drawable.ic_ui_palette,
                custom ? ThemePalette.onAccent(selected) : getColor(R.color.text_secondary)));
        cell.setContentDescription(getString(R.string.settings_ui_custom_color));
        cell.setOnClickListener(v -> showCustomColorDialog(custom ? selected : UiKit.accent(this)));
        DynamicAnim.applyPressScale(cell);
        return cell;
    }

    private View makeWallpaperSwatch() {
        FrameLayout cell = swatchCell(getColor(R.color.ui_default), false);
        cell.addView(swatchIcon(R.drawable.ic_ui_image, getColor(R.color.text_secondary)));
        cell.setContentDescription(getString(R.string.wallpaper_color_from_wallpaper));
        cell.setOnClickListener(v -> applyWallpaperAccentColor());
        DynamicAnim.applyPressScale(cell);
        return cell;
    }

    private FrameLayout swatchCell(int color, boolean selected) {
        FrameLayout cell = new FrameLayout(this);
        cell.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        if (selected) {
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(Color.TRANSPARENT);
            ring.setStroke(dp(2), color);
            cell.setBackground(ring);
        }
        View circle = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        circle.setBackground(dot);
        cell.addView(circle, new FrameLayout.LayoutParams(dp(selected ? 30 : 32), dp(selected ? 30 : 32), Gravity.CENTER));
        cell.setClickable(true);
        cell.setFocusable(true);
        return cell;
    }

    private ImageView swatchIcon(int res, int tint) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(res);
        icon.setImageTintList(ColorStateList.valueOf(tint));
        icon.setLayoutParams(new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        return icon;
    }

    private void setAccent(int color) {
        personalizationManager.setAccentColor(color);
        if (editDark == isDark()) recreate();
        else {
            rebuildSwatches();
            personalizationManager.applyAccentToView(findViewById(android.R.id.content), this);
        }
    }

    private void applyWallpaperAccentColor() {
        int color = WallpaperColorExtractor.extract(this);
        if (color != 0) {
            personalizationManager.setAccentColor(color);
            if (editDark == isDark()) recreate();
            else {
                rebuildSwatches();
                personalizationManager.applyAccentToView(findViewById(android.R.id.content), this);
            }
            Toast.makeText(this, R.string.wallpaper_color_applied, Toast.LENGTH_SHORT).show();
            return;
        }

        boolean storageMissing = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED;
        if (storageMissing) {
            new CustomAlertDialog(this)
                    .setTitleText(getString(R.string.wallpaper_color_failed_title))
                    .setMessage(getString(R.string.wallpaper_color_failed_storage_message))
                    .setPositiveButton(getString(R.string.grant_permission),
                            v -> wallpaperColorPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE))
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
        } else {
            Toast.makeText(this, R.string.wallpaper_color_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void showCustomColorDialog(int initial) {
        EditText input = new EditText(this, null, 0, R.style.Widget_Levi_Input);
        input.setHint(R.string.custom_color_hint);
        input.setText(String.format(Locale.ROOT, "#%06X", initial & 0xFFFFFF));
        input.setSingleLine(true);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, dp(4));
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.settings_ui_custom_color))
                .setCustomView(box)
                .setPositiveButton(getString(R.string.apply), v -> {
                    Integer color = parseColorInput(input.getText().toString());
                    if (color == null) {
                        Toast.makeText(this, R.string.invalid_color_format, Toast.LENGTH_SHORT).show();
                    } else {
                        setAccent(color);
                    }
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private static Integer parseColorInput(String raw) {
        String input = raw.trim();
        try {
            if (input.startsWith("#")) return Color.parseColor(input) | 0xFF000000;
            if (input.contains(",")) {
                String[] parts = input.split(",");
                if (parts.length != 3) return null;
                int r = Integer.parseInt(parts[0].trim());
                int g = Integer.parseInt(parts[1].trim());
                int b = Integer.parseInt(parts[2].trim());
                if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) return null;
                return Color.rgb(r, g, b);
            }
            return Color.parseColor("#" + input) | 0xFF000000;
        } catch (Exception e) {
            return null;
        }
    }

    private void onBaseChanged() {
        refreshBaseUI();
        if (editDark == isDark()) personalizationManager.refreshBackgroundEffects(this);
    }

    private void refreshBaseUI() {
        String mode = UiSettings.getBaseMode(this, editDark);
        int color = UiSettings.getBaseColor(this, editDark);
        int opacity = UiSettings.getBaseOpacity(this, editDark);
        int index = 0;
        for (int i = 0; i < BASE_MODES.length; i++) if (BASE_MODES[i].equals(mode)) index = i;
        ((SegmentedControl) findViewById(R.id.base_mode_tabs)).setSelectedIndex(index, false);
        findViewById(R.id.base_color_box).setVisibility(UiSettings.BASE_COLOR.equals(mode) ? View.VISIBLE : View.GONE);
        findViewById(R.id.base_opacity_box).setVisibility(UiSettings.BASE_NONE.equals(mode) ? View.GONE : View.VISIBLE);

        String hex = String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
        ((TextView) findViewById(R.id.base_color_value)).setText(hex);
        GradientDrawable swatch = roundRect(color, 12);
        swatch.setStroke(dp(1), getColor(R.color.ui_border));
        findViewById(R.id.base_color_swatch).setBackground(swatch);
        EditText input = findViewById(R.id.base_color_input);
        if (!input.hasFocus()) input.setText(hex);

        SeekBar seek = findViewById(R.id.base_opacity_seek);
        seek.setProgress(opacity);
        setValueText(R.id.base_opacity_value, R.string.settings_ui_value_percent, opacity);
    }

    private GradientDrawable roundRect(int color, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(radiusDp));
        gd.setColor(color);
        return gd;
    }

    private void setValueText(int viewId, int formatRes, int value) {
        TextView view = findViewById(viewId);
        if (view != null) view.setText(getString(formatRes, value));
    }

    private void setupWallpaperSection() {
        bgImagePreview = findViewById(R.id.bg_image_preview);
        bgImagePreview.setClipToOutline(true);
        bgImageStatus = findViewById(R.id.bg_image_status);

        findViewById(R.id.btn_select_bg_image).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            bgImagePickerLauncher.launch(intent);
        });
        findViewById(R.id.btn_clear_bg_image).setOnClickListener(v -> {
            personalizationManager.clearBackgroundImage();
            recreate();
        });

        setupBackgroundImageControls();
        setupBackgroundAnimationPicker();
        setupNavBarPositionPicker();
        updateBgImageUI();
        setBackgroundImportState(LauncherBackgroundController.isImporting());
    }

    private void setupBackgroundImageControls() {
        SeekBar blurSeek = findViewById(R.id.seek_bg_image_blur);
        SeekBar brightnessSeek = findViewById(R.id.seek_bg_image_brightness);
        SeekBar opacitySeek = findViewById(R.id.seek_bg_image_opacity);
        bgImageBlurValue = findViewById(R.id.bg_image_blur_value);
        bgImageBrightnessValue = findViewById(R.id.bg_image_brightness_value);
        bgImageOpacityValue = findViewById(R.id.bg_image_opacity_value);

        wallpaperSurfaceVisible = personalizationManager.hasBackgroundImage()
                && personalizationManager.getBackgroundOpacity() > 0;
        blurSeek.setMax(PersonalizationManager.BG_BLUR_MAX);
        blurSeek.setProgress(personalizationManager.getBackgroundImageBlur());
        updateBgImageBlurValue(blurSeek.getProgress());
        blurSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateBgImageBlurValue(progress);
                if (!fromUser) return;
                personalizationManager.setBackgroundImageBlur(progress);
                if (personalizationManager.supportsRealtimeBackgroundBlur()) {
                    personalizationManager.refreshBackgroundEffects(SettingsActivity.this);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                personalizationManager.refreshBackgroundEffects(SettingsActivity.this);
            }
        });

        brightnessSeek.setMin(PersonalizationManager.BG_BRIGHTNESS_MIN);
        brightnessSeek.setMax(PersonalizationManager.BG_BRIGHTNESS_MAX);
        brightnessSeek.setProgress(personalizationManager.getBackgroundImageBrightness());
        setBgValue(bgImageBrightnessValue, R.string.settings_ui_value_percent, brightnessSeek.getProgress());
        brightnessSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setBgValue(bgImageBrightnessValue, R.string.settings_ui_value_percent, progress);
                if (!fromUser) return;
                personalizationManager.setBackgroundImageBrightness(progress);
                personalizationManager.refreshBackgroundColorEffects(SettingsActivity.this);
            }
        });

        opacitySeek.setMax(100);
        opacitySeek.setProgress(personalizationManager.getBackgroundOpacity());
        setBgValue(bgImageOpacityValue, R.string.settings_ui_value_percent, opacitySeek.getProgress());
        opacitySeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setBgValue(bgImageOpacityValue, R.string.settings_ui_value_percent, progress);
                if (!fromUser) return;
                personalizationManager.setBackgroundOpacity(progress);
                personalizationManager.refreshBackgroundColorEffects(SettingsActivity.this);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                boolean visible = personalizationManager.hasBackgroundImage()
                        && personalizationManager.getBackgroundOpacity() > 0;
                if (visible != wallpaperSurfaceVisible) recreate();
            }
        });
    }

    private void updateBgImageBlurValue(int blurRadius) {
        setBgValue(bgImageBlurValue, R.string.settings_ui_value_px, blurRadius);
    }

    private void setBgValue(TextView view, int formatRes, int value) {
        if (view != null) view.setText(getString(formatRes, value));
    }

    private void updateBgImageUI() {
        if (bgImageStatus == null) return;
        boolean hasImage = personalizationManager.hasBackgroundImage();

        SeekBar blurSeek = findViewById(R.id.seek_bg_image_blur);
        boolean canBlur = personalizationManager.canBlurBackground();
        blurSeek.setEnabled(canBlur);
        if (canBlur) updateBgImageBlurValue(blurSeek.getProgress());
        else bgImageBlurValue.setText(R.string.bg_media_blur_unavailable);

        findViewById(R.id.bg_image_effect_controls).setVisibility(hasImage ? View.VISIBLE : View.GONE);

        if (hasImage) {
            bgImageStatus.setText(R.string.bg_image_selected);
            if (personalizationManager.applyBackgroundImageToView(bgImagePreview)) {
                bgImagePreview.setVisibility(View.VISIBLE);
            }
        } else {
            bgImageStatus.setText(R.string.bg_media_none);
            bgImagePreview.setImageDrawable(null);
            bgImagePreview.setVisibility(View.GONE);
        }

        MaterialButton clear = findViewById(R.id.btn_clear_bg_image);
        clear.setVisibility(hasImage ? View.VISIBLE : View.GONE);
    }

    private LinearLayout addPersonalizeCard(ViewGroup group) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        card.setLayoutParams(params);
        card.setCardBackgroundColor(getColor(R.color.ui_card_surface));
        card.setCardElevation(0);
        card.setStrokeColor(getColor(R.color.ui_card_stroke));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(8));
        LinearLayout body = new LinearLayout(this, null, 0, R.style.settings_CardBody);
        card.addView(body);
        group.addView(card);
        personalizationManager.applyGlassToView(card);
        return body;
    }

    private void setupBackgroundAnimationPicker() {
        String[] values = {
                "none", "matrix", "donut", "fireworks", "wave",
                "warp", "ripple", "bubbles", "fireflies", "aurora", "grid", "comet", "rain"
        };
        String[] labels = {
                getString(R.string.bg_animation_none),
                getString(R.string.bg_animation_matrix),
                getString(R.string.bg_animation_donut),
                getString(R.string.bg_animation_fireworks),
                getString(R.string.bg_animation_wave),
                getString(R.string.bg_animation_warp),
                getString(R.string.bg_animation_ripple),
                getString(R.string.bg_animation_bubbles),
                getString(R.string.bg_animation_fireflies),
                getString(R.string.bg_animation_aurora),
                getString(R.string.bg_animation_grid),
                getString(R.string.bg_animation_comet),
                getString(R.string.bg_animation_rain)
        };

        LinearLayout body = addPersonalizeCard(findViewById(R.id.personalize_wallpaper_group));
        LinearLayout header = addSettingsRow(body, R.string.bg_animation_title, R.string.bg_animation_desc);
        header.setTag(ROW_TAG);
        bgAnimationSpinner = new Spinner(this);
        header.addView(bgAnimationSpinner);
        bgAnimationOptions = new LinearLayout(this);
        bgAnimationOptions.setOrientation(LinearLayout.VERTICAL);
        body.addView(bgAnimationOptions);

        String selected = personalizationManager.getBackgroundAnimation();
        int selectedPosition = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(selected)) selectedPosition = i;
        }

        showAnimationOptions(values[selectedPosition]);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        bgAnimationSpinner.setAdapter(adapter);
        bgAnimationSpinner.setPopupBackgroundResource(R.drawable.bg_popup_menu_rounded);
        bgAnimationSpinner.setSelection(selectedPosition);
        bgAnimationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                showAnimationOptions(values[position]);
                if (values[position].equals(personalizationManager.getBackgroundAnimation())) return;
                personalizationManager.setBackgroundAnimation(values[position]);
                recreate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupNavBarPositionPicker() {
        String[] values = {"top", "bottom", "left", "right"};
        String[] labels = {
                getString(R.string.nav_position_top),
                getString(R.string.nav_position_bottom),
                getString(R.string.nav_position_left),
                getString(R.string.nav_position_right)
        };

        LinearLayout body = addPersonalizeCard(findViewById(R.id.personalize_wallpaper_group));
        LinearLayout header = addSettingsRow(body, R.string.nav_bar_position_title, 0);
        header.setTag(ROW_TAG);
        Spinner positionSpinner = new Spinner(this);
        header.addView(positionSpinner);

        String selected = personalizationManager.getNavBarPosition();
        int selectedPosition = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(selected)) selectedPosition = i;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        positionSpinner.setAdapter(adapter);
        positionSpinner.setPopupBackgroundResource(R.drawable.bg_popup_menu_rounded);
        positionSpinner.setSelection(selectedPosition);
        positionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (values[position].equals(personalizationManager.getNavBarPosition())) return;
                personalizationManager.setNavBarPosition(values[position]);
                recreate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void showAnimationOptions(String effect) {
        LinearLayout box = bgAnimationOptions;
        if (box == null) return;
        box.removeAllViews();
        Object[][] options = animationOptions(effect);
        if (options == null) {
            box.setVisibility(View.GONE);
            return;
        }
        box.setVisibility(View.VISIBLE);
        for (Object[] option : options) {
            String key = (String) option[0];
            int type = (Integer) option[1];
            int min = (Integer) option[3];
            int max = (Integer) option[4];
            int def = (Integer) option[5];
            if ("lightningRate".equals(key)
                    && personalizationManager.getAnimOption(effect, "lightning", 0) != 1) continue;
            box.addView(buildOptionRow(effect, key, type, getString((Integer) option[2]),
                    min, max, personalizationManager.getAnimOption(effect, key, def), option[6]));
        }
    }

    private void applyAnimationOption(String effect, String key, int value) {
        personalizationManager.setAnimOption(effect, key, value);
        View background = getWindow().getDecorView().findViewWithTag("personalization_bg");
        if (background instanceof org.levimc.launcher.ui.animation.AnimatedBackgroundView) {
            ((org.levimc.launcher.ui.animation.AnimatedBackgroundView) background).setOption(key, value);
        }
        if ("lightning".equals(key)) showAnimationOptions(effect);
    }

    private View buildOptionRow(String effect, String key, int type, String label,
                                int min, int max, int value, Object extra) {
        int pad = Math.round(6 * getResources().getDisplayMetrics().density);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, pad, 0, 0);

        if (type == 2) {
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = new TextView(this);
            name.setText(label);
            name.setTextColor(getColor(R.color.on_surface));
            name.setTextSize(12f);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(name);
            SwitchMaterial toggle = new SwitchMaterial(this);
            toggle.setChecked(value == 1);
            toggle.setOnCheckedChangeListener((button, checked) ->
                    applyAnimationOption(effect, key, checked ? 1 : 0));
            row.addView(toggle);
            return row;
        }

        if (type == 3) {
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = new TextView(this);
            name.setText(label);
            name.setTextColor(getColor(R.color.on_surface));
            name.setTextSize(12f);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(name);
            int[] choices = (int[]) extra;
            String[] choiceLabels = new String[choices.length];
            for (int i = 0; i < choices.length; i++) choiceLabels[i] = getString(choices[i]);
            Spinner picker = new Spinner(this);
            picker.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ArrayAdapter<String> choiceAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, choiceLabels);
            choiceAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            picker.setAdapter(choiceAdapter);
            picker.setPopupBackgroundResource(R.drawable.bg_popup_menu_rounded);
            picker.setSelection(value);
            picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position == value) return;
                    applyAnimationOption(effect, key, position);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            row.addView(picker);
            return row;
        }

        LinearLayout optionHeader = new LinearLayout(this);
        optionHeader.setOrientation(LinearLayout.HORIZONTAL);
        optionHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(getColor(R.color.on_surface));
        name.setTextSize(12f);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView amountLabel = new TextView(this);
        amountLabel.setText(optionValueText(type, value));
        amountLabel.setTextColor(getColor(R.color.text_secondary));
        amountLabel.setTextSize(12f);
        optionHeader.addView(name);
        optionHeader.addView(amountLabel);
        row.addView(optionHeader);

        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(value - min);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = progress + min;
                amountLabel.setText(optionValueText(type, v));
                applyAnimationOption(effect, key, v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        row.addView(seek);
        return row;
    }

    private String optionValueText(int type, int value) {
        return type == 0 ? value + "%" : String.valueOf(value);
    }

    private Object[][] animationOptions(String effect) {
        switch (effect) {
            case "wave":
                return optionSet(
                        option("speed", R.string.anim_option_speed, 20, 300, 100),
                        optionPlain("layers", R.string.anim_option_layers, 1, 5, 4));
            case "aurora":
                return optionSet(
                        option("speed", R.string.anim_option_speed, 20, 300, 100),
                        optionPlain("layers", R.string.anim_option_layers, 1, 5, 3));
            case "donut":
                return optionSet(
                        option("speed", R.string.anim_option_speed, 10, 300, 100),
                        optionPlain("rings", R.string.anim_option_rings, 6, 24, 14));
            case "matrix":
                return optionSet(
                        option("speed", R.string.anim_option_speed, 20, 300, 100),
                        option("amount", R.string.anim_option_density, 30, 100, 100));
            case "fireworks":
                return optionSet(
                        option("speed", R.string.anim_option_speed, 20, 300, 100),
                        option("amount", R.string.anim_option_amount, 50, 200, 100));
            case "ripple":
                return optionSet(
                        option("speed", R.string.anim_option_speed, 20, 300, 100),
                        option("amount", R.string.anim_option_size, 50, 150, 100));
            case "grid":
                return optionSet(
                        option("speed", R.string.anim_option_speed, 20, 300, 100),
                        option("amount", R.string.anim_option_density, 50, 150, 100));
            case "comet":
                return optionSet(
                        option("speed", R.string.anim_option_speed, 20, 300, 100),
                        option("amount", R.string.anim_option_amount, 20, 300, 100));
            case "rain":
                return optionSet(
                        optionToggle("clouds", R.string.anim_rain_clouds, 1),
                        optionChoice("cloudType", R.string.anim_rain_cloud_type, 0,
                                new int[]{R.string.anim_rain_cloud_sway, R.string.anim_rain_cloud_blow}),
                        optionPlain("wind", R.string.anim_rain_wind, 0, 100, 30),
                        optionChoice("windDir", R.string.anim_rain_wind_dir, 1,
                                new int[]{R.string.anim_rain_dir_left, R.string.anim_rain_dir_right}),
                        option("amount", R.string.anim_rain_density, 20, 150, 100),
                        optionToggle("lightning", R.string.anim_rain_lightning, 0),
                        optionPlain("lightningRate", R.string.anim_rain_rate, 1, 100, 30));
            case "bubbles":
            case "fireflies":
            case "warp":
                return optionSet(
                        option("speed", R.string.anim_option_speed, 20, 300, 100),
                        option("amount", R.string.anim_option_amount, 20, 100, 100));
            default:
                return null;
        }
    }

    private Object[] option(String key, int labelRes, int min, int max, int def) {
        return new Object[]{key, 0, labelRes, min, max, def, null};
    }

    private Object[] optionPlain(String key, int labelRes, int min, int max, int def) {
        return new Object[]{key, 1, labelRes, min, max, def, null};
    }

    private Object[] optionToggle(String key, int labelRes, int def) {
        return new Object[]{key, 2, labelRes, 0, 1, def, null};
    }

    private Object[] optionChoice(String key, int labelRes, int def, int[] choices) {
        return new Object[]{key, 3, labelRes, 0, 0, def, choices};
    }

    private Object[][] optionSet(Object[]... options) {
        return options;
    }

    private void importSingleImage(Uri uri) {
        setBackgroundImportState(true);
        WeakReference<SettingsActivity> ref = new WeakReference<>(this);
        LauncherBackgroundController.importBackground(getApplicationContext(), uri, success -> {
            SettingsActivity activity = ref.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            activity.setBackgroundImportState(false);
            if (success) {
                activity.recreate();
            } else {
                activity.updateBgImageUI();
                Toast.makeText(activity, R.string.bg_media_import_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setBackgroundImportState(boolean importing) {
        boolean blocked = LauncherBackgroundController.isMinecraftBlocked();
        findViewById(R.id.btn_select_bg_image).setEnabled(!importing && !blocked);
        findViewById(R.id.btn_clear_bg_image).setEnabled(!importing);
        if (importing && bgImageStatus != null) {
            bgImageStatus.setText(R.string.bg_media_importing);
            bgImageStatus.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDiscordAccountButton();
        if (personalizationManager != null) {
            updateBgImageUI();
            setBackgroundImportState(LauncherBackgroundController.isImporting());
        }
        if (pkgSettingsSection != null) {
            pkgSettingsSection.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (pkgSettingsSection != null) {
            pkgSettingsSection.onDestroy();
            pkgSettingsSection = null;
        }
        super.onDestroy();
    }

    private void setupUpdatesSection() {
        try {
            String localVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            TextView versionText = findViewById(R.id.version_text);
            versionText.setText(localVersion);
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        findViewById(R.id.btn_check_update).setOnClickListener(v -> handleUpdateButtonClick());

        preloaderSigsLastUpdateText = findViewById(R.id.preloader_sigs_last_update);
        preloaderSigsUpdateButton = findViewById(R.id.btn_update_preloader_sigs);
        refreshPreloaderSigsLastUpdateUi();
        if (preloaderSigsUpdateButton != null) {
            preloaderSigsUpdateButton.setOnClickListener(v -> handlePreloaderSigsUpdateClick());
        }

        ViewGroup updatesBody = (ViewGroup) ((ViewGroup) ((ViewGroup) findViewById(R.id.section_updates)).getChildAt(0)).getChildAt(0);
        LinearLayout crashRow = addSettingsRow(updatesBody, R.string.crash_logs_title, 0);
        crashRow.setTag(ROW_TAG);
        crashRow.setOnClickListener(v -> showCrashLogsDialog());
        LinearLayout installRow = addSettingsRow(updatesBody, R.string.install_method_title, 0);
        installRow.setTag(ROW_TAG);
        installMethodButton = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle);
        installMethodButton.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.ui_default)));
        installMethodButton.setTextColor(getColor(R.color.text_primary));
        installMethodButton.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        installMethodButton.setText(getString(R.string.install_method_button,
                getString(GithubReleaseUpdater.getInstallMethodLabel(this))));
        installRow.addView(installMethodButton);
        installMethodButton.setOnClickListener(v -> showInstallMethodDialog());
    }

    private void showCrashLogsDialog() {
        File[] files = LauncherStorage.getCrashLogsDir(this).listFiles(File::isFile);
        if (files == null || files.length == 0) {
            Toast.makeText(this, R.string.crash_logs_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        DateFormat fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        String[] items = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            items[i] = fmt.format(new Date(files[i].lastModified())) + " · " + files[i].getName();
        }
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.crash_logs_title))
                .setItems(items, (dialog, which) -> openCrashLog(files[which]))
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showInstallMethodDialog() {
        final String[] methods = {
                GithubReleaseUpdater.METHOD_SYSTEM,
                GithubReleaseUpdater.METHOD_SHIZUKU,
                GithubReleaseUpdater.METHOD_ROOT
        };
        String[] labels = {
                getString(R.string.install_method_system),
                getString(R.string.install_method_shizuku),
                getString(R.string.install_method_root)
        };
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.install_method_title))
                .setItems(labels, (dialog, which) -> {
                    GithubReleaseUpdater.setInstallMethod(this, methods[which]);
                    installMethodButton.setText(getString(R.string.install_method_button,
                            getString(GithubReleaseUpdater.getInstallMethodLabel(this))));
                    if (GithubReleaseUpdater.METHOD_SHIZUKU.equals(methods[which])) {
                        GithubReleaseUpdater.ensureShizukuPermission();
                    }
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void openCrashLog(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "text/plain")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, null));
        } catch (Throwable ignored) {
        }
    }

    private void handlePreloaderSigsUpdateClick() {
        if (preloaderSigsUpdateButton == null) {
            return;
        }
        if (!PreloaderSignatureRulesManager.hasRemoteRulesUrl()) {
            Toast.makeText(this, R.string.preloader_sigs_no_remote_url, Toast.LENGTH_SHORT).show();
            return;
        }

        preloaderSigsUpdateButton.setEnabled(false);
        preloaderSigsUpdateButton.setText(R.string.preloader_sigs_updating);
        PreloaderSignatureRulesManager.refreshNow(this, result -> {
            if (isFinishing()) {
                return;
            }
            preloaderSigsUpdateButton.setEnabled(true);
            preloaderSigsUpdateButton.setText(R.string.preloader_sigs_update);
            refreshPreloaderSigsLastUpdateUi();

            if (result.success) {
                Toast.makeText(this, R.string.preloader_sigs_update_success, Toast.LENGTH_SHORT).show();
            } else {
                String detail = result.message.isEmpty()
                        ? getString(R.string.unknown_error)
                        : result.message;
                Toast.makeText(this, getString(R.string.preloader_sigs_update_failed, detail), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void refreshPreloaderSigsLastUpdateUi() {
        if (preloaderSigsLastUpdateText == null) {
            return;
        }

        long updateTime = PreloaderSignatureRulesManager.getLastSuccessfulUpdateTime(this);
        String updateText = updateTime > 0L
                ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(new Date(updateTime))
                : getString(R.string.preloader_sigs_never_updated);
        preloaderSigsLastUpdateText.setText(getString(R.string.preloader_sigs_last_update, updateText));
    }

    private void setupAboutSection() {
        findViewById(R.id.settings_btn_github).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LiteLDev/LeviLauncher-Android"))));

        findViewById(R.id.settings_btn_discord).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/jsnzw4ueAt"))));
    }

    private void handleUpdateButtonClick() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastUpdateButtonTapTime > TAP_TIMEOUT_MS) {
            updateButtonTapCount = 0;
        }

        updateButtonTapCount++;
        lastUpdateButtonTapTime = currentTime;

        if (updateButtonTapCount >= EASTER_EGG_TAP_COUNT) {
            updateButtonTapCount = 0;
            triggerEasterEgg();
        } else {
            new GithubReleaseUpdater(this, "sang765", "LeviDroid-Modder", permissionResultLauncher).checkUpdate();
        }
    }

    private void triggerEasterEgg() {
        try {
            String[] feeds = {
                    "aHR0cHM6Ly95b3V0dS5iZS9GdHV0TEE2M0NwOD9zaT1CSExEWHZLOTZPZ1A0NUI4",
                    "aHR0cHM6Ly95b3V0dS5iZS9kUXc0dzlXZ1hjUQ=="
            };
            String encoded = feeds[ThreadLocalRandom.current().nextInt(feeds.length)];
            String url = new String(Base64.decode(encoded, Base64.DEFAULT));
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showAiCrashConfigDialog(TextView summary) {
        float density = getResources().getDisplayMetrics().density;
        int padH = (int) (20 * density);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padH, (int) (8 * density), padH, 0);

        String[] selectedProvider = {AiCrashAnalyzer.getProvider(this)};
        String[] selectedModel = {AiCrashAnalyzer.getModel(this)};
        String[] selectedFormat = {AiCrashAnalyzer.getFormat(this)};

        EditText baseUrlInput = new EditText(this);
        baseUrlInput.setHint(R.string.ai_hint_base_url);
        baseUrlInput.setSingleLine(true);
        baseUrlInput.setText(AiCrashAnalyzer.getBaseUrl(this));

        EditText apiKeyInput = new EditText(this);
        apiKeyInput.setHint(R.string.ai_hint_api_key);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        apiKeyInput.setText(AiCrashAnalyzer.getApiKey(this));

        TextView providerRow = new TextView(this);
        providerRow.setTextSize(15);
        providerRow.setTextColor(getColor(R.color.on_surface));
        providerRow.setHint(R.string.ai_tap_provider);
        providerRow.setText(selectedProvider[0]);
        providerRow.setPadding(0, (int) (14 * density), 0, (int) (14 * density));
        providerRow.setOnClickListener(v -> showProviderPickerDialog(
                getString(R.string.ai_select_provider), preset -> {
                    selectedProvider[0] = preset.name;
                    selectedFormat[0] = preset.format;
                    if (!preset.baseUrl.isEmpty()) baseUrlInput.setText(preset.baseUrl);
                    providerRow.setText(preset.name);
                }));

        TextView modelRow = new TextView(this);
        modelRow.setTextSize(15);
        modelRow.setTextColor(getColor(R.color.on_surface));
        modelRow.setHint(R.string.ai_tap_to_pick);
        modelRow.setText(selectedModel[0]);
        modelRow.setPadding(0, (int) (14 * density), 0, (int) (14 * density));
        modelRow.setOnClickListener(v -> showModelPicker(
                baseUrlInput.getText().toString(),
                apiKeyInput.getText().toString(),
                selectedFormat[0],
                selectedModel[0],
                model -> {
                    selectedModel[0] = model;
                    modelRow.setText(model);
                }));

        container.addView(providerRow);
        container.addView(baseUrlInput);
        container.addView(apiKeyInput);
        container.addView(modelRow);

        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.ai_crash_title))
                .setCustomView(container)
                .setPositiveButton(getString(R.string.save), v -> {
                    AiCrashAnalyzer.setConfig(this,
                            selectedProvider[0],
                            baseUrlInput.getText().toString(),
                            apiKeyInput.getText().toString(),
                            selectedModel[0],
                            selectedFormat[0]);
                    refreshAiCrashSummary(summary);
                })
                .setNegativeButton(getString(R.string.cancel), v -> { })
                .show();
    }

    private void showModelPicker(String baseUrl, String apiKey, String format,
                                 String current, Consumer<String> onPicked) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, R.string.ai_loading_models, Toast.LENGTH_SHORT).show();
        AiCrashAnalyzer.fetchModels(this, baseUrl, apiKey, format, new AiCrashAnalyzer.ModelsCallback() {
            @Override
            public void onDone(List<String> models) {
                if (isFinishing() || isDestroyed()) return;
                if (models.isEmpty()) {
                    Toast.makeText(SettingsActivity.this,
                            getString(R.string.ai_failed, getString(R.string.ai_no_models)),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                showSearchListDialog(models, getString(R.string.ai_select_model), onPicked);
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(SettingsActivity.this,
                        getString(R.string.ai_failed, message), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showProviderPickerDialog(String title, Consumer<AiProviderCatalog.Provider> onPicked) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding((int) (20 * density), (int) (8 * density), (int) (20 * density), 0);

        EditText dialogSearchInput = new EditText(this);
        dialogSearchInput.setHint(R.string.ai_search_providers);
        dialogSearchInput.setSingleLine(true);
        container.addView(dialogSearchInput);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        ListView listView = new ListView(this);
        listView.setAdapter(adapter);
        int maxListHeight = Math.min((int) (320 * density),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.45f));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxListHeight);
        listParams.topMargin = (int) (8 * density);
        listView.setLayoutParams(listParams);
        container.addView(listView);

        dialogSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.getFilter().filter(s);
            }
        });

        List<AiProviderCatalog.Provider> current = new ArrayList<>();
        CustomAlertDialog[] dialogHolder = new CustomAlertDialog[1];
        listView.setOnItemClickListener((parent, view, position, id) -> {
            AiProviderCatalog.Provider picked = AiProviderCatalog.byName(current, (String) parent.getItemAtPosition(position));
            if (picked == null) return;
            onPicked.accept(picked);
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        });

        dialogHolder[0] = new CustomAlertDialog(this)
                .setTitleText(title)
                .setCustomView(container)
                .setOuterScrollbarEnabled(false)
                .setNegativeButton(getString(R.string.cancel), v -> { });
        dialogHolder[0].show();

        boolean hasCache = AiProviderCatalog.load(this, providers -> {
            if (isFinishing() || isDestroyed()) return;
            current.clear();
            current.addAll(providers);
            adapter.clear();
            adapter.addAll(AiProviderCatalog.names(providers));
            adapter.notifyDataSetChanged();
        });
        if (!hasCache) {
            Toast.makeText(this, R.string.ai_loading_providers, Toast.LENGTH_SHORT).show();
        }
    }

    private void showSearchListDialog(List<String> items, String title, Consumer<String> onPicked) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding((int) (20 * density), (int) (8 * density), (int) (20 * density), 0);

        EditText dialogSearchInput = new EditText(this);
        dialogSearchInput.setHint(R.string.ai_search_models);
        dialogSearchInput.setSingleLine(true);
        container.addView(dialogSearchInput);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
        ListView listView = new ListView(this);
        listView.setAdapter(adapter);
        int maxListHeight = Math.min((int) (320 * density),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.45f));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxListHeight);
        listParams.topMargin = (int) (8 * density);
        listView.setLayoutParams(listParams);
        container.addView(listView);

        dialogSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.getFilter().filter(s);
            }
        });

        CustomAlertDialog[] dialogHolder = new CustomAlertDialog[1];
        listView.setOnItemClickListener((parent, view, position, id) -> {
            onPicked.accept((String) parent.getItemAtPosition(position));
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        });

        dialogHolder[0] = new CustomAlertDialog(this)
                .setTitleText(title)
                .setCustomView(container)
                .setOuterScrollbarEnabled(false)
                .setNegativeButton(getString(R.string.cancel), v -> { });
        dialogHolder[0].show();
    }

    private void showStorageRootPicker() {
        if (storageMigrationRunning) {
            Toast.makeText(this, R.string.storage_root_migrating, Toast.LENGTH_SHORT).show();
            return;
        }
        if (MinecraftActivityState.isRunning()) {
            Toast.makeText(this, R.string.storage_root_game_running, Toast.LENGTH_LONG).show();
            return;
        }
        String[] modes = {
                LauncherStorage.STORAGE_ROOT_MEDIA,
                LauncherStorage.STORAGE_ROOT_APP_EXTERNAL,
                LauncherStorage.STORAGE_ROOT_INTERNAL
        };
        String[] labels = {
                getString(R.string.storage_root_mode_media),
                getString(R.string.storage_root_mode_app_external),
                getString(R.string.storage_root_mode_internal)
        };
        String current = LauncherStorage.getStorageRootMode(this);
        for (int i = 0; i < labels.length; i++) {
            if (modes[i].equals(current)) {
                labels[i] = "✓ " + labels[i];
            }
        }
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.storage_root))
                .setMessage(getString(R.string.storage_root_dialog_message))
                .setItems(labels, (dialog, which) -> applyStorageRootMode(modes[which]))
                .show();
    }

    private void applyStorageRootMode(String mode) {
        if (mode.equals(LauncherStorage.getStorageRootMode(this))) return;
        File oldRoot = LauncherStorage.getAppRoot(this);
        File newRoot = LauncherStorage.resolveAppRootForMode(this, mode);
        if (newRoot.getAbsolutePath().equals(oldRoot.getAbsolutePath())) {
            commitStorageRootMode(mode);
            return;
        }
        if (!StorageRootMigrator.hasUserData(oldRoot)) {
            commitStorageRootMode(mode);
            return;
        }
        if (StorageRootMigrator.hasUserData(newRoot)) {
            new CustomAlertDialog(this)
                    .setTitleText(getString(R.string.storage_root))
                    .setMessage(getString(R.string.storage_root_conflict_message))
                    .setPositiveButton(getString(R.string.storage_root_switch_anyway), v -> commitStorageRootMode(mode))
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show();
            return;
        }
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.storage_root_migrate_title))
                .setMessage(getString(R.string.storage_root_migrate_message))
                .setPositiveButton(getString(R.string.storage_root_move_and_switch), v -> migrateAndSwitch(oldRoot, newRoot, mode))
                .setNeutralButton(getString(R.string.storage_root_switch_only), v -> commitStorageRootMode(mode))
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show();
    }

    private void commitStorageRootMode(String mode) {
        LauncherStorage.setStorageRootMode(this, mode);
        refreshStorageRootSummary();
        refreshVersionManager();
        Toast.makeText(this, R.string.storage_root_switched, Toast.LENGTH_SHORT).show();
    }

    private void refreshVersionManager() {
        VersionManager manager = VersionManager.getIfInitialized();
        if (manager != null) {
            manager.loadAllVersions();
        }
    }

    private void migrateAndSwitch(File oldRoot, File newRoot, String mode) {
        storageMigrationRunning = true;
        CustomAlertDialog progress = new CustomAlertDialog(this)
                .setTitleText(getString(R.string.storage_root_migrate_title))
                .setMessage(getString(R.string.storage_root_migrating));
        progress.setCancelable(false);
        progress.show();
        WeakReference<SettingsActivity> activityRef = new WeakReference<>(this);
        new Thread(() -> {
            boolean ok;
            try {
                ok = StorageRootMigrator.moveRoot(oldRoot, newRoot,
                        () -> LauncherStorage.setStorageRootMode(getApplicationContext(), mode));
            } catch (Throwable t) {
                ok = false;
            }
            final boolean success = ok;
            SettingsActivity activity = activityRef.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            activity.runOnUiThread(() -> {
                activity.storageMigrationRunning = false;
                if (progress.isShowing()) progress.dismiss();
                activity.refreshStorageRootSummary();
                if (success) {
                    activity.refreshVersionManager();
                }
                Toast.makeText(activity,
                        success ? R.string.storage_root_switched : R.string.storage_root_move_failed,
                        Toast.LENGTH_LONG).show();
            });
        }, "storage-root-migration").start();
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
