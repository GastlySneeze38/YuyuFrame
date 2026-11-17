package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult;
import com.yuyuframe.launcheragent.apigraphic.layout.UiLayout;
import com.yuyuframe.launcheragent.apigraphic.layout.UiLayout.Box;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.runtime.ui.HudConfigStore;
import com.yuyuframe.launcheragent.runtime.ui.HudElementOwner;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleGroup;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiCloseButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTextField;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.FriendsPanel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.GridEntry;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.LayoutSwitchButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.MissionsPanel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.ModGrid;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.NavItem;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.SidebarPanel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Le menu YuyuFrame : barre latérale (logo, navigation, amis) à gauche, et à
 * droite la grille des modules (onglet Accueil) ou les missions.
 *
 * <p>Cet écran n'est plus qu'un ASSEMBLAGE (refonte du 2026-11-17) : il
 * décrit sa structure à Taffy ({@link #rebuild}) et chaque panneau vit dans
 * son fichier ({@code ingameui/mainmenu/}). Aucune coordonnée n'est calculée
 * ici.
 *
 * <p>La mise en page est refaite au redimensionnement, au changement
 * d'échelle, quand les favoris changent et à chaque frappe dans la
 * recherche — sans rien perdre : l'état des modules vit dans les modules, et
 * les champs de recherche sont créés une seule fois.
 */
public class UiMainMenuScreen extends UiScreenBase {

    /** Onglet affiché à droite de la barre : faux = Accueil (les modules), vrai = Missions. Gardé d'une ouverture à l'autre. */
    private static boolean missionsTab;

    private final Object lastScreen;
    private final UiTextField searchField;
    private final UiTextField friendSearch = FriendsPanel.newSearchField();
    private final ModGrid grid = new ModGrid(this::requestRebuild);
    private UiScrollContainer modScroll;

    /** Bord gauche et haut du contenu, pour le titre « Mods installés ». */
    private float contentLeft;

    private int lastWidth = -1, lastHeight = -1;
    private float lastScale = -1f;
    private int lastFavoritesRevision = -1;
    private boolean rebuildRequested;

    public UiMainMenuScreen(Object lastScreen) {
        super("YuyuFrame");
        this.lastScreen = lastScreen;
        this.escapeTarget = lastScreen;
        this.searchField = new UiTextField(0, 0, 0, 0, Lang.tr("Rechercher un mod..."), v -> requestRebuild()).searchIcon();
    }

    /** Refaire la mise en page à la prochaine image (jamais au milieu d'une distribution de clics). */
    private void requestRebuild() {
        rebuildRequested = true;
    }

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        // La grille vit dans sa liste qui défile, hors de `widgets` ; la pile
        // de l'écran la rend sourde sous un élément flottant (bulle d'ami).
        if (modScroll != null) modScroll.pollInput(input);
    }

    /**
     * Voile léger quand le verre est disponible : un fond opaque masquerait le
     * monde, que les panneaux de verre sont justement censés flouter. Sans
     * verre, l'interface serait illisible sur le jeu en mouvement : fond
     * opaque.
     */
    @Override
    protected UiColor overlayColor() {
        return UiRenderer.get(getClass().getClassLoader()).isGlassAvailable() ? UiTheme.GLASS_SCRIM : UiTheme.OVERLAY_BG;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (screenWidth > 0 && screenHeight > 0
                && (rebuildRequested || screenWidth != lastWidth || screenHeight != lastHeight
                    || UiTheme.UI_SCALE != lastScale || ModuleRegistry.favoritesRevision() != lastFavoritesRevision)) {
            rebuildRequested = false;
            lastWidth = screenWidth;
            lastHeight = screenHeight;
            lastScale = UiTheme.UI_SCALE;
            lastFavoritesRevision = ModuleRegistry.favoritesRevision();
            rebuild();
        }
        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
        // Le flou partagé par toutes les surfaces de verre de l'image, AVANT
        // tout dessin : il doit capturer le monde seul.
        renderer.beginGlassFrame(UiTheme.GLASS_PASSES, screenWidth, screenHeight);
        super.uiDraw(mouseX, mouseY);
        try {
            if (modScroll != null) modScroll.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            // Posé sur le décor : ombre portée.
            UiText.drawOnBackdrop(renderer, null, Lang.tr(missionsTab ? "Missions" : "Mods installes"),
                contentLeft, screenHeight - px(UiTheme.Space.S40), UiTheme.TEXT_SECONDARY, px(UiTheme.Text.BODY),
                screenWidth, screenHeight);
        } catch (Throwable t) {
            LauncherLog.err("[UiMainMenuScreen] uiDraw: " + t);
        }
    }

    private void switchTab(boolean missions) {
        if (missionsTab == missions) return;
        missionsTab = missions;
        requestRebuild();
    }

    // ── Mise en page ─────────────────────────────────────────────────────────

    private void rebuild() {
        widgets.clear();
        float previousScroll = modScroll != null ? modScroll.scrollOffset() : 0f;

        float sidebarW = px(280f);
        float margin = px(UiTheme.Space.S24);
        float navH = px(UiTheme.Space.S36);
        float searchH = px(UiTheme.Space.S36);
        float closeSize = px(UiTheme.Space.S28);
        // Doit rester plus grand que la zone de fondu de la liste (46) : une
        // carte en train de s'estomper ne doit pas chevaucher la recherche.
        float searchToGrid = px(50f);

        SidebarPanel sidebarPanel = new SidebarPanel();
        NavItem navHome = new NavItem("Accueil", LauncherModule.icons8("home"), !missionsTab, () -> switchTab(false));
        NavItem navSettings = new NavItem("Parametres", LauncherModule.icons8("settings"), false,
            () -> closeTo(new UiModConfigScreen(this, GlobalUiSettings.INSTANCE)));
        NavItem navMissions = new NavItem("Missions", LauncherModule.icons8("goal"), missionsTab, () -> switchTab(true));
        NavItem navHud = new NavItem("Modifier le HUD", LauncherModule.icons8("dashboard"), false,
            () -> closeTo(new UiHudEditorScreen(this)));
        FriendsPanel friends = new FriendsPanel(friendSearch);
        UiCloseButton close = new UiCloseButton(() -> closeTo(lastScreen));
        LayoutSwitchButton layoutButton = new LayoutSwitchButton(this::requestRebuild);
        MissionsPanel missions = new MissionsPanel();

        // ── Structure ───────────────────────────────────────────────────
        Box root = UiLayout.row().size(screenWidth, screenHeight);

        // Barre latérale : en haut la place du logo, puis la navigation ; la
        // liste des amis prend toute la place libre et épingle les deux
        // dernières entrées en bas.
        Box sidebar = root.add(UiLayout.column().width(sidebarW).heightPct(100f).rigid().gap(px(UiTheme.Space.S4))
            .pad(px(104f) - navH, px(UiTheme.Space.S16), margin, px(UiTheme.Space.S16)).bind(sidebarPanel));
        sidebar.add(UiLayout.box().height(navH).rigid().bind(navHome));
        sidebar.add(UiLayout.box().height(navH).rigid().bind(navSettings));
        sidebar.add(UiLayout.box().grow(1f).margin(px(UiTheme.Space.S14), 0f, px(UiTheme.Space.S8), 0f).bind(friends));
        sidebar.add(UiLayout.box().height(navH).rigid().bind(navMissions));
        sidebar.add(UiLayout.box().height(navH).rigid().bind(navHud));

        Box content = root.add(UiLayout.column().grow(1f).gap(searchToGrid).pad(px(UiTheme.Space.S64), margin, margin, margin));
        Box viewport;
        if (missionsTab) {
            viewport = content.add(UiLayout.box().grow(1f).bind(missions));
        } else {
            // La recherche vise 380 de large mais cède si la place manque ; le bouton, jamais.
            Box topbar = content.add(UiLayout.row().height(searchH).rigid().gap(px(UiTheme.Space.S8)));
            topbar.add(UiLayout.box().width(px(380f)).height(searchH).bind(searchField));
            topbar.add(UiLayout.fixed(searchH, searchH).bind(layoutButton));
            viewport = content.add(UiLayout.box().grow(1f));
        }

        // Hors du flux, au coin haut-droit de l'écran.
        root.add(UiLayout.fixed(closeSize, closeSize).absolute(margin, margin, null, null).bind(close));

        UiLayout.Result layout = UiLayout.solve(root, screenWidth, screenHeight);
        TaffyLayoutResult.Rect sidebarRect = layout == null ? null : layout.rect(sidebar);
        TaffyLayoutResult.Rect vp = layout == null ? null : layout.rect(viewport);
        if (vp == null || sidebarRect == null) {
            // Déjà journalisé : sans mise en page, seul Échap ferme l'écran.
            modScroll = null;
            return;
        }
        contentLeft = vp.x;
        friends.bubbleLeft = sidebarRect.x + sidebarRect.w + px(UiTheme.Space.S8);

        // Ordre d'ajout = ordre de dessin (le fond de la barre d'abord) ; le
        // clic, lui, va au plus petit widget sous la souris.
        widgets.add(sidebarPanel);
        widgets.add(navHome);
        widgets.add(navSettings);
        widgets.add(navHud);
        widgets.add(navMissions);
        widgets.add(friends);
        // Après le panneau : il pose les bornes du champ pendant son propre dessin.
        widgets.add(friendSearch);
        widgets.add(close);

        if (missionsTab) {
            widgets.add(missions);
            modScroll = null;
            return;
        }
        widgets.add(searchField);
        widgets.add(layoutButton);

        // ── La grille ───────────────────────────────────────────────────
        String filter = searchField.text().trim().toLowerCase(Locale.ROOT);
        List<GridEntry> favorites = new ArrayList<>();
        List<GridEntry> others = new ArrayList<>();
        for (GridEntry e : entries()) {
            if (!filter.isEmpty() && !Lang.tr(e.name()).toLowerCase(Locale.ROOT).contains(filter)
                    && !e.name().toLowerCase(Locale.ROOT).contains(filter)) continue;
            // Les favoris toujours en tête ; le réglage ne décide que des titres de section.
            (e.isFavorite() ? favorites : others).add(e);
        }
        boolean separated = GlobalUiSettings.INSTANCE.separateFavorites && !favorites.isEmpty() && !others.isEmpty();

        modScroll = new UiScrollContainer(vp.x, vp.y, vp.w, Math.max(1f, vp.h));
        // La barre de défilement se dessine au bord droit du cadre : les cartes lui laissent sa place.
        float scrollbarReserve = px(UiTheme.Space.S20);
        grid.build(modScroll, vp.x, vp.w - scrollbarReserve, favorites, others, separated, GlobalUiSettings.INSTANCE.cardLayout);
        // Après le remplissage : la borne dépend de la hauteur du contenu.
        modScroll.restoreScroll(previousScroll);
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
                v -> { g.favorite = v; HudConfigStore.save(); }));
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
            v -> { mod.favorite = v; HudConfigStore.save(); });
    }
}
