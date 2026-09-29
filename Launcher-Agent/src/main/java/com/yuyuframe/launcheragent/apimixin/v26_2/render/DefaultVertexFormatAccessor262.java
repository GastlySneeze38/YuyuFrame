package com.yuyuframe.launcheragent.apimixin.v26_2.render;

import com.mojang.blaze3d.GpuFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Formats d'attributs de sommet vanilla de la 26.2 — remplace
 * {@code VertexFormatElementAccessor261} : en 26.2, {@code VertexFormatElement}
 * n'a plus de constantes ({@code POSITION}, {@code COLOR}, {@code UV0}…), un
 * format se compose par {@code VertexFormat.builder(0).addAttribute(nom, GpuFormat)}.
 *
 * <p>Les formats sont lus sur les constantes de {@code DefaultVertexFormat},
 * celles que le jeu utilise pour ses propres formats (relevées par javap dans
 * son {@code <clinit>}) : {@code POSITION_FORMAT} = RGB32_FLOAT,
 * {@code COLOR_FORMAT} = RGBA8_UNORM, {@code UV0_FORMAT} = RG32_FLOAT,
 * {@code UV1_FORMAT}/{@code UV2_FORMAT} = RG16_SINT — soit exactement la
 * disposition des anciennes constantes, que nos shaders GLSL attendent.
 *
 * <p>{@code static} REQUIS (les champs le sont, voir
 * {@code MinecraftAccessor262#la$fps()}) ; les corps ne sont jamais exécutés.
 */
@Mixin(targets = "com.mojang.blaze3d.vertex.DefaultVertexFormat")
public interface DefaultVertexFormatAccessor262 {

    @Accessor("POSITION_FORMAT")
    static GpuFormat la$positionFormat() { throw new AssertionError("DefaultVertexFormatAccessor262 non tissé"); }

    @Accessor("COLOR_FORMAT")
    static GpuFormat la$colorFormat() { throw new AssertionError("DefaultVertexFormatAccessor262 non tissé"); }

    @Accessor("UV0_FORMAT")
    static GpuFormat la$uv0Format() { throw new AssertionError("DefaultVertexFormatAccessor262 non tissé"); }

    @Accessor("UV1_FORMAT")
    static GpuFormat la$uv1Format() { throw new AssertionError("DefaultVertexFormatAccessor262 non tissé"); }

    @Accessor("UV2_FORMAT")
    static GpuFormat la$uv2Format() { throw new AssertionError("DefaultVertexFormatAccessor262 non tissé"); }
}
