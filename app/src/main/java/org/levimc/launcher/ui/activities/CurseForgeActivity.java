package org.levimc.launcher.ui.activities;

import android.content.Context;
import android.content.Intent;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import org.levimc.launcher.R;
import org.levimc.launcher.core.curseforge.CurseForgeClient;
import org.levimc.launcher.core.curseforge.models.Content;
import org.levimc.launcher.core.curseforge.models.ContentCategory;
import org.levimc.launcher.core.curseforge.models.ContentGameVersion;
import org.levimc.launcher.core.discord.DiscordRpcManager;
import org.levimc.launcher.core.curseforge.models.ContentSearchResponse;
import org.levimc.launcher.ui.adapter.CurseForgeContentAdapter;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.util.UIHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class CurseForgeActivity extends BaseActivity {

    private static final int PAGE_SIZE = 20;
    private static final int VISIBLE_VERSIONS = 12;

    private EditText searchBox;
    private TextView btnSort;
    private MaterialButton btnFilters;
    private RecyclerView recyclerView;
    private ProgressBar loadingProgress;
    private View emptyState;
    private TextView emptyMessage;
    private View retryButton;
    private CurseForgeContentAdapter adapter;
    private CurseForgeClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int currentPage = 1;
    private int totalPages = 1;
    private int requestSequence;
    private int filterSequence;
    private SortOption currentSort;

    private final List<ContentCategory> allCategories = new ArrayList<>();
    private final List<String> gameVersions = new ArrayList<>();
    private int browseClassId = 0;
    private final Set<Integer> appliedCatIds = new LinkedHashSet<>();
    private final Set<String> appliedVersions = new LinkedHashSet<>();
    private LinearLayout filterSectionsHost;
    private Dialog openFilterDialog;
    private FilterState openFilterState;

    private static final int[][] BASE_CLASSES = {
            {4984, R.string.cf_browse_addons},
            {6913, R.string.cf_browse_maps},
            {6929, R.string.cf_browse_texture_packs},
            {6940, R.string.cf_browse_scripts},
            {6925, R.string.cf_browse_skins},
    };

    private static class SortOption {
        final String name;
        final String field;
        final String order;

        SortOption(String name, String field, String order) {
            this.name = name;
            this.field = field;
            this.order = order;
        }
    }

    private final List<SortOption> sortOptions = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_curseforge);
        client = CurseForgeClient.getInstance();
        setupData();
        initViews();
        fetchFilterData();
        loadContent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DiscordRpcManager.getInstance().reportScreen(DiscordRpcManager.SCREEN_CURSEFORGE, R.string.rpc_curseforge);
    }

    @Override
    protected void onPause() {
        super.onPause();
        DiscordRpcManager.getInstance().clearScreen(DiscordRpcManager.SCREEN_CURSEFORGE);
    }

    private void setupData() {
        sortOptions.add(new SortOption(getString(R.string.catalog_cf_sort_popular), CurseForgeClient.SORT_POPULARITY, "desc"));
        sortOptions.add(new SortOption(getString(R.string.catalog_cf_sort_downloads), CurseForgeClient.SORT_TOTAL_DOWNLOADS, "desc"));
        sortOptions.add(new SortOption(getString(R.string.catalog_cf_sort_updated), CurseForgeClient.SORT_LAST_UPDATED, "desc"));
        sortOptions.add(new SortOption(getString(R.string.catalog_cf_sort_name), CurseForgeClient.SORT_NAME, "asc"));
    }

    private void fetchFilterData() {
        final int filterRequestId = ++filterSequence;
        client.getCategories(new CurseForgeClient.CurseForgeCallback<List<ContentCategory>>() {
            @Override
            public void onSuccess(List<ContentCategory> result) {
                handler.post(() -> {
                    if (isFinishing() || isDestroyed() || filterRequestId != filterSequence) return;
                    allCategories.clear();
                    if (result != null) allCategories.addAll(result);
                    rerenderOpenFilterDialog();
                });
            }

            @Override
            public void onError(Throwable error) {
                handler.post(() -> {
                    if (isFinishing() || isDestroyed() || filterRequestId != filterSequence) return;
                    allCategories.clear();
                    rerenderOpenFilterDialog();
                });
            }
        });
        client.getGameVersions(new CurseForgeClient.CurseForgeCallback<List<ContentGameVersion>>() {
            @Override
            public void onSuccess(List<ContentGameVersion> result) {
                handler.post(() -> {
                    if (isFinishing() || isDestroyed() || filterRequestId != filterSequence) return;
                    gameVersions.clear();
                    if (result != null && !result.isEmpty() && result.get(0).versions != null) {
                        gameVersions.addAll(result.get(0).versions);
                        Collections.sort(gameVersions, (a, b) -> compareVersions(b, a));
                    }
                    rerenderOpenFilterDialog();
                });
            }

            @Override
            public void onError(Throwable error) {
                handler.post(() -> {
                    if (isFinishing() || isDestroyed() || filterRequestId != filterSequence) return;
                    gameVersions.clear();
                    rerenderOpenFilterDialog();
                });
            }
        });
    }

    private void rerenderOpenFilterDialog() {
        if (openFilterDialog == null || !openFilterDialog.isShowing() || filterSectionsHost == null || openFilterState == null) {
            return;
        }
        renderFilterSections(filterSectionsHost, openFilterState, openFilterDialog);
    }

    private int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? parsePart(pa[i]) : 0;
            int vb = i < pb.length ? parsePart(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return a.compareTo(b);
    }

    private int parsePart(String part) {
        int digits = 0;
        while (digits < part.length() && Character.isDigit(part.charAt(digits))) digits++;
        if (digits == 0) return 0;
        try {
            return Integer.parseInt(part.substring(0, digits));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private void initViews() {
        searchBox = findViewById(R.id.search_box);
        btnFilters = findViewById(R.id.btn_filters);
        btnSort = findViewById(R.id.btn_sort);
        recyclerView = findViewById(R.id.mods_recycler);
        loadingProgress = findViewById(R.id.loading_progress);
        emptyState = findViewById(R.id.empty_state);
        emptyMessage = findViewById(R.id.empty_message);
        retryButton = findViewById(R.id.cf_retry);

        adapter = new CurseForgeContentAdapter(this::onContentClick, new CurseForgeContentAdapter.OnPageChangeListener() {
            @Override
            public void onNextPage() {
                if (currentPage < totalPages) {
                    currentPage++;
                    loadContent();
                }
            }

            @Override
            public void onPrevPage() {
                if (currentPage > 1) {
                    currentPage--;
                    loadContent();
                }
            }
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setItemAnimator(null);
        recyclerView.setAdapter(adapter);

        searchBox.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                currentPage = 1;
                loadContent();
                UIHelper.hideKeyboard(this);
                searchBox.clearFocus();
                return true;
            }
            return false;
        });

        retryButton.setOnClickListener(v -> loadContent());

        updateFilterButtonLabel();

        btnFilters.setOnClickListener(view -> showFilterDialog());

        currentSort = sortOptions.get(0);
        btnSort.setText(currentSort.name);
        btnSort.setOnClickListener(view -> {
            PopupMenu menu = new PopupMenu(this, view);
            for (int i = 0; i < sortOptions.size(); i++) {
                menu.getMenu().add(0, i, i, sortOptions.get(i).name);
            }
            menu.setOnMenuItemClickListener(item -> {
                currentSort = sortOptions.get(item.getItemId());
                btnSort.setText(currentSort.name);
                currentPage = 1;
                loadContent();
                return true;
            });
            menu.show();
        });
    }

    private void showEmpty(CharSequence message, boolean retry) {
        emptyMessage.setText(message);
        retryButton.setVisibility(retry ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(View.VISIBLE);
    }

    private void loadContent() {
        String query = searchBox.getText().toString().trim();
        DiscordRpcManager.getInstance().reportScreen(DiscordRpcManager.SCREEN_CURSEFORGE, R.string.rpc_curseforge,
                query.isEmpty() ? null : query);

        SortOption sort = currentSort;
        int request = ++requestSequence;
        int requestedPage = currentPage;
        loadingProgress.setVisibility(View.VISIBLE);
        emptyState.setVisibility(View.GONE);
        recyclerView.setVisibility(adapter.getItemCount() > 0 ? View.VISIBLE : View.INVISIBLE);

        client.searchContent(query, browseClassId, new ArrayList<>(appliedCatIds), new ArrayList<>(appliedVersions),
                (requestedPage - 1) * PAGE_SIZE, PAGE_SIZE,
                sort != null ? sort.field : CurseForgeClient.SORT_POPULARITY,
                sort != null ? sort.order : "desc",
                new CurseForgeClient.CurseForgeCallback<ContentSearchResponse>() {
                    @Override
                    public void onSuccess(ContentSearchResponse result) {
                        handler.post(() -> {
                            if (isFinishing() || isDestroyed() || request != requestSequence) return;
                            loadingProgress.setVisibility(View.GONE);
                            if (result != null && result.data != null) {
                                if (result.pagination != null && result.pagination.totalCount > 0) {
                                    totalPages = Math.max(1, (int) Math.ceil((double) result.pagination.totalCount / PAGE_SIZE));
                                } else {
                                    totalPages = result.data.size() < PAGE_SIZE ? requestedPage : requestedPage + 1;
                                }
                                adapter.setContents(result.data, requestedPage, totalPages);
                                boolean empty = result.data.isEmpty();
                                if (empty) showEmpty(getString(R.string.no_mods_found), false);
                                recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
                                if (!empty) recyclerView.scrollToPosition(0);
                            } else {
                                adapter.setContents(Collections.emptyList(), 1, 1);
                                recyclerView.setVisibility(View.GONE);
                                showEmpty(getString(R.string.no_mods_found), false);
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable error) {
                        handler.post(() -> {
                            if (isFinishing() || isDestroyed() || request != requestSequence) return;
                            loadingProgress.setVisibility(View.GONE);
                            if (adapter.getItemCount() == 0) {
                                recyclerView.setVisibility(View.GONE);
                                showEmpty(getString(R.string.error_message_format, error.getMessage()), true);
                            } else {
                                recyclerView.setVisibility(View.VISIBLE);
                                android.widget.Toast.makeText(CurseForgeActivity.this,
                                        getString(R.string.error_message_format, error.getMessage()),
                                        android.widget.Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
    }

    private void showFilterDialog() {
        if (allCategories.isEmpty() || gameVersions.isEmpty()) {
            fetchFilterData();
        }
        final FilterState state = new FilterState();
        state.classId = browseClassId;
        state.cats.addAll(appliedCatIds);
        state.versions.addAll(appliedVersions);

        View content = getLayoutInflater().inflate(R.layout.dialog_cf_filters, null);
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(content);

        LinearLayout sections = content.findViewById(R.id.filter_sections);
        content.findViewById(R.id.filter_close).setOnClickListener(v -> dialog.dismiss());
        MaterialButton apply = content.findViewById(R.id.btn_apply_filters);
        MaterialButton clear = content.findViewById(R.id.btn_clear_filters);

        filterSectionsHost = sections;
        openFilterState = state;
        openFilterDialog = dialog;
        dialog.setOnDismissListener(d -> {
            filterSectionsHost = null;
            openFilterState = null;
            openFilterDialog = null;
        });

        clear.setOnClickListener(v -> {
            state.classId = 0;
            state.cats.clear();
            state.versions.clear();
            state.expandedVersions = false;
            renderFilterSections(sections, state, dialog);
        });
        apply.setOnClickListener(v -> {
            browseClassId = state.classId;
            appliedCatIds.clear();
            appliedCatIds.addAll(state.cats);
            appliedVersions.clear();
            appliedVersions.addAll(state.versions);
            dialog.dismiss();
            updateFilterButtonLabel();
            currentPage = 1;
            loadContent();
        });

        renderFilterSections(sections, state, dialog);
        dialog.show();
    }

    private void renderFilterSections(LinearLayout sections, FilterState state, Dialog dialog) {
        Context context = this;
        PersonalizationManager personalization = new PersonalizationManager(context);
        int accentColor = personalization.hasCustomAccent()
                ? personalization.getAccentColor()
                : ContextCompat.getColor(context, R.color.primary);
        ColorStateList tint = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{accentColor, ContextCompat.getColor(context, R.color.divider)});
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        sections.removeAllViews();
        addSectionHeader(sections, getString(R.string.cf_browse_by));
        java.util.LinkedHashMap<Integer, String> roots = new java.util.LinkedHashMap<>();
        for (int[] base : BASE_CLASSES) {
            roots.put(base[0], getString(base[1]));
        }
        for (ContentCategory category : allCategories) {
            if (category.parentCategoryId == 0 && category.classId != 0 && category.name != null && !roots.containsKey(category.classId)) {
                roots.put(category.classId, category.name);
            }
        }
        RadioGroup classGroup = new RadioGroup(context);
        classGroup.setOrientation(LinearLayout.VERTICAL);
        addClassRow(classGroup, 0, getString(R.string.filter_all), state, tint);
        for (java.util.Map.Entry<Integer, String> entry : roots.entrySet()) {
            addClassRow(classGroup, entry.getKey(), entry.getValue(), state, tint);
        }
        classGroup.setOnCheckedChangeListener((group, checkedId) -> {
            View chosen = group.findViewById(checkedId);
            int classId = chosen != null && chosen.getTag() != null ? (int) chosen.getTag() : 0;
            if (classId != state.classId) {
                state.classId = classId;
                state.cats.clear();
                state.expandedVersions = false;
                renderFilterSections(sections, state, dialog);
            }
        });
        sections.addView(classGroup, rowParams);

        if (state.classId > 0) {
            addSectionHeader(sections, getString(R.string.cf_categories));
            List<ContentCategory> children = new ArrayList<>();
            for (ContentCategory category : allCategories) {
                if (category.classId == state.classId && category.id != state.classId) {
                    children.add(category);
                }
            }
            Collections.sort(children, (x, y) -> {
                String an = x != null ? x.name : null;
                String bn = y != null ? y.name : null;
                if (an == null && bn == null) return 0;
                if (an == null) return 1;
                if (bn == null) return -1;
                return an.compareToIgnoreCase(bn);
            });
            if (children.isEmpty()) {
                addSectionNote(sections, getString(R.string.cf_categories_unavailable));
            }
            for (ContentCategory category : children) {
                CheckBox row = new CheckBox(context);
                styleChoiceRow(row, tint);
                row.setText(category.name);
                row.setChecked(state.cats.contains(category.id));
                row.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) {
                        state.cats.add(category.id);
                    } else {
                        state.cats.remove(category.id);
                    }
                });
                sections.addView(row, rowParams);
            }
        }

        if (!gameVersions.isEmpty()) {
            addSectionHeader(sections, getString(R.string.cf_game_versions));
            int limit = state.expandedVersions ? gameVersions.size() : Math.min(VISIBLE_VERSIONS, gameVersions.size());
            for (int i = 0; i < limit; i++) {
                String version = gameVersions.get(i);
                CheckBox row = new CheckBox(context);
                styleChoiceRow(row, tint);
                row.setText(version);
                row.setChecked(state.versions.contains(version));
                row.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) {
                        state.versions.add(version);
                    } else {
                        state.versions.remove(version);
                    }
                });
                sections.addView(row, rowParams);
            }
            if (gameVersions.size() > VISIBLE_VERSIONS) {
                TextView toggle = new TextView(context);
                toggle.setText(state.expandedVersions ? R.string.cf_show_less : R.string.cf_show_all);
                toggle.setTextColor(accentColor);
                toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                toggle.setPadding(0, dp(8), 0, dp(8));
                toggle.setOnClickListener(v -> {
                    state.expandedVersions = !state.expandedVersions;
                    renderFilterSections(sections, state, dialog);
                });
                sections.addView(toggle, rowParams);
            }
        }
    }

    private void addClassRow(RadioGroup group, int classId, String name, FilterState state, ColorStateList tint) {
        RadioButton row = new RadioButton(this);
        styleChoiceRow(row, tint);
        row.setText(name);
        row.setTag(classId);
        row.setChecked(state.classId == classId);
        group.addView(row, new RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void styleChoiceRow(CompoundButton row, ColorStateList tint) {
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        row.setTextColor(ContextCompat.getColor(this, R.color.on_surface));
        row.setButtonTintList(tint);
        row.setPadding(0, dp(9), 0, dp(9));
    }

    private void addSectionHeader(LinearLayout parent, String title) {
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(ContextCompat.getColor(this, R.color.on_surface));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(14);
        params.bottomMargin = dp(2);
        parent.addView(label, params);
    }

    private void addSectionNote(LinearLayout parent, String text) {
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        note.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        parent.addView(note, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void updateFilterButtonLabel() {
        int count = (browseClassId > 0 ? 1 : 0) + appliedCatIds.size() + appliedVersions.size();
        btnFilters.setText(count > 0 ? getString(R.string.cf_filter_count, count) : getString(R.string.cf_filters));
    }

    private static final class FilterState {
        int classId;
        final Set<Integer> cats = new LinkedHashSet<>();
        final Set<String> versions = new LinkedHashSet<>();
        boolean expandedVersions;
    }

    private void onContentClick(Content content) {
        Intent intent = new Intent(this, ContentDetailsActivity.class);
        intent.putExtra(ContentDetailsActivity.EXTRA_CONTENT, content);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        requestSequence++;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
