package com.moulberry.flashback;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager;
import net.fabricmc.fabric.impl.registry.sync.RemappableRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Saves the raw (numeric) registry IDs that were in use while recording, and restores them when a replay is opened.
 *
 * <p>When you join a multiplayer server, Fabric's registry sync remaps the client's raw IDs to match the server.
 * Recorded packets (chunk palettes, block updates, items...) contain those server-side IDs. When the replay is
 * opened later the client is back on its own local ordering, so if the server's mod set registered things in a
 * different order (e.g. Yuushya addon packs, server-only mods), blocks show up as the wrong block.</p>
 *
 * <p>This snapshots the synced ID table at record start and re-applies it with Fabric's own remapper before the
 * replay world loads. Fabric automatically unmaps again on disconnect, so leaving the replay restores everything.</p>
 */
public final class RegistryIdSnapshot {

    public static final String FILE_NAME = "fabric_registry_ids.json";
    private static final int FORMAT_VERSION = 1;
    private static final Gson GSON = new Gson();

    private RegistryIdSnapshot() {
    }

    /**
     * Captures the current (possibly server-synced) raw IDs of every modded, synced registry.
     *
     * @return JSON to store in the replay, or null if there is nothing worth saving
     */
    @Nullable
    public static String capture() {
        try {
            Map<ResourceLocation, Object2IntMap<ResourceLocation>> map = RegistrySyncManager.createAndPopulateRegistryMap();
            if (map == null || map.isEmpty()) {
                return null;
            }

            JsonObject registries = new JsonObject();
            for (Map.Entry<ResourceLocation, Object2IntMap<ResourceLocation>> entry : map.entrySet()) {
                Object2IntMap<ResourceLocation> ids = entry.getValue();

                int max = -1;
                for (int rawId : ids.values()) {
                    max = Math.max(max, rawId);
                }

                // Index in the array == raw id. Holes (shouldn't normally exist) are written as null.
                String[] byRawId = new String[max + 1];
                for (Object2IntMap.Entry<ResourceLocation> idEntry : ids.object2IntEntrySet()) {
                    byRawId[idEntry.getIntValue()] = idEntry.getKey().toString();
                }

                JsonArray array = new JsonArray(byRawId.length);
                for (String id : byRawId) {
                    array.add(id);
                }
                registries.add(entry.getKey().toString(), array);
            }

            JsonObject root = new JsonObject();
            root.addProperty("version", FORMAT_VERSION);
            root.add("registries", registries);
            return GSON.toJson(root);
        } catch (Throwable t) {
            Flashback.LOGGER.error("Unable to capture registry ids for recording", t);
            return null;
        }
    }

    /**
     * Reads the saved ID table from a replay zip, if it has one.
     */
    @Nullable
    public static Map<ResourceLocation, Object2IntMap<ResourceLocation>> readFromReplay(Path replayZip) {
        try (FileSystem fs = FileSystems.newFileSystem(replayZip)) {
            Path path = fs.getPath("/" + FILE_NAME);
            if (!Files.exists(path)) {
                return null;
            }
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Flashback.LOGGER.error("Unable to read {} from replay {}", FILE_NAME, replayZip, t);
            return null;
        }
    }

    @Nullable
    private static Map<ResourceLocation, Object2IntMap<ResourceLocation>> parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        int version = root.has("version") ? root.get("version").getAsInt() : -1;
        if (version != FORMAT_VERSION) {
            Flashback.LOGGER.warn("Unknown {} version {}, ignoring", FILE_NAME, version);
            return null;
        }

        Map<ResourceLocation, Object2IntMap<ResourceLocation>> map = new LinkedHashMap<>();
        JsonObject registries = root.getAsJsonObject("registries");

        for (Map.Entry<String, JsonElement> entry : registries.entrySet()) {
            ResourceLocation registryId = ResourceLocation.tryParse(entry.getKey());
            if (registryId == null) {
                continue;
            }

            JsonArray array = entry.getValue().getAsJsonArray();
            Object2IntMap<ResourceLocation> ids = new Object2IntLinkedOpenHashMap<>(array.size());
            for (int rawId = 0; rawId < array.size(); rawId++) {
                JsonElement element = array.get(rawId);
                if (element == null || element.isJsonNull()) {
                    continue;
                }
                ResourceLocation id = ResourceLocation.tryParse(element.getAsString());
                if (id != null) {
                    ids.put(id, rawId);
                }
            }
            map.put(registryId, ids);
        }

        return map.isEmpty() ? null : map;
    }

    /**
     * Applies the replay's saved ID table to the client registries. Must be called on the render thread
     * <b>after</b> disconnecting from any previous world (which makes Fabric unmap) and before the replay world loads.
     *
     * @return true if the IDs were applied
     */
    public static boolean applyForReplay(Path replayZip) {
        Map<ResourceLocation, Object2IntMap<ResourceLocation>> map = readFromReplay(replayZip);
        if (map == null) {
            // Older replay, or one recorded without any modded registries: keep the old behaviour.
            return false;
        }

        try {
            int filled = fillHoles(map);
            RegistrySyncManager.apply(map, RemappableRegistry.RemapMode.REMOTE);
            Flashback.LOGGER.info("Applied recorded registry ids for replay {} ({} registries, {} holes filled)", replayZip.getFileName(), map.size(), filled);
            return true;
        } catch (Throwable t) {
            // Most likely the replay contains entries from a mod that isn't installed. Undo any partial
            // remap and fall back to local ids, which is exactly how Flashback behaved before this patch.
            Flashback.LOGGER.error("Unable to apply recorded registry ids for replay {}, falling back to local ids", replayZip.getFileName(), t);
            try {
                RegistrySyncManager.unmap();
            } catch (Throwable t2) {
                Flashback.LOGGER.error("Unable to unmap registries after failed remap", t2);
            }
            return false;
        }
    }

    /**
     * Makes every saved ID table dense (no unused raw ids) by assigning local entries that the table doesn't mention
     * into the gaps, then appending any that are left over after the highest id.
     *
     * <p>Gaps happen when the server hides some entries from registry sync, e.g. Polymer's server-side-only
     * blocks/items. Fabric's REMOTE remap leaves those raw ids empty, which is fine on a normal client but breaks
     * playback: Flashback runs an internal server on the same registries, and code that iterates a registry
     * (Polymer's sync) hits a null, and Fabric's own unmap throws "Unused id". Recorded packets never reference
     * the gap ids (the server never sent them), so filling them doesn't change how the replay decodes.</p>
     *
     * @return the number of gaps filled across all registries
     */
    private static int fillHoles(Map<ResourceLocation, Object2IntMap<ResourceLocation>> map) {
        int filled = 0;

        for (Map.Entry<ResourceLocation, Object2IntMap<ResourceLocation>> entry : map.entrySet()) {
            Registry<?> registry = BuiltInRegistries.REGISTRY.get(entry.getKey());
            if (registry == null) {
                continue;
            }

            Object2IntMap<ResourceLocation> ids = entry.getValue();

            int max = -1;
            BitSet used = new BitSet();
            for (int rawId : ids.values()) {
                used.set(rawId);
                max = Math.max(max, rawId);
            }

            // Local entries missing from the saved table, in local raw id order for determinism
            List<ResourceLocation> localOnly = new ArrayList<>();
            for (Object value : registry) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                ResourceLocation id = ((Registry) registry).getKey(value);
                if (id != null && !ids.containsKey(id)) {
                    localOnly.add(id);
                }
            }

            int nextFree = used.nextClearBit(0);
            for (ResourceLocation id : localOnly) {
                if (nextFree < max) {
                    ids.put(id, nextFree);
                    used.set(nextFree);
                    filled++;
                    nextFree = used.nextClearBit(nextFree + 1);
                } else {
                    ids.put(id, ++max);
                }
            }

            if (nextFree < max) {
                // More gaps than local-only entries: a remap would still leave holes. Rather than risk the crash,
                // fail so the caller falls back to local ids (the stock Flashback behaviour).
                throw new IllegalStateException("Registry " + entry.getKey() + " still has unused raw id " + nextFree
                    + " after filling, cannot apply recorded ids safely");
            }
        }

        return filled;
    }

    /**
     * Copies the ID table from one replay zip into a zip that's being written, used when combining replays.
     */
    public static void copyFromFileSystem(FileSystem source, ZipOutputStream zipOut) throws IOException {
        Path path = source.getPath("/" + FILE_NAME);
        if (Files.exists(path)) {
            zipOut.putNextEntry(new ZipEntry(FILE_NAME));
            Files.copy(path, zipOut);
            zipOut.closeEntry();
        }
    }

}
