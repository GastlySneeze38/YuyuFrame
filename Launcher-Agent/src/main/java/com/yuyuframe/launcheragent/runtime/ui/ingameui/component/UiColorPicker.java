package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiEasing;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiTransition;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;

import java.awt.Color;
import java.util.function.Consumer;

/**
 * Pastille de couleur + panneau déroulant façon OneConfig (voir
 * cc.polyfrost.oneconfig.gui.elements.ColorSelector sur son repo, pris comme
 * référence directe) : sliders Teinte/Saturation/Luminosité + Alpha (bandes
 * dégradées, pas RVB linéaire comme l'ancienne version) + champ hex.
 *
 * PAS repris de l'original : pipette (nécessiterait de lire les pixels du
 * framebuffer, capacité absente de notre GraphicAPI), favoris/récents
 * (nécessiterait une vraie sauvegarde disque), mode chroma animé — jugés hors
 * scope pour un simple color picker de config.
 *
 * Les bandes de dégradé sont des rectangles pleins empilés (pas de vrai
 * shader de dégradé) — même technique que la vignette de LowHealthTintModule.
 */
public class UiColorPicker extends UiWidget {

    // Non static — dépendent de UiTheme.UI_SCALE au moment de la construction
    // (voir GlobalUiSettings, réglage "Taille de l'interface"), même motif que
    // UiSlider/UiToggle.
    private final float SWATCH_W = UiTheme.scaled(42f), SWATCH_H = UiTheme.scaled(26f);
    private final float PANEL_W = UiTheme.scaled(210f), PANEL_PAD = UiTheme.scaled(10f);
    private final float PREVIEW_H = UiTheme.scaled(28f);
    private final float LABEL_H = UiTheme.scaled(15f), BAND_H = UiTheme.scaled(18f), ROW_GAP = UiTheme.scaled(8f);
    private final float HEX_H = UiTheme.scaled(26f);
    private final float labelTextScale = UiTheme.scaled(0.4f);
    private static final int BANDS = 24;

    private float hue, sat, bri; // HSB, tous 0..1
    private float a;
    private boolean expanded;
    private final Consumer<UiColor> onChange;
    private final UiTextField hexField;
    // Transition expand/collapse ajoutée (voir audit runtime/ui/ : panneau
    // qui apparaissait/disparaissait d'un coup). Simplification délibérée :
    // seul le FOND du panneau est fondu (multiplyAlpha) — les bandes/le
    // champ hex à l'intérieur restent dessinés à pleine opacité dès que le
    // fond dépasse un seuil quasi-nul, plutôt que de multiplier l'alpha sur
    // chaque primitive individuellement (bandes de dégradé, marqueurs, champ
    // hex...) pour ce premier passage de câblage. `expanded` reste la seule
    // source de vérité pour l'INTERACTION (pollContinuous) — cette
    // transition ne pilote QUE le rendu.
    private final UiTransition panelTransition = new UiTransition(0.14f, 0f, UiEasing.EASE_OUT_CUBIC);

    private boolean draggingHue, draggingSat, draggingBri, draggingAlpha;

    public UiColorPicker(float x, float y, UiColor initial, Consumer<UiColor> onChange) {
        // Ne PAS référencer SWATCH_W/SWATCH_H ici : les initialiseurs de champs
        // d'instance ne s'exécutent qu'APRÈS cet appel super(), ils vaudraient
        // encore 0f à ce stade (piège déjà rencontré avec ArmorDurabilityModule
        // — this-avant-super()).
        super(x, y, UiTheme.scaled(42f), UiTheme.scaled(26f));
        this.onChange = onChange;
        float[] hsb = Color.RGBtoHSB(to255(initial.r), to255(initial.g), to255(initial.b), null);
        hue = hsb[0]; sat = hsb[1]; bri = hsb[2];
        a = initial.a;
        hexField = new UiTextField(0, 0, PANEL_W - 2 * PANEL_PAD, HEX_H, "#RRGGBBAA", this::onHexTyped);
        hexField.setText(hexString());
    }

    private static int to255(float v) { return Math.round(Math.max(0f, Math.min(1f, v)) * 255f); }

    private UiColor rgbColor() {
        int rgb = Color.HSBtoRGB(hue, sat, bri);
        return new UiColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, to255(a));
    }

    private void fire() {
        hexField.setText(hexString());
        if (onChange != null) onChange.accept(rgbColor());
    }

    private String hexString() {
        UiColor c = rgbColor();
        return String.format("#%02X%02X%02X%02X", to255(c.r), to255(c.g), to255(c.b), to255(c.a));
    }

    private void onHexTyped(String typed) {
        String hex = typed.startsWith("#") ? typed.substring(1) : typed;
        if (hex.length() != 6 && hex.length() != 8) return;
        try {
            int rr = Integer.parseInt(hex.substring(0, 2), 16);
            int gg = Integer.parseInt(hex.substring(2, 4), 16);
            int bb = Integer.parseInt(hex.substring(4, 6), 16);
            int aa = hex.length() == 8 ? Integer.parseInt(hex.substring(6, 8), 16) : to255(a);
            float[] hsb = Color.RGBtoHSB(rr, gg, bb, null);
            hue = hsb[0]; sat = hsb[1]; bri = hsb[2];
            a = aa / 255f;
            if (onChange != null) onChange.accept(rgbColor());
        } catch (NumberFormatException ignored) {
            // Frappe en cours (hex incomplet/invalide) — pas d'action, on attend la suite.
        }
    }

    public UiColor value() { return rgbColor(); }

    // ── Géométrie du panneau — dérivée depuis le bas de la pastille (y), empile VERS LE BAS ──

    private float panelTop() { return y - PANEL_PAD; }
    private float previewY() { return panelTop() - PREVIEW_H; }
    private float hueLabelY() { return previewY() - ROW_GAP - LABEL_H; }
    private float hueBandY() { return hueLabelY() - BAND_H; }
    private float satLabelY() { return hueBandY() - ROW_GAP - LABEL_H; }
    private float satBandY() { return satLabelY() - BAND_H; }
    private float briLabelY() { return satBandY() - ROW_GAP - LABEL_H; }
    private float briBandY() { return briLabelY() - BAND_H; }
    private float alphaLabelY() { return briBandY() - ROW_GAP - LABEL_H; }
    private float alphaBandY() { return alphaLabelY() - BAND_H; }
    private float hexY() { return alphaBandY() - ROW_GAP - HEX_H; }
    private float panelBottom() { return hexY() - PANEL_PAD; }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        float swatchRadius = UiTheme.scaled(4f), swatchInset = UiTheme.scaled(3f);
        renderer.drawRoundedRect(x, y, x + w, y + h, swatchRadius, UiTheme.TRACK_OFF, vpWidth, vpHeight);
        renderer.drawRoundedRect(x + swatchInset, y + swatchInset, x + w - swatchInset, y + h - swatchInset, swatchRadius - 1f, rgbColor(), vpWidth, vpHeight);

        panelTransition.setTarget(expanded);
        float panelAlpha = panelTransition.eased();
        if (panelAlpha <= 0.02f) return;

        renderer.drawRoundedRect(x, panelBottom(), x + PANEL_W, panelTop(), UiTheme.RADIUS_MD, UiTheme.PANEL_BG_ALT.multiplyAlpha(panelAlpha), vpWidth, vpHeight);

        float bx = x + PANEL_PAD, bw = PANEL_W - 2 * PANEL_PAD;

        renderer.drawRoundedRect(bx, previewY(), bx + bw, panelTop() - PANEL_PAD / 2f, swatchRadius, rgbColor(), vpWidth, vpHeight);

        drawBandLabel(renderer, bx, hueLabelY(), "Teinte", vpWidth, vpHeight);
        drawBands(renderer, bx, hueBandY(), bw, hue, this::hueBandColor, vpWidth, vpHeight);

        drawBandLabel(renderer, bx, satLabelY(), "Saturation", vpWidth, vpHeight);
        drawBands(renderer, bx, satBandY(), bw, sat, t -> hsbColor(hue, t, bri), vpWidth, vpHeight);

        drawBandLabel(renderer, bx, briLabelY(), "Luminosité", vpWidth, vpHeight);
        drawBands(renderer, bx, briBandY(), bw, bri, t -> hsbColor(hue, sat, t), vpWidth, vpHeight);

        drawBandLabel(renderer, bx, alphaLabelY(), "Opacité", vpWidth, vpHeight);
        UiColor solid = rgbColor();
        drawBands(renderer, bx, alphaBandY(), bw, a, t -> new UiColor(solid.r, solid.g, solid.b, t), vpWidth, vpHeight);

        hexField.x = bx;
        hexField.y = hexY();
        hexField.draw(renderer, mouseX, mouseY, vpWidth, vpHeight);
    }

    private interface BandColorFn { UiColor at(float t); }

    private void drawBandLabel(UiRenderer renderer, float x, float y, String label, int vpW, int vpH) {
        renderer.drawText(label, x, y + UiTheme.scaled(2f), UiTheme.TEXT_SECONDARY, labelTextScale, vpW, vpH);
    }

    private void drawBands(UiRenderer renderer, float x, float y, float w, float value, BandColorFn colorFn, int vpW, int vpH) {
        float bandW = w / BANDS;
        for (int i = 0; i < BANDS; i++) {
            float t = i / (float) (BANDS - 1);
            renderer.drawRoundedRect(x + i * bandW, y, x + i * bandW + bandW + 1f, y + BAND_H, 0, colorFn.at(t), vpW, vpH);
        }
        float markerX = x + value * w;
        float markerHalfW = UiTheme.scaled(2f), markerPad = UiTheme.scaled(1f);
        renderer.drawRoundedRect(markerX - markerHalfW, y - markerPad, markerX + markerHalfW, y + BAND_H + markerPad, markerPad, UiTheme.TEXT_PRIMARY, vpW, vpH);
    }

    private UiColor hueBandColor(float t) {
        return hsbColor(t, 1f, 1f);
    }

    private static UiColor hsbColor(float h, float s, float b) {
        int rgb = Color.HSBtoRGB(h, s, b);
        return new UiColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
    }

    @Override
    public void onClick() {
        expanded = !expanded;
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        if (!expanded) return;
        hexField.pollContinuous(input);

        float bx = x + PANEL_PAD, bw = PANEL_W - 2 * PANEL_PAD;

        if (!input.leftDown) {
            draggingHue = draggingSat = draggingBri = draggingAlpha = false;
            return;
        }

        if (input.leftClicked) {
            if (over(input, bx, hueBandY(), bw)) draggingHue = true;
            else if (over(input, bx, satBandY(), bw)) draggingSat = true;
            else if (over(input, bx, briBandY(), bw)) draggingBri = true;
            else if (over(input, bx, alphaBandY(), bw)) draggingAlpha = true;
            else if (hexField.contains(input.mouseX, input.mouseY)) hexField.onClick();
        }

        if (draggingHue) { hue = clampT(input.mouseX, bx, bw); fire(); }
        else if (draggingSat) { sat = clampT(input.mouseX, bx, bw); fire(); }
        else if (draggingBri) { bri = clampT(input.mouseX, bx, bw); fire(); }
        else if (draggingAlpha) { a = clampT(input.mouseX, bx, bw); fire(); }
    }

    private boolean over(UiInputPoller input, float bx, float by, float bw) {
        return input.mouseX >= bx && input.mouseX <= bx + bw && input.mouseY >= by && input.mouseY <= by + BAND_H;
    }

    private float clampT(double mouseX, float bx, float bw) {
        return Math.max(0f, Math.min(1f, (float) (mouseX - bx) / bw));
    }
}
