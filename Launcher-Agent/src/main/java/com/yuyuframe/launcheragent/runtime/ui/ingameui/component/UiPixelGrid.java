package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Grille de dessin au pixel : clic gauche maintenu pour peindre, clic droit
 * maintenu pour effacer.
 *
 * <p>Elle édite EN PLACE le tableau qu'on lui donne ({@code size × size}
 * couleurs ARGB, 0 = case vide, ligne 0 en haut) : elle ne garde aucune
 * copie, l'écran peut donc la recréer à chaque reconstruction sans rien
 * perdre. {@code onChange} est appelé à chaque case modifiée (pour que
 * l'appelant redessine), {@code onCommit} une fois le trait relâché (pour
 * sauvegarder) — un enregistrement par case écrirait le fichier de
 * configuration des dizaines de fois par trait.
 */
public final class UiPixelGrid extends UiWidget {

    private static final UiColor BACKGROUND = new UiColor(18, 18, 24, 235);
    private static final UiColor LINE = new UiColor(255, 255, 255, 16);
    private static final UiColor CENTER = new UiColor(255, 255, 255, 22);
    private static final UiColor HOVER = new UiColor(255, 255, 255, 70);

    private final int size;
    private final int[] pixels;
    private final Supplier<UiColor> paint;
    private final BooleanSupplier enabled;
    private final String disabledHint;
    private final Runnable onChange, onCommit;

    private boolean painting, erasing, dirty;
    private int lastCol = -1, lastRow = -1;

    /**
     * @param paint        la couleur du pinceau, relue à chaque case peinte
     * @param enabled      faux = grille estompée et inerte, {@code disabledHint} affiché dessus
     */
    public UiPixelGrid(float x, float y, float w, float h, int size, int[] pixels, Supplier<UiColor> paint,
                       BooleanSupplier enabled, String disabledHint, Runnable onChange, Runnable onCommit) {
        super(x, y, w, h);
        this.size = size;
        this.pixels = pixels;
        this.paint = paint;
        this.enabled = enabled;
        this.disabledHint = disabledHint;
        this.onChange = onChange;
        this.onCommit = onCommit;
    }

    // ── Géométrie : un carré de cases ENTIÈRES (pas de demi-pixel), centré ──

    private float cell() {
        float room = Math.min(w, h) - 2f * UiTheme.scaled(10f);
        return Math.max(1f, (float) Math.floor(room / size));
    }

    private float left() {
        return (float) Math.round(x + (w - cell() * size) / 2f);
    }

    /** Bord HAUT de la grille (y vers le haut). */
    private float top() {
        return (float) Math.round(y + (h + cell() * size) / 2f);
    }

    private int colAt(double mx) {
        return (int) Math.floor((mx - left()) / cell());
    }

    private int rowAt(double my) {
        return (int) Math.floor((top() - my) / cell());
    }

    private boolean inGrid(int col, int row) {
        return col >= 0 && row >= 0 && col < size && row < size;
    }

    /** Seule la grille est cliquable, pas la marge de la ligne autour. */
    @Override
    public boolean contains(double mx, double my) {
        return inGrid(colAt(mx), rowAt(my));
    }

    // ── Dessin ─────────────────────────────────────────────────────────────

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (clipFade <= 0.01f) return;
        boolean active = enabled == null || enabled.getAsBoolean();
        float fade = clipFade * (active ? 1f : 0.35f);
        float cell = cell(), left = left(), top = top(), side = cell * size;

        renderer.drawRoundedRect(left - 2f, top - side - 2f, left + side + 2f, top + 2f, UiTheme.RADIUS_SM,
            BACKGROUND.multiplyAlpha(clipFade), vpWidth, vpHeight);

        List<float[]> bounds = new ArrayList<>();
        List<UiColor> colors = new ArrayList<>();

        // La case du milieu, en croix : le repère pour centrer son dessin.
        int mid = size / 2;
        UiColor center = CENTER.multiplyAlpha(fade);
        bounds.add(new float[]{ left + mid * cell, top - side, left + (mid + 1) * cell, top });
        colors.add(center);
        bounds.add(new float[]{ left, top - (mid + 1) * cell, left + side, top - mid * cell });
        colors.add(center);

        // Le quadrillage, seulement quand une case est assez grande pour qu'il ne mange pas le dessin.
        if (cell >= 5f) {
            UiColor line = LINE.multiplyAlpha(fade);
            for (int i = 1; i < size; i++) {
                float gx = left + i * cell, gy = top - i * cell;
                bounds.add(new float[]{ gx, top - side, gx + 1f, top });
                colors.add(line);
                bounds.add(new float[]{ left, gy, left + side, gy + 1f });
                colors.add(line);
            }
        }

        // Les cases peintes, une bande par suite de cases de même couleur sur une ligne.
        for (int row = 0; row < size; row++) {
            int col = 0;
            while (col < size) {
                int argb = pixels[row * size + col];
                if (argb == 0) { col++; continue; }
                int start = col;
                while (col < size && pixels[row * size + col] == argb) col++;
                bounds.add(new float[]{ left + start * cell, top - (row + 1) * cell, left + col * cell, top - row * cell });
                colors.add(color(argb).multiplyAlpha(fade));
            }
        }

        int hoverCol = colAt(mouseX), hoverRow = rowAt(mouseY);
        if (active && inGrid(hoverCol, hoverRow)) {
            bounds.add(new float[]{ left + hoverCol * cell, top - (hoverRow + 1) * cell,
                left + (hoverCol + 1) * cell, top - hoverRow * cell });
            colors.add(HOVER.multiplyAlpha(clipFade));
        }

        renderer.drawRoundedRectBatch(bounds.toArray(new float[0][]), colors.toArray(new UiColor[0]), 0f,
            vpWidth, vpHeight);

        if (!active && disabledHint != null) {
            String hint = Lang.tr(disabledHint);
            float scale = UiTheme.scaled(0.46f);
            float tw = renderer.textWidth(hint, scale);
            renderer.drawText(hint, x + (w - tw) / 2f, y + h / 2f - UiTheme.scaled(5f),
                UiTheme.TEXT_PRIMARY.multiplyAlpha(clipFade), scale, vpWidth, vpHeight);
        }
    }

    // ── Saisie ─────────────────────────────────────────────────────────────

    @Override
    public void pollContinuous(UiInputPoller input) {
        if (enabled != null && !enabled.getAsBoolean()) {
            release();
            return;
        }
        if (!painting && !erasing) {
            if (!inGrid(colAt(input.mouseX), rowAt(input.mouseY))) return;
            if (input.leftClicked) painting = true;
            else if (input.rightClicked) erasing = true;
            else return;
            lastCol = -1;
        }
        if ((painting && !input.leftDown) || (erasing && !input.rightDown)) {
            release();
            return;
        }

        int col = colAt(input.mouseX), row = rowAt(input.mouseY);
        if (!inGrid(col, row)) {
            // Sorti de la grille en gardant le bouton enfoncé : le trait reprend en y revenant, sans ligne parasite.
            lastCol = -1;
            return;
        }
        int argb = painting ? pack(paint.get()) : 0;
        if (lastCol < 0) {
            set(col, row, argb);
        } else {
            line(lastCol, lastRow, col, row, argb);
        }
        lastCol = col;
        lastRow = row;
    }

    private void release() {
        painting = false;
        erasing = false;
        lastCol = -1;
        if (dirty) {
            dirty = false;
            if (onCommit != null) onCommit.run();
        }
    }

    /** Relie deux cases (Bresenham) : la souris saute des cases quand elle va vite. */
    private void line(int c0, int r0, int c1, int r1, int argb) {
        int dc = Math.abs(c1 - c0), dr = Math.abs(r1 - r0);
        int sc = c0 < c1 ? 1 : -1, sr = r0 < r1 ? 1 : -1;
        int err = dc - dr;
        while (true) {
            set(c0, r0, argb);
            if (c0 == c1 && r0 == r1) return;
            int twice = 2 * err;
            if (twice > -dr) { err -= dr; c0 += sc; }
            if (twice < dc) { err += dc; r0 += sr; }
        }
    }

    private void set(int col, int row, int argb) {
        int index = row * size + col;
        if (pixels[index] == argb) return;
        pixels[index] = argb;
        dirty = true;
        if (onChange != null) onChange.run();
    }

    // ── Couleurs ───────────────────────────────────────────────────────────

    /** Une couleur peinte n'est jamais « vide » : un alpha nul est remonté à 1, sinon la case s'effacerait. */
    public static int pack(UiColor color) {
        int a = Math.max(1, Math.min(255, Math.round(color.a * 255f)));
        int r = Math.max(0, Math.min(255, Math.round(color.r * 255f)));
        int g = Math.max(0, Math.min(255, Math.round(color.g * 255f)));
        int b = Math.max(0, Math.min(255, Math.round(color.b * 255f)));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static UiColor color(int argb) {
        return new UiColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
    }
}
