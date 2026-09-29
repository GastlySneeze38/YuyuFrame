package com.yuyuframe.launcheragent.apimixin.v26_3.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor Sponge pour {@code GameRenderer.guiRenderer} (privé) — voir
 * {@code UiVanillaItemRenderer#flushPendingModernItemIcons} pour l'ancien
 * pont réflexif équivalent (résolution par {@code getDeclaredField} sur la
 * classe RUNTIME de l'instance, à chaque appel — ici, une seule interface
 * suffit, résolue une fois pour toutes par Mixin au tissage).
 */
@Mixin(targets = "net.minecraft.client.renderer.GameRenderer")
public interface GameRendererAccessor263 {
    @Accessor("guiRenderer")
    GuiRenderer la$guiRenderer();

    /**
     * 26.2 : cible de rendu principale du jeu, là où tout le moteur dessine
     * ({@code Blaze3DGpu263#mainColorView}). Remplace
     * {@code Minecraft.getMainRenderTarget()}, supprimé en 26.2 — champ
     * {@code private final RenderTarget mainRenderTarget} (vérifié par javap).
     */
    @Accessor("mainRenderTarget")
    RenderTarget la$mainRenderTarget();
}
