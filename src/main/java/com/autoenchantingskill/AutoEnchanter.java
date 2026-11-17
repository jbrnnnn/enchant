package com.autoenchantingskill;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.EnchantmentScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.GrindstoneScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Mesin otomatisasi (state machine, jalan tiap client tick).
 *
 * Siklus:
 *  meja enchant -> taruh item + lapis -> pilih slot terbaik -> enchant -> cek hasil
 *    - SESUAI target  : item disimpan di inventory, lanjut item berikutnya (meja tetap terbuka)
 *    - TIDAK sesuai   : tutup meja -> grindstone terdekat -> disenchant -> tutup -> buka meja lagi
 */
public final class AutoEnchanter {
    public enum State {
        IDLE, OPEN_TABLE, WAIT_TABLE, PLACE_ITEM, PLACE_LAPIS, WAIT_OFFERS, DO_ENCHANT, WAIT_ENCHANTED,
        RETURN_ITEM, CLOSE_TABLE, OPEN_GRIND, WAIT_GRIND, GRIND_PLACE, WAIT_GRIND_OUT, GRIND_TAKE, CLOSE_GRIND
    }
    private enum Kind { DONE, ERROR, LAPIS, MANUAL }

    private static State state = State.IDLE;
    private static int wait, timeout, attempts, successes, trackedInv = -1, chosenSlot;
    private static int tickCount, alarmLeft;
    private static boolean lastMatched;
    private static String lastMessage = "", lastResult = "";

    private AutoEnchanter() {}

    // ------------------------------------------------------------------ API
    public static boolean running() { return state != State.IDLE; }
    public static String lastMessage() { return lastMessage; }
    public static void reset() { state = State.IDLE; wait = 0; }
    public static void toggle() { if (running()) stop(); else start(); }
    public static void stop() { end("Dihentikan manual.", Kind.MANUAL); }

    public static String status() {
        if (!running()) return "§7Siap";
        return "§6AES §7| §f" + state + " §7| Percobaan §e" + attempts + " §7| Sukses §a" + successes
            + (lastResult.isEmpty() ? "" : " §7| " + lastResult);
    }

    public static void start() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        AesConfig c = AesConfig.get();
        if (c.selectedItems.isEmpty()) { lastMessage = "§cPilih minimal 1 item target dulu."; return; }
        for (String id : c.selectedItems) {
            boolean any = c.wanted(id).values().stream().anyMatch(v -> v > 0);
            if (!any) { lastMessage = "§cAtur enchant yang diinginkan untuk: " + id; return; }
        }
        if (!p.currentScreenHandler.getCursorStack().isEmpty()) {
            lastMessage = "§cKosongkan item di kursor dulu."; return;
        }
        attempts = 0; successes = 0; trackedInv = -1; lastResult = ""; lastMessage = "";
        if (mc.currentScreen != null && !(mc.currentScreen instanceof EnchantmentScreen)) mc.setScreen(null);
        go(State.OPEN_TABLE);
        chat(p, "§aOtomatisasi dimulai. §7(tekan tombol J untuk berhenti)");
    }

    // ------------------------------------------------------------------ tick
    public static void tick(MinecraftClient mc) {
        tickCount++;
        if (alarmLeft > 0 && tickCount % 5 == 0) {
            play(mc, SoundEvents.BLOCK_BELL_USE, (alarmLeft % 2 == 0) ? 1.0f : 1.6f);
            alarmLeft--;
        }
        if (state == State.IDLE) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) { state = State.IDLE; return; }
        if (!p.isAlive()) { end("Pemain mati, otomatisasi dihentikan.", Kind.ERROR); return; }
        if (tickCount % 10 == 0) p.sendMessage(Text.literal(status()), true);
        if (wait > 0) { wait--; return; }
        if (++timeout > 200) {
            end("Timeout di langkah " + state + " (lag server / terlalu jauh dari blok?).", Kind.ERROR);
            return;
        }
        try { step(mc, p); }
        catch (Exception e) { e.printStackTrace(); end("Error internal: " + e, Kind.ERROR); }
    }

    private static void go(State s) {
        state = s; timeout = 0; wait = AesConfig.get().actionDelay;
    }

    // ------------------------------------------------------------------ langkah
    private static void step(MinecraftClient mc, ClientPlayerEntity p) {
        AesConfig cfg = AesConfig.get();
        switch (state) {
            case OPEN_TABLE -> {
                if (limitsReached()) return;
                if (mc.currentScreen instanceof EnchantmentScreen && p.currentScreenHandler instanceof EnchantmentScreenHandler) {
                    go(State.PLACE_ITEM); return;
                }
                if (mc.currentScreen != null) { mc.setScreen(null); return; }
                BlockPos t = nearest(mc, p, Blocks.ENCHANTING_TABLE);
                if (t == null) { end("Meja enchant tidak ditemukan dalam jangkauan tangan.", Kind.ERROR); return; }
                interact(mc, p, t);
                go(State.WAIT_TABLE);
            }
            case WAIT_TABLE -> {
                if (p.currentScreenHandler instanceof EnchantmentScreenHandler) go(State.PLACE_ITEM);
            }
            case PLACE_ITEM -> {
                if (limitsReached()) return;
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler h)) { go(State.OPEN_TABLE); return; }
                if (h.getSlot(0).hasStack()) { end("Slot item di meja enchant tidak kosong.", Kind.ERROR); return; }
                int c = findCandidate(p, cfg);
                if (c < 0) { end("Tidak ada item target yang belum ter-enchant di inventory.", Kind.DONE); return; }
                int s = slotFor(h, p, c);
                if (s < 0) { end("Slot inventory tidak ditemukan.", Kind.ERROR); return; }
                click(mc, p, h, s);   // ambil ke kursor
                click(mc, p, h, 0);   // taruh di meja enchant
                trackedInv = c;
                go(State.PLACE_LAPIS);
            }
            case PLACE_LAPIS -> {
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler h)) { go(State.OPEN_TABLE); return; }
                int need = p.isCreative() ? 0 : lapisNeeded(cfg);
                if (need > 0 && h.getLapisCount() < need) {
                    int li = findLapis(p);
                    if (li >= 0) {
                        int s = slotFor(h, p, li);
                        if (s >= 0) mc.interactionManager.clickSlot(h.syncId, s, 0, SlotActionType.QUICK_MOVE, p);
                        go(State.PLACE_LAPIS);
                        return;
                    }
                    if (h.getLapisCount() == 0) { end("LAPIS HABIS! Isi lapis lazuli lalu mulai lagi.", Kind.LAPIS); return; }
                }
                go(State.WAIT_OFFERS);
            }
            case WAIT_OFFERS -> {
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler h)) { go(State.OPEN_TABLE); return; }
                boolean any = false;
                for (int i = 0; i < 3; i++) if (h.enchantmentPower[i] > 0) any = true;
                if (!any) {
                    if (timeout > 40) end("Meja tidak menawarkan enchant (bookshelf kurang / item tak bisa di-enchant).", Kind.ERROR);
                    return;
                }
                int slot = chooseSlot(mc, p, h, cfg);
                if (slot < 0) return; // chooseSlot sudah memanggil end()
                chosenSlot = slot;
                go(State.DO_ENCHANT);
            }
            case DO_ENCHANT -> {
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler h)) { go(State.OPEN_TABLE); return; }
                mc.interactionManager.clickButton(h.syncId, chosenSlot);
                attempts++;
                go(State.WAIT_ENCHANTED);
            }
            case WAIT_ENCHANTED -> {
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler h)) { go(State.OPEN_TABLE); return; }
                ItemStack st = h.getSlot(0).getStack();
                if (hasEnchants(st)) {
                    lastMatched = matches(st, ItemCatalog.idOf(st), cfg);
                    lastResult = (lastMatched ? "§a" : "§c") + describe(st);
                    go(State.RETURN_ITEM);
                }
            }
            case RETURN_ITEM -> {
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler h)) { go(State.OPEN_TABLE); return; }
                int back = slotFor(h, p, trackedInv);
                click(mc, p, h, 0);     // ambil dari meja
                click(mc, p, h, back);  // kembalikan ke slot inventory semula
                if (lastMatched) {
                    successes++;
                    notifySuccess(mc, p);
                    if (limitsReached()) return;
                    go(State.PLACE_ITEM);   // meja tetap terbuka, lanjut item berikutnya
                } else {
                    go(State.CLOSE_TABLE);
                }
            }
            case CLOSE_TABLE -> {
                p.closeHandledScreen();
                go(State.OPEN_GRIND);
            }
            case OPEN_GRIND -> {
                if (mc.currentScreen != null) return;
                BlockPos g = nearest(mc, p, Blocks.GRINDSTONE);
                if (g == null) { end("Grindstone tidak ditemukan dalam jangkauan tangan.", Kind.ERROR); return; }
                interact(mc, p, g);
                go(State.WAIT_GRIND);
            }
            case WAIT_GRIND -> {
                if (p.currentScreenHandler instanceof GrindstoneScreenHandler) go(State.GRIND_PLACE);
            }
            case GRIND_PLACE -> {
                if (!(p.currentScreenHandler instanceof GrindstoneScreenHandler h)) { go(State.OPEN_GRIND); return; }
                int s = slotFor(h, p, trackedInv);
                click(mc, p, h, s);   // ambil item
                click(mc, p, h, 0);   // taruh di input grindstone
                go(State.WAIT_GRIND_OUT);
            }
            case WAIT_GRIND_OUT -> {
                if (!(p.currentScreenHandler instanceof GrindstoneScreenHandler h)) { go(State.OPEN_GRIND); return; }
                if (h.getSlot(2).hasStack()) go(State.GRIND_TAKE);
            }
            case GRIND_TAKE -> {
                if (!(p.currentScreenHandler instanceof GrindstoneScreenHandler h)) { go(State.OPEN_GRIND); return; }
                click(mc, p, h, 2);                         // ambil hasil (tanpa enchant)
                click(mc, p, h, slotFor(h, p, trackedInv)); // kembalikan ke slot semula
                go(State.CLOSE_GRIND);
            }
            case CLOSE_GRIND -> {
                if (p.currentScreenHandler != null && !p.currentScreenHandler.getCursorStack().isEmpty()) return;
                p.closeHandledScreen();
                go(State.OPEN_TABLE);
                wait = Math.max(wait, cfg.xpCollectTicks); // beri waktu orb XP terserap
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ logika pintar
    private static int lapisNeeded(AesConfig cfg) {
        return switch (cfg.tier) {
            case SLOT1 -> 1;
            case SLOT2 -> 2;
            default -> 3;
        };
    }

    /**
     * Pilih slot enchant (0..2).
     * Tier 1/2/3: slot tetap. SMART: dari slot yang mampu dibayar (XP, lapis, level),
     * utamakan slot yang petunjuknya (clue) cocok dengan enchant yang diinginkan, lalu level tertinggi.
     */
    private static int chooseSlot(MinecraftClient mc, ClientPlayerEntity p, EnchantmentScreenHandler h, AesConfig cfg) {
        boolean creative = p.isCreative();
        int lapis = h.getLapisCount();
        boolean[] ok = new boolean[3];
        for (int i = 0; i < 3; i++) {
            int power = h.enchantmentPower[i];
            ok[i] = power > 0 && (creative || (p.experienceLevel >= i + 1 && p.experienceLevel >= power && lapis >= i + 1));
        }
        int forced = switch (cfg.tier) { case SLOT1 -> 0; case SLOT2 -> 1; case SLOT3 -> 2; default -> -1; };
        if (forced >= 0) {
            if (ok[forced]) return forced;
            diagnose(h, p, forced);
            return -1;
        }
        Map<String, Integer> want = cfg.wanted.getOrDefault(ItemCatalog.idOf(h.getSlot(0).getStack()), Map.of());
        Registry<Enchantment> reg = mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        int best = -1, bestScore = -1;
        for (int i = 0; i < 3; i++) {
            if (!ok[i]) continue;
            int score = h.enchantmentPower[i];
            String clue = reg.getEntry(h.enchantmentId[i]).flatMap(RegistryEntry::getKey)
                .map(k -> k.getValue().toString()).orElse("");
            if (want.getOrDefault(clue, 0) > 0) score += 1000;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        if (best >= 0) return best;
        diagnose(h, p, -1);
        return -1;
    }

    private static void diagnose(EnchantmentScreenHandler h, ClientPlayerEntity p, int forced) {
        int needLapis = forced >= 0 ? forced + 1 : 1;
        if (h.getLapisCount() < needLapis) { end("LAPIS HABIS / kurang untuk tier ini!", Kind.LAPIS); return; }
        if (forced >= 0 && h.enchantmentPower[forced] <= 0) {
            end("Slot tier " + (forced + 1) + " tidak tersedia (bookshelf kurang?).", Kind.ERROR); return;
        }
        end("Level XP tidak cukup untuk enchant. Naikkan level atau pilih tier lebih rendah.", Kind.ERROR);
    }

    public static boolean hasEnchants(ItemStack st) {
        return !st.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT).isEmpty();
    }

    public static Map<String, Integer> enchantsOf(ItemStack st) {
        Map<String, Integer> m = new HashMap<>();
        ItemEnchantmentsComponent c = st.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        for (RegistryEntry<Enchantment> e : c.getEnchantments())
            e.getKey().ifPresent(k -> m.put(k.getValue().toString(), c.getLevel(e)));
        return m;
    }

    private static boolean matches(ItemStack st, String itemId, AesConfig cfg) {
        Map<String, Integer> want = cfg.wanted.getOrDefault(itemId, Map.of());
        Map<String, Integer> have = enchantsOf(st);
        int req = 0, hit = 0;
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            if (e.getValue() <= 0) continue;
            req++;
            if (have.getOrDefault(e.getKey(), 0) >= e.getValue()) hit++;
        }
        if (req == 0) return true;
        return cfg.matchMode == AesConfig.MatchMode.ALL ? hit == req : hit > 0;
    }

    private static String describe(ItemStack st) {
        ItemEnchantmentsComponent c = st.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        List<String> parts = new ArrayList<>();
        for (RegistryEntry<Enchantment> e : c.getEnchantments())
            parts.add(Enchantment.getName(e, c.getLevel(e)).getString());
        return String.join(", ", parts);
    }

    private static int findCandidate(ClientPlayerEntity p, AesConfig cfg) {
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getStack(i);
            if (st.isEmpty() || !cfg.selectedItems.contains(ItemCatalog.idOf(st))) continue;
            if (hasEnchants(st) || !st.isEnchantable()) continue;
            return i;
        }
        return -1;
    }

    private static int findLapis(ClientPlayerEntity p) {
        for (int i = 0; i < 36; i++) if (p.getInventory().getStack(i).isOf(Items.LAPIS_LAZULI)) return i;
        return -1;
    }

    private static boolean limitsReached() {
        AesConfig c = AesConfig.get();
        if (c.goalSuccesses > 0 && successes >= c.goalSuccesses) {
            end("Target tercapai: " + successes + " item sesuai setelah " + attempts + " percobaan.", Kind.DONE);
            return true;
        }
        if (!c.infinite && attempts >= c.cycles) {
            end("Batas siklus tercapai (" + attempts + " percobaan, " + successes + " sukses).", Kind.DONE);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ util dunia / slot
    /** Cari index slot di screen handler yang menunjuk ke slot inventory pemain tertentu (0-35). */
    private static int slotFor(ScreenHandler h, ClientPlayerEntity p, int invIndex) {
        for (int i = 0; i < h.slots.size(); i++) {
            Slot s = h.slots.get(i);
            if (s.inventory == p.getInventory() && s.getIndex() == invIndex) return i;
        }
        return -1;
    }

    private static void click(MinecraftClient mc, ClientPlayerEntity p, ScreenHandler h, int slot) {
        if (slot < 0) return;
        mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, p);
    }

    private static BlockPos nearest(MinecraftClient mc, ClientPlayerEntity p, Block block) {
        BlockPos base = p.getBlockPos();
        Vec3d eye = p.getEyePos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -6; dx <= 6; dx++)
            for (int dy = -6; dy <= 6; dy++)
                for (int dz = -6; dz <= 6; dz++) {
                    BlockPos pos = base.add(dx, dy, dz);
                    if (!mc.world.getBlockState(pos).isOf(block)) continue;
                    if (!p.canInteractWithBlockAt(pos, -0.25)) continue;
                    double d = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
                    if (d < bestD) { bestD = d; best = pos; }
                }
        return best;
    }

    private static void interact(MinecraftClient mc, ClientPlayerEntity p, BlockPos pos) {
        Vec3d c = Vec3d.ofCenter(pos);
        Vec3d eye = p.getEyePos();
        Direction d = Direction.getFacing(eye.x - c.x, eye.y - c.y, eye.z - c.z);
        Vec3d hit = c.add(d.getOffsetX() * 0.5, d.getOffsetY() * 0.5, d.getOffsetZ() * 0.5);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, d, pos, false));
        p.swingHand(Hand.MAIN_HAND);
    }

    // ------------------------------------------------------------------ notifikasi
    private static void end(String reason, Kind kind) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean wasRunning = running();
        state = State.IDLE; wait = 0;
        lastMessage = reason;
        ClientPlayerEntity p = mc.player;
        if (p == null || !wasRunning) return;
        if (kind != Kind.MANUAL && (p.currentScreenHandler instanceof EnchantmentScreenHandler
            || p.currentScreenHandler instanceof GrindstoneScreenHandler)) {
            p.closeHandledScreen(); // item di slot input otomatis dikembalikan ke inventory
        }
        String color = switch (kind) { case DONE -> "§a"; case LAPIS -> "§c§l"; case ERROR -> "§c"; default -> "§e"; };
        chat(p, color + reason);
        AesConfig cfg = AesConfig.get();
        switch (kind) {
            case LAPIS -> {
                toast(mc, "LAPIS HABIS!", reason);
                if (cfg.sound) { alarmLeft = 10; }
            }
            case DONE -> {
                toast(mc, "AutoEnchantingSkill selesai", reason);
                if (cfg.sound) { play(mc, SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f); alarmLeft = 2; }
            }
            case ERROR -> {
                toast(mc, "AutoEnchantingSkill berhenti", reason);
                if (cfg.sound) { play(mc, SoundEvents.BLOCK_ANVIL_LAND, 0.8f); }
            }
            default -> { }
        }
    }

    private static void notifySuccess(MinecraftClient mc, ClientPlayerEntity p) {
        chat(p, "§a[SESUAI #" + successes + "] §f" + lastResult.replace("§a", ""));
        if (AesConfig.get().sound) play(mc, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f);
    }

    private static void chat(ClientPlayerEntity p, String msg) {
        p.sendMessage(Text.literal("§6[AES] §f" + msg), false);
    }

    private static void toast(MinecraftClient mc, String title, String desc) {
        SystemToast.add(mc.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION, Text.literal(title), Text.literal(desc));
    }

    private static void play(MinecraftClient mc, SoundEvent ev, float pitch) {
        if (!AesConfig.get().sound) return;
        mc.getSoundManager().play(PositionedSoundInstance.master(ev, pitch, 1.0f));
    }
}
