package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiOverlay;
import com.yuyuframe.launcheragent.apigraphic.widget.UiOverlayStack;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Liste déroulante. Le widget lui-même n'est que l'en-tête (la valeur choisie
 * et sa flèche) ; la liste dépliée est un élément flottant de l'écran
 * ({@link UiOverlay}, ouvert dans {@link UiOverlayStack#active()}) : elle passe
 * au-dessus de tout, prend les clics qui tombent sur elle, se ferme au clic
 * ailleurs et à Échap — sans rien demander à l'écran ni à la liste qui la
 * contient.
 */
public class UiDropdown extends UiWidget {

    private final List<String> options;
    private int selectedIndex;
    private final IntConsumer onChange;
    private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, UiTheme.Motion.HOVER);
    /** Ouverture vue par l'en-tête (flèche, anneau), de 0 à 1. */
    private final UiAnimatedFloat unfold = new UiAnimatedFloat(0f, UiTheme.Motion.UNFOLD);
    /** Position de la pastille de survol dans la liste, en lignes — elle glisse d'une option à l'autre. */
    private final UiAnimatedFloat hoverRow = new UiAnimatedFloat(-1f, 24f);
    private final UiAnimatedFloat hoverRowAlpha = new UiAnimatedFloat(0f, 18f);
    /** La pile où la liste a été ouverte (celle de l'écran au moment du clic). */
    private UiOverlayStack openedIn;

    public UiDropdown(float x, float y, float w, float h, List<String> options, int initialIndex, IntConsumer onChange) {
        super(x, y, w, h);
        this.options = options;
        this.selectedIndex = Math.max(0, Math.min(options.size() - 1, initialIndex));
        this.onChange = onChange;
    }

    public int selectedIndex() { return selectedIndex; }
    public String selectedValue() { return options.get(selectedIndex); }

    private float rowH() { return UiTheme.px(26f); }
    private float panelPad() { return UiTheme.px(UiTheme.Space.S5); }
    private float textScale() { return UiTheme.px(UiTheme.Text.LABEL); }
    private float textPad() { return UiTheme.px(UiTheme.Space.S8); }
    private float textVOff() { return UiTheme.px(UiTheme.Space.S5); }

    private boolean expanded() { return openedIn != null && openedIn.isOpen(list); }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
        unfold.setTarget(expanded() ? 1f : 0f);
        float hover = hoverAnim.get();
        float open = unfold.get();
        // Anneau d'accent au survol et tant que la liste est ouverte : on voit
        // de quel réglage elle sort.
        float ring = Math.max(hover, open);
        if (ring > 0.01f) {
            renderer.drawRoundedRect(x - 1f, y - 1f, x + w + 1f, y + h + 1f, UiTheme.RADIUS_SM + 1f,
                UiTheme.ACCENT.withAlpha(0.55f * ring), vpWidth, vpHeight);
        }
        UiColor bg = UiColor.lerp(UiColor.lerp(UiTheme.PANEL_BG_ALT, UiTheme.CARD_HOVER, hover), UiTheme.ACCENT_DIM, open);
        renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);

        float scale = textScale(), pad = textPad(), voff = textVOff();
        renderer.drawText(options.get(selectedIndex), x + pad + hover * UiTheme.px(UiTheme.Space.S2), y + h / 2f - voff,
            UiTheme.TEXT_PRIMARY, scale, vpWidth, vpHeight);
        // Chevron (une icône, pas les lettres « v » et « ^ ») : il bascule en glissant, sans saut.
        drawChevron(renderer, x + w - pad, y + h / 2f, open, vpWidth, vpHeight);
    }

    static final String CHEVRON_DOWN = UiIcon.named("expand-arrow");
    static final String CHEVRON_UP = UiIcon.named("collapse-arrow");

    /** Chevron d'une liste déroulante, bord droit à {@code right}, centré sur {@code midY} ; {@code open} de 0 à 1. */
    public static void drawChevron(UiRenderer renderer, float right, float midY, float open, int vpWidth, int vpHeight) {
        float size = UiTheme.px(UiTheme.Space.S12);
        float cx = right - size;
        float shift = UiTheme.px(UiTheme.Space.S4);
        UiIcon.crisp(renderer, CHEVRON_DOWN, cx, midY - size / 2f - open * shift, size, 0.7f * (1f - open), vpWidth, vpHeight);
        UiIcon.crisp(renderer, CHEVRON_UP, cx, midY - size / 2f + (1f - open) * shift, size, open, vpWidth, vpHeight);
    }

    /**
     * Un même clic peut arriver deux fois : par l'événement du jeu (la pile)
     * et par le sondage d'une liste qui défile, dans un ordre qui dépend de la
     * version. Un geste à moins de {@link #SAME_CLICK_NANOS} du précédent est
     * le même clic : il ne rouvre ni ne referme rien.
     */
    private static final long SAME_CLICK_NANOS = 120_000_000L;
    private long lastToggleNanos;

    private boolean sameClick() {
        return System.nanoTime() - lastToggleNanos < SAME_CLICK_NANOS;
    }

    @Override
    public void onClick() {
        if (sameClick()) return;
        lastToggleNanos = System.nanoTime();
        UiOverlayStack stack = UiOverlayStack.active();
        if (stack.isOpen(list)) {
            stack.close(list);
        } else {
            openedIn = stack;
            hoverRowAlpha.snapTo(0f);
            stack.open(list);
        }
    }

    private float panelTop() { return y - panelPad(); }
    private float panelBottom() { return panelTop() - options.size() * rowH(); }

    private boolean overPanel(double mx, double my) {
        return mx >= x && mx <= x + w && my >= panelBottom() && my <= panelTop();
    }


    /** La liste dépliée. Elle suit l'en-tête : sa position est relue à chaque image (liste qui défile). */
    private final UiOverlay list = new UiOverlay() {
        @Override
        public boolean modal() { return false; }

        /** L'en-tête en fait partie : le recliquer referme la liste au lieu de la rouvrir. */
        @Override
        public boolean contains(double mx, double my) {
            return overPanel(mx, my) || UiDropdown.this.contains(mx, my);
        }

        @Override
        public float enterSeconds() { return 0.2f; }

        @Override
        public float exitSeconds() { return 0.14f; }

        @Override
        public void click(double mx, double my, int button, boolean doubleClick) {
            if (!overPanel(mx, my) && sameClick()) return; // le clic qui vient de l'ouvrir
            lastToggleNanos = System.nanoTime();
            if (button == 0 && overPanel(mx, my)) {
                int i = (int) Math.floor((panelTop() - my) / rowH());
                if (i >= 0 && i < options.size()) {
                    selectedIndex = i;
                    if (onChange != null) onChange.accept(i);
                }
            }
            if (openedIn != null) openedIn.close(this);
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight, float appear) {
            // appear porte déjà la courbe ; la liste sort de son en-tête (pivot en haut), sans rebond.
            float open = appear;
            if (open < 0.02f) return;
            float rowH = rowH(), top = panelTop(), bottom = panelBottom();
            float scale = textScale(), pad = textPad(), voff = textVOff();
            renderer.pushLayer(open, 0.96f + 0.04f * open, x + w / 2f, top,
                0f, (1f - open) * UiTheme.px(UiTheme.Space.S4));
            try {
                // Tout ce qui flotte est du verre de modale, bordé d'accent pour dire d'où il sort.
                UiSurface.of(UiTheme.Glass.MODAL).radius(UiTheme.RADIUS_MD).fill(UiTheme.PANEL_BG_ALT)
                    .border(UiTheme.ACCENT.withAlpha(0.35f)).shadow(UiTheme.SHADOW_PANEL, 12f, 0f, 0f)
                    .draw(renderer, x, bottom, w, top - bottom, vpWidth, vpHeight);

                float rowInset = UiTheme.px(UiTheme.Space.S4), rowRadius = UiTheme.RADIUS_SM;
                int hovered = -1;
                if (overPanel(mouseX, mouseY)) hovered = (int) Math.floor((top - mouseY) / rowH);
                if (hovered >= options.size()) hovered = -1;
                // Première entrée sur la liste : la pastille apparaît sous la souris au lieu d'y glisser.
                if (hovered >= 0 && hoverRowAlpha.get() < 0.05f) hoverRow.snapTo(hovered);
                if (hovered >= 0) hoverRow.setTarget(hovered);
                hoverRowAlpha.setTarget(hovered >= 0 ? 1f : 0f);
                float ha = hoverRowAlpha.get();
                if (ha > 0.01f) {
                    float ht = top - hoverRow.get() * rowH;
                    renderer.drawRoundedRect(x + rowInset, ht - rowH + 1, x + w - rowInset, ht - 1, rowRadius,
                        UiTheme.CARD_HOVER.multiplyAlpha(ha), vpWidth, vpHeight);
                }
                int n = options.size();
                for (int i = 0; i < n; i++) {
                    float rowTop = top - i * rowH;
                    float rowBottom = rowTop - rowH;
                    // Les lignes arrivent l'une après l'autre, en glissant de la gauche.
                    float rowT = Math.max(0f, Math.min(1f, (open - 0.25f * i / Math.max(1, n)) / 0.75f));
                    renderer.pushLayer(rowT, 1f, x, rowBottom, -(1f - rowT) * UiTheme.px(UiTheme.Space.S14), 0f);
                    try {
                        if (i == selectedIndex) {
                            renderer.drawRoundedRect(x + rowInset, rowBottom + 1, x + w - rowInset, rowTop - 1, rowRadius,
                                UiTheme.ACCENT_DIM, vpWidth, vpHeight);
                            renderer.drawRoundedRect(x + rowInset, rowBottom + UiTheme.px(UiTheme.Space.S5), x + rowInset + UiTheme.px(UiTheme.Space.S3),
                                rowTop - UiTheme.px(UiTheme.Space.S5), UiTheme.px(UiTheme.Radius.XS), UiTheme.ACCENT, vpWidth, vpHeight);
                        }
                        float near = Math.max(0f, 1f - Math.abs(hoverRow.get() - i)) * ha;
                        renderer.drawText(options.get(i), x + pad + near * UiTheme.px(UiTheme.Space.S4), rowBottom + rowH / 2f - voff,
                            UiTheme.TEXT_PRIMARY, scale, vpWidth, vpHeight);
                    } finally {
                        renderer.popLayer();
                    }
                }
            } finally {
                renderer.popLayer();
            }
        }
    };
}
