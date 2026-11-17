package com.autoenchantingskill;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.ingame.EnchantmentScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class AutoEnchantingSkillClient implements ClientModInitializer {
    public static KeyBinding openKey;
    public static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        AesConfig.get();
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("autoenchantingskill", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.autoenchantingskill.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, cat));
        toggleKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.autoenchantingskill.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, cat));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.wasPressed()) {
                if (mc.player != null && mc.currentScreen == null) mc.setScreen(new AesConfigScreen(null));
            }
            while (toggleKey.wasPressed()) {
                if (mc.player != null) AutoEnchanter.toggle();
            }
            AutoEnchanter.tick(mc);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> AutoEnchanter.reset());

        // Tombol pintas langsung di layar meja enchant
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!(screen instanceof EnchantmentScreen)) return;
            int x = (w - 176) / 2 - 96;
            int y = (h - 166) / 2;
            Screens.getButtons(screen).add(ButtonWidget.builder(
                Text.literal(AutoEnchanter.running() ? "§c[] Stop AES" : "§a> Mulai AES"),
                b -> { AutoEnchanter.toggle(); }
            ).dimensions(x, y, 90, 20).build());
            Screens.getButtons(screen).add(ButtonWidget.builder(
                Text.literal("AES Setting"),
                b -> {
                    if (mc.player != null) mc.player.closeHandledScreen();
                    mc.setScreen(new AesConfigScreen(null));
                }
            ).dimensions(x, y + 22, 90, 20).build());
        });
    }
}
