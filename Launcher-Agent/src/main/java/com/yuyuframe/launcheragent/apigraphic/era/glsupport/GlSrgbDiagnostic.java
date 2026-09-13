package com.yuyuframe.launcheragent.apigraphic.era.glsupport;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

/**
 * Diagnostic gamma / espace colorimétrique (roadmap Phase 5.4), une ligne par
 * session — ères GL seulement.
 *
 * <p>Investigation faite : AUCUN de nos pipelines ne touche à un format sRGB
 * ni à une conversion gamma (grep sur {@code GL_SRGB*}/{@code
 * GL_FRAMEBUFFER_SRGB} dans tout {@code apigraphic/} : zéro résultat) ; Blaze3D
 * crée explicitement du {@code RGBA8} non-sRGB. Côté NOTRE code, les ères
 * traitent donc les couleurs de façon strictement identique (mélange linéaire
 * naïf sur des valeurs gamma-encodées, comme la quasi-totalité des moteurs
 * d'UI 2D).
 *
 * <p>Ce que notre code ne peut PAS voir : si Minecraft active lui-même
 * {@code GL_FRAMEBUFFER_SRGB} différemment selon le bracket — exactement le
 * genre de divergence « dégradés délavés selon la version » que visait cet
 * item. D'où cette ligne : l'état réel est journalisé au lieu d'être deviné.
 *
 * <p>Vivait dans {@code render/UiPrimitiveRenderer}, où il n'était plus atteint
 * depuis que les backends GL prennent le rect arrondi en charge ; appelé
 * désormais au branchement du backend.
 */
public final class GlSrgbDiagnostic {

    private GlSrgbDiagnostic() {}

    private static volatile boolean logged;

    public static void logOnce(GlBridge gl, String era) {
        if (logged) return;
        logged = true;
        try {
            boolean srgbEnabled = gl.glIsEnabled(0x8DB9); // GL_FRAMEBUFFER_SRGB
            LauncherLog.info("[UiRenderer] Diagnostic gamma (Phase 5.4) : GL_FRAMEBUFFER_SRGB="
                + srgbEnabled + " (ère " + era + ")");
        } catch (Throwable t) {
            LauncherLog.warn("[UiRenderer] Diagnostic gamma (Phase 5.4) indisponible : " + t);
        }
    }
}
