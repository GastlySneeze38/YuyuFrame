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
 * ouvert, posé sur le monde flouté et ses lueurs d'accent ({@link UiAurora}).
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

    private enum Tab { HOME, MODULES, MISSIONS, FRIENDS }

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
        }).searchIcon();
    }

    /** Refaire la mise en page à la prochaine image (jamais au milieu d'une distribution de clics). */
    private void requestRebuild() {
        rebuildRequested = true;
    }

    private void open(Tab next) {
        if (tab == next) return;
        tab = next;
        requestRebuild();
    }

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        // La grille vit dans sa liste qui défile, hors de `widgets`.
        if (modScroll != null) modScroll.pollInput(input);
    }

    /**
     * Pas de voile uniforme : {@link UiAurora} assombrit le monde en dégradé
     * et y pose ses lueurs. Sans verre (pas de flou possible), le fond opaque
     * d'avant reste, sinon le texte serait illisible sur le jeu en mouvement.
     */
    @Override
    protected UiColor overlayColor() {
        return UiRenderer.get(getClass().getClassLoader()).isGlassAvailable() ? UiColor.TRANSPARENT : UiTheme.OVERLAY_BG;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
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
        // il doit capturer le monde seul. Les lueurs viennent juste après.
        renderer.beginGlassFrame(UiTheme.GLASS_PASSES, screenWidth, screenHeight);
        if (renderer.isGlassAvailable()) UiAurora.draw(renderer, screenWidth, screenHeight);
        super.uiDraw(mouseX, mouseY);
        try {
            if (modScroll != null) modScroll.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            String title = tab == Tab.MODULES ? "Modules" : tab == Tab.MISSIONS ? "Missions" : tab == Tab.FRIENDS ? "Amis" : null;
            if (title != null) {
                UiText.drawOnBackdrop(renderer, UiFont.BOLD, Lang.tr(title), titleX, titleBaseline, UiTheme.TEXT_PRIMARY,
                    px(UiTheme.Text.XXL), screenWidth, screenHeight);
            }
            if (tab == Tab.HOME && quickTitleX >= 0f) drawQuickTitle(renderer);
            if (tab == Tab.MODULES && !moduleEntries.isEmpty()) drawModulesHint(renderer);
            if (tab == Tab.FRIENDS) drawFriendsHint(renderer);
        } catch (Throwable t) {
            LauncherLog.err("[UiMainMenuScreen] uiDraw: " + t);
        }
    }

    private void drawQuickTitle(UiRenderer r) {
        float s = px(UiTheme.Text.XL);
        UiText.drawOnBackdrop(r, UiFont.BOLD, Lang.tr("Accès rapide"), quickTitleX, quickTitleBaseline, UiTheme.TEXT_PRIMARY, s,
            screenWidth, screenHeight);
        float tw = UiText.width(UiFont.BOLD, Lang.tr("Accès rapide"), s);
        UiText.drawOnBackdrop(r, UiFont.REGULAR, Lang.tr("Tes modules favoris, un clic pour les allumer"),
            quickTitleX + tw + px(UiTheme.Space.S16), quickTitleBaseline, UiTheme.TEXT_MUTED, px(UiTheme.Text.MD),
            screenWidth, screenHeight);
    }

    // ── Mise en page ─────────────────────────────────────────────────────────

    private void rebuild() {
        widgets.clear();
        float previousScroll = modScroll != null ? modScroll.scrollOffset() : 0f;
        modScroll = null;
        quickTitleX = -1f;

        float barH = px(76f);
        float control = px(42f);
        float sideMargin = px(64f);

        // ── La barre du haut ─────────────────────────────────────────────
        TopBar bar = new TopBar();
        NavTab home = new NavTab("Accueil", tab == Tab.HOME, null, () -> open(Tab.HOME));
        NavTab modules = new NavTab("Modules", tab == Tab.MODULES, null, () -> open(Tab.MODULES));
        NavTab missions = new NavTab("Missions", tab == Tab.MISSIONS, UiMainMenuScreen::openMissions, () -> open(Tab.MISSIONS));
        NavTab friends = new NavTab("Amis", tab == Tab.FRIENDS, null, () -> open(Tab.FRIENDS));
        NavTab hud = new NavTab("HUD", false, null, () -> closeTo(new UiHudEditorScreen(this)));
        UiIconButton settings = new UiIconButton(LauncherModule.icons8("settings"),
            () -> closeTo(new UiModConfigScreen(this, GlobalUiSettings.INSTANCE)));
        settings.tooltip(Lang.tr("Paramètres"));
        UiCloseButton close = new UiCloseButton(() -> closeTo(lastScreen));

        Box root = UiLayout.column().size(screenWidth, screenHeight);
        Box barBox = root.add(UiLayout.row().widthPct(100f).height(barH).rigid().alignItems("center").gap(px(UiTheme.Space.S6))
            .pad(0f, px(UiTheme.Space.S32), 0f, px(UiTheme.Space.S40) + TopBar.brandWidth()).bind(bar));
        for (NavTab t : new NavTab[]{ home, modules, missions, friends, hud }) {
            barBox.add(UiLayout.fixed(t.preferredWidth(), control).bind(t));
        }
        barBox.add(UiLayout.spacer());
        barBox.add(UiLayout.box().width(px(300f)).height(control).shrink(1f).minWidth(px(140f)).bind(searchField));
        barBox.add(UiLayout.fixed(control, control).margin(0f, 0f, 0f, px(UiTheme.Space.S6)).bind(settings));
        barBox.add(UiLayout.fixed(control, control).bind(close));

        // ── L'onglet ─────────────────────────────────────────────────────
        Box content = root.add(UiLayout.box().grow(1f).widthPct(100f));
        TabParts parts = new TabParts();
        switch (tab) {
            case HOME: buildHome(content, parts, sideMargin); break;
            case MODULES: buildModules(content, parts, sideMargin); break;
            case MISSIONS: buildMissions(content, parts, sideMargin); break;
            case FRIENDS: buildFriends(content, parts, sideMargin); break;
        }

        UiLayout.Result layout = UiLayout.solve(root, screenWidth, screenHeight);
        if (layout == null) {
            // Déjà journalisé : sans mise en page, seul Échap ferme l'écran.
            return;
        }

        // Ordre d'ajout = ordre de dessin ; le clic va au plus petit widget sous la souris.
        widgets.add(bar);
        widgets.add(home);
        widgets.add(modules);
        widgets.add(missions);
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

    /** Pose le titre d'un onglet (sur le décor) au-dessus de la boîte {@code titleBox}. */
    private void placeTitle(TaffyLayoutResult.Rect rect) {
        if (rect == null) return;
        titleX = rect.x;
        titleBaseline = UiText.centeredBaseline(UiFont.BOLD, rect.y, rect.h, px(UiTheme.Text.XXL));
    }

    // ── Accueil ──────────────────────────────────────────────────────────────

    private void buildHome(Box content, TabParts parts, float sideMargin) {
        Box page = content.add(UiLayout.row().fill().gap(px(UiTheme.Space.S48))
            .pad(px(UiTheme.Space.S40), sideMargin - px(UiTheme.Space.S8), px(UiTheme.Space.S40), sideMargin));

        // Colonne de gauche : la carte de joueur, et l'accès rapide en bas.
        Box left = page.add(UiLayout.column().grow(1f).heightPct(100f).minWidth(0f));
        PlayerCard card = new PlayerCard(stats, mode -> closeTo(new UiPvpScoreScreen(this, mode)));
        left.add(UiLayout.box().widthPct(100f).height(PlayerCard.preferredHeight()).rigid().bind(card));

        // L'accès rapide juste dessous : deux rangées de tuiles, moins si la
        // place manque (elles sont posées à la main dans la boîte résolue).
        float quickHeadH = px(UiTheme.Space.S36);
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
            widgets.add(missionsCard);
            widgets.add(friendsCard);
            TaffyLayoutResult.Rect head = parts.layout.rect(quickHead);
            if (head != null) {
                quickTitleX = head.x;
                quickTitleBaseline = UiText.centeredBaseline(UiFont.BOLD, head.y, head.h, px(UiTheme.Text.XL));
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
        // Le titre et sa ligne d'aide à gauche, les filtres et l'agencement à droite.
        Box head = page.add(UiLayout.row().height(px(64f)).rigid().alignItems("flex-end").gap(px(UiTheme.Space.S10)));
        head.add(UiLayout.spacer());
        head.add(UiLayout.fixed(filters.preferredWidth(), control).bind(filters));
        head.add(UiLayout.fixed(control, control).bind(layoutButton));
        // Doit rester plus grand que la zone de fondu de la liste (46) : une carte qui s'estompe ne chevauche pas l'en-tête.
        Box viewport = page.add(UiLayout.box().grow(1f).margin(px(10f), 0f, 0f, 0f));

        parts.afterSolve.add(() -> {
            TaffyLayoutResult.Rect headRect = parts.layout.rect(head);
            if (headRect != null) {
                // Le titre en haut de la boîte, la ligne d'aide sous lui.
                titleX = headRect.x;
                titleBaseline = headRect.y + headRect.h - px(UiTheme.Space.S28);
                modulesHintBaseline = headRect.y + px(UiTheme.Space.S4);
            }
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
            modScroll = new UiScrollContainer(vp.x, vp.y, vp.w, Math.max(1f, vp.h));
            // La barre de défilement se dessine au bord droit du cadre : les cartes lui laissent sa place.
            float scrollbarReserve = px(UiTheme.Space.S20);
            grid.build(modScroll, vp.x, vp.w - scrollbarReserve, favorites, others, separated, GlobalUiSettings.INSTANCE.cardLayout);
            modScroll.restoreScroll(parts.previousScroll);
        });
    }

    /** Modules de la grille allumés ({@code true}) ou éteints : les nombres des filtres et de la ligne d'aide. */
    private int countModules(boolean active) {
        int n = 0;
        for (GridEntry e : moduleEntries) if (e.isToggleable() && e.isActive() == active) n++;
        return n;
    }

    /** « 8 actifs sur 26 · la bande du bas allume un module, la carte ouvre ses réglages ». */
    private void drawModulesHint(UiRenderer r) {
        float s = px(UiTheme.Text.MD);
        int on = countModules(true);
        int total = on + countModules(false);
        String count = on + " " + Lang.tr(on > 1 ? "actifs" : "actif");
        UiText.drawOnBackdrop(r, UiFont.BOLD, count, titleX, modulesHintBaseline, new UiColor(92, 210, 128, 255), s,
            screenWidth, screenHeight);
        float x = titleX + UiText.width(UiFont.BOLD, count, s);
        String rest = " " + Lang.tr("sur") + " " + total + "   -   " + Lang.tr("La bande du bas allume ou éteint un module, la carte ouvre ses réglages");
        UiText.drawOnBackdrop(r, UiFont.REGULAR, rest, x, modulesHintBaseline, UiTheme.TEXT_MUTED, s, screenWidth, screenHeight);
    }

    // ── Missions et amis ─────────────────────────────────────────────────────

    private void buildMissions(Box content, TabParts parts, float sideMargin) {
        MissionsPanel panel = new MissionsPanel();
        Box page = content.add(UiLayout.column().fill().gap(px(UiTheme.Space.S24))
            .pad(px(UiTheme.Space.S32), sideMargin, px(UiTheme.Space.S24), sideMargin));
        Box head = page.add(UiLayout.box().height(px(UiTheme.Space.S36)).rigid());
        page.add(UiLayout.box().grow(1f).bind(panel));
        parts.afterSolve.add(() -> {
            placeTitle(parts.layout.rect(head));
            widgets.add(panel);
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
        Box head = page.add(UiLayout.row().height(px(64f)).rigid().alignItems("flex-end").gap(px(UiTheme.Space.S10)));
        head.add(UiLayout.spacer());
        head.add(UiLayout.box().width(px(280f)).height(control).shrink(1f).minWidth(px(160f)).bind(friendSearch));
        head.add(UiLayout.fixed(addW, control).bind(add));
        Box body = page.add(UiLayout.row().grow(1f).gap(px(UiTheme.Space.S32)));
        Box listBox = body.add(UiLayout.box().grow(1f).minWidth(0f).heightPct(100f));
        Box detailBox = body.add(UiLayout.box().width(px(420f)).rigid().heightPct(100f));

        parts.afterSolve.add(() -> {
            TaffyLayoutResult.Rect headRect = parts.layout.rect(head);
            if (headRect != null) {
                titleX = headRect.x;
                titleBaseline = headRect.y + headRect.h - px(UiTheme.Space.S28);
                friendsHintBaseline = headRect.y + px(UiTheme.Space.S4);
            }
            widgets.add(friendSearch);
            widgets.add(add);
            TaffyLayoutResult.Rect d = parts.layout.rect(detailBox);
            if (d != null) {
                detail.x = d.x; detail.y = d.y; detail.w = d.w; detail.h = d.h;
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
        float s = px(UiTheme.Text.MD);
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
            rest = " " + Lang.tr("sur") + " " + friends.size() + "   -   " + Lang.tr("Clique sur un ami pour voir ce qu'il fait");
        }
        UiText.drawOnBackdrop(r, UiFont.REGULAR, rest, x, friendsHintBaseline, UiTheme.TEXT_MUTED, s, screenWidth, screenHeight);
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
            // Le Score PvP n'a aucun réglage, mais un écran à lui : l'historique des duels.
            ? () -> closeTo(new UiPvpScoreScreen(this))
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
