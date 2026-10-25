package dev.axiom.lyrics;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class LyricsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LyricsState.loadSettings();
        SpotifyAuth.load();
        SpotifyPoller.start();
        WindowsMedia.start();

        // Taste K öffnet das Menü (im Steuerungs-Menü änderbar)
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("spotifylyrics", "main"));
        KeyBinding open = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.spotifylyrics.open", GLFW.GLFW_KEY_K, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (open.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new LyricsScreen());
            }
        });

        // Lyrics als HUD-Ebene (unter dem Chat)
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.of("spotifylyrics", "lyrics"),
            LyricsRenderer::render);
    }
}
