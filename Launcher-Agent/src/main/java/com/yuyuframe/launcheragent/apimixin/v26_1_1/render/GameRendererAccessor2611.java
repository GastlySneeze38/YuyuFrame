package com.yuyuframe.launcheragent.apimixin.v26_1_1.render;

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
public interface GameRendererAccessor2611 {
    @Accessor("guiRenderer")
    GuiRenderer la$guiRenderer();
}
