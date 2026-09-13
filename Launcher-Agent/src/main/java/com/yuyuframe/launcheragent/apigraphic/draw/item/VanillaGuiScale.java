package com.yuyuframe.launcheragent.apigraphic.draw.item;

import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.game.ClientData;

/**
 * Ratio pixels FRAMEBUFFER / pixels GUI vanilla (réglage « GUI Scale » des
 * options vidéo).
 *
 * <p>Calcul PUR au sens de ce paquet : il ne dessine rien et ne connaît aucune
 * ère. Toutes les ères en dépendent — c'est la conversion qui fait passer des
 * coordonnées de CE projet (pixels framebuffer, origine bas-gauche) à celles
 * que tout le rendu vanilla attend.
 *
 * <p>Exposé aussi aux modules qui positionnent du contenu relativement au HUD
 * vanilla ({@code ArmorDurabilityModule} style « Vanilla », aligné sur la vraie
 * hotbar de 182x22 GUI-pixels ; {@code SaturationModule}).
 */
public final class VanillaGuiScale {

    private VanillaGuiScale() {}

    // AUDIT PERF (demandé explicitement par l'utilisateur, « gratter des fps
    // 26.1.2 ») : le repli réflexif fait 2 invocations — appelé plusieurs fois
    // PAR FRAME (ArmorDurabilityModule style « Vanilla » : une fois pour la
    // rangée de hotbar, puis une fois DE PLUS par icône, jusqu'à 5x/frame pour
    // ce seul module). La valeur ne dépend QUE de vpWidth ET du réglage « GUI
    // Scale » — quasi-constante d'une frame à l'autre (elle ne change qu'au
    // redimensionnement ou au changement de réglage) : cache COURT (200 ms)
    // plutôt qu'un cache infini indexé sur vpWidth seul — un changement de
    // réglage SANS redimensionnement (vpWidth inchangé) doit rester détecté,
    // juste avec un délai borné au lieu d'être invisible indéfiniment.
    private static float cachedGuiScale = 1f;
    private static int cachedGuiScaleVpWidth = -1;
    private static long cachedGuiScaleAtNanos;
    private static final long GUI_SCALE_CACHE_NANOS = 200_000_000L; // 200 ms

    /**
     * @return le ratio, ou {@code 1} si l'échelle est introuvable — et dans ce
     *         cas le log le dit (voir {@link #fallback()}), car 1 n'est PAS une
     *         valeur neutre pour l'appelant.
     */
    public static float of(int vpWidth) {
        // MÊME CHEMIN sur 26.1.2 ET 1.21.11 depuis le 2026-09-12 : le point
        // d'accès CLIENT_GUI_SIZE est lié des deux côtés et rend les VRAIES
        // dimensions GUI, sans qu'on nomme ici le moindre type de version.
        //
        // Avant, ce code appelait ClientData.window(), dont la signature nomme
        // com.mojang.blaze3d.platform.Window — la fenêtre de la 26.1.2. Sur
        // 1.21.11 c'est net.minecraft.client.util.Window : l'appel échouait,
        // on tombait dans le repli réflexif ci-dessous, et si CELUI-CI échouait
        // à son tour la méthode rendait 1 EN SILENCE. Or 1 n'est pas une
        // échelle neutre pour l'appelant : il divise ses pixels framebuffer
        // par cette valeur pour obtenir des pixels GUI, donc chaque icône
        // d'item se retrouvait placée 2 à 3 fois trop loin — hors de l'écran,
        // sans un mot dans le log.
        int[] gui = ClientData.guiSize();
        if (gui != null && gui[0] > 0) return (float) vpWidth / gui[0];

        // Brackets GELÉS (1.8.9/1.20.4/1.21.4) : le point d'accès n'y est pas
        // lié, le chemin réflexif reste leur seul recours — cache compris.
        long now = System.nanoTime();
        if (vpWidth == cachedGuiScaleVpWidth && (now - cachedGuiScaleAtNanos) < GUI_SCALE_CACHE_NANOS) {
            return cachedGuiScale;
        }
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return fallback();
            Object window = McReflect.method(mc.getClass(), "net/minecraft/client/MinecraftClient", "getWindow", "getWindow").invoke(mc);
            int scaledW = (int) McReflect.method(window.getClass(), "net/minecraft/client/util/Window", "getScaledWidth", "getGuiScaledWidth").invoke(window);
            if (scaledW <= 0) return fallback();
            cachedGuiScale = (float) vpWidth / scaledW;
            cachedGuiScaleVpWidth = vpWidth;
            cachedGuiScaleAtNanos = now;
            return cachedGuiScale;
        } catch (Throwable t) {
            return fallback();
        }
    }

    /**
     * Échelle de repli, 1 — et JAMAIS muette.
     *
     * <p>Rendre 1 revient à dire « pixels framebuffer = pixels GUI », ce qui
     * est faux dès que le joueur n'est pas en échelle 1 : tout ce qui se
     * positionne dessus part hors écran. Le symptôme est une DISPARITION, pas
     * un décalage visible — donc parfaitement indiagnosticable sans cette
     * ligne. Une fois par session suffit.
     */
    private static boolean fallbackReported;

    private static float fallback() {
        if (!fallbackReported) {
            fallbackReported = true;
            LauncherLog.err("[UiRenderer] guiScale: échelle GUI introuvable, repli sur 1"
                + " — tout ce qui se positionne dessus (icônes d'item) sera hors écran");
        }
        return 1f;
    }
}
