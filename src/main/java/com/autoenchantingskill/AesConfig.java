package com.autoenchantingskill;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Semua pengaturan mod, disimpan di config/autoenchantingskill.json */
public class AesConfig {
    public enum Tier { SLOT1, SLOT2, SLOT3, SMART }
    public enum MatchMode { ALL, ANY }

    /** id item yang dijadikan target (mis. minecraft:diamond_pickaxe) */
    public Set<String> selectedItems = new LinkedHashSet<>();
    /** per item: id enchant -> level minimal yang diinginkan */
    public Map<String, Map<String, Integer>> wanted = new HashMap<>();

    public Tier tier = Tier.SMART;
    public MatchMode matchMode = MatchMode.ALL;
    public int cycles = 100;          // jumlah percobaan enchant maksimum
    public boolean infinite = false;  // true = tak terbatas
    public int goalSuccesses = 1;     // berhenti setelah N item sesuai (0 = sampai item habis)
    public boolean sound = true;
    public int actionDelay = 3;       // jeda antar aksi (tick)
    public int xpCollectTicks = 15;   // tunggu orb XP dari grindstone terserap

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AesConfig instance;

    public static AesConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    public Map<String, Integer> wanted(String itemId) {
        return wanted.computeIfAbsent(itemId, k -> new HashMap<>());
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("autoenchantingskill.json");
    }

    private static AesConfig load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                AesConfig c = GSON.fromJson(Files.readString(f), AesConfig.class);
                if (c != null) { c.fix(); return c; }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return new AesConfig();
    }

    private void fix() {
        if (selectedItems == null) selectedItems = new LinkedHashSet<>();
        if (wanted == null) wanted = new HashMap<>();
        if (tier == null) tier = Tier.SMART;
        if (matchMode == null) matchMode = MatchMode.ALL;
        cycles = Math.max(1, cycles);
        actionDelay = Math.max(1, actionDelay);
    }

    public static void save() {
        try { Files.writeString(file(), GSON.toJson(get())); }
        catch (IOException e) { e.printStackTrace(); }
    }
}
