package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
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

    private static final float SWATCH_W = 42f, SWATCH_H = 26f;
    private static final float PANEL_W = 210f, PANEL_PAD = 10f;
    private static final float PREVIEW_H = 28f;
    private static final float LABEL_H = 15f, BAND_H = 18f, ROW_GAP = 8f;
    private static final float HEX_H = 26f;
    private static final int BANDS = 24;

    private float hue, sat, bri; // HSB, tous 0..1
    private float a;
    private boolean expanded;
    private final Consumer<UiColor> onChange;
    private final UiTextField hexField;

    private boolean draggingHue, draggingSat, draggingBri, draggingAlpha;

    public UiColorPicker(float x, float y, UiColor initial, Consumer<UiColor> onChange) {
        super(x, y, SWATCH_W, SWATCH_H);
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
        renderer.drawRoundedRect(x, y, x + w, y + h, 4f, UiTheme.TRACK_OFF, vpWidth, vpHeight);
        renderer.drawRoundedRect(x + 3, y + 3, x + w - 3, y + h - 3, 3f, rgbColor(), vpWidth, vpHeight);

        if (!expanded) return;

        renderer.drawRoundedRect(x, panelBottom(), x + PANEL_W, panelTop(), UiTheme.RADIUS_MD, UiTheme.PANEL_BG_ALT, vpWidth, vpHeight);

        float bx = x + PANEL_PAD, bw = PANEL_W - 2 * PANEL_PAD;

        renderer.drawRoundedRect(bx, previewY(), bx + bw, panelTop() - PANEL_PAD / 2f, 4f, rgbColor(), vpWidth, vpHeight);

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
        renderer.drawText(label, x, y + 2f, UiTheme.TEXT_SECONDARY, 0.4f, vpW, vpH);
    }

    private void drawBands(UiRenderer renderer, float x, float y, float w, float value, BandColorFn colorFn, int vpW, int vpH) {
        float bandW = w / BANDS;
        for (int i = 0; i < BANDS; i++) {
            float t = i / (float) (BANDS - 1);
            renderer.drawRoundedRect(x + i * bandW, y, x + i * bandW + bandW + 1f, y + BAND_H, 0, colorFn.at(t), vpW, vpH);
        }
        float markerX = x + value * w;
        renderer.drawRoundedRect(markerX - 2f, y - 1f, markerX + 2f, y + BAND_H + 1f, 1f, UiTheme.TEXT_PRIMARY, vpW, vpH);
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
