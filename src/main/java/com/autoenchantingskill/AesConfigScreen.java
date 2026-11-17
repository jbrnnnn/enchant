package com.autoenchantingskill;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.EnchantmentTags;
import net.minecraft.text.Text;

import java.util.*;

/** GUI pengaturan: grid semua tools/senjata, daftar enchant per item, tier, siklus, tombol mulai/stop. */
public class AesConfigScreen extends Screen {
    private static final int PER_PAGE = 6;
    private static final String[] TIER_NAMES = {"Tier 1", "Tier 2", "Tier 3", "SMART"};
    private static final String[] TIER_TIPS = {
        "Slot atas: murah (1 level XP, 1 lapis), enchant level rendah",
        "Slot tengah: 2 level XP, 2 lapis",
        "Slot bawah: paling mahal (3 level XP, 3 lapis), enchant level tertinggi",
        "Otomatis: pilih slot terbaik yang masih terjangkau XP & lapis,\nutamakan slot yang petunjuknya cocok dengan target"
    };

    private final AesConfig cfg = AesConfig.get();
    private final Screen parent;
    private String editing;
    private int page = 0;
    private int px, py, pw, ph;
    private final List<Cell> cells = new ArrayList<>();
    private List<RegistryEntry<Enchantment>> enchList = new ArrayList<>();

    private record Cell(int x, int y, String id, ItemStack stack) {}

    public AesConfigScreen(Screen parent) {
        super(Text.literal("AutoEnchantingSkill"));
        this.parent = parent;
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void removed() { AesConfig.save(); }

    @Override public void close() { if (client != null) client.setScreen(parent); }

    // ------------------------------------------------------------------ layout
    @Override
    protected void init() {
        pw = Math.min(460, width - 6);
        ph = Math.min(262, height - 6);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        cells.clear();

        List<ItemCatalog.Category> cats = ItemCatalog.get();
        if (editing == null) {
            if (!cfg.selectedItems.isEmpty()) editing = cfg.selectedItems.iterator().next();
            else if (!cats.isEmpty()) editing = cats.get(0).itemIds().get(0);
        }
        buildGrid(cats);
        buildEnchantPanel();
        buildBottom();
    }

    private void buildGrid(List<ItemCatalog.Category> cats) {
        int y = py + 26;
        for (ItemCatalog.Category cat : cats) {
            int col = 0;
            for (String id : cat.itemIds()) {
                if (col >= 8) { col = 0; y += 22; }
                int x = px + 10 + col * 22;
                cells.add(new Cell(x, y, id, ItemCatalog.stack(id)));
                addDrawableChild(ButtonWidget.builder(Text.empty(), b -> { editing = id; page = 0; clearAndInit(); })
                    .dimensions(x, y, 20, 20).build());
                col++;
            }
            y += 22;
        }
    }

    private void rebuildEnchList() {
        enchList = new ArrayList<>();
        if (editing == null || client == null || client.world == null) return;
        ItemStack st = ItemCatalog.stack(editing);
        Registry<Enchantment> reg = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        reg.streamEntries()
            .filter(e -> e.value().isAcceptableItem(st) && e.isIn(EnchantmentTags.IN_ENCHANTING_TABLE))
            .sorted(Comparator.comparing(e -> e.value().description().getString()))
            .forEach(enchList::add);
    }

    private void buildEnchantPanel() {
        rebuildEnchList();
        int pages = Math.max(1, (enchList.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages - 1);

        boolean sel = editing != null && cfg.selectedItems.contains(editing);
        addDrawableChild(ButtonWidget.builder(Text.literal(sel ? "§aTarget: ON" : "§7Target: OFF"), b -> {
            if (editing == null) return;
            if (!cfg.selectedItems.remove(editing)) cfg.selectedItems.add(editing);
            clearAndInit();
        }).dimensions(px + 356, py + 22, 94, 18).tooltip(Tooltip.of(Text.literal("Ikut di-enchant otomatis atau tidak"))).build());

        for (int r = 0; r < PER_PAGE; r++) {
            int idx = page * PER_PAGE + r;
            if (idx >= enchList.size()) break;
            RegistryEntry<Enchantment> en = enchList.get(idx);
            int ry = py + 46 + r * 19;
            addDrawableChild(btn("-", px + 352, ry, 18, 16, () -> change(en, -1)));
            addDrawableChild(btn("+", px + 430, ry, 18, 16, () -> change(en, +1)));
        }

        int by = py + 162;
        addDrawableChild(btn("<", px + 196, by, 20, 16, () -> { page = Math.max(0, page - 1); clearAndInit(); }));
        addDrawableChild(btn(">", px + 218, by, 20, 16, () -> { page = Math.min(pages - 1, page + 1); clearAndInit(); }));

        String cat = editing == null ? "" : ItemCatalog.categoryOf(editing);
        addDrawableChild(ButtonWidget.builder(Text.literal("Salin ke semua " + cat), b -> copyToCategory(cat))
            .dimensions(px + 264, by, 186, 16)
            .tooltip(Tooltip.of(Text.literal("Salin pengaturan enchant item ini ke semua item sejenis\ndan jadikan semuanya target")))
            .build());
    }

    private void buildBottom() {
        // --- Tier
        AesConfig.Tier[] tiers = AesConfig.Tier.values();
        for (int i = 0; i < 4; i++) {
            final AesConfig.Tier t = tiers[i];
            boolean on = cfg.tier == t;
            addDrawableChild(ButtonWidget.builder(Text.literal((on ? "§a" : "§f") + TIER_NAMES[i]), b -> { cfg.tier = t; clearAndInit(); })
                .dimensions(px + 40 + i * 76, py + 184, 74, 18).tooltip(Tooltip.of(Text.literal(TIER_TIPS[i]))).build());
        }
        String mm = cfg.matchMode == AesConfig.MatchMode.ALL ? "§aSEMUA cocok" : "§eSALAH SATU";
        addDrawableChild(ButtonWidget.builder(Text.literal("Syarat: " + mm), b -> {
            cfg.matchMode = cfg.matchMode == AesConfig.MatchMode.ALL ? AesConfig.MatchMode.ANY : AesConfig.MatchMode.ALL;
            clearAndInit();
        }).dimensions(px + 346, py + 184, 104, 18)
            .tooltip(Tooltip.of(Text.literal("SEMUA: semua enchant pilihan harus ada\nSALAH SATU: cukup salah satu"))).build());

        // --- Siklus
        addDrawableChild(btn("-10", px + 54, py + 206, 26, 18, () -> addCycles(-10)));
        addDrawableChild(btn("-1", px + 82, py + 206, 22, 18, () -> addCycles(-1)));
        addDrawableChild(btn("+1", px + 154, py + 206, 22, 18, () -> addCycles(1)));
        addDrawableChild(btn("+10", px + 178, py + 206, 26, 18, () -> addCycles(10)));
        addDrawableChild(ButtonWidget.builder(Text.literal(cfg.infinite ? "§bTak terbatas" : "§7Terbatas"), b -> {
            cfg.infinite = !cfg.infinite; clearAndInit();
        }).dimensions(px + 208, py + 206, 78, 18)
            .tooltip(Tooltip.of(Text.literal("Siklus = jumlah percobaan enchant.\nTak terbatas: jalan terus sampai target/lapis/item habis"))).build());

        // --- Target sukses
        addDrawableChild(btn("-", px + 332, py + 206, 16, 18, () -> { cfg.goalSuccesses = Math.max(0, cfg.goalSuccesses - 1); clearAndInit(); }));
        addDrawableChild(btn("+", px + 372, py + 206, 16, 18, () -> { cfg.goalSuccesses = Math.min(999, cfg.goalSuccesses + 1); clearAndInit(); }));
        addDrawableChild(ButtonWidget.builder(Text.literal(cfg.sound ? "§aSuara" : "§7Suara"), b -> { cfg.sound = !cfg.sound; clearAndInit(); })
            .dimensions(px + 394, py + 206, 56, 18).tooltip(Tooltip.of(Text.literal("Bunyi notifikasi (lapis habis, sukses, selesai)"))).build());

        // --- Aksi
        addDrawableChild(ButtonWidget.builder(
            Text.literal(AutoEnchanter.running() ? "§c[] STOP" : "§a> MULAI OTOMATIS"), b -> {
                AesConfig.save();
                if (AutoEnchanter.running()) AutoEnchanter.stop(); else AutoEnchanter.start();
                if (client != null && client.currentScreen == this) clearAndInit();
            }).dimensions(px + 10, py + 228, 130, 20).build());
        addDrawableChild(btn("Selesai", px + 146, py + 228, 64, 20, this::close));
        addDrawableChild(btn("Pilih semua", px + 216, py + 228, 80, 20, () -> {
            for (ItemCatalog.Category c : ItemCatalog.get()) cfg.selectedItems.addAll(c.itemIds());
            clearAndInit();
        }));
        addDrawableChild(btn("Kosongkan", px + 302, py + 228, 72, 20, () -> { cfg.selectedItems.clear(); clearAndInit(); }));
        addDrawableChild(btn("Reset enchant", px + 380, py + 228, 70, 20, () -> {
            if (editing != null) cfg.wanted(editing).clear();
            clearAndInit();
        }));
    }

    private ButtonWidget btn(String label, int x, int y, int w, int h, Runnable r) {
        return ButtonWidget.builder(Text.literal(label), b -> r.run()).dimensions(x, y, w, h).build();
    }

    // ------------------------------------------------------------------ aksi
    private void addCycles(int d) {
        cfg.cycles = Math.max(1, Math.min(100000, cfg.cycles + d));
        clearAndInit();
    }

    private static String key(RegistryEntry<Enchantment> e) {
        return e.getKey().map(k -> k.getValue().toString()).orElse("?");
    }

    private void change(RegistryEntry<Enchantment> en, int delta) {
        if (editing == null) return;
        Map<String, Integer> w = cfg.wanted(editing);
        String k = key(en);
        int cur = w.getOrDefault(k, 0);
        int nl = Math.max(0, Math.min(en.value().getMaxLevel(), cur + delta));
        if (nl <= 0) {
            w.remove(k);
        } else {
            w.put(k, nl);
            cfg.selectedItems.add(editing); // otomatis jadikan target
            // enchant yang saling bertentangan (mis. Sharpness vs Smite) otomatis dimatikan
            for (RegistryEntry<Enchantment> o : enchList) {
                if (o == en) continue;
                String ok = key(o);
                if (w.containsKey(ok) && !Enchantment.canBeCombined(en, o)) w.remove(ok);
            }
        }
        clearAndInit();
    }

    private void copyToCategory(String label) {
        if (editing == null) return;
        Map<String, Integer> src = new HashMap<>(cfg.wanted(editing));
        for (ItemCatalog.Category c : ItemCatalog.get()) {
            if (!c.label().equals(label)) continue;
            for (String id : c.itemIds()) {
                cfg.wanted.put(id, new HashMap<>(src));
                cfg.selectedItems.add(id);
            }
        }
        clearAndInit();
    }

    // ------------------------------------------------------------------ render
    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        super.renderBackground(ctx, mx, my, delta);
        ctx.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, 0xFF7C5CFF);
        ctx.fill(px, py, px + pw, py + ph, 0xF0121220);
        ctx.fillGradient(px, py, px + pw, py + 20, 0xFF4A34B8, 0xFF1C1540);
        ctx.fill(px + 8, py + 24, px + 190, py + 180, 0xFF0B0B14);
        ctx.fill(px + 194, py + 20, px + pw - 8, py + 180, 0xFF0F0F1A);
        ctx.fill(px + 8, py + 181, px + pw - 8, py + 182, 0xFF3A3A5A);
    }

    private static void box(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x, y, x + w, y + 1, col);
        c.fill(x, y + h - 1, x + w, y + h, col);
        c.fill(x, y, x + 1, y + h, col);
        c.fill(x + w - 1, y, x + w, y + h, col);
    }

    private static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);

        ctx.drawTextWithShadow(textRenderer, "AutoEnchantingSkill", px + 10, py + 7, 0xFFFFE066);
        String status = AutoEnchanter.running() ? "§aBERJALAN" : "§7Siap";
        ctx.drawTextWithShadow(textRenderer, "Target: " + cfg.selectedItems.size() + " item  |  " + status,
            px + pw - 10 - textRenderer.getWidth("Target: " + cfg.selectedItems.size() + " item  |  " + status), py + 7, 0xFFFFFFFF);

        Cell hovered = null;
        for (Cell c : cells) {
            ctx.drawItem(c.stack(), c.x() + 2, c.y() + 2);
            if (cfg.selectedItems.contains(c.id())) box(ctx, c.x(), c.y(), 20, 20, 0xFF55FF55);
            if (c.id().equals(editing)) box(ctx, c.x() - 1, c.y() - 1, 22, 22, 0xFFFFDD33);
            if (mx >= c.x() && mx < c.x() + 20 && my >= c.y() && my < c.y() + 20) hovered = c;
        }

        // panel kanan
        if (editing != null) {
            String name = textRenderer.trimToWidth(ItemCatalog.stack(editing).getName().getString(), 140);
            ctx.drawTextWithShadow(textRenderer, "Atur: " + name, px + 198, py + 27, 0xFFFFFFFF);
        }
        Map<String, Integer> w = editing == null ? Map.of() : cfg.wanted(editing);
        for (int r = 0; r < PER_PAGE; r++) {
            int idx = page * PER_PAGE + r;
            if (idx >= enchList.size()) break;
            RegistryEntry<Enchantment> en = enchList.get(idx);
            int ry = py + 46 + r * 19;
            int lvl = w.getOrDefault(key(en), 0);
            String label = textRenderer.trimToWidth(en.value().description().getString(), 146);
            ctx.drawTextWithShadow(textRenderer, label, px + 200, ry + 4, lvl > 0 ? 0xFFFFDD55 : 0xFFAAAAAA);
            String txt = lvl > 0 ? roman(lvl) + " / " + roman(en.value().getMaxLevel()) : "-";
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(txt), px + 400, ry + 4, lvl > 0 ? 0xFFFFDD55 : 0xFF777777);
        }
        if (enchList.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer, "(Buka dari dalam dunia untuk melihat daftar enchant)", px + 198, py + 50, 0xFF888888);
        }
        int pages = Math.max(1, (enchList.size() + PER_PAGE - 1) / PER_PAGE);
        ctx.drawTextWithShadow(textRenderer, (page + 1) + "/" + pages, px + 242, py + 166, 0xFFFFFFFF);

        // bawah
        ctx.drawTextWithShadow(textRenderer, "Tier:", px + 10, py + 190, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, "Siklus:", px + 10, py + 212, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(cfg.infinite ? "§b∞" : String.valueOf(cfg.cycles)), px + 129, py + 212, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, "Sukses:", px + 292, py + 212, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(cfg.goalSuccesses == 0 ? "All" : String.valueOf(cfg.goalSuccesses)), px + 360, py + 212, 0xFFFFFFFF);

        String msg = AutoEnchanter.lastMessage();
        if (!msg.isEmpty()) ctx.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(msg, pw - 20), px + 10, py + 251, 0xFFFFFFFF);

        if (hovered != null) ctx.drawTooltip(textRenderer, ItemCatalog.stack(hovered.id()).getName(), mx, my);
    }
}
