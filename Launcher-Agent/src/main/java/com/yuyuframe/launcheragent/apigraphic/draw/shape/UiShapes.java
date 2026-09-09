package com.yuyuframe.launcheragent.apigraphic.draw.shape;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;

/**
 * Formes COMPOSÉES — du calcul pur (positions, alphas, décroissances) qui se
 * termine par des appels aux primitives de la façade. Aucune ligne d'OpenGL,
 * aucune connaissance d'ère.
 *
 * <p>Extraites de {@code UiPrimitiveRenderer} le 2026-09-09, une fois toutes
 * les primitives passées derrière {@code UiBackend} — l'ordre comptait :
 * tant que les primitives n'étaient pas dans le contrat, ces méthodes
 * composaient sur les versions INTERNES du renderer, et les déplacer aurait
 * changé leur chemin de rendu.
 *
 * <h2>Pourquoi le déplacement est neutre</h2>
 *
 * Elles composent désormais sur {@link UiRenderer}, donc via le backend. Le
 * seul appelant vivant est {@code UiButton.drawRipple}, et {@code UiButton}
 * n'est utilisé que dans les écrans de l'agent ({@code runtime/ui/ingameui/}),
 * dessinés APRÈS la présentation de la frame — où la passe GUI de vanilla
 * n'est pas armée. Le backend décline donc, et la façade retombe sur
 * exactement le chemin d'avant. Vérifié appelant par appelant.
 *
 * <p>{@code glow}, {@code skeletonShimmer} et {@code spinner} n'ont, eux,
 * AUCUN appelant : ce sont des capacités du moteur (Phase 5) jamais câblées à
 * un écran. Conservées telles quelles — le moteur les offre, libre aux écrans
 * de s'en servir.
 */
public final class UiShapes {

    private UiShapes() {}

    /**
     * Halo lumineux centré sur {@code (x1,y1)-(x2,y2)} — capacité moteur
     * dédiée (voir audit runtime/ui/ : "glow" n'existait auparavant que
     * comme nom de variable locale réutilisant {@link #drawShadow} à un seul
     * site, UiMainMenuScreen). Empile {@code layers} passes de
     * {@link #drawFx} à blur croissant/alpha décroissant plutôt qu'un seul
     * flou plat — un vrai halo s'éteint progressivement, pas en un seul
     * palier — {@code intensity} (0..1) module l'alpha de départ.
     *
     * MÊME LIMITATION que {@link #drawShadow} sur era E (1.21.6+, Blaze3D) :
     * {@code drawFx} y est un no-op (pas de flou gaussien réalisable dans le
     * pipeline GUI_TEXT réutilisé, voir sa javadoc) — cette méthode hérite
     * donc silencieusement de la même absence de rendu sur ce bracket,
     * jusqu'à ce qu'une solution Blaze3D dédiée existe (hors scope ici).
     */
    public static void glow(UiRenderer ui, float x1, float y1, float x2, float y2, float radius, float intensity, UiColor color,
                          int vpWidth, int vpHeight) {
        int layers = 3;
        for (int i = 0; i < layers; i++) {
            float t = (i + 1f) / layers;               // 0.33 / 0.66 / 1.0
            float blur = radius * 0.6f + 18f * t * t;   // flou croissant, non-linéaire (le halo s'étale plus vite qu'il ne s'assombrit)
            float alpha = intensity * (1f - t) * 0.55f; // alpha décroissant, jamais 0 pour la couche la plus large
            if (alpha <= 0.003f) continue;
            ui.drawFx(x1, y1, x2, y2, radius, blur, 0f, color.withAlpha(alpha), color, false, vpWidth, vpHeight);
        }
    }

    /**
     * Cercle plein qui s'agrandit en s'estompant — retour tactile au clic
     * (capacité absente du moteur jusqu'ici, voir audit runtime/ui/ : le
     * hover ne fait qu'un lerp de couleur, rien à l'appui). Stateless comme
     * le reste de UiRenderer : {@code progress01} (0 au clic, 1 en fin de
     * ripple) et l'alpha de départ sont calculés par l'appelant (typiquement
     * via {@link UiAnimatedFloat} ou {@link UiTransition}, déjà existants —
     * aucune nouvelle classe d'animation nécessaire pour ce primitive).
     * Construit uniquement sur {@link #drawRoundedRect} (cercle = carré
     * entièrement arrondi, radius = moitié du côté) — fonctionne donc sur
     * les 3 pipelines, Blaze3D era E inclus, sans limitation contrairement à
     * {@link #drawGlow}.
     */
    public static void ripple(UiRenderer ui, float centerX, float centerY, float maxRadius, float progress01, float startAlpha,
                            UiColor color, int vpWidth, int vpHeight) {
        float p = Math.max(0f, Math.min(1f, progress01));
        float r = maxRadius * p;
        if (r <= 0.5f) return;
        float alpha = startAlpha * (1f - p);
        if (alpha <= 0.003f) return;
        ui.drawRoundedRect(centerX - r, centerY - r, centerX + r, centerY + r, r, color.withAlpha(alpha), vpWidth, vpHeight);
    }

    /**
     * Placeholder de chargement (skeleton) + balayage lumineux (shimmer) —
     * capacités absentes du moteur jusqu'ici (voir audit runtime/ui/ :
     * UiRemoteImage n'a ni placeholder animé ni transition, apparition
     * brute dès que le fetch HTTP termine). {@code phase01} (0..1, boucle
     * en continu) positionne la barre lumineuse de gauche à droite —
     * calculé par l'appelant, ex. {@code (System.currentTimeMillis() %
     * periodMs) / (float) periodMs}, même philosophie que {@link UiStagger}
     * qui laisse déjà le timing au consommateur plutôt que de le cacher
     * dans une classe d'état supplémentaire.
     *
     * Base posée via {@link #drawRoundedRect} (fonctionne partout, era E
     * inclus) ; barre lumineuse via {@link #drawShadow} (flou) — hérite donc
     * de la MÊME LIMITATION era E que {@link #drawGlow} : le skeleton reste
     * visible sur ce bracket (fond plat correct), seul le balayage lumineux
     * n'apparaît pas tant qu'aucune solution de flou Blaze3D n'existe.
     */
    public static void skeletonShimmer(UiRenderer ui, float x1, float y1, float x2, float y2, float radius, float phase01,
                                     UiColor baseColor, UiColor highlightColor, int vpWidth, int vpHeight) {
        ui.drawRoundedRect(x1, y1, x2, y2, radius, baseColor, vpWidth, vpHeight);
        float width = x2 - x1;
        float bandWidth = Math.max(24f, width * 0.28f);
        float bx = x1 - bandWidth + (width + bandWidth * 2f) * Math.max(0f, Math.min(1f, phase01));
        ui.drawShadow(bx - bandWidth * 0.15f, y1, bx + bandWidth * 0.15f, y2, radius, bandWidth * 0.5f, 0f,
            highlightColor, vpWidth, vpHeight);
    }

    /**
     * Spinner de chargement rotatif — capacité absente du moteur jusqu'ici
     * (voir audit runtime/ui/). Choix délibéré : {@code dotCount} points
     * disposés en cercle avec un dégradé d'alpha façon "comète" plutôt
     * qu'un véritable arc balayé — un arc angulaire correct demanderait un
     * 5ᵉ programme GLSL dédié (distance signée + test d'angle atan2, ni
     * {@link #drawRoundedRect} ni le shader FX existant ne calculent
     * d'angle) pour un gain visuel marginal à ce stade. Construit
     * uniquement sur {@link #drawRoundedRect} (cercle plein par point) :
     * fonctionne sur les 3 pipelines sans limitation, contrairement à
     * {@link #drawGlow}/{@link #drawSkeletonShimmer}.
     *
     * @param rotationDeg angle de la tête de la comète, calculé par
     *                    l'appelant (ex. {@code (System.currentTimeMillis() %
     *                    periodMs) / (float) periodMs * 360f}).
     */
    public static void spinner(UiRenderer ui, float centerX, float centerY, float radius, float dotRadius, float rotationDeg,
                             UiColor color, int vpWidth, int vpHeight) {
        int dotCount = 8;
        for (int i = 0; i < dotCount; i++) {
            float t = i / (float) dotCount;                 // 0..1 autour du cercle
            float angleDeg = rotationDeg + t * 360f;
            double angleRad = Math.toRadians(angleDeg);
            float dx = centerX + (float) Math.cos(angleRad) * radius;
            float dy = centerY + (float) Math.sin(angleRad) * radius;
            // Alpha décroissant depuis la tête (t=0, la plus opaque) vers la
            // queue de la comète (t proche de 1, quasi invisible).
            float alpha = color.a * (1f - t) * (1f - t);
            if (alpha <= 0.02f) continue;
            ui.drawRoundedRect(dx - dotRadius, dy - dotRadius, dx + dotRadius, dy + dotRadius, dotRadius,
                color.withAlpha(alpha), vpWidth, vpHeight);
        }
    }

}
