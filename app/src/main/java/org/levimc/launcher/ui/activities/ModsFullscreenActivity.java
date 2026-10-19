package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.core.view.ViewCompat;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.widget.NestedScrollView;
import android.graphics.Canvas;
import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.FileHandler;
import org.levimc.launcher.core.mods.GitHubModInstaller;
import org.levimc.launcher.core.mods.GitHubModRepository;
import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.core.mods.inbuilt.manager.InbuiltModManager;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.adapter.ModsAdapter;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.views.MainViewModel;
import org.levimc.launcher.ui.views.MainViewModelFactory;
import org.levimc.launcher.util.GithubReleaseUpdater;
import org.levimc.launcher.util.StorageAccess;
import org.levimc.launcher.util.PersonalizationManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.File;

public class ModsFullscreenActivity extends BaseActivity {

    private RecyclerView modsRecycler;
    private ModsAdapter modsAdapter;
    private MainViewModel viewModel;
    private TextView totalModsCount;
    private TextView enabledModsCount;
    private TextView totalSizeCount;
    private String selectedVersionCode;
    private ActivityResultLauncher<Intent> pickModLauncher;
    private FileHandler fileHandler;
    private InbuiltModManager inbuiltModManager;
    private int lastModsCount = -1;
    private GitHubModInstaller githubModInstaller;
    private boolean githubUpdateCheckRunning;
    private boolean githubBatchUpdating;
    private int githubBatchRemaining;
    private String lastShownUpdateSignature = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mods_fullscreen);

        View root = findViewById(android.R.id.content);
        if (root != null) {
            DynamicAnim.applyPressScaleRecursively(root);
        }

        inbuiltModManager = InbuiltModManager.getInstance(this);
        setupViews();
        setupViewModel();
        setupRecyclerView();
        fileHandler = new FileHandler(this, viewModel, VersionManager.get(this));
        githubModInstaller = new GitHubModInstaller(this, fileHandler);

        pickModLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        for (Uri uri : StorageAccess.selectedUris(result.getData())) {
                            StorageAccess.retainReadPermission(this, result.getData(), uri);
                        }
                        fileHandler.processIncomingFilesWithConfirmation(
                                result.getData(), createImportCallback(), true);
                    }
                }
        );
    }

    private void setupViews() {
        Button addModButton = findViewById(R.id.add_mod_fullscreen_button);
        addModButton.setVisibility(View.VISIBLE);
        addModButton.setOnClickListener(v -> showAddModDialog());
        DynamicAnim.applyPressScale(addModButton);

        Button modMenuButton = findViewById(R.id.mod_menu_button);
        boolean isMenuEnabled = inbuiltModManager.isModMenuEnabled();
        modMenuButton.setText(getString(R.string.mod_menu) + ": " + (isMenuEnabled ? "ON" : "OFF"));
        modMenuButton.setOnClickListener(v -> {
            boolean current = inbuiltModManager.isModMenuEnabled();
            inbuiltModManager.setModMenuEnabled(!current);
            modMenuButton.setText(getString(R.string.mod_menu) + ": " + (!current ? "ON" : "OFF"));
            Toast.makeText(this, !current ? R.string.mod_menu_enabled : R.string.mod_menu_disabled, Toast.LENGTH_SHORT).show();
        });
        DynamicAnim.applyPressScale(modMenuButton);

        Button externalModsButton = findViewById(R.id.external_mods_button);
        externalModsButton.setOnClickListener(v -> startActivity(
                new Intent(this, ExternalModsActivity.class),
                ActivityOptionsCompat.makeCustomAnimation(this, R.anim.fade_in, R.anim.fade_out).toBundle()));
        DynamicAnim.applyPressScale(externalModsButton);

        totalModsCount = findViewById(R.id.total_mods_count);
        enabledModsCount = findViewById(R.id.enabled_mods_count);
        totalSizeCount = findViewById(R.id.total_size_count);

        PersonalizationManager personalizationManager = new PersonalizationManager(this);
        View root = findViewById(android.R.id.content);
        if (root != null) {
            personalizationManager.applyAccentToView(root, this);
        }
    }

    private void startFilePicker() {
        pickModLauncher.launch(StorageAccess.downloadsPicker(true));
    }

    private FileHandler.FileOperationCallback createImportCallback() {
        return new FileHandler.FileOperationCallback() {
            @Override
            public void onSuccess(int processedFiles) {
                Toast.makeText(ModsFullscreenActivity.this,
                        getString(R.string.files_processed, processedFiles), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String errorMessage) {
                Toast.makeText(ModsFullscreenActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onProgressUpdate(int progress) {
            }
        };
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this, new MainViewModelFactory(getApplication())).get(MainViewModel.class);

        org.levimc.launcher.core.versions.GameVersion selectedVersion = VersionManager.get(this).getSelectedVersion();
        if (selectedVersion != null) {
            viewModel.setCurrentVersion(selectedVersion);
        }
        selectedVersionCode = selectedVersion != null ? selectedVersion.versionCode : null;

        viewModel.getModsLiveData().observe(this, this::updateModsUI);
    }

    private void setupRecyclerView() {
        modsRecycler = findViewById(R.id.mods_recycler_fullscreen);
        modsAdapter = new ModsAdapter(new ArrayList<>());
        modsAdapter.setCurrentVersionCode(selectedVersionCode);
        modsRecycler.setLayoutManager(new LinearLayoutManager(this));
        modsRecycler.setAdapter(modsAdapter);
        if (modsRecycler.getItemAnimator() instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) modsRecycler.getItemAnimator()).setSupportsChangeAnimations(false);
        }

        modsRecycler.post(() -> DynamicAnim.staggerRecyclerChildren(modsRecycler));

        modsAdapter.setOnModClickListener((mod, position, sharedView) -> {
            Intent intent = new Intent(this, ModDetailActivity.class);
            intent.putExtra("mod_filename", mod.getId());
            intent.putExtra("mod_position", position);
            startActivity(intent);
        });

        modsAdapter.setOnModEnableChangeListener((mod, enabled) -> {
            if (viewModel != null) {
                viewModel.setModEnabled(mod.getId(), enabled);
                updateModsCount(); 
            }
        });
        
        modsAdapter.setOnModReorderListener(reorderedMods -> {
            if (viewModel != null) {
                viewModel.reorderMods(reorderedMods);
                Toast.makeText(this, R.string.mod_reordered, Toast.LENGTH_SHORT).show();
            }
        });
        
        NestedScrollView nestedScrollView = findViewById(R.id.nested_scroll_view);
        ItemTouchHelper.SimpleCallback simpleCallback = new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT) {
            private long lastScrollTime = 0;

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int fromPosition = viewHolder.getAdapterPosition();
                int toPosition = target.getAdapterPosition();
                modsAdapter.moveItem(fromPosition, toPosition);
                return true;
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                modsAdapter.setDragHandleVisible(viewHolder, false);
                modsAdapter.commitReorder();
            }

            @Override
            public void onSelectedChanged(@Nullable RecyclerView.ViewHolder viewHolder, int actionState) {
                super.onSelectedChanged(viewHolder, actionState);
                if (viewHolder != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    modsAdapter.setDragHandleVisible(viewHolder, true);
                }
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive && nestedScrollView != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastScrollTime > 16) {
                        int[] location = new int[2];
                        viewHolder.itemView.getLocationOnScreen(location);
                        int y = location[1];

                        nestedScrollView.getLocationOnScreen(location);
                        int svY = location[1];
                        int svHeight = nestedScrollView.getHeight();

                        int scrollZone = (int) (80 * recyclerView.getResources().getDisplayMetrics().density);
                        int scrollAmount = 0;
                        int maxScrollSpeed = 15;

                        if (y < svY + scrollZone) {
                            float ratio = 1.0f - Math.max(0, y - svY) / (float) scrollZone;
                            scrollAmount = (int) (-maxScrollSpeed * ratio);
                        } else if (y + viewHolder.itemView.getHeight() > svY + svHeight - scrollZone) {
                            float ratio = 1.0f - Math.max(0, svY + svHeight - (y + viewHolder.itemView.getHeight())) / (float) scrollZone;
                            scrollAmount = (int) (maxScrollSpeed * ratio);
                        }

                        if (scrollAmount != 0) {
                            nestedScrollView.scrollBy(0, scrollAmount);
                            lastScrollTime = now;
                            recyclerView.invalidate();
                        }
                    }
                }
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int pos = viewHolder.getAdapterPosition();
                Mod mod = modsAdapter.getItem(pos);
                new CustomAlertDialog(ModsFullscreenActivity.this)
                        .setTitleText(getString(R.string.dialog_title_delete_mod))
                        .setMessage(getString(R.string.dialog_message_delete_mod))
                        .setPositiveButton(getString(R.string.dialog_positive_delete), v -> {
                            viewModel.removeMod(mod);
                            modsAdapter.removeAt(pos);
                            updateModsCount();
                        })
                        .setNegativeButton(getString(R.string.dialog_negative_cancel), v -> {
                            modsAdapter.notifyItemChanged(pos);
                        })
                        .show();
            }
        };
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(simpleCallback);
        itemTouchHelper.attachToRecyclerView(modsRecycler);
        modsAdapter.setItemTouchHelper(itemTouchHelper);
    }

    private void updateModsUI(List<Mod> mods) {
        if (modsAdapter != null) {
            modsAdapter.updateMods(mods);
            updateModsCount();
            checkGitHubUpdates(mods);
            if (modsRecycler != null) {
                int count = (mods != null) ? mods.size() : 0;
                if (lastModsCount == -1 || count != lastModsCount) {
                    modsRecycler.post(() -> DynamicAnim.staggerRecyclerChildren(modsRecycler));
                }
                lastModsCount = count;
            }
        }
    }

    private void updateModsCount() {
        List<Mod> mods = viewModel.getModsLiveData().getValue();
        
        int total = (mods != null ? mods.size() : 0);
        int enabled = 0;
        
        if (mods != null) {
            for (Mod mod : mods) {
                if (mod.isEnabled()) {
                    enabled++;
                }
            }
        }

        totalModsCount.setText(String.valueOf(total));
        enabledModsCount.setText(String.valueOf(enabled));

        if (totalSizeCount != null) {
            long totalBytes = 0;
            if (mods != null) {
                for (Mod mod : mods) {
                    File entry = mod.getEntryPath() == null ? null : new File(mod.getEntryPath());
                    if (entry != null && entry.isFile()) {
                        totalBytes += entry.length();
                    }
                }
            }
            totalSizeCount.setText(android.text.format.Formatter.formatShortFileSize(this, totalBytes));
        }
    }

    private void showAddModDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_add_mod, null);
        CustomAlertDialog dialog = new CustomAlertDialog(this)
                .setTitleText(getString(R.string.add_mod))
                .setCustomView(content)
                .setNegativeButton(getString(R.string.dialog_negative_cancel), null);
        content.findViewById(R.id.import_option_device).setOnClickListener(v -> {
            dialog.dismiss();
            startFilePicker();
        });
        content.findViewById(R.id.import_option_github).setOnClickListener(v -> {
            dialog.dismiss();
            showGitHubImportDialog();
        });
        dialog.show();
    }

    private void showGitHubImportDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_github_import, null);
        EditText repoEdit = dialogView.findViewById(R.id.github_repo_edit);
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.github_import_dialog_title))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.github_import_action), v -> {
                    String ownerRepo = GitHubModRepository.parseRepoRef(repoEdit.getText().toString());
                    if (ownerRepo == null) {
                        Toast.makeText(this, R.string.github_import_invalid_repo, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    fetchLatestForImport(ownerRepo);
                })
                .setNegativeButton(getString(R.string.dialog_negative_cancel), null)
                .show();
    }

    private void fetchLatestForImport(String ownerRepo) {
        GitHubModRepository.fetchLatestRelease(this, ownerRepo, new GitHubModRepository.ReleaseCallback() {
            @Override
            public void onRelease(GitHubModRepository.GitHubRelease release) {
                List<GitHubModRepository.GitHubAsset> assets = release.supportedAssets();
                if (assets.isEmpty()) {
                    Toast.makeText(ModsFullscreenActivity.this,
                            R.string.github_import_no_assets, Toast.LENGTH_LONG).show();
                    return;
                }
                if (assets.size() == 1) {
                    installFromGitHub(ownerRepo, assets.get(0), release.tagName, null, false);
                    return;
                }
                String[] names = new String[assets.size()];
                for (int i = 0; i < assets.size(); i++) {
                    names[i] = assets.get(i).name;
                }
                new CustomAlertDialog(ModsFullscreenActivity.this)
                        .setTitleText(getString(R.string.github_import_choose_asset))
                        .setItems(names, (d, which) ->
                                installFromGitHub(ownerRepo, assets.get(which), release.tagName, null, false))
                        .setNegativeButton(getString(R.string.dialog_negative_cancel), null)
                        .show();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(ModsFullscreenActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void installFromGitHub(String ownerRepo, GitHubModRepository.GitHubAsset asset,
                                   String versionTag, String nameOverride, boolean forceOverwrite) {
        githubModInstaller.install(ownerRepo, asset, versionTag, nameOverride, selectedVersionCode,
                forceOverwrite, new GitHubModInstaller.Callback() {
                    @Override
                    public void onWarning(String message, Runnable proceed) {
                        new CustomAlertDialog(ModsFullscreenActivity.this)
                                .setTitleText(getString(R.string.github_import_dialog_title))
                                .setMessage(message)
                                .setPositiveButton(getString(R.string.confirm), d -> proceed.run())
                                .setNegativeButton(getString(R.string.dialog_negative_cancel), null)
                                .show();
                    }

                    @Override
                    public void onSuccess() {
                        Toast.makeText(ModsFullscreenActivity.this,
                                forceOverwrite
                                        ? getString(R.string.external_mods_installed,
                                                nameOverride != null ? nameOverride : asset.name)
                                        : getString(R.string.github_import_success),
                                Toast.LENGTH_SHORT).show();
                        if (forceOverwrite) finishGitHubBatchItem();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(ModsFullscreenActivity.this, message, Toast.LENGTH_LONG).show();
                        if (forceOverwrite) finishGitHubBatchItem();
                    }
                });
    }

    private void checkGitHubUpdates(List<Mod> mods) {
        if (githubUpdateCheckRunning || githubBatchUpdating || mods == null) return;
        List<Mod> githubMods = new ArrayList<>();
        for (Mod mod : mods) {
            if (mod.getGithubRepo() != null && !mod.getGithubRepo().isEmpty()) {
                githubMods.add(mod);
            }
        }
        if (githubMods.isEmpty()) return;
        githubUpdateCheckRunning = true;
        Map<String, String> found = new LinkedHashMap<>();
        AtomicInteger remaining = new AtomicInteger(githubMods.size());
        for (Mod mod : githubMods) {
            GitHubModRepository.fetchLatestRelease(this, mod.getGithubRepo(), new GitHubModRepository.ReleaseCallback() {
                @Override
                public void onRelease(GitHubModRepository.GitHubRelease release) {
                    String installed = mod.getVersion() == null ? "" : mod.getVersion();
                    if (release.tagName != null
                            && GithubReleaseUpdater.compareVersion(release.tagName, installed) > 0) {
                        found.put(mod.getId(), release.tagName);
                    }
                    finishGitHubUpdateCheck(remaining, found);
                }

                @Override
                public void onError(String message) {
                    finishGitHubUpdateCheck(remaining, found);
                }
            });
        }
    }

    private void finishGitHubUpdateCheck(AtomicInteger remaining, Map<String, String> found) {
        if (remaining.decrementAndGet() != 0) return;
        githubUpdateCheckRunning = false;
        modsAdapter.setUpdateVersions(found);
        if (!found.isEmpty()) showGitHubUpdatesDialog(found);
    }

    private void showGitHubUpdatesDialog(Map<String, String> updates) {
        String signature = new TreeMap<>(updates).toString();
        if (signature.equals(lastShownUpdateSignature)) return;
        lastShownUpdateSignature = signature;

        StringBuilder lines = new StringBuilder();
        List<Mod> mods = viewModel.getModsLiveData().getValue();
        if (mods != null) {
            for (Mod mod : mods) {
                String tag = updates.get(mod.getId());
                if (tag != null) {
                    lines.append("• ").append(mod.getDisplayName()).append(" → ").append(tag).append('\n');
                }
            }
        }
        if (lines.length() == 0) return;

        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.github_updates_title))
                .setMessage(getString(R.string.github_updates_message, lines.toString().trim()))
                .setPositiveButton(getString(R.string.github_updates_install_all), v -> installGitHubUpdates(updates))
                .setNegativeButton(getString(R.string.github_updates_later), null)
                .show();
    }

    private void installGitHubUpdates(Map<String, String> updates) {
        List<Mod> mods = viewModel.getModsLiveData().getValue();
        if (mods == null || updates.isEmpty()) return;
        lastShownUpdateSignature = "";
        githubBatchUpdating = true;
        githubBatchRemaining = updates.size();
        for (Mod mod : mods) {
            if (!updates.containsKey(mod.getId()) || mod.getGithubRepo() == null) continue;
            GitHubModRepository.fetchLatestRelease(this, mod.getGithubRepo(), new GitHubModRepository.ReleaseCallback() {
                @Override
                public void onRelease(GitHubModRepository.GitHubRelease release) {
                    GitHubModRepository.GitHubAsset asset = pickGitHubAsset(release, mod.getGithubAsset());
                    if (asset == null) {
                        finishGitHubBatchItem();
                        return;
                    }
                    installFromGitHub(mod.getGithubRepo(), asset, release.tagName, mod.getDisplayName(), true);
                }

                @Override
                public void onError(String message) {
                    finishGitHubBatchItem();
                }
            });
        }
    }

    private void finishGitHubBatchItem() {
        if (--githubBatchRemaining > 0) return;
        githubBatchUpdating = false;
        if (viewModel != null) viewModel.refreshMods();
    }

    private static GitHubModRepository.GitHubAsset pickGitHubAsset(GitHubModRepository.GitHubRelease release,
                                                                  String preferredName) {
        List<GitHubModRepository.GitHubAsset> assets = release.supportedAssets();
        if (preferredName != null) {
            for (GitHubModRepository.GitHubAsset asset : assets) {
                if (preferredName.equalsIgnoreCase(asset.name)) return asset;
            }
        }
        return assets.isEmpty() ? null : assets.get(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupViews();
        if (viewModel != null) {
            viewModel.refreshMods();
        }
        updateModsCount();
    }

}
