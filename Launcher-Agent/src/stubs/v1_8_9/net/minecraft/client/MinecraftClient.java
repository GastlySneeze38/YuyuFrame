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

    /** {@code ave.v()} — faux quand l'interface est masquée (F1). */
    public static boolean isHudEnabled() {
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

    // ── Renderer (adaptateur Celeritas, voir celeritas/v1_8_9/FEATHER-YARN.md) ──

    /** {@code ave.o} — {@code gameRenderer} chez Feather. */
    public net.minecraft.client.render.GameRenderer gameRenderer;

    /** {@code ave.g} — même nom chez Feather. */
    public net.minecraft.client.render.WorldRenderer worldRenderer;

    /** {@code ave.a} — {@code setWorld} chez Feather : change de monde. */
    public void connect(ClientWorld world) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code ave.a} — {@code setWorld} chez Feather, avec le message de l'écran de chargement. */
    public void connect(ClientWorld world, String loadingMessage) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code ave.ac} — {@code getCamera} chez Feather. */
    public net.minecraft.entity.Entity getCameraEntity() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code ave.x()} — réglage « éclairage adouci » activé. */
    public static boolean isAmbientOcclusionEnabled() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code ave.T()} — l'atlas des textures de blocs. */
    public net.minecraft.client.texture.SpriteAtlasTexture getSpriteAtlasTexture() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code ave.ae} — {@code getBlockRenderDispatcher} chez Feather. */
    public net.minecraft.client.render.block.BlockRenderManager getBlockRenderManager() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code ave.af} — {@code getEntityRenderDispatcher} chez Feather. */
    public net.minecraft.client.render.entity.EntityRenderDispatcher getEntityRenderManager() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
