package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.friends.GameFriends;
import com.yuyuframe.launcheragent.runtime.friends.GameMissions;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.runtime.ui.HudConfigStore;
import com.yuyuframe.launcheragent.runtime.ui.HudElementOwner;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleGroup;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAsyncFade;
import com.yuyuframe.launcheragent.apigraphic.anim.UiBreathe;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.anim.UiEasing;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.text.UiRichText;
import com.yuyuframe.launcheragent.apigraphic.text.UiTextSpan;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiStagger;
import com.yuyuframe.launcheragent.apigraphic.anim.UiTransition;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.apigraphic.layout.LayoutSolver;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyNode;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTextField;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiToggle;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Écran d'accueil du moteur config custom — équivalent de OneConfigGui.create() :
 * sidebar de navigation à gauche, barre de recherche + grille de cartes mods
 * à droite. Chaque carte a son propre UiToggle (activer/désactiver) qui doit
 * gagner le clic face à la carte plus grande qui le contient — géré depuis
 * 2026-08-27 par {@code UiHitTest} (roadmap Phase 5.6 : "plus petite aire
 * gagne", PAS l'ordre d'ajout dans {@code modScroll}/{@code widgets} —
 * l'ancien commentaire ici prétendait le contraire, resté un cran derrière
 * le code réel après un revirement ; toggle ajouté APRÈS la carte pour des
 * raisons de PEINTURE uniquement, voir rebuildAll()).
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
    // 250 et non plus 190 depuis la liste des amis (2026-11-01) : une ligne
    // d'ami porte une tête, un pseudo et ce qu'il fait, comme dans le launcher.
    private float SIDEBAR_W = 280f;
    private float MARGIN = 24f;
    private float CARD_GAP = 16f;
    private float CARD_H = 76f;
    private float SEARCH_H = 36f;

    /**
     * Étages de flou de l'arrière-plan de verre (rework UI 2026-08-27) — 4 sur
     * 5 possibles : le 5e étage n'apporte quasiment rien de visible (à ce
     * stade l'image est déjà réduite à 1/32 de la résolution) pour une passe
     * de rendu plein écran de plus. Voir {@code UiRenderer.beginGlassFrame}.
     */
    private static final int GLASS_PASSES = 4;

    /** Cycle de respiration de la bande d'activation — lent, voir {@link UiBreathe#wave} (en dessous de ~1,5 s ça devient un clignotement). */
    private static final float BAR_BREATH_PERIOD_S = 3.2f;
    /** Intensité de la respiration — volontairement faible : elle doit se remarquer sans jamais attirer l'œil plus que le contenu de la carte. */
    private static final float BAR_BREATH_AMPLITUDE = 0.34f;

    /** Lignes de description d'une carte en mode Détaillé — la carte ayant une hauteur fixe, au-delà ça déborderait (voir UiRichText#layout borné). */
    private static final int DESC_MAX_LINES = 2;

    /**
     * Position LOCALE (repère grille) de chaque carte à la reconstruction
     * précédente, par identifiant d'entrée — sert à faire glisser une carte de
     * son ancienne place vers la nouvelle quand la grille est réordonnée
     * (voir {@code ModCard#startMove}).
     *
     * <p>Repère LOCAL et non écran : c'est celui dans lequel les cartes sont
     * posées par la grille Taffy, avant que {@code UiScrollContainer} n'y
     * ajoute son décalage de défilement. Comme ce décalage est désormais
     * préservé d'une reconstruction à l'autre (voir {@code restoreScroll}), un
     * écart local égale l'écart à l'écran — c'est justement ce qui rend la
     * comparaison valide.
     */
    private final Map<String, float[]> prevCardLocalPos = new HashMap<>();
    private final Map<String, float[]> cardLocalPos = new HashMap<>();

    /**
     * Identifiant de l'entrée dont le favori vient d'être basculé — la carte
     * correspondante reçoit un éclat après reconstruction, pour qu'on voie
     * LAQUELLE a changé (consommé une fois, remis à {@code null}).
     */
    private String pendingChangePulseId;

    /** Identifiant stable d'une entrée de grille, tous types confondus — clé de {@link #prevCardLocalPos}. */
    private static String entryId(Object entry) {
        if (entry instanceof LauncherModule) return "m:" + ((LauncherModule) entry).id;
        if (entry instanceof ModuleGroup) return "g:" + ((ModuleGroup) entry).id;
        // Une seule ActionCard existe (Modrinth) — même identifiant en dur que
        // HudConfigStore.loadActionFavorite, à généraliser si une 2e apparaît.
        if (entry instanceof ActionCard) return "a:modrinth";
        return "?";
    }

    private final Object lastScreen;
    private UiTextField searchField;
    /** Recherche de la liste des amis, repliée derrière la loupe de son en-tête (voir FriendsPanel). Même instance d'une reconstruction à l'autre, comme {@link #searchField}. */
    private UiTextField friendSearch;
    private FriendsPanel friendsPanel;
    /** État de la liste des amis gardé d'une ouverture du menu à l'autre. */
    private static boolean friendSearchOpen;
    private static final Set<String> collapsedFriendGroups = new HashSet<>();
    private static int friendScroll;
    /** Onglet affiché à droite de la sidebar : faux = Accueil (les mods), vrai = Missions. */
    private static boolean missionsTab;
    /** Grille de cartes mods — SÉPARÉE de {@code widgets} (voir UiScrollContainer/ConfigScreenBuilder pour le même motif) : permet de scroller quand le nombre de mods dépasse la hauteur visible, ce que {@code widgets} seul ne permet pas (pas de clipping/offset). */
    private UiScrollContainer modScroll;
    private int lastLayoutWidth = -1, lastLayoutHeight = -1;
    private float lastUiScale = -1f;
    /** Voir {@link ModuleRegistry#favoritesRevision()} — un favori coché depuis l'écran d'un groupe doit faire apparaître sa carte ici au retour, sans attendre un redimensionnement. */
    private int lastFavoritesRevision = -1;

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
        // La bulle d'un ami recouvre la grille : un clic dessus ne doit pas
        // atteindre la carte cachée dessous.
        boolean overBubble = friendsPanel != null && friendsPanel.overlayContains(input.mouseX, input.mouseY);
        if (modScroll != null && !overBubble) modScroll.pollInput(input);
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (screenWidth > 0 && screenHeight > 0
                && (screenWidth != lastLayoutWidth || screenHeight != lastLayoutHeight || UiTheme.UI_SCALE != lastUiScale
                    || ModuleRegistry.favoritesRevision() != lastFavoritesRevision)) {
            rebuildAll();
            lastLayoutWidth = screenWidth;
            lastLayoutHeight = screenHeight;
            lastUiScale = UiTheme.UI_SCALE;
            lastFavoritesRevision = ModuleRegistry.favoritesRevision();
        }
        // Arrière-plan flouté PARTAGÉ par toutes les surfaces de verre de ce
        // frame (sidebar, cartes, recherche) — DOIT être empilé avant tout le
        // reste : la file de rendu s'exécute dans l'ordre d'empilement, donc
        // cet appel en tête = la chaîne de flou capture le monde du jeu SEUL,
        // avant qu'un pixel d'interface ne soit posé dessus. Un seul calcul
        // pour tout l'écran, quel que soit le nombre de panneaux (voir
        // UiRenderer.beginGlassFrame — sans ça, CHAQUE panneau paierait ses
        // propres 9 passes plein écran).
        UiRenderer.get(getClass().getClassLoader()).beginGlassFrame(GLASS_PASSES, screenWidth, screenHeight);
        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            if (modScroll != null) modScroll.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            // La bulle d'un ami passe par-dessus tout, cartes comprises.
            if (friendsPanel != null) friendsPanel.drawBubble(renderer, mouseX, mouseY, screenWidth, screenHeight);
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
            // Ombre portée sur les textes posés DIRECTEMENT sur le décor (voir
            // UiTheme.TEXT_SHADOW) — le titre est sur la sidebar (donc sur du
            // verre) mais son halo d'accent le laisse par endroits sur un fond
            // presque transparent ; "Mods installés", lui, est franchement sur
            // le décor. Aucune couleur de texte ne peut tenir à la fois sur un
            // ciel blanc et dans une grotte : l'ombre règle les deux d'un coup.
            renderer.drawTextShadowed(UiFont.BOLD, "YuyuFrame", titleX, titleBaseline,
                UiTheme.TEXT_PRIMARY, UiTheme.TEXT_SHADOW, 1f, -1f, titleScale, screenWidth, screenHeight);
            renderer.drawTextShadowed(Lang.tr(missionsTab ? "Missions" : "Mods installes"), SIDEBAR_W + MARGIN, screenHeight - UiTheme.scaled(40f),
                UiTheme.TEXT_SECONDARY, UiTheme.TEXT_SHADOW, UiTheme.scaled(0.42f), screenWidth, screenHeight);

            // (Halo d'ambiance décoratif dans le coin haut-droit RETIRÉ — se
            // superposait telle une tache/cercle non identifiable au-dessus
            // des cartes de mods, sans lien visuel avec un élément précis,
            // remonté comme un artefact déroutant plutôt qu'un effet de
            // profondeur voulu.)

            drawRevealVeil(renderer);
        } catch (Throwable ignored) {}
    }

    /**
     * Voile de fond ALLÉGÉ quand le verre est disponible (rework UI
     * 2026-08-27) — {@code UiTheme.OVERLAY_BG} par défaut est quasi-opaque
     * (alpha 235) : il masquait entièrement le monde du jeu, ce qui rendait
     * tout arrière-plan flouté strictement invisible (on ne peut pas flouter
     * ce qui est déjà caché). {@code GLASS_SCRIM} laisse le décor transparaître
     * — c'est LUI que les panneaux de verre floutent, et c'est ce contraste
     * entre décor net (hors panneaux) et décor flouté (sous les panneaux) qui
     * fait tout l'effet.
     *
     * Repli inchangé sur les brackets sans Blaze3D : aucun panneau n'y sera du
     * verre (voir {@code UiRenderer.drawGlassPanel}), donc un fond transparent
     * n'y donnerait qu'une interface illisible par-dessus le jeu en mouvement.
     */
    @Override
    protected UiColor overlayColor() {
        return UiRenderer.get(getClass().getClassLoader()).isGlassAvailable()
            ? UiTheme.GLASS_SCRIM
            : UiTheme.OVERLAY_BG;
    }

    private void rebuildAll() {
        // Recalculées à CHAQUE reconstruction (pas juste au premier appel) —
        // seule façon de refléter un changement de "Taille de l'interface"
        // fait depuis Paramètres sans redémarrer l'agent, voir uiDraw()
        // (rebuildAll() redéclenché dès que UiTheme.UI_SCALE change).
        SIDEBAR_W = UiTheme.scaled(280f);
        MARGIN = UiTheme.scaled(24f);
        CARD_GAP = UiTheme.scaled(16f);
        CARD_H = UiTheme.scaled(76f);
        SEARCH_H = UiTheme.scaled(36f);

        widgets.clear();
        // Défilement de la grille sortante — restauré sur le conteneur neuf en
        // fin de méthode. Sans ça, toute reconstruction (mise en favori,
        // frappe dans la recherche) renvoie brutalement la liste en haut.
        float previousScroll = modScroll != null ? modScroll.scrollOffset() : 0f;
        // Bascule des positions : celles de la passe précédente deviennent la
        // référence "d'où l'on vient", la table courante repart vide.
        prevCardLocalPos.clear();
        prevCardLocalPos.putAll(cardLocalPos);
        cardLocalPos.clear();

        float closeSize = UiTheme.scaled(28f), closeMargin = UiTheme.scaled(24f);
        // 36 et non plus 26 (2026-11-01) : la navigation porte une icône et un texte plus grand.
        float navH = UiTheme.scaled(36f);
        float layoutBtnGap = UiTheme.scaled(8f);
        float layoutBtnSize = SEARCH_H;
        // Écart entre la barre de recherche et le haut de la zone défilante.
        // BUG D'ORIGINE CONSERVÉ TEL QUEL (voir viewportTop dans l'ancienne
        // version) : doit rester > UiScrollContainer.EDGE_FADE_ZONE (46
        // scaled), sinon une carte peut encore être partiellement visible en
        // train de s'estomper alors qu'elle chevauche déjà la barre.
        float searchToGridGap = UiTheme.scaled(50f);

        // ── Arbre de layout de l'écran ──────────────────────────────────────
        //
        // Remplace le calcul de position à la main (rework 2026-08-27, demande
        // explicite "change toutes les positions pour passer par taffy"). Ce
        // qui était auparavant une suite de soustractions depuis les bords
        // (`screenHeight - scaled(104f)`, `screenWidth - closeMargin -
        // closeSize`, ...) devient une STRUCTURE : une rangée [sidebar |
        // contenu], chacune une colonne, plus le bouton de fermeture ancré au
        // coin. Les nombres magiques qui restent sont des ESPACEMENTS
        // (paddings/gaps), plus des coordonnées — c'est là toute la
        // différence : ils ne se recalculent plus les uns à partir des autres,
        // donc en changer un ne casse plus les voisins.
        TaffyNode screen = new TaffyNode("screen", new TaffyStyle()
            .size(TaffyStyle.px(screenWidth), TaffyStyle.px(screenHeight)));

        // Sidebar — les items s'étirent d'eux-mêmes à la largeur du contenu
        // (alignItems: stretch, défaut flex), d'où l'absence de largeur
        // explicite : c'est le padding latéral qui donne l'ancien
        // `SIDEBAR_W - MARGIN * 2`.
        TaffyStyle sidebarStyle = new TaffyStyle();
        sidebarStyle.flexDirection = "column";
        sidebarStyle.width = TaffyStyle.px(SIDEBAR_W);
        sidebarStyle.height = TaffyStyle.pct(100);
        sidebarStyle.flexShrink = 0f;
        sidebarStyle.gapRow = TaffyStyle.px(UiTheme.scaled(4f));
        // Padding haut = ancien `scaled(104) - navH` : laisse la place du
        // logo et de son halo dessinés par uiDraw() au-dessus des items.
        sidebarStyle.padding = new String[]{
            TaffyStyle.px(UiTheme.scaled(104f) - navH), TaffyStyle.px(UiTheme.scaled(16f)),
            TaffyStyle.px(MARGIN), TaffyStyle.px(UiTheme.scaled(16f)) };
        TaffyNode sidebar = new TaffyNode("sidebar", sidebarStyle);
        sidebar.child(navNode("nav.home", navH));
        sidebar.child(navNode("nav.settings", navH));
        // La liste des amis prend toute la place libre entre la navigation et
        // "Modifier le HUD" — c'est elle, élastique, qui épingle ce bouton en
        // bas (rôle de l'ancienne cale). Elle n'affiche que les lignes qui
        // tiennent, le reste en "+N" (voir FriendsPanel).
        TaffyStyle friendsStyle = new TaffyStyle();
        friendsStyle.flexGrow = 1f;
        friendsStyle.margin = new String[]{
            TaffyStyle.px(UiTheme.scaled(14f)), TaffyStyle.px(0f), TaffyStyle.px(UiTheme.scaled(8f)), TaffyStyle.px(0f) };
        sidebar.child(new TaffyNode("friends", friendsStyle));
        sidebar.child(navNode("nav.missions", navH));
        sidebar.child(navNode("nav.hud", navH));
        screen.child(sidebar);

        TaffyStyle contentStyle = new TaffyStyle();
        contentStyle.flexDirection = "column";
        contentStyle.flexGrow = 1f;
        contentStyle.gapRow = TaffyStyle.px(searchToGridGap);
        contentStyle.padding = new String[]{
            TaffyStyle.px(UiTheme.scaled(64f)), TaffyStyle.px(MARGIN),
            TaffyStyle.px(MARGIN), TaffyStyle.px(MARGIN) };
        TaffyNode content = new TaffyNode("content", contentStyle);

        TaffyStyle topbarStyle = new TaffyStyle();
        topbarStyle.gapCol = TaffyStyle.px(layoutBtnGap);
        topbarStyle.height = TaffyStyle.px(SEARCH_H);
        topbarStyle.flexShrink = 0f;
        TaffyNode topbar = new TaffyNode("topbar", topbarStyle);
        // Largeur souhaitée 380, mais AUTORISÉE À RÉTRÉCIR (flexShrink=1 par
        // défaut) — reproduit exactement l'ancien `Math.min(scaled(380),
        // contentW - gap - bouton)` sans le calculer : si les trois éléments
        // ne tiennent pas, seule la recherche cède, le bouton gardant sa
        // taille (flexShrink=0 ci-dessous).
        TaffyStyle searchStyle = new TaffyStyle();
        searchStyle.width = TaffyStyle.px(UiTheme.scaled(380f));
        searchStyle.height = TaffyStyle.px(SEARCH_H);
        topbar.child(new TaffyNode("search", searchStyle));
        topbar.child(LayoutSolver.box("layoutbtn", layoutBtnSize, layoutBtnSize));
        content.child(topbar);

        TaffyStyle viewportStyle = new TaffyStyle();
        viewportStyle.flexGrow = 1f;
        content.child(new TaffyNode("viewport", viewportStyle));
        screen.child(content);

        // Ancré au coin haut-droit de l'ÉCRAN (hors du flux, donc il ne
        // pousse ni la sidebar ni le contenu) — ancien `screenWidth -
        // closeMargin - closeSize`.
        screen.child(LayoutSolver.anchored("close", closeSize, closeSize, closeMargin, closeMargin, null, null));

        LayoutSolver.Solved layout = LayoutSolver.solve(screen, screenWidth, screenHeight);

        if (searchField == null) {
            // Recréé la grille (pas tout l'écran) à chaque frappe — même
            // instance de champ conservée, voir javadoc de la classe.
            searchField = new UiTextField(0, 0, 0, 0, Lang.tr("Rechercher un mod..."), v -> rebuildAll()).searchIcon();
        }

        SidebarBackground sidebarBg = new SidebarBackground();
        // Accueil et Missions sont des onglets de cet écran (la partie droite
        // change, la sidebar reste) ; Paramètres et le HUD ouvrent un autre écran.
        SidebarItem navHome = new SidebarItem("Accueil", LauncherModule.icons8("home"), !missionsTab,
            () -> switchTab(false));
        SidebarItem navMissions = new SidebarItem("Missions", LauncherModule.icons8("goal"), missionsTab,
            () -> switchTab(true));
        SidebarItem navSettings = new SidebarItem("Parametres", LauncherModule.icons8("settings"), false,
            () -> closeTo(new UiModConfigScreen(UiMainMenuScreen.this, GlobalUiSettings.INSTANCE)));
        SidebarItem navHud = new SidebarItem("Modifier le HUD", LauncherModule.icons8("dashboard"), false,
            () -> closeTo(new UiHudEditorScreen(UiMainMenuScreen.this)));
        if (friendSearch == null) {
            // Invisible tant que la recherche est repliée : le panneau lui
            // donne une taille nulle, et il ne se dessine alors pas.
            friendSearch = new UiTextField(0, 0, 0, 0, Lang.tr("Rechercher un ami..."), v -> friendScroll = 0) {
                @Override
                public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
                    if (w > 1f && h > 1f) super.draw(renderer, mouseX, mouseY, vpWidth, vpHeight);
                }
            }.searchIcon();
        }
        friendsPanel = new FriendsPanel();
        CloseButton closeButton = new CloseButton(0, 0, closeSize);
        // Icône à côté de la barre de recherche (demandé explicitement :
        // "choisir avec une icon a coté de la bar de recherche") — cycle
        // Détaillé -> Compacte -> Grille d'icônes -> Détaillé au clic.
        LayoutSwitchButton layoutButton = new LayoutSwitchButton(0, 0, layoutBtnSize);

        float contentX, contentW, viewportBottom, viewportH;
        // La zone défilante est la SEULE dont les bornes servent aussi de
        // repère au reste (origine des cartes, hauteur du conteneur) — un id
        // manquant ici donnerait un NPE sur le point d'entrée de l'agent,
        // d'où le contrôle explicite plutôt qu'une confiance aveugle au fait
        // que solve() a réussi.
        TaffyLayoutResult.Rect vp = layout == null ? null : layout.get("viewport");
        if (layout != null && vp != null) {
            layout.apply("sidebar", sidebarBg);
            layout.apply("nav.home", navHome);
            layout.apply("nav.settings", navSettings);
            layout.apply("nav.hud", navHud);
            layout.apply("nav.missions", navMissions);
            layout.apply("friends", friendsPanel);
            layout.apply("close", closeButton);
            layout.apply("search", searchField);
            layout.apply("layoutbtn", layoutButton);
            contentX = vp.x;
            contentW = vp.w;
            viewportBottom = vp.y;
            viewportH = vp.h;
        } else {
            // Repli — voir fallbackChrome() : content_core.dll absente ou
            // Taffy en erreur (toujours journalisé par LayoutSolver, jamais
            // silencieux). Volontairement conservé malgré la duplication :
            // cet écran est le POINT D'ENTRÉE de tout l'agent, un layout
            // effondré ici empêcherait même d'atteindre les réglages pour
            // diagnostiquer.
            contentX = SIDEBAR_W + MARGIN;
            contentW = screenWidth - contentX - MARGIN;
            viewportBottom = MARGIN;
            fallbackChrome(sidebarBg, navHome, navSettings, navHud, closeButton, layoutButton,
                closeSize, closeMargin, navH, contentX, contentW, layoutBtnGap, layoutBtnSize);
            viewportH = Math.max(1f, (searchField.y - searchToGridGap) - viewportBottom);
        }

        // Ordre d'AJOUT = ordre de PEINTURE (le fond de sidebar doit rester
        // derrière les items de nav) — le clic, lui, ne dépend plus de cet
        // ordre depuis UiHitTest (voir javadoc de classe).
        widgets.add(sidebarBg);
        widgets.add(navHome);
        widgets.add(navSettings);
        widgets.add(navHud);
        // Sans layout (repli), l'onglet Missions n'a pas de place : taille nulle, invisible.
        widgets.add(navMissions);
        // Sans layout (repli), le panneau garde une taille nulle et ne dessine rien.
        widgets.add(friendsPanel);
        // Après le panneau : il pose les bornes du champ pendant son propre dessin.
        widgets.add(friendSearch);
        widgets.add(closeButton);

        if (missionsTab) {
            // Onglet Missions : toute la zone de droite, sous le titre ; ni
            // recherche de mods ni grille.
            MissionsPanel missions = new MissionsPanel();
            missions.x = contentX;
            missions.y = viewportBottom;
            missions.w = contentW;
            missions.h = Math.max(1f, screenHeight - UiTheme.scaled(64f) - viewportBottom);
            widgets.add(missions);
            modScroll = null;
            return;
        }

        widgets.add(searchField);
        widgets.add(layoutButton);

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
            () -> closeTo(new com.yuyuframe.launcheragent.runtime.ui.ingameui.modrinth.ModrinthContentScreen(UiMainMenuScreen.this)));
        modrinthCard.iconUrl = LauncherModule.icons8("puzzle");
        modrinthCard.favorite = HudConfigStore.loadActionFavorite("modrinth");
        if (filter.isEmpty() || modrinthCard.name.toLowerCase(Locale.ROOT).contains(filter)) filtered.add(modrinthCard);
        for (ModuleGroup g : ModuleRegistry.groups()) {
            if (filter.isEmpty() || g.name.toLowerCase(Locale.ROOT).contains(filter)) filtered.add(g);
        }
        for (LauncherModule m : ModuleRegistry.ungrouped()) {
            if (filter.isEmpty() || m.name.toLowerCase(Locale.ROOT).contains(filter)) filtered.add(m);
        }
        // Membres de groupe mis en favori : carte à part, EN PLUS de celle de
        // leur groupe (2026-08-30, demande explicite — "le module mis en
        // favori apparaisse comme un module a part"). Volontairement pas
        // retirés de leur groupe : la carte du groupe continue de les
        // contenir, celle-ci n'est qu'un raccourci vers leurs réglages (voir
        // emitCards, qui rouvre l'écran du groupe positionné dessus).
        for (LauncherModule m : ModuleRegistry.groupedFavorites()) {
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
        modScroll = new UiScrollContainer(contentX, viewportBottom, contentW, Math.max(1f, viewportH));

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

        boolean separated = GlobalUiSettings.INSTANCE.separateFavorites
            && !favoriteEntries.isEmpty() && !otherEntries.isEmpty();
        List<Object> combined = new ArrayList<>(favoriteEntries.size() + otherEntries.size());
        combined.addAll(favoriteEntries);
        combined.addAll(otherEntries);
        float headerH = sectionHeaderH();

        // ── Arbre de layout de la grille ────────────────────────────────────
        //
        // Résolu SÉPARÉMENT du chrome de l'écran, dans son propre repère local
        // et à hauteur LIBRE (availH = 0 -> Taffy déduit la hauteur naturelle
        // du contenu) : c'est exactement ce dont UiScrollContainer a besoin —
        // il replace lui-même ce contenu dans le viewport réel via un décalage
        // recalculé à chaque frame, seules les positions RELATIVES entre
        // cartes comptent ici.
        //
        // Ce que Taffy remplace concrètement : l'empilement vertical et le
        // cumul d'écarts entre sections, auparavant tenus à la main par un
        // curseur `top` décrémenté à chaque étape (`top -= headerH + CARD_GAP`,
        // puis `top -= CARD_GAP`, puis la valeur de retour de layoutGrid...) —
        // la source d'erreur classique, où oublier un gap décale silencieusement
        // TOUT ce qui suit.
        TaffyNode grid = LayoutSolver.column("grid", CARD_GAP);
        grid.style.width = TaffyStyle.px(cardAreaW);
        if (separated) {
            grid.child(LayoutSolver.box("hdr.fav", cardAreaW, headerH));
            addSectionRows(grid, favoriteEntries, "fav", cols, cardW, rowH, cardLayout);
            // Marge HAUTE en plus du gap régulier de la colonne — reproduit
            // exactement l'ancien double écart entre la fin d'une section et
            // le titre de la suivante (gap + margin = 2 x CARD_GAP), sans le
            // calculer.
            TaffyNode otherHeader = LayoutSolver.box("hdr.oth", cardAreaW, headerH);
            otherHeader.style.margin = new String[]{ TaffyStyle.px(CARD_GAP), "0", "0", "0" };
            grid.child(otherHeader);
            addSectionRows(grid, otherEntries, "oth", cols, cardW, rowH, cardLayout);
        } else {
            // Pas de séparation visuelle à afficher (réglage désactivé, OU
            // rien à séparer — une seule des deux listes non vide) : une
            // seule grille continue, favoris déjà en tête via l'ordre de
            // concaténation.
            addSectionRows(grid, combined, "all", cols, cardW, rowH, cardLayout);
        }

        LayoutSolver.Solved gridLayout = LayoutSolver.solve(grid, cardAreaW, 0f);
        if (gridLayout != null) {
            if (separated) {
                SectionTitle favTitle = new SectionTitle(Lang.tr("Favoris").toUpperCase(Locale.ROOT));
                gridLayout.apply("hdr.fav", favTitle, contentX, 0f);
                modScroll.add(favTitle);
                emitCards(favoriteEntries, "fav", gridLayout, contentX, cardLayout);
                SectionTitle othTitle = new SectionTitle(Lang.tr("Autres modules").toUpperCase(Locale.ROOT));
                gridLayout.apply("hdr.oth", othTitle, contentX, 0f);
                modScroll.add(othTitle);
                emitCards(otherEntries, "oth", gridLayout, contentX, cardLayout);
            } else {
                emitCards(combined, "all", gridLayout, contentX, cardLayout);
            }
        } else {
            // Repli — même raison que pour le chrome (voir plus haut).
            fallbackGrid(separated, favoriteEntries, otherEntries, combined,
                contentX, cardAreaW, headerH, cols, cardW, rowH, cardLayout);
        }

        // APRÈS tous les add() — le clamp dépend de la hauteur de contenu, qui
        // n'est connue qu'une fois la grille peuplée (voir restoreScroll).
        modScroll.restoreScroll(previousScroll);
        // Consommé : l'éclat ne doit jouer qu'UNE fois, pas à chaque
        // reconstruction ultérieure (frappe dans la recherche, resize...).
        pendingChangePulseId = null;
    }

    /**
     * Une petite icône (icons8, 100 px) dessinée nette à {@code size} pixels.
     *
     * <p>La carte graphique ne réduit proprement qu'à moitié : de 100 px à
     * 16 px elle saute des pixels, et l'icône sort pixelisée. On lui donne
     * donc une copie déjà réduite à la bonne taille
     * ({@link UiRemoteImage#scaled}, faite une fois, sur un autre fil), avec
     * sa propre clé de texture par taille. Tant que la copie n'est pas prête,
     * rien n'est dessiné : une image de moins pendant une image vaut mieux
     * qu'un éclat pixelisé.
     */
    private static void drawCrispIcon(UiRenderer renderer, String url, float ix, float iy, float size, float alpha,
                                      int vpWidth, int vpHeight) {
        int n = Math.max(1, Math.round(size));
        BufferedImage img = UiRemoteImage.scaled(url, n, n);
        if (img == null) return;
        // Posée sur des pixels entiers, à sa taille exacte : un demi-pixel de
        // décalage suffirait à la rendre floue.
        renderer.drawIcon(url + "@" + n, img, Math.round(ix), Math.round(iy), n, n, alpha, vpWidth, vpHeight);
    }

    /** Passe sur l'onglet Accueil ({@code false}) ou Missions ({@code true}). */
    private void switchTab(boolean missions) {
        if (missionsTab == missions) return;
        missionsTab = missions;
        lastLayoutWidth = -1; // force rebuildAll() à la prochaine image
    }

    /** Nœud d'un item de navigation — largeur laissée libre : la colonne sidebar l'étire d'elle-même à sa largeur de contenu. */
    private static TaffyNode navNode(String id, float h) {
        TaffyStyle s = new TaffyStyle();
        s.height = TaffyStyle.px(h);
        s.flexShrink = 0f;
        return new TaffyNode(id, s);
    }

    /**
     * Ajoute à {@code grid} une ligne de nœuds par rangée de cartes.
     *
     * <p>Rangées EXPLICITES plutôt que {@code flex-wrap} sur une seule liste :
     * les largeurs de carte sont calculées pour remplir {@code cardAreaW} au
     * pixel près, or la moindre erreur d'arrondi flottant sur cette somme
     * ferait basculer une carte à la ligne suivante — une grille 2 colonnes
     * deviendrait silencieusement une grille 1 colonne. Découper nous-mêmes en
     * rangées de {@code cols} rend le résultat déterministe, et laisse quand
     * même à Taffy tout ce qui compte vraiment ici : l'empilement vertical,
     * les écarts, et l'ancrage des sous-contrôles dans chaque carte.
     */
    private void addSectionRows(TaffyNode grid, List<Object> entries, String prefix,
                                 int cols, float cardW, float rowH, int cardLayout) {
        for (int start = 0; start < entries.size(); start += cols) {
            TaffyNode rowNode = LayoutSolver.row(prefix + ".row:" + start, CARD_GAP);
            rowNode.style.height = TaffyStyle.px(rowH);
            rowNode.style.flexShrink = 0f;
            for (int c = 0; c < cols && start + c < entries.size(); c++) {
                int idx = start + c;
                Object entry = entries.get(idx);
                TaffyNode cardNode = LayoutSolver.box(prefix + ".card:" + idx, cardW, rowH);
                // Sous-contrôles ANCRÉS AUX BORDS de la carte — remplace
                // l'arithmétique `cx + cardW - taille - marge` répétée à chaque
                // agencement : changer la taille d'une carte ne demande plus de
                // repositionner quoi que ce soit.
                if (cardLayout == 2) {
                    float barH = iconGridBarH();
                    float heartSize = iconGridHeartSize();
                    cardNode.child(LayoutSolver.anchored(prefix + ".heart:" + idx, heartSize, heartSize,
                        null, UiTheme.scaled(8f), (barH - heartSize) / 2f, null));
                    if (entry instanceof LauncherModule) {
                        cardNode.child(LayoutSolver.anchored(prefix + ".band:" + idx, cardW, barH,
                            null, null, 0f, 0f));
                    }
                } else if (entry instanceof LauncherModule) {
                    cardNode.child(LayoutSolver.anchored(prefix + ".toggle:" + idx, toggleW(), toggleH(),
                        toggleGapY(), toggleGapX(), null, null));
                }
                rowNode.child(cardNode);
            }
            grid.child(rowNode);
        }
    }

    /**
     * Emplacements résolus d'une carte et de ses sous-contrôles — pivot commun
     * entre le chemin Taffy ({@link #slotsFromTaffy}) et le repli manuel
     * ({@link #slotsManual}), pour que la CRÉATION des widgets ({@link
     * #emitCards}) n'existe qu'en un seul exemplaire. Sans ce pivot, le repli
     * aurait dupliqué toute la logique carte/bascule/cœur/appariement, avec la
     * dérive garantie que ça implique entre deux copies.
     */
    private static final class CardSlot {
        float x, y, w, h;
        /** Bascule d'activation (agencements 0/1) OU bande cliquable (agencement 2) — {@code w <= 0} si cette carte n'en a pas. */
        float tx, ty, tw, th;
        /** Cœur favori — {@code w <= 0} si absent. */
        float hx, hy, hw, hh;
    }

    private List<CardSlot> slotsFromTaffy(List<Object> entries, String prefix, LayoutSolver.Solved solved,
                                           float offsetX, int cardLayout) {
        List<CardSlot> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            CardSlot s = new CardSlot();
            TaffyLayoutResult.Rect card = solved.get(prefix + ".card:" + i);
            if (card == null) return null; // arbre incohérent — repli complet plutôt qu'une grille à trous
            s.x = card.x + offsetX; s.y = card.y; s.w = card.w; s.h = card.h;
            TaffyLayoutResult.Rect toggle = solved.get(prefix + (cardLayout == 2 ? ".band:" : ".toggle:") + i);
            if (toggle != null) { s.tx = toggle.x + offsetX; s.ty = toggle.y; s.tw = toggle.w; s.th = toggle.h; }
            TaffyLayoutResult.Rect heart = solved.get(prefix + ".heart:" + i);
            if (heart != null) { s.hx = heart.x + offsetX; s.hy = heart.y; s.hw = heart.w; s.hh = heart.h; }
            out.add(s);
        }
        return out;
    }

    /**
     * Repli — mêmes formules qu'avant le passage à Taffy (voir historique) :
     * position dérivée de l'indice dans la grille, sous-contrôles calculés
     * depuis les bords de la carte. Conservé parce que cet écran est le POINT
     * D'ENTRÉE de l'agent : si {@code content_core.dll} manque, mieux vaut une
     * grille correcte qu'un menu effondré d'où on ne peut même plus atteindre
     * les réglages pour comprendre ce qui se passe.
     */
    private List<CardSlot> slotsManual(List<Object> entries, float contentX, float startTop,
                                        int cols, float cardW, float rowH, int cardLayout) {
        List<CardSlot> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            int col = i % cols, row = i / cols;
            CardSlot s = new CardSlot();
            s.x = contentX + col * (cardW + CARD_GAP);
            s.y = startTop - row * (rowH + CARD_GAP) - rowH;
            s.w = cardW;
            s.h = rowH;
            if (cardLayout == 2) {
                float barH = iconGridBarH();
                float heartSize = iconGridHeartSize();
                s.hw = heartSize; s.hh = heartSize;
                s.hx = s.x + cardW - heartSize - UiTheme.scaled(8f);
                s.hy = s.y + (barH - heartSize) / 2f;
                if (entry instanceof LauncherModule) {
                    s.tx = s.x; s.ty = s.y; s.tw = cardW; s.th = barH;
                }
            } else if (entry instanceof LauncherModule) {
                s.tw = toggleW(); s.th = toggleH();
                s.tx = s.x + cardW - s.tw - toggleGapX();
                s.ty = s.y + rowH - s.th - toggleGapY();
            }
            out.add(s);
        }
        return out;
    }

    /** Hauteur totale occupée par une section en repli manuel — sert au curseur `top` de {@link #fallbackGrid}. */
    private float manualSectionHeight(int count, int cols, float rowH) {
        int rows = count == 0 ? 0 : (int) Math.ceil(count / (float) cols);
        return rows * (rowH + CARD_GAP);
    }

    private void fallbackGrid(boolean separated, List<Object> favoriteEntries, List<Object> otherEntries,
                               List<Object> combined, float contentX, float cardAreaW, float headerH,
                               int cols, float cardW, float rowH, int cardLayout) {
        float top = 0f;
        if (!separated) {
            emitCards(combined, slotsManual(combined, contentX, top, cols, cardW, rowH, cardLayout), cardLayout);
            return;
        }
        SectionTitle favTitle = new SectionTitle(Lang.tr("Favoris").toUpperCase(Locale.ROOT));
        favTitle.x = contentX; favTitle.y = top - headerH; favTitle.w = cardAreaW; favTitle.h = headerH;
        modScroll.add(favTitle);
        top -= headerH + CARD_GAP;
        emitCards(favoriteEntries, slotsManual(favoriteEntries, contentX, top, cols, cardW, rowH, cardLayout), cardLayout);
        top -= manualSectionHeight(favoriteEntries.size(), cols, rowH) + CARD_GAP;
        SectionTitle othTitle = new SectionTitle(Lang.tr("Autres modules").toUpperCase(Locale.ROOT));
        othTitle.x = contentX; othTitle.y = top - headerH; othTitle.w = cardAreaW; othTitle.h = headerH;
        modScroll.add(othTitle);
        top -= headerH + CARD_GAP;
        emitCards(otherEntries, slotsManual(otherEntries, contentX, top, cols, cardW, rowH, cardLayout), cardLayout);
    }

    private void fallbackChrome(SidebarBackground sidebarBg, SidebarItem navHome, SidebarItem navSettings,
                                 SidebarItem navHud, CloseButton closeButton, LayoutSwitchButton layoutButton,
                                 float closeSize, float closeMargin, float navH,
                                 float contentX, float contentW, float layoutBtnGap, float layoutBtnSize) {
        float navW = SIDEBAR_W - MARGIN * 2;
        sidebarBg.x = 0; sidebarBg.y = 0; sidebarBg.w = SIDEBAR_W; sidebarBg.h = screenHeight;
        navHome.x = MARGIN; navHome.y = screenHeight - UiTheme.scaled(104f); navHome.w = navW; navHome.h = navH;
        navSettings.x = MARGIN; navSettings.y = screenHeight - UiTheme.scaled(134f); navSettings.w = navW; navSettings.h = navH;
        navHud.x = MARGIN; navHud.y = MARGIN; navHud.w = navW; navHud.h = navH;
        closeButton.x = screenWidth - closeMargin - closeSize;
        closeButton.y = screenHeight - closeMargin - closeSize;
        closeButton.w = closeSize; closeButton.h = closeSize;
        searchField.x = contentX;
        searchField.y = screenHeight - UiTheme.scaled(64f) - SEARCH_H;
        searchField.w = Math.min(UiTheme.scaled(380f), contentW - layoutBtnGap - layoutBtnSize);
        searchField.h = SEARCH_H;
        layoutButton.x = searchField.x + searchField.w + layoutBtnGap;
        layoutButton.y = searchField.y;
        layoutButton.w = layoutBtnSize; layoutButton.h = layoutBtnSize;
    }

    private void emitCards(List<Object> entries, String prefix, LayoutSolver.Solved solved, float offsetX, int cardLayout) {
        List<CardSlot> slots = slotsFromTaffy(entries, prefix, solved, offsetX, cardLayout);
        if (slots == null) {
            LauncherLog.err("[UiMainMenuScreen] grille Taffy incomplete (prefixe '" + prefix + "') — cartes non creees");
            return;
        }
        emitCards(entries, slots, cardLayout);
    }

    /**
     * Fait glisser une carte depuis sa place PRÉCÉDENTE si elle existait déjà,
     * et déclenche l'éclat sur celle dont le favori vient de basculer.
     *
     * <p>BUG CORRIGÉ (retour utilisateur : "quand tu mets en favori, ça rejoue
     * juste l'animation d'entrée, ce qui rend le geste incompréhensible,
     * surtout avec les modules en liste séparée") — la cause : basculer un
     * favori appelle {@code rebuildAll()}, qui recrée TOUTES les cartes, donc
     * chacune rejouait son entrée en cascade. Résultat : la grille entière
     * clignotait, et la carte concernée changeait de section sans qu'on voie
     * le déplacement — on ne pouvait pas savoir ce qui venait de se passer.
     *
     * <p>Une carte déjà présente ne fait donc plus une ENTRÉE mais un
     * DÉPLACEMENT : elle repart de son ancienne position et glisse jusqu'à la
     * nouvelle. Une carte réellement nouvelle (filtre de recherche qui change)
     * garde son animation d'entrée — c'est bien une apparition dans ce cas.
     *
     * <p>Seuil de 0,5 px : sans lui, une carte qui n'a pas bougé déclencherait
     * quand même une transition (bruit inutile, et perte de son animation
     * d'entrée légitime au tout premier affichage).
     */
    private void applyReorder(ModCard card, String id, float newX, float newY) {
        float[] prev = prevCardLocalPos.get(id);
        if (prev != null) {
            // Déjà à l'écran avant cette reconstruction : pas d'entrée, quelle
            // que soit la suite — voir ModCard#markAlreadyPresent.
            card.markAlreadyPresent();
            float dx = prev[0] - newX, dy = prev[1] - newY;
            if (Math.abs(dx) > 0.5f || Math.abs(dy) > 0.5f) card.startMove(dx, dy);
        }
        if (id.equals(pendingChangePulseId)) card.playChangePulse();
    }

    /**
     * Crée les cartes et leurs sous-contrôles aux emplacements déjà résolus —
     * SEUL endroit qui construit ces widgets, quel que soit le mode de calcul
     * (Taffy ou repli, voir {@link CardSlot}).
     */
    private void emitCards(List<Object> entries, List<CardSlot> slots, int cardLayout) {
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            CardSlot slot = slots.get(i);
            float cx = slot.x, cy = slot.y, cardW = slot.w, rowH = slot.h;
            String id = entryId(entry);
            cardLocalPos.put(id, new float[]{ cx, cy });

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
            // Démarre après l'arrivée de l'écran lui-même (voir UiScreenBase#uiFrame).
            float enterDelay = 0.12f + UiStagger.delayFor(i, 0.04f, 0.45f);

            if (entry instanceof ModuleGroup) {
                ModuleGroup group = (ModuleGroup) entry;
                ModCard card = new ModCard(cx, cy, cardW, rowH, cardLayout, group.name, group.description, group.shortDescription, group.iconUrl, enterDelay,
                    () -> closeTo(new UiModGroupConfigScreen(UiMainMenuScreen.this, group)));
                modScroll.add(card);
                applyReorder(card, id, cx, cy);
                // Groupes rendus favorisables (demande explicite) — SEUL le
                // cœur existe pour un groupe, pas de bande activer/désactiver
                // (un groupe n'a pas d'état on/off propre, chaque module
                // membre garde le sien, voir UiModGroupConfigScreen) : la
                // bande reste donc TRACK_OFF par défaut (pairedToggle jamais
                // posé ici, voir ModCard#drawIconGrid), comportement déjà
                // existant avant cet ajout, inchangé.
                if (cardLayout == 2) {
                    UiToggle favoriteToggle = new UiToggle(slot.hx, slot.hy, slot.hw, slot.hh, group.favorite,
                        v -> { group.favorite = v; HudConfigStore.save(); pendingChangePulseId = id; rebuildAll(); }).heartStyle();
                    modScroll.add(favoriteToggle);
                    card.pairFavorite(favoriteToggle);
                }
            } else if (entry instanceof ActionCard) {
                ActionCard action = (ActionCard) entry;
                ModCard card = new ModCard(cx, cy, cardW, rowH, cardLayout, action.name, action.description, action.shortDescription, action.iconUrl, enterDelay, action.action);
                modScroll.add(card);
                applyReorder(card, id, cx, cy);
                // Favorisable (demande explicite : "on ne peut pas mettre
                // Modrinth en favori") — même principe que le groupe
                // ci-dessus (cœur seul, pas de bande on/off, une ActionCard
                // n'a pas d'état activé/désactivé). "modrinth" en dur : seule
                // ActionCard existante pour l'instant, voir HudConfigStore.
                // loadActionFavorite/saveActionFavorite si une 2ᵉ apparaît un
                // jour (identifiant à généraliser à ce moment-là).
                if (cardLayout == 2) {
                    UiToggle favoriteToggle = new UiToggle(slot.hx, slot.hy, slot.hw, slot.hh, action.favorite,
                        v -> { HudConfigStore.saveActionFavorite("modrinth", v); pendingChangePulseId = id; rebuildAll(); }).heartStyle();
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
                // Un module MEMBRE D'UN GROUPE n'a pas d'écran de config à lui :
                // ses réglages vivent dans l'écran du groupe. Sa carte (qui
                // n'existe que s'il est favori, voir plus haut) rouvre donc cet
                // écran-là, positionné directement sur sa section — c'est tout
                // l'intérêt du raccourci.
                ModuleGroup ownerGroup = ModuleRegistry.groupOf(mod);
                // Un module non groupé qui n'a NI réglage déclaré NI élément
                // HUD n'a rien à montrer : UiModConfigScreen s'ouvrirait
                // totalement vide (ni onglet, ni ligne — son toggle d'activation
                // vit sur cette carte, pas là-bas), ce qui se lit comme un bug.
                // Sa carte bascule donc simplement le module, comme le fait
                // déjà la bande en mode Grille (2026-08-30, demande explicite :
                // "comment cacher le fait qu'il n'a pas de config"). Concerne
                // aujourd'hui MumbleLink et ShulkerPreview ; tout module futur
                // dans le même cas est couvert automatiquement.
                boolean hasSomethingToShow = ownerGroup != null
                    || !mod.settings().isEmpty()
                    || mod instanceof HudElementOwner;
                // Le Score PvP n'a aucun réglage, mais il a un écran à lui :
                // l'historique des duels.
                Runnable cardAction = mod instanceof com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule
                    ? () -> closeTo(new UiPvpScoreScreen(UiMainMenuScreen.this))
                    : ownerGroup != null
                    ? () -> closeTo(new UiModGroupConfigScreen(UiMainMenuScreen.this, ownerGroup, mod))
                    : hasSomethingToShow
                        ? () -> closeTo(new UiModConfigScreen(UiMainMenuScreen.this, mod))
                        // PAS de rebuildAll() ici (retour utilisateur : "il y a
                        // un mini refresh quand j'active/désactive un module
                        // sans config, il est très visible") — reconstruire la
                        // grille rejoue les animations d'entrée de TOUTES les
                        // cartes. Inutile désormais : les toggles sont liés à
                        // l'état du module (voir UiToggle#boundTo), donc la
                        // bande de la carte suit d'elle-même à la frame
                        // suivante. La liste des cartes, elle, ne dépend pas de
                        // l'activation — seulement des favoris.
                        : () -> { mod.setEnabled(!mod.isEnabled()); HudConfigStore.save(); };
                ModCard card = new ModCard(cx, cy, cardW, rowH, cardLayout, mod.name, mod.description, mod.shortDescription, mod.iconUrl, enterDelay,
                    cardAction);
                modScroll.add(card);
                applyReorder(card, id, cx, cy);

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
                    // Le cœur est un sous-rectangle DANS la zone de la bande
                    // (bien plus grande) — les deux se chevauchent donc pour
                    // de vrai. Résolu par UiHitTest (roadmap Phase 5.6,
                    // "plus petite aire gagne") plutôt que par un ordre
                    // d'ajout précis dans modScroll : peu importe lequel des
                    // deux est ajouté en premier, le cœur (plus petit)
                    // l'emporte toujours sur la bande à cet endroit précis.
                    // boundTo : l'état vient du MODULE, pas d'une copie figée à
                    // la construction — sinon activer ce module depuis l'écran
                    // de son groupe (module groupé favori) laisserait cette
                    // bande afficher l'ancien état. Voir UiToggle#source.
                    UiToggle enableToggle = new UiToggle(slot.tx, slot.ty, slot.tw, slot.th, mod.isEnabled(),
                        v -> { mod.setEnabled(v); HudConfigStore.save(); }).invisibleStyle().boundTo(mod::isEnabled);

                    UiToggle favoriteToggle = new UiToggle(slot.hx, slot.hy, slot.hw, slot.hh, mod.favorite,
                        v -> { mod.favorite = v; HudConfigStore.save(); pendingChangePulseId = id; rebuildAll(); })
                        .heartStyle().boundTo(() -> mod.favorite);

                    modScroll.add(favoriteToggle);
                    modScroll.add(enableToggle);
                    card.pairToggle(enableToggle);
                    card.pairFavorite(favoriteToggle);
                } else {
                    // Position du toggle DÉPENDANTE de l'agencement — Compacte
                    // réutilise l'apparence (et donc la position toggle) de
                    // Détaillé telle quelle (voir commentaire plus haut).
                    // Ancrée par Taffy au coin haut-droit de la carte (voir
                    // addSectionRows) — plus de `cx + cardW - taille - marge` ici.
                    UiToggle toggle = new UiToggle(slot.tx, slot.ty, mod.isEnabled(),
                        v -> { mod.setEnabled(v); HudConfigStore.save(); }).boundTo(mod::isEnabled);
                    modScroll.add(toggle);
                    // Suit le soulèvement au survol de sa carte (voir ModCard#pairToggle) —
                    // sinon il resterait figé pendant que la carte en dessous bouge.
                    card.pairToggle(toggle);
                }
            }
        }
    }

    /** Hauteur d'un titre de section ("FAVORIS"/"AUTRES MODULES", voir rebuildAll()) — voir SectionTitle pour le dessin. */
    private float sectionHeaderH() { return UiTheme.scaled(24f); }

    /** Simple étiquette de section, non cliquable — le texte est déjà préparé (traduit + majuscule) par l'appelant, voir rebuildAll(). */
    private final class SectionTitle extends UiWidget {
        private final String label;
        /** Bornes posées APRÈS coup par le layout (Taffy, ou repli manuel) — voir rebuildAll(). */
        SectionTitle(String label) { super(0f, 0f, 0f, 0f); this.label = label; }

        @Override
        public boolean contains(double mx, double my) { return false; }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            // Titre de section : posé sur le décor, JAMAIS sur une carte —
            // ombre portée obligatoire ici (voir UiTheme.TEXT_SHADOW), sinon
            // "FAVORIS" disparaît purement et simplement sur un ciel clair.
            // TEXT_SECONDARY et non TEXT_MUTED : un atténué se dissout sur un
            // fond variable même avec ombre.
            renderer.drawTextShadowed(label, x, y + h / 2f - UiTheme.scaled(4f),
                UiTheme.TEXT_SECONDARY.multiplyAlpha(clipFade), UiTheme.TEXT_SHADOW.multiplyAlpha(clipFade),
                UiTheme.scaled(0.36f), vpWidth, vpHeight);
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
            // Séparation de profondeur avec le contenu — l'ombre de TOUTE la
            // bande sidebar (pas juste son bord, sinon rectangle dégénéré de
            // largeur nulle) : ne se voit que là où elle déborde du fond plein
            // de la sidebar, donc uniquement comme un dégradé sombre qui
            // mord sur le contenu à droite.
            //
            // Dessinée AVANT le fond (et non plus après) depuis le passage au
            // verre : un panneau de verre est OPAQUE dans ses bornes, il
            // recouvre donc proprement la partie de l'ombre qui tombe sur
            // lui-même — alors qu'une ombre posée PAR-DESSUS le verre en
            // ternirait le flou. Aucun changement visible sur le repli opaque
            // (le fond y était déjà plein et recouvrait la même zone).
            renderer.drawShadow(x, y, x + w, y + h, 0f, 10f, 0f,
                new UiColor(0, 0, 0, 100), vpWidth, vpHeight);

            if (renderer.isGlassAvailable()) {
                // Slab de verre pleine hauteur — la surface la plus teintée de
                // l'écran (GLASS_STRENGTH_SIDEBAR) : c'est le support de la
                // navigation, son texte doit rester lisible quel que soit le
                // décor derrière (ciel clair, neige, lave...).
                renderer.drawGlassPanel(x, y, x + w, y + h, 0f,
                    UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_SIDEBAR, UiTheme.SIDEBAR_BG, vpWidth, vpHeight);
                // Liseré de lumière sur la TRANCHE DROITE (pas le bord haut
                // comme sur une carte) — cette bande touche le haut ET le bas
                // de l'écran : son seul bord "libre", donc le seul qui puisse
                // capter la lumière, est celui qui donne sur le contenu.
                float hairline = Math.max(1f, UiTheme.scaled(1f));
                renderer.drawRoundedRect(x + w - hairline, y, x + w, y + h, 0f,
                    UiTheme.GLASS_HAIRLINE, vpWidth, vpHeight);
            } else {
                // Repli sans Blaze3D — dégradé opaque d'origine (plus clair/
                // teinté violet en haut, SIDEBAR_BG en bas) : c'est LA sidebar
                // qui porte le dégradé "clair en haut, foncé en bas" (pas le
                // fond général de l'écran, qui reste plat, voir UiScreenBase).
                // Gardé en branche explicite plutôt que via le paramètre
                // `fallback` de drawGlassPanel — celui-ci ne prend qu'une
                // couleur PLEINE, incapable d'exprimer un dégradé.
                UiColor top = new UiColor(
                    Math.min(1f, UiTheme.SIDEBAR_BG.r + 0.07f),
                    Math.min(1f, UiTheme.SIDEBAR_BG.g + 0.05f),
                    Math.min(1f, UiTheme.SIDEBAR_BG.b + 0.14f),
                    UiTheme.SIDEBAR_BG.a);
                renderer.drawGradientRect(x, y, x + w, y + h, 0, UiTheme.SIDEBAR_BG, top, vpWidth, vpHeight);
            }
        }
    }

    /** Item de nav sidebar — "action" null = purement visuel (ex: "Accueil", déjà l'écran affiché). */
    private final class SidebarItem extends UiWidget {
        private final String label;
        private final boolean active;
        private final Runnable action;
        /** Icône blanche posée devant l'étiquette (icons8, comme les cartes). */
        private final String iconUrl;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        /** Bornes posées APRÈS coup par le layout (Taffy, ou repli manuel) — voir rebuildAll(). */
        SidebarItem(String label, String iconUrl, boolean active, Runnable action) {
            super(0f, 0f, 0f, UiTheme.scaled(36f));
            this.iconUrl = iconUrl;
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
            // L'étiquette GLISSE vers la droite au survol — le liseré d'accent
            // apparaît au même moment sur le bord gauche, le texte lui "cède la
            // place" au lieu de rester planté dessus. Amplitude minuscule (3px)
            // : au-delà, la nav se met à tressauter à chaque passage de souris.
            float slide = hover * UiTheme.scaled(3f);
            float iconS = UiTheme.scaled(16f);
            float textX = x + UiTheme.scaled(12f) + slide;
            if (iconUrl != null) {
                // Pâle au repos, pleine au survol ou sur l'élément actif : elle suit le texte.
                float iconAlpha = active ? 1f : 0.55f + 0.45f * hover;
                drawCrispIcon(renderer, iconUrl, textX, y + (h - iconS) / 2f, iconS, iconAlpha, vpWidth, vpHeight);
            }
            if (iconUrl != null) textX += iconS + UiTheme.scaled(10f);
            renderer.drawText(Lang.tr(label), textX, y + h / 2f - UiTheme.scaled(5f), textColor, UiTheme.scaled(0.48f), vpWidth, vpHeight);
        }

        @Override
        public void onClick() {
            if (action != null) action.run();
        }
    }

    /**
     * L'onglet Missions (2026-11-01) : les missions du jour et de la semaine,
     * sur le modèle de l'écran Missions du launcher
     * ({@code components/competitive/Challenges.tsx}) — un titre en capitales
     * et son compte à rebours, les paliers du jour en losanges, puis une ligne
     * par mission : anneau de progression (coche verte une fois faite), la
     * phrase, où l'on en est et le mode, l'XP à droite.
     *
     * <p>Ici la place ne manque pas : jour et semaine sont deux cartes côte à
     * côte plutôt qu'une colonne. Données : {@link GameMissions}, par le
     * canal du jeu (mêmes missions que le launcher, {@code GET /v1/pass}).
     *
     * <p>Les anneaux et les losanges sont dessinés une fois en image (Java2D)
     * à leur taille exacte puis gardés : le moteur n'a pas de primitive pour
     * un arc ou un carré tourné.
     */
    private final class MissionsPanel extends UiWidget {
        private static final String ICON_CLOCK = "https://img.icons8.com/ios-filled/100/ffffff/clock.png";
        private static final String ICON_CHECK = "https://img.icons8.com/ios-filled/100/ffffff/checkmark.png";

        MissionsPanel() {
            super(0f, 0f, 0f, 0f);
        }

        private float px(float v) { return UiTheme.scaled(v); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            if (w <= 0f || h <= 0f) return;
            GameMissions.keepFresh();

            String message = null;
            if (!GameMissions.available()) {
                message = Lang.tr("Lance le jeu depuis le launcher YuyuFrame pour voir tes missions");
            } else if (!GameMissions.loaded()) {
                message = GameMissions.error() != null ? GameMissions.error() : Lang.tr("Chargement...");
            } else if (GameMissions.missions().isEmpty()) {
                message = Lang.tr("Les missions arrivent bientôt.");
            }
            if (message != null) {
                float cw = Math.min(w, px(560f));
                float ch = px(120f);
                float cx = x, cy = y + h - ch;
                card(renderer, cx, cy, cw, ch, vpWidth, vpHeight);
                float mw = renderer.textWidth(message, px(0.48f));
                renderer.drawText(message, cx + Math.max(px(22f), (cw - mw) / 2f), cy + ch / 2f - px(5f),
                    UiTheme.TEXT_MUTED, px(0.48f), vpWidth, vpHeight);
                return;
            }

            List<GameMissions.Mission> daily = new ArrayList<>();
            List<GameMissions.Mission> weekly = new ArrayList<>();
            for (GameMissions.Mission m : GameMissions.missions()) {
                if ("daily".equals(m.period)) daily.add(m); else weekly.add(m);
            }
            float gap = px(20f);
            float cardW = Math.min(px(620f), (w - gap) / 2f);
            float top = y + h;
            drawSection(renderer, Lang.tr("Missions du jour"), daily, true, x, top, cardW, mouseX, mouseY, vpWidth, vpHeight);
            drawSection(renderer, Lang.tr("Missions de la semaine"), weekly, false, x + cardW + gap, top, cardW, mouseX, mouseY, vpWidth, vpHeight);
        }

        /** Fond d'une carte : verre sombre arrondi, comme les cartes du launcher. */
        private void card(UiRenderer renderer, float cx, float cy, float cw, float ch, int vpWidth, int vpHeight) {
            renderer.drawShadow(cx, cy, cx + cw, cy + ch, px(16f), px(20f), 0f, new UiColor(0, 0, 0, 110), vpWidth, vpHeight);
            renderer.drawRoundedRect(cx, cy, cx + cw, cy + ch, px(16f), UiTheme.PANEL_BG.multiplyAlpha(0.94f), vpWidth, vpHeight);
            renderer.drawRoundedRect(cx + px(16f), cy + ch - px(1f), cx + cw - px(16f), cy + ch, 0f,
                UiTheme.GLASS_BORDER, vpWidth, vpHeight);
        }

        private void drawSection(UiRenderer renderer, String title, List<GameMissions.Mission> list, boolean daily,
                                 float cx, float top, float cw, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            float pad = px(24f);
            float headerH = px(36f);
            float checkpointsH = daily && !list.isEmpty() ? px(62f) : 0f;
            float rowH = px(68f);
            boolean allDone = !list.isEmpty();
            for (GameMissions.Mission m : list) if (!m.done()) allDone = false;
            boolean showRows = !list.isEmpty() && !(allDone && !daily);
            float bodyH = showRows ? list.size() * rowH : px(70f);
            float ch = pad + headerH + checkpointsH + bodyH + pad * 0.6f;
            float cy = top - ch;
            card(renderer, cx, cy, cw, ch, vpWidth, vpHeight);

            // ── Titre en capitales et compte à rebours ──────────────────────
            float headMid = top - pad - headerH / 2f + px(4f);
            renderer.drawText(UiFont.BOLD, title.toUpperCase(Locale.ROOT), cx + pad, headMid - px(5f),
                UiTheme.TEXT_PRIMARY, px(0.58f), vpWidth, vpHeight);
            if (!list.isEmpty()) {
                String left = countdown(list.get(0).endsAt - System.currentTimeMillis());
                float tw = renderer.textWidth(left, px(0.46f));
                float clock = px(15f);
                float rx = cx + cw - pad - tw;
                renderer.drawText(left, rx, headMid - px(4.5f), UiTheme.TEXT_MUTED, px(0.46f), vpWidth, vpHeight);
                drawCrispIcon(renderer, ICON_CLOCK, rx - clock - px(7f), headMid - clock / 2f, clock, 0.6f, vpWidth, vpHeight);
            }

            float cursor = top - pad - headerH;

            // ── Paliers du jour : un losange par mission faite ─────────────
            if (checkpointsH > 0f) {
                int total = list.size(), done = 0;
                for (GameMissions.Mission m : list) if (m.done()) done++;
                float d = px(38f);
                float mid = cursor - checkpointsH / 2f;
                float left = cx + pad, right = cx + cw - pad - d;
                float step = total > 1 ? (right - left) / (total - 1) : 0f;
                for (int i = 0; i < total; i++) {
                    float dx = left + i * step;
                    if (i < total - 1) {
                        boolean lit = i < done - 1;
                        renderer.drawRoundedRect(dx + d + px(4f), mid - px(1.5f), dx + step - px(4f), mid + px(1.5f), px(1.5f),
                            lit ? UiTheme.ACCENT.multiplyAlpha(0.75f) : new UiColor(255, 255, 255, 22), vpWidth, vpHeight);
                    }
                    boolean filled = i < done;
                    if (filled) {
                        renderer.drawShadow(dx + px(6f), mid - d / 2f + px(6f), dx + d - px(6f), mid + d / 2f - px(6f), px(6f), px(14f), 0f,
                            UiTheme.ACCENT.multiplyAlpha(0.45f), vpWidth, vpHeight);
                    }
                    int n = Math.max(1, Math.round(d));
                    renderer.drawIcon("yf-diamond-" + filled + "-" + n, diamondImage(n, filled), Math.round(dx), Math.round(mid - d / 2f), n, n,
                        1f, vpWidth, vpHeight);
                    String num = String.valueOf(i + 1);
                    float nw = renderer.textWidth(UiFont.BOLD, num, px(0.5f));
                    renderer.drawText(UiFont.BOLD, num, dx + (d - nw) / 2f, mid - px(5f),
                        filled ? UiTheme.TEXT_PRIMARY : new UiColor(255, 255, 255, 90), px(0.5f), vpWidth, vpHeight);
                }
                cursor -= checkpointsH;
            }

            if (!showRows) {
                String text = list.isEmpty() ? Lang.tr("Les missions arrivent bientôt.")
                    : Lang.tr("Toutes les missions de la semaine sont faites.");
                float tw = renderer.textWidth(UiFont.BOLD, text, px(0.44f));
                renderer.drawText(UiFont.BOLD, text, cx + (cw - tw) / 2f, cursor - bodyH / 2f - px(4f),
                    UiTheme.TEXT_MUTED, px(0.44f), vpWidth, vpHeight);
                return;
            }

            // ── Une ligne par mission ───────────────────────────────────────
            for (int i = 0; i < list.size(); i++) {
                GameMissions.Mission m = list.get(i);
                float rowTop = cursor - i * rowH, rowBottom = rowTop - rowH, mid = rowBottom + rowH / 2f;
                if (i > 0) {
                    renderer.drawRoundedRect(cx + pad, rowTop - px(0.5f), cx + cw - pad, rowTop + px(0.5f), 0f,
                        new UiColor(255, 255, 255, 13), vpWidth, vpHeight);
                }
                float ring = px(28f);
                float rx = cx + pad, ry = mid - ring / 2f;
                if (m.done()) {
                    renderer.drawRoundedRect(rx, ry, rx + ring, ry + ring, ring / 2f, UiTheme.SUCCESS.multiplyAlpha(0.2f), vpWidth, vpHeight);
                    drawCrispIcon(renderer, ICON_CHECK, rx + ring * 0.22f, ry + ring * 0.22f, ring * 0.56f, 1f, vpWidth, vpHeight);
                } else {
                    int n = Math.max(1, Math.round(ring));
                    int pct = Math.round(Math.min(1f, m.progress / (float) m.goal) * 50f) * 2;
                    renderer.drawIcon("yf-ring-" + pct + "-" + n, ringImage(n, pct / 100f), Math.round(rx), Math.round(ry), n, n,
                        1f, vpWidth, vpHeight);
                }

                // L'XP à droite, la phrase prend le reste.
                String xp = "+" + String.format(Locale.ROOT, "%,d", m.xp).replace(',', ' ') + " XP";
                float xpW = renderer.textWidth(UiFont.BOLD, xp, px(0.5f));
                renderer.drawText(UiFont.BOLD, xp, cx + cw - pad - xpW, mid - px(5f),
                    m.done() ? UiTheme.SUCCESS : UiTheme.accentLight(), px(0.5f), vpWidth, vpHeight);

                float tx = rx + ring + px(16f);
                float maxW = cx + cw - pad - xpW - px(16f) - tx;
                String phrase = fitText(renderer, UiFont.BOLD, missionText(m), maxW, px(0.52f));
                UiColor titleColor = m.done() ? UiTheme.TEXT_MUTED : UiTheme.TEXT_PRIMARY;
                float titleBase = mid + px(3f);
                renderer.drawText(UiFont.BOLD, phrase, tx, titleBase, titleColor, px(0.52f), vpWidth, vpHeight);
                if (m.done()) {
                    // Barrée une fois faite, comme dans le launcher.
                    float pw = renderer.textWidth(UiFont.BOLD, phrase, px(0.52f));
                    renderer.drawRoundedRect(tx, titleBase + px(5f), tx + pw, titleBase + px(6.5f), 0f,
                        new UiColor(255, 255, 255, 55), vpWidth, vpHeight);
                }
                String sub = m.progress + " / " + m.goal;
                String mode = "mace".equals(m.mode) ? Lang.tr("Masse") : "sword".equals(m.mode) ? Lang.tr("Épée") : "";
                if (!mode.isEmpty()) sub += " · " + mode;
                renderer.drawText(sub, tx, mid - px(16f), UiTheme.TEXT_MUTED, px(0.42f), vpWidth, vpHeight);
            }
        }

        /**
         * La phrase d'une mission, mêmes textes que le launcher
         * ({@code competitive.challenges.kinds}). {@code {n}} : le nombre à
         * atteindre ; {@code {v}} : la valeur de la phrase (note, secondes, RR).
         */
        private String missionText(GameMissions.Mission m) {
            String key;
            switch (m.kind) {
                case "winDuels": key = "Gagne {n} duels"; break;
                case "playDuels": key = "Joue {n} duels classés"; break;
                case "scoreAbove": key = "Fais une note de {v} ou plus"; break;
                case "scoresAbove": key = "Fais {n} duels à {v} ou plus"; break;
                case "streak": key = "Enchaîne {n} victoires"; break;
                case "quickWin": key = "Gagne un duel en moins de {v} s"; break;
                case "longWin": key = "Gagne un duel de plus de {v} s"; break;
                case "foes": key = "Bats {n} adversaires différents"; break;
                case "servers": key = "Joue sur {n} serveurs différents"; break;
                case "bothModes": key = "Gagne à la masse et à l'épée"; break;
                case "rrGain": key = "Gagne {v} RR"; break;
                default: return m.kind;
            }
            return Lang.tr(key).replace("{n}", String.valueOf(m.goal)).replace("{v}", String.valueOf(m.target));
        }

        /** « 08:24:55 » sous un jour, « 3:16:49:05 » (jours en tête) au-delà — comme le launcher. */
        private String countdown(long ms) {
            long total = Math.max(0L, ms / 1000L);
            long d = total / 86400L;
            String hms = String.format(Locale.ROOT, "%02d:%02d:%02d", (total % 86400L) / 3600L, (total % 3600L) / 60L, total % 60L);
            return d > 0 ? d + ":" + hms : hms;
        }

        private String fitText(UiRenderer renderer, UiFont font, String text, float maxW, float scale) {
            if (renderer.textWidth(font, text, scale) <= maxW) return text;
            String s = text;
            while (s.length() > 1 && renderer.textWidth(font, s + "...", scale) > maxW) s = s.substring(0, s.length() - 1);
            return s + "...";
        }
    }

    /** Images d'anneaux et de losanges déjà dessinées, par taille et état (voir MissionsPanel). */
    private static final Map<String, BufferedImage> MISSION_SHAPES = new HashMap<>();

    /** Anneau de progression de {@code n} pixels, rempli à {@code ratio} (0..1), accent sur piste claire. */
    private static BufferedImage ringImage(int n, float ratio) {
        String key = "ring:" + n + ":" + Math.round(ratio * 100f);
        BufferedImage cached = MISSION_SHAPES.get(key);
        if (cached != null) return cached;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            float stroke = Math.max(2f, n * 0.13f);
            float inset = stroke / 2f + 0.5f;
            g.setStroke(new java.awt.BasicStroke(stroke, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            g.setColor(new java.awt.Color(255, 255, 255, 28));
            g.draw(new Ellipse2D.Float(inset, inset, n - inset * 2f, n - inset * 2f));
            if (ratio > 0f) {
                UiColor a = UiTheme.ACCENT;
                g.setColor(new java.awt.Color(a.r, a.g, a.b, 1f));
                // Depuis le haut, dans le sens des aiguilles d'une montre (comme le launcher).
                g.draw(new java.awt.geom.Arc2D.Float(inset, inset, n - inset * 2f, n - inset * 2f, 90f, -360f * ratio,
                    java.awt.geom.Arc2D.OPEN));
            }
        } finally {
            g.dispose();
        }
        MISSION_SHAPES.put(key, img);
        return img;
    }

    /** Losange d'un palier du jour : rempli d'accent une fois atteint, simple contour sinon. */
    private static BufferedImage diamondImage(int n, boolean filled) {
        String key = "diamond:" + n + ":" + filled;
        BufferedImage cached = MISSION_SHAPES.get(key);
        if (cached != null) return cached;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float stroke = Math.max(2f, n * 0.06f);
            float c = n / 2f, r = n / 2f - stroke - n * 0.06f;
            java.awt.geom.Path2D.Float path = new java.awt.geom.Path2D.Float();
            path.moveTo(c, c - r);
            path.lineTo(c + r, c);
            path.lineTo(c, c + r);
            path.lineTo(c - r, c);
            path.closePath();
            UiColor a = UiTheme.ACCENT;
            g.setColor(filled ? new java.awt.Color(a.r, a.g, a.b, 0.32f) : new java.awt.Color(255, 255, 255, 6));
            g.fill(path);
            g.setStroke(new java.awt.BasicStroke(stroke, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            g.setColor(filled ? new java.awt.Color(a.r, a.g, a.b, 1f) : new java.awt.Color(255, 255, 255, 40));
            g.draw(path);
        } finally {
            g.dispose();
        }
        MISSION_SHAPES.put(key, img);
        return img;
    }

    /**
     * Liste des amis YuyuFrame dans la sidebar (2026-11-01), entre la
     * navigation et "Modifier le HUD" — même lecture que le panneau du
     * launcher ({@code components/friends/FriendsPanel.tsx}) : groupes
     * "En jeu", "En ligne", "Hors ligne" repliables, une ligne par ami avec
     * sa tête, son pseudo et ce qu'il fait.
     *
     * <p>Aucun bouton sur les lignes : survoler un ami ouvre une bulle à
     * droite de la sidebar avec tout ce qu'on sait de lui et les gestes
     * possibles (inviter sur son serveur). La bulle reste ouverte tant que la
     * souris est sur la ligne ou sur elle, avec un court délai pour franchir
     * l'écart entre les deux. Elle est dessinée en tout dernier par
     * {@link #uiDraw} ({@link #drawBubble}), au-dessus des cartes.
     *
     * <p>La recherche se replie derrière la loupe de l'en-tête. La liste
     * défile ligne par ligne à la molette : on ne dessine que des lignes
     * entières, donc sans découpe.
     *
     * <p>Données : {@link GameFriends}, par le canal du jeu.
     */
    private final class FriendsPanel extends UiWidget {
        private static final String ICON_SEARCH = "https://img.icons8.com/ios-filled/100/ffffff/search.png";
        private static final String ICON_CLOSE = "https://img.icons8.com/ios-filled/100/ffffff/multiply.png";
        private static final String ICON_OPEN = "https://img.icons8.com/ios-filled/100/ffffff/expand-arrow.png";
        private static final String ICON_CLOSED = "https://img.icons8.com/ios-filled/100/ffffff/forward.png";
        private static final String ICON_FRIENDS = "https://img.icons8.com/ios-filled/100/ffffff/conference-call.png";

        /** Ce qui est sous la souris dans le panneau, pour {@link #onClick()}. */
        private String hoveredAction;
        /** L'ami dont la bulle est ouverte, le haut de sa ligne, et le dernier instant où la souris était sur l'un des deux. */
        private GameFriends.Friend bubbleFriend;
        private float bubbleRowTop;
        private long bubbleSeenAt;
        /** Bornes de la bulle et de son bouton, relevées au dessin ; {@code null} quand fermée. */
        private float[] bubbleRect, inviteRect;
        private int maxScroll;

        FriendsPanel() {
            super(0f, 0f, 0f, 0f);
        }

        private float px(float v) { return UiTheme.scaled(v); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoveredAction = null;
            if (w <= 0f || h <= 0f) {
                friendSearch.w = 0f;
                friendSearch.h = 0f;
                return;
            }
            GameFriends.keepFresh();

            float top = y + h;
            float pad = px(10f);

            // ── En-tête : "Amis", combien en ligne, la loupe ────────────────
            float headerH = px(40f);
            renderer.drawRoundedRect(x + pad, top - px(1f), x + w - pad, top, 0f,
                UiTheme.TEXT_MUTED.multiplyAlpha(0.22f), vpWidth, vpHeight);
            float headerMid = top - headerH / 2f - px(3f);
            // Même icône devant "Amis" que devant les autres entrées de la navigation.
            float titleIcon = px(17f);
            drawCrispIcon(renderer, ICON_FRIENDS, x + pad, headerMid - titleIcon / 2f + px(1f), titleIcon, 1f, vpWidth, vpHeight);
            float titleX = x + pad + titleIcon + px(10f);
            renderer.drawText(UiFont.BOLD, Lang.tr("Amis"), titleX, headerMid - px(5f), UiTheme.TEXT_PRIMARY,
                px(0.56f), vpWidth, vpHeight);
            List<GameFriends.Friend> all = GameFriends.friends();
            int online = 0;
            for (GameFriends.Friend f : all) if (f.online()) online++;
            if (GameFriends.loaded() && !all.isEmpty()) {
                float titleW = renderer.textWidth(UiFont.BOLD, Lang.tr("Amis"), px(0.56f));
                renderer.drawText(online + " " + Lang.tr("en ligne"), titleX + titleW + px(9f), headerMid - px(4f),
                    UiTheme.TEXT_MUTED, px(0.42f), vpWidth, vpHeight);
            }
            float iconS = px(16f);
            float ix = x + w - pad - iconS, iy = headerMid - iconS / 2f + px(1f);
            boolean onSearchIcon = mouseX >= ix - px(5f) && mouseX <= ix + iconS + px(5f)
                && mouseY >= iy - px(5f) && mouseY <= iy + iconS + px(5f);
            if (onSearchIcon) {
                renderer.drawRoundedRect(ix - px(5f), iy - px(5f), ix + iconS + px(5f), iy + iconS + px(5f),
                    UiTheme.RADIUS_SM, UiTheme.SIDEBAR_HOVER, vpWidth, vpHeight);
                hoveredAction = "search";
            }
            String iconUrl = friendSearchOpen ? ICON_CLOSE : ICON_SEARCH;
            drawCrispIcon(renderer, iconUrl, ix, iy, iconS, 1f, vpWidth, vpHeight);

            // ── Recherche, repliée derrière la loupe ────────────────────────
            float cursor = top - headerH;
            if (friendSearchOpen) {
                float fieldH = px(32f);
                friendSearch.x = x + pad * 0.5f;
                friendSearch.w = w - pad;
                friendSearch.h = fieldH;
                friendSearch.y = cursor - fieldH - px(2f);
                cursor -= fieldH + px(10f);
            } else {
                friendSearch.w = 0f;
                friendSearch.h = 0f;
                if (friendSearch.focused()) friendSearch.setFocused(false);
            }

            // ── Résultat d'une invitation, réservé en bas ───────────────────
            String notice = GameFriends.notice();
            float bottom = y;
            if (notice != null) {
                UiColor c = GameFriends.noticeIsError() ? UiTheme.DANGER : UiTheme.SUCCESS;
                drawWrapped(renderer, notice, x + pad, y + px(24f), w - pad * 2f, 2, c, px(0.4f), vpWidth, vpHeight);
                bottom += px(38f);
            }

            float maxW = w - pad * 2f;
            float messageBase = cursor - px(18f);
            String message = null;
            if (!GameFriends.available()) {
                message = Lang.tr("Lance le jeu depuis le launcher YuyuFrame pour voir tes amis");
            } else if (!GameFriends.loaded()) {
                message = GameFriends.error() != null ? GameFriends.error() : Lang.tr("Chargement...");
            } else if (all.isEmpty()) {
                message = Lang.tr("Aucun ami pour l'instant. Ajoute-les depuis le launcher.");
            }
            if (message != null) {
                drawWrapped(renderer, message, x + pad, messageBase, maxW, 5, UiTheme.TEXT_MUTED, px(0.42f), vpWidth, vpHeight);
                keepBubble(mouseX, mouseY);
                return;
            }

            // ── Les lignes : en-têtes de groupe et amis, filtrés ───────────
            String needle = friendSearchOpen ? friendSearch.text().trim().toLowerCase(Locale.ROOT) : "";
            List<Object> entries = new ArrayList<>();
            String[][] groups = {
                { "game", Lang.tr("En jeu") }, { "online", Lang.tr("En ligne") }, { "offline", Lang.tr("Hors ligne") } };
            for (String[] g : groups) {
                List<GameFriends.Friend> members = new ArrayList<>();
                for (GameFriends.Friend f : all) {
                    String group = f.inGame ? "game" : f.online() ? "online" : "offline";
                    if (!group.equals(g[0])) continue;
                    if (!needle.isEmpty() && !f.name.toLowerCase(Locale.ROOT).contains(needle)) continue;
                    members.add(f);
                }
                if (members.isEmpty()) continue;
                entries.add(new String[]{ g[0], g[1] + " · " + members.size() });
                if (!collapsedFriendGroups.contains(g[0])) entries.addAll(members);
            }
            if (entries.isEmpty()) {
                drawWrapped(renderer, Lang.tr("Aucun résultat"), x + pad, messageBase, maxW, 2, UiTheme.TEXT_MUTED,
                    px(0.42f), vpWidth, vpHeight);
                keepBubble(mouseX, mouseY);
                return;
            }

            float groupH = px(32f), rowH = px(58f);
            // Combien d'entrées tiennent à partir de la fin : borne du défilement.
            float avail = cursor - bottom;
            float used = 0f;
            int fromEnd = 0;
            for (int i = entries.size() - 1; i >= 0; i--) {
                float eh = entries.get(i) instanceof String[] ? groupH : rowH;
                if (used + eh > avail) break;
                used += eh;
                fromEnd++;
            }
            maxScroll = Math.max(0, entries.size() - fromEnd);
            friendScroll = Math.max(0, Math.min(friendScroll, maxScroll));

            GameFriends.Friend hoveredFriend = null;
            float hoveredTop = 0f;
            float rowTop = cursor;
            for (int i = friendScroll; i < entries.size(); i++) {
                Object e = entries.get(i);
                float eh = e instanceof String[] ? groupH : rowH;
                if (rowTop - eh < bottom - 0.5f) break;
                float rowBottom = rowTop - eh;
                boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= rowBottom && mouseY <= rowTop;
                if (e instanceof String[]) {
                    String[] g = (String[]) e;
                    if (hovered) hoveredAction = "group:" + g[0];
                    boolean closed = collapsedFriendGroups.contains(g[0]);
                    String chevron = closed ? ICON_CLOSED : ICON_OPEN;
                    float cs = px(11f);
                    float textX = x + pad;
                    UiColor c = hovered ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_SECONDARY;
                    renderer.drawText(UiFont.BOLD, g[1], textX, rowBottom + eh / 2f - px(4.5f), c, px(0.44f), vpWidth, vpHeight);
                    float tw = renderer.textWidth(UiFont.BOLD, g[1], px(0.44f));
                    drawCrispIcon(renderer, chevron, textX + tw + px(6f), rowBottom + eh / 2f - cs / 2f, cs, 1f, vpWidth, vpHeight);
                } else {
                    GameFriends.Friend f = (GameFriends.Friend) e;
                    boolean open = bubbleFriend != null && bubbleFriend.id.equals(f.id);
                    if (hovered || open) {
                        renderer.drawRoundedRect(x, rowBottom + px(1f), x + w, rowTop - px(1f), px(6f),
                            UiTheme.SIDEBAR_HOVER, vpWidth, vpHeight);
                    }
                    if (hovered) {
                        hoveredFriend = f;
                        hoveredTop = rowTop;
                    }
                    drawFriendRow(renderer, f, x + pad * 0.6f, rowBottom, w - pad * 1.2f, eh, vpWidth, vpHeight);
                }
                rowTop = rowBottom;
            }
            if (hoveredFriend != null) {
                bubbleFriend = hoveredFriend;
                bubbleRowTop = hoveredTop;
                bubbleSeenAt = System.currentTimeMillis();
            } else {
                keepBubble(mouseX, mouseY);
            }
            // Liste relue entre-temps : la bulle suit l'ami (nouvel objet), ou se ferme s'il a disparu.
            if (bubbleFriend != null) {
                GameFriends.Friend same = null;
                for (Object e : entries) {
                    if (e instanceof GameFriends.Friend && ((GameFriends.Friend) e).id.equals(bubbleFriend.id)) {
                        same = (GameFriends.Friend) e;
                    }
                }
                bubbleFriend = same;
            }
        }

        /** Garde la bulle tant que la souris est dessus, ou vient de quitter la ligne. */
        private void keepBubble(double mouseX, double mouseY) {
            if (bubbleFriend == null) return;
            if (inside(bubbleRect, mouseX, mouseY)) {
                bubbleSeenAt = System.currentTimeMillis();
                return;
            }
            if (System.currentTimeMillis() - bubbleSeenAt > 160L) {
                bubbleFriend = null;
                bubbleRect = null;
                inviteRect = null;
            }
        }

        private boolean inside(float[] r, double mx, double my) {
            return r != null && mx >= r[0] && mx <= r[2] && my >= r[1] && my <= r[3];
        }

        /** Une ligne : tête avec la pastille d'état, pseudo, et ce qu'il fait. */
        private void drawFriendRow(UiRenderer renderer, GameFriends.Friend f, float rx, float ry, float rw, float rh,
                                   int vpWidth, int vpHeight) {
            float avatar = px(42f);
            float ax = rx, ay = ry + (rh - avatar) / 2f;
            drawHead(renderer, f, ax, ay, avatar, vpWidth, vpHeight);
            if (!f.online()) {
                renderer.drawRoundedRect(ax, ay, ax + avatar, ay + avatar, px(5f), new UiColor(10, 10, 14, 120), vpWidth, vpHeight);
            }
            float dot = px(13f);
            float dx = ax + avatar - dot + px(2f), dy = ay - px(2f);
            renderer.drawRoundedRect(dx - px(2f), dy - px(2f), dx + dot + px(2f), dy + dot + px(2f), dot / 2f + px(2f),
                UiTheme.SIDEBAR_BG, vpWidth, vpHeight);
            renderer.drawRoundedRect(dx, dy, dx + dot, dy + dot, dot / 2f, stateColor(f.state), vpWidth, vpHeight);

            float tx = ax + avatar + px(12f);
            float maxW = rx + rw - tx;
            float mid = ry + rh / 2f;
            UiColor nameColor = f.online() ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED;
            String sub = subtitle(f);
            if (sub == null) {
                renderer.drawText(UiFont.BOLD, fit(renderer, UiFont.BOLD, f.name, maxW, px(0.54f)), tx, mid - px(5f),
                    nameColor, px(0.54f), vpWidth, vpHeight);
                return;
            }
            renderer.drawText(UiFont.BOLD, fit(renderer, UiFont.BOLD, f.name, maxW, px(0.54f)), tx, mid + px(3f),
                nameColor, px(0.54f), vpWidth, vpHeight);
            UiColor subColor = f.inGame ? UiTheme.SUCCESS
                : (f.online() && !f.custom.isEmpty()) ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_MUTED;
            renderer.drawText(fit(renderer, null, sub, maxW, px(0.42f)), tx, mid - px(14f), subColor, px(0.42f), vpWidth, vpHeight);
        }

        /** La deuxième ligne, mêmes règles que {@code PresenceLine} du launcher. */
        private String subtitle(GameFriends.Friend f) {
            if (!f.online()) {
                String seen = f.lastSeen > 0 ? Lang.tr("Vu") + " " + ago(f.lastSeen) : null;
                if (!f.custom.isEmpty() && seen != null) return f.custom + " · " + seen;
                return !f.custom.isEmpty() ? f.custom : seen;
            }
            if (f.inGame) return activityText(f);
            if (!f.custom.isEmpty()) return f.custom;
            if ("launcher".equals(f.activity)) return Lang.tr("Dans le launcher");
            return null;
        }

        private String activityText(GameFriends.Friend f) {
            if (!f.duel.isEmpty()) {
                String[] d = f.duel.split(":", 2);
                String kind = "ranked".equals(d[0]) ? Lang.tr("En duel classé") : Lang.tr("En duel amical");
                String mode = d.length > 1 ? modeName(d[1]) : "";
                return mode.isEmpty() ? kind : kind + " · " + mode;
            }
            String text = "Minecraft " + f.version;
            if (!f.loader.isEmpty() && !"vanilla".equals(f.loader)) {
                text += " · " + f.loader.substring(0, 1).toUpperCase(Locale.ROOT) + f.loader.substring(1);
            }
            return text;
        }

        private String modeName(String mode) {
            switch (mode) {
                case "mace": return Lang.tr("Masse");
                case "sword": return Lang.tr("Épée");
                default: return "";
            }
        }

        /**
         * « il y a 2 h », « hier »… Le mot de devant peut être vide dans une
         * langue qui met tout derrière (« 3 days ago ») : d'où les {@code trim}.
         */
        private String ago(long unixSeconds) {
            long s = Math.max(0L, System.currentTimeMillis() / 1000L - unixSeconds);
            if (s < 60L) return Lang.tr("à l'instant");
            if (s < 3600L) return (Lang.tr("il y a") + " " + (s / 60L) + " min").trim();
            if (s < 86400L) return (Lang.tr("il y a") + " " + (s / 3600L) + " h").trim();
            long days = s / 86400L;
            if (days == 1L) return Lang.tr("hier");
            return (Lang.tr("il y a") + " " + days + " " + Lang.tr("jours")).trim();
        }

        private String stateLabel(GameFriends.Friend f) {
            if (f.inGame) return Lang.tr("En jeu");
            switch (f.state) {
                case "online": return Lang.tr("En ligne");
                case "away": return Lang.tr("Absent");
                case "dnd": return Lang.tr("Ne pas déranger");
                default: return Lang.tr("Hors ligne");
            }
        }

        /** Les couleurs de pastille du launcher ({@code STATUS_COLORS}). */
        private UiColor stateColor(String state) {
            switch (state) {
                case "online": return new UiColor(59, 165, 93, 255);
                case "away": return new UiColor(250, 166, 26, 255);
                case "dnd": return new UiColor(237, 66, 69, 255);
                default: return new UiColor(128, 132, 142, 255);
            }
        }

        /** La tête du compte Minecraft principal (mc-heads, comme le launcher), sinon l'initiale. */
        private void drawHead(UiRenderer renderer, GameFriends.Friend f, float hx, float hy, float size, int vpWidth, int vpHeight) {
            if (!f.minecraftUuid.isEmpty()) {
                String url = "https://mc-heads.net/avatar/" + f.minecraftUuid.replace("-", "") + "/64";
                BufferedImage head = UiRemoteImage.get(url);
                if (head != null) {
                    renderer.drawIcon(url, head, hx, hy, size, size, vpWidth, vpHeight);
                    return;
                }
            }
            renderer.drawRoundedRect(hx, hy, hx + size, hy + size, px(5f), UiTheme.ACCENT_DIM, vpWidth, vpHeight);
            String initial = f.name.isEmpty() ? "?" : f.name.substring(0, 1).toUpperCase(Locale.ROOT);
            float s = size / px(42f) * px(0.62f);
            float tw = renderer.textWidth(UiFont.BOLD, initial, s);
            renderer.drawText(UiFont.BOLD, initial, hx + (size - tw) / 2f, hy + size / 2f - px(5.5f) * size / px(42f),
                UiTheme.TEXT_PRIMARY, s, vpWidth, vpHeight);
        }

        /**
         * La bulle de l'ami survolé, à droite de la sidebar — dessinée par
         * {@link #uiDraw} après tout le reste.
         */
        void drawBubble(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            GameFriends.Friend f = bubbleFriend;
            if (f == null || w <= 0f) {
                bubbleRect = null;
                inviteRect = null;
                return;
            }
            float bw = px(320f), pad = px(16f);
            List<String[]> lines = new ArrayList<>();
            if (f.inGame) lines.add(new String[]{ "accent", activityText(f) });
            else if ("launcher".equals(f.activity)) lines.add(new String[]{ "muted", Lang.tr("Dans le launcher") });
            if (!f.server.isEmpty()) lines.add(new String[]{ "secondary", Lang.tr("Serveur") + " : " + f.server });
            if (!f.custom.isEmpty()) lines.add(new String[]{ "secondary", "« " + f.custom + " »" });
            if (!f.online() && f.lastSeen > 0) lines.add(new String[]{ "muted", Lang.tr("Vu") + " " + ago(f.lastSeen) });
            boolean canInvite = f.online();

            float headS = px(58f), lineH = px(20f), buttonH = px(36f);
            float bh = pad + headS + px(12f) + lines.size() * lineH + (canInvite ? px(10f) + buttonH : 0f) + pad;
            float bx = SIDEBAR_W + px(8f);
            float btop = Math.min(bubbleRowTop + px(6f), screenHeight - px(12f));
            float bbottom = btop - bh;
            if (bbottom < px(12f)) { bbottom = px(12f); btop = bbottom + bh; }
            // Le rectangle « vivant » de la bulle déborde vers la sidebar : la
            // souris qui passe de la ligne à la bulle ne la ferme pas en route.
            bubbleRect = new float[]{ bx - px(10f), bbottom, bx + bw, btop };

            renderer.drawShadow(bx, bbottom, bx + bw, btop, px(10f), px(18f), 0f, new UiColor(0, 0, 0, 150), vpWidth, vpHeight);
            renderer.drawRoundedRect(bx, bbottom, bx + bw, btop, px(10f), UiTheme.PANEL_BG, vpWidth, vpHeight);
            renderer.drawRoundedRect(bx + px(10f), btop - px(3f), bx + bw - px(10f), btop, px(1.5f),
                stateColor(f.state).multiplyAlpha(0.8f), vpWidth, vpHeight);

            float hx = bx + pad, hy = btop - pad - headS;
            drawHead(renderer, f, hx, hy, headS, vpWidth, vpHeight);
            float tx = hx + headS + px(12f), maxW = bx + bw - pad - tx;
            renderer.drawText(UiFont.BOLD, fit(renderer, UiFont.BOLD, f.name, maxW, px(0.64f)), tx, hy + headS / 2f + px(4f),
                UiTheme.TEXT_PRIMARY, px(0.64f), vpWidth, vpHeight);
            float dot = px(11f), dotY = hy + headS / 2f - px(17f);
            renderer.drawRoundedRect(tx, dotY, tx + dot, dotY + dot, dot / 2f, stateColor(f.state), vpWidth, vpHeight);
            renderer.drawText(stateLabel(f), tx + dot + px(7f), dotY + px(1.5f), UiTheme.TEXT_SECONDARY, px(0.44f), vpWidth, vpHeight);

            float ly = hy - px(12f) - lineH + px(4f);
            for (String[] l : lines) {
                UiColor c = "accent".equals(l[0]) ? UiTheme.SUCCESS : "secondary".equals(l[0]) ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_MUTED;
                renderer.drawText(fit(renderer, null, l[1], bw - pad * 2f, px(0.44f)), bx + pad, ly, c, px(0.44f), vpWidth, vpHeight);
                ly -= lineH;
            }

            if (canInvite) {
                float by1 = bbottom + pad, by2 = by1 + buttonH;
                float bx1 = bx + pad, bx2 = bx + bw - pad;
                inviteRect = new float[]{ bx1, by1, bx2, by2 };
                boolean hot = inside(inviteRect, mouseX, mouseY) && !GameFriends.inviting();
                renderer.drawRoundedRect(bx1, by1, bx2, by2, px(7f), hot ? UiTheme.accentLight() : UiTheme.ACCENT, vpWidth, vpHeight);
                String label = GameFriends.inviting() ? Lang.tr("Envoi...") : Lang.tr("Inviter sur mon serveur");
                float lw = renderer.textWidth(UiFont.BOLD, label, px(0.48f));
                renderer.drawText(UiFont.BOLD, label, bx1 + (bx2 - bx1 - lw) / 2f, by1 + buttonH / 2f - px(5f),
                    UiTheme.TEXT_PRIMARY, px(0.48f), vpWidth, vpHeight);
            } else {
                inviteRect = null;
            }
        }

        @Override
        public boolean overlayContains(double mouseX, double mouseY) {
            return bubbleFriend != null && inside(bubbleRect, mouseX, mouseY);
        }

        @Override
        public void pollContinuous(UiInputPoller input) {
            if (bubbleFriend != null && input.leftClicked && inside(inviteRect, input.mouseX, input.mouseY)) {
                GameFriends.invite(bubbleFriend);
            }
            if (input.scrollDelta != 0 && w > 0f && input.mouseX >= x && input.mouseX <= x + w
                    && input.mouseY >= y && input.mouseY <= y + h) {
                friendScroll = Math.max(0, Math.min(maxScroll, friendScroll - Integer.signum(input.scrollDelta)));
            }
        }

        @Override
        public void onClick() {
            String action = hoveredAction;
            if (action == null) return;
            if (action.equals("search")) {
                friendSearchOpen = !friendSearchOpen;
                if (friendSearchOpen) {
                    friendSearch.setFocused(true);
                } else {
                    friendSearch.setText("");
                    friendSearch.setFocused(false);
                }
                friendScroll = 0;
            } else if (action.startsWith("group:")) {
                String g = action.substring(6);
                if (!collapsedFriendGroups.remove(g)) collapsedFriendGroups.add(g);
            }
        }

        /** {@code text} raccourci avec "..." pour tenir dans {@code maxW} ({@code font} nul = police normale). */
        private String fit(UiRenderer renderer, UiFont font, String text, float maxW, float scale) {
            if (width(renderer, font, text, scale) <= maxW) return text;
            String s = text;
            // "..." et non le caractère unique : la police du jeu ne l'a pas (il s'affichait "?").
            while (s.length() > 1 && width(renderer, font, s + "...", scale) > maxW) s = s.substring(0, s.length() - 1);
            return s + "...";
        }

        private float width(UiRenderer renderer, UiFont font, String text, float scale) {
            return font == null ? renderer.textWidth(text, scale) : renderer.textWidth(font, text, scale);
        }

        /** Texte coupé aux mots sur au plus {@code maxLines} lignes, la première ligne à {@code baseline}. */
        private void drawWrapped(UiRenderer renderer, String text, float tx, float baseline, float maxW, int maxLines,
                                 UiColor color, float scale, int vpWidth, int vpHeight) {
            String[] words = text.split(" ");
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && renderer.textWidth(candidate, scale) > maxW) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (line.length() > 0) lines.add(line.toString());
            float lh = px(16f);
            for (int i = 0; i < lines.size() && i < maxLines; i++) {
                String l = lines.get(i);
                if (i == maxLines - 1 && lines.size() > maxLines) l = fit(renderer, null, l + " ...", maxW, scale);
                renderer.drawText(l, tx, baseline - i * lh, color, scale, vpWidth, vpHeight);
            }
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
        /**
         * Éclat joué au clic (voir onClick/draw) — {@link UiTransition} et non
         * {@link UiAnimatedFloat} : c'est un événement qui se joue UNE fois et
         * se termine, pas une valeur qui converge vers une cible. {@code
         * BACK_OUT} plutôt que le {@code EASE_OUT_CUBIC} utilisé partout
         * ailleurs jusqu'ici : il dépasse la cible avant de revenir, ce qui
         * donne au clic un vrai "rebond" — un fondu monotone ne se ressent pas
         * comme une réaction à un geste.
         */
        private final UiTransition clickAnim = new UiTransition(0.42f, 0f, UiEasing.BACK_OUT);

        /**
         * Glissement de l'ANCIENNE vers la NOUVELLE place quand la grille est
         * réordonnée sans que la carte disparaisse (mise en favori — voir
         * {@link #startMove}).
         *
         * <p>Technique classique "FLIP" : le layout final est déjà calculé
         * (Taffy vient de le poser), on RÉINJECTE l'écart avec l'ancienne
         * position et on le résorbe — la carte semble glisser alors qu'elle
         * est déjà, logiquement, à sa destination.
         *
         * <p>{@code CUBIC_OUT} et non {@code BACK_OUT} : un dépassement ferait
         * cogner la carte contre ses voisines à l'arrivée. Un déplacement doit
         * décélérer proprement, le rebond est réservé aux retours de geste
         * ponctuels (voir {@link #clickAnim}).
         */
        private final UiTransition moveAnim = new UiTransition(0.38f, 0f, UiEasing.CUBIC_OUT);
        private float moveDx, moveDy;
        private boolean moving;

        /**
         * Marque cette carte comme DÉJÀ PRÉSENTE avant la reconstruction :
         * elle ne doit pas jouer d'animation d'entrée, qu'elle ait bougé ou non.
         *
         * <p>BUG CORRIGÉ (retour utilisateur : "à la fin des animations de
         * changement comme le favori, ou quand tu changes le type de rendu, ça
         * rejoue l'animation d'entrée") — la première version se contentait de
         * FORCER {@code t = 1} tant que la carte se déplaçait, sans jamais
         * consulter {@code enterAnim}. Or ne pas lire une {@link UiTransition}
         * ne la met pas en pause : elle reste à {@code progress = 0}. À la fin
         * du déplacement, le code recommençait donc à la lire — et elle jouait
         * son entrée COMPLÈTE à ce moment-là, d'où l'animation parasite juste
         * après le glissement. Il faut la TERMINER explicitement.
         *
         * <p>Appelé pour toute carte qui existait déjà, PAS seulement pour
         * celles qui bougent : une carte restée exactement à la même place lors
         * d'un changement d'agencement n'"apparaît" pas davantage que ses
         * voisines qui glissent — sinon elle serait la seule à clignoter.
         */
        void markAlreadyPresent() {
            enterAnim.snapToEnd();
        }

        /**
         * @param dx/dy écart ANCIENNE position moins NOUVELLE, dans le repère
         *        local de la grille. Voir {@link #markAlreadyPresent} pour la
         *        neutralisation de l'entrée, faite séparément.
         */
        void startMove(float dx, float dy) {
            this.moveDx = dx;
            this.moveDy = dy;
            this.moving = true;
            this.moveAnim.replay();
        }

        /** Éclat d'accent sur la carte dont le favori vient de basculer — repère "c'est celle-ci qui a bougé". */
        void playChangePulse() {
            clickAnim.replay();
        }

        /** {@code name}/{@code description} générique — utilisée aussi bien pour un {@link LauncherModule} que pour un {@link ModuleGroup} (voir rebuildAll). */
        ModCard(float x, float y, float w, float h, int layoutMode, String name, String description, String shortDescription, String iconUrl, float enterDelay, Runnable onOpen) {
            super(x, y, w, h);
            this.layoutMode = layoutMode;
            this.cardName = name;
            this.cardDescription = description;
            this.cardShortDescription = shortDescription;
            this.cardIconUrl = iconUrl;
            this.onOpen = onOpen;
            this.enterAnim = new UiTransition(0.5f, enterDelay, UiEasing.QUART_OUT);
            this.enterAnim.show();
        }

        /**
         * X d'origine des widgets appariés, capturé à l'appariement.
         *
         * <p>PIÈGE : {@code UiScrollContainer.applyOffsets()} réécrit {@code y}
         * à chaque frame (base + décalage de défilement) mais NE TOUCHE JAMAIS
         * {@code x}. Un {@code x += ...} par frame s'ACCUMULERAIT donc
         * indéfiniment, et le widget dériverait hors de l'écran — alors que le
         * même {@code y += ...} est sans danger, puisque écrasé à la frame
         * suivante. D'où l'affectation ABSOLUE depuis cette base pour l'axe X.
         */
        private float pairedToggleBaseX, pairedFavoriteBaseX;

        void pairToggle(UiToggle toggle) {
            this.pairedToggle = toggle;
            this.pairedToggleBaseX = toggle.x;
            // Voir UiToggle#useExternalAlphaOnly — l'opacité de ce toggle
            // vient ENTIÈREMENT de externalAlpha (poussé chaque frame dans
            // draw() ci-dessous), plus jamais de son propre clipFade (calculé
            // sur SA taille, différente de celle de la carte).
            toggle.useExternalAlphaOnly();
        }

        /** Même principe que {@link #pairToggle} pour le cœur favori (mode Grille uniquement) — widget séparé, doit suivre le même soulèvement/fondu que la carte. */
        void pairFavorite(UiToggle favorite) {
            this.pairedFavorite = favorite;
            this.pairedFavoriteBaseX = favorite.x;
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

        /** Courbe de la montée d'entrée : rapide, un léger dépassement, puis se pose. */
        private float enterRise(float t) {
            t = Math.max(0f, Math.min(1f, t));
            float c1 = 1.2f, c3 = c1 + 1f, u = t - 1f;
            return 1f + c3 * u * u * u + c1 * u * u;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            // eased() peut légèrement dépasser 1.0 avec certaines courbes
            // (pas EASE_OUT_CUBIC ici, mais clampé quand même par prudence :
            // un alpha > 1 serait silencieusement ignoré par le shader, mais
            // autant rester explicite) — glissement vers le HAUT (Y croissant
            // vers le haut dans ce repère, voir UiRenderer) : la carte part
            // d'une position plus BASSE (y plus petit) et remonte vers y.
            // Une carte qui SE DÉPLACE n'entre pas : `t` est forcé à 1 (pleine
            // opacité, aucun glissement d'entrée) et enterAnim n'est même pas
            // consultée. C'est LE correctif du geste illisible signalé — mettre
            // en favori rejouait l'entrée de toute la grille au lieu de montrer
            // la carte changer de place.
            float t = moving ? 1f : Math.max(0f, Math.min(1f, enterAnim.eased()));
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

            // Reste de l'écart à résorber (voir startMove) — 1 au départ,
            // 0 à l'arrivée. eased() fait avancer l'horloge : un seul appel
            // par frame, d'où la lecture dans une locale.
            float moveRemain = 0f;
            if (moving) {
                moveRemain = 1f - moveAnim.eased();
                if (moveAnim.isFinished()) moving = false;
            }
            float mdx = moveDx * moveRemain, mdy = moveDy * moveRemain;

            float drawX = x + mdx;
            // Montée d'entrée plus ample, qui dépasse un peu sa place avant de s'y poser.
            float rise = moving ? 1f : enterRise(enterAnim.progress());
            float drawY = y + mdy - (1f - rise) * UiTheme.scaled(34f) + hoverT * HOVER_LIFT_PX;
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
                // + le glissement de réordonnancement (mdx/mdy) : sans lui, la
                // bande resterait plantée à la position d'ARRIVÉE pendant que
                // la carte glisse encore vers elle. X en ABSOLU depuis la base
                // (voir pairedToggleBaseX — un += y dériverait), Y en relatif
                // (réécrit chaque frame par applyOffsets).
                pairedToggle.x = pairedToggleBaseX + mdx;
                pairedToggle.y += mdy + hoverT * HOVER_LIFT_PX;
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
                pairedFavorite.x = pairedFavoriteBaseX + mdx;
                pairedFavorite.y += mdy + hoverT * HOVER_LIFT_PX;
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
            renderer.drawShadow(drawX, drawY - shadowOff, drawX + w, drawY + h - shadowOff, UiTheme.RADIUS_MD,
                UiTheme.scaled(18f) + hoverT * UiTheme.scaled(6f), UiTheme.scaled(3f),
                shadowColor, vpWidth, vpHeight);

            // Carte de verre — le survol n'éclaircit plus un aplat mais
            // DÉTEND la teinte (moins de teinte = plus de décor flouté visible
            // à travers) : sur du verre, "la carte s'allume au survol" se lit
            // comme un matériau qui devient plus transparent, pas comme un gris
            // qui monte d'un cran. L'alpha (fondu de bord de zone défilante /
            // apparition en cascade) passe par la couleur de repli, qui pilote
            // AUSSI l'opacité du verre — voir UiRenderer.drawGlassPanel.
            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hoverT).multiplyAlpha(alpha);
            float glassStrength = UiTheme.GLASS_STRENGTH_CARD - hoverT * 0.1f;
            // Contour posé DERRIÈRE le panneau (voir la variante à contour de
            // drawGlassPanel) — drawRoundedRectBorder est inerte sur era E, ses
            // appels GL bruts corrompaient l'état GPU.
            UiColor cardBorder = renderer.isGlassAvailable()
                ? UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.GLASS_BORDER_HOVER, hoverT).multiplyAlpha(alpha)
                : null;
            renderer.drawGlassPanel(drawX, drawY, drawX + w, drawY + h, UiTheme.RADIUS_MD,
                UiTheme.GLASS_TINT, glassStrength, bg,
                cardBorder, Math.max(1f, UiTheme.scaled(1f)), vpWidth, vpHeight);

            // Éclat de clic — s'étend brièvement sous la carte puis s'efface
            // (BACK_OUT : dépasse la taille cible avant de revenir, ce qui
            // donne le "rebond" qu'un simple fondu n'a pas). Dessiné APRÈS le
            // fond mais AVANT le contour, pour rester contenu dans la carte.
            // eased() FAIT AVANCER l'horloge interne (voir UiTransition#progress)
            // — appelé exactement une fois par frame, puis isFinished() lit
            // l'état fraîchement avancé. Le garde passe par isFinished() et NON
            // par la valeur elle-même : BACK_OUT DÉPASSE 1 au milieu du rebond,
            // donc un test "clickT < 1" ferait disparaître puis réapparaître
            // l'éclat en plein vol. Au repos (jamais cliqué) isFinished() est
            // déjà vrai, rien n'est dessiné.
            float clickT = clickAnim.eased();
            if (!clickAnim.isFinished()) {
                float spread = UiTheme.scaled(3f) * clickT;
                // Clampé : le dépassement de BACK_OUT rendrait (1 - clickT)
                // négatif, donc un alpha négatif silencieusement ignoré.
                float glow = Math.max(0f, 1f - clickT);
                renderer.drawRoundedRect(drawX - spread, drawY - spread, drawX + w + spread, drawY + h + spread,
                    UiTheme.RADIUS_MD + spread,
                    UiTheme.ACCENT.withAlpha(0.30f * glow * alpha), vpWidth, vpHeight);
            }

            if (renderer.isGlassAvailable()) {
                // Tranche haute éclairée — signature du verre épais (macOS/iOS),
                // conservée EN PLUS du contour : le contour délimite, ce liseré
                // donne l'épaisseur. Retiré aux extrémités (RADIUS_MD) pour ne
                // pas déborder sur les coins arrondis.
                float hairline = Math.max(1f, UiTheme.scaled(1f));
                renderer.drawRoundedRect(drawX + UiTheme.RADIUS_MD, drawY + h - hairline, drawX + w - UiTheme.RADIUS_MD, drawY + h, 0f,
                    UiTheme.GLASS_HAIRLINE.multiplyAlpha(alpha), vpWidth, vpHeight);
            }

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
                drawIconGrid(renderer, displayName, displayDescription, initial, drawX, drawY, alpha, mouseX, mouseY, vpWidth, vpHeight);
            } else {
                drawDetailed(renderer, displayName, displayDescription, initial, drawX, drawY, alpha, vpWidth, vpHeight);
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
                renderer.drawIcon(LauncherModule.ICON_LOCAL_CROSSHAIR, crosshairImage(px), ix, iy, size, size,
                    alpha * UiTheme.ICON_ALPHA, vpWidth, vpHeight);
                return;
            }
            BufferedImage icon = cardIconUrl != null ? UiRemoteImage.get(cardIconUrl) : null;
            if (icon != null) {
                iconFade.markReady();
                renderer.drawIcon(cardIconUrl, icon, ix, iy, size, size, iconFade.alpha() * alpha * UiTheme.ICON_ALPHA, vpWidth, vpHeight);
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
                float drawX, float drawY, float alpha, int vpWidth, int vpHeight) {
            this.tooltip = null;
            float iconSize = UiTheme.scaled(36f);
            float pad = UiTheme.scaled(12f);
            drawIconOrInitial(renderer, drawX + pad, drawY + h - iconSize - pad, iconSize, initial, alpha, vpWidth, vpHeight);

            float textX = drawX + pad + iconSize + pad;
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
            float textMaxW = (drawX + w) - textX - pad;
            renderer.drawText(renderer.truncate(displayName, UiTheme.scaled(0.42f), textMaxW), textX, drawY + h - UiTheme.scaled(26f), UiTheme.TEXT_PRIMARY.multiplyAlpha(alpha), UiTheme.scaled(0.42f), vpWidth, vpHeight);

            // Description en TEXTE RICHE avec retour à la ligne (voir
            // UiRichText) plutôt qu'une troncature à "..." sur une seule ligne :
            // une description coupée en plein mot n'apprend rien, deux lignes
            // complètes disent le nécessaire. Le nom, lui, reste tronqué — un
            // titre doit tenir sur UNE ligne pour que les cartes gardent le
            // même rythme vertical.
            float descScale = UiTheme.scaled(0.4f);
            UiRichText.Layout descLayout = descriptionLayout(displayDescription, textMaxW, descScale);
            if (descLayout != null) {
                // yTop = HAUT du bloc (voir UiRichText#draw), d'où le +
                // hauteur de ligne par rapport à l'ancienne base de texte.
                UiRichText.draw(renderer, descLayout, textX, drawY + h - UiTheme.scaled(34f), alpha, vpWidth, vpHeight);
            }
        }

        // ── Cache du layout de description ──────────────────────────────────
        //
        // UiRichText.layout() est un calcul COMPLET (découpe en mots, mesure,
        // retour à la ligne) — sa propre javadoc impose de le mettre en cache
        // et de ne le refaire que si le texte ou la largeur changent. Ces
        // cartes sont redessinées à CHAQUE frame : sans ce cache, on relancerait
        // la découpe de tous les paragraphes visibles 60 fois par seconde.
        //
        // La couleur ne fait PAS partie de la clé : elle n'influence pas les
        // positions, et l'estompement passe par le paramètre alpha de draw()
        // (ajouté pour ce cas précis) — sinon le cache serait invalidé à chaque
        // frame pendant un fondu, c'est-à-dire exactement quand il sert.
        private UiRichText.Layout cachedDescLayout;
        private String cachedDescText;
        private float cachedDescWidth = -1f, cachedDescScale = -1f;

        private UiRichText.Layout descriptionLayout(String text, float maxWidth, float scale) {
            if (text == null || text.isEmpty()) return null;
            if (cachedDescLayout != null && text.equals(cachedDescText)
                    && maxWidth == cachedDescWidth && scale == cachedDescScale) {
                return cachedDescLayout;
            }
            cachedDescText = text;
            cachedDescWidth = maxWidth;
            cachedDescScale = scale;
            // 2 lignes MAX — la carte a une hauteur fixe (CARD_H) : sans borne,
            // une description longue passerait à 3 lignes et déborderait sous
            // la carte, par-dessus celle du dessous.
            cachedDescLayout = UiRichText.layout(
                java.util.Collections.singletonList(UiTextSpan.plain(text, UiTheme.TEXT_SECONDARY)),
                maxWidth, scale, DESC_MAX_LINES);
            return cachedDescLayout;
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
                float drawX, float drawY, float alpha, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            this.tooltip = displayDescription;
            float barH = iconGridBarH();
            float pad = UiTheme.scaled(8f);

            // Bande cliquable (voir pairedToggle, invisibleStyle — ce
            // dessin-ci est SA seule apparence) — couleur reflète l'état
            // actif/inactif du mod, pas juste décorative. Rayon PAR COIN
            // (voir UiRenderer#drawRoundedRect à 4 rayons) : seuls les 2
            // coins bas sont arrondis, alignés sur ceux de la carte (même
            // radius, même bord bas — voir le fond générique de draw(), déjà
            // dessiné avant l'appel ici). REMPLACE l'ancien hack "rect arrondi
            // + rect plat par-dessus" (2 draws superposés) qui causait des
            // artefacts de chevauchement au raccord — retour utilisateur.
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
            UiColor barColor = UiColor.lerp(barBase, barHovered, barHoverT);

            // RESPIRATION — uniquement quand le module est ACTIF : c'est ce qui
            // porte le sens (un module allumé "vit", un module éteint est
            // inerte). Faire respirer les deux états n'aurait rien signalé du
            // tout, juste ajouté du mouvement partout.
            //
            // Oscille vers la variante CLAIRE de l'accent, jamais vers du blanc
            // ni vers une autre teinte : la bande doit rester identifiable
            // comme "accent" à chaque instant du cycle — une respiration qui
            // change la teinte se lirait comme un changement d'état, pas comme
            // une pulsation.
            //
            // L'amplitude retombe à mesure que le survol monte (1 - barHoverT) :
            // au survol, la couleur de survol devient le signal utile, et deux
            // effets superposés au même endroit se parasitent — le curseur
            // "fige" naturellement l'élément qu'il désigne.
            if (enabled) {
                float breath = UiBreathe.wave(BAR_BREATH_PERIOD_S) * BAR_BREATH_AMPLITUDE * (1f - barHoverT);
                barColor = UiColor.lerp(barColor, UiTheme.accentLight(), breath);
            }
            renderer.drawRoundedRect(drawX, drawY, drawX + w, drawY + barH,
                UiTheme.RADIUS_MD, UiTheme.RADIUS_MD, 0f, 0f, barColor.multiplyAlpha(alpha), vpWidth, vpHeight);

            // Icône centrée dans la zone au-dessus de la bande — réduite
            // (retour utilisateur : "met les icônes plus petites pour la
            // grille") : occupait quasiment toute la zone disponible avant
            // (juste la marge `pad` en moins), désormais une fraction fixe
            // de cette zone, avec la marge résultante répartie tout autour.
            float iconAreaH = h - barH;
            float iconSize = Math.max(UiTheme.scaled(18f), Math.min(w, iconAreaH) * 0.55f);
            float iconX = drawX + (w - iconSize) / 2f;
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
            renderer.drawText(truncName, drawX + pad, drawY + barH / 2f - UiTheme.scaled(4f),
                // Un blanc adouci, pas le blanc pur : il tranchait trop sur la bande (2026-10-11).
                new UiColor(0.84f, 0.84f, 0.88f, 1f).multiplyAlpha(alpha), nameScale, vpWidth, vpHeight);
        }

        @Override
        public void onClick() {
            // Éclat déclenché AVANT l'action : celle-ci ouvre en général un
            // autre écran (closeTo), donc l'animation ne serait jamais vue si
            // elle partait après. Elle reste visible le temps de la transition
            // de sortie de cet écran.
            clickAnim.replay();
            onOpen.run();
        }
    }

    private final class CloseButton extends UiWidget {
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);
        /** Voir ModCard#clickAnim — même motif (événement ponctuel, rebond BACK_OUT). */
        private final UiTransition clickAnim = new UiTransition(0.4f, 0f, UiEasing.BACK_OUT);

        CloseButton(float x, float y, float size) { super(x, y, size, size); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float hoverT = hoverAnim.get();

            // Le bouton GRANDIT légèrement au survol — un simple changement de
            // couleur passait inaperçu sur du verre (le fond bouge peu). Le
            // grossissement se fait autour du CENTRE (d'où le décalage de la
            // moitié), sinon le bouton semblerait glisser vers le bas-droite.
            float grow = hoverT * UiTheme.scaled(2f);
            float bx = x - grow, by = y - grow, bw = w + grow * 2f, bh = h + grow * 2f;

            UiColor bg = UiColor.lerp(UiTheme.CARD_BG, UiTheme.DANGER.multiplyAlpha(0.55f), hoverT);
            // Vire au ROUGE au survol plutôt qu'au blanc : sur un bouton de
            // fermeture, le contour est le seul endroit où signaler que
            // l'action est destructrice avant le clic.
            UiColor closeBorder = renderer.isGlassAvailable()
                ? UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.DANGER, hoverT) : null;
            renderer.drawGlassPanel(bx, by, bx + bw, by + bh, UiTheme.RADIUS_SM,
                UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD, bg,
                closeBorder, Math.max(1f, UiTheme.scaled(1f)), vpWidth, vpHeight);

            float clickT = clickAnim.eased();
            if (!clickAnim.isFinished()) {
                float spread = UiTheme.scaled(4f) * clickT;
                renderer.drawRoundedRect(bx - spread, by - spread, bx + bw + spread, by + bh + spread,
                    UiTheme.RADIUS_SM + spread,
                    UiTheme.DANGER.withAlpha(0.35f * Math.max(0f, 1f - clickT)), vpWidth, vpHeight);
            }

            String label = "x";
            float scale = UiTheme.scaled(0.45f);
            float tw = renderer.textWidth(label, scale);
            UiColor labelColor = UiColor.lerp(UiTheme.TEXT_SECONDARY, UiTheme.TEXT_PRIMARY, hoverT);
            renderer.drawText(label, bx + (bw - tw) / 2f, by + bh / 2f - UiTheme.scaled(5f), labelColor, scale, vpWidth, vpHeight);
        }

        @Override
        public void onClick() {
            clickAnim.replay();
            closeTo(lastScreen);
        }
    }
}
