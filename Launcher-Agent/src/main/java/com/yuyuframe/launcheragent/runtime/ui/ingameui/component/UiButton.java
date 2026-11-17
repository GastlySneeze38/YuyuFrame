package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

/**
 * LE bouton de l'interface, en trois variantes — le même geste doit avoir le
 * même aspect partout :
 * <ul>
 *   <li>{@link Variant#SECONDARY} (par défaut) : petite surface de verre ;</li>
 *   <li>{@link Variant#PRIMARY} : aplat d'accent, pour LE geste d'un écran
 *       (« Inviter », « Terminer ») ;</li>
 *   <li>{@link Variant#DANGER} : verre bordé de rouge, pour ce qui supprime.</li>
 * </ul>
 * Halo d'accent au survol, enfoncement à l'appui, onde au clic, libellé centré
 * (coupé avec « ... » s'il ne tient pas, texte complet en infobulle).
 *
 * <p>Un bouton dessiné sans widget (dans une bulle, une carte) passe par
 * {@link #paint} : même rendu, garanti.
 */
public class UiButton extends UiInteractive {

    public enum Variant { PRIMARY, SECONDARY, DANGER }

    /**
     * Onde partie du CENTRE (un clic ne porte pas de coordonnées), rayon borné
     * au plus petit des deux côtés : plus large, elle déborderait des coins.
     */
    private static final long RIPPLE_DURATION_MS = 380L;
    private static final UiColor RIPPLE_COLOR = new UiColor(255, 255, 255, 255);
    private long rippleStartMs = -1L;

    private final String label;
    private final Runnable action;
    private Variant variant = Variant.SECONDARY;

    public UiButton(float x, float y, float w, float h, String label, Runnable action) {
        super(x, y, w, h);
        this.label = label;
        this.action = action;
    }

    public UiButton variant(Variant v) {
        this.variant = v;
        return this;
    }

    /**
     * Le rendu d'un bouton, partagé par tous. {@code hover} et {@code press}
     * de 0 à 1 ; {@code label} déjà coupé à la largeur.
     */
    public static void paint(UiRenderer renderer, float x, float y, float w, float h, Variant variant,
                             float hover, float press, String label, int vpWidth, int vpHeight) {
        float radius = UiTheme.RADIUS_SM;
        // Halo d'accent qui s'étend au survol : le signal « cliquable ».
        if (hover > 0.01f) {
            float halo = UiTheme.px(UiTheme.Space.S3) * hover;
            UiColor haloColor = variant == Variant.DANGER ? UiTheme.DANGER : UiTheme.ACCENT;
            renderer.drawRoundedRect(x - halo, y - halo, x + w + halo, y + h + halo, radius + halo,
                haloColor.withAlpha(0.16f * hover), vpWidth, vpHeight);
        }
        // Enfoncé : la surface rentre un peu dans ses bornes.
        float inset = Math.min(w, h) * 0.05f * press;
        float bx = x + inset, by = y + inset, bw = w - inset * 2f, bh = h - inset * 2f;
        UiColor text;
        if (variant == Variant.PRIMARY) {
            renderer.drawRoundedRect(bx, by, bx + bw, by + bh, radius,
                UiColor.lerp(UiTheme.ACCENT, UiTheme.accentLight(), hover), vpWidth, vpHeight);
            text = UiTheme.TEXT_ON_ACCENT;
        } else {
            boolean danger = variant == Variant.DANGER;
            UiColor fill = UiColor.lerp(UiTheme.PANEL_BG_ALT, danger ? UiTheme.DANGER.multiplyAlpha(0.35f) : UiTheme.CARD_HOVER, hover);
            UiColor rest = danger ? UiTheme.DANGER.multiplyAlpha(0.6f) : UiTheme.GLASS_BORDER;
            UiColor over = danger ? UiTheme.DANGER : UiTheme.GLASS_BORDER_HOVER;
            UiColor border = UiColor.lerp(UiColor.lerp(rest, over, hover), danger ? UiTheme.DANGER : UiTheme.ACCENT, press);
            UiSurface.field(fill).radius(radius).border(border).draw(renderer, bx, by, bw, bh, vpWidth, vpHeight);
            text = danger ? UiColor.lerp(UiTheme.DANGER, UiTheme.TEXT_PRIMARY, hover) : UiTheme.TEXT_PRIMARY;
        }
        if (label != null && !label.isEmpty()) {
            float scale = UiTheme.px(UiTheme.Text.LABEL);
            UiText.drawCentered(renderer, UiFont.REGULAR, label, x, w,
                UiText.centeredBaseline(UiFont.REGULAR, y, h, scale), text, scale, vpWidth, vpHeight);
        }
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        updateStates(mouseX, mouseY);
        String shown = label == null ? null : fittedLabel(UiTheme.px(UiTheme.Text.LABEL));
        paint(renderer, x, y, w, h, variant, hover, press, shown, vpWidth, vpHeight);
        if (rippleStartMs >= 0) {
            float progress = (System.currentTimeMillis() - rippleStartMs) / (float) RIPPLE_DURATION_MS;
            if (progress >= 1f) {
                rippleStartMs = -1L;
            } else {
                renderer.drawRipple(x + w / 2f, y + h / 2f, Math.min(w, h) * 0.9f, progress, 0.22f, RIPPLE_COLOR, vpWidth, vpHeight);
            }
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
