package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_1_0.vanillagui.pipeline;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.gpu.ShaderPipelineFactory;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass.Blaze3DCore;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass.Blaze3DRect;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.vanillagui.GuiElementShaders;
import com.yuyuframe.launcheragent.apimixin.v26_1_0.render.RenderPipelinesAccessor2610;
import com.yuyuframe.launcheragent.apimixin.v26_1_0.render.VertexFormatElementAccessor2610;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import net.minecraft.client.gui.render.TextureSetup;

import java.awt.image.BufferedImage;

/**
 * Pipeline des ICÔNES RGBA destinées à l'état de GUI de vanilla — la brique
 * qui manquait au nouveau chemin de rendu (2026-08-31).
 *
 * <h2>Ce que ça débloque</h2>
 *
 * Jusqu'ici seuls le rect, le texte, le verre et la vignette savaient
 * s'émettre dans l'état de GUI ; une icône ne pouvait partir que par la file
 * Blaze3D, vidée APRÈS la présentation — donc au-dessus du chat, exactement
 * le défaut de z-order corrigé partout ailleurs. Toute icône du HUD peut
 * désormais se placer correctement en profondeur.
 *
 * <h2>Format et texture</h2>
 *
 * <pre>
 * Position  vec3
 * Color     vec4   teinte + opacité, par sommet
 * UV0       vec2   coordonnées dans l'ATLAS partagé
 * </pre>
 *
 * <p>Même format que {@link Blaze3DGuiText} — le fragment diffère seulement
 * par ce qu'il fait de l'échantillon : couleur réelle de l'image ici, champ
 * de distance signée là-bas.
 *
 * <p>La texture est l'atlas 2048² déjà partagé par {@link Blaze3DRect} : une
 * icône packée pour la file Blaze3D est immédiatement utilisable ici, sans
 * seconde copie GPU. Toutes les icônes partagent donc le MÊME
 * {@code TextureSetup}, ce qui les laisse se regrouper en un seul maillage
 * quand elles se suivent (voir {@code GuiRenderer.addElementToMesh}, qui
 * referme le maillage dès que pipeline ou texture change).
 */
public final class Blaze3DGuiIcon {
    private Blaze3DGuiIcon() {}

    // GLSL PARTAGÉ avec la 1.21.11 (voir GuiElementShaders) : le rendu diffère, le shader non.
    private static final String VERTEX_SRC = GuiElementShaders.ICON_VERTEX;

    private static final String FRAGMENT_SRC = GuiElementShaders.ICON_FRAGMENT;

    private static Object pipeline, shaderSource;
    private static boolean buildAttempted, buildFailed;

    /** Le pipeline, construit à la première demande — {@code null} si indisponible. */
    public static Object pipeline() {
        if (!buildAttempted) {
            buildAttempted = true;
            try {
                if (!Blaze3DCore.isAvailable()) { buildFailed = true; return null; }

                VertexFormat format = VertexFormat.builder()
                    .add("Position", VertexFormatElementAccessor2610.la$position())
                    .add("Color", VertexFormatElementAccessor2610.la$color())
                    .add("UV0", VertexFormatElementAccessor2610.la$uv0())
                    .build();

                Object vsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_gui_icon.vsh");
                Object fsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_gui_icon.fsh");
                pipeline = ShaderPipelineFactory.buildPipeline("ui_gui_icon", vsh, fsh,
                    new String[]{ "Sampler0" }, new String[]{ "DynamicTransforms", "Projection" },
                    RenderPipelinesAccessor2610.la$gui(), format);
                shaderSource = ShaderPipelineFactory.shaderSource(vsh, VERTEX_SRC, fsh, FRAGMENT_SRC);
            } catch (Throwable t) {
                buildFailed = true;
                LauncherLog.err("[Blaze3DGuiIcon] construction du pipeline: " + t);
            }
        }
        return buildFailed ? null : pipeline;
    }

    /** Compile le pipeline — voir {@code Blaze3DGuiRoundedRect.ensureCompiled} pour le pourquoi. */
    public static boolean ensureCompiled() {
        Object p = pipeline();
        if (p == null) return false;
        try {
            ShaderPipelineFactory.precompile(ShaderPipelineFactory.device(), p, shaderSource);
            return true;
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DGuiIcon] précompilation: " + t);
            return false;
        }
    }

    /** {@code TextureSetup} de l'atlas partagé — mis en cache, l'atlas ne change pas de la session. */
    private static TextureSetup atlasSetup;

    /**
     * Packe {@code img} dans l'atlas si besoin et renvoie de quoi la dessiner.
     *
     * @return {@code [float[]{u0,v0,u1,v1}, TextureSetup]}, ou {@code null}
     *         si l'atlas est plein / Blaze3D indisponible (déjà journalisé).
     */
    public static Object[] atlasEntry(String cacheKey, BufferedImage img) {
        Object[] entry = Blaze3DRect.guiAtlasEntry(cacheKey, img);
        if (entry == null) return null;
        try {
            if (atlasSetup == null) {
                atlasSetup = TextureSetup.singleTexture((GpuTextureView) entry[1], (GpuSampler) entry[2]);
            }
            if (atlasSetup == null) return null;
            return new Object[]{ entry[0], atlasSetup };
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DGuiIcon] atlasEntry: " + t);
            return null;
        }
    }
}
