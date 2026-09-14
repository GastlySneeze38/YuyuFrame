package com.yuyuframe.launcheragent.apigraphic.era.gl3.item;

import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaGuiBlit;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaItemIcon;

import java.util.List;

/**
 * Appel au rendu d'items vanilla pour une version de l'ère gl3 qui dessine son
 * GUI en pipeline fixe — pendant de {@code VanillaGuiSink.drawVanillaItems}
 * sur Blaze3D.
 *
 * <p>Même partage que Blaze3D : la file, la conversion de coordonnées et les
 * diagnostics restent dans le moteur ({@link Gl3VanillaItemRenderer}) ; seul
 * l'appel au jeu passe ici, compilé dans l'unité de la version et typé.
 *
 * <h2>Contrat</h2>
 * <ul>
 *   <li>Appelé depuis un point d'accroche où le jeu a déjà posé son état GUI :
 *       coordonnées en PIXELS GUI, origine en haut à gauche, Y vers le bas
 *       (celles de {@link VanillaItemIcon}/{@link VanillaGuiBlit}).</li>
 *   <li>Blits PUIS icônes (un fond de conteneur recouvrirait sinon les
 *       icônes) ; pour une icône {@code vanillaExtras} : fond de case avant,
 *       barre de durabilité et nombre après.</li>
 *   <li>L'état GL touché est rendu au jeu tel que vanilla le laisse après sa
 *       propre hotbar.</li>
 * </ul>
 */
public interface Gl3VanillaItemSink {

    /** Identifiant lisible pour les logs. */
    String id();

    void drawVanillaItems(List<VanillaItemIcon> icons, List<VanillaGuiBlit> blits);
}
