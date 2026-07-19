package com.yuyuframe.launcheragent.runtime.module.optimodule;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;

/**
 * Le vanilla a déjà une coupure de distance dans
 * {@code EntityRenderer.renderLabelIfPresent()} (base commune à TOUS les
 * labels flottants — pseudos joueurs/mobs, ET les hologrammes ArmorStand des
 * serveurs, très nombreux sur certains lobbys/hubs PvP), mais elle est fixée
 * au cas par cas par la méthode appelante, jamais réglable par l'utilisateur.
 * Beaucoup de labels proches/moyens (hologrammes de boutique, pseudos en
 * zone bondée) restent coûteux même sous ce seuil — matrice GL, police,
 * glColor par label, à chaque frame.
 *
 * Ce module ajoute NOTRE PROPRE seuil, réglable, appliqué EN PLUS de celui du
 * vanilla (voir MixinLabelRenderDistance189) — le label est masqué dès que
 * L'UN DES DEUX seuils est dépassé.
 *
 * Second seuil, plus proche : {@link #simplifyDistance} — au-delà, le label
 * reste affiché (tant qu'on est sous {@link #maxDistance}) mais simplifié :
 * pas de rotation "billboard" face caméra, pas de carré de fond semi-
 * transparent, pas de passe de texte "à travers les murs" (voir le détail de
 * chaque optimisation dans MixinLabelRenderDistance189).
 */
public final class LabelRenderDistanceModule extends LauncherModule {

    @ConfigSlider(name = "Distance max (blocs)", category = "Réglages", min = 8f, max = 64f, step = 4f)
    public float maxDistance = 32f;

    @ConfigSlider(name = "Distance simplification (blocs)", category = "Réglages", min = 4f, max = 32f, step = 2f)
    public float simplifyDistance = 16f;

    public LabelRenderDistanceModule() {
        // Nom raccourci — voir TileEntityRenderDistanceModule pour le pourquoi (même onglet groupé "Distance de rendu").
        super("label-render-distance", "Labels", "Masque/simplifie les pseudos/hologrammes selon la distance — utile en zone bondée ou avec beaucoup d'hologrammes serveur", true);
    }
}
