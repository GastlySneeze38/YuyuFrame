package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.runtime.ui.HudConfigStore;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleGroup;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAsyncFade;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.anim.UiEasing;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.core.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiStagger;
import com.yuyuframe.launcheragent.apigraphic.anim.UiTransition;
import com.yuyuframe.launcheragent.apigraphic.core.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTextField;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiToggle;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
            renderer.drawText(Lang.tr("Mods installes"), SIDEBAR_W + MARGIN, screenHeight - UiTheme.scaled(40f),
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
            searchField = new UiTextField(0, 0, 0, 0, Lang.tr("Rechercher un mod..."), v -> rebuildAll()).searchIcon();
        }
        searchField.x = contentX;
        searchField.y = screenHeight - UiTheme.scaled(64f) - SEARCH_H;
        // Réserve la place du bouton d'agencement (voir LayoutSwitchButton
        // ci-dessous) — sinon la barre de recherche pleine largeur (380
        // scaled) le chevaucherait.
        float layoutBtnGap = UiTheme.scaled(8f);
        float layoutBtnSize = SEARCH_H;
        searchField.w = Math.min(UiTheme.scaled(380f), contentW - layoutBtnGap - layoutBtnSize);
        searchField.h = SEARCH_H;
        widgets.add(searchField);
        // Icône à côté de la barre de recherche (demandé explicitement :
        // "choisir avec une icon a coté de la bar de recherche") — cycle
        // Détaillé -> Compacte -> Grille d'icônes -> Détaillé au clic.
        widgets.add(new LayoutSwitchButton(searchField.x + searchField.w + layoutBtnGap, searchField.y, layoutBtnSize));

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
        // Ouvre ModrinthContentScreen (notre propre pipeline graphique,
        // version-générique) — PAS l'ancien screen.ResourcePackSearchScreen
        // (compile contre les stubs vanilla + ScreenHelper, cassé sur 1.8.9,
        // voir sa javadoc pour le détail). UNE SEULE carte pour resource packs
        // ET shaders (avant : deux cartes séparées) — l'écran s'ouvre sur
        // l'onglet Resource Packs, l'onglet Shaders y est proposé EN PLUS
        // (gating ShaderLoaderDetector appliqué DANS l'écran, voir
        // ModrinthContentScreen.buildLayout, pas ici).
        ActionCard modrinthCard = new ActionCard("Modrinth Install", "Rechercher et installer un resource pack ou un shader pack",
            "Resource packs et shaders",
            () -> closeTo(new com.yuyuframe.launcheragent.runtime.module.ModrinthContentScreen(UiMainMenuScreen.this)));
        modrinthCard.iconUrl = LauncherModule.icons8("puzzle");
        modrinthCard.favorite = HudConfigStore.loadActionFavorite("modrinth");
        if (filter.isEmpty() || modrinthCard.name.toLowerCase(Locale.ROOT).contains(filter)) filtered.add(modrinthCard);
        for (ModuleGroup g : ModuleRegistry.groups()) {
            if (filter.isEmpty() || g.name.toLowerCase(Locale.ROOT).contains(filter)) filtered.add(g);
        }
        for (LauncherModule m : ModuleRegistry.ungrouped()) {
            if (filter.isEmpty() || m.name.toLowerCase(Locale.ROOT).contains(filter)) filtered.add(m);
        }

        // BUG TROUVÉ (retour utilisateur, capture d'écran : "la barre de
        // scroll est chevauchée" par les toggles) — MÊME cause/même fix que
        // UiModConfigScreen ("le scroll chevauche les paramètres") : les
        // cartes/toggles utilisaient TOUTE la largeur de contentW, la même
        // zone où la barre de scroll se dessine (~20px scaled du bord du
        // viewport, voir UiScrollContainer.SCROLLBAR_W/MARGIN). Le viewport
        // de modScroll garde TOUTE la largeur (la barre se dessine à son
        // bord droit réel, voir modScroll ci-dessous), seule la largeur des
        // CARTES est réduite par cette réserve.
        float scrollbarReserve = UiTheme.scaled(20f);
        float cardAreaW = contentW - scrollbarReserve;

        // 3 agencements demandés explicitement ("comme dans Lunar") — colonnes
        // et hauteur de ligne dépendent du mode, voir LayoutSwitchButton pour
        // le cycle et GlobalUiSettings#cardLayout pour la persistance :
        // - Détaillé (0, INCHANGÉ) : 2 colonnes, cartes hautes avec description.
        // - Compacte (1) : MÊME apparence de carte que Détaillé (retour
        //   utilisateur explicite : "garder l'apparence de la card détaillé
        //   juste mettre tout sur 1 seule colonne") — voir ModCard.draw(),
        //   layoutMode 1 utilise drawDetailed() telle quelle, seule la grille
        //   change (1 colonne pleine largeur au lieu de 2).
        // - Grille d'icônes (2) : autant de colonnes que la largeur le permet,
        //   cellules carrées, pas de description (voir ModCard#tooltip à la place).
        int cardLayout = GlobalUiSettings.INSTANCE.cardLayout;
        int cols;
        float cardW, rowH;
        if (cardLayout == 1) {
            cols = 1;
            cardW = cardAreaW;
            rowH = CARD_H;
        } else if (cardLayout == 2) {
            // Cellules ENCORE bien plus grandes que le premier essai (retour
            // utilisateur : "agrandi les énormément, ils sont tout petit
            // là") — 110->200 scaled, quitte à n'avoir que 2-4 colonnes sur
            // un écran normal, exactement le style "grosses tuiles" visé.
            float targetCell = UiTheme.scaled(200f);
            cols = Math.max(1, (int) Math.floor((cardAreaW + CARD_GAP) / (targetCell + CARD_GAP)));
            cardW = (cardAreaW - (cols - 1) * CARD_GAP) / cols;
            // PAS carrée (rowH = cardW) — retour utilisateur : "c'est la
            // hauteur de la card entière qu'il fallait réduire", pas celle
            // de la bande (voir iconGridBarH, revenue à sa taille d'avant).
            // Ratio choisi pour rester bien plus court que large, façon
            // tuile Lunar, tout en gardant assez de place pour une icône
            // lisible au-dessus de la bande.
            rowH = cardW * 0.72f;
        } else {
            cols = 2;
            cardW = (cardAreaW - CARD_GAP) / 2f;
            rowH = CARD_H;
        }
        // Grille positionnée dans un repère LOCAL arbitraire (contrairement à
        // avant, où "top" dérivait de searchField.y, un repère ÉCRAN absolu) —
        // UiScrollContainer se charge lui-même de replacer ce contenu dans le
        // viewport réel via un offset recalculé chaque frame (voir sa javadoc
        // et ConfigScreenBuilder pour le même motif) : peu importe l'origine
        // choisie ici, seules les positions RELATIVES entre cartes comptent.
        float top = 0f;

        float viewportBottom = MARGIN;
        // BUG TROUVÉ (retour utilisateur : "les cards du haut disparaissent
        // trop tard et chevauchent la barre de navigation") — UiScrollContainer
        // continue de dessiner (en s'estompant progressivement, voir clipFade)
        // un widget jusqu'à EDGE_FADE_ZONE (46 scaled) AU-DELÀ du viewport,
        // pas juste jusqu'à son bord — cet écart n'était que de 24 scaled ici,
        // donc une carte pouvait encore être visible (partiellement) jusqu'à
        // 46-24=22px DANS la zone de la barre de recherche. Porté à 50
        // (> EDGE_FADE_ZONE) pour que le fondu se termine TOUJOURS avant
        // d'atteindre la barre.
        float viewportTop = searchField.y - UiTheme.scaled(50f);
        modScroll = new UiScrollContainer(contentX, viewportBottom, contentW, Math.max(1f, viewportTop - viewportBottom));

        // Favoris — demandé explicitement ("mets tout les favoris devant
        // déjà même sans l'option activée") — TOUJOURS remontés en tête,
        // inconditionnellement. LauncherModule/ModuleGroup/ActionCard
        // favorisables tous les trois désormais (ActionCard ajouté après
        // coup : "on ne peut pas mettre Modrinth en favori"). Le réglage
        // {@link GlobalUiSettings#separateFavorites} ne pilote QUE
        // l'affichage d'une section séparée avec titres, jamais l'ordre.
        List<Object> favoriteEntries = new ArrayList<>();
        List<Object> otherEntries = new ArrayList<>();
        for (Object entry : filtered) {
            boolean isFavorite = (entry instanceof LauncherModule && ((LauncherModule) entry).favorite)
                || (entry instanceof ModuleGroup && ((ModuleGroup) entry).favorite)
                || (entry instanceof ActionCard && ((ActionCard) entry).favorite);
            if (isFavorite) favoriteEntries.add(entry);
            else otherEntries.add(entry);
        }

        if (!GlobalUiSettings.INSTANCE.separateFavorites || favoriteEntries.isEmpty() || otherEntries.isEmpty()) {
            // Pas de séparation visuelle à afficher (réglage désactivé, OU
            // rien à séparer — une seule des deux listes non vide) : une
            // seule grille continue, favoris déjà en tête via l'ordre de
            // concaténation.
            List<Object> combined = new ArrayList<>(favoriteEntries.size() + otherEntries.size());
            combined.addAll(favoriteEntries);
            combined.addAll(otherEntries);
            layoutGrid(combined, contentX, top, cols, cardW, rowH, cardLayout);
        } else {
            // Séparation demandée ("tu sépare les deux liste avec des titre
            // en majuscule et tu met les favori sur la liste du haut") — un
            // en-tête + section par liste, favoris d'abord.
            float headerH = sectionHeaderH();
            modScroll.add(new SectionTitle(contentX, top - headerH, cardAreaW, headerH, Lang.tr("Favoris").toUpperCase(Locale.ROOT)));
            top -= headerH + CARD_GAP;
            top = layoutGrid(favoriteEntries, contentX, top, cols, cardW, rowH, cardLayout);
            top -= CARD_GAP;
            modScroll.add(new SectionTitle(contentX, top - headerH, cardAreaW, headerH, Lang.tr("Autres modules").toUpperCase(Locale.ROOT)));
            top -= headerH + CARD_GAP;
            layoutGrid(otherEntries, contentX, top, cols, cardW, rowH, cardLayout);
        }
    }

    /**
     * Construit les cartes (+ toggles) d'une liste d'entrées dans la grille,
     * à partir de {@code startTop} — extrait de l'ancienne boucle unique de
     * rebuildAll() (demande explicite : séparer favoris/autres en 2 sections,
     * voir son appelant) pour pouvoir l'appeler 1 ou 2 fois selon {@link
     * GlobalUiSettings#separateFavorites}. Retourne le nouveau "top" (bord
     * BAS de la dernière ligne, repère LOCAL comme le reste de la grille)
     * pour permettre d'enchaîner une 2ᵉ section juste en dessous.
     */
    private float layoutGrid(List<Object> entries, float contentX, float startTop, int cols, float cardW, float rowH, int cardLayout) {
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            int col = i % cols, row = i / cols;
            float cx = contentX + col * (cardW + CARD_GAP);
            float cy = startTop - row * (rowH + CARD_GAP) - rowH;

            // Délai croissant par index (voir UiStagger) — les cartes
            // apparaissent en cascade plutôt que toutes d'un coup, à chaque
            // rebuildAll() (resize ET chaque frappe dans la recherche, voir
            // javadoc de classe) : nouvelles instances de ModCard à chaque
            // fois, donc l'animation d'entrée rejoue naturellement à chaque
            // reconstruction — pas besoin de la déclencher "à la main".
            // Redémarre à 0 par SECTION (pas un index global continu) : léger
            // recouvrement de cascade entre la section favoris et la
            // suivante, sans conséquence visuelle notable (positions déjà
            // différentes à l'écran) — évite de faire transiter un compteur
            // entre deux appels séparés pour un gain quasi imperceptible.
            float enterDelay = UiStagger.delayFor(i, 0.035f, 0.3f);

            if (entry instanceof ModuleGroup) {
                ModuleGroup group = (ModuleGroup) entry;
                ModCard card = new ModCard(cx, cy, cardW, rowH, cardLayout, group.name, group.description, group.shortDescription, group.iconUrl, enterDelay,
                    () -> closeTo(new UiModGroupConfigScreen(UiMainMenuScreen.this, group)));
                modScroll.add(card);
                // Groupes rendus favorisables (demande explicite) — SEUL le
                // cœur existe pour un groupe, pas de bande activer/désactiver
                // (un groupe n'a pas d'état on/off propre, chaque module
                // membre garde le sien, voir UiModGroupConfigScreen) : la
                // bande reste donc TRACK_OFF par défaut (pairedToggle jamais
                // posé ici, voir ModCard#drawIconGrid), comportement déjà
                // existant avant cet ajout, inchangé.
                if (cardLayout == 2) {
                    float barH = iconGridBarH();
                    float heartSize = iconGridHeartSize();
                    float heartX = cx + cardW - heartSize - UiTheme.scaled(8f);
                    float heartY = cy + (barH - heartSize) / 2f;
                    UiToggle favoriteToggle = new UiToggle(heartX, heartY, heartSize, heartSize, group.favorite,
                        v -> { group.favorite = v; HudConfigStore.save(); rebuildAll(); }).heartStyle();
                    modScroll.add(favoriteToggle);
                    card.pairFavorite(favoriteToggle);
                }
            } else if (entry instanceof ActionCard) {
                ActionCard action = (ActionCard) entry;
                ModCard card = new ModCard(cx, cy, cardW, rowH, cardLayout, action.name, action.description, action.shortDescription, action.iconUrl, enterDelay, action.action);
                modScroll.add(card);
                // Favorisable (demande explicite : "on ne peut pas mettre
                // Modrinth en favori") — même principe que le groupe
                // ci-dessus (cœur seul, pas de bande on/off, une ActionCard
                // n'a pas d'état activé/désactivé). "modrinth" en dur : seule
                // ActionCard existante pour l'instant, voir HudConfigStore.
                // loadActionFavorite/saveActionFavorite si une 2ᵉ apparaît un
                // jour (identifiant à généraliser à ce moment-là).
                if (cardLayout == 2) {
                    float barH = iconGridBarH();
                    float heartSize = iconGridHeartSize();
                    float heartX = cx + cardW - heartSize - UiTheme.scaled(8f);
                    float heartY = cy + (barH - heartSize) / 2f;
                    UiToggle favoriteToggle = new UiToggle(heartX, heartY, heartSize, heartSize, action.favorite,
                        v -> { HudConfigStore.saveActionFavorite("modrinth", v); rebuildAll(); }).heartStyle();
                    modScroll.add(favoriteToggle);
                    card.pairFavorite(favoriteToggle);
                }
            } else {
                LauncherModule mod = (LauncherModule) entry;
                // Carte D'ABORD (dessinée en dessous), toggle ENSUITE (dessiné
                // PAR-DESSUS, sinon le fond plein de la carte le recouvrait
                // entièrement — visible nulle part bien que toujours cliquable
                // en dessous). ModCard.contains() exclut explicitement la zone du
                // toggle pour que le clic dessus continue de basculer le toggle
                // plutôt que d'ouvrir la config du mod — délègue directement à
                // pairedToggle.contains() (voir ModCard.contains()), donc aucune
                // géométrie à dupliquer/désynchroniser ici quel que soit
                // l'agencement.
                ModCard card = new ModCard(cx, cy, cardW, rowH, cardLayout, mod.name, mod.description, mod.shortDescription, mod.iconUrl, enterDelay,
                    () -> closeTo(new UiModConfigScreen(UiMainMenuScreen.this, mod)));
                modScroll.add(card);

                if (cardLayout == 2) {
                    // Retour utilisateur, après une 1ère version où le cœur
                    // pilotait l'activation : "en fait la bande du dessous
                    // est cliquable pour activer/désactiver le module et le
                    // cœur c'est un système de favori" — DEUX widgets
                    // cliquables distincts désormais :
                    // - toute la bande (voir ModCard#drawIconGrid pour le
                    //   dessin, couleur pilotée par pairedToggle.value())
                    //   bascule enabled — SANS rendu propre (invisibleStyle),
                    //   ModCard dessine déjà tout.
                    // - le cœur, petit, en haut de la bande, favori —
                    //   INDÉPENDANT de enabled (voir LauncherModule#favorite).
                    // Ajouté AVANT le toggle de bande dans modScroll — le
                    // cœur est un sous-rectangle DANS la zone de la bande,
                    // le premier widget dont contains() matche gagne le clic
                    // (voir UiScreenBase.dispatchClick), donc le cœur doit
                    // être testé EN PREMIER pour intercepter les clics sur
                    // sa petite zone avant que la bande (bien plus grande)
                    // ne les capte à sa place.
                    float barH = iconGridBarH();
                    UiToggle enableToggle = new UiToggle(cx, cy, cardW, barH, mod.isEnabled(),
                        v -> { mod.setEnabled(v); HudConfigStore.save(); }).invisibleStyle();

                    float heartSize = iconGridHeartSize();
                    float heartX = cx + cardW - heartSize - UiTheme.scaled(8f);
                    float heartY = cy + (barH - heartSize) / 2f;
                    UiToggle favoriteToggle = new UiToggle(heartX, heartY, heartSize, heartSize, mod.favorite,
                        v -> { mod.favorite = v; HudConfigStore.save(); rebuildAll(); }).heartStyle();

                    modScroll.add(favoriteToggle);
                    modScroll.add(enableToggle);
                    card.pairToggle(enableToggle);
                    card.pairFavorite(favoriteToggle);
                } else {
                    // Position du toggle DÉPENDANTE de l'agencement — Compacte
                    // réutilise l'apparence (et donc la position toggle) de
                    // Détaillé telle quelle (voir commentaire plus haut).
                    float togX = cx + cardW - toggleW() - toggleGapX();
                    float togY = cy + rowH - toggleH() - toggleGapY();
                    UiToggle toggle = new UiToggle(togX, togY, mod.isEnabled(),
                        v -> { mod.setEnabled(v); HudConfigStore.save(); });
                    modScroll.add(toggle);
                    // Suit le soulèvement au survol de sa carte (voir ModCard#pairToggle) —
                    // sinon il resterait figé pendant que la carte en dessous bouge.
                    card.pairToggle(toggle);
                }
            }
        }
        int rows = entries.isEmpty() ? 0 : (int) Math.ceil(entries.size() / (float) cols);
        return startTop - rows * (rowH + CARD_GAP);
    }

    /** Hauteur d'un titre de section ("FAVORIS"/"AUTRES MODULES", voir rebuildAll()) — voir SectionTitle pour le dessin. */
    private float sectionHeaderH() { return UiTheme.scaled(24f); }

    /** Simple étiquette de section, non cliquable — le texte est déjà préparé (traduit + majuscule) par l'appelant, voir rebuildAll(). */
    private final class SectionTitle extends UiWidget {
        private final String label;
        SectionTitle(float x, float y, float w, float h, String label) { super(x, y, w, h); this.label = label; }

        @Override
        public boolean contains(double mx, double my) { return false; }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            renderer.drawText(label, x, y + h / 2f - UiTheme.scaled(4f), UiTheme.TEXT_MUTED.multiplyAlpha(clipFade), UiTheme.scaled(0.36f), vpWidth, vpHeight);
        }
    }

    private static final Map<Integer, BufferedImage> CROSSHAIR_ICON_CACHE = new HashMap<>();

    /**
     * Réticule à 4 branches + point central, baké en texture CPU (même motif
     * "forme vectorielle simple" que le cœur favori de {@code UiToggle}) —
     * voir {@link LauncherModule#ICON_LOCAL_CROSSHAIR}. Retour utilisateur :
     * "trouve un meilleur icone pour custom crosshair" — AUCUN nom d'icône
     * crosshair/réticule/viseur n'existe dans le style "ios-filled" utilisé
     * partout ailleurs (vérifié individuellement, tous 404), et mélanger un
     * style icons8 différent pour cette seule carte aurait détonné
     * visuellement (épaisseur de trait/couleur différentes) — un vrai
     * réticule dessiné à la main est de toute façon plus fidèle au concept
     * que n'importe quelle icône générique disponible (ex: "target", une
     * cible en cercles concentriques, PAS un viseur).
     */
    private static BufferedImage crosshairImage(int px) {
        BufferedImage cached = CROSSHAIR_ICON_CACHE.get(px);
        if (cached != null) return cached;

        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(java.awt.Color.WHITE);

        float cx = px / 2f, cy = px / 2f;
        float thickness = px * 0.09f;
        float gap = px * 0.14f;
        float armLen = px * 0.28f;
        g.fill(new RoundRectangle2D.Float(cx - thickness / 2f, cy - gap - armLen, thickness, armLen, thickness, thickness));
        g.fill(new RoundRectangle2D.Float(cx - thickness / 2f, cy + gap, thickness, armLen, thickness, thickness));
        g.fill(new RoundRectangle2D.Float(cx - gap - armLen, cy - thickness / 2f, armLen, thickness, thickness, thickness));
        g.fill(new RoundRectangle2D.Float(cx + gap, cy - thickness / 2f, armLen, thickness, thickness, thickness));
        float dotR = px * 0.035f;
        g.fill(new Ellipse2D.Float(cx - dotR, cy - dotR, dotR * 2f, dotR * 2f));
        g.dispose();

        CROSSHAIR_ICON_CACHE.put(px, img);
        return img;
    }

    /**
     * Icône à côté de la barre de recherche — cycle les 3 agencements de la
     * grille de cartes ({@link GlobalUiSettings#cardLayout}). Dessine un
     * petit pictogramme vectoriel représentant l'agencement COURANT (pas une
     * texture — cohérent avec le reste du moteur pour une icône aussi simple,
     * voir SidebarBackground/UiToggle pour le même principe de formes
     * dessinées plutôt qu'une image).
     */
    private final class LayoutSwitchButton extends UiWidget {
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);
        private final String[] LAYOUT_NAMES = { "Détaillé", "Compacte", "Grille d'icônes" };

        LayoutSwitchButton(float x, float y, float size) { super(x, y, size, size); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float hover = hoverAnim.get();
            this.tooltip = Lang.tr("Affichage") + " : " + Lang.tr(LAYOUT_NAMES[GlobalUiSettings.INSTANCE.cardLayout]);

            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hover);
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);

            UiColor iconColor = UiColor.lerp(UiTheme.TEXT_SECONDARY, UiTheme.TEXT_PRIMARY, hover);
            float pad = UiTheme.scaled(8f);
            float ix = x + pad, iy = y + pad, iw = w - pad * 2f, ih = h - pad * 2f;
            int layout = GlobalUiSettings.INSTANCE.cardLayout;
            if (layout == 1) {
                // Compacte : mêmes cartes que Détaillé, mais 1 seule colonne
                // pleine largeur — 2 lignes empilées (au lieu de 2 côte à
                // côte pour Détaillé) pour distinguer visuellement l'icône.
                float gap = UiTheme.scaled(3f);
                float rh = (ih - gap) / 2f;
                renderer.drawRoundedRect(ix, iy, ix + iw, iy + rh, UiTheme.scaled(1.5f), iconColor, vpWidth, vpHeight);
                renderer.drawRoundedRect(ix, iy + rh + gap, ix + iw, iy + ih, UiTheme.scaled(1.5f), iconColor, vpWidth, vpHeight);
            } else if (layout == 2) {
                // Grille d'icônes : 3x3 petits carrés.
                float gap = UiTheme.scaled(2.5f);
                float cell = (iw - gap * 2f) / 3f;
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        float cx = ix + c * (cell + gap), cy = iy + r * (cell + gap);
                        renderer.drawRoundedRect(cx, cy, cx + cell, cy + cell, UiTheme.scaled(1f), iconColor, vpWidth, vpHeight);
                    }
                }
            } else {
                // Détaillé : 2 grandes cartes côte à côte.
                float gap = UiTheme.scaled(3f);
                float cw = (iw - gap) / 2f;
                renderer.drawRoundedRect(ix, iy, ix + cw, iy + ih, UiTheme.scaled(1.5f), iconColor, vpWidth, vpHeight);
                renderer.drawRoundedRect(ix + cw + gap, iy, ix + iw, iy + ih, UiTheme.scaled(1.5f), iconColor, vpWidth, vpHeight);
            }
        }

        @Override
        public void onClick() {
            GlobalUiSettings.INSTANCE.cardLayout = (GlobalUiSettings.INSTANCE.cardLayout + 1) % 3;
            HudConfigStore.save();
            rebuildAll();
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

    /** Hauteur de la bande colorée en bas d'une carte en mode Grille d'icônes (voir ModCard#drawIconGrid) — utilisée aussi ici pour positionner le cœur (pairedFavorite) au centre exact de cette bande. REVENU à 34 (retour utilisateur : "il ne fallait pas réduire sa hauteur [à elle], c'est la hauteur de la card entière qu'il fallait réduire" — voir rowH dans rebuildAll(), pas ce champ). */
    private float iconGridBarH() { return UiTheme.scaled(34f); }
    /** Retour utilisateur : "le cœur fait un peu plus petit" (20->15). */
    private float iconGridHeartSize() { return UiTheme.scaled(15f); }

    /**
     * Entrée de grille "action" — carte qui exécute {@code action} au clic,
     * sans toggle ni {@link LauncherModule}/{@link ModuleRegistry} (outil
     * ponctuel, pas un effet continu à activer/désactiver — voir rebuildAll()).
     */
    private static final class ActionCard {
        final String name, description;
        /** Voir LauncherModule/ModuleGroup#shortDescription — même principe pour une carte "action". {@code null} = pas de version dédiée. */
        final String shortDescription;
        final Runnable action;
        /** Voir LauncherModule#iconUrl — même principe, assigné après construction (voir rebuildAll(), carte Modrinth). */
        String iconUrl;
        /** Voir LauncherModule/ModuleGroup#favorite — même principe (demandé explicitement : "on ne peut pas mettre Modrinth en favori"), rechargé à CHAQUE rebuildAll() (voir HudConfigStore.loadActionFavorite, cette carte est reconstruite à chaque fois, pas une instance persistante comme un module/groupe). */
        boolean favorite;
        ActionCard(String name, String description, Runnable action) {
            this(name, description, null, action);
        }
        ActionCard(String name, String description, String shortDescription, Runnable action) {
            this.name = name;
            this.description = description;
            this.shortDescription = shortDescription;
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
            renderer.drawText(Lang.tr(label), x + UiTheme.scaled(12f), y + h / 2f - UiTheme.scaled(4f), textColor, UiTheme.scaled(0.4f), vpWidth, vpHeight);
        }

        @Override
        public void onClick() {
            if (action != null) action.run();
        }
    }

    private final class ModCard extends UiWidget {
        private final String cardName, cardDescription;
        /** Voir LauncherModule/ModuleGroup#shortDescription — {@code null} = pas de version dédiée, repli sur une troncature de {@link #cardDescription} (voir draw()). */
        private final String cardShortDescription;
        /** {@link GlobalUiSettings#cardLayout} au moment de la construction — voir rebuildAll(), figé pour la durée de vie de cette instance (une nouvelle est créée à chaque rebuildAll(), donc un changement d'agencement se reflète naturellement). 0=Détaillé, 1=Compacte, 2=Grille d'icônes. */
        private final int layoutMode;
        private final Runnable onOpen;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);
        /** Fondu + léger glissement vers le haut à l'apparition — durée fixe, PAS UiAnimatedFloat (voir sa javadoc). {@code enterDelay} = décalage en cascade, voir UiStagger dans rebuildAll(). */
        private final UiTransition enterAnim;
        // Léger soulèvement au survol (même demande/traitement que
        // ResultCard dans ModrinthContentScreen — "que ça soit dynamique").
        // hoverAnim (déjà existant pour le lerp de couleur du fond) réutilisé
        // tel quel plutôt qu'un second UiAnimatedFloat dédié — un seul
        // .get() par frame (voir sa javadoc), stocké dans une locale et
        // réutilisé pour couleur/ombre/décalage.
        private static final float HOVER_LIFT_PX = 4f;
        /** Toggle "activer/désactiver" posé PAR-DESSUS cette carte (voir rebuildAll — widget SÉPARÉ, jamais construit pour ModuleGroup/ActionCard) — sa position Y suit le soulèvement au survol via {@link #pairToggle}, sinon il resterait figé pendant que la carte en dessous bouge. En mode Grille (voir rebuildAll()), couvre TOUTE la bande du bas ({@code invisibleStyle}, dessin délégué à {@link #drawIconGrid}) plutôt qu'un petit switch — mêmes clic/valeur, juste une géométrie différente. */
        private UiToggle pairedToggle;
        /** Cœur "favori" (voir {@link com.yuyuframe.launcheragent.runtime.ui.LauncherModule#favorite}) — SEULEMENT en mode Grille (voir {@link #pairFavorite}), INDÉPENDANT de {@link #pairedToggle} (activation) : retour utilisateur explicite après une 1ère version où les deux étaient confondus. */
        private UiToggle pairedFavorite;
        /** Voir {@link com.yuyuframe.launcheragent.runtime.ui.LauncherModule#iconUrl} — {@code null} = pas d'icône dédiée, {@link #drawIconOrInitial} retombe alors sur la pastille-lettre existante. */
        private final String cardIconUrl;
        /** Survol de la bande activer/désactiver EN MODE GRILLE UNIQUEMENT (retour utilisateur : "ajoute un hover à la bar") — DISTINCT du survol de toute la carte ({@link #hoverAnim}, qui pilote le soulèvement) : suit précisément la zone de {@link #pairedToggle} (toute la bande en mode Grille, voir rebuildAll()), pas la carte entière. */
        private final UiAnimatedFloat barHoverAnim = new UiAnimatedFloat(0f, 16f);
        /** Fondu d'entrée de l'icône distante une fois chargée (voir UiRemoteImage, non-bloquant) — même motif que ResultCard dans ModrinthContentScreen. */
        private final UiAsyncFade iconFade = new UiAsyncFade();

        /** {@code name}/{@code description} générique — utilisée aussi bien pour un {@link LauncherModule} que pour un {@link ModuleGroup} (voir rebuildAll). */
        ModCard(float x, float y, float w, float h, int layoutMode, String name, String description, String shortDescription, String iconUrl, float enterDelay, Runnable onOpen) {
            super(x, y, w, h);
            this.layoutMode = layoutMode;
            this.cardName = name;
            this.cardDescription = description;
            this.cardShortDescription = shortDescription;
            this.cardIconUrl = iconUrl;
            this.onOpen = onOpen;
            this.enterAnim = new UiTransition(0.28f, enterDelay, UiEasing.EASE_OUT_CUBIC);
            this.enterAnim.show();
        }

        void pairToggle(UiToggle toggle) {
            this.pairedToggle = toggle;
            // Voir UiToggle#useExternalAlphaOnly — l'opacité de ce toggle
            // vient ENTIÈREMENT de externalAlpha (poussé chaque frame dans
            // draw() ci-dessous), plus jamais de son propre clipFade (calculé
            // sur SA taille, différente de celle de la carte).
            toggle.useExternalAlphaOnly();
        }

        /** Même principe que {@link #pairToggle} pour le cœur favori (mode Grille uniquement) — widget séparé, doit suivre le même soulèvement/fondu que la carte. */
        void pairFavorite(UiToggle favorite) {
            this.pairedFavorite = favorite;
            favorite.useExternalAlphaOnly();
        }

        // Exclut la zone du toggle — la carte est vérifiée en PREMIER dans
        // la boucle de dispatch des clics, sinon un clic dessus ouvrirait la
        // config du mod au lieu de basculer le toggle.
        //
        // BUG TROUVÉ (retour utilisateur : "les toggle dans la card de mod
        // sont chevauchés par les card de mod") : cette zone d'exclusion
        // était recalculée ICI depuis la position DE BASE (non soulevée) du
        // toggle (x/y + constantes toggleW/H/GapX/GapY) — mais {@link
        // #pairToggle} déplace le VRAI toggle de {@code hoverT *
        // HOVER_LIFT_PX} au survol (voir draw()). Une fois soulevé, la
        // position RÉELLE du toggle et cette zone d'exclusion recalculée
        // divergeaient de plusieurs pixels : la bande du haut du toggle
        // soulevé tombait alors HORS de la zone exclue (toujours calée sur
        // l'ancienne position), donc DANS la zone cliquable de la carte —
        // qui, vérifiée avant lui dans la liste, interceptait le clic à sa
        // place. Fix : interroger directement le VRAI widget
        // ({@code pairedToggle.contains}) au lieu de dupliquer sa géométrie
        // — ne peut plus jamais diverger, quel que soit un futur changement
        // d'animation.
        @Override
        public boolean contains(double mx, double my) {
            if (!super.contains(mx, my)) return false;
            if (pairedToggle != null && pairedToggle.contains(mx, my)) return false;
            if (pairedFavorite != null && pairedFavorite.contains(mx, my)) return false;
            return true;
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
            // BUG TROUVÉ (retour utilisateur, comparaison avant/après scroll :
            // "la barre de recherche disparaît après un scroll") — clipFade
            // (posé CHAQUE frame par UiScrollContainer selon la proximité du
            // bord du viewport, voir sa javadoc — 1 = pleinement opaque, vers
            // 0 en s'approchant/dépassant le bord) n'était JAMAIS lu par
            // cette carte : elle restait dessinée à PLEINE opacité tant
            // qu'elle passait le test "visible" (jusqu'à EDGE_FADE_ZONE,
            // 46 scaled, AU-DELÀ du bord du viewport), au lieu de s'estomper
            // progressivement. modScroll se dessinant APRÈS la barre de
            // recherche (voir uiDraw(), z-order — "titres par-dessus TOUT"),
            // une carte encore opaque juste au-dessus du viewport recouvrait
            // entièrement la barre. `alpha` combine les deux (PAS `drawY` —
            // le glissement d'entrée reste piloté par `t` seul, un décalage
            // de POSITION, pas d'opacité).
            float alpha = t * clipFade;
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float hoverT = hoverAnim.get();
            float drawY = y - (1f - t) * UiTheme.scaled(14f) + hoverT * HOVER_LIFT_PX;
            // BUG TROUVÉ (retour utilisateur, capture d'écran : les toggles
            // n'apparaissent NULLE PART sur l'écran principal, pas un simple
            // chevauchement) — cette ligne ÉCRASAIT pairedToggle.y avec une
            // position figée au moment de #pairToggle (repère LOCAL de la
            // grille, capturé AVANT que UiScrollContainer.applyOffsets()
            // n'ait jamais tourné, voir rebuildAll() : "repère LOCAL
            // arbitraire... UiScrollContainer se charge de replacer ce
            // contenu dans le viewport réel"). Résultat : à CHAQUE frame, le
            // vrai offset écran posé par applyOffsets() (juste avant, au
            // début de ce même draw()) était aussitôt remplacé par cette
            // valeur non-offsettée — le toggle atterrissait à sa coordonnée
            // LOCALE brute, jamais convertie en position écran réelle, donc
            // hors-champ pour absolument tous les modules. Fix : ADDITIONNER
            // le léger soulèvement de survol à la position COURANTE du
            // toggle (déjà correctement offsettée par applyOffsets() cette
            // frame), au lieu de la remplacer par une base figée.
            if (pairedToggle != null) {
                pairedToggle.y += hoverT * HOVER_LIFT_PX;
                // BUG TROUVÉ (retour utilisateur : "le fade-in marche mais
                // pas avec la card... vu que sa taille est plus grande [elle]
                // peut être presque invisible alors que le toggle est bien
                // visible") — poussait `t` seul, MAIS UiToggle multipliait
                // ENCORE par SON PROPRE clipFade (posé indépendamment par
                // UiScrollContainer selon SA PROPRE hauteur, bien plus petite
                // que celle de la carte) : pour un même défilement, le bord
                // du viewport "mange" plus de la carte (plus haute) que du
                // toggle (plus petit, souvent encore loin du bord), donnant
                // deux valeurs de fondu DIFFÉRENTES pour un seul élément
                // visuel. Fix : pousse `alpha` (déjà `t * this.clipFade`,
                // voir plus haut — la valeur COMPLÈTE de CETTE carte) et
                // {@link UiToggle#useExternalAlphaOnly} (posé une fois dans
                // pairToggle()) fait qu'il ignore désormais SON PROPRE
                // clipFade — l'opacité du toggle suit alors EXACTEMENT celle
                // de sa carte, quelle que soit leur différence de taille.
                pairedToggle.setExternalAlpha(alpha);
            }
            // Même synchronisation position/alpha pour le cœur favori (voir
            // pairFavorite()) — sinon il resterait figé/désynchronisé du
            // soulèvement au survol pendant que la carte ET la bande du
            // toggle d'activation, eux, bougent ensemble.
            if (pairedFavorite != null) {
                pairedFavorite.y += hoverT * HOVER_LIFT_PX;
                pairedFavorite.setExternalAlpha(alpha);
            }

            // Ombre portée AVANT le fond de la carte (sinon elle le
            // recouvrirait) — légèrement décalée vers le bas pour un effet
            // "carte qui flotte" plutôt qu'une simple bordure sombre. Spread
            // POSITIF (pas négatif) : avec un spread négatif, le cœur opaque
            // de l'ombre finit entièrement SOUS la carte (masqué), seul le
            // bord très adouci du flou dépassait — quasi invisible en jeu.
            // Alpha remonté (90->170) pour la même raison : noir sur noir
            // (CARD_BG est déjà très sombre) a naturellement peu de contraste.
            // Blur/alpha légèrement accentués au survol (+hoverT) — même
            // traitement que ResultCard, accentue la sensation de carte qui
            // se soulève plutôt que de simplement glisser.
            UiColor shadowColor = new UiColor(0, 0, 0, 170).multiplyAlpha(alpha);
            float shadowOff = UiTheme.scaled(6f);
            renderer.drawShadow(x, drawY - shadowOff, x + w, drawY + h - shadowOff, UiTheme.RADIUS_MD,
                UiTheme.scaled(18f) + hoverT * UiTheme.scaled(6f), UiTheme.scaled(3f),
                shadowColor, vpWidth, vpHeight);

            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hoverT).multiplyAlpha(alpha);
            renderer.drawRoundedRect(x, drawY, x + w, drawY + h, UiTheme.RADIUS_MD, bg, vpWidth, vpHeight);

            // Traduit UNE FOIS ici, à l'affichage — "cardName"/"cardDescription"
            // restent le texte source (français) dans les champs de la
            // classe (pas de conflit d'usage comme clé ici, contrairement à
            // SectionHeader/CategoryTab, mais même principe : traduire au
            // dernier moment permet un changement de langue instantané, sans
            // attendre le prochain rebuildAll()).
            String displayName = Lang.tr(cardName);
            // BUG TROUVÉ (retour utilisateur : "il faut changer le texte
            // carrément selon la taille de l'interface", PAS juste réduire
            // l'échelle ou tronquer par défaut) — en mode "Taille de
            // l'interface" = Grande, utilise la description COURTE dédiée du
            // module/groupe si une a été fournie (voir LauncherModule/
            // ModuleGroup#shortDescription — ex: "Confort visuel" y perd son
            // énumération de tous ses modules membres pour une vraie phrase
            // courte). Repli sur la description complète (comme avant) si
            // aucune version courte n'a été déclarée pour cette carte —
            // renderer.truncate ci-dessous reste alors le filet de sécurité.
            boolean largeMode = UiTheme.UI_SCALE >= 1.4f;
            String descriptionSource = (largeMode && cardShortDescription != null) ? cardShortDescription : cardDescription;
            String displayDescription = Lang.tr(descriptionSource);
            String initial = displayName.substring(0, 1).toUpperCase(Locale.ROOT);

            // 3 agencements (voir GlobalUiSettings#cardLayout/LayoutSwitchButton) —
            // Compacte (1) réutilise drawDetailed TEL QUEL (retour utilisateur
            // explicite : "garder l'apparence de la card détaillé, juste
            // mettre tout sur 1 seule colonne") — seule la grille change (voir
            // rebuildAll(), 1 colonne pleine largeur au lieu de 2), aucune
            // différence de rendu de carte elle-même. Seule Grille d'icônes
            // (2) a un rendu vraiment distinct.
            if (layoutMode == 2) {
                drawIconGrid(renderer, displayName, displayDescription, initial, drawY, alpha, mouseX, mouseY, vpWidth, vpHeight);
            } else {
                drawDetailed(renderer, displayName, displayDescription, initial, drawY, alpha, vpWidth, vpHeight);
            }
        }

        /**
         * Icône carrée à {@code (ix,iy)} taille {@code size} — vraie icône
         * distante ({@link #cardIconUrl}, voir {@link LauncherModule#iconUrl})
         * une fois chargée, PASTILLE-LETTRE de repli sinon (pas encore
         * chargée, ou aucune URL fournie pour cette carte — ex: modules pas
         * encore couverts) : demandé explicitement ("ajoute des icônes pour
         * tous les modules... même système que Modrinth"), voir
         * ResultCard#draw dans ModrinthContentScreen pour le même motif
         * exact (UiRemoteImage.get + UiAsyncFade + repli pastille). Partagé
         * entre {@link #drawDetailed} et {@link #drawIconGrid} — seule la
         * TAILLE/POSITION de l'icône diffère entre agencements, jamais son
         * contenu.
         */
        private void drawIconOrInitial(UiRenderer renderer, float ix, float iy, float size, String initial,
                float alpha, int vpWidth, int vpHeight) {
            // Réticule baké en local (voir LauncherModule.ICON_LOCAL_CROSSHAIR)
            // — synchrone, pas de fetch/fondu async nécessaire, contrairement
            // à une vraie URL distante ci-dessous.
            if (LauncherModule.ICON_LOCAL_CROSSHAIR.equals(cardIconUrl)) {
                int px = Math.max(8, Math.round(size));
                renderer.drawIcon(LauncherModule.ICON_LOCAL_CROSSHAIR, crosshairImage(px), ix, iy, size, size, alpha, vpWidth, vpHeight);
                return;
            }
            BufferedImage icon = cardIconUrl != null ? UiRemoteImage.get(cardIconUrl) : null;
            if (icon != null) {
                iconFade.markReady();
                renderer.drawIcon(cardIconUrl, icon, ix, iy, size, size, iconFade.alpha() * alpha, vpWidth, vpHeight);
                return;
            }
            // Dégradé (accent clair en haut, dim en bas) plutôt qu'un fond
            // plat — même jeu de lumière que le reste de l'appli. Échelle de
            // texte FIXE (pas proportionnelle à `size`, comme avant l'ajout
            // des icônes distantes) : la grande cellule du mode Grille a
            // toujours utilisé la même échelle que la petite pastille du
            // mode Détaillé, sans lien avec `size` — repli rare (icône
            // distante manquante/en cours de chargement), pas retouché ici.
            UiColor iconTop = UiTheme.accentLight().multiplyAlpha(UiTheme.ACCENT_DIM.a * alpha);
            UiColor iconBottom = UiTheme.ACCENT_DIM.multiplyAlpha(alpha);
            renderer.drawGradientRect(ix, iy, ix + size, iy + size, UiTheme.RADIUS_SM, iconBottom, iconTop, vpWidth, vpHeight);
            float iconTextScale = UiTheme.scaled(0.5f);
            float iw = renderer.textWidth(initial, iconTextScale);
            renderer.drawText(initial, ix + (size - iw) / 2f, iy + size / 2f - UiTheme.scaled(4f),
                UiTheme.ACCENT.multiplyAlpha(alpha), iconTextScale, vpWidth, vpHeight);
        }

        /** Agencement d'origine, INCHANGÉ — icône en bas-gauche, nom + description empilés à droite. */
        private void drawDetailed(UiRenderer renderer, String displayName, String displayDescription, String initial,
                float drawY, float alpha, int vpWidth, int vpHeight) {
            this.tooltip = null;
            float iconSize = UiTheme.scaled(36f);
            float pad = UiTheme.scaled(12f);
            drawIconOrInitial(renderer, x + pad, drawY + h - iconSize - pad, iconSize, initial, alpha, vpWidth, vpHeight);

            float textX = x + pad + iconSize + pad;
            // BUG TROUVÉ (retour utilisateur : "les sous-titres des cards de
            // mod en mode grand dépassent") — ni le nom ni la description
            // n'étaient jamais bornés à la largeur réelle de la carte, texte
            // fixe (pas de retour à la ligne voulu, voir demande explicite).
            // renderer.truncate (voir UiRenderer, déplacé depuis
            // ModrinthContentScreen où ce même besoin existait déjà) ne
            // change RIEN tant que le texte tient déjà dans cette largeur —
            // donc aucun effet en mode Petite/Normale, tronque avec "..."
            // seulement quand ça déborde réellement (mode Grande, ou un nom/
            // description simplement long). Gardé comme filet de sécurité
            // pour l'instant (retour utilisateur : solution finale encore en
            // discussion — voir "changer le texte carrément selon la taille
            // de l'interface").
            float textMaxW = (x + w) - textX - pad;
            renderer.drawText(renderer.truncate(displayName, UiTheme.scaled(0.42f), textMaxW), textX, drawY + h - UiTheme.scaled(26f), UiTheme.TEXT_PRIMARY.multiplyAlpha(alpha), UiTheme.scaled(0.42f), vpWidth, vpHeight);
            renderer.drawText(renderer.truncate(displayDescription, UiTheme.scaled(0.4f), textMaxW), textX, drawY + h - UiTheme.scaled(46f), UiTheme.TEXT_SECONDARY.multiplyAlpha(alpha), UiTheme.scaled(0.4f), vpWidth, vpHeight);
        }

        /**
         * Cellule carrée (colonnes dynamiques, voir rebuildAll()) — refaite
         * pour coller à une référence visuelle fournie par l'utilisateur :
         * grande icône sur fond sombre au-dessus, bande en bas portant le
         * nom à gauche et un cœur à droite. Précision utilisateur après une
         * 1ère version : la bande ENTIÈRE (pas le cœur) est le clic
         * activer/désactiver — sa couleur suit donc {@code pairedToggle.value()}
         * (accent quand actif, gris terne sinon) — et le cœur est un
         * FAVORI indépendant ({@link com.yuyuframe.launcheragent.runtime.ui.LauncherModule#favorite}),
         * voir rebuildAll() pour les deux widgets cliquables séparés qui
         * portent chacun sa propre logique, cette méthode ne fait QUE dessiner.
         */
        private void drawIconGrid(UiRenderer renderer, String displayName, String displayDescription, String initial,
                float drawY, float alpha, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            this.tooltip = displayDescription;
            float barH = iconGridBarH();
            float pad = UiTheme.scaled(8f);

            // Bande cliquable (voir pairedToggle, invisibleStyle — ce
            // dessin-ci est SA seule apparence) — couleur reflète l'état
            // actif/inactif du mod, pas juste décorative. drawRoundedRect
            // arrondit TOUJOURS les 4 coins identiquement (pas de contrôle
            // par coin dans ce moteur), donc un second rect PLAT (radius 0)
            // recouvre la moitié haute de la bande pour annuler l'arrondi du
            // haut : seuls les 2 coins bas restent visuellement arrondis,
            // alignés sur ceux de la carte (même radius, même bord bas — voir
            // le fond générique de draw(), déjà dessiné avant l'appel ici).
            //
            // Survol ajouté (retour utilisateur : "ajoute un hover à la bar
            // pour activer/désactiver le module") — hoverAnim (soulèvement
            // de TOUTE la carte) ne suffit pas, il faut un retour visuel
            // localisé À LA BANDE elle-même pour qu'elle se ressente comme
            // cliquable indépendamment du reste de la carte. Interroge
            // directement pairedToggle.contains() (sa géométrie EST la
            // bande, voir rebuildAll()) plutôt que de dupliquer x/y/w/barH —
            // même principe que ModCard.contains() plus haut.
            boolean enabled = pairedToggle != null && pairedToggle.value();
            barHoverAnim.setTarget(pairedToggle != null && pairedToggle.contains(mouseX, mouseY) ? 1f : 0f);
            float barHoverT = barHoverAnim.get();
            UiColor barBase = enabled ? UiTheme.ACCENT : UiTheme.TRACK_OFF;
            UiColor barHovered = enabled ? UiTheme.accentLight() : UiColor.lerp(UiTheme.TRACK_OFF, UiTheme.TEXT_MUTED, 0.35f);
            UiColor barColor = UiColor.lerp(barBase, barHovered, barHoverT).multiplyAlpha(alpha);
            renderer.drawRoundedRect(x, drawY, x + w, drawY + barH, UiTheme.RADIUS_MD, barColor, vpWidth, vpHeight);
            if (barH > UiTheme.RADIUS_MD) {
                renderer.drawRoundedRect(x, drawY + UiTheme.RADIUS_MD, x + w, drawY + barH, 0f, barColor, vpWidth, vpHeight);
            }

            // Icône centrée dans la zone au-dessus de la bande — réduite
            // (retour utilisateur : "met les icônes plus petites pour la
            // grille") : occupait quasiment toute la zone disponible avant
            // (juste la marge `pad` en moins), désormais une fraction fixe
            // de cette zone, avec la marge résultante répartie tout autour.
            float iconAreaH = h - barH;
            float iconSize = Math.max(UiTheme.scaled(18f), Math.min(w, iconAreaH) * 0.55f);
            float iconX = x + (w - iconSize) / 2f;
            float iconY = drawY + barH + (iconAreaH - iconSize) / 2f;
            drawIconOrInitial(renderer, iconX, iconY, iconSize, initial, alpha, vpWidth, vpHeight);

            // Nom dans la bande, à gauche du cœur (voir rebuildAll() pour la
            // position du cœur lui-même — widget séparé {@link #pairedFavorite}).
            // Blanc FIXE (pas TEXT_PRIMARY) : la bande reste colorée quel que
            // soit le thème clair/sombre, un texte qui suivrait le thème
            // perdrait tout contraste en thème clair.
            float heartReserve = pairedFavorite != null ? (pairedFavorite.w + UiTheme.scaled(14f)) : pad;
            float nameScale = UiTheme.scaled(0.36f);
            float nameMaxW = w - pad * 2f - heartReserve;
            String truncName = renderer.truncate(displayName, nameScale, nameMaxW);
            renderer.drawText(truncName, x + pad, drawY + barH / 2f - UiTheme.scaled(4f),
                new UiColor(1f, 1f, 1f, 1f).multiplyAlpha(alpha), nameScale, vpWidth, vpHeight);
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
