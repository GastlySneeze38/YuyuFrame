package net.minecraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.sounds.SoundManager;

/**
 * Stub compile-only (26.1+) — cible de mixin ({@code ScreenSetMixin261}, etc.)
 * ET, depuis {@code GlobalUiRenderBridge261} (remplacement réflexion →
 * accès direct), point d'appel typé pour les méthodes PUBLIQUES vérifiées via
 * javap sur le jar client 26.1.2 réel : {@code getInstance()}, {@code
 * setScreen(Screen)}, {@code getMainRenderTarget()}, {@code getConnection()}
 * — descripteurs exacts requis pour qu'un appel direct (hors Mixin) résolve
 * la VRAIE classe au runtime (voir javadoc de {@code GlobalUiRenderBridge261}).
 *
 * {@code player} : champ PUBLIC (confirmé par l'usage universel de {@code
 * Minecraft.getInstance().player} dans l'écosystème Fabric/Forge, contrairement
 * à {@code screen}/{@code window}/{@code mouseHandler}/{@code options}, eux
 * privés — voir {@code MinecraftAccessor261}) — accès direct au champ, pas
 * besoin d'Accessor.
 */
public abstract class Minecraft {
    public static Minecraft getInstance() { return null; }
    public abstract void setScreen(Screen screen);
    public abstract RenderTarget getMainRenderTarget();
    public abstract ClientPacketListener getConnection();
    public abstract SoundManager getSoundManager();
    public abstract Window getWindow();

    public LocalPlayer player;
    public ClientLevel level;
}
