package com.yuyuframe.launcheragent.apimixin.v26_3.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
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
 * {@code MinecraftAccessor263#la$fps()}) ; les corps ne sont jamais exécutés.
 */
@Mixin(targets = "com.mojang.blaze3d.vertex.DefaultVertexFormat")
public interface DefaultVertexFormatAccessor263 {

    @Accessor("POSITION_FORMAT")
    static GpuFormat la$positionFormat() { throw new AssertionError("DefaultVertexFormatAccessor263 non tissé"); }

    @Accessor("COLOR_FORMAT")
    static GpuFormat la$colorFormat() { throw new AssertionError("DefaultVertexFormatAccessor263 non tissé"); }

    @Accessor("UV0_FORMAT")
    static GpuFormat la$uv0Format() { throw new AssertionError("DefaultVertexFormatAccessor263 non tissé"); }

    @Accessor("UV1_FORMAT")
    static GpuFormat la$uv1Format() { throw new AssertionError("DefaultVertexFormatAccessor263 non tissé"); }

    @Accessor("UV2_FORMAT")
    static GpuFormat la$uv2Format() { throw new AssertionError("DefaultVertexFormatAccessor263 non tissé"); }

    /**
     * Position + Color + UV0 + UV2 (28 octets) — la disposition qu'écrit à la
     * main tout le chemin « en file » du moteur ({@code Blaze3DCore.putVertexPCTL}).
     * C'était le format de {@code RenderPipelines.GUI_TEXT} en 26.1.2 ; en
     * 26.2, GUI_TEXT est passé à {@code POSITION_TEX_COLOR} (24 octets, autre
     * ordre), d'où ce format désormais nommé explicitement. Vérifié par javap :
     * même constante, même composition en 26.2.
     */
    @Accessor("POSITION_COLOR_TEX_LIGHTMAP")
    static VertexFormat la$positionColorTexLightmap() { throw new AssertionError("DefaultVertexFormatAccessor263 non tissé"); }
}
