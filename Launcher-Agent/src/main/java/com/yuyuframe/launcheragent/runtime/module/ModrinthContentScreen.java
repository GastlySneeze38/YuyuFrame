package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.content.ContentBridge;
import com.yuyuframe.launcheragent.runtime.content.ModrinthJson;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRemoteImage;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTextField;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

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

    private static final int MAX_RESULTS = 24;

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
    private UiScrollContainer results;
    private boolean layoutBuilt;

    private volatile boolean busy;
    private volatile String statusText = "";
    private volatile List<ModrinthJson.Hit> pendingResults;
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
        selectedCategories.clear(); // catégories propres au type précédent, sans rapport ici
        if (searchField != null) searchField.setPlaceholder(kind.searchPlaceholder);
        if (results != null) results.clear();
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
        super.uiPollInput(input);
        // Écran DERRIÈRE le tiroir totalement inerte pendant qu'il est ouvert
        // (voir modalWidgets()) — la liste de résultats a son PROPRE dispatch
        // de clic indépendant (UiScrollContainer.pollInput lit input.leftClicked
        // directement, pas via UiScreenBase.dispatchClick), donc pas couverte
        // par modalWidgets() : sans cette garde, une "Installer" sous le
        // voile resterait cliquable à travers le tiroir.
        if (!filtersOpen && results != null) results.pollInput(input);
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
        List<ModrinthJson.Hit> results0 = pendingResults;
        if (results0 != null) {
            pendingResults = null;
            showResults(results0);
        }
        // Débounce de la recherche live — voir onSearchTextChanged/SEARCH_DEBOUNCE_MS.
        if (searchPending && System.currentTimeMillis() - lastEditAtMs >= SEARCH_DEBOUNCE_MS) {
            searchPending = false;
            triggerSearch();
        }

        updateDrawerLayout();
        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            renderer.drawText(UiFont.BOLD, kind.headerTitle, MARGIN, screenHeight - 48,
                UiTheme.TEXT_PRIMARY, 0.8f, screenWidth, screenHeight);
            String status = statusText;
            if (!status.isEmpty()) {
                // 40px sous la barre de recherche, encore 40px au-dessus du
                // haut de la liste — marge généreuse des deux côtés (voir
                // STATUS_Y_GAP/HEADER_H, revu suite au chevauchement statut/
                // 1re carte confirmé en jeu). Le tiroir de filtres FLOTTE
                // par-dessus (voir drawDrawer ci-dessous) — ne pousse plus ce
                // gabarit vers le bas comme l'ancien panneau inline.
                renderer.drawText(status, MARGIN, screenHeight - STATUS_Y_GAP, UiTheme.TEXT_SECONDARY, 0.42f, screenWidth, screenHeight);
            }
            if (results != null) results.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
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
    private static final float BACK_W = 110f, BACK_H = 34f;
    private static final float TAB_H = 34f, TAB_W = 160f;
    // Distances depuis le HAUT de l'écran (screenHeight - X) — décalées de
    // +52px (hauteur d'onglet + marge) par rapport à l'ancien agencement sans
    // onglets, pour faire de la place à la ligne Resource Packs/Shaders.
    private static final float TAB_TOP_GAP = 96f;
    private static final float SEARCH_TOP_GAP = 172f;
    private static final float SEARCH_H = 40f;
    private static final float SEARCH_BTN_W = 130f;
    private static final float FILTER_BTN_W = 100f;
    private static final float STATUS_Y_GAP = 242f;
    private static final float HEADER_H = 282f;
    private static final float CHIP_H = 28f, CHIP_GAP = 8f;
    // Largeur de la scrollbar (6) + sa marge (4, voir UiScrollContainer) +
    // marge supplémentaire pour ne pas la coller au bord de la carte.
    private static final float SCROLLBAR_CLEARANCE = 24f;

    private void buildLayout() {
        widgets.clear();
        // "Retour" en HAUT-DROITE — l'ancien placement (haut-gauche, sous le
        // titre) chevauchait littéralement le titre (confirmé en jeu).
        widgets.add(new UiButton(screenWidth - MARGIN - BACK_W, screenHeight - MARGIN - BACK_H, BACK_W, BACK_H,
            "Retour", () -> closeTo(lastScreen)));

        // Onglets Resource Packs / Shaders — Shaders SEULEMENT si un loader de
        // shaders compatible est détecté (même garde que l'ancien bouton
        // "Shaders..." de GameMenuScreenMixin, supprimé : un shaderpack
        // installé sans loader ne sert à rien). Repli sur Resource Packs si
        // l'onglet Shaders était actif mais le loader a disparu entre-temps
        // (peu probable en pratique — mods rechargés seulement au lancement —
        // mais évite un écran bloqué sur un onglet qui n'existe plus).
        boolean shadersAvailable = com.yuyuframe.launcheragent.runtime.fabric.ShaderLoaderDetector.isPresent(getClass().getClassLoader());
        if (!shadersAvailable && kind == ContentKind.SHADER) kind = ContentKind.RESOURCE_PACK;
        float tabY = screenHeight - TAB_TOP_GAP - TAB_H;
        widgets.add(new TabButton(MARGIN, tabY, TAB_W, TAB_H, ContentKind.RESOURCE_PACK));
        if (shadersAvailable) {
            widgets.add(new TabButton(MARGIN + TAB_W + 10f, tabY, TAB_W, TAB_H, ContentKind.SHADER));
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
        widgets.add(searchField);
        widgets.add(new UiButton(MARGIN + searchW + 16f, searchY, SEARCH_BTN_W, SEARCH_H, "Chercher", this::triggerSearch));
        boolean anyFilterActive = sort != SortOrder.DEFAULT || currentVersionOnly || !selectedCategories.isEmpty();
        widgets.add(new UiButton(MARGIN + searchW + 16f + SEARCH_BTN_W + 10f, searchY, FILTER_BTN_W, SEARCH_H,
            "Filtres" + (anyFilterActive ? " •" : ""), this::toggleFilters));

        results = new UiScrollContainer(MARGIN, MARGIN, screenWidth - MARGIN * 2, screenHeight - MARGIN - HEADER_H);
        // Ré-affiche les résultats DÉJÀ reçus à la nouvelle géométrie — PAS un
        // nouvel appel réseau, showResults() est purement local. Vide au tout
        // premier appel (avant la toute première recherche) : rien à réafficher.
        if (!shownResults.isEmpty()) showResults(shownResults);
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
    private static final float DRAWER_W = 380f;
    private static final float DRAWER_MARGIN = 20f;
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
        renderer.drawRoundedRect(drawerX, 0, screenWidth, screenHeight, 0, UiTheme.PANEL_BG, screenWidth, screenHeight);
        // Liseré ACCENT au bord gauche — sépare visuellement le tiroir du
        // reste (drawShadow existe mais compose TOUJOURS après Blaze3D sur
        // era E, voir sa javadoc — un vrai risque de retomber sur le même bug
        // de z-order déjà rencontré plusieurs fois cette session ; un simple
        // liseré plein via drawRoundedRect, lui, passe par le même chemin
        // Blaze3D que le reste, aucun risque de composition).
        renderer.drawRoundedRect(drawerX, 0, drawerX + 3f, screenHeight, 0, UiTheme.ACCENT, screenWidth, screenHeight);

        renderer.drawText(UiFont.BOLD, "Filtres", drawerX + DRAWER_MARGIN, screenHeight - 40f, UiTheme.TEXT_PRIMARY, 0.56f, screenWidth, screenHeight);
        renderer.drawText("Trier par", drawerX + DRAWER_MARGIN, drawerSortLabelY, UiTheme.TEXT_MUTED, 0.4f, screenWidth, screenHeight);
        if (drawerHasVersionChip) {
            renderer.drawText("Version", drawerX + DRAWER_MARGIN, drawerVersionLabelY, UiTheme.TEXT_MUTED, 0.4f, screenWidth, screenHeight);
        }
        renderer.drawText("Catégories", drawerX + DRAWER_MARGIN, drawerCatLabelY, UiTheme.TEXT_MUTED, 0.4f, screenWidth, screenHeight);

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

        DrawerCloseButton(float w, float h) { super(0, 0, w, h); }

        @Override
        public void onClick() { toggleFilters(); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            UiColor bg = UiColor.lerp(UiTheme.PANEL_BG_ALT, UiTheme.CARD_HOVER, hoverAnim.get());
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            float scale = 0.5f;
            float tw = renderer.textWidth("×", scale); // × (multiplication, U+00D7 — Latin-1 Supplement, supporté par UiFont)
            renderer.drawText("×", x + (w - tw) / 2f, y + h / 2f - 6f, UiTheme.TEXT_SECONDARY, scale, vpWidth, vpHeight);
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
    private void buildDrawer() {
        drawerContent.clear();
        drawerContentBaseX.clear();
        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
        float chipScale = 0.4f;
        float maxLocalX = DRAWER_W - DRAWER_MARGIN;

        float closeSize = 28f;
        addDrawerWidget(new DrawerCloseButton(closeSize, closeSize), DRAWER_W - DRAWER_MARGIN - closeSize, screenHeight - 54f);

        float y = screenHeight - 96f;
        drawerSortLabelY = y;
        y -= 26f;
        float x = DRAWER_MARGIN;
        for (SortOrder s : SortOrder.values()) {
            float w = renderer.textWidth(s.label, chipScale) + 24f;
            if (x + w > maxLocalX) {
                x = DRAWER_MARGIN;
                y -= CHIP_H + CHIP_GAP;
            }
            addDrawerWidget(new FilterChip(0, 0, w, CHIP_H, s.label, () -> sort == s, () -> {
                sort = s;
                triggerSearch();
            }), x, y);
            x += w + CHIP_GAP;
        }
        y -= CHIP_H + CHIP_GAP + 14f;

        String mcVersion = System.getProperty("launcheragent.mcVersion", "");
        drawerHasVersionChip = !mcVersion.isEmpty() && !"unknown".equals(mcVersion);
        if (drawerHasVersionChip) {
            drawerVersionLabelY = y;
            y -= 26f;
            String versionLabel = "Version " + mcVersion + " uniquement";
            float vw = renderer.textWidth(versionLabel, chipScale) + 24f;
            addDrawerWidget(new FilterChip(0, 0, vw, CHIP_H, versionLabel, () -> currentVersionOnly, () -> {
                currentVersionOnly = !currentVersionOnly;
                triggerSearch();
            }), DRAWER_MARGIN, y);
            y -= CHIP_H + CHIP_GAP + 14f;
        }

        drawerCatLabelY = y;
        y -= 26f;
        x = DRAWER_MARGIN;
        for (String cat : kind.categories) {
            float w = renderer.textWidth(cat, chipScale) + 24f;
            if (x + w > maxLocalX) {
                x = DRAWER_MARGIN;
                y -= CHIP_H + CHIP_GAP;
            }
            addDrawerWidget(new FilterChip(0, 0, w, CHIP_H, cat, () -> selectedCategories.contains(cat), () -> {
                if (!selectedCategories.remove(cat)) selectedCategories.add(cat);
                triggerSearch();
            }), x, y);
            x += w + CHIP_GAP;
        }
        y -= CHIP_H + CHIP_GAP + 24f;

        addDrawerWidget(new UiButton(0, 0, DRAWER_W - DRAWER_MARGIN * 2, 34f, "Réinitialiser les filtres", this::resetFilters),
            DRAWER_MARGIN, y - 34f);

        drawerAllWidgets.clear();
        drawerAllWidgets.add(drawerBackdrop);
        drawerAllWidgets.addAll(drawerContent);
    }

    private void resetFilters() {
        selectedCategories.clear();
        sort = SortOrder.DEFAULT;
        currentVersionOnly = false;
        triggerSearch();
    }

    /** "Pastille" de filtre cliquable (tri/version/catégorie) — plein ACCENT si actif, léger survol sinon. Même esprit visuel que TabButton, en plus petit/compact. */
    private final class FilterChip extends UiWidget {
        private final String label;
        private final java.util.function.BooleanSupplier active;
        private final Runnable onToggle;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        FilterChip(float x, float y, float w, float h, String label, java.util.function.BooleanSupplier active, Runnable onToggle) {
            super(x, y, w, h);
            this.label = label;
            this.active = active;
            this.onToggle = onToggle;
        }

        @Override
        public void onClick() { onToggle.run(); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean isActive = active.getAsBoolean();
            hoverAnim.setTarget(!isActive && contains(mouseX, mouseY) ? 1f : 0f);
            UiColor bg = isActive ? UiTheme.ACCENT : UiColor.lerp(UiTheme.PANEL_BG_ALT, UiTheme.CARD_HOVER, hoverAnim.get());
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            float scale = 0.4f;
            float tw = renderer.textWidth(label, scale);
            renderer.drawText(label, x + (w - tw) / 2f, y + h / 2f - 4f,
                isActive ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_SECONDARY, scale, vpWidth, vpHeight);
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
    }

    private void triggerSearch() {
        searchPending = false; // une recherche manuelle (Entrée/bouton) rend le débounce en cours obsolète
        if (busy) return;
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
        busy = true;
        statusText = query.isEmpty() ? "Recommandations en cours..." : "Recherche en cours...";

        Thread t = new Thread(() -> doSearch(query, searchType, categoriesCsv, version, sortValue), "LauncherAgent-ModrinthSearch");
        t.setDaemon(true);
        t.start();
    }

    private void doSearch(String query, String searchType, String categoriesCsv, String version, String sortValue) {
        try {
            if (!ContentBridge.ensureLoaded()) {
                statusText = "content_core.dll indisponible";
                return;
            }
            String json = ContentBridge.searchModrinth(query, searchType, categoriesCsv, version, sortValue);
            String error = ModrinthJson.jsonString(json, "error");
            if (error != null) {
                statusText = "Erreur Modrinth : " + error;
                return;
            }
            List<ModrinthJson.Hit> hits = ModrinthJson.parseHits(json, MAX_RESULTS);
            pendingResults = hits;
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
        statusText = hits.isEmpty() ? "Aucun résultat" : hits.size() + " résultat(s)";

        results.clear();
        Set<String> installed = scanInstalledNormalizedNames();
        // rowH/rowGap augmentés — la description (près du bas de la carte)
        // débordait dans l'écart entre cartes avec les anciennes valeurs plus
        // serrées (chevauchement confirmé en jeu).
        float rowH = 128f;
        float rowGap = 26f;
        // La carte occupait TOUTE la largeur du viewport, jusque dans la zone
        // où UiScrollContainer dessine sa scrollbar (tx = vx+vw-10) — les deux
        // se chevauchaient forcément (confirmé en jeu). SCROLLBAR_CLEARANCE
        // réserve la place, que la scrollbar soit visible ou non (peu de
        // résultats = pas de scrollbar, mais la carte doit rester à la même
        // largeur pour ne pas "sauter" visuellement selon le nombre de résultats).
        float rowW = screenWidth - MARGIN * 2 - SCROLLBAR_CLEARANCE;
        float top = -8f;
        for (int i = 0; i < hits.size(); i++) {
            ModrinthJson.Hit hit = hits.get(i);
            float rowY = top - i * (rowH + rowGap) - rowH;
            boolean already = isInstalled(hit, installed);
            results.add(new ResultCard(MARGIN, rowY, rowW, rowH, hit, already));
        }
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

    private static String formatDownloads(long n) {
        if (n >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000L) return String.format(Locale.ROOT, "%.1fk", n / 1_000.0);
        return String.valueOf(n);
    }

    /** Tronque {@code text} (avec "...") pour tenir dans {@code maxWidth} pixels à l'échelle donnée — sinon un titre/description long déborde par-dessus le bouton Installer. */
    private static String truncate(UiRenderer renderer, String text, float scale, float maxWidth) {
        if (text == null) return "";
        if (maxWidth <= 0 || renderer.textWidth(text, scale) <= maxWidth) return text;
        String ellipsis = "...";
        int len = text.length();
        while (len > 0 && renderer.textWidth(text.substring(0, len) + ellipsis, scale) > maxWidth) len--;
        return len <= 0 ? ellipsis : text.substring(0, len) + ellipsis;
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
        private static final float BTN_W = 130f, BTN_H = 34f;
        private final ModrinthJson.Hit hit;
        private final boolean alreadyInstalled;

        ResultCard(float x, float y, float w, float h, ModrinthJson.Hit hit, boolean alreadyInstalled) {
            super(x, y, w, h);
            this.hit = hit;
            this.alreadyInstalled = alreadyInstalled;
        }

        private float btnX() { return x + w - BTN_W - 12f; }
        private float btnY() { return y + (h - BTN_H) / 2f; }
        private boolean installing() { return hit.projectId.equals(installingProjectId); }

        @Override
        public boolean contains(double mx, double my) {
            if (alreadyInstalled || installing()) return false;
            float bx = btnX(), by = btnY();
            return mx >= bx && mx <= bx + BTN_W && my >= by && my <= by + BTN_H;
        }

        @Override
        public void onClick() {
            if (!alreadyInstalled && !installing()) triggerInstall(hit);
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            // clipFade (voir UiScrollContainer/UiWidget) : 1 = carte
            // pleinement dans le viewport, dégressif vers 0 en chevauchant le
            // bord haut/bas — remplace le "pop" opaque d'avant (toute la carte
            // devait sortir du viewport pour disparaître, chevauchant le
            // titre/statut au-dessus) par une disparition progressive. PAS de
            // vrai clip pixel ici (Blaze3D era E n'a pas de scissor fiable
            // pour ce pipeline de dessin différé, voir UiScrollContainer) —
            // juste l'opacité de chaque élément de la carte.
            float fade = clipFade;
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_MD, UiTheme.CARD_BG.multiplyAlpha(fade), vpWidth, vpHeight);

            // Icône plafonnée (pas juste h-24) — sinon elle grossit à l'infini
            // avec la hauteur de carte et écrase visuellement le texte.
            float iconSize = Math.min(h - 32f, 84f);
            float iconY = y + (h - iconSize) / 2f;
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
                    renderer.drawIcon(hit.projectId, icon, x + 14, iconY, iconSize, vpWidth, vpHeight);
                } else {
                    renderer.drawRoundedRect(x + 14, iconY, x + 14 + iconSize, iconY + iconSize,
                        UiTheme.RADIUS_SM, UiTheme.ACCENT_DIM.multiplyAlpha(fade), vpWidth, vpHeight);
                    String initial = (hit.title == null || hit.title.isEmpty()) ? "?" : hit.title.substring(0, 1).toUpperCase(Locale.ROOT);
                    float iw = renderer.textWidth(initial, 0.75f);
                    renderer.drawText(initial, x + 14 + (iconSize - iw) / 2f, iconY + iconSize / 2f - 9f,
                        UiTheme.ACCENT.multiplyAlpha(fade), 0.75f, vpWidth, vpHeight);
                }
            }

            float textX = x + 14 + iconSize + 18;
            float textMaxW = btnX() - 16f - textX;

            // Titre/meta/description nettement plus espacés (chevauchement
            // confirmé en jeu avec les anciens écarts, trop serrés une fois
            // les échelles de texte agrandies) — répartis sur toute la
            // hauteur désormais disponible (rowH=128) plutôt que tassés en
            // haut/bas de la carte.
            renderer.drawText(truncate(renderer, hit.title, 0.56f, textMaxW), textX, y + h - 34,
                UiTheme.TEXT_PRIMARY.multiplyAlpha(fade), 0.56f, vpWidth, vpHeight);

            String meta = (hit.author != null && !hit.author.isEmpty() ? hit.author + "  ·  " : "")
                + formatDownloads(hit.downloads) + " téléchargements";
            renderer.drawText(truncate(renderer, meta, 0.42f, textMaxW), textX, y + h - 62,
                UiTheme.TEXT_SECONDARY.multiplyAlpha(fade), 0.42f, vpWidth, vpHeight);

            if (hit.description != null && !hit.description.isEmpty()) {
                renderer.drawText(truncate(renderer, hit.description, 0.4f, textMaxW), textX, y + 22,
                    UiTheme.TEXT_MUTED.multiplyAlpha(fade), 0.4f, vpWidth, vpHeight);
            }

            boolean installing = installing();
            boolean hoverBtn = contains(mouseX, mouseY);
            UiColor btnColor = installing ? UiTheme.ACCENT_DIM
                : alreadyInstalled ? UiTheme.PANEL_BG_ALT
                : (hoverBtn ? UiTheme.ACCENT : UiTheme.CARD_HOVER);
            renderer.drawRoundedRect(btnX(), btnY(), btnX() + BTN_W, btnY() + BTN_H,
                UiTheme.RADIUS_SM, btnColor.multiplyAlpha(fade), vpWidth, vpHeight);
            String label = installing ? spinnerFrame() + " Installation" : (alreadyInstalled ? "Installé" : "Installer");
            float lw = renderer.textWidth(label, 0.48f);
            renderer.drawText(label, btnX() + (BTN_W - lw) / 2f, btnY() + BTN_H / 2f - 6f,
                (alreadyInstalled && !installing) ? UiTheme.TEXT_SECONDARY.multiplyAlpha(fade) : UiTheme.TEXT_PRIMARY.multiplyAlpha(fade), 0.48f, vpWidth, vpHeight);
        }
    }

    /** Frame de spinner ASCII (rotation continue, ~120ms/frame) — pas de dépendance à une police à glyphes étendus. */
    private static String spinnerFrame() {
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
            if (ok) pendingResults = shownResults;
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
