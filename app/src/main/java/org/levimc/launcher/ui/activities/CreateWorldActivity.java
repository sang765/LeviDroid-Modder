package org.levimc.launcher.ui.activities;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.ResourcePackItem;
import org.levimc.launcher.core.content.ResourcePackManager;
import org.levimc.launcher.core.content.WorldCreator;
import org.levimc.launcher.core.content.WorldCreator.BlockLayer;
import org.levimc.launcher.core.content.WorldCreator.PackRef;
import org.levimc.launcher.core.content.WorldCreator.WorldOptions;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.databinding.ActivityCreateWorldBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CreateWorldActivity extends BaseActivity {

    public static final String EXTRA_WORLDS_DIRECTORY = "worlds_directory";

    private ActivityCreateWorldBinding binding;
    private LayersAdapter layersAdapter;
    private List<BlockLayer> layers;
    private File worldsDirectory;
    private ExecutorService executor;
    private ItemTouchHelper itemTouchHelper;

    private final WorldOptions options = new WorldOptions();
    private GameVersion gameVersion;
    private List<ResourcePackItem> behaviorPacks = new ArrayList<>();
    private List<ResourcePackItem> resourcePacks = new ArrayList<>();
    private PackAdapter behaviorAdapter;
    private PackAdapter resourceAdapter;

    private static final String[] BIOME_NAMES = {
            "Plains", "Desert", "Mountains", "Forest", "Taiga", "Swamp", "River", "Nether Wastes",
            "The End", "Frozen Ocean", "Frozen River", "Snowy Plains", "Snowy Mountains", "Mushroom Fields",
            "Beach", "Jungle", "Sparse Jungle", "Deep Ocean", "Stony Shore", "Snowy Beach",
            "Birch Forest", "Dark Forest", "Snowy Taiga", "Old Growth Pine Taiga", "Windswept Forest",
            "Savanna", "Savanna Plateau", "Badlands", "Wooded Badlands", "Warm Ocean"
    };

    private static final int[] BIOME_IDS = {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 21, 23, 24, 25, 26,
            27, 29, 30, 32, 34, 35, 36, 37, 38, 44
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCreateWorldBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        executor = Executors.newSingleThreadExecutor();

        String worldsPath = getIntent().getStringExtra(EXTRA_WORLDS_DIRECTORY);
        if (worldsPath == null) {
            Toast.makeText(this, R.string.invalid_worlds_directory, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        worldsDirectory = new File(worldsPath);

        gameVersion = VersionManager.get(this).getSelectedVersion();
        options.gameVersionName = gameVersion != null ? gameVersion.versionCode : null;

        layers = new ArrayList<>(WorldCreator.getDefaultLayers());
        setupUI();
        loadPacks();
    }

    private void setupUI() {
        ArrayAdapter<String> biomeAdapter = new ArrayAdapter<>(this, R.layout.item_flat_spinner, BIOME_NAMES);
        biomeAdapter.setDropDownViewResource(R.layout.item_flat_spinner_dropdown);
        binding.biomeSpinner.setAdapter(biomeAdapter);

        String[] gameModes = {
                getString(R.string.mode_survival),
                getString(R.string.mode_creative),
                getString(R.string.mode_adventure)
        };
        ArrayAdapter<String> gameModeAdapter = new ArrayAdapter<>(this, R.layout.item_flat_spinner, gameModes);
        gameModeAdapter.setDropDownViewResource(R.layout.item_flat_spinner_dropdown);
        binding.gameModeSpinner.setAdapter(gameModeAdapter);

        String[] difficulties = {
                getString(R.string.diff_peaceful),
                getString(R.string.diff_easy),
                getString(R.string.diff_normal),
                getString(R.string.diff_hard)
        };
        ArrayAdapter<String> difficultyAdapter = new ArrayAdapter<>(this, R.layout.item_flat_spinner, difficulties);
        difficultyAdapter.setDropDownViewResource(R.layout.item_flat_spinner_dropdown);
        binding.difficultySpinner.setAdapter(difficultyAdapter);
        binding.difficultySpinner.setSelection(2);

        String[] simulationDistances = {"4", "6", "8"};
        ArrayAdapter<String> simulationAdapter = new ArrayAdapter<>(this, R.layout.item_flat_spinner, simulationDistances);
        simulationAdapter.setDropDownViewResource(R.layout.item_flat_spinner_dropdown);
        binding.simulationSpinner.setAdapter(simulationAdapter);

        binding.respawnRadiusEdit.setText("10");
        binding.sleepingValue.setText(getString(R.string.required_sleeping_players) + ": 100%");

        binding.flatSwitch.setOnCheckedChangeListener((v, checked) -> {
            options.flat = checked;
            binding.layersContainer.setVisibility(checked ? View.VISIBLE : View.GONE);
            updateAchievementsNote();
        });

        binding.sleepingSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                binding.sleepingValue.setText(getString(R.string.required_sleeping_players) + ": " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
            }
        });

        setupSwitchRows();
        setupLayersEditor();

        binding.createWorldButton.setOnClickListener(v -> createWorld());
        updateAchievementsNote();
    }

    private void setupSwitchRows() {
        addSwitchRow(binding.switchesAdvanced, R.string.start_with_map, v -> options.startWithMap = v);
        addSwitchRow(binding.switchesAdvanced, R.string.bonus_chest, v -> options.bonusChest = v);
        addSwitchRow(binding.switchesAdvanced, R.string.show_coordinates, v -> options.showCoordinates = v);
        addSwitchRow(binding.switchesAdvanced, R.string.show_days_played, v -> options.showDaysPlayed = v);
        addSwitchRow(binding.switchesAdvanced, R.string.recipe_unlocking, options.recipeUnlocking, v -> options.recipeUnlocking = v);
        addSwitchRow(binding.switchesAdvanced, R.string.fire_spreads, options.fireSpreads, v -> options.fireSpreads = v);
        addSwitchRow(binding.switchesAdvanced, R.string.tnt_explodes, options.tntExplodes, v -> options.tntExplodes = v);
        addSwitchRow(binding.switchesAdvanced, R.string.mob_loot, options.mobLoot, v -> options.mobLoot = v);
        addSwitchRow(binding.switchesAdvanced, R.string.natural_regeneration, options.naturalRegeneration, v -> options.naturalRegeneration = v);
        addSwitchRow(binding.switchesAdvanced, R.string.tile_drops, options.tileDrops, v -> options.tileDrops = v);
        addSwitchRow(binding.switchesAdvanced, R.string.mob_spawning, options.mobSpawning, v -> options.mobSpawning = v);
        addSwitchRow(binding.switchesAdvanced, R.string.mob_griefing, options.mobGriefing, v -> options.mobGriefing = v);
        addSwitchRow(binding.switchesAdvanced, R.string.entities_drop_loot, options.entitiesDropLoot, v -> options.entitiesDropLoot = v);
        addSwitchRow(binding.switchesAdvanced, R.string.weather_cycle, options.weatherCycle, v -> options.weatherCycle = v);
        addSwitchRow(binding.switchesAdvanced, R.string.keep_inventory, v -> options.keepInventory = v);
        addSwitchRow(binding.switchesAdvanced, R.string.immediate_respawn, v -> options.immediateRespawn = v);
        addSwitchRow(binding.switchesAdvanced, R.string.respawn_blocks_explode, options.respawnBlocksExplode, v -> options.respawnBlocksExplode = v);

        addSwitchRow(binding.switchesCheats, R.string.cheats, v -> {
            options.cheats = v;
            updateAchievementsNote();
        });
        addSwitchRow(binding.switchesCheats, R.string.command_blocks, v -> options.commandBlocks = v);
        addSwitchRow(binding.switchesCheats, R.string.education_features, v -> {
            options.educationFeatures = v;
            updateAchievementsNote();
        });
    }

    private void addSwitchRow(LinearLayout container, int labelRes, java.util.function.Consumer<Boolean> setter) {
        addSwitchRow(container, labelRes, false, setter);
    }

    private void addSwitchRow(LinearLayout container, int labelRes, boolean initial, java.util.function.Consumer<Boolean> setter) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = (int) (12 * getResources().getDisplayMetrics().density);
        row.setLayoutParams(rowParams);

        TextView label = new TextView(this);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        label.setText(labelRes);
        label.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.on_surface));
        label.setTextSize(13f);
        label.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.misans));

        SwitchMaterial switchView = new SwitchMaterial(this);
        switchView.setChecked(initial);
        switchView.setOnCheckedChangeListener((v, checked) -> setter.accept(checked));

        row.addView(label);
        row.addView(switchView);
        container.addView(row);
    }

    private void setupLayersEditor() {
        layersAdapter = new LayersAdapter();
        binding.layersRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.layersRecyclerView.setAdapter(layersAdapter);

        itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT) {
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                    return false;
                }
                Collections.swap(layers, from, to);
                layersAdapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                layersAdapter.notifyItemRangeChanged(0, layersAdapter.getItemCount());
                updateLayersUi();
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                removeLayer(viewHolder.getBindingAdapterPosition());
            }
        });
        itemTouchHelper.attachToRecyclerView(binding.layersRecyclerView);

        binding.addLayerButton.setOnClickListener(v -> {
            layers.add(new BlockLayer("minecraft:stone", 1));
            int position = layers.size() - 1;
            layersAdapter.notifyItemInserted(position);
            updateLayersUi();
            binding.layersRecyclerView.post(() -> binding.layersRecyclerView.smoothScrollToPosition(position));
        });

        updateLayersUi();
    }

    private void loadPacks() {
        executor.execute(() -> {
            List<ResourcePackItem> behaviors = new ArrayList<>();
            List<ResourcePackItem> resources = new ArrayList<>();
            if (gameVersion != null) {
                try {
                    ResourcePackManager packManager = new ResourcePackManager(getApplicationContext());
                    packManager.setCurrentVersion(gameVersion);
                    List<ResourcePackItem> behaviorsLoaded = packManager.getBehaviorPacks();
                    List<ResourcePackItem> resourcesLoaded = packManager.getResourcePacks();
                    if (behaviorsLoaded != null) behaviors.addAll(behaviorsLoaded);
                    if (resourcesLoaded != null) resources.addAll(resourcesLoaded);
                } catch (Exception e) {
                    android.util.Log.w("CreateWorldActivity", "Failed to list packs", e);
                }
            }
            runOnUiThread(() -> {
                behaviorPacks = behaviors;
                resourcePacks = resources;
                bindPackList(binding.behaviorPacksRecycler, binding.behaviorPacksEmpty, behaviors, true);
                bindPackList(binding.resourcePacksRecycler, binding.resourcePacksEmpty, resources, false);
            });
        });
    }

    private void bindPackList(RecyclerView recycler, TextView emptyView,
                              List<ResourcePackItem> packs, boolean behavior) {
        if (packs.isEmpty()) {
            recycler.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        recycler.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        PackAdapter adapter = new PackAdapter(packs);
        if (behavior) {
            behaviorAdapter = adapter;
        } else {
            resourceAdapter = adapter;
        }
        recycler.setAdapter(adapter);
    }

    private void updateAchievementsNote() {
        boolean disabled = WorldCreator.disablesAchievements(options) || options.hardcore;
        binding.achievementsNote.setText(disabled
                ? R.string.achievements_disabled_note
                : R.string.achievements_enabled_note);
    }

    private void removeLayer(int position) {
        if (position < 0 || position >= layers.size()) {
            return;
        }
        layers.remove(position);
        layersAdapter.notifyItemRemoved(position);
        if (position < layersAdapter.getItemCount()) {
            layersAdapter.notifyItemRangeChanged(position, layersAdapter.getItemCount() - position);
        }
        updateLayersUi();
    }

    private void updateLayersUi() {
        int count = layers.size();
        binding.layersSummary.setText(getResources().getQuantityString(R.plurals.flat_layers_count, count, count));
        binding.emptyLayersState.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        binding.layersRecyclerView.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
    }

    private void saveAllLayerValues() {
        for (int i = 0; i < binding.layersRecyclerView.getChildCount(); i++) {
            View child = binding.layersRecyclerView.getChildAt(i);
            RecyclerView.ViewHolder holder = binding.layersRecyclerView.getChildViewHolder(child);
            if (holder != null) {
                int position = holder.getBindingAdapterPosition();
                if (position >= 0 && position < layers.size()) {
                    EditText countEdit = child.findViewById(R.id.count_edit);
                    EditText blockEdit = child.findViewById(R.id.block_edit);
                    if (countEdit != null) {
                        try {
                            int count = Integer.parseInt(countEdit.getText().toString());
                            layers.get(position).count = Math.max(1, Math.min(count, 256));
                        } catch (NumberFormatException e) {
                            layers.get(position).count = 1;
                        }
                    }
                    if (blockEdit != null) {
                        String blockName = blockEdit.getText().toString().trim();
                        if (!blockName.isEmpty()) {
                            layers.get(position).blockName = blockName;
                        }
                    }
                }
            }
        }
    }

    private void createWorld() {
        binding.getRoot().clearFocus();

        String worldName = binding.worldNameEdit.getText().toString().trim();
        if (worldName.isEmpty()) {
            Toast.makeText(this, R.string.enter_world_name, Toast.LENGTH_SHORT).show();
            return;
        }

        if (options.flat && layers.isEmpty()) {
            Toast.makeText(this, R.string.add_at_least_one_layer, Toast.LENGTH_SHORT).show();
            return;
        }

        saveAllLayerValues();

        binding.createWorldButton.setEnabled(false);
        binding.createWorldButton.setAlpha(0.45f);
        binding.loadingProgress.setVisibility(View.VISIBLE);

        options.worldName = worldName;
        options.seedText = binding.seedEdit.getText().toString().trim();
        options.gameMode = binding.gameModeSpinner.getSelectedItemPosition();
        options.difficulty = binding.difficultySpinner.getSelectedItemPosition();
        options.hardcore = binding.hardcoreSwitch.isChecked();
        int biomeIndex = binding.biomeSpinner.getSelectedItemPosition();
        options.flatBiomeId = biomeIndex >= 0 && biomeIndex < BIOME_IDS.length ? BIOME_IDS[biomeIndex] : 1;
        options.respawnRadius = parseBoundedInt(binding.respawnRadiusEdit.getText().toString(), 10, 0, 128);
        options.playerSleepingPercentage = binding.sleepingSeek.getProgress();
        options.simulationDistance = parseBoundedInt(
                String.valueOf(binding.simulationSpinner.getSelectedItem()), 4, 1, 12);
        options.layers = new ArrayList<>();
        for (BlockLayer layer : layers) {
            options.layers.add(new BlockLayer(layer.blockName, layer.count));
        }
        options.packs.clear();
        if (behaviorAdapter != null) {
            for (ResourcePackItem pack : behaviorAdapter.getSelected()) {
                options.packs.add(new PackRef(pack.getFile(), true));
            }
        }
        if (resourceAdapter != null) {
            for (ResourcePackItem pack : resourceAdapter.getSelected()) {
                options.packs.add(new PackRef(pack.getFile(), false));
            }
        }

        executor.execute(() -> {
            try {
                WorldCreator.generate(worldsDirectory, options);
                runOnUiThread(() -> {
                    binding.loadingProgress.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.world_created_successfully, Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    binding.loadingProgress.setVisibility(View.GONE);
                    binding.createWorldButton.setEnabled(true);
                    binding.createWorldButton.setAlpha(1f);
                    Toast.makeText(this, getString(R.string.failed_to_create_world) + ": " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private int parseBoundedInt(String text, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(Integer.parseInt(text.trim()), max));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
        }
    }

    private class LayersAdapter extends RecyclerView.Adapter<LayersAdapter.LayerViewHolder> {

        @NonNull
        @Override
        public LayerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_flat_layer, parent, false);
            return new LayerViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull LayerViewHolder holder, int position) {
            holder.bind(layers.get(position), position);
        }

        @Override
        public int getItemCount() {
            return layers.size();
        }

        class LayerViewHolder extends RecyclerView.ViewHolder {
            final EditText blockEdit;
            final EditText countEdit;
            final TextView layerNumber;
            final View dragHandle;
            final View deleteButton;
            private boolean isBinding;

            LayerViewHolder(View itemView) {
                super(itemView);
                blockEdit = itemView.findViewById(R.id.block_edit);
                countEdit = itemView.findViewById(R.id.count_edit);
                layerNumber = itemView.findViewById(R.id.layer_number);
                dragHandle = itemView.findViewById(R.id.drag_handle);
                deleteButton = itemView.findViewById(R.id.delete_layer_button);

                dragHandle.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper.startDrag(this);
                        return true;
                    }
                    return false;
                });

                deleteButton.setOnClickListener(v -> removeLayer(getBindingAdapterPosition()));

                blockEdit.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        if (isBinding) {
                            return;
                        }
                        int adapterPos = getBindingAdapterPosition();
                        if (adapterPos >= 0 && adapterPos < layers.size()) {
                            String blockName = s.toString().trim();
                            if (!blockName.isEmpty()) {
                                layers.get(adapterPos).blockName = blockName;
                            }
                        }
                    }
                });

                countEdit.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        if (isBinding) {
                            return;
                        }
                        int adapterPos = getBindingAdapterPosition();
                        if (adapterPos >= 0 && adapterPos < layers.size()) {
                            try {
                                int count = Integer.parseInt(s.toString());
                                layers.get(adapterPos).count = Math.max(1, Math.min(count, 256));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                });
            }

            void bind(BlockLayer layer, int position) {
                isBinding = true;
                layerNumber.setText(String.valueOf(position + 1));
                blockEdit.setText(layer.blockName);
                countEdit.setText(String.valueOf(layer.count));
                isBinding = false;
            }
        }
    }

    private class PackAdapter extends RecyclerView.Adapter<PackAdapter.PackViewHolder> {

        private final List<ResourcePackItem> packs;
        private final Set<ResourcePackItem> selected = new HashSet<>();

        PackAdapter(List<ResourcePackItem> packs) {
            this.packs = packs;
        }

        List<ResourcePackItem> getSelected() {
            List<ResourcePackItem> result = new ArrayList<>();
            for (ResourcePackItem pack : packs) {
                if (selected.contains(pack)) {
                    result.add(pack);
                }
            }
            return result;
        }

        @NonNull
        @Override
        public PackViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_create_world_pack, parent, false);
            return new PackViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull PackViewHolder holder, int position) {
            ResourcePackItem pack = packs.get(position);
            holder.name.setText(pack.getName());
            holder.version.setText(pack.getVersion() != null
                    ? String.format(Locale.US, "v%s", pack.getVersion()) : "");
            holder.check.setOnCheckedChangeListener(null);
            holder.check.setChecked(selected.contains(pack));
            holder.check.setOnCheckedChangeListener((v, checked) -> {
                if (checked) {
                    selected.add(pack);
                } else {
                    selected.remove(pack);
                }
            });
        }

        @Override
        public int getItemCount() {
            return packs.size();
        }

        class PackViewHolder extends RecyclerView.ViewHolder {
            final com.google.android.material.checkbox.MaterialCheckBox check;
            final TextView name;
            final TextView version;

            PackViewHolder(View itemView) {
                super(itemView);
                check = itemView.findViewById(R.id.pack_check);
                name = itemView.findViewById(R.id.pack_name);
                version = itemView.findViewById(R.id.pack_version);
            }
        }
    }
}
