package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

/**
 * Bouton texte : petite surface de verre, halo d'accent au survol, enfoncement
 * à l'appui, onde au clic, libellé centré (coupé avec « ... » s'il ne tient
 * pas, texte complet en infobulle).
 */
public class UiButton extends UiInteractive {

    private static final UiColor BASE = new UiColor(45, 45, 50, 230);
    private static final UiColor HOVER = new UiColor(65, 65, 72, 230);

    /**
     * Onde partie du CENTRE (un clic ne porte pas de coordonnées), rayon borné
     * au plus petit des deux côtés : plus large, elle déborderait des coins.
     */
    private static final long RIPPLE_DURATION_MS = 380L;
    private static final UiColor RIPPLE_COLOR = new UiColor(255, 255, 255, 255);
    private long rippleStartMs = -1L;

    private final String label;
    private final Runnable action;

    public UiButton(float x, float y, float w, float h, String label, Runnable action) {
        super(x, y, w, h);
        this.label = label;
        this.action = action;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        updateStates(mouseX, mouseY);
        float radius = UiTheme.px(5f);
        // Halo d'accent qui s'étend au survol : le signal « cliquable », en
        // plus du fond qui s'éclaircit.
        if (hover > 0.01f) {
            float halo = UiTheme.px(UiTheme.Space.S3) * hover;
            renderer.drawRoundedRect(x - halo, y - halo, x + w + halo, y + h + halo, radius + halo,
                UiTheme.ACCENT.withAlpha(0.16f * hover), vpWidth, vpHeight);
        }
        // Enfoncé : la surface rentre un peu dans ses bornes, le contour passe à l'accent.
        float inset = Math.min(w, h) * 0.05f * press;
        UiColor border = UiColor.lerp(UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.GLASS_BORDER_HOVER, hover), UiTheme.ACCENT, press);
        UiSurface.field(UiColor.lerp(BASE, HOVER, hover)).radius(radius).border(border)
            .draw(renderer, x + inset, y + inset, w - inset * 2f, h - inset * 2f, vpWidth, vpHeight);
        if (rippleStartMs >= 0) {
            float progress = (System.currentTimeMillis() - rippleStartMs) / (float) RIPPLE_DURATION_MS;
            if (progress >= 1f) {
                rippleStartMs = -1L;
            } else {
                renderer.drawRipple(x + w / 2f, y + h / 2f, Math.min(w, h) * 0.9f, progress, 0.22f, RIPPLE_COLOR, vpWidth, vpHeight);
            }
        }
        if (label != null) {
            float scale = UiTheme.px(UiTheme.Text.BUTTON);
            String shown = fittedLabel(scale);
            UiText.drawCentered(renderer, null, shown, x, w, y + h / 2f - UiTheme.px(UiTheme.Space.S5),
                UiTheme.TEXT_PRIMARY, scale, vpWidth, vpHeight);
        }
    }

    private String fittedFor;
    private float fittedWidth = -1f, fittedScale = -1f;
    /** L'infobulle actuelle a été posée par {@link #fittedLabel}, pas par l'appelant. */
    private boolean autoTooltip;

    /**
     * Libellé tenant dans la largeur du bouton, coupé sinon (un libellé
     * rétréci deviendrait illisible). Recalculé seulement quand la largeur
     * change. Le texte complet passe en infobulle, jamais par-dessus une
     * infobulle choisie par l'appelant.
     */
    private String fittedLabel(float scale) {
        if (fittedFor != null && fittedWidth == w && fittedScale == scale) return fittedFor;
        float maxW = w - UiTheme.px(UiTheme.Space.S12);
        String result = UiText.fit(UiFont.REGULAR, label, maxW, scale);
        if (!result.equals(label)) {
            if (tooltip == null) {
                tooltip = label;
                autoTooltip = true;
            }
        } else if (autoTooltip) {
            tooltip = null;
            autoTooltip = false;
        }
        fittedFor = result;
        fittedWidth = w;
        fittedScale = scale;
        return result;
    }

    @Override
    protected void onActivate() {
        rippleStartMs = System.currentTimeMillis();
        if (action != null) action.run();
    }
}
