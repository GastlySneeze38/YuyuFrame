package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult;
import com.yuyuframe.launcheragent.apigraphic.layout.UiLayout;
import com.yuyuframe.launcheragent.apigraphic.layout.UiLayout.Box;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.friends.GameFriends;
import com.yuyuframe.launcheragent.runtime.friends.GameMissions;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.runtime.ui.HudConfigStore;
import com.yuyuframe.launcheragent.runtime.ui.HudElementOwner;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleGroup;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiAurora;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiCloseButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiIconButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTextField;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.AddTile;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.FilterTabs;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.FriendsCard;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.FriendDetail;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.FriendsList;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.GridEntry;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.LayoutSwitchButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.MissionsCard;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.MissionsPanel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.ModGrid;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.NavTab;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.PlayerCard;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.QuickTile;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.TextLink;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.TopBar;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.HomeStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Le menu YuyuFrame, en plein écran : une barre de verre en haut (la marque,
 * les onglets, la recherche, les réglages, la croix), et dessous l'onglet
 * ouvert, posé sur le monde net et légèrement voilé ({@link UiAurora}).
 *
 * <h2>Les onglets</h2>
 *
 * <ul>
 *   <li><b>Accueil</b> : la carte de joueur ({@link PlayerCard} : bannière, titre, rang, chiffres, derniers duels), les missions du jour,
 *       les amis en ligne et l'accès rapide aux modules favoris ;</li>
 *   <li><b>Modules</b> : la grille complète, filtrée par la recherche ;</li>
 *   <li><b>Missions</b> : le jour et la semaine en détail ;</li>
 *   <li><b>Amis</b> : la liste complète, sa recherche et les invitations.</li>
 * </ul>
 *
 * « HUD » n'est pas un onglet : c'est l'éditeur, un écran à part. Taper dans
 * la recherche ouvre l'onglet Modules.
 *
 * <p>Comme avant, l'écran n'est qu'un ASSEMBLAGE : il décrit sa structure à
 * Taffy ({@link #rebuild}) et chaque morceau vit dans son fichier
 * ({@code ingameui/mainmenu/}).
 */
public class UiMainMenuScreen extends UiScreenBase {

    private enum Tab { HOME, MODULES, MISSIONS, COMPETITIVE, FRIENDS }

    /** Onglet ouvert, gardé d'une ouverture à l'autre. */
    private static Tab tab = Tab.HOME;

    private final Object lastScreen;
    private final UiTextField searchField;
    private final UiTextField friendSearch = new UiTextField(0, 0, 0, 0, Lang.tr("Chercher un ami"), v -> requestRebuild()).searchIcon();
    /** L'ami ouvert dans la fiche de l'onglet Amis (identifiant public), gardé d'une ouverture à l'autre. */
    private static String selectedFriend;
    /** Composition de la liste à la dernière mise en page : quand elle change, l'onglet Amis se refait. */
    private String lastFriendsSignature = "";
    private float friendsHintBaseline;
    private final ModGrid grid = new ModGrid(this::requestRebuild);
    private final HomeStats stats = HomeStats.load();
    private UiScrollContainer modScroll;

    /** Titres posés sur le décor, calculés par la mise en page. */
    private float titleX, titleBaseline;
    /** Onglet Modules : la ligne d'aide sous le titre, et les cartes de la grille (leurs états sont relus en direct). */
    private float modulesHintBaseline;
    private List<GridEntry> moduleEntries = new ArrayList<>();
    /** Filtre de l'onglet Modules : 0 tous, 1 activés, 2 désactivés ; gardé d'une ouverture à l'autre. */
    private static int moduleFilter;
    private float quickTitleX = -1f, quickTitleBaseline;
    /** Le panneau vertical de droite (accueil : missions et amis ; amis : la fiche) : la bande sombre s'aligne dessus. */
    private TaffyLayoutResult.Rect homeRight;
    /** Onglet Compétitif : son état (l'historique, lu une fois par ouverture du menu), sa ligne d'aide, le titre de la grille, le message d'une grille vide. */
    private CompetitiveTab competitiveTab;
    private float competitiveHintBaseline, gridTitleX = -1f, gridTitleBaseline;
    private String competitiveEmpty;
    private float competitiveEmptyX, competitiveEmptyBaseline;

    private int lastWidth = -1, lastHeight = -1;
    private float lastScale = -1f;
    private int lastFavoritesRevision = -1;
    private int lastOpenMissions = -1;
    private boolean rebuildRequested;

    public UiMainMenuScreen(Object lastScreen) {
        super("YuyuFrame");
        this.lastScreen = lastScreen;
        this.escapeTarget = lastScreen;
        this.searchField = new UiTextField(0, 0, 0, 0, Lang.tr("Rechercher un module"), v -> {
            // Chercher, c'est vouloir la grille : l'onglet Modules s'ouvre de lui-même.
            if (!v.trim().isEmpty() && tab != Tab.MODULES) tab = Tab.MODULES;
            requestRebuild();
        }).searchIcon().pill();
    }

    /** Ouverture de la recherche : 0 = bulle ronde (la loupe), 1 = champ déplié. */
    private final com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat searchOpen =
        new com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat(0f, UiTheme.Motion.UNFOLD);
    /** La place réservée à la recherche dépliée (bord droit fixe, la bulle s'étire vers la gauche). */
    private float searchSlotRight, searchSlotW;

    /**
     * La recherche est une bulle qui s'étire en champ quand on clique dessus,
     * et se replie quand elle perd le focus sans texte. Rien de plus qu'un
     * champ dont on anime la largeur : un clic sur la bulle lui donne le
     * focus comme à n'importe quel champ.
     */
    private void layoutSearch(boolean snap) {
        if (searchSlotW <= 0f) return;
        float target = searchField.focused() || !searchField.text().isEmpty() ? 1f : 0f;
        if (snap) searchOpen.snapTo(target); else searchOpen.setTarget(target);
        float o = searchOpen.get();
        float h = searchField.h;
        float width = h + (searchSlotW - h) * o;
        searchField.x = searchSlotRight - width;
        searchField.w = width;
    }

    /** Refaire la mise en page à la prochaine image (jamais au milieu d'une distribution de clics). */
    private void requestRebuild() {
        rebuildRequested = true;
    }

    private void open(Tab next) {
        if (tab == next) return;
        tab = next;
        // La bande du fond rejoue son entrée sur la page qui s'ouvre.
        bandEnterNanos = 0L;
        if (next == Tab.COMPETITIVE && competitiveTab != null) competitiveTab.replay();
        requestRebuild();
    }

    /** Début de l'entrée de la bande du fond ; 0 = à la prochaine image (nouvelle page, nouvel écran). */
    private long bandEnterNanos, bandLastFrame;
    /** Plus long qu'un élément d'interface : c'est un décor, il ne retient rien. */
    private static final float BAND_ENTER_S = 0.7f;

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        // La grille vit dans sa liste qui défile, hors de `widgets`.
        if (modScroll != null) modScroll.pollInput(input);
    }

    /**
     * Pas de voile uniforme : {@link UiAurora} assombrit légèrement le monde
     * en dégradé. Sans verre (pas de flou possible), le fond opaque
     * d'avant reste, sinon le texte serait illisible sur le jeu en mouvement.
     */
    @Override
    protected UiColor overlayColor() {
        return UiRenderer.get(getClass().getClassLoader()).isGlassAvailable() ? UiColor.TRANSPARENT : UiTheme.OVERLAY_BG;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        // Le serveur de classement vient d'être joint (ou perdu) : rang et pastille suivent sans rouvrir.
        if (tab == Tab.COMPETITIVE && competitiveTab != null && competitiveTab.pollReady()) rebuildRequested = true;
        if (screenWidth > 0 && screenHeight > 0
                && (rebuildRequested || screenWidth != lastWidth || screenHeight != lastHeight
                    || UiTheme.UI_SCALE != lastScale || ModuleRegistry.favoritesRevision() != lastFavoritesRevision
                    // La pastille de l'onglet Missions change sa largeur.
                    || openMissions() != lastOpenMissions
                    // Un ami arrive, part ou change de section : la liste se refait.
                    || (tab == Tab.FRIENDS && !FriendsList.signature(GameFriends.friends(), friendSearchText()).equals(lastFriendsSignature)))) {
            rebuildRequested = false;
            lastOpenMissions = openMissions();
            lastWidth = screenWidth;
            lastHeight = screenHeight;
            lastScale = UiTheme.UI_SCALE;
            lastFavoritesRevision = ModuleRegistry.favoritesRevision();
            rebuild();
        }
        // La liste des amis se tient à jour tant que l'onglet est ouvert.
        if (tab == Tab.FRIENDS) GameFriends.keepFresh();
        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
        // Le flou partagé par toutes les surfaces de verre, AVANT tout dessin :
        // il doit capturer le monde seul. Le voile du fond vient juste après.
        renderer.beginGlassFrame(UiTheme.GLASS_PASSES, screenWidth, screenHeight);
        layoutSearch(false);
        if (renderer.isGlassAvailable()) {
            UiAurora.draw(renderer, screenWidth, screenHeight);
            // Derrière le panneau vertical de droite seulement (accueil : missions
            // et amis ; amis : la fiche), la bande sombre et son illustration.
            // Entrée du décor à chaque ouverture de la page. Décor seulement :
            // rien n'attend la fin, les panneaux sont déjà cliquables.
            long now = System.nanoTime();
            // Pas dessiné depuis un moment : on revient sur l'écran (après les
            // réglages, le jeu…), c'est une nouvelle ouverture.
            if (bandEnterNanos == 0L || now - bandLastFrame > 300_000_000L) {
                bandEnterNanos = now;
                // Même moment pour le contour du grand titre : il arrive avec la page.
                com.yuyuframe.launcheragent.runtime.ui.ingameui.component.DisplayTitle.replay();
            }
            bandLastFrame = now;
            float e = com.yuyuframe.launcheragent.apigraphic.anim.UiEasing.UI_OUT.apply(
                Math.min(1f, (now - bandEnterNanos) / 1e9f / BAND_ENTER_S));
            // Pages horizontales (modules, missions) : les losanges montent du
            // bas en apparaissant (y vers le haut : un décalage négatif = plus bas).
            if (tab == Tab.MODULES || tab == Tab.MISSIONS || tab == Tab.COMPETITIVE) {
                float peaksH = screenHeight * com.yuyuframe.launcheragent.runtime.ui.ingameui.component.BackdropPeaks.HEIGHT_SHARE;
                // Les textes posés sur les losanges savent qu'ils assombrissent le fond.
                final float enter = e, share = com.yuyuframe.launcheragent.runtime.ui.ingameui.component.BackdropPeaks.HEIGHT_SHARE;
                com.yuyuframe.launcheragent.apigraphic.effect.UiBackdropTone.cover((u, v) -> v >= share ? 0f
                    : enter * com.yuyuframe.launcheragent.runtime.ui.ingameui.component.BackdropPeaks.alphaAt(1f - v / share));
                renderer.pushLayer(e, 1f, screenWidth / 2f, 0f, 0f, -(1f - e) * peaksH * 0.35f);
                try {
                    com.yuyuframe.launcheragent.runtime.ui.ingameui.component.BackdropPeaks.draw(renderer,
                        0f, 0f, screenWidth, peaksH, screenWidth, screenHeight);
                } finally {
                    renderer.popLayer();
                }
            }
            if (homeRight != null && (tab == Tab.HOME || tab == Tab.FRIENDS)) {
                // Le passage en fondu se termine juste avant la colonne : elle est posée sur le fond plein.
                float bandX = homeRight.x - px(UiTheme.Space.S32)
                    - com.yuyuframe.launcheragent.runtime.ui.ingameui.component.BackdropWords.fadeWidth();
                // La bande glisse de la droite vers sa place en apparaissant.
                float bandW = screenWidth - bandX;
                final float enter = e, left = bandX, width = bandW, sw = screenWidth;
                com.yuyuframe.launcheragent.apigraphic.effect.UiBackdropTone.cover((u, v) -> enter
                    * com.yuyuframe.launcheragent.runtime.ui.ingameui.component.BackdropWords.alphaAt(u * sw - left, width, v));
                renderer.pushLayer(e, 1f, screenWidth, screenHeight / 2f, (1f - e) * bandW * 0.35f, 0f);
                try {
                    com.yuyuframe.launcheragent.runtime.ui.ingameui.component.BackdropWords.drawBand(renderer,
                        bandX, 0f, bandW, screenHeight, screenWidth, screenHeight);
                } finally {
                    renderer.popLayer();
                }
            }
        }
        super.uiDraw(mouseX, mouseY);
        try {
            if (modScroll != null) modScroll.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            String title = tab == Tab.MODULES ? "Modules" : tab == Tab.MISSIONS ? "Missions"
                : tab == Tab.COMPETITIVE ? "Compétitif" : tab == Tab.FRIENDS ? "Amis" : null;
            if (title != null) {
                com.yuyuframe.launcheragent.runtime.ui.ingameui.component.DisplayTitle.drawPage(renderer, Lang.tr(title),
                    titleX, titleBaseline, com.yuyuframe.launcheragent.runtime.ui.ingameui.component.DisplayTitle.pageSize(),
                    screenWidth, screenHeight);
            }
            if (tab == Tab.HOME && quickTitleX >= 0f) drawQuickTitle(renderer);
            if (tab == Tab.MODULES && !moduleEntries.isEmpty()) drawModulesHint(renderer);
            if (tab == Tab.FRIENDS) drawFriendsHint(renderer);
            if (tab == Tab.COMPETITIVE && competitiveTab != null) {
                competitiveTab.drawHint(renderer, titleX, competitiveHintBaseline, screenWidth, screenHeight);
                if (gridTitleX >= 0f) CompetitiveTab.drawGridTitle(renderer, gridTitleX, gridTitleBaseline, screenWidth, screenHeight);
                if (competitiveEmpty != null) {
                    UiText.drawOnBackdrop(renderer, UiFont.REGULAR, competitiveEmpty, competitiveEmptyX, competitiveEmptyBaseline,
                        UiTheme.TEXT_MUTED, px(UiTheme.Text.MD), screenWidth, screenHeight);
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[UiMainMenuScreen] uiDraw: " + t);
        }
    }

    private void drawQuickTitle(UiRenderer r) {
        com.yuyuframe.launcheragent.runtime.ui.ingameui.component.DisplayTitle.drawSection(r, Lang.tr("Accès rapide"),
            quickTitleX, quickTitleBaseline, com.yuyuframe.launcheragent.runtime.ui.ingameui.component.DisplayTitle.sectionSize(),
            screenWidth, screenHeight);
    }

    /** Hauteur de l'en-tête d'une page : le grand titre, et sous lui une ligne courte (un compte). */
    private static float headHeight() {
        return px(104f);
    }

    /** Le titre en haut de l'en-tête, la ligne courte à son pied ; rend la ligne de base de cette dernière. */
    private float placeHead(TaffyLayoutResult.Rect head) {
        titleX = head.x;
        titleBaseline = head.y + px(40f);
        return head.y + px(UiTheme.Space.S4);
    }

    // ── Mise en page ─────────────────────────────────────────────────────────

    private void rebuild() {
        widgets.clear();
        float previousScroll = modScroll != null ? modScroll.scrollOffset() : 0f;
        modScroll = null;
        quickTitleX = -1f;
        homeRight = null;
        gridTitleX = -1f;
        competitiveEmpty = null;

        float sideMargin = px(64f);

        // ── La navigation flottante (voir TopBar) ────────────────────────
        TopBar bar = new TopBar();
        NavTab home = new NavTab("Accueil", tab == Tab.HOME, null, () -> open(Tab.HOME));
        NavTab modules = new NavTab("Modules", tab == Tab.MODULES, null, () -> open(Tab.MODULES));
        NavTab missions = new NavTab("Missions", tab == Tab.MISSIONS, UiMainMenuScreen::openMissions, () -> open(Tab.MISSIONS));
        NavTab competitive = new NavTab("Compétitif", tab == Tab.COMPETITIVE, null, () -> open(Tab.COMPETITIVE));
        NavTab friends = new NavTab("Amis", tab == Tab.FRIENDS, null, () -> open(Tab.FRIENDS));
        NavTab hud = new NavTab("HUD", false, null, () -> closeTo(new UiHudEditorScreen(this)));
        UiIconButton settings = new UiIconButton(LauncherModule.icons8("settings"),
            () -> closeTo(new UiModConfigScreen(this, GlobalUiSettings.INSTANCE))).round();
        settings.tooltip(Lang.tr("Paramètres"));
        UiIconButton close = new UiCloseButton(() -> closeTo(lastScreen)).round();

        float bubble = TopBar.bubbleSize();
        float capPad = TopBar.capsulePad();
        float tabH = bubble - capPad * 2f;
        float tabGap = px(UiTheme.Space.S2);
        NavTab[] navTabs = { home, modules, missions, competitive, friends, hud };

        Box root = UiLayout.column().size(screenWidth, screenHeight);
        // Pas de bande : une rangée transparente, décollée du haut.
        Box barBox = root.add(UiLayout.row().widthPct(100f).height(bubble).rigid().alignItems("center")
            .margin(px(UiTheme.Space.S18), 0f, 0f, 0f).pad(0f, px(UiTheme.Space.S28), 0f, px(UiTheme.Space.S28)).bind(bar));
        Box brandBox = barBox.add(UiLayout.fixed(bubble, bubble));
        barBox.add(UiLayout.spacer());
        // À droite : la recherche (une bulle qui s'étire, voir layoutSearch), puis les boutons.
        Box tools = barBox.add(UiLayout.row().rigid().alignItems("center").gap(px(UiTheme.Space.S8)));
        tools.add(UiLayout.box().width(px(280f)).height(bubble).rigid().bind(searchField));
        tools.add(UiLayout.fixed(bubble, bubble).bind(settings));
        tools.add(UiLayout.fixed(bubble, bubble).bind(close));
        // Au centre : la capsule des onglets, centrée sur la fenêtre et non entre les deux côtés.
        float capW = capPad * 2f + tabGap * (navTabs.length - 1);
        for (NavTab t : navTabs) capW += t.preferredWidth();
        Box capsule = barBox.add(UiLayout.row().absolute(0f, null, null, (screenWidth - capW) / 2f).size(capW, bubble)
            .alignItems("center").gap(tabGap).pad(0f, capPad, 0f, capPad));
        for (NavTab t : navTabs) {
            capsule.add(UiLayout.fixed(t.preferredWidth(), tabH).bind(t));
        }

        // ── L'onglet ─────────────────────────────────────────────────────
        Box content = root.add(UiLayout.box().grow(1f).widthPct(100f));
        TabParts parts = new TabParts();
        switch (tab) {
            case HOME: buildHome(content, parts, sideMargin); break;
            case MODULES: buildModules(content, parts, sideMargin); break;
            case MISSIONS: buildMissions(content, parts, sideMargin); break;
            case COMPETITIVE: buildCompetitive(content, parts, sideMargin); break;
            case FRIENDS: buildFriends(content, parts, sideMargin); break;
        }

        UiLayout.Result layout = UiLayout.solve(root, screenWidth, screenHeight);
        if (layout == null) {
            // Déjà journalisé : sans mise en page, seul Échap ferme l'écran.
            return;
        }

        bar.brand(layout.rect(brandBox));
        bar.capsule(layout.rect(capsule), java.util.Arrays.asList(navTabs));
        searchSlotRight = searchField.x + searchField.w;
        searchSlotW = searchField.w;
        layoutSearch(true);

        // Ordre d'ajout = ordre de dessin ; le clic va au plus petit widget sous la souris.
        widgets.add(bar);
        widgets.add(home);
        widgets.add(modules);
        widgets.add(missions);
        widgets.add(competitive);
        widgets.add(friends);
        widgets.add(hud);
        widgets.add(searchField);
        widgets.add(settings);
        widgets.add(close);
        parts.finish(layout, previousScroll);
    }

    /** Nombre de missions du jour pas encore faites : la pastille de l'onglet. */
    private static int openMissions() {
        int n = 0;
        for (GameMissions.Mission m : GameMissions.missions()) {
            if ("daily".equals(m.period) && !m.done()) n++;
        }
        return n;
    }

    /**
     * Ce qu'un onglet ajoute après la résolution : ses widgets, et ceux qu'il
     * place lui-même dans un rectangle résolu (la grille, l'accès rapide).
     */
    private final class TabParts {
        final List<Runnable> afterSolve = new ArrayList<>();
        UiLayout.Result layout;

        void finish(UiLayout.Result solved, float previousScroll) {
            this.layout = solved;
            this.previousScroll = previousScroll;
            for (Runnable r : afterSolve) r.run();
        }

        float previousScroll;
    }


    // ── Accueil ──────────────────────────────────────────────────────────────

    private void buildHome(Box content, TabParts parts, float sideMargin) {
        Box page = content.add(UiLayout.row().fill().gap(px(UiTheme.Space.S48))
            .pad(px(UiTheme.Space.S40), sideMargin - px(UiTheme.Space.S8), px(UiTheme.Space.S40), sideMargin));

        // Colonne de gauche : la carte de joueur, et l'accès rapide en bas.
        Box left = page.add(UiLayout.column().grow(1f).heightPct(100f).minWidth(0f));
        PlayerCard card = new PlayerCard(stats, mode -> {
            CompetitiveTab.preselect(mode);
            // Recréé sur ce mode : ses comptes en dépendent.
            competitiveTab = null;
            open(Tab.COMPETITIVE);
        });
        left.add(UiLayout.box().widthPct(100f).height(PlayerCard.preferredHeight()).rigid().bind(card));

        // L'accès rapide juste dessous : deux rangées de tuiles, moins si la
        // place manque (elles sont posées à la main dans la boîte résolue).
        float quickHeadH = px(44f);
        Box quickHead = left.add(UiLayout.row().height(quickHeadH).rigid().margin(px(UiTheme.Space.S40), 0f, 0f, 0f));
        quickHead.add(UiLayout.spacer());
        TextLink edit = new TextLink("Modifier", () -> open(Tab.MODULES));
        quickHead.add(UiLayout.fixed(edit.preferredWidth(), quickHeadH).bind(edit));
        float quickH = 2f * QuickTile.height() + px(UiTheme.Space.S12);
        Box quick = left.add(UiLayout.box().height(quickH).shrink(1f).minHeight(0f).margin(px(UiTheme.Space.S12), 0f, 0f, 0f));

        // Colonne de droite : missions puis amis.
        MissionsCard missionsCard = new MissionsCard(() -> open(Tab.MISSIONS));
        FriendsCard friendsCard = new FriendsCard(() -> open(Tab.FRIENDS));
        Box right = page.add(UiLayout.column().width(px(460f)).rigid().gap(px(UiTheme.Space.S16)));
        right.add(UiLayout.box().widthPct(100f).height(MissionsCard.preferredHeight()).shrink(1f).bind(missionsCard));
        right.add(UiLayout.box().widthPct(100f).height(FriendsCard.preferredHeight()).shrink(1f).bind(friendsCard));

        parts.afterSolve.add(() -> {
            widgets.add(card);
            // Ses parties cliquables, posées dans la boîte résolue.
            card.place();
            widgets.addAll(card.children());
            homeRight = parts.layout.rect(right);
            widgets.add(missionsCard);
            widgets.add(friendsCard);
            TaffyLayoutResult.Rect head = parts.layout.rect(quickHead);
            if (head != null) {
                quickTitleX = head.x;
                quickTitleBaseline = head.y + px(UiTheme.Space.S10);
                widgets.add(edit);
            }
            layoutQuick(parts.layout.rect(quick));
        });
    }

    /** Les tuiles de l'accès rapide : autant de rangées de quatre que la place en laisse. */
    private void layoutQuick(TaffyLayoutResult.Rect rect) {
        if (rect == null || rect.w <= 0f || rect.h <= 0f) return;
        List<GridEntry> favorites = new ArrayList<>();
        for (GridEntry e : entries()) if (e.isFavorite()) favorites.add(e);

        float gap = px(UiTheme.Space.S12);
        float tileH = QuickTile.height();
        int cols = Math.max(1, UiLayout.columns(rect.w, px(220f), gap, 1, 4));
        float tileW = UiLayout.cellWidth(rect.w, cols, gap);
        int rows = Math.max(0, (int) Math.floor((rect.h + gap) / (tileH + gap)));
        int capacity = rows * cols;
        if (capacity <= 0) return;
        int shown = Math.min(favorites.size(), capacity - 1);
        float top = rect.y + rect.h;
        for (int i = 0; i <= shown; i++) {
            int row = i / cols, col = i % cols;
            float tx = rect.x + col * (tileW + gap);
            float ty = top - (row + 1) * tileH - row * gap;
            if (i < shown) {
                QuickTile tile = new QuickTile(favorites.get(i));
                tile.x = tx; tile.y = ty; tile.w = tileW; tile.h = tileH;
                widgets.add(tile);
            } else {
                AddTile add = new AddTile(favorites.isEmpty() ? "Choisir des favoris" : "Ajouter un favori", () -> open(Tab.MODULES));
                add.x = tx; add.y = ty; add.w = tileW; add.h = tileH;
                widgets.add(add);
            }
        }
    }

    // ── Modules ──────────────────────────────────────────────────────────────

    private void buildModules(Box content, TabParts parts, float sideMargin) {
        LayoutSwitchButton layoutButton = new LayoutSwitchButton(this::requestRebuild);
        List<GridEntry> all = entries();
        moduleEntries = all;
        FilterTabs filters = new FilterTabs(new String[]{ "Tous", "Activés", "Désactivés" },
            new java.util.function.IntSupplier[]{ all::size, () -> countModules(true), () -> countModules(false) },
            () -> moduleFilter, i -> { moduleFilter = i; requestRebuild(); });
        float control = px(44f);
        Box page = content.add(UiLayout.column().fill().gap(px(UiTheme.Space.S24))
            .pad(px(UiTheme.Space.S32), sideMargin, px(UiTheme.Space.S24), sideMargin));
        // Le grand titre et son compte à gauche, les filtres et l'agencement à droite.
        Box head = page.add(UiLayout.row().height(headHeight()).rigid().alignItems("flex-end").gap(px(UiTheme.Space.S10)));
        head.add(UiLayout.spacer());
        head.add(UiLayout.fixed(filters.preferredWidth(), control).bind(filters));
        head.add(UiLayout.fixed(control, control).bind(layoutButton));
        // Doit rester plus grand que la zone de fondu de la liste (46) : une carte qui s'estompe ne chevauche pas l'en-tête.
        Box viewport = page.add(UiLayout.box().grow(1f).margin(px(10f), 0f, 0f, 0f));

        parts.afterSolve.add(() -> {
            TaffyLayoutResult.Rect headRect = parts.layout.rect(head);
            if (headRect != null) modulesHintBaseline = placeHead(headRect);
            widgets.add(filters);
            widgets.add(layoutButton);
            TaffyLayoutResult.Rect vp = parts.layout.rect(viewport);
            if (vp == null) return;
            String filter = searchField.text().trim().toLowerCase(Locale.ROOT);
            List<GridEntry> favorites = new ArrayList<>();
            List<GridEntry> others = new ArrayList<>();
            for (GridEntry e : all) {
                if (!filter.isEmpty() && !Lang.tr(e.name()).toLowerCase(Locale.ROOT).contains(filter)
                        && !e.name().toLowerCase(Locale.ROOT).contains(filter)) continue;
                // Activés / Désactivés : les modules seuls (un groupe ou un outil n'a pas d'état).
                if (moduleFilter != 0 && (!e.isToggleable() || e.isActive() != (moduleFilter == 1))) continue;
                // Les favoris toujours en tête ; le réglage ne décide que des titres de section.
                (e.isFavorite() ? favorites : others).add(e);
            }
            boolean separated = GlobalUiSettings.INSTANCE.separateFavorites && !favorites.isEmpty() && !others.isEmpty();
            // La barre de défilement est collée au bord droit de la fenêtre,
            // dans la marge : les cartes gardent toute la largeur.
            modScroll = new UiScrollContainer(vp.x, vp.y, vp.w, Math.max(1f, vp.h))
                .railAt(screenWidth - px(UiTheme.Space.S16));
            grid.build(modScroll, vp.x, vp.w, favorites, others, separated, GlobalUiSettings.INSTANCE.cardLayout);
            modScroll.restoreScroll(parts.previousScroll);
        });
    }

    /** Modules de la grille allumés ({@code true}) ou éteints : les nombres des filtres et de la ligne d'aide. */
    private int countModules(boolean active) {
        int n = 0;
        for (GridEntry e : moduleEntries) if (e.isToggleable() && e.isActive() == active) n++;
        return n;
    }

    /** « 8 actifs sur 26 » : le compte seul, l'explication est retirée (2026-11-21). */
    private void drawModulesHint(UiRenderer r) {
        float s = px(UiTheme.Text.LG);
        int on = countModules(true);
        int total = on + countModules(false);
        String count = on + " " + Lang.tr(on > 1 ? "actifs" : "actif");
        UiText.drawOnBackdrop(r, UiFont.BOLD, count, titleX, modulesHintBaseline, new UiColor(92, 210, 128, 255), s,
            screenWidth, screenHeight);
        float x = titleX + UiText.width(UiFont.BOLD, count, s);
        UiText.drawOnBackdrop(r, UiFont.REGULAR, " " + Lang.tr("sur") + " " + total, x, modulesHintBaseline,
            UiTheme.TEXT_SECONDARY, s, screenWidth, screenHeight);
    }

    // ── Missions et amis ─────────────────────────────────────────────────────

    private void buildMissions(Box content, TabParts parts, float sideMargin) {
        MissionsPanel panel = new MissionsPanel();
        Box page = content.add(UiLayout.column().fill().gap(px(UiTheme.Space.S24))
            .pad(px(UiTheme.Space.S32), sideMargin, px(UiTheme.Space.S24), sideMargin));
        Box head = page.add(UiLayout.box().height(px(64f)).rigid());
        page.add(UiLayout.box().grow(1f).bind(panel));
        parts.afterSolve.add(() -> {
            TaffyLayoutResult.Rect headRect = parts.layout.rect(head);
            if (headRect != null) {
                titleX = headRect.x;
                titleBaseline = headRect.y + px(UiTheme.Space.S12);
            }
            widgets.add(panel);
        });
    }

    /**
     * L'onglet Compétitif (2026-11-21), voir {@link CompetitiveTab} : en haut
     * ce qu'on joue, le mode, Casual / Compétitif, l'aide et les réglages du
     * module ; le bandeau du rang ; puis les duels en grille, filtrés par
     * résultat.
     */
    private void buildCompetitive(Box content, TabParts parts, float sideMargin) {
        if (competitiveTab == null) competitiveTab = new CompetitiveTab();
        CompetitiveTab ct = competitiveTab;
        PvpScoreUi.ModeDropdown modes = ct.modeDropdown(this::requestRebuild);
        FilterTabs types = ct.typeTabs(this::requestRebuild);
        FilterTabs results = ct.resultTabs(this::requestRebuild);
        PvpScoreUi.PlayingSwitch playing = ct.playingSwitch(this::requestRebuild);
        com.yuyuframe.launcheragent.apigraphic.widget.UiWidget playingPainter = ct.switchPainter(playing);
        UiIconButton help = new UiIconButton(com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScreenHeader.ICON_INFO,
            () -> overlays.open(new PvpInfoModal(null, PvpInfoModal.OVERVIEW))).round();
        help.tooltip(Lang.tr("Comment ça marche ?"));
        PvpScoreModule module = PvpScoreModule.instance();
        UiIconButton moduleSettings = module == null ? null
            : new UiIconButton(LauncherModule.icons8("settings"), () -> closeTo(new UiModConfigScreen(this, module))).round();
        if (moduleSettings != null) {
            moduleSettings.tooltip(Lang.tr("Réglages : compétitif ou casual, sa touche, les mots, les sons, le partage des duels"));
        }
        CompetitiveTab.Banner banner = ct.banner();
        float control = px(44f);

        Box page = content.add(UiLayout.column().fill().gap(px(UiTheme.Space.S20))
            .pad(px(UiTheme.Space.S32), sideMargin, px(UiTheme.Space.S24), sideMargin));
        // Le titre et sa ligne d'aide à gauche ; à droite ce qu'on joue, puis ce qu'on regarde.
        Box head = page.add(UiLayout.row().height(headHeight()).rigid().alignItems("flex-end").gap(px(UiTheme.Space.S10)));
        head.add(UiLayout.spacer());
        head.add(UiLayout.fixed(CompetitiveTab.switchWidth(), control).bind(playing));
        head.add(UiLayout.fixed(CompetitiveTab.modeDropdownWidth(), control).bind(modes));
        head.add(UiLayout.fixed(types.preferredWidth(), control).bind(types));
        head.add(UiLayout.fixed(control, control).bind(help));
        if (moduleSettings != null) head.add(UiLayout.fixed(control, control).bind(moduleSettings));
        page.add(UiLayout.box().widthPct(100f).height(CompetitiveTab.bannerHeight()).rigid().bind(banner));
        Box gridHead = page.add(UiLayout.row().height(control).rigid().alignItems("center").margin(px(UiTheme.Space.S8), 0f, 0f, 0f));
        gridHead.add(UiLayout.spacer());
        gridHead.add(UiLayout.fixed(results.preferredWidth(), control).bind(results));
        // Comme Modules : plus grand que la zone de fondu de la liste, une carte qui s'estompe ne touche pas l'en-tête.
        Box viewport = page.add(UiLayout.box().grow(1f).margin(px(UiTheme.Space.S4), 0f, 0f, 0f));

        parts.afterSolve.add(() -> {
            TaffyLayoutResult.Rect headRect = parts.layout.rect(head);
            if (headRect != null) competitiveHintBaseline = placeHead(headRect);
            widgets.add(playing);
            widgets.add(playingPainter);
            widgets.add(modes);
            widgets.add(types);
            widgets.add(help);
            if (moduleSettings != null) widgets.add(moduleSettings);
            widgets.add(banner);
            TaffyLayoutResult.Rect gh = parts.layout.rect(gridHead);
            if (gh != null) {
                gridTitleX = gh.x;
                gridTitleBaseline = gh.y + px(UiTheme.Space.S10);
            }
            widgets.add(results);
            TaffyLayoutResult.Rect vp = parts.layout.rect(viewport);
            if (vp == null) return;
            modScroll = new UiScrollContainer(vp.x, vp.y, vp.w, Math.max(1f, vp.h))
                .railAt(screenWidth - px(UiTheme.Space.S16));
            if (ct.buildGrid(modScroll, vp.x, vp.w, duel -> closeTo(new UiPvpDuelScreen(this, duel)))) {
                modScroll.restoreScroll(parts.previousScroll);
            } else {
                modScroll = null;
                competitiveEmpty = ct.emptyMessage();
                competitiveEmptyX = vp.x;
                competitiveEmptyBaseline = vp.y + vp.h - px(UiTheme.Space.S28);
            }
        });
    }

    /**
     * L'onglet Amis (refonte du 2026-11-20) : à gauche les amis en trois
     * sections ({@link FriendsList}), à droite la fiche de celui qu'on a choisi
     * ({@link FriendDetail}). En haut, la recherche et « Ajouter un ami », qui
     * ramène le launcher sur l'ajout.
     */
    private void buildFriends(Box content, TabParts parts, float sideMargin) {
        GameFriends.keepFresh();
        List<GameFriends.Friend> friends = GameFriends.friends();
        if (GameFriends.find(selectedFriend) == null) selectedFriend = FriendsList.firstId(friends);
        lastFriendsSignature = FriendsList.signature(friends, friendSearchText());

        FriendDetail detail = new FriendDetail(() -> selectedFriend);
        FriendDetail.Button add = new FriendDetail.Button(() -> "+  " + Lang.tr("Ajouter un ami"), true,
            GameFriends::available, GameFriends::openAddInLauncher);
        float control = px(44f);
        float addW = UiText.width(UiFont.BOLD, "+  " + Lang.tr("Ajouter un ami"), px(UiTheme.Text.MD)) + px(UiTheme.Space.S40);

        Box page = content.add(UiLayout.column().fill().gap(px(UiTheme.Space.S24))
            .pad(px(UiTheme.Space.S32), sideMargin, px(UiTheme.Space.S24), sideMargin));
        Box head = page.add(UiLayout.row().height(headHeight()).rigid().alignItems("flex-end").gap(px(UiTheme.Space.S10)));
        head.add(UiLayout.spacer());
        head.add(UiLayout.box().width(px(280f)).height(control).shrink(1f).minWidth(px(160f)).bind(friendSearch));
        head.add(UiLayout.fixed(addW, control).bind(add));
        Box body = page.add(UiLayout.row().grow(1f).gap(px(UiTheme.Space.S32)));
        Box listBox = body.add(UiLayout.box().grow(1f).minWidth(0f).heightPct(100f));
        Box detailBox = body.add(UiLayout.box().width(px(420f)).rigid().heightPct(100f));

        parts.afterSolve.add(() -> {
            TaffyLayoutResult.Rect headRect = parts.layout.rect(head);
            if (headRect != null) friendsHintBaseline = placeHead(headRect);
            widgets.add(friendSearch);
            widgets.add(add);
            TaffyLayoutResult.Rect d = parts.layout.rect(detailBox);
            if (d != null) {
                detail.x = d.x; detail.y = d.y; detail.w = d.w; detail.h = d.h;
                homeRight = d;
                widgets.add(detail);
                widgets.addAll(detail.children());
            }
            TaffyLayoutResult.Rect vp = parts.layout.rect(listBox);
            if (vp == null) return;
            modScroll = new UiScrollContainer(vp.x, vp.y, vp.w, Math.max(1f, vp.h));
            float scrollbarReserve = px(UiTheme.Space.S20);
            FriendsList.build(modScroll, vp.x, vp.y + vp.h, vp.w - scrollbarReserve, friends, friendSearchText(),
                () -> selectedFriend, id -> selectedFriend = id);
            modScroll.restoreScroll(parts.previousScroll);
        });
    }

    private String friendSearchText() {
        return friendSearch.text();
    }

    /** « 3 en ligne sur 8 - clique sur un ami pour voir ce qu'il fait », ou pourquoi la liste est vide. */
    private void drawFriendsHint(UiRenderer r) {
        float s = px(UiTheme.Text.LG);
        List<GameFriends.Friend> friends = GameFriends.friends();
        String rest;
        float x = titleX;
        if (!GameFriends.available()) {
            rest = Lang.tr("Le launcher YuyuFrame n'est pas joignable");
        } else if (GameFriends.error() != null) {
            rest = GameFriends.error();
        } else if (!GameFriends.loaded()) {
            rest = Lang.tr("Chargement...");
        } else if (friends.isEmpty()) {
            rest = Lang.tr("Ajoute des amis depuis le launcher");
        } else {
            int online = 0;
            for (GameFriends.Friend f : friends) if (f.online()) online++;
            String count = online + " " + Lang.tr("en ligne");
            UiText.drawOnBackdrop(r, UiFont.BOLD, count, x, friendsHintBaseline, new UiColor(92, 210, 128, 255), s,
                screenWidth, screenHeight);
            x += UiText.width(UiFont.BOLD, count, s);
            rest = " " + Lang.tr("sur") + " " + friends.size();
        }
        UiText.drawOnBackdrop(r, UiFont.REGULAR, rest, x, friendsHintBaseline, UiTheme.TEXT_SECONDARY, s, screenWidth, screenHeight);
    }

    // ── Les cartes ───────────────────────────────────────────────────────────

    /**
     * Toutes les cartes possibles, dans l'ordre d'affichage : la carte
     * Modrinth, les groupes, les modules hors groupe, puis les modules
     * groupés mis en favori (un raccourci EN PLUS de la carte de leur groupe).
     */
    private List<GridEntry> entries() {
        List<GridEntry> out = new ArrayList<>();

        // Carte « action » : un outil ponctuel, pas un effet à activer. Un
        // seul écran pour les packs de ressources ET les shaders.
        boolean[] modrinthFavorite = { HudConfigStore.loadActionFavorite("modrinth") };
        out.add(new GridEntry("a:modrinth", "Modrinth Install", "Rechercher et installer un resource pack ou un shader pack",
            "Resource packs et shaders", LauncherModule.icons8("puzzle"),
            () -> closeTo(new com.yuyuframe.launcheragent.runtime.ui.ingameui.modrinth.ModrinthContentScreen(this)),
            null, null,
            () -> modrinthFavorite[0],
            v -> { modrinthFavorite[0] = v; HudConfigStore.saveActionFavorite("modrinth", v); }));

        // Un groupe n'a pas d'état propre : chaque module membre garde le sien, dans l'écran du groupe.
        for (ModuleGroup g : ModuleRegistry.groups()) {
            out.add(new GridEntry("g:" + g.id, g.name, g.description, g.shortDescription, g.iconUrl,
                () -> closeTo(new UiModGroupConfigScreen(this, g)),
                null, null,
                () -> g.favorite,
                v -> { g.favorite = v; HudConfigStore.save(); })
                .members(() -> {
                    boolean[] on = new boolean[g.members.size()];
                    for (int i = 0; i < on.length; i++) on[i] = g.members.get(i).isEnabled();
                    return on;
                }));
        }
        for (LauncherModule m : ModuleRegistry.ungrouped()) out.add(moduleEntry(m));
        for (LauncherModule m : ModuleRegistry.groupedFavorites()) out.add(moduleEntry(m));
        return out;
    }

    private GridEntry moduleEntry(LauncherModule mod) {
        ModuleGroup owner = ModuleRegistry.groupOf(mod);
        // Un module sans réglage ni élément de HUD ouvrirait un écran vide :
        // sa carte bascule simplement le module (sans refaire la grille, son
        // interrupteur suit l'état du module de lui-même).
        boolean hasSomethingToShow = owner != null || !mod.settings().isEmpty() || mod instanceof HudElementOwner;
        Runnable open = mod instanceof PvpScoreModule
            // Le Score PvP n'a pas d'écran de réglages à lui : sa carte ouvre l'onglet Compétitif (réglages dans son en-tête).
            ? () -> open(Tab.COMPETITIVE)
            : owner != null
            // Membre d'un groupe : ses réglages sont dans l'écran du groupe, ouvert sur lui.
            ? () -> closeTo(new UiModGroupConfigScreen(this, owner, mod))
            : hasSomethingToShow
            ? () -> closeTo(new UiModConfigScreen(this, mod))
            : () -> { mod.setEnabled(!mod.isEnabled()); HudConfigStore.save(); };
        return new GridEntry("m:" + mod.id, mod.name, mod.description, mod.shortDescription, mod.iconUrl, open,
            mod::isEnabled,
            v -> { mod.setEnabled(v); HudConfigStore.save(); },
            () -> mod.favorite,
            v -> { mod.favorite = v; HudConfigStore.save(); })
            .opensSettings(hasSomethingToShow || mod instanceof PvpScoreModule);
    }
}
