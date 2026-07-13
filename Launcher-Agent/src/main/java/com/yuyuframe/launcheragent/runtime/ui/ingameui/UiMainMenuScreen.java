package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.runtime.ui.HudConfigStore;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleGroup;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiEasing;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiStagger;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiTransition;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTextField;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiToggle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Écran d'accueil du moteur config custom — équivalent de OneConfigGui.create() :
 * sidebar de navigation à gauche, barre de recherche + grille de cartes mods
 * à droite. Chaque carte a son propre UiToggle (activer/désactiver,
 * enregistré AVANT le widget "corps de carte" dans {@code widgets} pour que
 * le clic sur le toggle gagne le test de collision face au clic "ouvrir la
 * config").
 *
 * Layout entièrement reconstruit (rebuildAll) au redimensionnement de fenêtre
 * ET à chaque frappe dans la recherche — sans jamais perdre d'état, car :
 * (a) l'état "activé/désactivé" d'un mod vit dans le {@link LauncherModule}
 * lui-même (externe aux widgets, jamais recréé), (b) {@code searchField} est
 * créé UNE FOIS puis seulement repositionné/ré-ajouté (même instance, donc
 * même texte/focus).
 *
 * La grille vient de {@link ModuleRegistry#all()} — cet écran ne connaît plus
 * AUCUN mod en particulier, voir docs/LauncherAgent/index.md.
 */
public class UiMainMenuScreen extends UiScreenBase {

    // NON static/final (contrairement à l'habitude "constante d'écran") —
    // recalculées à chaque rebuildAll() depuis UiTheme.UI_SCALE (voir
    // GlobalUiSettings, réglage "Taille de l'interface") : un champ figé au
    // chargement de la classe ne pourrait jamais refléter un changement
    // d'échelle fait APRÈS coup depuis Paramètres.
    private float SIDEBAR_W = 190f;
    private float MARGIN = 24f;
    private float CARD_GAP = 16f;
    private float CARD_H = 76f;
    private float SEARCH_H = 36f;

    private final Object lastScreen;
    private UiTextField searchField;
    /** Grille de cartes mods — SÉPARÉE de {@code widgets} (voir UiScrollContainer/ConfigScreenBuilder pour le même motif) : permet de scroller quand le nombre de mods dépasse la hauteur visible, ce que {@code widgets} seul ne permet pas (pas de clipping/offset). */
    private UiScrollContainer modScroll;
    private int lastLayoutWidth = -1, lastLayoutHeight = -1;
    private float lastUiScale = -1f;

    public UiMainMenuScreen(Object lastScreen) {
        super("YuyuFrame");
        this.lastScreen = lastScreen;
        this.escapeTarget = lastScreen;
    }

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        // Molette/drag de la barre de défilement + dispatch clic/continu des
        // cartes/toggles qu'elle contient — scroll.pollInput() gère tout ça
        // (voir UiModConfigScreen, même motif) puisque ces widgets ne sont
        // PLUS dans `widgets` (dispatché par super.uiPollInput() ci-dessus).
        if (modScroll != null) modScroll.pollInput(input);
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (screenWidth > 0 && screenHeight > 0
                && (screenWidth != lastLayoutWidth || screenHeight != lastLayoutHeight || UiTheme.UI_SCALE != lastUiScale)) {
            rebuildAll();
            lastLayoutWidth = screenWidth;
            lastLayoutHeight = screenHeight;
            lastUiScale = UiTheme.UI_SCALE;
        }
        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            if (modScroll != null) modScroll.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            // Titres par-dessus TOUT (widgets inclus) — le fond de la sidebar,
            // lui, est un widget ordinaire ajouté EN PREMIER dans rebuildAll()
            // (voir SidebarBackground) pour être dessiné AVANT les items de
            // nav par super.uiDraw(), sinon il les recouvrirait entièrement
            // (bug vécu : le bouton "Modifier le HUD" — et "Accueil"/
            // "Parametres" avant lui, juste jamais remarqué — invisibles
            // derrière ce fond dessiné après coup).
            //
            // Halo accent derrière le logo — forme "pilule" (large et basse,
            // rayon = moitié de la hauteur), PAS un cercle (voir tentatives
            // précédentes) : un cercle ne convient qu'à un point lumineux
            // ponctuel, pas à un TITRE (forme large et basse). Position/taille
            // calculées à partir des VRAIES métriques du texte (largeur réelle
            // + ascent/descent de la police) au lieu de constantes ajustées à
            // l'œil — 3 essais de coordonnées à la main n'ont toujours pas
            // fait mouche (retours utilisateur successifs), la seule façon
            // fiable de tomber pile sur le texte est de mesurer sa vraie boîte.
            float titleScale = UiTheme.scaled(0.68f);
            float titleW = renderer.textWidth(UiFont.BOLD, "YuyuFrame", titleScale);
            // Centré horizontalement dans la largeur de la SIDEBAR (pas
            // plaqué à MARGIN comme avant) — retour utilisateur persistant
            // "le titre n'est pas centré" : un simple alignement à gauche
            // dans cette colonne verticale étroite laissait un vide visible à
            // droite du texte, alors qu'un logo/wordmark en haut d'une nav se
            // veut normalement centré dans SA colonne, pas dans l'écran entier.
            float titleX = Math.max(MARGIN / 2f, (SIDEBAR_W - titleW) / 2f);
            float titleBaseline = screenHeight - UiTheme.scaled(42f);
            float cs = titleScale * UiFont.SIZE_CORRECTION;
            float titleTop = titleBaseline + UiFont.BOLD.ascent * cs;
            float titleBottom = titleBaseline - UiFont.BOLD.descent * cs;

            float glowPadX = UiTheme.scaled(14f), glowPadY = UiTheme.scaled(8f);
            float glowX1 = titleX - glowPadX, glowX2 = titleX + titleW + glowPadX;
            float glowBlur = UiTheme.scaled(14f);
            // Garde-fou : quelle que soit la métrique réelle de la police,
            // jamais moins de marge que le flou avant le bord haut de l'écran
            // (voir bug précédent — un flou qui n'a pas la place de retomber à
            // 0 avant un bord d'écran laisse une coupure nette visible).
            // RÉDUIT LE PAD DE FAÇON SYMÉTRIQUE (haut ET bas identiquement)
            // plutôt que de clamper UNIQUEMENT le haut (glowY2) comme avant :
            // ce clamp asymétrique rétrécissait le pad du haut sans toucher
            // celui du bas dès que le titre (donc son ascendant) approchait le
            // bord d'écran — ce qui décale visuellement la pilule vers le bas
            // par rapport au texte, symptôme "titre pas centré" remonté par
            // l'utilisateur. Se déclenche plus souvent depuis le décalage des
            // paliers d'échelle (v290, défaut 1f->1.18f) : police plus grande
            // -> ascendant plus grand -> titleTop plus proche du bord.
            float maxTopPad = (screenHeight - glowBlur - UiTheme.scaled(6f)) - titleTop;
            float actualPadY = Math.max(0f, Math.min(glowPadY, maxTopPad));
            float glowY1 = titleBottom - actualPadY, glowY2 = titleTop + actualPadY;
            float glowHalfH = (glowY2 - glowY1) / 2f;
            renderer.drawShadow(glowX1, glowY1, glowX2, glowY2, glowHalfH, glowBlur, 0f,
                UiTheme.ACCENT.multiplyAlpha(0.28f), screenWidth, screenHeight);
            renderer.drawText(UiFont.BOLD, "YuyuFrame", titleX, titleBaseline, UiTheme.TEXT_PRIMARY, titleScale, screenWidth, screenHeight);
            renderer.drawText("Mods installes", SIDEBAR_W + MARGIN, screenHeight - UiTheme.scaled(40f),
                UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.42f), screenWidth, screenHeight);

            // (Halo d'ambiance décoratif dans le coin haut-droit RETIRÉ — se
            // superposait telle une tache/cercle non identifiable au-dessus
            // des cartes de mods, sans lien visuel avec un élément précis,
            // remonté comme un artefact déroutant plutôt qu'un effet de
            // profondeur voulu.)

            drawRevealVeil(renderer);
        } catch (Throwable ignored) {}
    }

    private void rebuildAll() {
        // Recalculées à CHAQUE reconstruction (pas juste au premier appel) —
        // seule façon de refléter un changement de "Taille de l'interface"
        // fait depuis Paramètres sans redémarrer l'agent, voir uiDraw()
        // (rebuildAll() redéclenché dès que UiTheme.UI_SCALE change).
        SIDEBAR_W = UiTheme.scaled(190f);
        MARGIN = UiTheme.scaled(24f);
        CARD_GAP = UiTheme.scaled(16f);
        CARD_H = UiTheme.scaled(76f);
        SEARCH_H = UiTheme.scaled(36f);

        widgets.clear();

        widgets.add(new SidebarBackground());
        // Repoussés plus bas qu'avant (84->104, 114->134) — laisse plus de
        // place réelle au logo/son halo juste au-dessus (voir uiDraw()) au
        // lieu de les coller l'un à l'autre.
        widgets.add(new SidebarItem(MARGIN, screenHeight - UiTheme.scaled(104f), SIDEBAR_W - MARGIN * 2, "Accueil", true, null));
        widgets.add(new SidebarItem(MARGIN, screenHeight - UiTheme.scaled(134f), SIDEBAR_W - MARGIN * 2, "Parametres", false,
            () -> closeTo(new UiModConfigScreen(UiMainMenuScreen.this, GlobalUiSettings.INSTANCE))));
        // Épinglé en bas de la sidebar (pas empilé sous les items du haut) —
        // même position quel que soit le nombre d'items ajoutés au-dessus.
        widgets.add(new SidebarItem(MARGIN, MARGIN, SIDEBAR_W - MARGIN * 2, "Modifier le HUD", false,
            () -> closeTo(new UiHudEditorScreen(UiMainMenuScreen.this))));
        float closeSize = UiTheme.scaled(28f), closeMargin = UiTheme.scaled(24f);
        widgets.add(new CloseButton(screenWidth - closeMargin - closeSize, screenHeight - closeMargin - closeSize, closeSize));

        float contentX = SIDEBAR_W + MARGIN;
        float contentW = screenWidth - contentX - MARGIN;

        if (searchField == null) {
            // Recréé la grille (pas tout l'écran) à chaque frappe — même
            // instance de champ conservée, voir javadoc de la classe.
            searchField = new UiTextField(0, 0, 0, 0, "Rechercher un mod...", v -> rebuildAll());
        }
        searchField.x = contentX;
        searchField.y = screenHeight - UiTheme.scaled(64f) - SEARCH_H;
        searchField.w = Math.min(UiTheme.scaled(380f), contentW);
        searchField.h = SEARCH_H;
        widgets.add(searchField);

        // Chaque entrée est SOIT un LauncherModule (carte + toggle propre,
        // comme avant), SOIT un ModuleGroup (carte seule, pas de toggle —
        // elle ouvre un écran à onglets où CHAQUE module membre a son propre
        // toggle, voir UiModGroupConfigScreen). Les groupes d'abord, puis les
        // modules non groupés (ModuleRegistry.ungrouped() exclut déjà tout
        // module membre d'un groupe — sinon il apparaîtrait deux fois).
        String filter = searchField.text().trim().toLowerCase(Locale.ROOT);
        List<Object> filtered = new ArrayList<>();
        // Carte "action" — ouvre un écran au clic, ni toggle ni ModuleRegistry
        // (pas un effet continu à activer/désactiver, juste un outil ponctuel,
        // voir ActionCard). Seul autre précédent d'une action hors toggle :
        // "Modifier le HUD" dans la sidebar, câblé en dur de la même façon.
        // Ouvre ModrinthResourcePackScreen (notre propre pipeline graphique,
        // version-générique) — PAS l'ancien screen.ResourcePackSearchScreen
        // (compile contre les stubs vanilla + ScreenHelper, cassé sur 1.8.9,
        // voir la javadoc de ModrinthResourcePackScreen pour le détail).
        ActionCard resourcePacks = new ActionCard("Resource Packs (Modrinth)", "Rechercher et installer un resource pack",
            () -> closeTo(new com.yuyuframe.launcheragent.runtime.module.ModrinthResourcePackScreen(UiMainMenuScreen.this)));
        if (filter.isEmpty() || resourcePacks.name.toLowerCase(Locale.ROOT).contains(filter)) filtered.add(resourcePacks);
        // Même gating que l'ancien bouton "Shaders..." de GameMenuScreenMixin
        // (supprimé) : n'affiche la carte que si un mod de shaders (Iris/
        // OptiFine via Iris) est présent — installer un shader pack sans
        // loader de shaders ne servirait à rien.
        if (com.yuyuframe.launcheragent.runtime.fabric.ShaderLoaderDetector.isPresent(getClass().getClassLoader())) {
            ActionCard shaderPacks = new ActionCard("Shaders (Modrinth)", "Rechercher et installer un shader pack",
                () -> closeTo(new com.yuyuframe.launcheragent.runtime.module.ModrinthShaderPackScreen(UiMainMenuScreen.this)));
            if (filter.isEmpty() || shaderPacks.name.toLowerCase(Locale.ROOT).contains(filter)) filtered.add(shaderPacks);
        }
        for (ModuleGroup g : ModuleRegistry.groups()) {
            if (filter.isEmpty() || g.name.toLowerCase(Locale.ROOT).contains(filter)) filtered.add(g);
        }
        for (LauncherModule m : ModuleRegistry.ungrouped()) {
            if (filter.isEmpty() || m.name.toLowerCase(Locale.ROOT).contains(filter)) filtered.add(m);
        }

        float cardW = (contentW - CARD_GAP) / 2f;
        // Grille positionnée dans un repère LOCAL arbitraire (contrairement à
        // avant, où "top" dérivait de searchField.y, un repère ÉCRAN absolu) —
        // UiScrollContainer se charge lui-même de replacer ce contenu dans le
        // viewport réel via un offset recalculé chaque frame (voir sa javadoc
        // et ConfigScreenBuilder pour le même motif) : peu importe l'origine
        // choisie ici, seules les positions RELATIVES entre cartes comptent.
        float top = 0f;

        float viewportBottom = MARGIN;
        float viewportTop = searchField.y - UiTheme.scaled(24f);
        modScroll = new UiScrollContainer(contentX, viewportBottom, contentW, Math.max(1f, viewportTop - viewportBottom));

        for (int i = 0; i < filtered.size(); i++) {
            Object entry = filtered.get(i);
            int col = i % 2, row = i / 2;
            float cx = contentX + col * (cardW + CARD_GAP);
            float cy = top - row * (CARD_H + CARD_GAP) - CARD_H;

            // Délai croissant par index (voir UiStagger) — les cartes
            // apparaissent en cascade plutôt que toutes d'un coup, à chaque
            // rebuildAll() (resize ET chaque frappe dans la recherche, voir
            // javadoc de classe) : nouvelles instances de ModCard à chaque
            // fois, donc l'animation d'entrée rejoue naturellement à chaque
            // reconstruction — pas besoin de la déclencher "à la main".
            float enterDelay = UiStagger.delayFor(i, 0.035f, 0.3f);

            if (entry instanceof ModuleGroup) {
                ModuleGroup group = (ModuleGroup) entry;
                modScroll.add(new ModCard(cx, cy, cardW, group.name, group.description, enterDelay,
                    () -> closeTo(new UiModGroupConfigScreen(UiMainMenuScreen.this, group))));
            } else if (entry instanceof ActionCard) {
                ActionCard action = (ActionCard) entry;
                modScroll.add(new ModCard(cx, cy, cardW, action.name, action.description, enterDelay, action.action));
            } else {
                LauncherModule mod = (LauncherModule) entry;
                // Carte D'ABORD (dessinée en dessous), toggle ENSUITE (dessiné
                // PAR-DESSUS, sinon le fond plein de la carte le recouvrait
                // entièrement — visible nulle part bien que toujours cliquable
                // en dessous). ModCard.contains() exclut explicitement la zone du
                // toggle pour que le clic dessus continue de basculer le toggle
                // plutôt que d'ouvrir la config du mod — mêmes méthodes
                // toggleW()/toggleH()/toggleGapX()/toggleGapY() qu'utilisées
                // ci-dessous pour la zone d'exclusion (avant ce correctif, la
                // zone d'exclusion (34x18, voir ModCard.contains) ne
                // correspondait PAS à la taille réelle du widget UiToggle
                // (44x24) : un clic sur la tranche du toggle hors de cette
                // zone trop étroite ouvrait la config du mod au lieu de
                // basculer le toggle).
                modScroll.add(new ModCard(cx, cy, cardW, mod.name, mod.description, enterDelay,
                    () -> closeTo(new UiModConfigScreen(UiMainMenuScreen.this, mod))));
                float togX = cx + cardW - toggleW() - toggleGapX(), togY = cy + CARD_H - toggleH() - toggleGapY();
                modScroll.add(new UiToggle(togX, togY, mod.isEnabled(),
                    v -> { mod.setEnabled(v); HudConfigStore.save(); }));
            }
        }
    }

    /**
     * Dimensions/marges du toggle sur une ModCard — DOIVENT correspondre à la
     * taille réelle du widget {@link UiToggle} (44x24, voir son constructeur)
     * pour que la zone d'exclusion de clic dans {@link ModCard#contains}
     * corresponde exactement à ce qui est visuellement dessiné/cliquable.
     * Marges augmentées (12->16 en X, 10->14 en Y) — l'ancien réglage
     * (calculé à partir d'une largeur de 34 au lieu des 44 réels) collait le
     * toggle à 2px du bord de la carte au lieu des 12px voulus.
     */
    private float toggleW() { return UiTheme.scaled(44f); }
    private float toggleH() { return UiTheme.scaled(24f); }
    private float toggleGapX() { return UiTheme.scaled(16f); }
    private float toggleGapY() { return UiTheme.scaled(14f); }

    /**
     * Entrée de grille "action" — carte qui exécute {@code action} au clic,
     * sans toggle ni {@link LauncherModule}/{@link ModuleRegistry} (outil
     * ponctuel, pas un effet continu à activer/désactiver — voir rebuildAll()).
     */
    private static final class ActionCard {
        final String name, description;
        final Runnable action;
        ActionCard(String name, String description, Runnable action) {
            this.name = name;
            this.description = description;
            this.action = action;
        }
    }

    /** Bande de fond de la sidebar — widget ordinaire (pas un dessin manuel après coup) pour rester DERRIÈRE les items de nav ajoutés après elle. */
    private final class SidebarBackground extends UiWidget {
        SidebarBackground() { super(0, 0, SIDEBAR_W, screenHeight); }

        // Jamais cliquable : sans ce override, son rectangle (toute la
        // sidebar) gagnerait le test de collision AVANT les vrais boutons
        // ajoutés après elle dans la liste (premier widget dont contains()
        // matche = celui qui reçoit onClick, voir UiScreenBase) — "Accueil"/
        // "Parametres"/"Modifier le HUD" ne recevaient donc jamais leur clic.
        @Override
        public boolean contains(double mx, double my) { return false; }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            // Dégradé (plus clair/teinté violet en haut, SIDEBAR_BG normal en
            // bas) plutôt qu'un fond plat — c'est LA sidebar qui porte le
            // dégradé "clair en haut, foncé en bas" (pas le fond général du
            // reste de l'écran, qui reste plat, voir UiScreenBase).
            UiColor top = new UiColor(
                Math.min(1f, UiTheme.SIDEBAR_BG.r + 0.07f),
                Math.min(1f, UiTheme.SIDEBAR_BG.g + 0.05f),
                Math.min(1f, UiTheme.SIDEBAR_BG.b + 0.14f),
                UiTheme.SIDEBAR_BG.a);
            renderer.drawGradientRect(0, 0, SIDEBAR_W, vpHeight, 0, UiTheme.SIDEBAR_BG, top, vpWidth, vpHeight);
            // Séparation de profondeur avec le contenu — l'ombre de TOUTE la
            // bande sidebar (pas juste son bord, sinon rectangle dégénéré de
            // largeur nulle) : ne se voit que là où elle déborde du fond plein
            // de la sidebar, donc uniquement comme un dégradé sombre qui
            // mord sur le contenu à droite.
            renderer.drawShadow(0, 0, SIDEBAR_W, vpHeight, 0f, 10f, 0f,
                new UiColor(0, 0, 0, 100), vpWidth, vpHeight);
        }
    }

    /** Item de nav sidebar — "action" null = purement visuel (ex: "Accueil", déjà l'écran affiché). */
    private final class SidebarItem extends UiWidget {
        private final String label;
        private final boolean active;
        private final Runnable action;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        SidebarItem(float x, float y, float w, String label, boolean active, Runnable action) {
            super(x, y, w, UiTheme.scaled(26f));
            this.label = label;
            this.active = active;
            this.action = action;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float hover = hoverAnim.get();
            float edgeInset = UiTheme.scaled(3f), edgeW = UiTheme.scaled(3f), edgeRadius = UiTheme.scaled(1.5f);
            if (active) {
                renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, UiTheme.SIDEBAR_ACTIVE, vpWidth, vpHeight);
                // Liseré en dégradé (clair en haut, accent normal en bas)
                // plutôt qu'une couleur plate — même "jeu de lumière" que
                // UiToggle/ModCard, cohérent sur toute l'appli.
                renderer.drawGradientRect(x, y + edgeInset, x + edgeW, y + h - edgeInset, edgeRadius, UiTheme.ACCENT, UiTheme.accentLight(), vpWidth, vpHeight);
            } else {
                // Fond ET liseré d'accent (plus discret que celui de l'item
                // actif) réagissent tous les deux au survol — avant, seul un
                // fond quasi invisible (alpha 16/255) bougeait, et le texte
                // restait TEXT_MUTED même souris dessus : aucun retour visuel
                // net, contrairement à l'item actif.
                UiColor bg = UiColor.lerp(UiColor.TRANSPARENT, UiTheme.SIDEBAR_HOVER, hover);
                renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
                UiColor edgeBottom = UiColor.lerp(UiColor.TRANSPARENT, UiTheme.ACCENT_DIM, hover);
                UiColor edgeTop = UiColor.lerp(UiColor.TRANSPARENT, UiTheme.accentLight().multiplyAlpha(UiTheme.ACCENT_DIM.a), hover);
                renderer.drawGradientRect(x, y + edgeInset, x + edgeW, y + h - edgeInset, edgeRadius, edgeBottom, edgeTop, vpWidth, vpHeight);
            }
            UiColor textColor = active ? UiTheme.TEXT_PRIMARY : UiColor.lerp(UiTheme.TEXT_MUTED, UiTheme.TEXT_PRIMARY, hover);
            renderer.drawText(label, x + UiTheme.scaled(12f), y + h / 2f - UiTheme.scaled(4f), textColor, UiTheme.scaled(0.4f), vpWidth, vpHeight);
        }

        @Override
        public void onClick() {
            if (action != null) action.run();
        }
    }

    private final class ModCard extends UiWidget {
        private final String cardName, cardDescription;
        private final Runnable onOpen;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);
        /** Fondu + léger glissement vers le haut à l'apparition — durée fixe, PAS UiAnimatedFloat (voir sa javadoc). {@code enterDelay} = décalage en cascade, voir UiStagger dans rebuildAll(). */
        private final UiTransition enterAnim;

        /** {@code name}/{@code description} générique — utilisée aussi bien pour un {@link LauncherModule} que pour un {@link ModuleGroup} (voir rebuildAll). */
        ModCard(float x, float y, float w, String name, String description, float enterDelay, Runnable onOpen) {
            super(x, y, w, CARD_H);
            this.cardName = name;
            this.cardDescription = description;
            this.onOpen = onOpen;
            this.enterAnim = new UiTransition(0.28f, enterDelay, UiEasing.EASE_OUT_CUBIC);
            this.enterAnim.show();
        }

        // Exclut la zone du toggle (mêmes coordonnées que celles utilisées
        // pour le construire dans rebuildAll()) : sinon un clic dessus
        // ouvrirait la config du mod au lieu de basculer le toggle, la carte
        // étant vérifiée en PREMIER dans la boucle de dispatch des clics.
        @Override
        public boolean contains(double mx, double my) {
            if (!super.contains(mx, my)) return false;
            float togW = toggleW(), togGapX = toggleGapX();
            float togH = toggleH(), togGapY = toggleGapY();
            float togX = x + w - togW - togGapX, togY = y + CARD_H - togH - togGapY;
            return !(mx >= togX && mx <= togX + togW && my >= togY && my <= togY + togH);
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            // eased() peut légèrement dépasser 1.0 avec certaines courbes
            // (pas EASE_OUT_CUBIC ici, mais clampé quand même par prudence :
            // un alpha > 1 serait silencieusement ignoré par le shader, mais
            // autant rester explicite) — glissement vers le HAUT (Y croissant
            // vers le haut dans ce repère, voir UiRenderer) : la carte part
            // d'une position plus BASSE (y plus petit) et remonte vers y.
            float t = Math.max(0f, Math.min(1f, enterAnim.eased()));
            float drawY = y - (1f - t) * UiTheme.scaled(14f);

            // Ombre portée AVANT le fond de la carte (sinon elle le
            // recouvrirait) — légèrement décalée vers le bas pour un effet
            // "carte qui flotte" plutôt qu'une simple bordure sombre. Spread
            // POSITIF (pas négatif) : avec un spread négatif, le cœur opaque
            // de l'ombre finit entièrement SOUS la carte (masqué), seul le
            // bord très adouci du flou dépassait — quasi invisible en jeu.
            // Alpha remonté (90->170) pour la même raison : noir sur noir
            // (CARD_BG est déjà très sombre) a naturellement peu de contraste.
            UiColor shadowColor = new UiColor(0, 0, 0, 170).multiplyAlpha(t);
            float shadowOff = UiTheme.scaled(6f);
            renderer.drawShadow(x, drawY - shadowOff, x + w, drawY + h - shadowOff, UiTheme.RADIUS_MD, UiTheme.scaled(18f), UiTheme.scaled(3f),
                shadowColor, vpWidth, vpHeight);

            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hoverAnim.get()).multiplyAlpha(t);
            renderer.drawRoundedRect(x, drawY, x + w, drawY + h, UiTheme.RADIUS_MD, bg, vpWidth, vpHeight);

            // Pastille icone (initiale du mod) — pas d'image reelle en attendant les icones mods.
            // Dégradé (accent clair en haut, dim en bas) plutôt qu'un fond
            // plat — même jeu de lumière que le reste de l'appli.
            float iconSize = UiTheme.scaled(36f);
            float pad = UiTheme.scaled(12f);
            UiColor iconTop = UiTheme.accentLight().multiplyAlpha(UiTheme.ACCENT_DIM.a * t);
            UiColor iconBottom = UiTheme.ACCENT_DIM.multiplyAlpha(t);
            renderer.drawGradientRect(x + pad, drawY + h - iconSize - pad, x + pad + iconSize, drawY + h - pad,
                UiTheme.RADIUS_SM, iconBottom, iconTop, vpWidth, vpHeight);
            String initial = cardName.substring(0, 1).toUpperCase(Locale.ROOT);
            float iconTextScale = UiTheme.scaled(0.5f);
            float iw = renderer.textWidth(initial, iconTextScale);
            renderer.drawText(initial, x + pad + (iconSize - iw) / 2f, drawY + h - pad - iconSize / 2f - UiTheme.scaled(6f),
                UiTheme.ACCENT.multiplyAlpha(t), iconTextScale, vpWidth, vpHeight);

            float textX = x + pad + iconSize + pad;
            renderer.drawText(cardName, textX, drawY + h - UiTheme.scaled(26f), UiTheme.TEXT_PRIMARY.multiplyAlpha(t), UiTheme.scaled(0.42f), vpWidth, vpHeight);
            renderer.drawText(cardDescription, textX, drawY + h - UiTheme.scaled(46f), UiTheme.TEXT_SECONDARY.multiplyAlpha(t), UiTheme.scaled(0.4f), vpWidth, vpHeight);
        }

        @Override
        public void onClick() {
            onOpen.run();
        }
    }

    private final class CloseButton extends UiWidget {
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        CloseButton(float x, float y, float size) { super(x, y, size, size); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hoverAnim.get());
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            String label = "x";
            float scale = UiTheme.scaled(0.45f);
            float tw = renderer.textWidth(label, scale);
            renderer.drawText(label, x + (w - tw) / 2f, y + h / 2f - UiTheme.scaled(5f), UiTheme.TEXT_SECONDARY, scale, vpWidth, vpHeight);
        }

        @Override
        public void onClick() { closeTo(lastScreen); }
    }
}
