package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.i18n.Lang;
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
import java.util.Map;

/**
 * Page de config d'un {@link ModuleGroup} — même carcasse que
 * {@link UiModConfigScreen} (bouton retour, carte autour de la sous-sidebar,
 * liste continue scrollable sur fond transparent) : UN EN-TÊTE DE SECTION PAR
 * {@link ModuleGroup.Tab} (pas par catégorie de champ d'un seul module),
 * chaque onglet empilant le toggle d'activation (déplacé ici puisqu'un module
 * groupé n'a plus sa propre carte pour le porter) + les réglages annotés
 * habituels (via {@link ConfigScreenBuilder}) de CHAQUE module qui lui est
 * rattaché — la plupart des onglets n'ont qu'un seul module, mais certains en
 * cumulent plusieurs d'apparentés (ex: "Culling face arrière" = joueur +
 * entités, "Distance de rendu" = tile entities + labels + particules — voir
 * ModuleRegistry) pour réduire le nombre d'onglets et la longueur de leurs
 * noms dans la sous-sidebar.
 *
 * REFONTE (même demande explicite que UiModConfigScreen, voir sa javadoc) :
 * fond transparent, carte autour de la sous-sidebar (plus du contenu), liste
 * continue avec en-tête de section par onglet — cliquer un onglet fait
 * défiler jusqu'à sa section (voir {@code UiScrollContainer#scrollToAnchor})
 * au lieu de changer de page.
 */
public class UiModGroupConfigScreen extends UiScreenBase {

    // Même valeur que UiModConfigScreen.CONFIG_OVERLAY — pas centralisée
    // (même choix que UiHudEditorScreen.EDITOR_OVERLAY, qui a sa propre
    // constante séparée) : un seul point d'usage chacun, une indirection
    // partagée n'apporterait rien.
    private static final UiColor CONFIG_OVERLAY = new UiColor(6, 6, 10, 70);

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
    /** Titre de module DANS le contenu (onglet à plusieurs modules, voir javadoc de classe) — agrandi (voir buildLayout, demande explicite "plus grand") par rapport à l'ancien sous-titre discret qu'il remplace. */
    private float TAB_LABEL_SCALE_BIG = 0.52f;

    private final Object lastScreen;
    private final ModuleGroup group;
    private LinkedHashMap<String, Float> anchors = new LinkedHashMap<>();
    private String activeTab;
    private UiScrollContainer scroll;
    private float sidebarX, sidebarY, sidebarW, sidebarH;
    private int lastLayoutWidth = -1, lastLayoutHeight = -1;
    private float lastUiScale = -1f;

    public UiModGroupConfigScreen(Object lastScreen, ModuleGroup group) {
        super(group.name);
        this.lastScreen = lastScreen;
        this.escapeTarget = lastScreen;
        this.group = group;
    }

    @Override
    protected UiColor overlayColor() {
        return CONFIG_OVERLAY;
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
        // Voir UiModConfigScreen#uiDraw pour le bug de z-order déjà corrigé
        // ici dès le départ : SidebarPanel est un widget ajouté EN PREMIER
        // dans "widgets" (voir buildLayout), jamais dessiné séparément ici.
        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            renderer.drawText(UiFont.BOLD, Lang.tr(group.name), SIDE_MARGIN + UiTheme.scaled(46f), screenHeight - UiTheme.scaled(44f),
                UiTheme.TEXT_PRIMARY, UiTheme.scaled(0.68f), screenWidth, screenHeight);
            updateActiveTab();
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
        // Agrandi (retour utilisateur : "c'est trop petit") — noms d'onglets
        // désormais raccourcis pour les tabs multi-modules (voir
        // ModuleRegistry/optimodule), plus besoin d'un scale aussi réduit
        // qu'avant pour éviter le débordement.
        TAB_LABEL_SCALE = UiTheme.scaled(0.46f);
        TAB_LABEL_SCALE_BIG = UiTheme.scaled(0.52f);

        float panelTop = screenHeight - HEADER_H;
        float panelBottom = UiTheme.scaled(20f);

        float contentX = SIDE_MARGIN + SUB_SIDEBAR_W + UiTheme.scaled(16f);
        float contentW = Math.min(CONTENT_MAX_W, screenWidth - contentX - SIDE_MARGIN);

        float contentPad = UiTheme.scaled(12f);
        // Réserve pour la barre de scroll — voir UiModConfigScreen (même bug,
        // même fix : le viewport garde toute la largeur, les LIGNES sont
        // plus étroites).
        float scrollbarReserve = UiTheme.scaled(20f);
        float rowX = contentX;
        float rowW = contentW - scrollbarReserve;
        scroll = new UiScrollContainer(rowX, panelBottom + contentPad, contentW, (panelTop - panelBottom) - contentPad * 2f);

        // ── Construit CHAQUE onglet (inchangé : toggle "Activé" + réglages
        // annotés de chaque module rattaché, cursor LOCAL par onglet) PUIS
        // enchaîne tous les onglets dans LA MÊME liste continue (voir
        // ConfigScreenBuilder.buildContinuous pour l'algorithme identique)
        // au lieu de pages séparées — demande explicite de l'utilisateur,
        // même traitement que UiModConfigScreen.
        List<UiWidget> combined = new ArrayList<>();
        anchors = new LinkedHashMap<>();
        float headerH = ConfigScreenBuilder.sectionHeaderHeight();
        float sectionGapBefore = ConfigScreenBuilder.sectionGapBeforeHeight();
        float headerToRowGap = ConfigScreenBuilder.headerToRowGapHeight();
        float globalCursor = 0f;
        boolean firstTab = true;

        for (ModuleGroup.Tab tab : group.tabs) {
            List<UiWidget> rows = new ArrayList<>();
            float cursor = 0f;
            // Sous-titre par module UNIQUEMENT utile quand l'onglet en cumule
            // plusieurs (voir javadoc de classe) — avec un seul module, le nom
            // de l'onglet EST déjà le nom du module, un sous-titre identique
            // juste en dessous serait redondant.
            boolean multi = tab.modules.size() > 1;
            // Onglet à un seul module : son toggle "Activé" part dans l'EN-TÊTE
            // de section lui-même (voir plus bas, sectionHeaderWithToggle) —
            // demande explicite : "enlève les [lignes 'Activé' séparées] pour
            // mettre juste le toggle a côté du titre". Capturé ici pour être
            // lu au moment de construire l'en-tête, plus bas.
            LauncherModule soloMember = (!multi && !tab.modules.isEmpty()) ? tab.modules.get(0) : null;

            for (LauncherModule member : tab.modules) {
                if (member == null) continue;

                if (multi) {
                    // Nom du module + toggle "Activé" SUR LA MÊME LIGNE (même
                    // demande explicite que ci-dessus, cas où l'onglet cumule
                    // plusieurs modules) — plus de ligne "Activé" séparée, le
                    // nom sert directement de titre pour ce bloc. Agrandi
                    // (0.4->0.52, TEXT_SECONDARY->TEXT_PRIMARY) : il joue
                    // maintenant le rôle d'un vrai titre, pas d'un sous-texte
                    // discret.
                    float rowY = cursor - ROW_H;
                    rows.add(new UiLabel(rowX, rowY + ROW_H / 2f - UiTheme.scaled(5f), Lang.tr(member.name), UiTheme.TEXT_PRIMARY, TAB_LABEL_SCALE_BIG));
                    rows.add(new UiToggle(rowX + rowW - UiTheme.scaled(44f) - UiTheme.scaled(10f), rowY + (ROW_H - UiTheme.scaled(24f)) / 2f, member.isEnabled(),
                        v -> { member.setEnabled(v); HudConfigStore.save(); }));
                    cursor = rowY - ROW_GAP;
                }
                // Sinon (onglet à un seul module) : aucune ligne "Activé" du
                // tout, son toggle vit dans l'en-tête (soloMember ci-dessus).

                // ConfigScreenBuilder positionne chaque catégorie en partant
                // d'un curseur à 0 (indépendant) — UiWidget expose x/y en
                // public mutable (même pattern que searchField.x/y dans
                // UiMainMenuScreen), donc on décale tout du "cursor" courant
                // pour enchaîner juste après le titre/toggle de CE module,
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
                // onglet — sans ça, le titre du module suivant collerait
                // directement à la dernière ligne de réglage du précédent.
                if (multi) cursor -= ROW_GAP;
            }

            if (rows.isEmpty() && soloMember == null) continue;

            if (!firstTab) globalCursor -= sectionGapBefore;
            firstTab = false;

            globalCursor -= headerH;
            float headerY = globalCursor;
            UiWidget header = soloMember != null
                ? ConfigScreenBuilder.sectionHeaderWithToggle(rowX, headerY, rowW, headerH, tab.label,
                    soloMember.isEnabled(), v -> { soloMember.setEnabled(v); HudConfigStore.save(); })
                : ConfigScreenBuilder.sectionHeader(rowX, headerY, rowW, headerH, tab.label);
            combined.add(header);
            anchors.put(tab.label, headerY + headerH);

            globalCursor -= headerToRowGap;
            float shift = globalCursor;
            for (UiWidget w : rows) {
                w.y += shift;
                combined.add(w);
            }
            globalCursor = shift + cursor;
        }

        for (UiWidget w : combined) scroll.add(w);

        if (activeTab == null || !anchors.containsKey(activeTab)) {
            activeTab = anchors.isEmpty() ? null : anchors.keySet().iterator().next();
        }

        // Carte à toute la hauteur disponible (voir UiModConfigScreen — choix
        // préféré de l'utilisateur, pas un ajustement au nombre d'onglets).
        this.sidebarX = SIDE_MARGIN;
        this.sidebarW = SUB_SIDEBAR_W;
        this.sidebarY = panelBottom;
        this.sidebarH = panelTop - panelBottom;

        widgets.clear();
        // EN PREMIER (voir uiDraw/SidebarPanel — z-order + non-cliquable, même
        // bug déjà rencontré et corrigé sur UiModConfigScreen).
        widgets.add(new SidebarPanel());
        widgets.add(new BackButton());

        // Agrandi/espacé (retour utilisateur : "c'est trop petit... pas assez
        // de marge sur les titres") — tabH 34->38, tabGap 38->44.
        float tabH = UiTheme.scaled(38f), tabGap = UiTheme.scaled(44f), tabTopGap = UiTheme.scaled(44f);
        int i = 0;
        for (String tab : anchors.keySet()) {
            widgets.add(new TabItem(sidebarX + UiTheme.scaled(10f), panelTop - tabTopGap - i * tabGap,
                sidebarW - UiTheme.scaled(20f), tabH, tab));
            i++;
        }
    }

    /** Détermine quel onglet est "actif" — calcul en POURCENTAGE, voir UiModConfigScreen#updateActiveCategory pour le détail complet (identique ici). */
    private void updateActiveTab() {
        if (scroll == null || anchors.isEmpty()) return;
        float progress = scroll.scrollProgress();
        String best = null;
        for (Map.Entry<String, Float> entry : anchors.entrySet()) {
            if (scroll.baseYFraction(entry.getValue()) <= progress + 0.001f) best = entry.getKey();
        }
        activeTab = best != null ? best : anchors.keySet().iterator().next();
    }

    /** Fond de la sous-sidebar — voir UiModConfigScreen.SidebarPanel pour le détail du bug de z-order/clic déjà corrigé (contains() toujours faux, purement décoratif). */
    private final class SidebarPanel extends UiWidget {
        SidebarPanel() { super(sidebarX, sidebarY, sidebarW, sidebarH); }

        @Override
        public boolean contains(double mx, double my) { return false; }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            UiPanel.draw(renderer, sidebarX, sidebarY, sidebarW, sidebarH, null, vpWidth, vpHeight);
        }
    }

    private final class TabItem extends UiWidget {
        private final String name;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);
        /** Transition douce actif/inactif — voir UiModConfigScreen.CategoryTab#activeAnim (même demande utilisateur, même traitement). */
        private final UiAnimatedFloat activeAnim = new UiAnimatedFloat(0f, 12f);

        TabItem(float x, float y, float w, float h, String name) {
            super(x, y, w, h);
            this.name = name;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean active = name.equals(activeTab);
            activeAnim.setTarget(active ? 1f : 0f);
            float activeT = activeAnim.get();
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float hoverT = hoverAnim.get();

            float edgeW = UiTheme.scaled(3f), edgeInset = UiTheme.scaled(3f), edgeRadius = UiTheme.scaled(1.5f);
            UiColor inactiveBg = UiColor.lerp(UiColor.TRANSPARENT, UiTheme.SIDEBAR_HOVER, hoverT);
            UiColor bg = UiColor.lerp(inactiveBg, UiTheme.SIDEBAR_ACTIVE, activeT);
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            if (activeT > 0.01f) {
                renderer.drawRoundedRect(x, y + edgeInset, x + edgeW, y + h - edgeInset, edgeRadius,
                    UiTheme.ACCENT.multiplyAlpha(activeT), vpWidth, vpHeight);
            }
            UiColor textColor = UiColor.lerp(UiTheme.TEXT_MUTED, UiTheme.TEXT_PRIMARY, activeT);
            // Marge gauche/texte agrandis (retour utilisateur) — 14->18, scale via TAB_LABEL_SCALE (0.4->0.46, voir buildLayout).
            // "name" reste le texte source (clé de anchors/activeTab, voir
            // onClick ci-dessous) — traduit UNIQUEMENT ici, à l'affichage.
            renderer.drawText(Lang.tr(name), x + UiTheme.scaled(18f), y + h / 2f - UiTheme.scaled(6f), textColor, TAB_LABEL_SCALE, vpWidth, vpHeight);
        }

        /** Ne bascule plus de page (liste continue désormais, voir javadoc de classe) — fait défiler jusqu'à la section. */
        @Override
        public void onClick() {
            Float anchor = anchors.get(name);
            if (anchor != null && scroll != null) scroll.scrollToAnchor(anchor);
        }
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
