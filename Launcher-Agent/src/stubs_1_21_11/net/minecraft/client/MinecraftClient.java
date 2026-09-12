package net.minecraft.client;

import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.session.Session;
import net.minecraft.client.util.Window;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.resource.ResourceManager;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code gfj}) — voir
 * {@code com.mojang.blaze3d.systems.RenderSystem} pour les règles de l'unité.
 *
 * <p>Visibilités VÉRIFIÉES par {@code javap} sur le jar client réel : {@code
 * player}, {@code world}, {@code options}, {@code currentScreen}, {@code
 * inGameHud} et {@code mouse} y sont PUBLICS — contrairement à la 26.1.2, qui
 * a besoin d'un accessor Mixin pour les mêmes données. {@code window},
 * {@code session} et {@code resourceManager} sont privés, mais leurs getters
 * sont publics.
 */
public class MinecraftClient {

    public ClientPlayerEntity player;

    public ClientWorld world;

    public final GameOptions options = null;

    public Screen currentScreen;

    public final InGameHud inGameHud = null;

    public final Mouse mouse = null;

    private MinecraftClient() {
    }

    public static MinecraftClient getInstance() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public Framebuffer getFramebuffer() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public Window getWindow() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public Session getSession() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Pendant de {@code getConnection()} en 26.1.2 — {@code null} hors partie. */
    public net.minecraft.client.network.ClientPlayNetworkHandler getNetworkHandler() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public ResourceManager getResourceManager() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Même nom qu'en 26.1.2 — seul le type rendu change ({@code net.minecraft.client.sound} au lieu de {@code net.minecraft.client.sounds}). */
    public net.minecraft.client.sound.SoundManager getSoundManager() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
