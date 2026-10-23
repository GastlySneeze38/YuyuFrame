package com.yuyuframe.launcheragent.apimixin.v1_8_9.model;

import net.minecraft.client.util.TexturedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Les six faces d'un pavé 1.8.9 ({@code bcr.i}, privé, javap). Cible en nom Yarn, traduite par le refmap. */
@Mixin(targets = "net.minecraft.client.render.ModelBox")
public interface ModelBoxAccessor189 {

    @Accessor("quads")
    TexturedQuad[] la$quads();
}
