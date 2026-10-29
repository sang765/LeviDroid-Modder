package org.levimc.launcher.ui.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.switchmaterial.SwitchMaterial;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.levimc.launcher.R;
import org.levimc.launcher.core.crash.CrashReporter;
import org.levimc.launcher.core.logging.AppLevelLogSink;
import org.levimc.launcher.preloader.PreloaderSignatureRulesManager;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.dialogs.LogcatOverlayManager;
import org.levimc.launcher.util.GithubReleaseUpdater;
import org.levimc.launcher.util.LanguageManager;
import org.levimc.launcher.util.LauncherStorage;
import org.levimc.launcher.util.PermissionsHandler;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.util.LauncherBackgroundController;
import org.levimc.launcher.util.ThemeManager;
import org.levimc.launcher.util.WallpaperColorExtractor;

import java.text.DateFormat;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class SettingsActivity extends BaseActivity {

    private PermissionsHandler permissionsHandler;
    private ActivityResultLauncher<Intent> permissionResultLauncher;
    private ActivityResultLauncher<Intent> bgImagePickerLauncher;
    private ActivityResultLauncher<String> wallpaperColorPermissionLauncher;
    private int updateButtonTapCount = 0;
    private long lastUpdateButtonTapTime = 0;
    private static final int EASTER_EGG_TAP_COUNT = 3;
    private static final long TAP_TIMEOUT_MS = 2000;

    private TextView tabBasic;
    private TextView tabPersonalize;
    private TextView tabUpdates;
    private TextView tabAbout;

    private View sectionBasic;
    private View sectionPersonalize;
    private View sectionUpdates;
    private View sectionAbout;

    private static final String KEY_SELECTED_TAB = "selected_tab_index";
    private int selectedTabIndex = 0;

    private PersonalizationManager personalizationManager;
    private LinearLayout colorGridContainer;
    private LinearLayout moreColorsContainer;
    private TextView bgImageStatus;
    private TextView bgImageBlurValue;
    private TextView bgImageBrightnessValue;
    private ImageView bgImagePreview;
    private TextView preloaderSigsLastUpdateText;
    private Button preloaderSigsUpdateButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        DynamicAnim.applyPressScaleRecursively(findViewById(android.R.id.content));

        setupNavBar();

        personalizationManager = new PersonalizationManager(this);

        if (savedInstanceState != null) {
            selectedTabIndex = savedInstanceState.getInt(KEY_SELECTED_TAB, 0);
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
                        if (uri != null) {
                            setBackgroundImportState(true);
                            WeakReference<SettingsActivity> activityRef = new WeakReference<>(this);
                            LauncherBackgroundController.importBackground(getApplicationContext(), uri, success -> {
                                SettingsActivity activity = activityRef.get();
                                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                                activity.setBackgroundImportState(false);
                                activity.updateBgImageUI();
                                if (success) activity.recreate();
                                else Toast.makeText(activity, R.string.bg_media_import_failed, Toast.LENGTH_LONG).show();
                            });
                        }
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
        setupBasicSection();
        setupPersonalizeSection();
        setupUpdatesSection();
        setupAboutSection();

        TextView[] tabs = getSettingsTabs();
        if (selectedTabIndex >= tabs.length) {
            selectedTabIndex = 0;
        }
        selectTab(tabs[selectedTabIndex]);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_SELECTED_TAB, selectedTabIndex);
    }

    private void initTabs() {
        tabBasic = findViewById(R.id.tab_basic);
        tabPersonalize = findViewById(R.id.tab_personalize);
        tabUpdates = findViewById(R.id.tab_updates);
        tabAbout = findViewById(R.id.tab_about);

        sectionBasic = findViewById(R.id.section_basic);
        sectionPersonalize = findViewById(R.id.section_personalize);
        sectionUpdates = findViewById(R.id.section_updates);
        sectionAbout = findViewById(R.id.section_about);

        tabBasic.setOnClickListener(v -> { selectedTabIndex = 0; selectTab(tabBasic); });
        tabPersonalize.setOnClickListener(v -> { selectedTabIndex = 1; selectTab(tabPersonalize); });
        tabUpdates.setOnClickListener(v -> { selectedTabIndex = 2; selectTab(tabUpdates); });
        tabAbout.setOnClickListener(v -> { selectedTabIndex = 3; selectTab(tabAbout); });
    }

    private void selectTab(TextView selectedTab) {
        TextView[] tabs = getSettingsTabs();
        View[] sections = {sectionBasic, sectionPersonalize, sectionUpdates, sectionAbout};

        int accent = personalizationManager.getAccentColor();

        for (int i = 0; i < tabs.length; i++) {
            boolean isSelected = tabs[i] == selectedTab;

            if (isSelected) {
                if (accent != 0) {
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                    gd.setColor(accent);
                    gd.setCornerRadius(16 * getResources().getDisplayMetrics().density);
                    tabs[i].setBackground(gd);
                } else {
                    tabs[i].setBackgroundResource(R.drawable.bg_tab_selected);
                }
                tabs[i].setTextColor(Color.WHITE);
                tabs[i].setTextSize(13);
            } else {
                tabs[i].setBackgroundResource(R.drawable.bg_tab_unselected);
                tabs[i].setTextColor(getColor(R.color.text_secondary));
            }

            if (isSelected) {
                sections[i].setVisibility(View.VISIBLE);
                sections[i].setAlpha(0f);
                sections[i].animate().alpha(1f).setDuration(200).start();
            } else {
                sections[i].setVisibility(View.GONE);
            }
        }
    }

    private TextView[] getSettingsTabs() {
        return new TextView[]{tabBasic, tabPersonalize, tabUpdates, tabAbout};
    }

    private void setupBasicSection() {
        LanguageManager languageManager = new LanguageManager(this);
        FeatureSettings fs = FeatureSettings.getInstance();

        // Keep English at the top (default language).
        // Sort all other languages alphabetically by their display name.
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

        String currentCode = languageManager.getCurrentLanguage();
        int defaultIdx = switch (currentCode) {
            case "zh", "zh-CN" -> 1;
            case "fr" -> 2;
            case "hi" -> 3;
            case "idn" -> 4;
            case "ja" -> 5;
            case "pt" -> 6;
            case "ru" -> 7;
            case "es" -> 8;
            case "tr", "tr-TR" -> 9;
            case "vi" -> 10;
            default -> 0;
        };

        TextView languageCurrent = findViewById(R.id.language_current);
        languageCurrent.setText(languageOptions[defaultIdx]);

        Spinner languageSpinner = findViewById(R.id.language_spinner);
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, languageOptions);
        langAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        languageSpinner.setAdapter(langAdapter);
        languageSpinner.setPopupBackgroundResource(R.drawable.bg_popup_menu_rounded);
        languageSpinner.setSelection(defaultIdx);
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String code = switch (position) {
                    case 1 -> "zh-CN";
                    case 2 -> "fr";
                    case 3 -> "hi";
                    case 4 -> "idn";
                    case 5 -> "ja";
                    case 6 -> "pt";
                    case 7 -> "ru";
                    case 8 -> "es";
                    case 9 -> "tr";
                    case 10 -> "vi";
                    default -> "en";
                };
                if (!code.equals(languageManager.getCurrentLanguage())) {
                    languageManager.setAppLanguage(code);
                }
                languageCurrent.setText(languageOptions[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        SwitchMaterial switchLogcat = findViewById(R.id.switch_logcat);
        SwitchMaterial switchAppLevelLog = findViewById(R.id.switch_logcat_app_level);
        switchAppLevelLog.setChecked(fs.isAppLevelLoggingEnabled());
        switchAppLevelLog.setEnabled(fs.isLogcatOverlayEnabled());
        switchAppLevelLog.setAlpha(fs.isLogcatOverlayEnabled() ? 1f : 0.5f);
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
        switchAppLevelLog.setOnCheckedChangeListener((btn, checked) -> {
            fs.setAppLevelLoggingEnabled(checked);
            if (!checked) AppLevelLogSink.teardown();
            try {
                LogcatOverlayManager mgr = LogcatOverlayManager.getInstance();
                if (mgr != null) mgr.refreshVisibility();
            } catch (Throwable ignored) {}
        });

        SwitchMaterial switchForegroundService = findViewById(R.id.switch_foreground_service);
        switchForegroundService.setChecked(fs.isForegroundServiceEnabled());
        switchForegroundService.setOnCheckedChangeListener((btn, checked) -> fs.setForegroundServiceEnabled(checked));

        SwitchMaterial switchCrashUpload = findViewById(R.id.switch_crash_upload);
        switchCrashUpload.setChecked(fs.isCrashUploadEnabled());
        switchCrashUpload.setOnCheckedChangeListener((btn, checked) -> {
            fs.setCrashUploadEnabled(checked);
            CrashReporter.refreshCrashlyticsCollection(this);
        });

        SwitchMaterial switchManagedLogin = findViewById(R.id.switch_managed_login);
        switchManagedLogin.setChecked(fs.isLauncherManagedMcLoginEnabled());
        switchManagedLogin.setOnCheckedChangeListener((btn, checked) -> fs.setLauncherManagedMcLoginEnabled(checked));
    }

    private void setupPersonalizeSection() {
        ThemeManager themeManager = new ThemeManager(this);

        View itemSystem = findViewById(R.id.theme_item_system);
        View itemLight = findViewById(R.id.theme_item_light);
        View itemAmoled = findViewById(R.id.theme_item_amoled);

        refreshThemeSelectionUI();

        if (itemSystem != null && itemLight != null && itemAmoled != null) {
            itemSystem.setOnClickListener(v -> { themeManager.setThemeMode(ThemeManager.MODE_FOLLOW_SYSTEM); });
            itemLight.setOnClickListener(v -> { themeManager.setThemeMode(ThemeManager.MODE_LIGHT); });
            itemAmoled.setOnClickListener(v -> { themeManager.setThemeMode(ThemeManager.MODE_AMOLED); });
        }

        setupColorPicker();
        setupBackgroundImagePicker();
        setupAnimatedBackgroundPicker();
        setupNavBarPositionRow();
    }

    private void setupAnimatedBackgroundPicker() {
        String[] values = {"none", "matrix", "donut", "fireworks", "wave"};
        int[] ids = {
                R.id.bg_animation_none,
                R.id.bg_animation_matrix,
                R.id.bg_animation_donut,
                R.id.bg_animation_fireworks,
                R.id.bg_animation_wave
        };
        String current = personalizationManager.getBackgroundAnimation();
        int accent = personalizationManager.getAccentColor();
        int selectedColor = accent != 0 ? accent : getColor(R.color.primary);
        for (int i = 0; i < ids.length; i++) {
            Button btn = findViewById(ids[i]);
            if (btn == null) continue;
            String value = values[i];
            boolean active = value.equals(current);
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    active ? selectedColor : getColor(R.color.surface_variant)));
            btn.setTextColor(active ? Color.WHITE : getColor(R.color.on_surface));
            btn.setOnClickListener(v -> {
                if (value.equals(personalizationManager.getBackgroundAnimation())) return;
                personalizationManager.setBackgroundAnimation(value);
                recreate();
            });
        }
    }

    private void setupNavBarPositionRow() {
        TextView current = findViewById(R.id.nav_bar_position_current);
        Spinner spinner = findViewById(R.id.nav_bar_position_spinner);
        if (current == null || spinner == null) return;

        String[] values = {"top", "bottom", "left", "right"};
        String[] labels = {
                getString(R.string.nav_position_top),
                getString(R.string.nav_position_bottom),
                getString(R.string.nav_position_left),
                getString(R.string.nav_position_right)
        };

        int selected = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(personalizationManager.getNavBarPosition())) {
                selected = i;
                break;
            }
        }
        current.setText(labels[selected]);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setPopupBackgroundResource(R.drawable.bg_popup_menu_rounded);
        spinner.setSelection(selected);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
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

    private void refreshThemeSelectionUI() {
        ThemeManager themeManager = new ThemeManager(this);
        int currentMode = themeManager.getCurrentMode();

        TextView textSystem = findViewById(R.id.theme_text_system);
        TextView textLight = findViewById(R.id.theme_text_light);
        TextView textAmoled = findViewById(R.id.theme_text_amoled);

        ImageView iconSystem = findViewById(R.id.theme_icon_system);
        ImageView iconLight = findViewById(R.id.theme_icon_light);
        ImageView iconAmoled = findViewById(R.id.theme_icon_amoled);

        int accent = personalizationManager.getAccentColor();
        int selectedColor = accent != 0 ? accent : getColor(R.color.on_surface);
        int unselectedColor = getColor(R.color.text_secondary);

        if (textSystem != null) {
            textSystem.setTypeface(null, currentMode == ThemeManager.MODE_FOLLOW_SYSTEM ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            textSystem.setTextColor(currentMode == ThemeManager.MODE_FOLLOW_SYSTEM ? selectedColor : getColor(R.color.on_surface));
        }
        if (textLight != null) {
            textLight.setTypeface(null, currentMode == ThemeManager.MODE_LIGHT ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            textLight.setTextColor(currentMode == ThemeManager.MODE_LIGHT ? selectedColor : getColor(R.color.on_surface));
        }
        if (textAmoled != null) {
            textAmoled.setTypeface(null, currentMode == ThemeManager.MODE_AMOLED ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            textAmoled.setTextColor(currentMode == ThemeManager.MODE_AMOLED ? selectedColor : getColor(R.color.on_surface));
        }

        if (iconSystem != null) iconSystem.setImageTintList(android.content.res.ColorStateList.valueOf(currentMode == ThemeManager.MODE_FOLLOW_SYSTEM ? selectedColor : unselectedColor));
        if (iconLight != null) iconLight.setImageTintList(android.content.res.ColorStateList.valueOf(currentMode == ThemeManager.MODE_LIGHT ? selectedColor : unselectedColor));
        if (iconAmoled != null) iconAmoled.setImageTintList(android.content.res.ColorStateList.valueOf(currentMode == ThemeManager.MODE_AMOLED ? selectedColor : unselectedColor));
    }

    private void setupColorPicker() {
        colorGridContainer = findViewById(R.id.color_preset_grid);
        moreColorsContainer = findViewById(R.id.color_more_grid);

        if (colorGridContainer != null && moreColorsContainer != null) {
            int currentAccent = personalizationManager.getAccentColor();
            buildColorGrid(colorGridContainer, PersonalizationManager.PRESET_COLORS, currentAccent);
            buildColorGrid(moreColorsContainer, PersonalizationManager.MORE_COLORS, currentAccent);
        }

        android.widget.EditText inputCustomColor = findViewById(R.id.input_custom_color);
        Button btnApplyColor = findViewById(R.id.btn_apply_color);
        if (inputCustomColor != null && btnApplyColor != null) {
            btnApplyColor.setOnClickListener(v -> {
                String input = inputCustomColor.getText().toString().trim();
                try {
                    int color;
                    if (input.startsWith("#")) {
                        color = Color.parseColor(input);
                    } else if (input.contains(",")) {
                        String[] parts = input.split(",");
                        if (parts.length == 3) {
                            color = Color.rgb(Integer.parseInt(parts[0].trim()),
                                    Integer.parseInt(parts[1].trim()),
                                    Integer.parseInt(parts[2].trim()));
                        } else {
                            throw new IllegalArgumentException("Invalid RGB format");
                        }
                    } else {
                        color = Color.parseColor("#" + input);
                    }
                    personalizationManager.setAccentColor(color);
                    refreshColorPickerInPlace();
                } catch (Exception e) {
                    Toast.makeText(this, R.string.invalid_color_format, Toast.LENGTH_SHORT).show();
                }
            });
        }

        Button btnWallpaperColor = findViewById(R.id.btn_wallpaper_color);
        if (btnWallpaperColor != null) {
            btnWallpaperColor.setOnClickListener(v -> applyWallpaperAccentColor());
        }
    }

    private void applyWallpaperAccentColor() {
        int color = WallpaperColorExtractor.extract(this);
        if (color != 0) {
            personalizationManager.setAccentColor(color);
            refreshColorPickerInPlace();
            Toast.makeText(this, R.string.wallpaper_color_applied, Toast.LENGTH_SHORT).show();
            return;
        }

        // Android 11 and below need READ_EXTERNAL_STORAGE to read the wallpaper bitmap.
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

    private void buildColorGrid(LinearLayout container, int[] colors, int selectedColor) {
        container.removeAllViews();

        float density = getResources().getDisplayMetrics().density;
        int circleSize = (int) (32 * density);
        int margin = (int) (4 * density);
        int checkSize = (int) (14 * density);

        int columns = 15;
        int index = 0;
        while (index < colors.length) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            for (int col = 0; col < columns && index < colors.length; col++, index++) {
                int color = colors[index];

                FrameLayout wrapper = new FrameLayout(this);
                LinearLayout.LayoutParams wrapParams = new LinearLayout.LayoutParams(circleSize, circleSize);
                wrapParams.setMargins(margin, margin, margin, margin);
                wrapper.setLayoutParams(wrapParams);

                View circle = new View(this);
                circle.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                GradientDrawable circleDrawable = new GradientDrawable();
                circleDrawable.setShape(GradientDrawable.OVAL);
                circleDrawable.setColor(color);
                if (color == selectedColor) {
                    circleDrawable.setStroke((int) (2 * density), Color.WHITE);
                }
                circle.setBackground(circleDrawable);
                wrapper.addView(circle);

                if (color == selectedColor) {
                    ImageView check = new ImageView(this);
                    FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(checkSize, checkSize);
                    checkParams.gravity = Gravity.CENTER;
                    check.setLayoutParams(checkParams);
                    check.setImageResource(R.drawable.ic_check);
                    check.setColorFilter(Color.WHITE);
                    wrapper.addView(check);
                }

                wrapper.setClickable(true);
                wrapper.setFocusable(true);
                final int finalColor = color;
                wrapper.setOnClickListener(v -> {
                    personalizationManager.setAccentColor(finalColor);
                    refreshColorPickerInPlace();
                });
                DynamicAnim.applyPressScale(wrapper);

                row.addView(wrapper);
            }

            container.addView(row);
        }

    }

    private void refreshColorPickerInPlace() {
        setupColorPicker();
        PersonalizationManager pm = new PersonalizationManager(this);
        int accent = pm.getAccentColor();
        
        pm.applyToActivity(this);

        refreshThemeSelectionUI();
        
        TextView[] tabs = getSettingsTabs();
        selectTab(tabs[selectedTabIndex]);
        
        View settingsTitle = findViewById(R.id.settings_title);
        if (settingsTitle instanceof TextView && accent != 0) {
            ((TextView) settingsTitle).setTextColor(accent);
        }
        
        Button btnSelectImage = findViewById(R.id.btn_select_bg_image);
        if (btnSelectImage != null && accent != 0) {
            btnSelectImage.setBackgroundTintList(ColorStateList.valueOf(accent));
            btnSelectImage.setTextColor(Color.WHITE);
        }

        Button btnWallpaperColor = findViewById(R.id.btn_wallpaper_color);
        if (btnWallpaperColor != null && accent != 0) {
            btnWallpaperColor.setBackgroundTintList(ColorStateList.valueOf(accent));
            btnWallpaperColor.setTextColor(Color.WHITE);
        }
        
        Button btnCheckUpdate = findViewById(R.id.btn_check_update);
        if (btnCheckUpdate != null && accent != 0) {
            btnCheckUpdate.setBackgroundTintList(ColorStateList.valueOf(accent));
            btnCheckUpdate.setTextColor(Color.WHITE);
        }

        Button btnCrashLogs = findViewById(R.id.btn_crash_logs);
        if (btnCrashLogs != null && accent != 0) {
            btnCrashLogs.setBackgroundTintList(ColorStateList.valueOf(accent));
            btnCrashLogs.setTextColor(Color.WHITE);
        }

        Button btnInstallMethod = findViewById(R.id.btn_install_method);
        if (btnInstallMethod != null && accent != 0) {
            btnInstallMethod.setBackgroundTintList(ColorStateList.valueOf(accent));
            btnInstallMethod.setTextColor(Color.WHITE);
        }

        Button btnUpdatePreloaderSigs = findViewById(R.id.btn_update_preloader_sigs);
        if (btnUpdatePreloaderSigs != null && accent != 0) {
            btnUpdatePreloaderSigs.setBackgroundTintList(ColorStateList.valueOf(accent));
            btnUpdatePreloaderSigs.setTextColor(Color.WHITE);
        }
        
        SwitchMaterial switchLogcat = findViewById(R.id.switch_logcat);
        if (switchLogcat != null && accent != 0) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            switchLogcat.setThumbTintList(new ColorStateList(states, new int[]{accent, 0xFFAAAAAA}));
            int trackChecked = Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent));
            switchLogcat.setTrackTintList(new ColorStateList(states, new int[]{trackChecked, 0xFF555555}));
        }

        SwitchMaterial switchAppLevelLog = findViewById(R.id.switch_logcat_app_level);
        if (switchAppLevelLog != null && accent != 0) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            switchAppLevelLog.setThumbTintList(new ColorStateList(states, new int[]{accent, 0xFFAAAAAA}));
            int trackChecked = Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent));
            switchAppLevelLog.setTrackTintList(new ColorStateList(states, new int[]{trackChecked, 0xFF555555}));
        }
        
        SwitchMaterial switchForegroundService = findViewById(R.id.switch_foreground_service);
        if (switchForegroundService != null && accent != 0) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            switchForegroundService.setThumbTintList(new ColorStateList(states, new int[]{accent, 0xFFAAAAAA}));
            int trackChecked = Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent));
            switchForegroundService.setTrackTintList(new ColorStateList(states, new int[]{trackChecked, 0xFF555555}));
        }

        SwitchMaterial switchManagedLogin = findViewById(R.id.switch_managed_login);
        if (switchManagedLogin != null && accent != 0) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            switchManagedLogin.setThumbTintList(new ColorStateList(states, new int[]{accent, 0xFFAAAAAA}));
            int trackChecked = Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent));
            switchManagedLogin.setTrackTintList(new ColorStateList(states, new int[]{trackChecked, 0xFF555555}));
        }

        SwitchMaterial switchCrashUpload = findViewById(R.id.switch_crash_upload);
        if (switchCrashUpload != null && accent != 0) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            switchCrashUpload.setThumbTintList(new ColorStateList(states, new int[]{accent, 0xFFAAAAAA}));
            int trackChecked = Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent));
            switchCrashUpload.setTrackTintList(new ColorStateList(states, new int[]{trackChecked, 0xFF555555}));
        }
        
        TextView navAppName = findViewById(R.id.nav_app_name);
        if (navAppName != null && accent != 0) {
            pm.applySolidAccentText(navAppName, accent);
        }
        
        Button navSignInBtn = findViewById(R.id.nav_sign_in_button);
        if (navSignInBtn != null && accent != 0) {
            navSignInBtn.setBackgroundTintList(ColorStateList.valueOf(accent));
            navSignInBtn.setTextColor(Color.WHITE);
        }
        
        int[] navTabIds = {R.id.nav_tab_launch, R.id.nav_tab_instances, R.id.nav_tab_about, R.id.nav_tab_settings};
        for (int id : navTabIds) {
            TextView navTab = findViewById(id);
            if (navTab != null && id == R.id.nav_tab_settings && accent != 0) {
                navTab.setTextColor(accent);
                navTab.setTypeface(navTab.getTypeface(), android.graphics.Typeface.BOLD);
                androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(navTab, ColorStateList.valueOf(accent));
            }
        }
    }

    private void setupBackgroundImagePicker() {
        bgImageStatus = findViewById(R.id.bg_image_status);
        bgImagePreview = findViewById(R.id.bg_image_preview);
        Button btnSelectImage = findViewById(R.id.btn_select_bg_image);
        Button btnClearImage = findViewById(R.id.btn_clear_bg_image);

        if (btnSelectImage == null) return;

        setupBackgroundImageControls();
        updateBgImageUI();
        setBackgroundImportState(LauncherBackgroundController.isImporting());

        btnSelectImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            bgImagePickerLauncher.launch(intent);
        });

        if (btnClearImage != null) {
            btnClearImage.setOnClickListener(v -> {
                personalizationManager.clearBackgroundImage();
                updateBgImageUI();
                recreate();
            });
        }
    }

    private void setupBackgroundImageControls() {
        SeekBar blurSeek = findViewById(R.id.seek_bg_image_blur);
        SeekBar brightnessSeek = findViewById(R.id.seek_bg_image_brightness);
        bgImageBlurValue = findViewById(R.id.bg_image_blur_value);
        bgImageBrightnessValue = findViewById(R.id.bg_image_brightness_value);

        if (blurSeek != null) {
            blurSeek.setMax(PersonalizationManager.BG_BLUR_MAX);
            blurSeek.setProgress(personalizationManager.getBackgroundImageBlur());
            updateBgImageBlurValue(blurSeek.getProgress());
            blurSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    updateBgImageBlurValue(progress);
                    if (!fromUser) return;
                    personalizationManager.setBackgroundImageBlur(progress);
                    if (personalizationManager.supportsRealtimeBackgroundBlur()) {
                        refreshBackgroundImageEffects();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    refreshBackgroundImageEffects();
                }
            });
        }

        if (brightnessSeek != null) {
            brightnessSeek.setMin(PersonalizationManager.BG_BRIGHTNESS_MIN);
            brightnessSeek.setMax(PersonalizationManager.BG_BRIGHTNESS_MAX);
            brightnessSeek.setProgress(personalizationManager.getBackgroundImageBrightness());
            updateBgImageBrightnessValue(brightnessSeek.getProgress());
            brightnessSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    updateBgImageBrightnessValue(progress);
                    if (!fromUser) return;
                    personalizationManager.setBackgroundImageBrightness(progress);
                    refreshBackgroundImageColorEffects();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }
    }

    private void updateBgImageBlurValue(int blurRadius) {
        if (bgImageBlurValue != null) {
            bgImageBlurValue.setText(getString(R.string.bg_image_blur_value, blurRadius));
        }
    }

    private void updateBgImageBrightnessValue(int brightnessPercent) {
        if (bgImageBrightnessValue != null) {
            bgImageBrightnessValue.setText(getString(R.string.bg_image_brightness_value, brightnessPercent));
        }
    }

    private void refreshBackgroundImageEffects() {
        personalizationManager.refreshBackgroundEffects(this);
        if (bgImagePreview != null) {
            personalizationManager.refreshBackgroundImageView(bgImagePreview);
        }
    }

    private void refreshBackgroundImageColorEffects() {
        personalizationManager.refreshBackgroundColorEffects(this);
        if (bgImagePreview != null) {
            personalizationManager.applyBackgroundImageEffects(bgImagePreview);
        }
    }

    private void updateBgImageUI() {
        if (bgImageStatus == null) return;
        boolean hasBackgroundImage = personalizationManager.hasBackgroundImage();
        SeekBar blurSeek = findViewById(R.id.seek_bg_image_blur);
        boolean canBlur = personalizationManager.canBlurBackground();
        if (blurSeek != null) blurSeek.setEnabled(canBlur);
        if (!canBlur && bgImageBlurValue != null) bgImageBlurValue.setText(R.string.bg_media_blur_unavailable);
        View effectControls = findViewById(R.id.bg_image_effect_controls);
        if (effectControls != null) {
            effectControls.setVisibility(hasBackgroundImage ? View.VISIBLE : View.GONE);
        }

        if (hasBackgroundImage) {
            bgImageStatus.setText(R.string.bg_image_selected);
            if (bgImagePreview != null) {
                if (personalizationManager.applyBackgroundImageToView(bgImagePreview)) {
                    bgImagePreview.setVisibility(View.VISIBLE);
                }
            }
            View btnClear = findViewById(R.id.btn_clear_bg_image);
            if (btnClear != null) btnClear.setVisibility(View.VISIBLE);
        } else {
            bgImageStatus.setText(R.string.bg_media_none);
            if (bgImagePreview != null) {
                bgImagePreview.setImageDrawable(null);
                bgImagePreview.setVisibility(View.GONE);
            }
            View btnClear = findViewById(R.id.btn_clear_bg_image);
            if (btnClear != null) btnClear.setVisibility(View.GONE);
        }
    }

    private void setBackgroundImportState(boolean importing) {
        View select = findViewById(R.id.btn_select_bg_image);
        View clear = findViewById(R.id.btn_clear_bg_image);
        if (select != null) select.setEnabled(!importing && !LauncherBackgroundController.isMinecraftBlocked());
        if (clear != null) clear.setEnabled(!importing);
        if (importing && bgImageStatus != null) bgImageStatus.setText(R.string.bg_media_importing);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (personalizationManager != null) {
            updateBgImageUI();
            setBackgroundImportState(LauncherBackgroundController.isImporting());
        }
    }

    private void setupUpdatesSection() {
        try {
            String localVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            TextView versionText = findViewById(R.id.version_text);
            versionText.setText(getString(R.string.version_prefix) + localVersion);
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        Button btnCheckUpdate = findViewById(R.id.btn_check_update);
        btnCheckUpdate.setOnClickListener(v -> handleUpdateButtonClick());

        Button btnCrashLogs = findViewById(R.id.btn_crash_logs);
        btnCrashLogs.setOnClickListener(v -> showCrashLogsDialog());

        Button btnInstallMethod = findViewById(R.id.btn_install_method);
        if (btnInstallMethod != null) {
            btnInstallMethod.setText(getString(R.string.install_method_button,
                    getString(GithubReleaseUpdater.getInstallMethodLabel(this))));
            btnInstallMethod.setOnClickListener(v -> showInstallMethodDialog(btnInstallMethod));
        }

        preloaderSigsLastUpdateText = findViewById(R.id.preloader_sigs_last_update);
        preloaderSigsUpdateButton = findViewById(R.id.btn_update_preloader_sigs);
        refreshPreloaderSigsLastUpdateUi();
        if (preloaderSigsUpdateButton != null) {
            preloaderSigsUpdateButton.setOnClickListener(v -> handlePreloaderSigsUpdateClick());
        }
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

    private void showInstallMethodDialog(Button button) {
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
                    button.setText(getString(R.string.install_method_button,
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
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sang765/LeviDroid-Modder"))));

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

    private void setupNavBar() {
        setActiveNavTab(R.id.nav_tab_settings);
        findViewById(R.id.nav_tab_settings).setOnClickListener(v -> {});
    }

}
