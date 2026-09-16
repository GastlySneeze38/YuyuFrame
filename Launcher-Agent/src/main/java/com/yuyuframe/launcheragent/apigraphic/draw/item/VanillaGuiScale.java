package com.yuyuframe.launcheragent.apigraphic.draw.item;

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

    /**
     * @return le ratio, ou {@code 1} si l'échelle est introuvable — et dans ce
     *         cas le log le dit (voir {@link #fallback()}), car 1 n'est PAS une
     *         valeur neutre pour l'appelant.
     */
    public static float of(int vpWidth) {
        // MÊME CHEMIN sur toutes les versions supportées (1.8.9, 1.21.11,
        // 26.1.2) : le point d'accès CLIENT_GUI_SCALE est lié partout et rend
        // la VRAIE échelle, sans qu'on nomme ici le moindre type de version.
        //
        // BUG TROUVÉ (2026-09-13, « la saturation est décalée d'un pixel ») :
        // jusqu'ici on rendait largeurFB / largeurGUI (CLIENT_GUI_SIZE). Or la
        // largeur GUI est arrondie AU SUPÉRIEUR : ce rapport n'est l'échelle
        // que si la fenêtre en est un multiple (1366 px à l'échelle 3 donnait
        // 2,9956). Vanilla, lui, projette sa GUI avec l'échelle ENTIÈRE — voir
        // AccessPoint.CLIENT_GUI_SCALE. D'où un écart qui grandit vers la
        // droite de l'écran et n'apparaît qu'à certaines tailles de fenêtre.
        //
        // Le repli réflexif des tranches gelées (et son cache de 200 ms) a été
        // supprimé le 2026-09-16 avec l'abandon de ces versions.
        int scale = ClientData.guiScale();
        return scale > 0 ? scale : fallback();
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
