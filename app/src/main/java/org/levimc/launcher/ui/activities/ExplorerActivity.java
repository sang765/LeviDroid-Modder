package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.levimc.launcher.R;
import org.levimc.launcher.core.explorer.ExplorerEntry;
import org.levimc.launcher.core.explorer.ExplorerRepository;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.databinding.ActivityExplorerBinding;
import org.levimc.launcher.ui.adapter.ExplorerEntryAdapter;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.widget.LauncherPopup;
import org.levimc.launcher.util.LauncherStorage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class ExplorerActivity extends BaseActivity {
    interface FileAction {
        void run() throws IOException;
    }

    private ActivityExplorerBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<File> stack = new ArrayList<>();
    private List<GameVersion> versions = new ArrayList<>();
    private ExplorerEntryAdapter adapter;
    private File pendingSource;
    private boolean pendingMove;
    private File exportSource;
    private String restoredProfileId;
    private ActivityResultLauncher<Intent> exportLauncher;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityExplorerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setActiveNavTab(R.id.nav_tab_explorer);
        if (state != null) restoredProfileId = state.getString("explorer_profile_id");

        adapter = new ExplorerEntryAdapter(new ExplorerEntryAdapter.OnEntryClickListener() {
            @Override
            public void onEntryClick(ExplorerEntry entry) {
                if (entry.directory) enter(entry.file);
                else if (ExplorerRepository.isTextFile(entry.file.getName())) openTextEditor(entry.file);
                else showEntryMenu(entry);
            }

            @Override
            public void onEntryLongClick(ExplorerEntry entry) {
                showEntryMenu(entry);
            }
        });
        binding.explorerRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.explorerRecyclerView.setAdapter(adapter);
        binding.explorerRecyclerView.setItemAnimator(null);

        binding.explorerInstanceButton.setOnClickListener(v -> showVersionPicker());
        binding.explorerUpButton.setOnClickListener(v -> goUp());
        binding.explorerNewFolderButton.setOnClickListener(v -> showInputDialog(
                R.string.explorer_new_folder, "", name -> {
                    File parent = current();
                    runFileAction(() -> ExplorerRepository.createDirectory(
                            ExplorerRepository.child(root(), parent, name)));
                }));
        binding.explorerRefreshButton.setOnClickListener(v -> refresh());

        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    File source = exportSource;
                    exportSource = null;
                    if (result.getResultCode() != RESULT_OK || result.getData() == null || source == null) return;
                    Uri uri = result.getData().getData();
                    if (uri == null) return;
                    executor.execute(() -> {
                        String failure = null;
                        try (InputStream in = new java.io.FileInputStream(source);
                             OutputStream out = getContentResolver().openOutputStream(uri)) {
                            if (out == null) throw new IOException("Cannot open destination");
                            byte[] buffer = new byte[16 * 1024];
                            int read;
                            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                        } catch (Exception error) {
                            failure = error.getMessage();
                        }
                        final String problem = failure;
                        runOnUiThread(() -> Toast.makeText(this,
                                problem == null ? getString(R.string.explorer_export_done)
                                        : getString(R.string.explorer_error, problem),
                                problem == null ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show());
                    });
                });

        loadVersions();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (!stack.isEmpty()) outState.putString("explorer_profile_id", stack.get(0).getAbsolutePath());
    }

    private void loadVersions() {
        binding.explorerLoadingProgress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            List<GameVersion> found = new ArrayList<>();
            try {
                VersionManager manager = VersionManager.get(getApplicationContext());
                List<GameVersion> installed = manager.getInstalledVersions();
                if (installed != null) found.addAll(installed);
                List<GameVersion> custom = manager.getCustomVersions();
                if (custom != null) found.addAll(custom);
            } catch (Exception ignored) {
            }
            final List<GameVersion> result = found;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                versions = result;
                binding.explorerLoadingProgress.setVisibility(View.GONE);
                if (restoredProfileId != null) {
                    for (GameVersion version : result) {
                        if (restoredProfileId.equals(rootFor(version).getAbsolutePath())) {
                            openRoot(rootFor(version));
                            restoredProfileId = null;
                            return;
                        }
                    }
                }
                if (result.isEmpty()) {
                    binding.explorerEmptyText.setVisibility(View.VISIBLE);
                    binding.explorerEmptyText.setText(R.string.explorer_no_instances);
                } else {
                    showVersionPicker();
                }
            });
        });
    }

    private File rootFor(GameVersion version) {
        if (!version.versionIsolation) return LauncherStorage.getSharedRoot(this);
        return LauncherStorage.getVersionRoot(this, version.getStorageProfileId());
    }

    private File root() {
        return stack.get(0);
    }

    private File current() {
        return stack.get(stack.size() - 1);
    }

    private void showVersionPicker() {
        if (versions.isEmpty()) return;
        String[] labels = new String[versions.size()];
        for (int i = 0; i < versions.size(); i++) {
            GameVersion version = versions.get(i);
            labels[i] = version.displayName != null ? version.displayName : version.directoryName;
            if (!version.versionIsolation) labels[i] += " (" + getString(R.string.tag_installed) + ")";
        }
        new CustomAlertDialog(this).setTitleText(getString(R.string.explorer_select_instance))
                .setItems(labels, (dialog, index) -> {
                    if (index >= 0 && index < versions.size()) openRoot(rootFor(versions.get(index)));
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void openRoot(File file) {
        stack.clear();
        stack.add(file);
        pendingSource = null;
        binding.explorerEmptyText.setVisibility(View.GONE);
        binding.explorerInstanceButton.setText(file.getName());
        refresh();
    }

    private void enter(File dir) {
        try {
            File target = ExplorerRepository.child(root(), current(), dir.getName());
            if (!target.isDirectory()) return;
            stack.add(target);
            refresh();
        } catch (IOException error) {
            toastError(error);
        }
    }

    private void goUp() {
        if (stack.size() <= 1) return;
        stack.remove(stack.size() - 1);
        refresh();
    }

    private void refresh() {
        if (stack.isEmpty()) return;
        File dir = current();
        binding.explorerPath.setText(dir.getAbsolutePath());
        binding.explorerLoadingProgress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            List<ExplorerEntry> result = new ArrayList<>();
            final String[] failure = new String[1];
            try {
                result = ExplorerRepository.list(root(), dir);
            } catch (IOException error) {
                failure[0] = error.getMessage();
            }
            final List<ExplorerEntry> entries = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                binding.explorerLoadingProgress.setVisibility(View.GONE);
                adapter.setEntries(entries);
                boolean empty = entries.isEmpty();
                binding.explorerEmptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
                if (empty) binding.explorerEmptyText.setText(failure[0] != null
                        ? getString(R.string.explorer_error, failure[0]) : getString(R.string.explorer_empty));
            });
        });
    }

    private void showEntryMenu(ExplorerEntry entry) {
        LauncherPopup popup = new LauncherPopup(binding.explorerRecyclerView).width(240)
                .section(entry.file.getName());
        if (!entry.directory && ExplorerRepository.isTextFile(entry.file.getName())) {
            popup.action(1, getString(R.string.explorer_open), R.drawable.ic_code);
        }
        popup.action(2, getString(R.string.explorer_copy), R.drawable.ic_backup);
        popup.action(3, getString(R.string.explorer_cut), R.drawable.ic_ui_folder);
        popup.action(4, getString(R.string.explorer_rename), R.drawable.ic_edit);
        popup.action(5, getString(R.string.explorer_export), R.drawable.ic_export);
        if (pendingSource != null) popup.action(6, getString(R.string.explorer_paste), R.drawable.ic_add);
        popup.destructiveAction(7, getString(R.string.explorer_delete), R.drawable.ic_delete);
        popup.onSelect(id -> {
            switch (id) {
                case 1 -> openTextEditor(entry.file);
                case 2 -> setPending(entry.file, false);
                case 3 -> setPending(entry.file, true);
                case 4 -> showInputDialog(R.string.explorer_rename, entry.file.getName(), name ->
                        runFileAction(() -> ExplorerRepository.rename(entry.file,
                                ExplorerRepository.child(root(), entry.file.getParentFile(), name))));
                case 5 -> exportFile(entry.file);
                case 6 -> paste();
                case 7 -> confirmDelete(entry.file);
                default -> {
                }
            }
        }).show();
    }

    private void setPending(File file, boolean move) {
        pendingSource = file;
        pendingMove = move;
        Toast.makeText(this, getString(move ? R.string.explorer_cut_done : R.string.explorer_copy_done),
                Toast.LENGTH_SHORT).show();
    }

    private void paste() {
        File source = pendingSource;
        boolean move = pendingMove;
        pendingSource = null;
        if (source == null) return;
        File targetDir = current();
        runFileAction(() -> {
            if (source.isDirectory() && ExplorerRepository.isInside(source, targetDir)) {
                throw new IOException(getString(R.string.explorer_paste_into_self));
            }
            File destination = ExplorerRepository.uniqueDestination(targetDir, source.getName());
            if (move) ExplorerRepository.move(source, destination);
            else ExplorerRepository.copy(source, destination);
        });
    }

    private void confirmDelete(File file) {
        new CustomAlertDialog(this).setTitleText(getString(R.string.explorer_delete))
                .setMessage(getString(R.string.explorer_delete_confirm, file.getName()))
                .setPositiveButton(getString(R.string.explorer_delete), v ->
                        runFileAction(() -> ExplorerRepository.delete(file)))
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void runFileAction(FileAction action) {
        binding.explorerLoadingProgress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            final String[] failure = new String[1];
            try {
                action.run();
            } catch (IOException error) {
                failure[0] = error.getMessage();
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                binding.explorerLoadingProgress.setVisibility(View.GONE);
                if (failure[0] != null) toastText(getString(R.string.explorer_error, failure[0]), true);
                refresh();
            });
        });
    }

    private void openTextEditor(File file) {
        binding.explorerLoadingProgress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            final String[] content = new String[1];
            final String[] failure = new String[1];
            try {
                content[0] = ExplorerRepository.readText(file);
            } catch (IOException error) {
                failure[0] = error.getMessage();
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                binding.explorerLoadingProgress.setVisibility(View.GONE);
                if (failure[0] != null) {
                    toastText(getString(R.string.explorer_error, failure[0]), true);
                    return;
                }
                View view = LayoutInflater.from(this).inflate(R.layout.dialog_explorer_editor, null, false);
                EditText field = view.findViewById(R.id.explorer_editor_field);
                field.setText(content[0]);
                CustomAlertDialog dialog = new CustomAlertDialog(this).setTitleText(file.getName())
                        .setCustomView(view)
                        .setNegativeButton(getString(R.string.cancel), null)
                        .setPositiveButton(getString(R.string.save), v -> {
                            String value = field.getText().toString();
                            runFileAction(() -> ExplorerRepository.writeText(file, value));
                        });
                dialog.setOuterScrollbarEnabled(false);
                dialog.show();
            });
        });
    }

    private void showInputDialog(int titleRes, String initial, Consumer<String> onConfirm) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_explorer_input, null, false);
        EditText field = view.findViewById(R.id.explorer_input_field);
        field.setText(initial);
        field.setSelection(field.length());
        new CustomAlertDialog(this).setTitleText(getString(titleRes)).setCustomView(view)
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.save), v -> {
                    String name = field.getText().toString().trim();
                    if (!name.isEmpty()) onConfirm.accept(name);
                }).show();
    }

    private void exportFile(File file) {
        exportSource = file;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, file.getName());
        exportLauncher.launch(intent);
    }

    private void toastError(IOException error) {
        toastText(getString(R.string.explorer_error, error.getMessage() == null ? "" : error.getMessage()), true);
    }

    private void toastText(String message, boolean longDuration) {
        Toast.makeText(this, message, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
