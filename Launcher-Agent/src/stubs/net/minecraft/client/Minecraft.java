package net.minecraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.screens.Screen;

/**
 * Stub compile-only (26.1+) — cible de mixin ({@code ScreenSetMixin261}, etc.)
 * ET, depuis {@code GlobalUiRenderBridge261} (remplacement réflexion →
 * accès direct), point d'appel typé pour 3 méthodes PUBLIQUES vérifiées via
 * javap sur le jar client 26.1.2 réel : {@code getInstance()}, {@code
 * setScreen(Screen)}, {@code getMainRenderTarget()} — descripteurs exacts
 * requis pour qu'un appel direct (hors Mixin) résolve la VRAIE classe au
 * runtime (voir javadoc de {@code GlobalUiRenderBridge261}).
 */
public abstract class Minecraft {
    public static Minecraft getInstance() { return null; }
    public abstract void setScreen(Screen screen);
    public abstract RenderTarget getMainRenderTarget();
}
