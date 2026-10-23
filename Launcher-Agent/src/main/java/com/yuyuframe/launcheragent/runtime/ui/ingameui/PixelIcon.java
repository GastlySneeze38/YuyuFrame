package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;

import java.util.HashMap;
import java.util.Map;

/**
 * Une icône en pixels, à la manière d'une texture d'objet du jeu : une grille
 * de lettres (une par pixel, {@code '.'} = transparent) et la couleur de
 * chaque lettre. Dessinée pixel par pixel, chaque bord calé sur l'écran, sans
 * lissage : nette à toute taille, comme un objet de l'inventaire agrandi.
 *
 * <p>Générique : rien n'y parle de rang. Les icônes elles-mêmes vivent chez
 * qui les utilise ({@link PvpRank} pour le classement).
 */
public final class PixelIcon {

    private final String[] rows;
    private final Map<Character, Integer> colors = new HashMap<>();
    private final int width;

    /**
     * @param rows    les lignes, de haut en bas, toutes de même longueur
     * @param palette des paires lettre / couleur RGB : {@code 'k', 0x2A2A2A, 'L', 0xD8D8D8…}
     */
    public PixelIcon(String[] rows, Object... palette) {
        this.rows = rows;
        int w = 0;
        for (String row : rows) w = Math.max(w, row.length());
        this.width = w;
        for (int i = 0; i + 1 < palette.length; i += 2) {
            colors.put((Character) palette[i], (Integer) palette[i + 1]);
        }
    }

    /**
     * Dessine l'icône dans le carré {@code (x, y, size)} — repère du moteur,
     * origine en bas à gauche. {@code grey} la désature (une icône pas encore
     * acquise, par exemple).
     */
    public void draw(UiRenderer r, float x, float y, float size, float alpha, boolean grey, int vw, int vh) {
        int grid = Math.max(rows.length, width);
        float cell = size / grid;
        // Centrée si elle n'est pas carrée.
        float offsetX = (grid - width) * cell / 2f;
        float top = y + size - (grid - rows.length) * cell / 2f;
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            int start = 0;
            while (start < line.length()) {
                char c = line.charAt(start);
                int end = start + 1;
                while (end < line.length() && line.charAt(end) == c) end++;
                Integer rgb = colors.get(c);
                if (c != '.' && rgb != null) {
                    float x0 = Math.round(x + offsetX + start * cell), x1 = Math.round(x + offsetX + end * cell);
                    float y1 = Math.round(top - row * cell), y0 = Math.round(top - (row + 1) * cell);
                    r.drawRoundedRect(x0, y0, x1, y1, 0f, color(rgb, grey).multiplyAlpha(alpha), vw, vh);
                }
                start = end;
            }
        }
    }

    private static UiColor color(int rgb, boolean grey) {
        int red = (rgb >> 16) & 255, green = (rgb >> 8) & 255, blue = rgb & 255;
        if (grey) {
            int luma = (red * 30 + green * 59 + blue * 11) / 100;
            red = green = blue = luma;
        }
        return new UiColor(red, green, blue, 255);
    }
}
