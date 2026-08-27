package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiWidget;

/** Bouton texte — rect arrondi, s'éclaircit en douceur au survol (voir UiAnimatedFloat), libellé centré. */
public class UiButton extends UiWidget {

    private static final UiColor BASE = new UiColor(45, 45, 50, 230);
    private static final UiColor HOVER = new UiColor(65, 65, 72, 230);
    private static final float HOVER_ANIM_SPEED = 16f;

    // Non static — dépendent de UiTheme.UI_SCALE au moment de la construction
    // (voir GlobalUiSettings, réglage "Taille de l'interface"), même motif que
    // UiSlider/UiToggle/UiColorPicker. w/h viennent déjà mis à l'échelle par
    // l'appelant (ConfigScreenBuilder, UiMainMenuScreen...).
    private final float RADIUS = UiTheme.scaled(5f);
    private final float LABEL_SCALE = UiTheme.scaled(0.46f);

    // Retour visuel à l'APPUI ajouté (voir audit runtime/ui/ : seul le hover
    // faisait un lerp de couleur, rien ne se passait à l'appui lui-même).
    // UiWidget.onClick() ne reçoit aucune coordonnée (voir sa javadoc) — le
    // ripple part donc du CENTRE du bouton plutôt que du point de clic exact,
    // simplification délibérée plutôt que de changer la signature partagée
    // par tous les widgets pour ce seul besoin. Rayon borné au plus petit
    // des deux côtés : aucun clip GPU fiable (voir UiScrollContainer), un
    // ripple plus large déborderait visiblement des coins arrondis.
    private static final long RIPPLE_DURATION_MS = 380L;
    private static final UiColor RIPPLE_COLOR = new UiColor(255, 255, 255, 255);
    private long rippleStartMs = -1L;

    private final String label;
    private final Runnable action;
    private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, HOVER_ANIM_SPEED);

    public UiButton(float x, float y, float w, float h, String label, Runnable action) {
        super(x, y, w, h);
        this.label = label;
        this.action = action;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
        float hover = hoverAnim.get();
        UiColor color = UiColor.lerp(BASE, HOVER, hover);
        // Bouton de verre (rework 2026-08-27) — composant PARTAGÉ, converti
        // ici pour propager le style à tous ses appelants d'un coup.
        renderer.drawGlassPanel(x, y, x + w, y + h, RADIUS,
            UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD, color, vpWidth, vpHeight);
        if (renderer.isGlassAvailable()) {
            UiColor border = UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.GLASS_BORDER_HOVER, hover);
            renderer.drawRoundedRectBorder(x, y, x + w, y + h, RADIUS,
                Math.max(1f, UiTheme.scaled(1f)), border, vpWidth, vpHeight);
        }
        if (rippleStartMs >= 0) {
            float progress = (System.currentTimeMillis() - rippleStartMs) / (float) RIPPLE_DURATION_MS;
            if (progress >= 1f) {
                rippleStartMs = -1L;
            } else {
                float maxRadius = Math.min(w, h) * 0.9f;
                renderer.drawRipple(x + w / 2f, y + h / 2f, maxRadius, progress, 0.22f, RIPPLE_COLOR, vpWidth, vpHeight);
            }
        }
        if (label != null) {
            float tw = renderer.textWidth(label, LABEL_SCALE);
            renderer.drawText(label, x + (w - tw) / 2f, y + h / 2f - UiTheme.scaled(5f), UiTheme.TEXT_PRIMARY, LABEL_SCALE, vpWidth, vpHeight);
        }
    }

    @Override
    public void onClick() {
        rippleStartMs = System.currentTimeMillis();
        if (action != null) action.run();
    }
}
