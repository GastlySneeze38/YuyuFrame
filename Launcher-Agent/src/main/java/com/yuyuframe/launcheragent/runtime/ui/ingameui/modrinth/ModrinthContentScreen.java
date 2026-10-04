package com.yuyuframe.launcheragent.runtime.ui.ingameui.modrinth;

import com.yuyuframe.launcheragent.runtime.content.ContentBridge;
import com.yuyuframe.launcheragent.runtime.content.ModrinthJson;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAsyncFade;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.anim.UiEasing;
import com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.layout.LayoutSolver;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyNode;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle;
import com.yuyuframe.launcheragent.apigraphic.anim.UiStagger;
import com.yuyuframe.launcheragent.apigraphic.anim.UiTransition;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTextField;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recherche/installation de contenu Modrinth (resource packs ET shader
 * packs, bascule via des onglets en haut de l'écran — voir {@link ContentKind}
 * et {@link #switchKind}, fusionnés en un seul écran depuis les anciennes
 * classes séparées {@code ModrinthResourcePackScreen}/
 * {@code ModrinthShaderPackScreen}) — dessiné avec NOTRE pipeline graphique
 * (UiScreenBase/UiRenderer/UiWidget), PAS l'ancien système
 * {@code screen.ResourcePackSearchScreen}/{@code ShaderPackSearchScreen}
 * (compilait contre les stubs Screen/Text vanilla + {@code ScreenHelper}, qui
 * codait en dur les noms de classes obfusquées 1.21.11 — cassé sur 1.8.9,
 * confirmé en jeu : NoSuchMethodError sur le constructeur ET
 * ClassNotFoundException("yh") dans ScreenHelper.literal()). Notre propre
 * pipeline est DÉJÀ version-générique (UiMainMenuScreen tourne identiquement
 * sur 1.8.9 et 1.21.11) — l'ancien système (screens + ScreenHelper + les
 * Mixins PackScreenMixin/GameMenuScreenMixin qui les ouvraient) a été
 * SUPPRIMÉ, ce chemin-ci est désormais la SEULE voie d'accès.
 *
 * Icônes : chargées EN MÉMOIRE UNIQUEMENT depuis leur URL Modrinth (voir
 * {@link UiRemoteImage}, fetch HTTPS direct + décodage côté Rust incl. WebP,
 * aucune écriture disque) puis affichées via {@link UiRenderer#drawIcon},
 * version-générique (Blaze3D sur era E, GL classique ailleurs).
 */
public final class ModrinthContentScreen extends UiScreenBase {

    /** Garde-fou : au-delà, on ne propose plus de pages (personne n'ira, et l'offset resterait raisonnable). */
    private static final int MAX_PAGES = 500;

    /** Une page de résultats telle que rendue par le thread de recherche — résultats et total voyagent ensemble. */
    private static final class SearchResult {
        final List<ModrinthJson.Hit> hits;
        final long total;

        SearchResult(List<ModrinthJson.Hit> hits, long total) {
            this.hits = hits;
            this.total = total;
        }
    }

    /**
     * Un onglet = un {@code project_type} Modrinth + son dossier d'install +
     * ses textes — remplace les anciennes sous-classes
     * {@code ModrinthResourcePackScreen}/{@code ModrinthShaderPackScreen}
     * (mêmes valeurs, mais comme DONNÉES commutables au lieu de deux CLASSES
     * distinctes, pour permettre une bascule en place dans le même écran).
     */
    public enum ContentKind {
        // Catégories Modrinth réelles (voir api.modrinth.com/v2/tag/category)
        // — sous-ensemble curaté des plus utiles pour chaque type plutôt que
        // la liste complète (une vingtaine chacune) : un panneau de filtres
        // avec TOUTES les catégories serait plus encombrant qu'utile.
        RESOURCE_PACK("resourcepack", "resourcepacks", "Rechercher un resource pack...", "Resource Packs", "Modrinth · Resource Packs",
            new String[]{"16x", "32x", "64x", "128x", "256x", "realistic", "vanilla-like", "themed", "simplistic", "modded"}),
        SHADER("shader", "shaderpacks", "Rechercher un shader pack...", "Shaders", "Modrinth · Shaders",
            new String[]{"realistic", "vanilla-like", "fantasy", "cinematic", "colored-lighting", "shadows", "low", "high"});

        final String searchType, installDirName, searchPlaceholder, tabLabel, headerTitle;
        final String[] categories;

        ContentKind(String searchType, String installDirName, String searchPlaceholder, String tabLabel, String headerTitle, String[] categories) {
            this.searchType = searchType;
            this.installDirName = installDirName;
            this.searchPlaceholder = searchPlaceholder;
            this.tabLabel = tabLabel;
            this.headerTitle = headerTitle;
            this.categories = categories;
        }
    }

    /** Index de tri Modrinth (voir search_modrinth côté Rust) — DEFAULT laisse le serveur choisir (pertinence si texte tapé, sinon popularité). */
    private enum SortOrder {
        DEFAULT("", "Pertinence"),
        DOWNLOADS("downloads", "Téléchargements"),
        NEWEST("newest", "Plus récent"),
        UPDATED("updated", "Mis à jour");

        final String apiValue, label;

        SortOrder(String apiValue, String label) {
            this.apiValue = apiValue;
            this.label = label;
        }
    }

    protected final Object lastScreen;
    // Onglet actif — RESOURCE_PACK par défaut à l'ouverture (voir carte
    // "Modrinth" unique dans UiMainMenuScreen, remplace les deux anciennes
    // cartes séparées). Shaders reste soumis à ShaderLoaderDetector (voir
    // buildLayout) : l'onglet n'apparaît que si un loader de shaders est présent.
    private ContentKind kind = ContentKind.RESOURCE_PACK;

    // ── Filtres (écran/panneau dédié, voir buildFilterPanel) ────────────────
    private boolean filtersOpen;
    private SortOrder sort = SortOrder.DEFAULT;
    // Version MC COURANTE (celle réellement lancée, voir launcheragent.mcVersion
    // posé par LauncherAgent.premain0) plutôt qu'une liste déroulante de
    // versions à maintenir/peupler — répond directement au vrai besoin
    // ("est-ce compatible avec CE que je joue"), pas à un besoin générique de
    // parcourir toutes les versions Modrinth.
    private boolean currentVersionOnly;
    // Catégories propres au TYPE actif (voir ContentKind.categories) — vidées
    // au changement d'onglet (switchKind), une catégorie "16x" n'a aucun sens
    // côté shaders et inversement.
    private final Set<String> selectedCategories = new HashSet<>();

    private UiTextField searchField;
    private boolean layoutBuilt;

    // ── Grille paginée (rework 2026-10-04) ──────────────────────────────────
    //
    // Plus de liste défilante : la grille MESURE la place dont elle dispose
    // (voir buildLayout) et en déduit colonnes × rangées = taille de page ; le
    // reste se parcourt par la pagination du bas, servie par Modrinth
    // (offset/limit). Raison : sur Blaze3D il n'y a pas de vrai clip, une carte
    // qui sortait du viewport ne faisait que s'estomper — elle devenait
    // invisible bien avant d'en être sortie, tout en restant cliquable. Sans
    // défilement, il n'y a plus rien à couper.
    private float gridX, gridY, gridW, gridH; // zone de la grille, (gridX, gridY) = coin bas-gauche
    private int gridCols = 3, gridRows = 1;
    private int page;                         // 0 = première page
    private volatile long totalHits;
    /** Cartes + boutons de page : reconstruits à chaque résultat, retirés de {@code widgets} avant. */
    private final List<UiWidget> resultWidgets = new ArrayList<>();

    private int pageSize() { return Math.max(1, gridCols * gridRows); }

    private int pageCount() {
        long pages = (totalHits + pageSize() - 1) / pageSize();
        return (int) Math.max(1L, Math.min(MAX_PAGES, pages));
    }

    private volatile boolean busy;
    private volatile String statusText = "";
    private volatile SearchResult pendingResults;
    // Recherche live débouncée — barre de recherche moderne : recherche
    // automatique après une pause de frappe, pas besoin d'un clic explicite
    // sur "Chercher" (qui reste utilisable pour forcer une relance immédiate,
    // voir buildLayout). volatile : écrit depuis pollContinuous (thread de
    // rendu), lu chaque frame dans uiDraw — même thread en pratique ici, mais
    // gardé cohérent avec le reste des champs d'état partagés de cette classe.
    private static final long SEARCH_DEBOUNCE_MS = 450L;
    private volatile long lastEditAtMs;
    private volatile boolean searchPending;
    private List<ModrinthJson.Hit> shownResults = Collections.emptyList();
    // projectId en cours de téléchargement — piloté par le thread d'install,
    // lu par ResultCard.draw() (thread de rendu) pour animer le spinner. Un
    // seul projectId à la fois (busy bloque déjà tout nouveau déclenchement
    // pendant qu'une install est en cours, voir triggerInstall).
    private volatile String installingProjectId;

    public ModrinthContentScreen(Object lastScreen) {
        super("Modrinth");
        this.lastScreen = lastScreen;
    }

    /** Même convention que HudConfigStore/launcher.rs (user.dir = dossier de travail du process Java = dossier de l'instance) — recalculé à chaque appel plutôt que mis en cache, la bascule d'onglet change le dossier. */
    private Path installDir() {
        return Paths.get(System.getProperty("user.dir", "."), kind.installDirName);
    }

    /**
     * Change d'onglet et relance immédiatement la recherche AVEC LE MÊME
     * TEXTE (une bascule Resource Packs→Shaders en pleine recherche "faithful"
     * doit chercher "faithful" côté shaders, pas repartir d'un champ vide) —
     * état d'install/résultats précédents jetés (appartiennent à l'AUTRE
     * project_type, plus pertinents ici).
     */
    private void switchKind(ContentKind newKind) {
        if (newKind == kind || busy) return;
        kind = newKind;
        installingProjectId = null;
        shownResults = Collections.emptyList();
        totalHits = 0L;
        selectedCategories.clear(); // catégories propres au type précédent, sans rapport ici
        if (searchField != null) searchField.setPlaceholder(kind.searchPlaceholder);
        buildLayout();
        if (filtersOpen) buildDrawer(); // les chips de catégories dépendent du type actif
        triggerSearch();
    }

    private void toggleFilters() {
        filtersOpen = !filtersOpen;
        if (filtersOpen) buildDrawer();
        buildLayout(); // met à jour le "•" du bouton Filtres — pas de recherche réseau relancée
    }

    @Override
    protected List<UiWidget> modalWidgets() {
        return filtersOpen ? drawerAllWidgets : null;
    }

    @Override
    protected void onModalEscape() {
        toggleFilters();
    }

    @Override
    public void uiPollInput(UiInputPoller input) {
        updateDrawerLayout();
        // Les cartes et la pagination vivent dans la liste widgets de l'écran :
        // modalWidgets() les rend inertes sous le tiroir sans garde de plus.
        super.uiPollInput(input);
    }

    /**
     * Voile léger au lieu du fond quasi opaque par défaut — même règle que
     * l'écran principal : un fond opaque rend le décor flouté invisible, donc
     * les cartes de verre n'ont plus rien à flouter. Repli opaque sans verre.
     */
    @Override
    protected UiColor overlayColor() {
        return UiRenderer.get(getClass().getClassLoader()).isGlassAvailable()
            ? UiTheme.GLASS_SCRIM
            : UiTheme.OVERLAY_BG;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (!layoutBuilt && screenWidth > 0 && screenHeight > 0) {
            buildLayout();
            layoutBuilt = true;
            triggerSearch(); // recherche initiale — buildLayout() lui-même ne recherche plus (voir toggleFilters/switchKind, qui l'appellent sans vouloir relancer le réseau à chaque fois)
        }
        // Consommé UNE SEULE FOIS par le thread de rendu — jamais muté
        // directement depuis le thread de recherche (voir javadoc de la classe).
        SearchResult results0 = pendingResults;
        if (results0 != null) {
            pendingResults = null;
            totalHits = results0.total;
            showResults(results0.hits);
        }
        // Débounce de la recherche live — voir onSearchTextChanged/SEARCH_DEBOUNCE_MS.
        // runSearch et non triggerSearch : la page demandée est gardée (c'est
        // aussi par ici que repasse une recherche demandée pendant qu'une
        // autre tournait, voir runSearch).
        if (searchPending && System.currentTimeMillis() - lastEditAtMs >= SEARCH_DEBOUNCE_MS) {
            runSearch();
        }

        updateDrawerLayout();
        // Chaîne de flou partagée par tout le frame — voir
        // UiRenderer#beginGlassFrame (doit précéder tout dessin).
        UiRenderer.get(getClass().getClassLoader()).beginGlassFrame(GLASS_PASSES, screenWidth, screenHeight);
        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            // Titre posé directement sur le décor — ombre portée obligatoire
            // (voir UiTheme.TEXT_SHADOW), il ne repose sur aucune surface de verre.
            renderer.drawTextShadowed(UiFont.BOLD, kind.headerTitle, MARGIN, screenHeight - 48,
                UiTheme.TEXT_PRIMARY, UiTheme.TEXT_SHADOW, 1f, -1f, 0.8f, screenWidth, screenHeight);
            String status = statusText;
            if (!status.isEmpty()) {
                // Sur la rangée de la pagination, à gauche : le haut de l'écran
                // est gardé pour la grille. Posé sur le décor, donc ombré.
                renderer.drawTextShadowed(UiFont.REGULAR, status, MARGIN, MARGIN + PAGER_H / 2f - 5f,
                    UiTheme.TEXT_SECONDARY, UiTheme.TEXT_SHADOW, 1f, -1f, 0.42f, screenWidth, screenHeight);
            }
            // Tiroir de filtres DESSINÉ EN DERNIER (par-dessus la liste) — voir
            // modalWidgets() pour pourquoi le dessin ne peut pas passer par la
            // liste widgets générique de UiScreenBase (ordre de dessin vs.
            // ordre de priorité au clic contradictoires sur une liste plate).
            drawDrawer(renderer, mouseX, mouseY);
            // Voir UiModConfigScreen — ré-appliqué pour couvrir le titre/la liste ci-dessus.
            drawRevealVeil(renderer);
        } catch (Throwable t) {
            LauncherLog.err("[ModrinthContentScreen] uiDraw: " + t);
        }
    }

    // Constantes d'agencement — regroupées ici (au lieu de littéraux éparpillés)
    // suite au premier jet jugé "pas beau"/trop tassé en haut à gauche (titre/
    // Retour/recherche qui se chevauchaient, texte minuscule sur un écran
    // entier vide) : tout est maintenant nettement plus grand et espacé.
    private static final float MARGIN = 28f;

    /** Étages de flou — même valeur que les autres écrans (voir UiRenderer#beginGlassFrame). */
    private static final int GLASS_PASSES = 4;
    private static final float BACK_W = 110f, BACK_H = 34f;
    private static final float TAB_H = 34f, TAB_W = 160f;
    // Distances depuis le HAUT de l'écran (screenHeight - X) — décalées de
    // +52px (hauteur d'onglet + marge) par rapport à l'ancien agencement sans
    // onglets, pour faire de la place à la ligne Resource Packs/Shaders.
    // Resserrées le 2026-10-04 (onglets 72, recherche 120, chips 174, grille
    // 220) : le statut est descendu sur la rangée de la pagination, et tout ce
    // qui est rendu en haut va à la grille.
    private static final float TAB_TOP_GAP = 72f;
    private static final float SEARCH_TOP_GAP = 120f;
    private static final float SEARCH_H = 40f;
    private static final float SEARCH_BTN_W = 130f;
    private static final float FILTER_BTN_W = 100f;
    /** Rangée de catégories au-dessus de la grille — raccourci des mêmes filtres que le tiroir. */
    private static final float INLINE_CHIPS_TOP_GAP = 174f;
    private static final float INLINE_CHIP_H = 30f, INLINE_CHIP_GAP = 8f;
    private static final float HEADER_H = 220f;
    private static final float CHIP_H = 40f, CHIP_GAP = 14f;
    // Grille : au plus 3 colonnes, moins si une carte passerait sous CARD_MIN_W.
    private static final float CARD_H = 96f, CARD_GAP = 14f, CARD_MIN_W = 380f;
    private static final int MAX_COLS = 3;
    /** Rangée du bas : statut à gauche, pagination au centre. */
    private static final float PAGER_H = 36f, PAGER_GAP = 14f;

    private void buildLayout() {
        widgets.clear();

        // ── Arbre de layout (rework 2026-08-27) ─────────────────────────────
        //
        // Remplace une pile de constantes "distance depuis le haut de l'écran"
        // (TAB_TOP_GAP, SEARCH_TOP_GAP, HEADER_H...) qui devaient rester
        // cohérentes ENTRE ELLES à la main : décaler la barre de recherche
        // obligeait à recalculer HEADER_H, sous peine de voir la liste de
        // résultats la chevaucher. Elles deviennent ici de simples ÉCARTS
        // successifs, chacun indépendant des autres.
        //
        // Écarts portés par des marges INDIVIDUELLES et non par un gap de
        // colonne : les trois écarts sont différents, un gap unique ne peut
        // pas les exprimer.
        TaffyNode screen = new TaffyNode("screen", new TaffyStyle()
            .size(TaffyStyle.px(screenWidth), TaffyStyle.px(screenHeight))
            .flexDirection("column"));
        screen.style.padding = new String[]{ "0", TaffyStyle.px(MARGIN), TaffyStyle.px(MARGIN), TaffyStyle.px(MARGIN) };

        boolean shadersTab = com.yuyuframe.launcheragent.runtime.content.ShaderLoaderDetector.isPresent(getClass().getClassLoader());

        TaffyNode tabsRow = LayoutSolver.row("tabsRow", 10f);
        tabsRow.style.height = TaffyStyle.px(TAB_H);
        tabsRow.style.flexShrink = 0f;
        tabsRow.style.margin = new String[]{ TaffyStyle.px(TAB_TOP_GAP), "0", "0", "0" };
        tabsRow.child(LayoutSolver.box("tab:pack", TAB_W, TAB_H));
        if (shadersTab) tabsRow.child(LayoutSolver.box("tab:shader", TAB_W, TAB_H));
        screen.child(tabsRow);

        TaffyNode searchRow = LayoutSolver.row("searchRow", 0f);
        searchRow.style.height = TaffyStyle.px(SEARCH_H);
        searchRow.style.flexShrink = 0f;
        searchRow.style.margin = new String[]{
            TaffyStyle.px(SEARCH_TOP_GAP - TAB_TOP_GAP - TAB_H), "0", "0", "0" };
        // 460 souhaité mais AUTORISÉ À RÉTRÉCIR face à deux boutons qui, eux,
        // gardent leur taille — reproduit l'ancien Math.min(460, ...) sans le
        // calculer, et sans sa constante "32" qui ne correspondait à aucun des
        // deux écarts réels (16 et 10).
        TaffyStyle searchStyle = new TaffyStyle();
        searchStyle.width = TaffyStyle.px(460f);
        searchStyle.height = TaffyStyle.px(SEARCH_H);
        searchRow.child(new TaffyNode("search", searchStyle));
        TaffyNode findBtn = LayoutSolver.box("btn:search", SEARCH_BTN_W, SEARCH_H);
        findBtn.style.margin = new String[]{ "0", "0", "0", TaffyStyle.px(16f) };
        searchRow.child(findBtn);
        TaffyNode filterBtn = LayoutSolver.box("btn:filters", FILTER_BTN_W, SEARCH_H);
        filterBtn.style.margin = new String[]{ "0", "0", "0", TaffyStyle.px(10f) };
        searchRow.child(filterBtn);
        screen.child(searchRow);

        TaffyStyle resultsStyle = new TaffyStyle();
        resultsStyle.flexGrow = 1f;
        // Marge basse = la rangée de pagination (voir showResults).
        resultsStyle.margin = new String[]{
            TaffyStyle.px(HEADER_H - SEARCH_TOP_GAP - SEARCH_H), "0", TaffyStyle.px(PAGER_H + PAGER_GAP), "0" };
        screen.child(new TaffyNode("results", resultsStyle));

        // "Retour" en HAUT-DROITE — l'ancien placement (haut-gauche, sous le
        // titre) chevauchait littéralement le titre (confirmé en jeu). Ancré
        // HORS FLUX : il ne doit pousser aucune des rangées ci-dessus.
        screen.child(LayoutSolver.anchored("back", BACK_W, BACK_H, MARGIN, MARGIN, null, null));

        LayoutSolver.Solved layout = LayoutSolver.solve(screen, screenWidth, screenHeight);

        UiButton backBtn = new UiButton(screenWidth - MARGIN - BACK_W, screenHeight - MARGIN - BACK_H, BACK_W, BACK_H,
            "Retour", () -> closeTo(lastScreen));
        if (layout != null) layout.apply("back", backBtn);
        widgets.add(backBtn);

        // Onglets Resource Packs / Shaders — Shaders SEULEMENT si un loader de
        // shaders compatible est détecté (même garde que l'ancien bouton
        // "Shaders..." de GameMenuScreenMixin, supprimé : un shaderpack
        // installé sans loader ne sert à rien). Repli sur Resource Packs si
        // l'onglet Shaders était actif mais le loader a disparu entre-temps
        // (peu probable en pratique — mods rechargés seulement au lancement —
        // mais évite un écran bloqué sur un onglet qui n'existe plus).
        boolean shadersAvailable = shadersTab;
        if (!shadersAvailable && kind == ContentKind.SHADER) kind = ContentKind.RESOURCE_PACK;
        float tabY = screenHeight - TAB_TOP_GAP - TAB_H;
        TabButton packTab = new TabButton(MARGIN, tabY, TAB_W, TAB_H, ContentKind.RESOURCE_PACK);
        if (layout != null) layout.apply("tab:pack", packTab);
        widgets.add(packTab);
        if (shadersAvailable) {
            TabButton shaderTab = new TabButton(MARGIN + TAB_W + 10f, tabY, TAB_W, TAB_H, ContentKind.SHADER);
            if (layout != null) layout.apply("tab:shader", shaderTab);
            widgets.add(shaderTab);
        }

        float searchY = screenHeight - SEARCH_TOP_GAP - SEARCH_H;
        float searchW = Math.min(460f, screenWidth - MARGIN * 2 - SEARCH_BTN_W - FILTER_BTN_W - 32f);
        // Même instance de champ réutilisée d'un rebuild à l'autre (toggleFilters/
        // switchKind rappellent buildLayout()) — recréer un UiTextField à
        // chaque fois effacerait ce que l'utilisateur est en train de taper.
        if (searchField == null) {
            searchField = new UiTextField(0, 0, 0, 0, kind.searchPlaceholder, this::onSearchTextChanged)
                .searchIcon()
                .onSubmit(this::triggerSearch);
        }
        searchField.x = MARGIN;
        searchField.y = searchY;
        searchField.w = searchW;
        searchField.h = SEARCH_H;
        if (layout != null) layout.apply("search", searchField);
        widgets.add(searchField);

        UiButton findButton = new UiButton(MARGIN + searchW + 16f, searchY, SEARCH_BTN_W, SEARCH_H, "Chercher", this::triggerSearch);
        if (layout != null) layout.apply("btn:search", findButton);
        widgets.add(findButton);

        boolean anyFilterActive = sort != SortOrder.DEFAULT || currentVersionOnly || !selectedCategories.isEmpty();
        UiButton filterButton = new UiButton(MARGIN + searchW + 16f + SEARCH_BTN_W + 10f, searchY, FILTER_BTN_W, SEARCH_H,
            "Filtres" + (anyFilterActive ? " •" : ""), this::toggleFilters);
        if (layout != null) layout.apply("btn:filters", filterButton);
        widgets.add(filterButton);

        // Catégories du type actif, en une rangée au-dessus de la grille. Même
        // état que les chips du tiroir (selectedCategories) : ce n'est qu'un
        // raccourci. Celles qui ne tiennent pas sur la rangée restent
        // accessibles par « Filtres » — pas de retour à la ligne, qui
        // prendrait une rangée de cartes.
        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
        float chipY = screenHeight - INLINE_CHIPS_TOP_GAP - INLINE_CHIP_H;
        float chipX = MARGIN;
        for (String cat : kind.categories) {
            float cw = renderer.textWidth(cat, InlineChip.SCALE) + 26f;
            if (chipX + cw > screenWidth - MARGIN) break;
            widgets.add(new InlineChip(chipX, chipY, cw, INLINE_CHIP_H, cat));
            chipX += cw + INLINE_CHIP_GAP;
        }

        TaffyLayoutResult.Rect res = layout == null ? null : layout.get("results");
        if (res != null) {
            gridX = res.x; gridY = res.y; gridW = res.w; gridH = Math.max(1f, res.h);
        } else {
            gridX = MARGIN;
            gridY = MARGIN + PAGER_H + PAGER_GAP;
            gridW = screenWidth - MARGIN * 2;
            gridH = Math.max(1f, screenHeight - HEADER_H - gridY);
        }
        // La grille mesure sa place : autant de colonnes que possible sans
        // passer sous CARD_MIN_W, autant de rangées ENTIÈRES que la hauteur en
        // offre. Leur produit est la taille de page demandée à Modrinth.
        gridCols = Math.max(1, Math.min(MAX_COLS, (int) ((gridW + CARD_GAP) / (CARD_MIN_W + CARD_GAP))));
        gridRows = Math.max(1, (int) ((gridH + CARD_GAP) / (CARD_H + CARD_GAP)));

        // Ré-affiche les résultats DÉJÀ reçus à la nouvelle géométrie — PAS un
        // nouvel appel réseau, showResults() est purement local. Vide au tout
        // premier appel (avant la toute première recherche) : rien à réafficher.
        resultWidgets.clear(); // widgets.clear() ci-dessus les a déjà retirés de l'écran
        if (!shownResults.isEmpty()) showResults(shownResults);
    }

    /**
     * Pastille de catégorie de la rangée du haut — pleine ACCENT si la
     * catégorie filtre, verre léger sinon. Sans animation d'entrée,
     * contrairement à {@link FilterChip} : buildLayout la recrée à chaque
     * ouverture du tiroir, elle clignoterait.
     */
    private final class InlineChip extends UiWidget {
        static final float SCALE = 0.4f;
        private final String category;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        InlineChip(float x, float y, float w, float h, String category) {
            super(x, y, w, h);
            this.category = category;
        }

        @Override
        public void onClick() {
            if (!selectedCategories.remove(category)) selectedCategories.add(category);
            triggerSearch();
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean active = selectedCategories.contains(category);
            hoverAnim.setTarget(!active && contains(mouseX, mouseY) ? 1f : 0f);
            float hover = hoverAnim.get();
            float radius = h / 2f;
            if (active) {
                renderer.drawRoundedRect(x, y, x + w, y + h, radius, UiTheme.ACCENT, vpWidth, vpHeight);
            } else {
                UiColor border = renderer.isGlassAvailable()
                    ? UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.GLASS_BORDER_HOVER, hover) : null;
                renderer.drawGlassPanel(x, y, x + w, y + h, radius,
                    UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD - hover * 0.1f,
                    UiColor.lerp(UiTheme.PANEL_BG_ALT, UiTheme.CARD_HOVER, hover),
                    border, Math.max(1f, UiTheme.scaled(1f)), vpWidth, vpHeight);
            }
            float tw = renderer.textWidth(category, SCALE);
            renderer.drawText(category, x + (w - tw) / 2f, y + h / 2f - 4.5f,
                active ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_SECONDARY, SCALE, vpWidth, vpHeight);
        }
    }

    // ── Tiroir de filtres (modal) ────────────────────────────────────────────
    //
    // PREMIER modal de ce moteur UI — voir UiScreenBase.modalWidgets() pour le
    // mécanisme générique posé pour lui (et réutilisable par de futurs
    // modaux). Glisse depuis la droite, pleine hauteur, fond assombri sur le
    // reste de l'écran (liste de résultats visible en dégradé derrière, pas
    // repoussée vers le bas comme l'ancien panneau inline — qui pouvait la
    // chevaucher, voir historique de session).
    //
    // Les chips (drawerContent) sont des widgets PERSISTANTS pendant que le
    // tiroir reste ouvert (jamais recréés frame après frame, seulement
    // REPOSITIONNÉS via updateDrawerLayout — même motif que
    // UiScrollContainer.baseY/applyOffsets) : les recréer à chaque frame
    // remettrait leur UiAnimatedFloat de survol à zéro en permanence, cassant
    // l'animation de survol.
    private static final float DRAWER_W = 460f;
    private static final float DRAWER_MARGIN = 30f;
    private final UiAnimatedFloat drawerAnim = new UiAnimatedFloat(0f, 12f);
    private final UiWidget drawerBackdrop = new DrawerBackdrop();
    private final List<UiWidget> drawerContent = new ArrayList<>();
    private final List<Float> drawerContentBaseX = new ArrayList<>(); // offset LOCAL (0 = bord gauche du tiroir)
    private final List<UiWidget> drawerAllWidgets = new ArrayList<>(); // backdrop + drawerContent, recombiné à chaque buildDrawer()
    private float drawerSortLabelY, drawerVersionLabelY, drawerCatLabelY;
    private boolean drawerHasVersionChip;

    /** Repositionne le tiroir/son fond à l'état d'animation COURANT — appelé chaque frame (poll ET dessin), indépendamment de buildDrawer() (qui, lui, ne (re)construit les widgets qu'à l'ouverture/au changement de type). */
    private void updateDrawerLayout() {
        drawerAnim.setTarget(filtersOpen ? 1f : 0f);
        float t = drawerAnim.get();
        float drawerX = screenWidth - DRAWER_W * t;
        drawerBackdrop.x = 0;
        drawerBackdrop.y = 0;
        drawerBackdrop.w = Math.max(0f, drawerX);
        drawerBackdrop.h = screenHeight;
        for (int i = 0; i < drawerContent.size(); i++) {
            drawerContent.get(i).x = drawerX + drawerContentBaseX.get(i);
        }
    }

    private void drawDrawer(UiRenderer renderer, double mouseX, double mouseY) {
        float t = drawerAnim.get();
        if (t <= 0.001f) return;
        drawerBackdrop.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
        float drawerX = screenWidth - DRAWER_W * t;
        if (renderer.isGlassAvailable()) {
            // drawBlurredPanel et NON drawGlassPanel : ce tiroir est une MODALE,
            // il doit flouter TOUT ce qu'il recouvre — y compris l'interface
            // déjà dessinée dessous. La chaîne PARTAGÉE du frame
            // (beginGlassFrame) ne contient que le monde du jeu, capturé avant
            // le moindre pixel d'UI (voir Blaze3DBlur#queueFrameChain) : s'en
            // servir ici laisserait la liste de résultats parfaitement nette
            // sous le tiroir. Celui-ci recalcule donc sa propre chaîne à son
            // tour dans la file — 9 passes en plus, mais seulement tant que le
            // tiroir est ouvert, et pour un seul panneau.
            renderer.drawBlurredPanel(drawerX, 0, screenWidth, screenHeight, 0f, 0f, 0f, 0f,
                GLASS_PASSES, UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_MODAL, screenWidth, screenHeight);
        } else {
            renderer.drawRoundedRect(drawerX, 0, screenWidth, screenHeight, 0, UiTheme.PANEL_BG, screenWidth, screenHeight);
        }
        // Liseré ACCENT au bord gauche — sépare visuellement le tiroir du
        // reste (drawShadow existe mais compose TOUJOURS après Blaze3D sur
        // era E, voir sa javadoc — un vrai risque de retomber sur le même bug
        // de z-order déjà rencontré plusieurs fois cette session ; un simple
        // liseré plein via drawRoundedRect, lui, passe par le même chemin
        // Blaze3D que le reste, aucun risque de composition).
        renderer.drawRoundedRect(drawerX, 0, drawerX + 3f, screenHeight, 0, UiTheme.ACCENT, screenWidth, screenHeight);

        // Libellés fondus en synchro avec le glissement du tiroir (même valeur
        // "t" que le fond/le liseré ci-dessus) — pas de UiTransition dédiée
        // pour un simple texte statique, cette synchronisation suffit.
        UiColor labelColor = UiTheme.TEXT_MUTED.multiplyAlpha(t);
        renderer.drawText(UiFont.BOLD, "Filtres", drawerX + DRAWER_MARGIN, screenHeight - 52f,
            UiTheme.TEXT_PRIMARY.multiplyAlpha(t), 0.72f, screenWidth, screenHeight);
        renderer.drawText("Trier par", drawerX + DRAWER_MARGIN, drawerSortLabelY, labelColor, 0.46f, screenWidth, screenHeight);
        if (drawerHasVersionChip) {
            renderer.drawText("Version", drawerX + DRAWER_MARGIN, drawerVersionLabelY, labelColor, 0.46f, screenWidth, screenHeight);
        }
        renderer.drawText("Catégories", drawerX + DRAWER_MARGIN, drawerCatLabelY, labelColor, 0.46f, screenWidth, screenHeight);

        for (UiWidget w : drawerContent) w.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
    }

    /** Fond assombri derrière le tiroir — clic dessus = fermer (comme une modale classique). Largeur mise à jour chaque frame par updateDrawerLayout(). */
    private final class DrawerBackdrop extends UiWidget {
        DrawerBackdrop() { super(0, 0, 0, 0); }

        @Override
        public void onClick() { toggleFilters(); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            renderer.drawRoundedRect(x, y, x + w, y + h, 0,
                new UiColor(8, 8, 12, 255).multiplyAlpha(0.55f * drawerAnim.get()), vpWidth, vpHeight);
        }
    }

    /** Bouton fermer (×) du tiroir — coin haut-droite. */
    private final class DrawerCloseButton extends UiWidget {
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);
        private final UiTransition entrance;

        DrawerCloseButton(float w, float h, float entranceDelay) {
            super(0, 0, w, h);
            entrance = new UiTransition(0.24f, entranceDelay, UiEasing.EASE_OUT_CUBIC);
            entrance.show();
        }

        @Override
        public void onClick() { toggleFilters(); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            float e = entrance.eased();
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            UiColor bg = UiColor.lerp(UiTheme.PANEL_BG_ALT, UiTheme.CARD_HOVER, hoverAnim.get()).multiplyAlpha(e);
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            float scale = 0.56f;
            float tw = renderer.textWidth("×", scale); // × (multiplication, U+00D7 — Latin-1 Supplement, supporté par UiFont)
            renderer.drawText("×", x + (w - tw) / 2f, y + h / 2f - 7f, UiTheme.TEXT_SECONDARY.multiplyAlpha(e), scale, vpWidth, vpHeight);
        }
    }

    /** Enregistre un widget de tiroir à sa position LOCALE (x relatif au bord gauche, y absolu — le tiroir ne défile pas verticalement) — voir updateDrawerLayout(). */
    private void addDrawerWidget(UiWidget w, float localX, float y) {
        w.y = y;
        drawerContent.add(w);
        drawerContentBaseX.add(localX);
    }

    /**
     * (Re)construit le CONTENU du tiroir (fermer, tri, version, catégories,
     * réinitialiser) — appelé à l'ouverture et au changement d'onglet (les
     * catégories dépendent du type actif, voir switchKind). Positions
     * calculées en LOCAL (0 = bord gauche du tiroir) via addDrawerWidget,
     * décalées vers l'écran chaque frame par updateDrawerLayout().
     */
    // Cascade d'entrée (voir UiStagger/UiTransition, moteur déjà présent mais
    // encore jamais câblé en prod avant ce tiroir) — chaque chip apparaît
    // légèrement après la précédente plutôt que toutes d'un coup. Plafonné
    // (ENTRANCE_MAX_DELAY) : une longue liste de catégories n'attend plus
    // indéfiniment avant que ses derniers éléments n'apparaissent.
    private static final float ENTRANCE_PER_ITEM_DELAY = 0.028f;
    private static final float ENTRANCE_MAX_DELAY = 0.32f;

    // BUG TROUVÉ (utilisateur : "les titres sont beaucoup trop serrés au
    // sélection") : l'espace réservé entre un libellé de section et sa
    // rangée de chips valait EXACTEMENT CHIP_H (40f), donc le HAUT de la
    // première chip tombait pile sur la ligne de BASE du texte (drawText
    // dessine au-dessus de "y", jamais en dessous) — zéro espace visuel
    // entre les lettres et la chip. Un vrai espacement doit ajouter une
    // marge EN PLUS de CHIP_H, pas s'y substituer.
    private static final float LABEL_TO_CHIP_GAP = CHIP_H + 20f;

    private void buildDrawer() {
        drawerContent.clear();
        drawerContentBaseX.clear();
        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
        float chipScale = 0.46f;
        float maxLocalX = DRAWER_W - DRAWER_MARGIN;
        int[] entranceIndex = {0}; // tableau 1-case = compteur mutable capturable par les lambdas ci-dessous

        float closeSize = 34f;
        addDrawerWidget(new DrawerCloseButton(closeSize, closeSize, nextEntranceDelay(entranceIndex)),
            DRAWER_W - DRAWER_MARGIN - closeSize, screenHeight - 62f);

        float y = screenHeight - 128f;
        drawerSortLabelY = y;
        y -= LABEL_TO_CHIP_GAP;
        float x = DRAWER_MARGIN;
        for (SortOrder s : SortOrder.values()) {
            float w = renderer.textWidth(s.label, chipScale) + 34f;
            if (x + w > maxLocalX) {
                x = DRAWER_MARGIN;
                y -= CHIP_H + CHIP_GAP;
            }
            addDrawerWidget(new FilterChip(0, 0, w, CHIP_H, s.label, () -> sort == s, () -> {
                sort = s;
                triggerSearch();
            }, nextEntranceDelay(entranceIndex)), x, y);
            x += w + CHIP_GAP;
        }
        y -= CHIP_H + CHIP_GAP + 32f;

        String mcVersion = System.getProperty("launcheragent.mcVersion", "");
        drawerHasVersionChip = !mcVersion.isEmpty() && !"unknown".equals(mcVersion);
        if (drawerHasVersionChip) {
            drawerVersionLabelY = y;
            y -= LABEL_TO_CHIP_GAP;
            String versionLabel = "Version " + mcVersion + " uniquement";
            float vw = renderer.textWidth(versionLabel, chipScale) + 34f;
            addDrawerWidget(new FilterChip(0, 0, vw, CHIP_H, versionLabel, () -> currentVersionOnly, () -> {
                currentVersionOnly = !currentVersionOnly;
                triggerSearch();
            }, nextEntranceDelay(entranceIndex)), DRAWER_MARGIN, y);
            y -= CHIP_H + CHIP_GAP + 32f;
        }

        drawerCatLabelY = y;
        y -= LABEL_TO_CHIP_GAP;
        x = DRAWER_MARGIN;
        for (String cat : kind.categories) {
            float w = renderer.textWidth(cat, chipScale) + 34f;
            if (x + w > maxLocalX) {
                x = DRAWER_MARGIN;
                y -= CHIP_H + CHIP_GAP;
            }
            addDrawerWidget(new FilterChip(0, 0, w, CHIP_H, cat, () -> selectedCategories.contains(cat), () -> {
                if (!selectedCategories.remove(cat)) selectedCategories.add(cat);
                triggerSearch();
            }, nextEntranceDelay(entranceIndex)), x, y);
            x += w + CHIP_GAP;
        }
        y -= CHIP_H + CHIP_GAP + 44f;

        addDrawerWidget(new UiButton(0, 0, DRAWER_W - DRAWER_MARGIN * 2, 46f, "Réinitialiser les filtres", this::resetFilters),
            DRAWER_MARGIN, y - 46f);

        drawerAllWidgets.clear();
        drawerAllWidgets.add(drawerBackdrop);
        drawerAllWidgets.addAll(drawerContent);
    }

    /** Délai d'entrée du PROCHAIN élément de la cascade, incrémente le compteur au passage — voir ENTRANCE_PER_ITEM_DELAY/UiStagger. */
    private static float nextEntranceDelay(int[] counter) {
        return UiStagger.delayFor(counter[0]++, ENTRANCE_PER_ITEM_DELAY, ENTRANCE_MAX_DELAY);
    }

    private void resetFilters() {
        selectedCategories.clear();
        sort = SortOrder.DEFAULT;
        currentVersionOnly = false;
        triggerSearch();
    }

    /**
     * "Pastille" de filtre cliquable (tri/version/catégorie) — plein ACCENT
     * si actif, léger survol sinon. Même esprit visuel que TabButton, en
     * plus compact. Fondu + léger glissement vertical à l'apparition
     * ({@code entrance}, voir buildDrawer/nextEntranceDelay) — jamais
     * recréée pendant que le tiroir reste ouvert (voir javadoc de la section
     * "Tiroir de filtres"), donc l'animation ne se rejoue QUE quand le
     * tiroir se rouvre (buildDrawer() reconstruit tout depuis zéro).
     */
    private final class FilterChip extends UiWidget {
        private static final float ENTRANCE_SLIDE_PX = 12f;

        private final String label;
        private final java.util.function.BooleanSupplier active;
        private final Runnable onToggle;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);
        private final UiTransition entrance;

        FilterChip(float x, float y, float w, float h, String label, java.util.function.BooleanSupplier active,
                   Runnable onToggle, float entranceDelay) {
            super(x, y, w, h);
            this.label = label;
            this.active = active;
            this.onToggle = onToggle;
            entrance = new UiTransition(0.26f, entranceDelay, UiEasing.EASE_OUT_CUBIC);
            entrance.show();
        }

        @Override
        public void onClick() { onToggle.run(); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            float e = entrance.eased();
            // Glisse légèrement vers le HAUT en apparaissant (Y-up : "monte
            // vers sa position finale" = y DÉCROÎT depuis un point de départ
            // plus bas) — décalage purement visuel, contains() (hit-test)
            // reste sur la position finale (this.y), pas sur cette position
            // dessinée : un clic pendant les ~280ms d'animation reste fiable.
            float sy = y + (1f - e) * -ENTRANCE_SLIDE_PX; // "y" décalé pour cette frame (voir commentaire ci-dessus)

            boolean isActive = active.getAsBoolean();
            hoverAnim.setTarget(!isActive && contains(mouseX, mouseY) ? 1f : 0f);
            UiColor bg = (isActive ? UiTheme.ACCENT : UiColor.lerp(UiTheme.PANEL_BG_ALT, UiTheme.CARD_HOVER, hoverAnim.get()))
                .multiplyAlpha(e);
            renderer.drawRoundedRect(x, sy, x + w, sy + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            float scale = 0.46f;
            float tw = renderer.textWidth(label, scale);
            UiColor textColor = (isActive ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_SECONDARY).multiplyAlpha(e);
            renderer.drawText(label, x + (w - tw) / 2f, sy + h / 2f - 5f, textColor, scale, vpWidth, vpHeight);
        }
    }

    /** Bouton d'onglet Resource Packs/Shaders — fond plein (ACCENT) quand actif, léger survol sinon. Voir switchKind. */
    private final class TabButton extends UiWidget {
        private final ContentKind target;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        TabButton(float x, float y, float w, float h, ContentKind target) {
            super(x, y, w, h);
            this.target = target;
        }

        @Override
        public void onClick() { switchKind(target); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean active = kind == target;
            hoverAnim.setTarget(!active && contains(mouseX, mouseY) ? 1f : 0f);
            UiColor bg = active ? UiTheme.ACCENT : UiColor.lerp(UiTheme.PANEL_BG_ALT, UiTheme.CARD_HOVER, hoverAnim.get());
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            float scale = 0.46f;
            float tw = renderer.textWidth(target.tabLabel, scale);
            renderer.drawText(target.tabLabel, x + (w - tw) / 2f, y + h / 2f - 5f,
                active ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_SECONDARY, scale, vpWidth, vpHeight);
        }
    }

    // ── Recherche ──────────────────────────────────────────────────────────────

    /** Callback {@code onChange} du champ de recherche — arme le débounce (voir uiDraw), n'appelle jamais triggerSearch() directement (évite une requête réseau par frappe). */
    private void onSearchTextChanged(String newText) {
        lastEditAtMs = System.currentTimeMillis();
        searchPending = true;
        page = 0; // un autre texte = une autre liste, on repart du début
    }

    /** Nouvelle recherche (texte, filtre, tri, onglet) : repart de la première page. */
    private void triggerSearch() {
        page = 0;
        runSearch();
    }

    /** Autre page de la MÊME recherche. */
    private void goToPage(int newPage) {
        int clamped = Math.max(0, Math.min(pageCount() - 1, newPage));
        if (clamped == page) return;
        page = clamped;
        runSearch();
    }

    /** Lance la recherche de la page courante. */
    private void runSearch() {
        // Occupé (recherche ou installation en cours) : la demande n'est pas
        // perdue, le débounce de uiDraw la relance dès que c'est libre. Avant,
        // un filtre cliqué pendant une recherche changeait d'état sans que la
        // liste suive.
        if (busy) {
            searchPending = true;
            return;
        }
        searchPending = false; // une recherche manuelle (Entrée/bouton) rend le débounce en cours obsolète
        String query = searchField != null ? searchField.text().trim() : "";
        // Capturés ICI, sur le thread de rendu, AVANT de démarrer le thread de
        // recherche — selectedCategories/sort/currentVersionOnly ne sont PAS
        // volatile et peuvent continuer à être mutés par un clic sur une chip
        // de filtre pendant qu'une recherche est déjà en cours (busy ne
        // bloque QUE triggerSearch() lui-même, pas onToggle) : lire
        // selectedCategories DIRECTEMENT depuis le thread de recherche
        // risquerait une ConcurrentModificationException sur ce HashSet muté
        // en parallèle par un clic. Un snapshot immuable ici, transmis en
        // paramètre, élimine ce risque (même motif que "query" déjà capturé
        // de la même façon juste au-dessus).
        String categoriesCsv = String.join(",", selectedCategories);
        String version = currentVersionOnly ? System.getProperty("launcheragent.mcVersion", "") : "";
        String searchType = kind.searchType;
        String sortValue = sort.apiValue;
        int limit = pageSize();
        int offset = page * limit;
        busy = true;
        statusText = query.isEmpty() ? "Recommandations en cours..." : "Recherche en cours...";

        Thread t = new Thread(() -> doSearch(query, searchType, categoriesCsv, version, sortValue, offset, limit), "LauncherAgent-ModrinthSearch");
        t.setDaemon(true);
        t.start();
    }

    private void doSearch(String query, String searchType, String categoriesCsv, String version, String sortValue,
                          int offset, int limit) {
        try {
            if (!ContentBridge.ensureLoaded()) {
                statusText = "content_core.dll indisponible";
                return;
            }
            String json = ContentBridge.searchModrinthPage(query, searchType, categoriesCsv, version, sortValue, offset, limit);
            String error = ModrinthJson.jsonString(json, "error");
            if (error != null) {
                statusText = "Erreur Modrinth : " + error;
                return;
            }
            List<ModrinthJson.Hit> hits = ModrinthJson.parseHits(json, limit);
            // total_hits suit le tableau « hits » dans la réponse : cherché
            // APRÈS lui, sinon un projet dont la description contiendrait ce
            // mot-clé fausserait le compte.
            int tail = json.lastIndexOf("\"total_hits\"");
            long total = tail >= 0 ? ModrinthJson.jsonLong(json.substring(tail), "total_hits") : hits.size();
            pendingResults = new SearchResult(hits, Math.max(total, offset + hits.size()));
        } catch (Throwable t) {
            LauncherLog.err("[ModrinthContentScreen] doSearch: " + t);
            statusText = "Erreur recherche : " + t;
        } finally {
            busy = false;
        }
    }

    /** Reconstruit la liste de widgets résultat — appelé UNIQUEMENT depuis le thread de rendu (voir uiDraw). */
    private void showResults(List<ModrinthJson.Hit> hits) {
        shownResults = hits;
        int pages = pageCount();
        // Une page au-delà de la dernière (le total a baissé entre deux
        // requêtes) : on revient sur la dernière au lieu d'afficher du vide.
        if (hits.isEmpty() && page > 0 && totalHits > 0) {
            page = pages - 1;
            runSearch();
            return;
        }
        statusText = hits.isEmpty() ? "Aucun résultat"
            : totalHits + " résultat(s)" + (pages > 1 ? "  ·  page " + (page + 1) + " / " + pages : "");

        widgets.removeAll(resultWidgets);
        resultWidgets.clear();
        Set<String> installed = scanInstalledNormalizedNames();
        float cardW = (gridW - (gridCols - 1) * CARD_GAP) / gridCols;
        float top = gridY + gridH;
        int count = Math.min(hits.size(), pageSize());
        for (int i = 0; i < count; i++) {
            ModrinthJson.Hit hit = hits.get(i);
            int col = i % gridCols, row = i / gridCols;
            float cardX = gridX + col * (cardW + CARD_GAP);
            float cardY = top - row * (CARD_H + CARD_GAP) - CARD_H;
            resultWidgets.add(new ResultCard(cardX, cardY, cardW, CARD_H, hit, isInstalled(hit, installed)));
        }
        buildPager(pages);
        widgets.addAll(resultWidgets);
    }

    // ── Pagination ───────────────────────────────────────────────────────────

    private static final float PAGE_BTN_W = 36f, PAGE_BTN_GAP = 6f;
    private static final int PAGE_PREV = -2, PAGE_NEXT = -3, PAGE_ELLIPSIS = -1;

    /**
     * Rangée « &lt; 1 … 4 5 6 … 20 &gt; » centrée en bas : première, dernière et
     * la page courante entourée de ses voisines — même fenêtre que la
     * pagination du launcher. Rien s'il n'y a qu'une page.
     */
    private void buildPager(int pages) {
        if (pages <= 1) return;
        List<Integer> items = new ArrayList<>();
        items.add(PAGE_PREV);
        int last = -1;
        for (int p = 0; p < pages; p++) {
            if (p != 0 && p != pages - 1 && Math.abs(p - page) > 1) continue;
            if (last >= 0 && p - last > 1) items.add(PAGE_ELLIPSIS);
            items.add(p);
            last = p;
        }
        items.add(PAGE_NEXT);

        float total = items.size() * PAGE_BTN_W + (items.size() - 1) * PAGE_BTN_GAP;
        float px = Math.max(MARGIN, (screenWidth - total) / 2f);
        for (int item : items) {
            resultWidgets.add(new PageButton(px, MARGIN, PAGE_BTN_W, PAGER_H, item, pages));
            px += PAGE_BTN_W + PAGE_BTN_GAP;
        }
    }

    /** Un élément de la pagination : numéro, flèche, ou points de suspension (inertes). */
    private final class PageButton extends UiWidget {
        private final int item, pages;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        PageButton(float x, float y, float w, float h, int item, int pages) {
            super(x, y, w, h);
            this.item = item;
            this.pages = pages;
        }

        private int target() {
            return item == PAGE_PREV ? page - 1 : item == PAGE_NEXT ? page + 1 : item;
        }

        private boolean enabled() {
            if (item == PAGE_ELLIPSIS) return false;
            int t = target();
            return t >= 0 && t < pages && t != page;
        }

        @Override
        public boolean contains(double mx, double my) {
            return enabled() && super.contains(mx, my);
        }

        @Override
        public void onClick() {
            if (enabled()) goToPage(target());
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean current = item == page;
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float hover = hoverAnim.get();
            String label = item == PAGE_PREV ? "<" : item == PAGE_NEXT ? ">"
                : item == PAGE_ELLIPSIS ? "..." : String.valueOf(item + 1);
            if (current) {
                renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, UiTheme.ACCENT, vpWidth, vpHeight);
            } else if (item != PAGE_ELLIPSIS) {
                UiColor border = renderer.isGlassAvailable()
                    ? UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.GLASS_BORDER_HOVER, hover) : null;
                renderer.drawGlassPanel(x, y, x + w, y + h, UiTheme.RADIUS_SM,
                    UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD - hover * 0.1f,
                    UiColor.lerp(UiTheme.PANEL_BG_ALT, UiTheme.CARD_HOVER, hover),
                    border, Math.max(1f, UiTheme.scaled(1f)), vpWidth, vpHeight);
            }
            float scale = 0.44f;
            float tw = renderer.textWidth(label, scale);
            UiColor color = current ? UiTheme.TEXT_PRIMARY
                : enabled() ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_MUTED;
            renderer.drawText(label, x + (w - tw) / 2f, y + h / 2f - 5f, color, scale, vpWidth, vpHeight);
        }
    }

    /**
     * Coupe {@code text} en au plus deux lignes tenant dans {@code maxW} — la
     * seconde est tronquée par {@link UiRenderer#truncate}. Découpe aux
     * espaces ; un mot plus long que la ligne est laissé à la troncature.
     */
    private static String[] wrapTwoLines(UiRenderer renderer, String text, float scale, float maxW) {
        String flat = text.replace('\n', ' ').trim();
        if (renderer.textWidth(flat, scale) <= maxW) return new String[]{ flat, null };
        int cut = -1;
        for (int i = flat.indexOf(' '); i >= 0; i = flat.indexOf(' ', i + 1)) {
            if (renderer.textWidth(flat.substring(0, i), scale) > maxW) break;
            cut = i;
        }
        if (cut <= 0) return new String[]{ renderer.truncate(flat, scale, maxW), null };
        return new String[]{ flat.substring(0, cut), renderer.truncate(flat.substring(cut + 1), scale, maxW) };
    }

    // ── Icônes — voir UiRemoteImage (fetch HTTPS direct en mémoire, décodage
    // côté Rust incl. WebP, AUCUNE écriture disque) pour le chargement brut.
    // Ce cache-ci ne garde que la version REDIMENSIONNÉE 84px pour l'affichage
    // (UiRemoteImage.CACHE garde, lui, l'image source telle que décodée) —
    // deux caches séparés pour ne pas re-redimensionner à chaque frame.

    // STATIQUE et PARTAGÉE entre resource packs ET shaders (projectId
    // Modrinth globalement unique, aucun risque de collision).
    private static final Map<String, BufferedImage> RESIZED_ICON_CACHE = new ConcurrentHashMap<>();
    private static final int ICON_SIZE = 84; // doit correspondre à iconSize plafonné dans ResultCard.draw()

    /** Version 84px de l'icône d'un projet, ou {@code null} si pas encore chargée (voir UiRemoteImage.get, non-bloquant) — redimensionne UNE SEULE FOIS (pas à chaque frame) dès que la source devient disponible. */
    private static BufferedImage resizedIcon(String projectId, String iconUrl) {
        BufferedImage cached = RESIZED_ICON_CACHE.get(projectId);
        if (cached != null) return cached;
        BufferedImage raw = UiRemoteImage.get(iconUrl);
        if (raw == null) return null; // pas encore chargé — pastille-lettre en attendant, voir ResultCard.draw()

        BufferedImage resized = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(raw, 0, 0, ICON_SIZE, ICON_SIZE, null);
        } finally {
            g.dispose();
        }
        RESIZED_ICON_CACHE.put(projectId, resized);
        return resized;
    }

    // ── Délégations pour ModrinthProjectDetailScreen — garde TOUTE la logique
    // d'installation/recherche centralisée ici (une seule source de vérité
    // pour installingProjectId/busy/statusText), l'écran de détail ne fait
    // que déclencher/lire, jamais dupliquer cette logique.

    /** Délègue l'installation à cette recherche (voir triggerInstall) — même pipeline que le bouton "Installer" d'une carte de résultat. */
    void installFromDetail(ModrinthJson.Hit hit) {
        triggerInstall(hit);
    }

    boolean isInstalling(String projectId) {
        return projectId.equals(installingProjectId);
    }

    boolean isBusy() {
        return busy;
    }

    String statusTextSnapshot() {
        return statusText;
    }

    /** Re-scan du dossier d'install — utilisé par l'écran de détail juste après la fin d'une installation déclenchée depuis lui, pour rafraîchir son propre badge Installé/Installer (scanné une fois à l'ouverture de la liste, pas mis à jour en direct autrement). */
    boolean isNowInstalled(ModrinthJson.Hit hit) {
        return isInstalled(hit, scanInstalledNormalizedNames());
    }

    // Package-private (pas private) : réutilisées telles quelles par
    // ModrinthProjectDetailScreen (même package) — pas de duplication.
    static String formatDownloads(long n) {
        if (n >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000L) return String.format(Locale.ROOT, "%.1fk", n / 1_000.0);
        return String.valueOf(n);
    }

    /**
     * Carte de résultat façon page Modrinth (icône, titre, auteur/stats,
     * description, bouton Installer/Installé) — remplace les simples lignes
     * de texte de la v1 (jugées "pas belles"). Le bouton est la SEULE zone
     * cliquable de la carte (voir contains()) : pas d'action de clic sur le
     * reste de la carte pour l'instant, juste un fond décoratif cohérent
     * avec ModCard (UiMainMenuScreen).
     */
    private final class ResultCard extends UiWidget {
        private static final float BTN_W = 96f, BTN_H = 30f, PAD = 14f, ICON = 64f;
        private static final float TITLE_SCALE = 0.48f, DESC_SCALE = 0.36f;
        /** Description coupée en deux lignes — calculée une fois, pas à chaque image. */
        private String[] descLines;
        private final ModrinthJson.Hit hit;
        private final boolean alreadyInstalled;
        private boolean prevLeftDown;
        // Fondu d'entrée ajouté (voir audit runtime/ui/ : apparition brute
        // dès que le fetch HTTP termine) — voir UiAsyncFade.
        private final UiAsyncFade iconFade = new UiAsyncFade();
        // Léger soulèvement au survol (demande explicite : "que ça soit
        // dynamique") — VISUEL UNIQUEMENT : x/y/w/h (donc contains()/
        // overButtonRect()/pollContinuous(), tous basés sur les champs réels)
        // restent inchangés, seules les coordonnées de DESSIN sont décalées
        // (voir dy dans draw()) — sinon la zone cliquable "courrait après"
        // la carte pendant l'animation, avec un risque de clic manqué juste
        // après le début du survol.
        private static final float HOVER_LIFT_PX = 3f;
        private final UiAnimatedFloat hoverLift = new UiAnimatedFloat(0f, 18f);

        ResultCard(float x, float y, float w, float h, ModrinthJson.Hit hit, boolean alreadyInstalled) {
            super(x, y, w, h);
            this.hit = hit;
            this.alreadyInstalled = alreadyInstalled;
        }

        // Bouton en haut à droite, sur la ligne du titre (structure de la
        // grille du launcher) — plus centré verticalement comme sur l'ancienne
        // carte pleine largeur.
        private float btnX() { return x + w - BTN_W - PAD; }
        private float btnY() { return y + h - PAD - BTN_H; }
        private boolean installing() { return hit.projectId.equals(installingProjectId); }

        /** Zone géométrique du bouton, INDÉPENDANTE de l'état (contrairement à contains()) — sert à EXCLURE cette zone du clic "ouvrir la page de détail" sur le reste de la carte, voir pollContinuous(). */
        private boolean overButtonRect(double mx, double my) {
            float bx = btnX(), by = btnY();
            return mx >= bx && mx <= bx + BTN_W && my >= by && my <= by + BTN_H;
        }

        @Override
        public boolean contains(double mx, double my) {
            if (alreadyInstalled || installing()) return false;
            return overButtonRect(mx, my);
        }

        @Override
        public void onClick() {
            if (!alreadyInstalled && !installing()) triggerInstall(hit);
        }

        /**
         * Clic sur le CORPS de la carte (hors bouton Installer) = ouvre la
         * page de détail — géré ici plutôt que via onClick()/contains() (qui
         * restent dédiés au bouton, voir leur javadoc) car onClick() ne
         * reçoit AUCUNE coordonnée : impossible d'y distinguer "clic sur le
         * bouton" de "clic ailleurs sur la carte" sans changer la signature
         * de UiWidget.onClick() pour TOUS les widgets existants. Même motif
         * que UiTextField (bouton × vs. reste du champ) : pollContinuous()
         * reçoit input.mouseX/mouseY/leftDown en continu, largement suffisant.
         */
        @Override
        public void pollContinuous(UiInputPoller input) {
            boolean justPressed = input.leftDown && !prevLeftDown;
            prevLeftDown = input.leftDown;
            if (!justPressed) return;
            boolean overCard = input.mouseX >= x && input.mouseX <= x + w && input.mouseY >= y && input.mouseY <= y + h;
            if (overCard && !overButtonRect(input.mouseX, input.mouseY)) {
                closeTo(new ModrinthProjectDetailScreen(ModrinthContentScreen.this, hit, alreadyInstalled));
            }
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            // Toujours 1 depuis que la grille est paginée (plus de conteneur
            // défilant pour le faire varier) — gardé : c'est aussi par lui
            // que passerait un fondu d'entrée.
            float fade = clipFade;

            boolean hoveredCard = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
            hoverLift.setTarget(hoveredCard ? 1f : 0f);
            // UiAnimatedFloat.get() avance son horloge interne à CHAQUE appel
            // (voir sa javadoc, "une fois par frame") — lu UNE SEULE FOIS ici,
            // réutilisé partout ci-dessous (dy/ombre/bouton), jamais rappelé.
            float liftT = hoverLift.get();
            float dy = y + liftT * HOVER_LIFT_PX; // Y-up : "vers le haut" = y plus grand

            // Ombre discrète ajoutée (voir audit runtime/ui/ : drawShadow
            // confiné à UiMainMenuScreen) — fade appliqué comme sur le fond
            // de la carte, pour disparaître en même temps qu'elle au bord du
            // scroll plutôt que de rester visible un instant après elle.
            // Légèrement plus prononcée qu'au repos au maximum du survol :
            // accentue la sensation de carte qui se soulève, pas juste "qui glisse".
            float shadowBlur = 6f + liftT * 4f;
            float shadowAlpha = (60 + liftT * 30) * fade / 255f;
            renderer.drawShadow(x, dy, x + w, dy + h, UiTheme.RADIUS_MD, shadowBlur, 0f,
                new UiColor(0f, 0f, 0f, shadowAlpha), vpWidth, vpHeight);
            // Carte de verre (rework 2026-08-27) — le survol DÉTEND la teinte
            // (plus de décor flouté visible à travers) au lieu d'éclaircir un
            // aplat, comme sur l'écran principal. Le fondu de bord de scroll
            // passe par la couleur de repli, qui pilote AUSSI l'opacité du
            // verre (voir UiRenderer#drawGlassPanel).
            float glassStrength = UiTheme.GLASS_STRENGTH_CARD - liftT * 0.1f;
            UiColor resBorder = renderer.isGlassAvailable()
                ? UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.GLASS_BORDER_HOVER, liftT).multiplyAlpha(fade) : null;
            renderer.drawGlassPanel(x, dy, x + w, dy + h, UiTheme.RADIUS_MD,
                UiTheme.GLASS_TINT, glassStrength, UiTheme.CARD_BG.multiplyAlpha(fade),
                resBorder, Math.max(1f, UiTheme.scaled(1f)), vpWidth, vpHeight);
            if (renderer.isGlassAvailable()) {
                float hairline = Math.max(1f, UiTheme.scaled(1f));
                renderer.drawRoundedRect(x + UiTheme.RADIUS_MD, dy + h - hairline, x + w - UiTheme.RADIUS_MD, dy + h, 0f,
                    UiTheme.GLASS_HAIRLINE.multiplyAlpha(fade), vpWidth, vpHeight);
            }

            // Icône plafonnée (pas juste h-24) — sinon elle grossit à l'infini
            // avec la hauteur de carte et écrase visuellement le texte.
            float iconSize = Math.min(h - 32f, ICON);
            float iconY = dy + (h - iconSize) / 2f;
            // Vraie icône du pack (voir resizedIcon/UiRemoteImage) une
            // fois disponible ; pastille-lettre en attendant (état "en cours
            // de chargement", jamais un blocage) — remplace l'ancien
            // placeholder permanent. drawIcon ne supporte pas de teinte
            // (dessine la texture telle quelle, voir sa javadoc) — masquée
            // une fois quasiment invisible plutôt que de rester à pleine
            // opacité alors que le reste de la carte s'est déjà estompé.
            if (fade > 0.05f) {
                BufferedImage icon = resizedIcon(hit.projectId, hit.iconUrl);
                if (icon != null) {
                    iconFade.markReady();
                    renderer.drawIcon(hit.projectId, icon, x + 14, iconY, iconSize, iconSize,
                        iconFade.alpha() * fade, vpWidth, vpHeight);
                } else {
                    renderer.drawRoundedRect(x + 14, iconY, x + 14 + iconSize, iconY + iconSize,
                        UiTheme.RADIUS_SM, UiTheme.ACCENT_DIM.multiplyAlpha(fade), vpWidth, vpHeight);
                    String initial = (hit.title == null || hit.title.isEmpty()) ? "?" : hit.title.substring(0, 1).toUpperCase(Locale.ROOT);
                    float iw = renderer.textWidth(initial, 0.75f);
                    renderer.drawText(initial, x + 14 + (iconSize - iw) / 2f, iconY + iconSize / 2f - 9f,
                        UiTheme.ACCENT.multiplyAlpha(fade), 0.75f, vpWidth, vpHeight);
                }
            }

            float textX = x + 14 + iconSize + 14;
            float textMaxW = btnX() - 12f - textX;

            // Carte compacte : titre en gras sur la ligne du bouton, puis la
            // description sur deux lignes. Auteur et téléchargements ne sont
            // plus ici — ils sont sur la page de détail, qu'un clic sur la
            // carte ouvre. Le gras est un peu plus large que la mesure
            // (truncate mesure en maigre), d'où la marge de 6 %.
            renderer.drawText(UiFont.BOLD, renderer.truncate(hit.title, TITLE_SCALE, textMaxW * 0.94f),
                textX, dy + h - PAD - 19f, UiTheme.TEXT_PRIMARY.multiplyAlpha(fade), TITLE_SCALE, vpWidth, vpHeight);

            if (hit.description != null && !hit.description.isEmpty()) {
                if (descLines == null) descLines = wrapTwoLines(renderer, hit.description, DESC_SCALE, textMaxW);
                UiColor descColor = UiTheme.TEXT_SECONDARY.multiplyAlpha(fade);
                renderer.drawText(descLines[0], textX, dy + h - PAD - 42f, descColor, DESC_SCALE, vpWidth, vpHeight);
                if (descLines[1] != null) {
                    renderer.drawText(descLines[1], textX, dy + h - PAD - 60f, descColor, DESC_SCALE, vpWidth, vpHeight);
                }
            }

            boolean installing = installing();
            boolean hoverBtn = contains(mouseX, mouseY);
            UiColor btnColor = installing ? UiTheme.ACCENT_DIM
                : alreadyInstalled ? UiTheme.PANEL_BG_ALT
                : (hoverBtn ? UiTheme.ACCENT : UiTheme.CARD_HOVER);
            // btnY() + lift (PAS btnY() seul) : le bouton suit visuellement le
            // soulèvement de la carte — sa vraie zone cliquable (btnY(), lue
            // par contains()/overButtonRect() ci-dessus, jamais modifiée) reste
            // volontairement à la position non-soulevée, voir javadoc de hoverLift.
            float liftedBtnY = btnY() + liftT * HOVER_LIFT_PX;
            renderer.drawRoundedRect(btnX(), liftedBtnY, btnX() + BTN_W, liftedBtnY + BTN_H,
                UiTheme.RADIUS_SM, btnColor.multiplyAlpha(fade), vpWidth, vpHeight);
            String label = installing ? spinnerFrame() + " Installation" : (alreadyInstalled ? "Installé" : "Installer");
            float lw = renderer.textWidth(label, 0.4f);
            renderer.drawText(label, btnX() + (BTN_W - lw) / 2f, liftedBtnY + BTN_H / 2f - 5f,
                (alreadyInstalled && !installing) ? UiTheme.TEXT_SECONDARY.multiplyAlpha(fade) : UiTheme.TEXT_PRIMARY.multiplyAlpha(fade), 0.4f, vpWidth, vpHeight);
        }
    }

    /** Frame de spinner ASCII (rotation continue, ~120ms/frame) — pas de dépendance à une police à glyphes étendus. Package-private, réutilisée par ModrinthProjectDetailScreen. */
    static String spinnerFrame() {
        char[] frames = {'|', '/', '-', '\\'};
        return String.valueOf(frames[(int) ((System.currentTimeMillis() / 120L) % frames.length)]);
    }

    // ── Installation ───────────────────────────────────────────────────────────

    private void triggerInstall(ModrinthJson.Hit hit) {
        if (busy) return;
        busy = true;
        installingProjectId = hit.projectId;
        statusText = "Téléchargement de " + hit.title + "...";

        Thread t = new Thread(() -> doInstall(hit), "LauncherAgent-ModrinthInstall");
        t.setDaemon(true);
        t.start();
    }

    private void doInstall(ModrinthJson.Hit hit) {
        try {
            String fileJson = ContentBridge.getLatestFile(hit.projectId);
            String error = ModrinthJson.jsonString(fileJson, "error");
            if (error != null) {
                statusText = "Erreur Modrinth : " + error;
                return;
            }
            String url = ModrinthJson.jsonString(fileJson, "url");
            String filename = ModrinthJson.jsonString(fileJson, "filename");
            if (url == null || filename == null) {
                statusText = "Aucun fichier disponible pour " + hit.title;
                return;
            }

            Path installDir = installDir();
            Path dest = installDir.resolve(filename);
            Files.createDirectories(installDir);
            boolean ok = ContentBridge.downloadFile(url, dest.toString());
            statusText = ok
                ? hit.title + " installé · visible dans la liste \"Disponibles\""
                : "Échec du téléchargement de " + hit.title;
            // Réaffiche les mêmes résultats pour rafraîchir le badge Installé/Installer,
            // sans relancer une recherche réseau (showResults() ne fait que du local).
            if (ok) pendingResults = new SearchResult(shownResults, totalHits);
        } catch (Throwable t) {
            LauncherLog.err("[ModrinthContentScreen] doInstall: " + t);
            statusText = "Erreur installation : " + t;
        } finally {
            busy = false;
            installingProjectId = null;
        }
    }

    // ── Détection "déjà installé" (pur Java, aucune réflexion MC — portable tel quel) ──

    private Set<String> scanInstalledNormalizedNames() {
        Set<String> names = new HashSet<>();
        try {
            Path installDir = installDir();
            if (Files.isDirectory(installDir)) {
                try (java.util.stream.Stream<Path> stream = Files.list(installDir)) {
                    stream.forEach(p -> names.add(normalize(p.getFileName().toString())));
                }
            }
        } catch (Exception e) {
            LauncherLog.warn("[ModrinthContentScreen] scanInstalled: " + e);
        }
        return names;
    }

    private static boolean isInstalled(ModrinthJson.Hit hit, Set<String> installedNormalizedNames) {
        String needle = normalize(hit.title);
        if (needle.isEmpty()) return false;
        for (String name : installedNormalizedNames) {
            if (name.contains(needle)) return true;
        }
        return false;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
