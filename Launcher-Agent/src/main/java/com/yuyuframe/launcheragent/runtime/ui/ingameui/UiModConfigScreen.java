package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.ui.ConfigScreenBuilder;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiPanel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Page de config d'un module — bouton retour + titre fixes en haut, carte
 * autour de la sous-sidebar de catégories à gauche, contenu à DROITE posé
 * directement sur le fond transparent de l'écran (voir {@link #overlayColor}).
 *
 * REFONTE (demande explicite de l'utilisateur, deux captures fournies en
 * référence : notre propre écran + les réglages vidéo façon Sodium) :
 *  - Fond d'écran nettement plus transparent (voir overlayColor ci-dessous)
 *    pour voir ce qui se passe en jeu en bougeant un curseur avec effet
 *    visuel immédiat (ex: WorldTimeModule).
 *  - La carte entoure désormais la SOUS-SIDEBAR (catégories), plus le
 *    contenu — inversion délibérée par rapport à l'ancien écran.
 *  - Les réglages ne sont plus répartis en PAGES séparées par catégorie :
 *    UNE SEULE liste scrollable continue (voir {@link
 *    ConfigScreenBuilder#buildContinuous}), avec un en-tête de section bold
 *    par catégorie — cliquer une catégorie dans la sous-sidebar ne change
 *    plus le contenu affiché, ça fait juste DÉFILER jusqu'à sa section (voir
 *    {@code UiScrollContainer#scrollToAnchor}), façon Sodium/Iris.
 *
 * Cet écran ne connaît toujours AUCUN module en particulier : les lignes de
 * réglage sont générées par réflexion à partir des champs annotés du module
 * (voir {@link ConfigScreenBuilder}) — un futur module se contente de
 * déclarer ses champs, jamais de code d'écran.
 *
 * Les widgets de réglage sont reconstruits à chaque {@link #buildLayout()}
 * (redimensionnement) directement depuis les VALEURS ACTUELLES des champs du
 * module (pas un modèle séparé) : rien n'est perdu, le module EST la source
 * de vérité de ses propres réglages.
 */
public class UiModConfigScreen extends UiScreenBase {

    // Fond quasi-transparent (voir UiHudEditorScreen.EDITOR_OVERLAY, même
    // philosophie déjà établie pour l'éditeur HUD) — le monde (temps,
    // météo...) reste visible en direct derrière pendant qu'on ajuste un
    // réglage qui l'affecte, demande explicite de l'utilisateur. Un peu plus
    // opaque que l'éditeur HUD (70 vs 60) : cet écran a BEAUCOUP plus de
    // texte dense (lignes de réglage) qu'une poignée de boîtes HUD, un peu
    // plus de voile aide la lisibilité sans redevenir un mur opaque.
    private static final UiColor CONFIG_OVERLAY = new UiColor(6, 6, 10, 70);

    // Non static/final — recalculées à chaque buildLayout() depuis
    // UiTheme.UI_SCALE (voir GlobalUiSettings, réglage "Taille de
    // l'interface"), même motif que UiMainMenuScreen/UiModGroupConfigScreen.
    private float HEADER_H = 72f;
    private float SIDE_MARGIN = 48f;
    private float SUB_SIDEBAR_W = 180f;
    private float CONTENT_MAX_W = 620f;

    private final Object lastScreen;
    private final LauncherModule module;
    private LinkedHashMap<String, Float> anchors = new LinkedHashMap<>();
    private String activeCategory;
    private UiScrollContainer scroll;
    private float sidebarX, sidebarY, sidebarW, sidebarH;
    private int lastLayoutWidth = -1, lastLayoutHeight = -1;
    private float lastUiScale = -1f;

    public UiModConfigScreen(Object lastScreen, LauncherModule module) {
        super(module.name);
        this.lastScreen = lastScreen;
        this.escapeTarget = lastScreen;
        this.module = module;
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
        // BUG TROUVÉ (retour utilisateur : "la sidebar y'a rien qui va, le
        // z-order est pas bon") : UiPanel.draw() était appelé ICI, APRÈS
        // super.uiDraw() — qui a déjà dessiné les CategoryTab (dans
        // "widgets") À CE moment. Le panneau, positionné exactement sur la
        // même zone que ces onglets, se retrouvait donc dessiné PAR-DESSUS
        // eux (dernier dessiné = au-dessus), les recouvrant entièrement.
        // Fix : le panneau est maintenant un widget à part entière
        // (SidebarPanel), ajouté EN PREMIER dans "widgets" (voir
        // buildLayout) — l'ordre d'insertion pilote le z-order de
        // super.uiDraw(), donc il se dessine AVANT (donc EN DESSOUS) les
        // onglets, plus besoin de l'appeler séparément ici.
        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            renderer.drawText(UiFont.BOLD, module.name, SIDE_MARGIN + UiTheme.scaled(46f), screenHeight - UiTheme.scaled(44f),
                UiTheme.TEXT_PRIMARY, UiTheme.scaled(0.68f), screenWidth, screenHeight);
            updateActiveCategory();
            if (scroll != null) scroll.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            // Ré-appliqué ici (déjà dessiné une fois dans super.uiDraw()) — voir
            // sa javadoc : sans ça, le titre/panneau ci-dessus apparaîtrait
            // d'un coup sec, jamais couvert par le voile.
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
        SUB_SIDEBAR_W = UiTheme.scaled(180f);
        CONTENT_MAX_W = UiTheme.scaled(620f);

        float panelTop = screenHeight - HEADER_H;
        float panelBottom = UiTheme.scaled(20f);

        float contentX = SIDE_MARGIN + SUB_SIDEBAR_W + UiTheme.scaled(16f);
        float contentW = Math.min(CONTENT_MAX_W, screenWidth - contentX - SIDE_MARGIN);

        // Petite marge haut/bas conservée même sans carte englobante (voir
        // javadoc de classe) — sinon la première/dernière ligne toucherait
        // pile le bord du viewport de scroll.
        float contentPad = UiTheme.scaled(12f);
        // Réserve pour la barre de scroll (retour utilisateur : "la barre de
        // scroll chevauche les paramètres") — le viewport du scroll garde
        // TOUTE la largeur de contenu (la barre se dessine à son bord droit
        // réel), mais les LIGNES elles-mêmes (rowW, passé à
        // buildContinuous) sont plus étroites : sans cette réserve, les
        // contrôles alignés à droite (slider/toggle/color picker, insérés à
        // 10px du bord de leur ligne) tombaient exactement là où la barre de
        // scroll se dessine (~10px du bord du viewport) — même zone,
        // chevauchement garanti.
        float scrollbarReserve = UiTheme.scaled(20f);
        float rowX = contentX;
        float rowW = contentW - scrollbarReserve;
        scroll = new UiScrollContainer(rowX, panelBottom + contentPad, contentW, (panelTop - panelBottom) - contentPad * 2f);

        ConfigScreenBuilder.Result result = ConfigScreenBuilder.buildContinuous(module, rowX, rowW);
        anchors = result.anchors;
        for (UiWidget w : result.rows) scroll.add(w);

        if (activeCategory == null || !anchors.containsKey(activeCategory)) {
            activeCategory = anchors.isEmpty() ? null : anchors.keySet().iterator().next();
        }

        // Carte revenue à toute la hauteur disponible (retour utilisateur :
        // "c'était mieux avant" — l'ajustement au nombre d'onglets, tenté
        // dans une itération précédente, est annulé). Le vide sous un seul
        // onglet reste visible mais c'est le choix préféré de l'utilisateur.
        // tabTopGap remonté (24->44) — retour utilisateur : le bouton
        // poussait au-dessus du bord haut de la carte, pas la carte à
        // repositionner/redimensionner (déjà revenue à sa taille d'origine
        // juste avant), le bouton à descendre.
        // tabH/tabGap agrandis (retour utilisateur : "c'est trop petit... pas
        // assez de marge sur les titres") — même ajustement que
        // UiModGroupConfigScreen, pour rester cohérent visuellement entre les
        // deux écrans.
        float tabH = UiTheme.scaled(38f), tabGap = UiTheme.scaled(44f), tabTopGap = UiTheme.scaled(44f);
        this.sidebarX = SIDE_MARGIN;
        this.sidebarW = SUB_SIDEBAR_W;
        this.sidebarY = panelBottom;
        this.sidebarH = panelTop - panelBottom;

        widgets.clear();
        // EN PREMIER (voir uiDraw ci-dessus pour le bug de z-order corrigé) —
        // doit se dessiner AVANT les CategoryTab ajoutés plus bas.
        widgets.add(new SidebarPanel());
        widgets.add(new BackButton());

        int i = 0;
        for (String category : anchors.keySet()) {
            widgets.add(new CategoryTab(sidebarX + UiTheme.scaled(10f), panelTop - tabTopGap - i * tabGap,
                sidebarW - UiTheme.scaled(20f), tabH, category));
            i++;
        }
    }

    /**
     * Détermine quelle catégorie est "active" (surbrillance dans la sous-
     * sidebar) — recalculé CHAQUE frame (pas juste au clic) : un défilement
     * libre à la molette doit aussi la mettre à jour.
     *
     * Reconstruit en POURCENTAGE (retour utilisateur : "il faut le calculer
     * autrement... quand on a scrollé tout en bas on est au dernier module,
     * calculer quel pourcentage de la page prend chaque module et appliquer
     * ce pourcentage au scroll") — l'ancienne version comparait un seuil de
     * PIXELS fixe (bord du viewport ± un décalage arbitraire), qui ne
     * garantissait pas mathématiquement d'atteindre la DERNIÈRE catégorie à
     * scroll max (dépendait du nombre/de la hauteur des sections). Ici :
     * {@code scroll.scrollProgress()} (0..1, 0=haut, 1=bas) comparé à
     * {@code scroll.baseYFraction(ancre)} (part du contenu TOTAL avant cette
     * ancre) — la dernière catégorie dont la fraction est déjà dépassée est
     * l'active. Par construction, scrollProgress()==1.0 exactement à scroll
     * max, donc la dernière catégorie devient TOUJOURS active en bas de page.
     */
    private void updateActiveCategory() {
        if (scroll == null || anchors.isEmpty()) return;
        float progress = scroll.scrollProgress();
        String best = null;
        for (Map.Entry<String, Float> entry : anchors.entrySet()) {
            if (scroll.baseYFraction(entry.getValue()) <= progress + 0.001f) best = entry.getKey();
        }
        activeCategory = best != null ? best : anchors.keySet().iterator().next();
    }

    /** Fond de la sous-sidebar — widget dédié (voir uiDraw pour le pourquoi) plutôt qu'un appel direct à UiPanel.draw() : purement décoratif, jamais cliqué (contains() par défaut suffit, jamais interrogé puisqu'aucun onClick()). */
    private final class SidebarPanel extends UiWidget {
        SidebarPanel() { super(sidebarX, sidebarY, sidebarW, sidebarH); }

        /**
         * BUG TROUVÉ (retour utilisateur : "les boutons de catégorie ne
         * marchent pas non plus") — MÊME cause que RowBackground
         * (ConfigScreenBuilder) et l'exclusion de toggle de ModCard
         * (UiMainMenuScreen) : purement décoratif mais ajouté EN PREMIER
         * dans "widgets" (voir buildLayout), avec le {@code contains()} PAR
         * DÉFAUT de UiWidget qui couvre TOUTE la sous-sidebar — exactement
         * la même zone que les CategoryTab dessinés par-dessus. Le dispatch
         * de clic ({@code UiScreenBase.dispatchClick}) prend le PREMIER
         * widget de la liste dont contains() matche : ce panneau
         * interceptait donc systématiquement le clic à la place de l'onglet
         * cliqué. Ne doit jamais être cliquable.
         */
        @Override
        public boolean contains(double mx, double my) { return false; }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            UiPanel.draw(renderer, sidebarX, sidebarY, sidebarW, sidebarH, null, vpWidth, vpHeight);
        }
    }

    private final class CategoryTab extends UiWidget {
        private final String name;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);
        // Transition douce de l'état actif (demande explicite : "quand on
        // passe à la catégorie suivante... ajoute une belle animation") —
        // AVANT, "active" changeait de façon binaire d'une frame à l'autre
        // (fond/liseré d'accent/couleur du texte apparaissaient/
        // disparaissaient d'un coup) — updateActiveCategory() tourne CHAQUE
        // frame (pas juste au clic), donc un simple survol continu qui
        // franchit une frontière de section rejouait déjà ce changement en
        // continu, mais sans aucun lissage visuel avant cet ajout.
        private final UiAnimatedFloat activeAnim = new UiAnimatedFloat(0f, 12f);

        CategoryTab(float x, float y, float w, float h, String name) {
            super(x, y, w, h);
            this.name = name;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean active = name.equals(activeCategory);
            activeAnim.setTarget(active ? 1f : 0f);
            float activeT = activeAnim.get();
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float hoverT = hoverAnim.get();

            float edgeW = UiTheme.scaled(3f), edgeInset = UiTheme.scaled(3f), edgeRadius = UiTheme.scaled(1.5f);
            // Fond : dégradé continu entre le survol (inactif) et l'actif —
            // jamais un simple if/else qui saute d'un état à l'autre.
            UiColor inactiveBg = UiColor.lerp(UiColor.TRANSPARENT, UiTheme.SIDEBAR_HOVER, hoverT);
            UiColor bg = UiColor.lerp(inactiveBg, UiTheme.SIDEBAR_ACTIVE, activeT);
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            // Liseré d'accent : fondu (alpha) plutôt qu'apparition/disparition nette.
            if (activeT > 0.01f) {
                renderer.drawRoundedRect(x, y + edgeInset, x + edgeW, y + h - edgeInset, edgeRadius,
                    UiTheme.ACCENT.multiplyAlpha(activeT), vpWidth, vpHeight);
            }
            UiColor textColor = UiColor.lerp(UiTheme.TEXT_MUTED, UiTheme.TEXT_PRIMARY, activeT);
            // Agrandi/marge augmentée (retour utilisateur : "c'est trop petit... pas assez de marge") — 14px->18px, 0.48->0.5.
            renderer.drawText(name, x + UiTheme.scaled(18f), y + h / 2f - UiTheme.scaled(6f), textColor, UiTheme.scaled(0.5f), vpWidth, vpHeight);
        }

        /** Ne bascule plus l'affichage (liste continue désormais, voir javadoc de classe) — fait défiler jusqu'à la section, la sous-sidebar suit ensuite toute seule au fil du scroll (voir updateActiveCategory). */
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
            // "«" (chevron double, U+00AB, présent dans l'atlas Latin-1 de
            // UiFont) plutôt que "<" — un simple signe "inférieur à" détourné
            // en flèche, jugé "moche" par l'utilisateur. Police BOLD (plus
            // épaisse, un vrai pictogramme plutôt qu'un caractère de
            // ponctuation) + léger glissement vers la gauche au survol
            // (affordance "on te tire vers l'arrière").
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
