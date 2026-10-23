package com.yuyuframe.launcheragent.apimixin.v1_8_9.model;

import net.minecraft.client.util.math.TexturePosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Les quatre sommets d'une face 1.8.9 ({@code bbs.a}, public, javap). Cible en nom Yarn, traduite par le refmap. */
@Mixin(targets = "net.minecraft.client.util.TexturedQuad")
public interface TexturedQuadAccessor189 {

    @Accessor("positions")
    TexturePosition[] la$positions();
}
