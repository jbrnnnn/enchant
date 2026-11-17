package com.autoenchantingskill;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.*;

/** Daftar semua tools & senjata yang bisa di-enchant (dibaca dari registry, jadi ikut versi game/mod lain). */
public final class ItemCatalog {
    public record Category(String label, List<String> itemIds) {}

    private static final String[][] SUFFIX = {
        {"_sword", "Pedang"}, {"_axe", "Kapak"}, {"_pickaxe", "Beliung"},
        {"_shovel", "Sekop"}, {"_hoe", "Cangkul"}, {"_spear", "Tombak"}
    };
    private static final String MISC = "Busur, Trident & Lainnya";
    private static final Set<String> MISC_PATHS = Set.of("bow", "crossbow", "trident", "mace", "fishing_rod");
    private static List<Category> cache;

    private ItemCatalog() {}

    public static List<Category> get() {
        if (cache == null) cache = build();
        return cache;
    }

    private static List<Category> build() {
        LinkedHashMap<String, List<String>> map = new LinkedHashMap<>();
        for (String[] s : SUFFIX) map.put(s[1], new ArrayList<>());
        map.put(MISC, new ArrayList<>());

        for (Item item : Registries.ITEM) {
            Identifier id = Registries.ITEM.getId(item);
            String path = id.getPath();
            String label = null;
            for (String[] s : SUFFIX) if (path.endsWith(s[0])) label = s[1];
            if (label == null && MISC_PATHS.contains(path)) label = MISC;
            if (label == null) continue;
            if (!item.getDefaultStack().isEnchantable()) continue;
            map.get(label).add(id.toString());
        }
        List<Category> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : map.entrySet())
            if (!e.getValue().isEmpty()) out.add(new Category(e.getKey(), e.getValue()));
        return out;
    }

    public static String categoryOf(String itemId) {
        for (Category c : get()) if (c.itemIds().contains(itemId)) return c.label();
        return "";
    }

    public static ItemStack stack(String id) {
        return Registries.ITEM.get(Identifier.of(id)).getDefaultStack();
    }

    public static String idOf(ItemStack s) {
        return Registries.ITEM.getId(s.getItem()).toString();
    }
}
