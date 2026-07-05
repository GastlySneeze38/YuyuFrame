package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.ui.ConfigScreenBuilder;
import com.yuyuframe.launcheragent.runtime.ui.HudConfigStore;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleGroup;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiLabel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiPanel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiToggle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Page de config d'un {@link ModuleGroup} — même carcasse que
 * {@link UiModConfigScreen} (bouton retour, sous-sidebar d'onglets, carte de
 * contenu scrollable), mais UN ONGLET PAR {@link ModuleGroup.Tab} (pas par
 * catégorie de champ d'un seul module) : chaque onglet empile le toggle
 * d'activation (déplacé ici puisqu'un module groupé n'a plus sa propre carte
 * pour le porter) + les réglages annotés habituels (via
 * {@link ConfigScreenBuilder}) de CHAQUE module qui lui est rattaché — la
 * plupart des onglets n'ont qu'un seul module, mais certains en cumulent
 * plusieurs d'apparentés (ex: "Culling face arrière" = joueur + entités,
 * "Distance de rendu" = tile entities + labels + particules — voir
 * ModuleRegistry) pour réduire le nombre d'onglets et la longueur de leurs
 * noms dans la sous-sidebar.
 */
public class UiModGroupConfigScreen extends UiScreenBase {

    // Non static/final — recalculées à chaque buildLayout() depuis
    // UiTheme.UI_SCALE (voir GlobalUiSettings, réglage "Taille de
    // l'interface"), même motif que UiMainMenuScreen/ConfigScreenBuilder.
    private float HEADER_H = 72f;
    private float SIDE_MARGIN = 48f;
    // Élargi (180->200) — les noms d'onglets (parfois encore longs même après
    // regroupement, ex: "Threads de construction") débordaient de la largeur
    // précédente, voir aussi la réduction de LABEL_SCALE ci-dessous.
    private float SUB_SIDEBAR_W = 200f;
    private float CONTENT_MAX_W = 620f;
    private float ROW_H = 34f, ROW_GAP = 8f;
    private float LABEL_SCALE = 0.5f;
    /** Scale du nom d'onglet dans la sous-sidebar — réduit (0.48->0.4) pour que les noms les plus longs restent lisibles sans déborder. */
    private float TAB_LABEL_SCALE = 0.4f;
    /** Sous-titre affiché au-dessus du toggle "Activé" d'un module — UNIQUEMENT quand son onglet en cumule plusieurs (voir javadoc de classe), pour distinguer "Joueur" de "Entités" par exemple. */
    private float SUB_HEADER_H = 22f;
    private float SUB_HEADER_SCALE = 0.4f;

    private final Object lastScreen;
    private final ModuleGroup group;
    private LinkedHashMap<String, List<UiWidget>> tabWidgets = new LinkedHashMap<>();
    private String activeTab;
    private UiScrollContainer scroll;
    private float panelX, panelY, panelW, panelH;
    private int lastLayoutWidth = -1, lastLayoutHeight = -1;
    private float lastUiScale = -1f;

    public UiModGroupConfigScreen(Object lastScreen, ModuleGroup group) {
        super(group.name);
        this.lastScreen = lastScreen;
        this.escapeTarget = lastScreen;
        this.group = group;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (screenWidth > 0 && screenHeight > 0
                && (screenWidth != lastLayoutWidth || screenHeight != lastLayoutHeight || UiTheme.UI_SCALE != lastUiScale)) {
            buildLayout();
            lastLayoutWidth = screenWidth;
            lastLayoutHeight = screenHeight;
            lastUiScale = UiTheme.UI_SCALE;
        }
        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            renderer.drawText(UiFont.BOLD, group.name, SIDE_MARGIN + UiTheme.scaled(46f), screenHeight - UiTheme.scaled(44f),
                UiTheme.TEXT_PRIMARY, UiTheme.scaled(0.68f), screenWidth, screenHeight);
            UiPanel.draw(renderer, panelX, panelY, panelW, panelH, null, screenWidth, screenHeight);
            if (scroll != null) scroll.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            // Voir UiModConfigScreen — ré-appliqué pour couvrir le titre/panneau ci-dessus.
            drawRevealVeil(renderer);
        } catch (Throwable ignored) {}
    }

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        if (scroll != null) scroll.pollInput(input);
    }

    private void buildLayout() {
        HEADER_H = UiTheme.scaled(72f);
        SIDE_MARGIN = UiTheme.scaled(48f);
        SUB_SIDEBAR_W = UiTheme.scaled(200f);
        CONTENT_MAX_W = UiTheme.scaled(620f);
        ROW_H = UiTheme.scaled(34f);
        ROW_GAP = UiTheme.scaled(8f);
        LABEL_SCALE = UiTheme.scaled(0.5f);
        TAB_LABEL_SCALE = UiTheme.scaled(0.4f);
        SUB_HEADER_H = UiTheme.scaled(22f);
        SUB_HEADER_SCALE = UiTheme.scaled(0.4f);

        widgets.clear();
        widgets.add(new BackButton());

        float panelXLocal = SIDE_MARGIN + SUB_SIDEBAR_W + UiTheme.scaled(16f);
        float panelWLocal = Math.min(CONTENT_MAX_W, screenWidth - panelXLocal - SIDE_MARGIN);
        float panelTop = screenHeight - HEADER_H;
        float panelBottom = UiTheme.scaled(20f);
        this.panelX = panelXLocal;
        this.panelY = panelBottom;
        this.panelW = panelWLocal;
        this.panelH = panelTop - panelBottom;

        float rowX = panelX + UiTheme.scaled(16f);
        float rowW = panelW - UiTheme.scaled(32f);
        scroll = new UiScrollContainer(rowX, panelY + UiTheme.scaled(16f), rowW, panelH - UiTheme.scaled(32f));

        tabWidgets = new LinkedHashMap<>();
        for (ModuleGroup.Tab tab : group.tabs) {
            List<UiWidget> rows = new ArrayList<>();
            float cursor = 0f;
            // Sous-titre par module UNIQUEMENT utile quand l'onglet en cumule
            // plusieurs (voir javadoc de classe) — avec un seul module, le nom
            // de l'onglet EST déjà le nom du module, un sous-titre identique
            // juste en dessous serait redondant.
            boolean multi = tab.modules.size() > 1;

            for (LauncherModule member : tab.modules) {
                if (member == null) continue;

                if (multi) {
                    rows.add(new UiLabel(rowX, cursor - SUB_HEADER_H / 2f - UiTheme.scaled(4f), member.name, UiTheme.TEXT_SECONDARY, SUB_HEADER_SCALE));
                    cursor -= SUB_HEADER_H;
                }

                // Toggle d'activation du module EN PREMIER dans son bloc —
                // même convention que le toggle de carte sur l'écran
                // d'accueil (voir UiMainMenuScreen), juste déplacé ici
                // puisqu'un module groupé n'a plus sa propre carte pour le
                // porter.
                float rowY = cursor - ROW_H;
                rows.add(new UiLabel(rowX, rowY + ROW_H / 2f - UiTheme.scaled(5f), "Activé", UiTheme.TEXT_PRIMARY, LABEL_SCALE));
                rows.add(new UiToggle(rowX + rowW - UiTheme.scaled(44f), rowY + (ROW_H - UiTheme.scaled(24f)) / 2f, member.isEnabled(),
                    v -> { member.setEnabled(v); HudConfigStore.save(); }));
                cursor = rowY - ROW_GAP;

                // ConfigScreenBuilder positionne chaque catégorie en partant
                // d'un curseur à 0 (indépendant) — UiWidget expose x/y en
                // public mutable (même pattern que searchField.x/y dans
                // UiMainMenuScreen), donc on décale tout du "cursor" courant
                // pour enchaîner juste après le toggle "Activé" de CE module,
                // puis on avance le curseur jusqu'au point le plus bas atteint
                // par CETTE catégorie avant de passer à la suivante.
                LinkedHashMap<String, List<UiWidget>> memberCategories = ConfigScreenBuilder.build(member, rowX, rowW);
                for (List<UiWidget> categoryRows : memberCategories.values()) {
                    if (categoryRows.isEmpty()) continue;
                    float shift = cursor;
                    float minY = Float.MAX_VALUE;
                    for (UiWidget w : categoryRows) {
                        w.y += shift;
                        if (w.y < minY) minY = w.y;
                    }
                    rows.addAll(categoryRows);
                    cursor = minY - ROW_GAP;
                }

                // Marge additionnelle entre deux modules cumulés dans le même
                // onglet — sans ça, le sous-titre du module suivant colle
                // directement à la dernière ligne de réglage du précédent.
                if (multi) cursor -= ROW_GAP;
            }

            tabWidgets.put(tab.label, rows);
        }
        if (activeTab == null || !tabWidgets.containsKey(activeTab)) {
            activeTab = tabWidgets.isEmpty() ? null : tabWidgets.keySet().iterator().next();
        }

        float tabH = UiTheme.scaled(34f), tabGap = UiTheme.scaled(38f), tabTopGap = UiTheme.scaled(24f);
        int i = 0;
        for (String tab : tabWidgets.keySet()) {
            widgets.add(new TabItem(SIDE_MARGIN, panelTop - tabTopGap - i * tabGap, SUB_SIDEBAR_W, tabH, tab));
            i++;
        }

        switchTab(activeTab);
    }

    private void switchTab(String tab) {
        activeTab = tab;
        scroll.clear();
        if (tab == null) return;
        List<UiWidget> rows = tabWidgets.get(tab);
        if (rows != null) for (UiWidget w : rows) scroll.add(w);
    }

    private final class TabItem extends UiWidget {
        private final String name;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        TabItem(float x, float y, float w, float h, String name) {
            super(x, y, w, h);
            this.name = name;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean active = name.equals(activeTab);
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float edgeW = UiTheme.scaled(3f), edgeInset = UiTheme.scaled(3f), edgeRadius = UiTheme.scaled(1.5f);
            if (active) {
                renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, UiTheme.SIDEBAR_ACTIVE, vpWidth, vpHeight);
                renderer.drawRoundedRect(x, y + edgeInset, x + edgeW, y + h - edgeInset, edgeRadius, UiTheme.ACCENT, vpWidth, vpHeight);
            } else {
                UiColor bg = UiColor.lerp(UiColor.TRANSPARENT, UiTheme.SIDEBAR_HOVER, hoverAnim.get());
                renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            }
            renderer.drawText(name, x + UiTheme.scaled(14f), y + h / 2f - UiTheme.scaled(5f), active ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED, TAB_LABEL_SCALE, vpWidth, vpHeight);
        }

        @Override
        public void onClick() { switchTab(name); }
    }

    private final class BackButton extends UiWidget {
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        BackButton() { super(SIDE_MARGIN, screenHeight - HEADER_H + UiTheme.scaled(20f), UiTheme.scaled(38f), UiTheme.scaled(38f)); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float hover = hoverAnim.get();
            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hover);
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            // "«" (chevron double, U+00AB) plutôt que "<" — voir UiModConfigScreen.BackButton pour le détail du choix.
            String arrow = "«";
            float scale = UiTheme.scaled(0.7f);
            float tw = renderer.textWidth(UiFont.BOLD, arrow, scale);
            float slide = hover * UiTheme.scaled(3f);
            renderer.drawText(UiFont.BOLD, arrow, x + (w - tw) / 2f - slide, y + h / 2f - UiTheme.scaled(7f), UiTheme.TEXT_PRIMARY, scale, vpWidth, vpHeight);
        }

        @Override
        public void onClick() { closeTo(lastScreen); }
    }
}
