package com.yuyuframe.launcheragent.apigraphic.widget;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;

import java.util.Collections;
import java.util.List;

/**
 * Tout ce qui flotte au-dessus d'un écran : modale, tiroir, liste déroulée,
 * sélecteur de couleur, bulle de survol. Ouvert et fermé par
 * {@link UiOverlayStack}, qui seul décide qui dessine par-dessus qui, qui
 * reçoit le clic et ce que fait Échap (refonte du 2026-11-17 — il y avait
 * auparavant cinq mécanismes différents, et autant de clics qui traversaient).
 *
 * <p>Deux sortes :
 * <ul>
 *   <li><b>bloquante</b> ({@link #modal()} vrai) : voile, et tout ce qui est
 *       dessous n'existe plus pour la souris ni le clavier tant qu'elle est là ;</li>
 *   <li><b>flottante</b> : ne prend que ce qui tombe sur elle
 *       ({@link #contains}) ; un clic ailleurs la ferme par défaut et continue
 *       vers ce qu'il y a dessous.</li>
 * </ul>
 */
public interface UiOverlay {

    /** Bloquante : voile et exclusivité de la souris et du clavier. */
    boolean modal();

    /**
     * Dessine l'élément. {@code appear} va de 0 (fermé) à 1 (ouvert), en
     * linéaire, aussi bien à l'ouverture qu'à la fermeture : l'élément y
     * accroche ses propres animations. La pile pose déjà le voile.
     */
    void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight, float appear);

    /** Ce point est-il sur l'élément ? (Une bloquante prend tout, quoi qu'elle réponde.) */
    default boolean contains(double mouseX, double mouseY) { return false; }

    /** Ses widgets cliquables : la pile leur distribue clics, sondage continu, Tab et infobulles. */
    default List<UiWidget> widgets() { return Collections.emptyList(); }

    /** Clic sur l'élément qu'aucun de ses widgets n'a pris. */
    default void click(double mouseX, double mouseY, int button, boolean doubleClick) {}

    /** Sondage à chaque image, avant celui de l'écran (glissés, molette...). */
    default void poll(UiInputPoller input) {}

    /** Flottante : un clic ailleurs la ferme. */
    default boolean closeOnOutsideClick() { return true; }

    /** Échap la ferme. */
    default boolean closeOnEscape() { return true; }

    /** Appelé une fois, au moment où la fermeture est demandée (le dessin continue le temps de la sortie). */
    default void onClosed() {}

    /** Durée d'apparition, en secondes. */
    default float enterSeconds() { return 0.42f; }

    /** Durée de disparition, en secondes. */
    default float exitSeconds() { return 0.2f; }

    /** Voile posé sous une bloquante (opaque à son maximum), {@code null} = aucun. */
    default UiColor scrim() { return modal() ? DEFAULT_SCRIM : null; }

    UiColor DEFAULT_SCRIM = new UiColor(0, 0, 0, 120);
}
