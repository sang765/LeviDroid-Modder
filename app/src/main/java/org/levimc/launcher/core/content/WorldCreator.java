package org.levimc.launcher.core.content;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.levimc.launcher.core.content.nbt.BedrockNbtWriter;
import org.levimc.launcher.core.content.nbt.NbtTag;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Creates a new Bedrock world folder (level.dat + empty db) outside the game,
 * mirroring the in-game "Create New World" options. Version tags are derived
 * from the instance's game version so the world is stamped as created by it.
 */
public class WorldCreator {
    private static final String TAG = "WorldCreator";

    public static final int GAME_MODE_SURVIVAL = 0;
    public static final int GAME_MODE_CREATIVE = 1;
    public static final int GAME_MODE_ADVENTURE = 2;

    // Protocol numbers are spot-checked per release; unknown versions fall back
    // to an older value so the client upgrades the world on first load.
    private static final int DEFAULT_PROTOCOL = 748;

    /** Versions without confirmed protocol entries continue using this. */
    private static final int[] DEFAULT_WORLD_VERSION = {1, 21, 50, 0, 0};

    public static class BlockLayer {
        public String blockName;
        public int count;

        public BlockLayer(String blockName, int count) {
            this.blockName = blockName;
            this.count = count;
        }
    }

    public static class PackRef {
        public final File packDir;     // source folder with manifest.json
        public final boolean behavior; // true = behavior pack, false = resource pack

        public PackRef(File packDir, boolean behavior) {
            this.packDir = packDir;
            this.behavior = behavior;
        }
    }

    public static class WorldOptions {
        public String worldName = "My World";
        public String seedText;             // null/empty = random seed
        public int gameMode = GAME_MODE_SURVIVAL;
        public int difficulty = 2;          // 0 peaceful .. 3 hard
        public boolean hardcore;
        public boolean cheats;
        public boolean commandBlocks;
        public boolean educationFeatures;

        public boolean flat;
        public List<BlockLayer> layers;     // only used when flat
        public int flatBiomeId = 1;

        public boolean startWithMap;
        public boolean bonusChest;
        public int simulationDistance = 4;  // chunks
        public int respawnRadius = 10;
        public int playerSleepingPercentage = 100;

        // Gamerule toggles
        public boolean showCoordinates;
        public boolean showDaysPlayed;
        public boolean recipeUnlocking = true;
        public boolean fireSpreads = true;
        public boolean tntExplodes = true;
        public boolean mobLoot = true;
        public boolean naturalRegeneration = true;
        public boolean tileDrops = true;
        public boolean mobSpawning = true;
        public boolean mobGriefing = true;
        public boolean entitiesDropLoot = true;
        public boolean weatherCycle = true;
        public boolean immediateRespawn;
        public boolean respawnBlocksExplode = true;
        public boolean keepInventory;

        public List<PackRef> packs = new ArrayList<>();

        // Instance game version, e.g. "1.26.52.3"; null -> legacy defaults
        public String gameVersionName;
    }

    /** @return whether this world can no longer earn achievements for these settings. */
    public static boolean disablesAchievements(WorldOptions options) {
        return options.gameMode == GAME_MODE_CREATIVE
                || options.cheats
                || options.flat
                || options.educationFeatures;
    }

    public static File generate(File worldsDirectory, WorldOptions options) throws IOException {
        String safeName = options.worldName.replaceAll("[^a-zA-Z0-9_\\- ]", "_");
        String folderName = safeName + "_" + System.currentTimeMillis();
        File worldDir = new File(worldsDirectory, folderName);

        if (!worldDir.mkdirs()) {
            throw new IOException("Failed to create world directory");
        }

        try {
            boolean flat = options.flat && options.layers != null && !options.layers.isEmpty();

            writeFile(new File(worldDir, "levelname.txt"), options.worldName);
            writeLevelDat(worldDir, options, flat);
            copyPacksIntoWorld(worldDir, options);
            writePackRefs(worldDir, options);
            copyFile(new File(worldDir, "level.dat"), new File(worldDir, "level.dat_old"));
            createDbDirectory(worldDir);

            Log.d(TAG, "Generated world '" + options.worldName + "' (flat=" + flat
                    + ", version=" + options.gameVersionName + ")");
            return worldDir;
        } catch (Exception e) {
            deleteDirectory(worldDir);
            throw new IOException("Failed to create world: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ NBT

    private static void writeLevelDat(File worldDir, WorldOptions options, boolean flat) throws IOException {
        Map<String, NbtTag> compound = new LinkedHashMap<>();

        long currentTime = System.currentTimeMillis() / 1000;
        long seed;
        try {
            seed = options.seedText == null || options.seedText.trim().isEmpty()
                    ? new Random().nextLong() : Long.parseLong(options.seedText.trim());
        } catch (NumberFormatException numberFormatException) {
            // shortcut: non-numeric seed is hashed, not mapped like the in-game
            // text-seed algorithm, so terrain differs from the same name in-game
            seed = options.seedText.trim().hashCode() * 25214903917L;
        }

        int spawnY = flat ? flatSpawnY(options.layers) : 32767; // game resolves surface spawn

        compound.put("LevelName", new NbtTag(NbtTag.TAG_STRING, "LevelName", options.worldName));
        compound.put("GameType", new NbtTag(NbtTag.TAG_INT, "GameType", options.gameMode));
        compound.put("Generator", new NbtTag(NbtTag.TAG_INT, "Generator", flat ? 2 : 0));
        compound.put("RandomSeed", new NbtTag(NbtTag.TAG_LONG, "RandomSeed", seed));
        compound.put("IsHardcore", new NbtTag(NbtTag.TAG_BYTE, "IsHardcore", (byte) (options.hardcore ? 1 : 0)));
        compound.put("Difficulty", new NbtTag(NbtTag.TAG_INT, "Difficulty", options.hardcore ? 3 : options.difficulty));
        compound.put("ForceGameType", new NbtTag(NbtTag.TAG_BYTE, "ForceGameType", (byte) 0));
        compound.put("commandsEnabled", new NbtTag(NbtTag.TAG_BYTE, "commandsEnabled", (byte) (options.cheats ? 1 : 0)));
        compound.put("commandblocksenabled", new NbtTag(NbtTag.TAG_BYTE, "commandblocksenabled",
                (byte) (options.commandBlocks ? 1 : 0)));
        compound.put("educationFeaturesEnabled", new NbtTag(NbtTag.TAG_BYTE, "educationFeaturesEnabled",
                (byte) (options.educationFeatures ? 1 : 0)));
        compound.put("eduOffer", new NbtTag(NbtTag.TAG_INT, "eduOffer", 0));

        if (flat) {
            compound.put("FlatWorldLayers", new NbtTag(NbtTag.TAG_STRING, "FlatWorldLayers",
                    buildFlatWorldLayersJson(options.layers, options.flatBiomeId)));
        }

        compound.put("SpawnX", new NbtTag(NbtTag.TAG_INT, "SpawnX", 0));
        compound.put("SpawnY", new NbtTag(NbtTag.TAG_INT, "SpawnY", spawnY));
        compound.put("SpawnZ", new NbtTag(NbtTag.TAG_INT, "SpawnZ", 0));

        addGamerules(compound, options);

        compound.put("Time", new NbtTag(NbtTag.TAG_LONG, "Time", 0L));
        compound.put("LastPlayed", new NbtTag(NbtTag.TAG_LONG, "LastPlayed", currentTime));
        compound.put("Platform", new NbtTag(NbtTag.TAG_INT, "Platform", 2));
        compound.put("StorageVersion", new NbtTag(NbtTag.TAG_INT, "StorageVersion", 10));
        compound.put("worldStartCount", new NbtTag(NbtTag.TAG_LONG, "worldStartCount", 0L));

        // hasBeenLoadedInCreative is the persisted "achievements disabled" lock:
        // keep it 0 for survival + cheats off so add-ons don't void achievements.
        boolean achievementsDisabled = disablesAchievements(options);
        compound.put("hasBeenLoadedInCreative", new NbtTag(NbtTag.TAG_BYTE, "hasBeenLoadedInCreative",
                (byte) (achievementsDisabled || options.hardcore ? 1 : 0)));
        compound.put("hasLockedBehaviorPack", new NbtTag(NbtTag.TAG_BYTE, "hasLockedBehaviorPack", (byte) 0));
        compound.put("hasLockedResourcePack", new NbtTag(NbtTag.TAG_BYTE, "hasLockedResourcePack", (byte) 0));
        compound.put("isFromLockedTemplate", new NbtTag(NbtTag.TAG_BYTE, "isFromLockedTemplate", (byte) 0));
        compound.put("isFromWorldTemplate", new NbtTag(NbtTag.TAG_BYTE, "isFromWorldTemplate", (byte) 0));
        compound.put("isSingleUseWorld", new NbtTag(NbtTag.TAG_BYTE, "isSingleUseWorld", (byte) 0));
        compound.put("isWorldTemplateOptionLocked", new NbtTag(NbtTag.TAG_BYTE, "isWorldTemplateOptionLocked", (byte) 0));
        compound.put("experiments_ever_used", new NbtTag(NbtTag.TAG_BYTE, "experiments_ever_used", (byte) 0));
        compound.put("experiments", new NbtTag(NbtTag.TAG_COMPOUND, "experiments", new LinkedHashMap<String, NbtTag>()));
        compound.put("saved_with_toggled_experiments", new NbtTag(NbtTag.TAG_BYTE, "saved_with_toggled_experiments", (byte) 0));
        compound.put("lightningLevel", new NbtTag(NbtTag.TAG_FLOAT, "lightningLevel", 0.0f));
        compound.put("lightningTime", new NbtTag(NbtTag.TAG_INT, "lightningTime", 0));
        compound.put("rainLevel", new NbtTag(NbtTag.TAG_FLOAT, "rainLevel", 0.0f));
        compound.put("rainTime", new NbtTag(NbtTag.TAG_INT, "rainTime", 0));
        compound.put("BiomeOverride", new NbtTag(NbtTag.TAG_STRING, "BiomeOverride", ""));
        compound.put("CenterMapsToOrigin", new NbtTag(NbtTag.TAG_BYTE, "CenterMapsToOrigin", (byte) 0));
        compound.put("ConfirmedPlatformLockedContent", new NbtTag(NbtTag.TAG_BYTE, "ConfirmedPlatformLockedContent", (byte) 0));
        compound.put("currentTick", new NbtTag(NbtTag.TAG_LONG, "currentTick", 0L));
        compound.put("immutableWorld", new NbtTag(NbtTag.TAG_BYTE, "immutableWorld", (byte) 0));
        compound.put("LANBroadcast", new NbtTag(NbtTag.TAG_BYTE, "LANBroadcast", (byte) 1));
        compound.put("LANBroadcastIntent", new NbtTag(NbtTag.TAG_BYTE, "LANBroadcastIntent", (byte) 1));
        compound.put("LimitedWorldOriginX", new NbtTag(NbtTag.TAG_INT, "LimitedWorldOriginX", 0));
        compound.put("LimitedWorldOriginY", new NbtTag(NbtTag.TAG_INT, "LimitedWorldOriginY", 32767));
        compound.put("LimitedWorldOriginZ", new NbtTag(NbtTag.TAG_INT, "LimitedWorldOriginZ", 0));
        compound.put("limitedWorldWidth", new NbtTag(NbtTag.TAG_INT, "limitedWorldWidth", 16));
        compound.put("limitedWorldDepth", new NbtTag(NbtTag.TAG_INT, "limitedWorldDepth", 16));
        compound.put("MultiplayerGame", new NbtTag(NbtTag.TAG_BYTE, "MultiplayerGame", (byte) 1));
        compound.put("MultiplayerGameIntent", new NbtTag(NbtTag.TAG_BYTE, "MultiplayerGameIntent", (byte) 1));
        compound.put("requiresCopiedPackRemovalCheck", new NbtTag(NbtTag.TAG_BYTE, "requiresCopiedPackRemovalCheck", (byte) 0));
        compound.put("SpawnV1Villagers", new NbtTag(NbtTag.TAG_BYTE, "SpawnV1Villagers", (byte) 0));
        compound.put("startWithMapEnabled", new NbtTag(NbtTag.TAG_BYTE, "startWithMapEnabled",
                (byte) (options.startWithMap ? 1 : 0)));
        compound.put("bonusChestEnabled", new NbtTag(NbtTag.TAG_BYTE, "bonusChestEnabled",
                (byte) (options.bonusChest ? 1 : 0)));
        compound.put("bonusChestSpawned", new NbtTag(NbtTag.TAG_BYTE, "bonusChestSpawned", (byte) 0));
        compound.put("texturePacksRequired", new NbtTag(NbtTag.TAG_BYTE, "texturePacksRequired", (byte) 0));
        compound.put("useMsaGamertagsOnly", new NbtTag(NbtTag.TAG_BYTE, "useMsaGamertagsOnly", (byte) 0));
        compound.put("WorldVersion", new NbtTag(NbtTag.TAG_INT, "WorldVersion", 1));
        compound.put("prid", new NbtTag(NbtTag.TAG_STRING, "prid", ""));
        compound.put("worldPolicies", new NbtTag(NbtTag.TAG_COMPOUND, "worldPolicies", new LinkedHashMap<String, NbtTag>()));

        Map<String, NbtTag> abilities = new LinkedHashMap<>();
        abilities.put("attackmobs", new NbtTag(NbtTag.TAG_BYTE, "attackmobs", (byte) 1));
        abilities.put("attackplayers", new NbtTag(NbtTag.TAG_BYTE, "attackplayers", (byte) 1));
        abilities.put("build", new NbtTag(NbtTag.TAG_BYTE, "build", (byte) 1));
        abilities.put("doorsandswitches", new NbtTag(NbtTag.TAG_BYTE, "doorsandswitches", (byte) 1));
        abilities.put("flySpeed", new NbtTag(NbtTag.TAG_FLOAT, "flySpeed", 0.05f));
        abilities.put("flying", new NbtTag(NbtTag.TAG_BYTE, "flying",
                (byte) (options.gameMode == GAME_MODE_CREATIVE ? 1 : 0)));
        abilities.put("instabuild", new NbtTag(NbtTag.TAG_BYTE, "instabuild",
                (byte) (options.gameMode == GAME_MODE_CREATIVE ? 1 : 0)));
        abilities.put("invulnerable", new NbtTag(NbtTag.TAG_BYTE, "invulnerable",
                (byte) (options.gameMode == GAME_MODE_CREATIVE ? 1 : 0)));
        abilities.put("lightning", new NbtTag(NbtTag.TAG_BYTE, "lightning", (byte) 0));
        abilities.put("mayfly", new NbtTag(NbtTag.TAG_BYTE, "mayfly",
                (byte) (options.gameMode == GAME_MODE_CREATIVE ? 1 : 0)));
        abilities.put("mine", new NbtTag(NbtTag.TAG_BYTE, "mine", (byte) 1));
        abilities.put("op", new NbtTag(NbtTag.TAG_BYTE, "op", (byte) (options.cheats ? 1 : 0)));
        abilities.put("opencontainers", new NbtTag(NbtTag.TAG_BYTE, "opencontainers", (byte) 1));
        abilities.put("permissionsLevel", new NbtTag(NbtTag.TAG_INT, "permissionsLevel", 1));
        abilities.put("playerPermissionsLevel", new NbtTag(NbtTag.TAG_INT, "playerPermissionsLevel", 1));
        abilities.put("teleport", new NbtTag(NbtTag.TAG_BYTE, "teleport", (byte) (options.cheats ? 1 : 0)));
        abilities.put("walkSpeed", new NbtTag(NbtTag.TAG_FLOAT, "walkSpeed", 0.1f));
        compound.put("abilities", new NbtTag(NbtTag.TAG_COMPOUND, "abilities", abilities));

        int[] version = parseVersionParts(options.gameVersionName);
        compound.put("baseGameVersion", new NbtTag(NbtTag.TAG_STRING, "baseGameVersion", "*"));
        compound.put("InventoryVersion", new NbtTag(NbtTag.TAG_STRING, "InventoryVersion",
                inventoryVersionString(version)));
        compound.put("lastOpenedWithVersion", versionArray("lastOpenedWithVersion", version));
        compound.put("MinimumCompatibleClientVersion", versionArray("MinimumCompatibleClientVersion", version));
        compound.put("NetworkVersion", new NbtTag(NbtTag.TAG_INT, "NetworkVersion", protocolFor(version)));

        NbtTag root = new NbtTag(NbtTag.TAG_COMPOUND, "", compound);

        BedrockNbtWriter writer = new BedrockNbtWriter();
        writer.setHeaderVersion(10);
        writer.writeFile(new File(worldDir, "level.dat"), root);
    }

    private static void addGamerules(Map<String, NbtTag> compound, WorldOptions options) {
        putByte(compound, "dodaylightcycle", true);
        putByte(compound, "dofiretick", options.fireSpreads);
        putByte(compound, "domobspawning", options.mobSpawning);
        putByte(compound, "domobloot", options.mobLoot);
        putByte(compound, "doentitydrops", options.entitiesDropLoot);
        putByte(compound, "dotiledrops", options.tileDrops);
        putByte(compound, "doweathercycle", options.weatherCycle);
        putByte(compound, "doimmediaterespawn", options.immediateRespawn);
        putByte(compound, "naturalregeneration", options.naturalRegeneration);
        putByte(compound, "keepinventory", options.keepInventory);
        putByte(compound, "mobgriefing", options.mobGriefing);
        putByte(compound, "pvp", true);
        putByte(compound, "spawnMobs", options.mobSpawning);
        putByte(compound, "showcoordinates", options.showCoordinates);
        putByte(compound, "showdaysplayed", options.showDaysPlayed);
        putByte(compound, "recipesunlock", options.recipeUnlocking);
        putByte(compound, "respawnblocksexplode", options.respawnBlocksExplode);
        putByte(compound, "tntexplodes", options.tntExplodes);
        compound.put("randomtickspeed", new NbtTag(NbtTag.TAG_INT, "randomtickspeed", 1));
        compound.put("spawnradius", new NbtTag(NbtTag.TAG_INT, "spawnradius",
                Math.max(0, Math.min(options.respawnRadius, 128))));
        compound.put("playersleepingpercentage", new NbtTag(NbtTag.TAG_INT, "playersleepingpercentage",
                Math.max(0, Math.min(options.playerSleepingPercentage, 100))));
        compound.put("serverChunkTickRange", new NbtTag(NbtTag.TAG_INT, "serverChunkTickRange",
                Math.max(1, options.simulationDistance)));
        compound.put("maxcommandchainlength", new NbtTag(NbtTag.TAG_INT, "maxcommandchainlength", 65536));
        compound.put("functioncommandlimit", new NbtTag(NbtTag.TAG_INT, "functioncommandlimit", 10000));
    }

    private static void putByte(Map<String, NbtTag> compound, String key, boolean value) {
        compound.put(key, new NbtTag(NbtTag.TAG_BYTE, key, (byte) (value ? 1 : 0)));
    }

    // ------------------------------------------------------------- packs

    private static void writePackRefs(File worldDir, WorldOptions options) throws IOException {
        JSONArray behaviorRefs = new JSONArray();
        JSONArray resourceRefs = new JSONArray();

        for (PackRef packRef : options.packs) {
            JSONObject header = readManifestHeader(packRef.packDir);
            if (header == null) continue;
            String uuid = header.optString("uuid", null);
            if (uuid == null) continue;

            JSONObject ref;
            try {
                ref = new JSONObject();
                ref.put("pack_id", uuid);
                JSONArray versionArray = new JSONArray();
                for (int part : headerVersion(header)) versionArray.put(part);
                ref.put("version", versionArray);
            } catch (Exception exception) {
                Log.w(TAG, "Skipping pack with invalid manifest: " + packRef.packDir, exception);
                continue;
            }
            if (packRef.behavior) {
                behaviorRefs.put(ref);
            } else {
                resourceRefs.put(ref);
            }
        }

        writeFile(new File(worldDir, "world_behavior_packs.json"), behaviorRefs.toString());
        writeFile(new File(worldDir, "world_resource_packs.json"), resourceRefs.toString());
    }

    private static JSONObject readManifestHeader(File packDir) {
        File manifest = new File(packDir, "manifest.json");
        if (!manifest.isFile()) return null;
        try {
            JSONObject json = new JSONObject(readText(manifest));
            return json.optJSONObject("header");
        } catch (Exception exception) {
            Log.w(TAG, "Unreadable manifest in " + packDir, exception);
            return null;
        }
    }

    private static int[] headerVersion(JSONObject header) {
        JSONArray versionArray = header.optJSONArray("version");
        int[] parts = versionArray == null ? new int[0] : new int[versionArray.length()];
        for (int i = 0; i < parts.length; i++) parts[i] = versionArray.optInt(i);
        if (parts.length == 0) return new int[]{0};
        return parts;
    }

    /**
     * Copies selected packs into the world folder: Bedrock resolves
     * world_behavior_packs.json entries in world-local behavior_packs/
     * resource_packs/ dirs. product_type=addon (documented manifest marker)
     * keeps modern clients from voiding achievements for behavior packs.
     */
    private static void copyPacksIntoWorld(File worldDir, WorldOptions options) throws IOException {
        for (PackRef packRef : options.packs) {
            File destination = nextFreeDirectory(new File(worldDir, packRef.behavior ? "behavior_packs" : "resource_packs"),
                    packRef.packDir.getName());
            copyDirectory(packRef.packDir, destination);
            if (packRef.behavior) {
                ensureAddonProductType(new File(destination, "manifest.json"));
            }
        }
    }

    /** Adds metadata.product_type=addon when the manifest doesn't declare it. */
    private static void ensureAddonProductType(File manifestFile) {
        if (!manifestFile.isFile()) return;
        try {
            JSONObject manifest = new JSONObject(readText(manifestFile));
            JSONObject metadata = manifest.optJSONObject("metadata");
            boolean declared = metadata != null && "addon".equals(metadata.optString("product_type", null));
            if (declared) return;
            if (metadata == null) metadata = new JSONObject();
            metadata.put("product_type", "addon");
            manifest.put("metadata", metadata);
            writeFile(manifestFile, manifest.toString());
        } catch (Exception exception) {
            Log.w(TAG, "Failed to patch manifest product_type in " + manifestFile, exception);
        }
    }

    private static File nextFreeDirectory(File parent, String baseName) {
        File destination = new File(parent, baseName);
        int suffix = 2;
        while (destination.exists()) {
            destination = new File(parent, baseName + "_" + suffix++);
        }
        return destination;
    }

    // ------------------------------------------------------------- layers

    public static List<BlockLayer> getDefaultLayers() {
        List<BlockLayer> layers = new ArrayList<>();
        layers.add(new BlockLayer("minecraft:bedrock", 1));
        layers.add(new BlockLayer("minecraft:dirt", 2));
        layers.add(new BlockLayer("minecraft:grass_block", 1));
        return layers;
    }

    public static String buildFlatWorldLayersJson(List<BlockLayer> layers, int biomeId) {
        try {
            JSONObject root = new JSONObject();
            root.put("biome_id", biomeId);

            JSONArray blockLayers = new JSONArray();
            for (BlockLayer layer : layers) {
                JSONObject layerObj = new JSONObject();
                layerObj.put("block_name", layer.blockName);
                layerObj.put("count", layer.count);
                blockLayers.put(layerObj);
            }
            root.put("block_layers", blockLayers);
            root.put("encoding_version", 6);
            root.put("structure_options", JSONObject.NULL);
            root.put("world_version", "version.post_1_18");
            return root.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to build flat world layers JSON", e);
            return "{\"biome_id\":1,\"block_layers\":[{\"block_name\":\"minecraft:bedrock\",\"count\":1},{\"block_name\":\"minecraft:dirt\",\"count\":2},{\"block_name\":\"minecraft:grass_block\",\"count\":1}],\"encoding_version\":6,\"structure_options\":null,\"world_version\":\"version.post_1_18\"}";
        }
    }

    // ------------------------------------------------------------- version helpers (unit-tested)

    static int[] parseVersionParts(String versionCode) {
        if (versionCode == null) return null;
        List<Integer> parts = new ArrayList<>();
        for (String token : versionCode.trim().split("\\.")) {
            String numbers = token.replaceAll("\\D", "");
            if (numbers.isEmpty()) break;
            parts.add(Integer.parseInt(numbers));
        }
        if (parts.isEmpty()) return null;
        int[] result = new int[Math.max(parts.size(), 5)];
        for (int i = 0; i < parts.size(); i++) result[i] = parts.get(i);
        return result;
    }

    static String inventoryVersionString(int[] parts) {
        if (parts == null || parts.length < 3) return "1.21.50";
        StringBuilder sb = new StringBuilder();
        sb.append(parts[0]).append('.').append(parts[1]).append('.').append(parts[2]);
        if (parts.length >= 4) sb.append('.').append(parts[3]);
        return sb.toString();
    }

    static int protocolFor(int[] parts) {
        if (parts == null || parts.length < 3) return DEFAULT_PROTOCOL;
        String known = PROTOCOLS.get(parts[0] + "." + parts[1] + "." + parts[2]);
        return known != null ? Integer.parseInt(known) : DEFAULT_PROTOCOL;
    }

    static final Map<String, String> PROTOCOLS = new LinkedHashMap<>();
    static {
        PROTOCOLS.put("1.21.40", "748");
        PROTOCOLS.put("1.21.44", "748");
        PROTOCOLS.put("1.21.50", "766");
        PROTOCOLS.put("1.21.51", "766");
        PROTOCOLS.put("1.21.111", "844");
        PROTOCOLS.put("1.21.114", "844");
        PROTOCOLS.put("1.21.120", "859");
        PROTOCOLS.put("1.21.123", "859");
        PROTOCOLS.put("1.21.124", "860");
        PROTOCOLS.put("1.26.52", "2193"); // current release line as of 26.52
    }

    private static NbtTag versionArray(String name, int[] parts) {
        int[] source = parts != null ? parts : DEFAULT_WORLD_VERSION;
        List<NbtTag> version = new ArrayList<>();
        for (int part : source) {
            version.add(new NbtTag(NbtTag.TAG_INT, "", part));
        }
        return new NbtTag(NbtTag.TAG_LIST, name, version);
    }

    // ------------------------------------------------------------- files

    private static int flatSpawnY(List<BlockLayer> layers) {
        int totalHeight = 0;
        for (BlockLayer layer : layers) totalHeight += layer.count;
        return Math.max(totalHeight + 2, 4);
    }

    private static void writeFile(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = fis.read(data);
            if (read < data.length) throw new IOException("Short read on " + file);
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }

    private static void copyDirectory(File source, File destination) throws IOException {
        if (!destination.exists() && !destination.mkdirs()) {
            throw new IOException("Failed to create " + destination);
        }
        File[] files = source.listFiles();
        if (files == null) return;
        for (File file : files) {
            File target = new File(destination, file.getName());
            if (file.isDirectory()) {
                copyDirectory(file, target);
            } else {
                copyFile(file, target);
            }
        }
    }

    private static void createDbDirectory(File worldDir) {
        File dbDir = new File(worldDir, "db");
        dbDir.mkdirs();
    }

    private static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
}
