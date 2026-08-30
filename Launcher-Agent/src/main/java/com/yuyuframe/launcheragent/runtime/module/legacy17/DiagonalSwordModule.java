package com.yuyuframe.launcheragent.runtime.module.legacy17;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

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
    public float angle = 14f;

    public float offsetX = -0.05f;

    public float offsetY = 0.02f;

    @Override
    protected void settings(SettingList s) {
        s.slider("angle", "Angle (°)", "Réglages", -90f, 90f, 5f, () -> angle, v -> angle = v);
        s.slider("offsetX", "Décalage X", "Réglages", -0.3f, 0.3f, 0.01f, () -> offsetX, v -> offsetX = v);
        s.slider("offsetY", "Décalage Y", "Réglages", -0.3f, 0.3f, 0.01f, () -> offsetY, v -> offsetY = v);
    }

    public DiagonalSwordModule() {
        super("diagonal-sword", "Épée en diagonale", "Incline l'épée tenue en 1ère personne", false);
    }
}
