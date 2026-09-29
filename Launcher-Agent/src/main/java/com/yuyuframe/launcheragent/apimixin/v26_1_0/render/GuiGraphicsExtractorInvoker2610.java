package com.yuyuframe.launcheragent.apimixin.v26_1_0.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker Sponge pour le constructeur privé de {@code GuiGraphicsExtractor}
 * (Yarn {@code DrawContext}) + les 4 méthodes privées utilisées par
 * l'ancien pont réflexif (voir {@code UiVanillaItemRenderer#flushIntoGuiState}
 * pour la version {@code getDeclaredConstructor}/{@code getDeclaredMethods}
 * + {@code setAccessible} équivalente — chaque nom ici est repris
 * IDENTIQUE, déjà confirmé par désassemblage bytecode du vrai jar 26.1.2
 * dans les commentaires de ce fichier).
 *
 * Constructeur : {@code @Invoker("<init>")} exige une méthode STATIC dans
 * l'interface — idiome standard Sponge Mixin, pas une erreur de frappe.
 */
@Mixin(targets = "net.minecraft.client.gui.GuiGraphicsExtractor")
public interface GuiGraphicsExtractorInvoker2610 {

    @Invoker("<init>")
    static GuiGraphicsExtractor la$new(Minecraft mc, GuiRenderState state, int x, int y) {
        throw new AssertionError();
    }

    @Invoker("item")
    void la$item(ItemStack stack, int x, int y);

    @Invoker("itemBar")
    void la$itemBar(ItemStack stack, int x, int y);

    @Invoker("blit")
    void la$blit(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int w, int h, int texW, int texH);

    @Invoker("blitSprite")
    void la$blitSprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h);
}
