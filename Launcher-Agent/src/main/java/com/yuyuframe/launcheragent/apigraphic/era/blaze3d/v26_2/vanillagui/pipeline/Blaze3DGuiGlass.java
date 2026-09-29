package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_2.vanillagui.pipeline;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.gpu.ShaderPipelineFactory;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass.Blaze3DBlur;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass.Blaze3DCore;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.vanillagui.GuiElementShaders;
import com.yuyuframe.launcheragent.apimixin.v26_2.render.RenderPipelinesAccessor262;
import com.yuyuframe.launcheragent.apimixin.v26_2.render.VertexFormatElementAccessor262;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import net.minecraft.client.gui.render.TextureSetup;

/**
 * Panneau de VERRE DÉPOLI destiné à l'état de GUI de vanilla — troisième et
 * dernière primitive du HUD à être portée (2026-08-31).
 *
 * <h2>Pourquoi pas le flou de vanilla</h2>
 *
 * Vanilla expose {@code blurBeforeThisStratum()}, mais c'est un post-effet
 * PLEIN ÉCRAN et DESTRUCTIF : {@code GuiRenderer.draw} appelle
 * {@code GameRenderer.processBlurEffect()}, qui exécute
 * {@code post_effect/blur.json} — <b>six</b> passes de flou-boîte séparable —
 * en réécrivant le framebuffer principal. Tout ce qui est dessiné avant la
 * strate est flouté PARTOUT. C'est le flou du menu pause, pas du verre
 * localisé : l'utiliser pour le HUD flouterait toute la vue de jeu.
 *
 * <p>Notre chaîne dual-Kawase fait moins de passes (trois pour le HUD),
 * n'écrase pas le framebuffer, et produit une texture qu'on masque ensuite à
 * la forme du panneau. Elle reste donc le bon outil ici.
 *
 * <h2>Le créneau manquant, et comment on s'en passe</h2>
 *
 * Le format porte déjà position locale (UV0), demi-taille (UV1) et rayons
 * (UV2) : plus rien de libre pour la taille de l'écran, nécessaire pour
 * convertir un fragment en coordonnée de texture. {@code textureSize(Sampler0, 0)}
 * la donne directement en GLSL — aucun attribut ni uniforme supplémentaire.
 *
 * <p>La teinte voyage dans l'attribut {@code Color} : {@code rgb} = couleur de
 * teinte, {@code a} = opacité finale du panneau. La FORCE de teinte n'ayant
 * plus de créneau, elle est figée à la valeur du HUD — voir
 * {@link #TINT_STRENGTH}.
 */
public final class Blaze3DGuiGlass {
    private Blaze3DGuiGlass() {}

    /**
     * Force de teinte, figée faute de créneau libre par sommet. Reprend
     * {@code UiTheme.GLASS_STRENGTH_FIELD}, la seule valeur que le HUD
     * utilisait ({@code HudPanelRenderer} n'en passe pas d'autre).
     */
    public static final float TINT_STRENGTH = GuiElementShaders.GLASS_TINT_STRENGTH;

    // GLSL PARTAGÉ avec la 1.21.11 (voir GuiElementShaders) : le rendu diffère, le shader non.
    private static final String VERTEX_SRC = GuiElementShaders.GLASS_VERTEX;

    private static final String FRAGMENT_SRC = GuiElementShaders.GLASS_FRAGMENT;

    private static Object pipeline, shaderSource;
    private static boolean buildAttempted, buildFailed;

    public static Object pipeline() {
        if (!buildAttempted) {
            buildAttempted = true;
            try {
                if (!Blaze3DCore.isAvailable()) { buildFailed = true; return null; }
                VertexFormat format = VertexFormat.builder()
                    .add("Position", VertexFormatElementAccessor262.la$position())
                    .add("Color", VertexFormatElementAccessor262.la$color())
                    .add("UV0", VertexFormatElementAccessor262.la$uv0())
                    .add("UV1", VertexFormatElementAccessor262.la$uv1())
                    .add("UV2", VertexFormatElementAccessor262.la$uv2())
                    .build();
                Object vsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_gui_glass.vsh");
                Object fsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_gui_glass.fsh");
                pipeline = ShaderPipelineFactory.buildPipeline("ui_gui_glass", vsh, fsh,
                    new String[]{ "Sampler0" }, new String[]{ "DynamicTransforms", "Projection" },
                    RenderPipelinesAccessor262.la$gui(), format);
                shaderSource = ShaderPipelineFactory.shaderSource(vsh, VERTEX_SRC, fsh, FRAGMENT_SRC);
            } catch (Throwable t) {
                buildFailed = true;
                LauncherLog.err("[Blaze3DGuiGlass] construction du pipeline: " + t);
            }
        }
        return buildFailed ? null : pipeline;
    }

    public static boolean ensureCompiled() {
        Object p = pipeline();
        if (p == null) return false;
        try {
            ShaderPipelineFactory.precompile(ShaderPipelineFactory.device(), p, shaderSource);
            return true;
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DGuiGlass] précompilation: " + t);
            return false;
        }
    }

    /**
     * {@code TextureSetup} sur le résultat de la chaîne de flou, ou
     * {@code null} si aucune chaîne n'a encore été calculée pour cette frame.
     *
     * <p>PAS mis en cache, contrairement à l'atlas de police : la chaîne est
     * recréée à chaque changement de résolution, et sa vue avec.
     */
    public static TextureSetup textureSetup() {
        try {
            Object view = Blaze3DBlur.blurredView();
            Object sampler = Blaze3DBlur.blurSampler();
            if (view == null || sampler == null) return null;
            return TextureSetup.singleTexture((GpuTextureView) view, (GpuSampler) sampler);
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DGuiGlass] textureSetup: " + t);
            return null;
        }
    }
}
