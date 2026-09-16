package net.minecraft.client;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.util.Session;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.resource.ResourceManager;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code ave}).
 *
 * <p>Champs PUBLICS vérifiés javap : {@code player}, {@code world},
 * {@code options}, {@code currentScreen}, {@code inGameHud}, {@code mouse}.
 * {@code session}/{@code resourceManager}/{@code soundManager} sont privés
 * mais ont un getter public ; {@code getCurrentFps()} est un getter STATIQUE
 * public — aucun accessor Mixin pour cette classe.
 */
public class MinecraftClient {

    public ClientPlayerEntity player;

    public ClientWorld world;

    public GameOptions options;

    public Screen currentScreen;

    public InGameHud inGameHud;

    public MouseInput mouse;

    public net.minecraft.client.font.TextRenderer textRenderer;

    /** Cible du réticule ({@code ave.s}), {@code null} hors partie. */
    public net.minecraft.util.hit.BlockHitResult result;
    /** Gestionnaire de particules ({@code ave.j}). */
    public net.minecraft.client.particle.ParticleManager particleManager;

    public net.minecraft.client.render.item.ItemRenderer getItemRenderer() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public net.minecraft.client.texture.TextureManager getTextureManager() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    private MinecraftClient() {
    }

    public static MinecraftClient getInstance() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static int getCurrentFps() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void setScreen(Screen screen) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public Session getSession() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public ResourceManager getResourceManager() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public SoundManager getSoundManager() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public ClientPlayNetworkHandler getNetworkHandler() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public ServerInfo getCurrentServerEntry() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
