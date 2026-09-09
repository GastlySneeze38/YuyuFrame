package com.yuyuframe.launcheragent.apigraphic.widget;

import java.util.List;

/**
 * Hit-testing centralisé (roadmap Phase 5.6) — remplace la boucle "premier
 * match en ordre d'insertion gagne" auparavant dupliquée dans {@code
 * UiScreenBase#dispatchClick}/{@code UiScrollContainer#pollInput}.
 *
 * RÈGLE : le widget dont les bornes matchent avec la PLUS PETITE AIRE gagne
 * (pas "premier inséré", pas "dernier dessiné"). Retour utilisateur exact :
 * "c'est ce qui est visible qui doit être cliquable" — un ordre d'insertion
 * n'est qu'un DÉTAIL D'IMPLÉMENTATION, il ne dit rien sur ce que l'œil perçoit
 * comme la cible précise. La "plus petite aire" est le proxy fiable pour "la
 * cible la plus SPÉCIFIQUE à cet endroit" : un petit contrôle (toggle, cœur)
 * niché DANS les bornes d'un grand conteneur (carte, bande cliquable) doit
 * toujours l'emporter sur ce dernier, quel que soit l'ordre où les deux ont
 * été ajoutés à la liste — vérifié contre les 2 cas RÉELS déjà existants dans
 * ce moteur (voir {@code UiMainMenuScreen}) :
 *   - Le cœur favori (petit, VISIBLE) niché dans la bande d'activation
 *     (grande, INVISIBLE — {@code invisibleStyle()}, aucun rendu propre) en
 *     mode Grille d'icônes : avec "premier inséré gagne" ça ne marchait QUE
 *     parce que le cœur était ajouté AVANT la bande (commentaire dédié à ce
 *     sujet dans rebuildAll()) — avec "plus petite aire gagne", ça marche
 *     PAR CONSTRUCTION, sans dépendre de cet ordre fragile.
 *   - Le toggle (petit, visible) sur une carte de mod (grande, visible) en
 *     mode Détaillé/Compacte : ajouté APRÈS la carte (pour être dessiné
 *     PAR-DESSUS, sinon invisible — contrainte de PEINTURE, PAS de clic,
 *     voir le commentaire dédié dans rebuildAll()) — donnait le résultat
 *     INVERSE ("dernier ajouté gagne" aurait suffi ici, mais PAS pour le cas
 *     du cœur ci-dessus). Aucun ordre d'insertion unique ne satisfaisait les
 *     deux cas à la fois — "plus petite aire" satisfait les DEUX
 *     simultanément, sans aucune règle d'ordre à retenir/documenter par site
 *     d'appel.
 * Égalité d'aire (rarissime, deux widgets de même taille superposés) :
 * départagé par ordre d'insertion, le DERNIER (donc visuellement le plus
 * probable "au-dessus") l'emporte.
 *
 * CACHE — le vrai sujet de cet item (voir roadmap) : {@link #find} ne refait
 * le balayage QUE si la liste, {@code mouseX} ou {@code mouseY} ont changé
 * depuis le dernier appel. {@link #invalidate()} force un recalcul au
 * prochain appel — à invoquer après tout changement de layout réel
 * (rebuildAll, resize, ajout/suppression de widget), jamais par frame.
 */
public final class UiHitTest {
    private UiHitTest() {}

    private static List<? extends UiWidget> lastList;
    private static double lastMouseX = Double.NaN, lastMouseY = Double.NaN;
    private static UiWidget cachedResult;

    /** @return le widget de {@code widgets} dont {@link UiWidget#contains} matche {@code (mouseX,mouseY)} avec la PLUS PETITE aire (voir javadoc de classe), {@code null} si aucun. */
    public static UiWidget find(List<? extends UiWidget> widgets, double mouseX, double mouseY) {
        if (widgets == lastList && mouseX == lastMouseX && mouseY == lastMouseY) {
            return cachedResult;
        }
        UiWidget best = null;
        float bestArea = Float.MAX_VALUE;
        for (UiWidget w : widgets) {
            if (!w.contains(mouseX, mouseY)) continue;
            float area = Math.max(0f, w.w) * Math.max(0f, w.h);
            if (best == null || area <= bestArea) {
                best = w;
                bestArea = area;
            }
        }
        lastList = widgets;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        cachedResult = best;
        return best;
    }

    /** Force un recalcul au prochain {@link #find} — à appeler après un changement de layout réel (jamais par frame). */
    public static void invalidate() {
        lastList = null;
        lastMouseX = Double.NaN;
        lastMouseY = Double.NaN;
        cachedResult = null;
    }
}
