package com.yuyuframe.launcheragent.runtime.module.legacy17;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.MatrixOps;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * Épée tenue en diagonale (1ère personne) — petite translation puis rotation
 * autour de Z, juste avant le dessin de l'objet, seulement pour une épée.
 *
 * <p>Logique ici depuis le 2026-09-15 ({@link HookPoint#HELD_ITEM_TRANSFORM},
 * étape {@code "item"}). Elle vivait dans {@code MixinDiagonalSword189}, qui
 * cherchait l'objet tenu, {@code SwordItem} et {@code GlStateManager} par
 * réflexion à chaque image.
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

    private final MatrixOps ops = new MatrixOps();

    @Override
    protected void settings(SettingList s) {
        s.slider("angle", "Angle (°)", "Réglages", -90f, 90f, 5f, () -> angle, v -> angle = v);
        s.slider("offsetX", "Décalage X", "Réglages", -0.3f, 0.3f, 0.01f, () -> offsetX, v -> offsetX = v);
        s.slider("offsetY", "Décalage Y", "Réglages", -0.3f, 0.3f, 0.01f, () -> offsetY, v -> offsetY = v);
    }

    public DiagonalSwordModule() {
        super("diagonal-sword", "Épée en diagonale", "Incline l'épée tenue en 1ère personne", false,
            HookPoint.HELD_ITEM_TRANSFORM);
        VanillaHookRegistry.registerValue(HookPoint.HELD_ITEM_TRANSFORM, this::tilt);
    }

    private Object tilt(Object ctx) {
        if (!isEnabled() || !Legacy17.isStage(ctx, "item")) return null;
        if (!"sword".equals(PlayerData.mainHandKind())) return null;
        return ops.clear().translate(offsetX, offsetY, 0f).rotate(angle, 0f, 0f, 1f);
    }
}
