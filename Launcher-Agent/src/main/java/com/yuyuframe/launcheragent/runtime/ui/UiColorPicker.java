package com.yuyuframe.launcheragent.runtime.ui;

import java.util.function.Consumer;

/**
 * Pastille de couleur + panneau déroulant (4 sliders R/G/B/A) — pas de
 * teinte/saturation/luminosité façon roue chromatique (hors scope de cette
 * première passe), juste RGBA linéaire comme repli simple et prévisible.
 *
 * Les sliders enfants ne sont PAS enregistrés dans la liste de widgets de
 * l'écran parent (UiScreenBase.widgets) : cette pastille les possède et leur
 * délègue directement draw()/pollContinuous() elle-même. Son propre
 * contains()/onClick() (hérité de UiWidget) reste borné à la SEULE zone de la
 * pastille (w,h passés au constructeur) — cliquer un slider enfant, positionné
 * hors de ces bounds, ne referme donc jamais accidentellement le panneau.
 */
public class UiColorPicker extends UiWidget {

    private static final float SWATCH_W = 32f, SWATCH_H = 20f;
    private static final float LABEL_W = 14f, PANEL_W = 150f;
    private static final float ROW_H = 16f, ROW_GAP = 4f, PANEL_PAD = 8f;

    private float r, g, b, a;
    private boolean expanded;
    private final Consumer<UiColor> onChange;
    private final UiSlider sliderR, sliderG, sliderB, sliderA;

    public UiColorPicker(float x, float y, UiColor initial, Consumer<UiColor> onChange) {
        super(x, y, SWATCH_W, SWATCH_H);
        this.r = initial.r; this.g = initial.g; this.b = initial.b; this.a = initial.a;
        this.onChange = onChange;

        float sx = x + LABEL_W;
        float rowTop = y - PANEL_PAD; // juste sous la pastille (y = son bord bas)
        sliderR = new UiSlider(sx, rowTop - ROW_H,                       PANEL_W, 0f, 255f, 1f, r * 255f, v -> { r = v / 255f; fire(); });
        sliderG = new UiSlider(sx, sliderR.y - (ROW_H + ROW_GAP),        PANEL_W, 0f, 255f, 1f, g * 255f, v -> { g = v / 255f; fire(); });
        sliderB = new UiSlider(sx, sliderG.y - (ROW_H + ROW_GAP),        PANEL_W, 0f, 255f, 1f, b * 255f, v -> { b = v / 255f; fire(); });
        sliderA = new UiSlider(sx, sliderB.y - (ROW_H + ROW_GAP),        PANEL_W, 0f, 255f, 1f, a * 255f, v -> { a = v / 255f; fire(); });
    }

    private void fire() { if (onChange != null) onChange.accept(new UiColor(r, g, b, a)); }

    public UiColor value() { return new UiColor(r, g, b, a); }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        renderer.drawRoundedRect(x, y, x + w, y + h, 3f, UiTheme.TRACK_OFF, vpWidth, vpHeight);
        renderer.drawRoundedRect(x + 2, y + 2, x + w - 2, y + h - 2, 2f, new UiColor(r, g, b, a), vpWidth, vpHeight);

        if (!expanded) return;
        renderer.drawRoundedRect(x - PANEL_PAD, sliderA.y - PANEL_PAD, x + LABEL_W + PANEL_W + PANEL_PAD, y,
            UiTheme.RADIUS_MD, UiTheme.PANEL_BG_ALT, vpWidth, vpHeight);

        drawRow(renderer, sliderR, "R", vpWidth, vpHeight);
        drawRow(renderer, sliderG, "G", vpWidth, vpHeight);
        drawRow(renderer, sliderB, "B", vpWidth, vpHeight);
        drawRow(renderer, sliderA, "A", vpWidth, vpHeight);
    }

    private void drawRow(UiRenderer renderer, UiSlider slider, String label, int vpW, int vpH) {
        renderer.drawText(label, x, slider.y + 2f, UiTheme.TEXT_SECONDARY, 0.35f, vpW, vpH);
        slider.draw(renderer, 0, 0, vpW, vpH);
    }

    @Override
    public void onClick() {
        expanded = !expanded;
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        if (!expanded) return;
        sliderR.pollContinuous(input);
        sliderG.pollContinuous(input);
        sliderB.pollContinuous(input);
        sliderA.pollContinuous(input);
    }
}
