package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;

import java.util.List;
import java.util.function.IntConsumer;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

/**
 * Combobox déroulant — même pattern que UiColorPicker : les lignes d'options
 * NE SONT PAS enregistrées dans la liste de widgets de l'écran/scroll parent,
 * ce widget les dessine/teste lui-même. Son propre contains()/onClick()
 * (hérité de UiWidget) reste borné au bouton d'en-tête (w,h) : cliquer une
 * option, positionnée EN DESSOUS de ces bounds, ne re-bascule donc jamais
 * accidentellement expanded via le dispatch de clic générique de l'écran.
 */
public class UiDropdown extends UiWidget {

    // Non static — dépendent de UiTheme.UI_SCALE au moment de la construction
    // (voir GlobalUiSettings, réglage "Taille de l'interface"), même motif que
    // UiSlider/UiToggle/UiColorPicker.
    private final float ROW_H = UiTheme.scaled(26f);
    private final float PANEL_PAD = UiTheme.scaled(5f);
    private final float textScale = UiTheme.scaled(0.48f);
    private final float textPad = UiTheme.scaled(8f);
    private final float textVOff = UiTheme.scaled(5f);

    private final List<String> options;
    private int selectedIndex;
    private boolean expanded;
    private final IntConsumer onChange;
    private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

    public UiDropdown(float x, float y, float w, float h, List<String> options, int initialIndex, IntConsumer onChange) {
        super(x, y, w, h);
        this.options = options;
        this.selectedIndex = Math.max(0, Math.min(options.size() - 1, initialIndex));
        this.onChange = onChange;
    }

    public int selectedIndex() { return selectedIndex; }
    public String selectedValue() { return options.get(selectedIndex); }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
        unfold.setTarget(expanded ? 1f : 0f);
        float hover = hoverAnim.get();
        float open = unfold.get();
        // Liseré qui s'allume au survol et reste allumé tant que la liste est ouverte.
        float ring = Math.max(hover, open);
        if (ring > 0.01f) {
            renderer.drawRoundedRect(x - 1f, y - 1f, x + w + 1f, y + h + 1f, UiTheme.RADIUS_SM + 1f,
                UiTheme.ACCENT.withAlpha(0.55f * ring), vpWidth, vpHeight);
        }
        UiColor bg = UiColor.lerp(UiColor.lerp(UiTheme.PANEL_BG_ALT, UiTheme.CARD_HOVER, hover), UiTheme.ACCENT_DIM, open);
        renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);

        String label = options.get(selectedIndex);
        renderer.drawText(label, x + textPad + hover * UiTheme.scaled(2f), y + h / 2f - textVOff, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
        // La flèche se retourne : « v » descend en s'effaçant, « ^ » arrive.
        float aw = renderer.textWidth("v", textScale);
        float ax = x + w - aw - textPad;
        float ay = y + h / 2f - textVOff;
        renderer.drawText("v", ax, ay - open * UiTheme.scaled(4f), UiTheme.TEXT_SECONDARY.multiplyAlpha(1f - open), textScale, vpWidth, vpHeight);
        renderer.drawText("^", ax, ay + (1f - open) * UiTheme.scaled(4f), UiTheme.accentLight().multiplyAlpha(open), textScale, vpWidth, vpHeight);
    }

    /** Ouverture de la liste, de 0 (fermée) à 1 (dépliée). */
    private final UiAnimatedFloat unfold = new UiAnimatedFloat(0f, 11f);
    /** Position de la pastille de survol dans la liste, en lignes — elle glisse d'une option à l'autre. */
    private final UiAnimatedFloat hoverRow = new UiAnimatedFloat(-1f, 24f);
    private final UiAnimatedFloat hoverRowAlpha = new UiAnimatedFloat(0f, 18f);

    /**
     * Liste déroulée ENTIÈRE — voir UiWidget#drawOverlay (même bug/même fix
     * que UiColorPicker : dessinée dans un second passage APRÈS tout le
     * reste de la liste par UiScrollContainer, ce panneau flotte désormais
     * TOUJOURS au-dessus, peu importe ce qui se trouve plus bas dans
     * l'écran). Seul le bouton d'en-tête reste dans draw().
     */
    @Override
    public void drawOverlay(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        // Dessinée tant qu'elle n'est pas complètement repliée. Le panneau
        // entier sort du bouton : il grandit depuis son bord haut en
        // descendant, avec un petit rebond, pendant que les options arrivent
        // l'une après l'autre en glissant de la gauche. Il repart par le même
        // chemin en se fermant.
        float open = unfold.get();
        if (open < 0.02f) return;

        float panelTop = y - PANEL_PAD;
        float panelBottom = panelTop - options.size() * ROW_H;
        float grow = backOut(open);
        renderer.pushLayer(Math.min(1f, open * 1.6f), 0.82f + 0.18f * grow, x + w / 2f, panelTop,
            0f, (1f - open) * UiTheme.scaled(10f));
        try {
            // Ombre ajoutée (voir audit runtime/ui/) : ce panneau flotte
            // au-dessus du contenu de l'écran en dessous (options, texte...) —
            // sans ombre rien ne le distinguait visuellement de ce qu'il
            // recouvre, contrairement à un vrai menu déroulant.
            renderer.drawShadow(x, panelBottom, x + w, panelTop, UiTheme.RADIUS_SM, 12f, 0f,
                new UiColor(0, 0, 0, 110), vpWidth, vpHeight);
            renderer.drawRoundedRect(x - 1f, panelBottom - 1f, x + w + 1f, panelTop + 1f, UiTheme.RADIUS_SM + 1f,
                UiTheme.ACCENT.withAlpha(0.35f), vpWidth, vpHeight);
            renderer.drawRoundedRect(x, panelBottom, x + w, panelTop, UiTheme.RADIUS_SM, UiTheme.PANEL_BG_ALT, vpWidth, vpHeight);

            float rowInset = UiTheme.scaled(2f), rowRadius = UiTheme.scaled(2f);
            int hovered = -1;
            for (int i = 0; i < options.size(); i++) {
                float rowTop = panelTop - i * ROW_H;
                if (mouseX >= x && mouseX <= x + w && mouseY >= rowTop - ROW_H && mouseY <= rowTop) hovered = i;
            }
            // La pastille de survol glisse d'une option à l'autre au lieu de sauter.
            if (hovered >= 0 && hoverRowAlpha.get() < 0.05f) hoverRow.snapTo(hovered);
            if (hovered >= 0) hoverRow.setTarget(hovered);
            hoverRowAlpha.setTarget(hovered >= 0 ? 1f : 0f);
            float ha = hoverRowAlpha.get();
            if (ha > 0.01f) {
                float ht = panelTop - hoverRow.get() * ROW_H;
                renderer.drawRoundedRect(x + rowInset, ht - ROW_H + 1, x + w - rowInset, ht - 1, rowRadius,
                    UiTheme.CARD_HOVER.multiplyAlpha(ha), vpWidth, vpHeight);
            }
            int n = options.size();
            for (int i = 0; i < n; i++) {
                float rowTop = panelTop - i * ROW_H;
                float rowBottom = rowTop - ROW_H;
                // Chaque option a son tour : la première d'abord.
                float rowT = Math.max(0f, Math.min(1f, (open - 0.25f * i / Math.max(1, n)) / 0.75f));
                renderer.pushLayer(rowT, 1f, x, rowBottom, -(1f - rowT) * UiTheme.scaled(14f), 0f);
                try {
                    if (i == selectedIndex) {
                        renderer.drawRoundedRect(x + rowInset, rowBottom + 1, x + w - rowInset, rowTop - 1, rowRadius,
                            UiTheme.ACCENT_DIM, vpWidth, vpHeight);
                        renderer.drawRoundedRect(x + rowInset, rowBottom + UiTheme.scaled(5f), x + rowInset + UiTheme.scaled(3f),
                            rowTop - UiTheme.scaled(5f), UiTheme.scaled(1.5f), UiTheme.ACCENT, vpWidth, vpHeight);
                    }
                    // Le libellé suit la pastille de survol : il s'écarte quand elle passe dessous.
                    float near = Math.max(0f, 1f - Math.abs(hoverRow.get() - i)) * ha;
                    renderer.drawText(options.get(i), x + textPad + near * UiTheme.scaled(4f), rowBottom + ROW_H / 2f - textVOff,
                        UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
                } finally {
                    renderer.popLayer();
                }
            }
        } finally {
            renderer.popLayer();
        }
    }

    /** Arrive avec un léger dépassement, puis se pose. */
    private static float backOut(float t) {
        float c1 = 1.4f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    @Override
    public void onClick() {
        expanded = !expanded;
    }

    /**
     * Instant où un clic a été pris par la liste déroulée. Ce clic la referme
     * sur-le-champ : pour qui demande ensuite à qui il revient
     * ({@link #overlayContains}), elle n'est déjà plus ouverte — alors que
     * c'est bien elle qui l'a reçu. On s'en souvient donc un court instant.
     */
    private long clickTakenAtNanos;
    private static final long CLICK_MEMORY_NANOS = 200_000_000L;

    private boolean overPanel(double mx, double my) {
        float panelTop = y - PANEL_PAD;
        float panelBottom = panelTop - options.size() * ROW_H;
        return mx >= x && mx <= x + w && my >= panelBottom && my <= panelTop;
    }

    /** La liste déroulée couvre ce point : le clic est pour elle, pas pour le réglage qu'elle cache. */
    @Override
    public boolean overlayContains(double mouseX, double mouseY) {
        boolean justTaken = clickTakenAtNanos != 0L && System.nanoTime() - clickTakenAtNanos < CLICK_MEMORY_NANOS;
        return (expanded || justTaken) && overPanel(mouseX, mouseY);
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        if (!expanded || !input.leftClicked) return;

        float panelTop = y - PANEL_PAD;
        for (int i = 0; i < options.size(); i++) {
            float rowTop = panelTop - i * ROW_H;
            float rowBottom = rowTop - ROW_H;
            if (input.mouseX >= x && input.mouseX <= x + w && input.mouseY >= rowBottom && input.mouseY <= rowTop) {
                selectedIndex = i;
                expanded = false;
                clickTakenAtNanos = System.nanoTime();
                if (onChange != null) onChange.accept(i);
                return;
            }
        }
        // Clic en dehors des options (mais pas forcément sur le bouton d'en-tête,
        // déjà géré par onClick via le dispatch générique) — referme le panneau,
        // sinon il resterait ouvert indéfiniment tant qu'on ne re-clique pas dessus.
        boolean onHeader = input.mouseX >= x && input.mouseX <= x + w && input.mouseY >= y && input.mouseY <= y + h;
        if (!onHeader) expanded = false;
    }
}
