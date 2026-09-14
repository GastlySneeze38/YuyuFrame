package com.yuyuframe.launcheragent.apigraphic.backend;

import com.yuyuframe.launcheragent.apimixin.version.VersionProfileRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

/**
 * Les modèles de rendu que le moteur sait servir — le SEUL vocabulaire de
 * version qu'{@code apigraphic} a le droit de connaître.
 *
 * <h2>Reçue, jamais déduite</h2>
 *
 * L'ère active vient de {@code VersionProfile.renderEra}, publié au bootstrap
 * Mixin. Le moteur ne lit plus {@code launcheragent.mcVersion} et ne sonde plus
 * la présence de {@code GpuDevice} : ces deux gestes reviennent à déduire une
 * ère d'une VERSION, ce qui est le métier d'{@code apimixin}.
 *
 * <p>Trois endroits le faisaient avant le 2026-09-09 :
 * <ul>
 *   <li>{@code UiRenderer} — {@code supportsFixedFunctionDrawing(mcVersion)} ;</li>
 *   <li>{@code Blaze3DCore.isAvailable()} — sondage de {@code GpuDevice} ;</li>
 *   <li>{@code ShaderPipelineFactory.isAvailable()} — le même sondage.</li>
 * </ul>
 * Tous trois passent désormais par {@link #active()}.
 *
 * <h2>Résolue UNE fois</h2>
 *
 * Le résultat est mis en cache : l'ère ne change pas en cours de partie, et
 * ces trois appelants sont sur le chemin de frame. Une ère inconnue vaut
 * {@link #NOOP} — le moteur tourne, ne prend rien en charge, et l'appelant
 * retombe sur son chemin historique.
 */
public enum RenderEra {

    /** Aucun backend : rien n'est pris en charge. Version reconnue mais non servie, ou ère non encore écrite. */
    NOOP,

    /** Pipeline fixe (≤ 1.16) — pile de matrices, {@code GL_ALPHA_TEST}, dessin immédiat. */
    GL2,

    /**
     * Shaders GLSL 150 et VAO/VBO (1.17 – 1.21.x en Core Profile 3.2, et
     * 1.8.9 depuis le 2026-09-14 en contexte 3.2 de compatibilité ouvert par
     * la couche LWJGL 3) — plus de pile de matrices.
     */
    GL3,

    /** Blaze3D (1.21.11 – 26.x) — {@code RenderPipeline}, {@code GuiRenderState} différé. */
    BLAZE3D;

    /** Clé portée par {@code VersionProfile.renderEra} — le vocabulaire de frontière. */
    public static RenderEra fromKey(String key) {
        if ("gl2".equals(key)) return GL2;
        if ("gl3".equals(key)) return GL3;
        if ("blaze3d".equals(key)) return BLAZE3D;
        return NOOP;
    }

    private static volatile RenderEra active;

    /** Ère du lancement en cours, résolue au premier appel puis mise en cache. */
    public static RenderEra active() {
        RenderEra local = active;
        if (local != null) return local;
        synchronized (RenderEra.class) {
            if (active != null) return active;
            RenderEra resolved = fromKey(VersionProfileRegistry.activeRenderEra());
            LauncherLog.agent(3, "[RenderEra] ère de rendu active : " + resolved);
            active = resolved;
            return resolved;
        }
    }
}
