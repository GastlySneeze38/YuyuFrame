package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_2.vanillagui.pipeline;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.gpu.ShaderPipelineFactory;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass.Blaze3DCore;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.vanillagui.GuiElementShaders;
import com.yuyuframe.launcheragent.apimixin.v26_2.render.RenderPipelinesAccessor262;
import com.yuyuframe.launcheragent.apimixin.v26_2.render.DefaultVertexFormatAccessor262;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

/**
 * Format de sommet et pipeline du DÉGRADÉ DE BORD (vignette plein écran)
 * destiné à l'état de GUI de vanilla — portage de {@code LowHealthTintModule}
 * sur le nouveau chemin de rendu (2026-08-31), voir
 * {@code docs/LauncherAgent/rendering-pipeline.md}.
 *
 * <h2>Pourquoi ce pipeline existe</h2>
 *
 * L'ancienne vignette dessinait en <b>OpenGL brut</b> ({@code glUseProgram},
 * {@code glDisable}, {@code glBlendFunc}, VAO maison — voir
 * {@code UiPrimitiveRenderer.drawEdgeVignetteModern}), APRÈS la présentation
 * de la frame. Sur 26.1.2, Blaze3D suit lui-même l'état GPU : y toucher
 * derrière son dos corrompt ce qu'il croit avoir posé, d'où les artefacts
 * signalés dès l'activation du module. Émettre un
 * {@code GuiElementRenderState} comme n'importe quel autre élément de GUI
 * supprime le problème à la racine — plus une seule commande GL de notre
 * part.
 *
 * <h2>Format de sommet</h2>
 *
 * Même principe que {@link Blaze3DGuiRoundedRect} (lire sa javadoc pour le
 * pourquoi général : {@code GuiRenderer} ne lie aucun bloc d'uniformes à nous,
 * donc TOUTE donnée variable passe par les sommets) :
 *
 * <pre>
 * Position  vec3    coin du quad, en pixels GUI
 * Color     vec4    couleur AU BORD, alpha compris
 * UV0       vec2    position locale, relative au centre de l'écran
 * UV1       ivec2   (demi-largeur, demi-hauteur) de l'écran, pixels GUI
 * UV2       ivec2   x = largeur du dégradé en pixels GUI ; y inutilisé
 * </pre>
 *
 * <p>Le dégradé se calcule à partir de la distance au bord le plus PROCHE,
 * qui est un {@code min()} de quatre fonctions linéaires : non planaire, donc
 * impossible à interpoler entre quatre sommets. C'est bien un calcul par
 * fragment, pas un dégradé de sommets.
 */
public final class Blaze3DGuiVignette {
    private Blaze3DGuiVignette() {}

    // GLSL PARTAGÉ avec la 1.21.11 (voir GuiElementShaders) : le rendu diffère, le shader non.
    private static final String VERTEX_SRC = GuiElementShaders.VIGNETTE_VERTEX;

    /**
     * Reprend TRAIT POUR TRAIT la courbe de l'ancien shader GL brut — même
     * smootherstep, même dithering : le portage change le chemin de rendu, pas
     * l'apparence. Les deux commentaires d'origine valent toujours et sont
     * conservés ici, ce sont des conclusions durement acquises.
     */
    private static final String FRAGMENT_SRC = GuiElementShaders.VIGNETTE_FRAGMENT;

    private static Object pipeline, shaderSource;
    private static boolean buildAttempted, buildFailed;

    /** Le pipeline, construit à la première demande — {@code null} si indisponible. */
    public static Object pipeline() {
        if (!buildAttempted) {
            buildAttempted = true;
            try {
                if (!Blaze3DCore.isAvailable()) { buildFailed = true; return null; }

                // 26.2 : builder(0) + addAttribute(nom, GpuFormat) — formats lus
                // sur DefaultVertexFormat, même disposition qu'en 26.1.2.
                VertexFormat format = VertexFormat.builder(0)
                    .addAttribute("Position", DefaultVertexFormatAccessor262.la$positionFormat())
                    .addAttribute("Color", DefaultVertexFormatAccessor262.la$colorFormat())
                    .addAttribute("UV0", DefaultVertexFormatAccessor262.la$uv0Format())
                    .addAttribute("UV1", DefaultVertexFormatAccessor262.la$uv1Format())
                    .addAttribute("UV2", DefaultVertexFormatAccessor262.la$uv2Format())
                    .build();

                Object vsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_gui_vignette.vsh");
                Object fsh = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_gui_vignette.fsh");
                pipeline = ShaderPipelineFactory.buildPipeline("ui_gui_vignette", vsh, fsh,
                    new String[0], new String[]{ "DynamicTransforms", "Projection" },
                    RenderPipelinesAccessor262.la$gui(), format);
                shaderSource = ShaderPipelineFactory.shaderSource(vsh, VERTEX_SRC, fsh, FRAGMENT_SRC);
            } catch (Throwable t) {
                buildFailed = true;
                LauncherLog.err("[Blaze3DGuiVignette] construction du pipeline: " + t);
            }
        }
        return buildFailed ? null : pipeline;
    }

    /**
     * Compile le pipeline si besoin. À appeler AVANT de soumettre l'élément :
     * c'est vanilla qui dessinera plus tard, et il ne précompile pas nos
     * pipelines.
     *
     * <p>Volontairement PAS appelé depuis {@code VanillaGuiTarget.begin()},
     * contrairement aux pipelines du rect et du texte : la vignette ne sert
     * qu'à un module, et à vie basse seulement. La faire compiler à chaque
     * passe de HUD ferait payer sa construction à tout le monde, et un échec
     * de sa part ferait tomber le HUD entier.
     */
    public static boolean ensureCompiled() {
        Object p = pipeline();
        if (p == null) return false;
        try {
            ShaderPipelineFactory.precompile(ShaderPipelineFactory.device(), p, shaderSource);
            return true;
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DGuiVignette] précompilation: " + t);
            return false;
        }
    }
}
