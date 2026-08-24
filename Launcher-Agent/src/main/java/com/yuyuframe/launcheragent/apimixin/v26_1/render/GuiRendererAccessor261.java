package com.yuyuframe.launcheragent.apimixin.v26_1.render;

import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor Sponge pour {@code GuiRenderer.renderState} (privé, Yarn "state")
 * — voir {@code GameRendererAccessor261}, même principe.
 */
@Mixin(targets = "net.minecraft.client.gui.render.GuiRenderer")
public interface GuiRendererAccessor261 {
    @Accessor("renderState")
    GuiRenderState la$renderState();
}
