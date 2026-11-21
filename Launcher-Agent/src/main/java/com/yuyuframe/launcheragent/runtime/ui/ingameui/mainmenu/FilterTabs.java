package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.effect.UiBackdropTone;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Un choix en segments (« Tous 26 · Activés 8 · Désactivés 18 ») : le
 * segment choisi en pilule d'accent, comme l'onglet ouvert de la barre du
 * haut. Les nombres sont relus à chaque image.
 */
public final class FilterTabs extends UiInteractive {

    private final String[] labels;
    private final IntSupplier[] counts;
    private final IntSupplier selected;
    private final IntConsumer onSelect;
    private double lastMouseX;

    public FilterTabs(String[] labels, IntSupplier[] counts, IntSupplier selected, IntConsumer onSelect) {
        this.labels = labels;
        this.counts = counts;
        this.selected = selected;
        this.onSelect = onSelect;
    }

    private float pad() { return px(UiTheme.Space.S4); }

    private float labelScale() { return px(UiTheme.Text.SM); }

    private float countScale() { return px(UiTheme.Text.XS); }

    private float segmentWidth(int i) {
        String count = String.valueOf(counts[i].getAsInt());
        return UiText.width(UiFont.BOLD, Lang.tr(labels[i]), labelScale()) + px(UiTheme.Space.S6)
            + UiText.width(UiFont.REGULAR, count, countScale()) + 2f * px(UiTheme.Space.S16);
    }

    public float preferredWidth() {
        float total = 2f * pad() + (labels.length - 1) * pad();
        for (int i = 0; i < labels.length; i++) total += segmentWidth(i);
        return total;
    }

    @Override
    public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (w <= 0f || h <= 0f) return;
        updateStates(mouseX, mouseY);
        lastMouseX = mouseX;
        float radius = px(UiTheme.Space.S14);
        // Posé sur le décor : sur une page claire, c'est son fond qui s'assombrit, les mots restent blancs (UiBackdropTone).
        float light = UiBackdropTone.lightness(-1f);
        r.drawRoundedRect(x, y, x + w, y + h, radius, UiBackdropTone.controlFill(new UiColor(255, 255, 255, 13)), vw, vh);

        int current = selected.getAsInt();
        float sx = x + pad();
        float sh = h - 2f * pad();
        for (int i = 0; i < labels.length; i++) {
            float sw = segmentWidth(i);
            boolean over = hover > 0.01f && mouseX >= sx && mouseX < sx + sw && contains(mouseX, mouseY);
            // La pilule du segment choisi, d'accent, un peu plus franche sur le fond assombri d'une page claire.
            if (i == current) {
                r.drawRoundedRect(sx, y + pad(), sx + sw, y + pad() + sh, px(UiTheme.Space.S10),
                    UiTheme.ACCENT.withAlpha(0.42f + 0.18f * light), vw, vh);
            } else if (over) {
                r.drawRoundedRect(sx, y + pad(), sx + sw, y + pad() + sh, px(UiTheme.Space.S10),
                    UiTheme.ACCENT.withAlpha(0.16f * hover), vw, vh);
            }
            // UNE teinte pour tous les mots, choisi compris : le blanc, sur un fond qui s'adapte.
            UiColor color = UiTheme.TEXT_PRIMARY;
            String label = Lang.tr(labels[i]);
            float ls = labelScale();
            float tx = sx + px(UiTheme.Space.S16);
            r.drawText(UiFont.BOLD, label, tx, UiText.centeredBaseline(UiFont.BOLD, y, h, ls), color, ls, vw, vh);
            tx += UiText.width(UiFont.BOLD, label, ls) + px(UiTheme.Space.S6);
            r.drawText(UiFont.REGULAR, String.valueOf(counts[i].getAsInt()), tx, UiText.centeredBaseline(UiFont.BOLD, y, h, ls),
                color, countScale(), vw, vh);
            sx += sw + pad();
        }
    }

    @Override
    protected void onActivate() {
        float sx = x + pad();
        for (int i = 0; i < labels.length; i++) {
            float sw = segmentWidth(i);
            if (lastMouseX >= sx && lastMouseX < sx + sw) {
                if (onSelect != null) onSelect.accept(i);
                return;
            }
            sx += sw + pad();
        }
    }
}
