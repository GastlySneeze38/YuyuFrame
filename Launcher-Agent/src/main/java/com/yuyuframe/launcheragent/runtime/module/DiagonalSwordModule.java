package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;

/**
 * Épée tenue en diagonale (1ère personne) — juste un marqueur activé/
 * désactivé + l'angle ici, TOUTE la logique vit dans
 * {@code MixinDiagonalSword189} (rotation Z additionnelle en TAIL de
 * {@code HeldItemRenderer.applyEquipAndSwingOffset}, gatée sur
 * {@code instanceof SwordItem} — voir sa javadoc).
 */
public final class DiagonalSwordModule extends LauncherModule {

    // Angle par défaut réduit (25°→14°) : une rotation Z pure, sans
    // translation compensatoire, pivote l'épée autour de son coin/origine
    // plutôt que de son centre visuel — à angle trop élevé, ça donne un
    // rendu "bizarre" (retour utilisateur). Le petit décalage X/Y ci-dessous
    // recentre le pivot pour un tilt plus naturel.
    @ConfigSlider(name = "Angle (°)", category = "Réglages", min = -90f, max = 90f, step = 5f)
    public float angle = 14f;

    @ConfigSlider(name = "Décalage X", category = "Réglages", min = -0.3f, max = 0.3f, step = 0.01f)
    public float offsetX = -0.05f;

    @ConfigSlider(name = "Décalage Y", category = "Réglages", min = -0.3f, max = 0.3f, step = 0.01f)
    public float offsetY = 0.02f;

    public DiagonalSwordModule() {
        super("diagonal-sword", "Épée en diagonale", "Incline l'épée tenue en 1ère personne", false);
    }
}
