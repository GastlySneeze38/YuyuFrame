package com.yuyuframe.launcheragent.apimixin.v26_1_0.render;

import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor Sponge pour {@code GuiRenderer.renderState} (privé, Yarn "state")
 * — voir {@code GameRendererAccessor2610}, même principe. Package de {@code
 * GuiRenderState} vérifié par javap sur le jar client 26.1.2 réel : {@code
 * net.minecraft.renderer.state.gui} (PAS {@code net.minecraft.client.gui.
 * render.state} — corrigé après un crash au lancement causé par ce mauvais
 * package, voir le stub {@code src/stubs/v26_1/net/minecraft/client/renderer/
 * state/gui/GuiRenderState.java}).
 */
@Mixin(targets = "net.minecraft.client.gui.render.GuiRenderer")
public interface GuiRendererAccessor2610 {
    @Accessor("renderState")
    GuiRenderState la$renderState();
}
