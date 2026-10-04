package com.yuyuframe.launcheragent.runtime.ui.ingameui.modrinth;

import com.yuyuframe.launcheragent.runtime.content.ContentBridge;
import com.yuyuframe.launcheragent.runtime.content.ModrinthJson;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAsyncFade;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiLabel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.Block;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.DividerBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.HeadingBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.ImageRowBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.Inline;
import com.yuyuframe.launcheragent.apigraphic.text.UiRichText;
import com.yuyuframe.launcheragent.apigraphic.text.UiTextSpan;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.ListItemBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.ParagraphBlock;
import static com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.parseBlocks;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.DividerWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.InlineIconRow;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.GalleryCarousel;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Page de détail d'un projet Modrinth, à la manière de la page du site :
 * en-tête (icône, titre, auteur, bouton d'état), puis trois onglets —
 * <b>Description</b> (le « body » du projet, mis en page par blocs),
 * <b>Versions</b> (tableau des versions publiées, chacune installable) et
 * <b>Galerie</b> (grille de vignettes, clic = visionneuse). Refaite le
 * 2026-10-04 : avant, galerie et description s'empilaient dans une seule
 * colonne défilante, et seule la dernière version était installable.
 *
 * <p>Ouverte en cliquant sur le CORPS d'une {@code ResultCard} depuis
 * {@link ModrinthContentScreen}, qui reste la SEULE source de vérité pour
 * l'installation et la suppression (cet écran délègue via
 * {@code installFromDetail}/{@code installFileFromDetail}/{@code removeFromDetail},
 * il ne duplique jamais cette logique).
 *
 * <p>« body » mélange markdown ET balises HTML brutes : {@code parseBlocks}
 * en fait une vraie mise en page par blocs (titres, paragraphes, listes,
 * séparateurs, images), voir sa javadoc.
 */
public final class ModrinthProjectDetailScreen extends UiScreenBase {

    private static final int MAX_GALLERY = 24;

    /** Étages de flou — même valeur que les autres écrans (voir UiRenderer#beginGlassFrame). */
    private static final int GLASS_PASSES = 4;
    private static final float MARGIN = 28f;
    private static final float BACK_W = 110f, BACK_H = 34f;
    private static final float STATE_W = 140f, STATE_H = 34f;
    private static final float DEL_H = 26f, DEL_GAP = 6f;
    private static final long DELETE_CONFIRM_MS = 3000L;

    // En-tête : icône à gauche, titre et auteur à sa droite, boutons à droite.
    private static final float ICON = 64f;
    private static final float TITLE_TOP_GAP = 58f, META_TOP_GAP = 84f;
    // Rangée d'onglets, puis le panneau de contenu jusqu'en bas.
    private static final float TABS_TOP_GAP = 112f, TABS_H = 40f, TAB_PAD = 4f;
    private static final float PANEL_TOP_GAP = 166f;
    /** Marge intérieure du panneau, de chaque côté du contenu. */
    private static final float PANEL_PAD = 24f;
    /** Retrait du viewport défilant dans le panneau — voir buildLayout. */
    private static final float VIEWPORT_INSET = 10f;
    /** Place gardée à droite pour la barre de défilement du conteneur. */
    private static final float SCROLLBAR_CLEARANCE = 16f;

    // Images de la description : une image seule et large = bannière ; plusieurs
    // images ou une petite largeur déclarée = rangée de badges (shields.io…).
    private static final float BANNER_H = 280f;
    private static final float ICON_ROW_H = 40f;

    // Onglet Versions.
    private static final float VERSIONS_HEAD_H = 44f;
    private static final float VERSION_ROW_H = 60f;
    private static final float ROW_BTN_W = 96f, ROW_BTN_H = 28f;
    // Onglet Galerie.
    private static final float THUMB_MIN_W = 300f, THUMB_GAP = 16f, THUMB_FOOT_H = 58f;
    private static final int THUMB_MAX_COLS = 4;

    private enum Tab {
        DESCRIPTION("Description"), VERSIONS("Versions"), GALLERY("Galerie");

        final String label;

        Tab(String label) { this.label = label; }
    }

    private final ModrinthContentScreen parent;
    private final ModrinthJson.Hit hit;
    private boolean alreadyInstalled; // rafraîchi quand une installation ou une suppression se termine, voir uiDraw
    private boolean wasWorking;

    private Tab tab = Tab.DESCRIPTION;
    private UiScrollContainer content;
    private boolean layoutBuilt;
    private float panelX, panelY, panelW, panelH; // (panelX, panelY) = coin bas-gauche

    // Chargement du détail (body + galerie) — même motif « pendingXxx consommé
    // une seule fois par le thread de rendu » que ModrinthContentScreen. Le
    // parsing (parseBlocks) tourne sur le thread de fetch : rien que du texte.
    private volatile boolean detailReady;
    private volatile boolean pendingRebuild;
    private volatile List<Block> parsedBlocks = Collections.emptyList();
    private volatile List<ModrinthJson.GalleryItem> gallery = Collections.emptyList();

    // Versions : chargées à la première ouverture de l'onglet seulement.
    private boolean versionsRequested;
    private volatile boolean versionsReady;
    private volatile String versionsError;
    private volatile List<ModrinthJson.Version> versions = Collections.emptyList();
    /** Version du jeu en cours, ou vide si inconnue. */
    private final String mcVersion;
    /** Ne montrer que les versions publiées pour {@link #mcVersion} — c'est la question qu'on se pose en jeu. */
    private boolean currentVersionOnly;

    // Visionneuse de la galerie (modale).
    private GalleryCarousel lightbox;
    private boolean lightboxOpen;
    private final UiAnimatedFloat lightboxAnim = new UiAnimatedFloat(0f, 14f);
    private final List<UiWidget> lightboxWidgets = new ArrayList<>();
    /**
     * Le contenu défilant reste sourd tant que la visionneuse est ouverte, puis
     * jusqu'au relâchement du bouton : le clic qui la ferme serait sinon repris
     * par la vignette qui se trouve dessous.
     */
    private boolean contentMuted;

    public ModrinthProjectDetailScreen(ModrinthContentScreen parent, ModrinthJson.Hit hit, boolean alreadyInstalled) {
        super("Modrinth · " + hit.title);
        this.parent = parent;
        this.hit = hit;
        this.alreadyInstalled = alreadyInstalled;
        String v = System.getProperty("launcheragent.mcVersion", "");
        this.mcVersion = "unknown".equals(v) ? "" : v;
        this.currentVersionOnly = !mcVersion.isEmpty();
        escapeTarget = parent; // Échap = « Retour » vers la liste (la visionneuse a sa propre sortie, voir onModalEscape)
        fetchDetails();
    }

    private void fetchDetails() {
        Thread t = new Thread(() -> {
            try {
                if (!ContentBridge.ensureLoaded()) return;
                String json = ContentBridge.getProject(hit.projectId);
                String error = ModrinthJson.jsonString(json, "error");
                if (error == null) {
                    String rawBody = ModrinthJson.jsonString(json, "body");
                    List<Block> blocks = parseBlocks(rawBody);
                    parsedBlocks = !blocks.isEmpty() ? blocks : parseBlocks(hit.description);
                    gallery = ModrinthJson.parseGallery(json, MAX_GALLERY);
                } else {
                    parsedBlocks = parseBlocks(hit.description);
                    LauncherLog.warn("[ModrinthProjectDetailScreen] getProject(" + hit.projectId + "): " + error);
                }
            } catch (Throwable t2) {
                LauncherLog.err("[ModrinthProjectDetailScreen] fetchDetails: " + t2);
            } finally {
                detailReady = true;
                pendingRebuild = true;
            }
        }, "LauncherAgent-ModrinthDetail-" + hit.projectId);
        t.setDaemon(true);
        t.start();
    }

    private void fetchVersions() {
        versionsRequested = true;
        Thread t = new Thread(() -> {
            try {
                if (!ContentBridge.ensureLoaded()) {
                    versionsError = "content_core.dll indisponible";
                    return;
                }
                String json = ContentBridge.getVersions(hit.projectId);
                String error = ModrinthJson.jsonString(json, "error");
                if (error != null) versionsError = error;
                else versions = ModrinthJson.parseVersions(json);
            } catch (Throwable t2) {
                LauncherLog.err("[ModrinthProjectDetailScreen] fetchVersions: " + t2);
                versionsError = String.valueOf(t2);
            } finally {
                versionsReady = true;
                pendingRebuild = true;
            }
        }, "LauncherAgent-ModrinthVersions-" + hit.projectId);
        t.setDaemon(true);
        t.start();
    }

    /** Même voile léger que la liste : un fond opaque ne laisserait rien à flouter aux panneaux de verre. */
    @Override
    protected UiColor overlayColor() {
        return UiRenderer.get(getClass().getClassLoader()).isGlassAvailable()
            ? UiTheme.GLASS_SCRIM
            : UiTheme.OVERLAY_BG;
    }

    @Override
    protected List<UiWidget> modalWidgets() {
        return lightboxOpen ? lightboxWidgets : null;
    }

    @Override
    protected void onModalEscape() {
        lightboxOpen = false;
    }

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        if (lightboxOpen) contentMuted = true;
        else if (!input.leftDown) contentMuted = false;
        if (content != null && !contentMuted) content.pollInput(input);
    }

    private boolean working() {
        return parent.isInstalling(hit.projectId) || parent.isRemoving(hit.projectId);
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (!layoutBuilt && screenWidth > 0 && screenHeight > 0) {
            buildLayout();
            layoutBuilt = true;
        }
        // Une installation ou une suppression vient de se terminer : le badge
        // de l'en-tête et ceux des lignes de version sont à relire sur le disque.
        boolean nowWorking = working();
        if (wasWorking && !nowWorking) {
            alreadyInstalled = parent.isNowInstalled(hit);
            pendingRebuild = true;
        }
        wasWorking = nowWorking;
        // Une image de la description vient d'arriver (ou d'échouer) : sa
        // vraie taille est connue, la page se remet en place autour d'elle.
        if (tab == Tab.DESCRIPTION && !pendingImages.isEmpty()) {
            for (String url : pendingImages) {
                if (UiRemoteImage.get(url) != null || UiRemoteImage.failed(url)) {
                    pendingRebuild = true;
                    break;
                }
            }
        }
        if (pendingRebuild && content != null) {
            pendingRebuild = false;
            float keep = content.scrollOffset();
            buildContent();
            content.restoreScroll(keep);
        }

        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
        // Chaîne de flou partagée — voir UiRenderer#beginGlassFrame.
        renderer.beginGlassFrame(GLASS_PASSES, screenWidth, screenHeight);
        try {
            // L'ordre de dessin fait l'ordre Z, et c'est lui qui tient lieu de
            // découpe : Blaze3D n'a pas de vrai clip, donc ce qui défile hors
            // du panneau doit être RECOUVERT. D'où : fond (super), panneau,
            // contenu défilant, en-tête de colonnes des versions, puis le
            // CADRE de verre de l'écran (tout sauf le panneau, opaque dans ses
            // bornes), et seulement ensuite l'en-tête et ses boutons.
            super.uiDraw(mouseX, mouseY);
            drawPanel(renderer);
            if (content != null) {
                // Sous la visionneuse, le contenu ne réagit pas non plus au survol.
                double mx = contentMuted ? -1e6 : mouseX, my = contentMuted ? -1e6 : mouseY;
                content.draw(renderer, mx, my, screenWidth, screenHeight);
            }
            if (tab == Tab.VERSIONS) drawVersionsHead(renderer);
            drawFrame(renderer);
            // Les widgets de l'en-tête ont déjà été dessinés par super, sous le
            // cadre : on les redessine par-dessus. (Leur liste ne contient que
            // l'en-tête ; le contenu défilant vit dans `content`.)
            double hx = lightboxOpen ? -1e6 : mouseX, hy = lightboxOpen ? -1e6 : mouseY;
            for (UiWidget w : widgets) w.draw(renderer, hx, hy, screenWidth, screenHeight);
            drawHeader(renderer);
            drawLightbox(renderer, mouseX, mouseY);
            drawRevealVeil(renderer);
        } catch (Throwable t) {
            LauncherLog.err("[ModrinthProjectDetailScreen] uiDraw: " + t);
        }
    }

    private void drawHeader(UiRenderer renderer) {
        float iconY = screenHeight - MARGIN - ICON;
        BufferedImage icon = ModrinthContentScreen.resizedIcon(hit.projectId, hit.iconUrl);
        if (icon != null) {
            renderer.drawIcon(hit.projectId, icon, MARGIN, iconY, ICON, ICON, 1f, screenWidth, screenHeight);
        } else {
            renderer.drawRoundedRect(MARGIN, iconY, MARGIN + ICON, iconY + ICON, UiTheme.RADIUS_SM,
                UiTheme.ACCENT_DIM, screenWidth, screenHeight);
        }

        float textX = MARGIN + ICON + 16f;
        float textMaxW = stateButtonX() - 20f - textX;
        // Posés sur le décor (voile léger) : ombre portée, comme le titre de la liste.
        renderer.drawTextShadowed(UiFont.BOLD, renderer.truncate(hit.title, 0.66f, textMaxW * 0.94f),
            textX, screenHeight - TITLE_TOP_GAP, UiTheme.TEXT_PRIMARY, UiTheme.TEXT_SHADOW, 1f, -1f, 0.66f,
            screenWidth, screenHeight);
        String meta = (hit.author != null && !hit.author.isEmpty() ? hit.author + "  ·  " : "")
            + ModrinthContentScreen.formatDownloads(hit.downloads) + " téléchargements";
        renderer.drawTextShadowed(UiFont.REGULAR, meta, textX, screenHeight - META_TOP_GAP,
            UiTheme.TEXT_SECONDARY, UiTheme.TEXT_SHADOW, 1f, -1f, 0.4f, screenWidth, screenHeight);

        // Statut de l'installation/suppression en cours, sur la rangée des
        // onglets, à droite. Jamais hors de ces moments : parent.statusText est
        // partagé avec le compte de résultats de la liste.
        if (working()) {
            String status = parent.statusTextSnapshot();
            if (status != null && !status.isEmpty()) {
                float sw = renderer.textWidth(status, 0.38f);
                float right = tab == Tab.VERSIONS && !mcVersion.isEmpty()
                    ? versionChipX() - 16f : screenWidth - MARGIN;
                renderer.drawTextShadowed(UiFont.REGULAR, status, right - sw,
                    screenHeight - TABS_TOP_GAP - TABS_H / 2f - 5f,
                    UiTheme.TEXT_SECONDARY, UiTheme.TEXT_SHADOW, 1f, -1f, 0.38f, screenWidth, screenHeight);
            }
        }
    }

    /**
     * Cadre de verre : les quatre bandes de l'écran qui entourent le panneau.
     * Un panneau de verre est opaque dans ses bornes (décor flouté + teinte),
     * donc ces bandes cachent tout ce que le contenu défilant laisse dépasser
     * — images hautes, lignes en cours de fondu. Teinte plus légère que le
     * panneau, pour qu'il se détache. Sans verre, un aplat opaque fait le
     * même travail.
     */
    private void drawFrame(UiRenderer renderer) {
        float left = panelX, right = panelX + panelW, bottom = panelY, top = panelY + panelH;
        UiColor flat = new UiColor(8, 8, 12, 255);
        float s = FRAME_STRENGTH;
        renderer.drawGlassPanel(0, top, screenWidth, screenHeight, 0f, UiTheme.GLASS_TINT, s, flat, screenWidth, screenHeight);
        renderer.drawGlassPanel(0, 0, screenWidth, bottom, 0f, UiTheme.GLASS_TINT, s, flat, screenWidth, screenHeight);
        renderer.drawGlassPanel(0, bottom, left, top, 0f, UiTheme.GLASS_TINT, s, flat, screenWidth, screenHeight);
        renderer.drawGlassPanel(right, bottom, screenWidth, top, 0f, UiTheme.GLASS_TINT, s, flat, screenWidth, screenHeight);
    }

    /** Teinte du cadre — plus légère que celle du panneau (GLASS_STRENGTH_PANEL). */
    private static final float FRAME_STRENGTH = 0.5f;

    /** Panneau de verre du contenu. */
    private void drawPanel(UiRenderer renderer) {
        UiColor border = renderer.isGlassAvailable() ? UiTheme.GLASS_BORDER : null;
        renderer.drawGlassPanel(panelX, panelY, panelX + panelW, panelY + panelH, UiTheme.RADIUS_MD,
            UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_PANEL, UiTheme.PANEL_BG,
            border, Math.max(1f, UiTheme.scaled(1f)), screenWidth, screenHeight);
    }

    /**
     * En-tête de colonnes de l'onglet Versions — fixe, hors défilement. Sa
     * bande est un morceau du même verre que le panneau (opaque dans ses
     * bornes), coins hauts arrondis comme lui.
     */
    private void drawVersionsHead(UiRenderer renderer) {
        float headY = panelY + panelH - VERSIONS_HEAD_H;
        renderer.drawGlassPanel(panelX, headY, panelX + panelW, panelY + panelH,
            UiTheme.RADIUS_MD, UiTheme.RADIUS_MD, 0f, 0f,
            UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_PANEL, UiTheme.PANEL_BG, screenWidth, screenHeight);
        float baseline = headY + VERSIONS_HEAD_H / 2f - 5f;
        float x0 = contentX(), w = innerW();
        UiColor c = UiTheme.TEXT_PRIMARY;
        renderer.drawText(UiFont.BOLD, "Version", colVersion(x0, w), baseline, c, 0.4f, screenWidth, screenHeight);
        renderer.drawText(UiFont.BOLD, "Version du jeu", colGame(x0, w), baseline, c, 0.4f, screenWidth, screenHeight);
        renderer.drawText(UiFont.BOLD, "Plateforme", colLoaders(x0, w), baseline, c, 0.4f, screenWidth, screenHeight);
        renderer.drawText(UiFont.BOLD, "Publiée", colPublished(x0, w), baseline, c, 0.4f, screenWidth, screenHeight);
        renderer.drawText(UiFont.BOLD, "Téléchargements", colDownloads(x0, w), baseline, c, 0.4f, screenWidth, screenHeight);
        renderer.drawRoundedRect(panelX + 1f, headY, panelX + panelW - 1f, headY + 1f, 0f,
            UiTheme.TRACK_OFF, screenWidth, screenHeight);
    }

    // ── Géométrie ────────────────────────────────────────────────────────────

    private float stateButtonX() { return screenWidth - MARGIN - BACK_W - 10f - STATE_W; }
    private float stateButtonY() { return screenHeight - MARGIN - STATE_H; }
    private float contentX() { return panelX + PANEL_PAD; }
    private float innerW() { return panelW - PANEL_PAD * 2 - SCROLLBAR_CLEARANCE; }

    // Colonnes du tableau des versions, en fractions de la largeur utile.
    private static float colVersion(float x0, float w) { return x0 + 44f; }
    private static float colGame(float x0, float w) { return x0 + w * 0.20f; }
    private static float colLoaders(float x0, float w) { return x0 + w * 0.52f; }
    private static float colPublished(float x0, float w) { return x0 + w * 0.68f; }
    private static float colDownloads(float x0, float w) { return x0 + w * 0.79f; }

    private String versionChipLabel() { return "Version du jeu : " + mcVersion; }

    private float versionChipX() {
        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
        return screenWidth - MARGIN - (renderer.textWidth(versionChipLabel(), 0.4f) + 28f);
    }

    private void buildLayout() {
        widgets.clear();
        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());

        widgets.add(new UiButton(screenWidth - MARGIN - BACK_W, screenHeight - MARGIN - BACK_H, BACK_W, BACK_H,
            "Retour", () -> closeTo(parent)));
        widgets.add(new StateButton(stateButtonX(), stateButtonY(), STATE_W, STATE_H));
        widgets.add(new DeleteButton(stateButtonX(), stateButtonY() - DEL_GAP - DEL_H, STATE_W, DEL_H));

        // Onglets : une pastille par onglet dans une gélule de verre (TabBar
        // dessine la gélule, et se place AVANT les pastilles dans la liste).
        float tabY = screenHeight - TABS_TOP_GAP - TABS_H;
        float tx = MARGIN + TAB_PAD;
        List<TabPill> pills = new ArrayList<>();
        for (Tab t : Tab.values()) {
            float tw = renderer.textWidth(UiFont.BOLD, t.label, 0.42f) + 34f;
            pills.add(new TabPill(tx, tabY + TAB_PAD, tw, TABS_H - TAB_PAD * 2, t));
            tx += tw + 2f;
        }
        widgets.add(new TabBar(MARGIN, tabY, tx - 2f + TAB_PAD - MARGIN, TABS_H));
        widgets.addAll(pills);

        if (tab == Tab.VERSIONS && !mcVersion.isEmpty()) {
            float cw = renderer.textWidth(versionChipLabel(), 0.4f) + 28f;
            widgets.add(new VersionFilterChip(screenWidth - MARGIN - cw, tabY + TAB_PAD, cw, TABS_H - TAB_PAD * 2));
        }

        panelX = MARGIN;
        panelY = MARGIN;
        panelW = screenWidth - MARGIN * 2;
        panelH = Math.max(1f, screenHeight - PANEL_TOP_GAP - MARGIN);
        // Viewport légèrement rentré dans le panneau. Ce qui dépasse (un
        // widget finit de s'estomper ~46 px APRÈS le bord du viewport, voir
        // UiScrollContainer) est recouvert par le cadre de l'écran (drawFrame).
        // Sur l'onglet Versions, le haut s'arrête sous l'en-tête de colonnes,
        // dont la bande opaque joue le même rôle.
        float top = tab == Tab.VERSIONS
            ? panelY + panelH - VERSIONS_HEAD_H
            : panelY + panelH - VIEWPORT_INSET;
        float bottom = panelY + VIEWPORT_INSET;
        content = new UiScrollContainer(panelX, bottom, panelW, Math.max(1f, top - bottom));
        pendingRebuild = false;
        buildContent();
    }

    private void switchTab(Tab newTab) {
        if (newTab == tab) return;
        tab = newTab;
        if (tab == Tab.VERSIONS && !versionsRequested) fetchVersions();
        buildLayout();
    }

    private void buildContent() {
        content.clear();
        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
        switch (tab) {
            case VERSIONS: buildVersions(renderer); break;
            case GALLERY: buildGallery(); break;
            default: buildDescription(renderer);
        }
    }

    /** Message seul en haut du panneau (chargement, vide, erreur). */
    private void addNotice(String text) {
        content.add(new UiLabel(contentX(), -20f, text, UiTheme.TEXT_MUTED, 0.42f));
    }

    // ── Onglet Description ───────────────────────────────────────────────────

    private void buildDescription(UiRenderer renderer) {
        if (!detailReady) {
            addNotice("Chargement de la description...");
            return;
        }
        List<Block> blocks = parsedBlocks;
        if (blocks.isEmpty()) {
            addNotice("Aucune description disponible.");
            return;
        }
        float x0 = contentX(), w = innerW();
        // Largeur de LECTURE plafonnée : sur un écran large, une ligne de
        // 1800 px ne se relit pas (l'œil perd la ligne suivante).
        float textW = Math.min(w, TEXT_MAX_W);
        // Tout est CENTRÉ dans le panneau (demande du 2026-10-04), comme le
        // sont la plupart des pages Modrinth : la colonne de texte, chaque
        // ligne dans cette colonne, les images et les rangées de badges.
        // Seules les listes restent calées à gauche de la colonne — des puces
        // centrées ligne à ligne ne s'alignent plus entre elles.
        float textX = x0 + (w - textW) / 2f;
        pendingImages.clear();
        float y = -4f;
        boolean first = true;
        for (Block block : blocks) {
            if (block instanceof HeadingBlock) {
                HeadingBlock hb = (HeadingBlock) block;
                float scale = hb.level <= 1 ? H1_SCALE : (hb.level == 2 ? H2_SCALE : H3_SCALE);
                if (!first) y -= hb.level <= 2 ? 26f : 18f;
                y = addRich(spans(hb.inlines, UiTheme.TEXT_PRIMARY, true), textX, y, textW, scale, 1.2f, null, true);
                // Filet sous les grands titres, comme sur la page Modrinth : il
                // découpe la description en sections qu'on repère en défilant.
                if (hb.level <= 2) {
                    y -= 8f;
                    content.add(new DividerWidget(x0, y, w));
                    y -= 16f;
                } else {
                    y -= 8f;
                }
            } else if (block instanceof DividerBlock) {
                y -= 10f;
                content.add(new DividerWidget(x0, y, w));
                y -= 20f;
            } else if (block instanceof ListItemBlock) {
                ListItemBlock lb = (ListItemBlock) block;
                y = addRich(spans(lb.inlines, UiTheme.TEXT_SECONDARY, false), textX + LIST_INDENT, y,
                    textW - LIST_INDENT, P_SCALE, LEADING, lb.bullet, false);
                y -= 4f;
            } else if (block instanceof ImageRowBlock) {
                ImageRowBlock ib = (ImageRowBlock) block;
                boolean banner = ib.images.size() == 1 && (ib.images.get(0).declaredW == null || ib.images.get(0).declaredW > 140);
                if (banner) {
                    y = addImage(ib.images.get(0).url, ib.images.get(0).declaredW, x0, y, w);
                } else {
                    content.add(new InlineIconRow(x0, y - ICON_ROW_H, w, ICON_ROW_H, ib.images).centered());
                    y -= ICON_ROW_H + 14f;
                }
            } else if (block instanceof ParagraphBlock) {
                ParagraphBlock pb = (ParagraphBlock) block;
                y = addRich(spans(pb.inlines, UiTheme.TEXT_SECONDARY, false), textX, y, textW, P_SCALE, LEADING, null, true);
                y -= pb.tight ? 2f : 14f;
            }
            first = false;
        }
        // Marge de fin : la dernière ligne ne doit pas coller au bas du panneau.
        content.add(new UiLabel(x0, y - 16f, "", UiTheme.TEXT_MUTED, P_SCALE));
    }

    // Tailles du texte de la description (remontées le 2026-10-04 : 0.42 se lisait mal).
    private static final float P_SCALE = 0.6f, H1_SCALE = 0.96f, H2_SCALE = 0.82f, H3_SCALE = 0.7f;
    /** Interligne des paragraphes, en multiple de la hauteur de ligne de la police. */
    private static final float LEADING = 1.4f;
    private static final float LIST_INDENT = 26f;
    private static final float TEXT_MAX_W = 1180f;
    /** Une image de description ne dépasse pas cette hauteur à l'écran. */
    private static final float IMAGE_MAX_H = 520f;

    /** Images de la description pas encore chargées : leur arrivée relance la mise en page (voir uiDraw). */
    private final List<String> pendingImages = new ArrayList<>();

    /** Traduit les fragments du parseur en spans du moteur de texte : gras, code et liens se distinguent par la police et la couleur. */
    private static List<UiTextSpan> spans(List<Inline> inlines, UiColor base, boolean allBold) {
        UiColor link = UiColor.lerp(UiTheme.ACCENT, UiTheme.TEXT_PRIMARY, 0.25f);
        UiColor code = UiColor.lerp(UiTheme.ACCENT, UiTheme.TEXT_PRIMARY, 0.6f);
        List<UiTextSpan> out = new ArrayList<>(inlines.size());
        for (Inline in : inlines) {
            UiColor color = in.link ? link : in.code ? code : in.bold ? UiTheme.TEXT_PRIMARY : base;
            out.add(new UiTextSpan(in.text, allBold || in.bold, color, null, 1f));
        }
        return out;
    }

    /**
     * Met en page un bloc de texte riche à partir de son bord haut {@code yTop}
     * et ajoute UN widget par LIGNE au contenu défilant. Rend le y du bas du bloc.
     *
     * <p>Par ligne et non par bloc : le conteneur estompe un widget entier dès
     * qu'il dépasse du cadre, donc un paragraphe de dix lignes disparaîtrait
     * d'un coup alors que neuf sont encore à l'écran. Et le retour à la ligne
     * est celui d'{@link UiRichText}, qui place chaque MOT à une position
     * mesurée — l'ancien découpage sommait des largeurs de mots puis dessinait
     * la ligne d'un trait, et la somme tombait ~3 % sous la largeur réelle :
     * les longues lignes sortaient du panneau.
     *
     * @param leading interligne, en multiple de la hauteur de ligne de la police
     * @param bullet  puce dessinée à gauche de la première ligne, ou {@code null}
     */
    private float addRich(List<UiTextSpan> spans, float x, float yTop, float maxW, float scale, float leading,
                          String bullet, boolean center) {
        UiRichText.Layout layout = UiRichText.layout(spans, maxW, scale);
        if (layout.runs.isEmpty()) return yTop;
        float lineH = UiFont.REGULAR.lineHeight(scale);
        float step = lineH * leading;
        // Le moteur empile ses lignes à lineH ; on les ré-espace à `step`.
        // run.y = -(k × lineH) - ascendante, donc k = partie entière de -run.y / lineH.
        List<MdLine> lines = new ArrayList<>();
        for (UiRichText.Run run : layout.runs) {
            int k = (int) Math.floor((-run.y - 0.001f) / lineH);
            while (lines.size() <= k) lines.add(null);
            float baseline = yTop - k * step + (run.y + k * lineH) - (step - lineH) / 2f;
            MdLine line = lines.get(k);
            if (line == null) {
                line = new MdLine(x, baseline);
                lines.set(k, line);
                if (k == 0 && bullet != null) line.add(-LIST_INDENT + 8f, 0f, bullet, false, UiTheme.TEXT_MUTED, scale);
            }
            line.add(run.x, baseline - line.y, run.text, run.span.bold, run.span.color, run.scale);
            line.right = Math.max(line.right, run.x + run.width);
        }
        for (MdLine line : lines) {
            if (line == null) continue;
            // Centrage ligne à ligne : chaque ligne est décalée de la moitié
            // de la place qu'elle laisse dans la colonne.
            if (center) line.x += Math.max(0f, (maxW - line.right) / 2f);
            content.add(line);
        }
        return yTop - lines.size() * step;
    }

    /** Une ligne de texte riche : ses mots, chacun à sa position mesurée. {@code y} = ligne de base (hauteur nulle, comme UiLabel). */
    private static final class MdLine extends UiWidget {
        private final List<String> texts = new ArrayList<>();
        private final List<UiFont> fonts = new ArrayList<>();
        private final List<UiColor> colors = new ArrayList<>();
        private float[] geom = new float[12]; // dx, dy, échelle par mot
        /** Bord droit du dernier mot, depuis le début de la ligne — sert au centrage. */
        float right;

        MdLine(float x, float baseline) { super(x, baseline, 0, 0); }

        void add(float dx, float dy, String text, boolean bold, UiColor color, float scale) {
            int i = texts.size() * 3;
            if (i + 3 > geom.length) geom = java.util.Arrays.copyOf(geom, geom.length * 2);
            geom[i] = dx;
            geom[i + 1] = dy;
            geom[i + 2] = scale;
            texts.add(text);
            fonts.add(bold ? UiFont.BOLD : UiFont.REGULAR);
            colors.add(color);
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            float fade = clipFade;
            if (fade <= 0.01f) return;
            for (int i = 0; i < texts.size(); i++) {
                UiColor c = fade >= 0.999f ? colors.get(i) : colors.get(i).multiplyAlpha(fade);
                renderer.drawText(fonts.get(i), texts.get(i), x + geom[i * 3], y + geom[i * 3 + 1], c, geom[i * 3 + 2],
                    vpWidth, vpHeight);
            }
        }
    }

    /**
     * Ajoute une image de la description à sa taille RÉELLE (ramenée à la
     * largeur du panneau et à {@link #IMAGE_MAX_H}), et rend le y du dessous.
     * La taille n'est connue qu'une fois l'image chargée : d'ici là elle n'a
     * qu'une petite place d'attente, et son arrivée relance la mise en page.
     * Une image qui ne viendra pas ne prend aucune place — avant, chaque image
     * réservait 240 px quoi qu'il arrive, d'où des trous dans la page.
     */
    private float addImage(String url, Integer declaredW, float x, float yTop, float maxW) {
        if (UiRemoteImage.failed(url)) return yTop;
        BufferedImage src = UiRemoteImage.get(url);
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) {
            pendingImages.add(url);
            float ph = 96f, pw = Math.min(maxW, 420f);
            content.add(new MdImage(x + (maxW - pw) / 2f, yTop - 6f - ph, pw, ph, url, 0, 0));
            return yTop - 6f - ph - 18f;
        }
        float limitW = declaredW != null ? Math.min(maxW, declaredW) : maxW;
        float ratio = Math.min(1f, Math.min(limitW / src.getWidth(), IMAGE_MAX_H / src.getHeight()));
        float dw = Math.round(src.getWidth() * ratio), dh = Math.round(src.getHeight() * ratio);
        // Centrée dans le panneau, sur un pixel entier (une image posée à
        // cheval sur deux pixels se floute).
        content.add(new MdImage(Math.round(x + (maxW - dw) / 2f), yTop - 6f - dh, dw, dh, url, (int) dw, (int) dh));
        return yTop - 6f - dh - 18f;
    }

    /**
     * Image de la description. Dessinée à pleine opacité, SANS le fondu de
     * bord du conteneur : une image haute s'effacerait en entier dès que son
     * bord dépasse du cadre. Ce qui dépasse du panneau est recouvert par le
     * cadre de verre de l'écran (voir drawFrame).
     */
    private static final class MdImage extends UiWidget {
        private final String url, cacheKey;
        private final int texW, texH; // 0 = place d'attente, taille pas encore connue
        private final UiAsyncFade fade = new UiAsyncFade();

        MdImage(float x, float y, float w, float h, String url, int texW, int texH) {
            super(x, y, w, h);
            this.url = url;
            this.texW = texW;
            this.texH = texH;
            this.cacheKey = "md:" + texW + "x" + texH + ":" + url;
        }

        @Override
        public boolean contains(double mx, double my) { return false; }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            BufferedImage img = texW > 0 ? UiRemoteImage.scaled(url, texW, texH) : null;
            if (img == null) {
                renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM,
                    UiTheme.PANEL_BG_ALT.multiplyAlpha(0.6f), vpWidth, vpHeight);
                return;
            }
            fade.markReady();
            renderer.drawIcon(cacheKey, img, x, y, w, h, fade.alpha(), vpWidth, vpHeight);
        }
    }

    // ── Onglet Versions ──────────────────────────────────────────────────────

    private void buildVersions(UiRenderer renderer) {
        if (!versionsReady) {
            addNotice("Chargement des versions...");
            return;
        }
        if (versionsError != null) {
            addNotice("Versions indisponibles : " + versionsError);
            return;
        }
        List<ModrinthJson.Version> shown = new ArrayList<>();
        for (ModrinthJson.Version v : versions) {
            if (!currentVersionOnly || mcVersion.isEmpty() || v.gameVersions.contains(mcVersion)) shown.add(v);
        }
        if (shown.isEmpty()) {
            addNotice(versions.isEmpty() ? "Aucune version publiée."
                : "Aucune version publiée pour " + mcVersion + " — retire le filtre pour tout voir.");
            return;
        }
        float rowX = panelX + 1f, rowW = panelW - 2f;
        float y = 0f;
        for (ModrinthJson.Version v : shown) {
            y -= VERSION_ROW_H;
            content.add(new VersionRow(rowX, y, rowW, VERSION_ROW_H, v, parent.isFileInstalled(v.filename)));
        }
    }

    /** « 26.3 » en tête : Modrinth liste les versions du jeu de la plus ancienne à la plus récente. */
    private static List<String> newestFirst(List<String> gameVersions) {
        List<String> out = new ArrayList<>(gameVersions);
        Collections.reverse(out);
        return out;
    }

    /** Âge d'une publication, en clair : « aujourd'hui », « il y a 2 sem. », « il y a 5 mois », « il y a 2 ans ». */
    static String relativeAge(String iso) {
        try {
            long days = Duration.between(Instant.parse(iso), Instant.now()).toDays();
            if (days <= 0) return "aujourd'hui";
            if (days == 1) return "hier";
            if (days < 14) return "il y a " + days + " j";
            if (days < 60) return "il y a " + (days / 7) + " sem.";
            if (days < 365) return "il y a " + (days / 30) + " mois";
            long years = days / 365;
            return "il y a " + years + (years > 1 ? " ans" : " an");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static final DateTimeFormatter DAY_FORMAT =
        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH).withZone(ZoneId.systemDefault());

    static String longDate(String iso) {
        try {
            return DAY_FORMAT.format(Instant.parse(iso));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Une ligne du tableau : pastille de canal (R/B/A), numéro, versions du
     * jeu, plateformes, âge, téléchargements, et un bouton qui installe CE
     * fichier. Le bouton est la seule zone cliquable ({@link #contains}).
     */
    private final class VersionRow extends UiWidget {
        private final ModrinthJson.Version version;
        private final boolean installed;
        private final List<String> gameVersions;
        private final String age, downloads;
        private final UiAnimatedFloat rowHover = new UiAnimatedFloat(0f, 16f);
        private final UiAnimatedFloat btnHover = new UiAnimatedFloat(0f, 16f);

        VersionRow(float x, float y, float w, float h, ModrinthJson.Version version, boolean installed) {
            super(x, y, w, h);
            this.version = version;
            this.installed = installed;
            this.gameVersions = newestFirst(version.gameVersions);
            this.age = relativeAge(version.published);
            this.downloads = ModrinthContentScreen.formatDownloads(version.downloads);
        }

        private float btnX() { return contentX() + innerW() - ROW_BTN_W; }
        private float btnY() { return y + (h - ROW_BTN_H) / 2f; }

        private boolean overButton(double mx, double my) {
            float bx = btnX(), by = btnY();
            return mx >= bx && mx <= bx + ROW_BTN_W && my >= by && my <= by + ROW_BTN_H;
        }

        @Override
        public boolean contains(double mx, double my) {
            // clipFade : une ligne à moitié estompée au bord du cadre n'est pas cliquable.
            if (installed || parent.isBusy() || clipFade < 0.5f) return false;
            return overButton(mx, my);
        }

        @Override
        public void onClick() {
            parent.installFileFromDetail(hit, version.url, version.filename);
        }

        /** Dessine des pastilles de {@code from} jusqu'à {@code maxX} ; ce qui ne tient pas devient « +N ». */
        private void drawChips(UiRenderer renderer, List<String> labels, float from, float maxX, float fade,
                               int vpWidth, int vpHeight) {
            float chipH = 22f, scale = 0.34f, gap = 5f;
            float cy = y + (h - chipH) / 2f;
            float cx = from;
            for (int i = 0; i < labels.size(); i++) {
                String label = labels.get(i);
                float cw = renderer.textWidth(label, scale) + 16f;
                int left = labels.size() - i;
                // Garde la place d'un « +N » tant qu'il reste d'autres pastilles derrière.
                float reserve = left > 1 ? renderer.textWidth("+" + left, scale) + 16f + gap : 0f;
                if (cx + cw + reserve > maxX) {
                    label = "+" + left;
                    cw = renderer.textWidth(label, scale) + 16f;
                    i = labels.size(); // dernière pastille
                }
                renderer.drawRoundedRect(cx, cy, cx + cw, cy + chipH, chipH / 2f,
                    UiTheme.TRACK_OFF.multiplyAlpha(0.8f * fade), vpWidth, vpHeight);
                renderer.drawText(label, cx + 8f, cy + chipH / 2f - 4.2f,
                    UiTheme.TEXT_SECONDARY.multiplyAlpha(fade), scale, vpWidth, vpHeight);
                cx += cw + gap;
            }
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            float fade = clipFade;
            if (fade <= 0.01f) return;
            boolean over = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
            rowHover.setTarget(over ? 1f : 0f);
            float rh = rowHover.get();
            if (rh > 0.01f) {
                renderer.drawRoundedRect(x, y, x + w, y + h, 0f,
                    UiTheme.CARD_HOVER.multiplyAlpha(0.35f * rh * fade), vpWidth, vpHeight);
            }
            renderer.drawRoundedRect(x, y, x + w, y + 1f, 0f, UiTheme.TRACK_OFF.multiplyAlpha(0.7f * fade), vpWidth, vpHeight);

            float x0 = contentX(), iw = innerW();
            float mid = y + h / 2f;

            // Canal de publication : R (stable), B (bêta), A (alpha).
            boolean beta = "beta".equals(version.type), alpha = "alpha".equals(version.type);
            UiColor channel = alpha ? UiTheme.DANGER : beta ? new UiColor(240, 170, 60, 255) : UiTheme.ACCENT;
            String letter = alpha ? "A" : beta ? "B" : "R";
            float d = 28f;
            renderer.drawRoundedRect(x0, mid - d / 2f, x0 + d, mid + d / 2f, d / 2f,
                channel.multiplyAlpha(0.22f * fade), vpWidth, vpHeight);
            float lw = renderer.textWidth(UiFont.BOLD, letter, 0.38f);
            renderer.drawText(UiFont.BOLD, letter, x0 + (d - lw) / 2f, mid - 4.8f,
                channel.multiplyAlpha(fade), 0.38f, vpWidth, vpHeight);

            float numMaxW = colGame(x0, iw) - colVersion(x0, iw) - 12f;
            renderer.drawText(UiFont.BOLD, renderer.truncate(version.number, 0.44f, numMaxW * 0.94f),
                colVersion(x0, iw), mid - 5.5f, UiTheme.TEXT_PRIMARY.multiplyAlpha(fade), 0.44f, vpWidth, vpHeight);

            drawChips(renderer, gameVersions, colGame(x0, iw), colLoaders(x0, iw) - 14f, fade, vpWidth, vpHeight);
            drawChips(renderer, version.loaders, colLoaders(x0, iw), colPublished(x0, iw) - 14f, fade, vpWidth, vpHeight);

            UiColor soft = UiTheme.TEXT_SECONDARY.multiplyAlpha(fade);
            renderer.drawText(age, colPublished(x0, iw), mid - 5f, soft, 0.4f, vpWidth, vpHeight);
            renderer.drawText(downloads, colDownloads(x0, iw), mid - 5f, soft, 0.4f, vpWidth, vpHeight);

            boolean installingThis = parent.isInstallingFile(version.filename);
            btnHover.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            String label = installingThis ? ModrinthContentScreen.spinnerFrame() + " Installation"
                : installed ? "Installé" : "Installer";
            ModrinthContentScreen.drawStateButton(renderer, btnX(), btnY(), ROW_BTN_W, ROW_BTN_H, label,
                installed, installingThis, btnHover.get(), fade, 0.36f, vpWidth, vpHeight);
        }
    }

    /** Filtre « version du jeu en cours » de l'onglet Versions — même allure que les pastilles de la liste. */
    private final class VersionFilterChip extends UiWidget {
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        VersionFilterChip(float x, float y, float w, float h) { super(x, y, w, h); }

        @Override
        public void onClick() {
            currentVersionOnly = !currentVersionOnly;
            buildContent();
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            hoverAnim.setTarget(!currentVersionOnly && contains(mouseX, mouseY) ? 1f : 0f);
            float hover = hoverAnim.get();
            float radius = h / 2f;
            if (currentVersionOnly) {
                renderer.drawRoundedRect(x, y, x + w, y + h, radius, UiTheme.ACCENT, vpWidth, vpHeight);
            } else {
                UiColor border = renderer.isGlassAvailable()
                    ? UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.GLASS_BORDER_HOVER, hover) : null;
                renderer.drawGlassPanel(x, y, x + w, y + h, radius,
                    UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD - hover * 0.1f,
                    UiColor.lerp(UiTheme.PANEL_BG_ALT, UiTheme.CARD_HOVER, hover),
                    border, Math.max(1f, UiTheme.scaled(1f)), vpWidth, vpHeight);
            }
            String label = versionChipLabel();
            float tw = renderer.textWidth(label, 0.4f);
            renderer.drawText(label, x + (w - tw) / 2f, y + h / 2f - 5f,
                currentVersionOnly ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_SECONDARY, 0.4f, vpWidth, vpHeight);
        }
    }

    // ── Onglet Galerie ───────────────────────────────────────────────────────

    private void buildGallery() {
        if (!detailReady) {
            addNotice("Chargement de la galerie...");
            return;
        }
        List<ModrinthJson.GalleryItem> items = gallery;
        if (items.isEmpty()) {
            addNotice("Ce projet n'a pas d'images.");
            return;
        }
        float x0 = contentX(), w = innerW();
        int cols = Math.max(1, Math.min(THUMB_MAX_COLS, (int) ((w + THUMB_GAP) / (THUMB_MIN_W + THUMB_GAP))));
        float cardW = (w - (cols - 1) * THUMB_GAP) / cols;
        float cardH = cardW * 9f / 16f + THUMB_FOOT_H;
        float top = 0f;
        for (int i = 0; i < items.size(); i++) {
            int col = i % cols, row = i / cols;
            float cx = x0 + col * (cardW + THUMB_GAP);
            float cy = top - row * (cardH + THUMB_GAP) - cardH;
            content.add(new GalleryThumb(cx, cy, cardW, cardH, items.get(i), i));
        }

        List<String> urls = new ArrayList<>(items.size());
        for (ModrinthJson.GalleryItem item : items) urls.add(item.url);
        float lw = screenWidth * 0.86f, lh = screenHeight * 0.82f;
        lightbox = new GalleryCarousel((screenWidth - lw) / 2f, (screenHeight - lh) / 2f, lw, lh, urls);
        lightboxWidgets.clear();
        lightboxWidgets.add(new LightboxBackdrop());
        lightboxWidgets.add(lightbox);
    }

    private void openLightbox(int index) {
        if (lightbox == null) return;
        lightbox.setIndex(index);
        lightboxOpen = true;
    }

    private void drawLightbox(UiRenderer renderer, double mouseX, double mouseY) {
        lightboxAnim.setTarget(lightboxOpen ? 1f : 0f);
        float t = lightboxAnim.get();
        if (t <= 0.001f || lightbox == null) return;
        renderer.drawRoundedRect(0, 0, screenWidth, screenHeight, 0f,
            new UiColor(6, 6, 10, 255).multiplyAlpha(0.82f * t), screenWidth, screenHeight);
        lightbox.clipFade = t;
        lightbox.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
        String hint = "Échap ou clic en dehors pour fermer";
        float hw = renderer.textWidth(hint, 0.36f);
        renderer.drawText(hint, (screenWidth - hw) / 2f, lightbox.y - 26f,
            UiTheme.TEXT_MUTED.multiplyAlpha(t), 0.36f, screenWidth, screenHeight);
    }

    /** Fond de la visionneuse : plein écran, un clic dessus la ferme. Le carrousel, plus petit, passe devant au clic. */
    private final class LightboxBackdrop extends UiWidget {
        // Taille réelle de l'écran, pas 0 × 0 : au clic c'est la plus petite
        // aire qui gagne (UiHitTest), un fond d'aire nulle passerait DEVANT
        // le carrousel.
        LightboxBackdrop() { super(0, 0, screenWidth, screenHeight); }

        @Override
        public void onClick() { lightboxOpen = false; }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {}
    }

    /** Vignette de la grille : l'image entière (sans recadrage) sur fond sombre, puis son titre et sa date. */
    private final class GalleryThumb extends UiWidget {
        private final ModrinthJson.GalleryItem item;
        private final int index;
        private final String cacheKey, title, date;
        private final int texW, texH;
        private final UiAsyncFade imageFade = new UiAsyncFade();
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        GalleryThumb(float x, float y, float w, float h, ModrinthJson.GalleryItem item, int index) {
            super(x, y, w, h);
            this.item = item;
            this.index = index;
            this.texW = Math.max(1, (int) w);
            this.texH = Math.max(1, (int) (h - THUMB_FOOT_H));
            this.cacheKey = "gallery-thumb:" + texW + "x" + texH + ":" + item.url;
            this.title = item.title.isEmpty() ? "Image " + (index + 1) : item.title;
            this.date = longDate(item.created);
        }

        @Override
        public void onClick() { openLightbox(index); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            // Pleine opacité, sans le fondu de bord du conteneur : une carte
            // haute s'effacerait en entier dès que son bord dépasse du cadre,
            // tout en restant cliquable. Ce qui dépasse du panneau est
            // recouvert par le cadre de l'écran (drawFrame), et le conteneur
            // n'envoie de clic qu'à l'intérieur de son cadre.
            float fade = 1f;
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float hover = hoverAnim.get();
            float line = Math.max(1f, UiTheme.scaled(1f));
            // Carte opaque (pas du verre) : une image se lit mieux sur un fond
            // stable que sur le décor flouté. Contour qui s'allume au survol.
            renderer.drawRoundedRect(x - line, y - line, x + w + line, y + h + line, UiTheme.RADIUS_MD + line,
                UiColor.lerp(UiTheme.TRACK_OFF, UiTheme.ACCENT, hover).multiplyAlpha(fade), vpWidth, vpHeight);
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_MD,
                UiTheme.CARD_BG.multiplyAlpha(fade), vpWidth, vpHeight);

            float imgY = y + THUMB_FOOT_H, imgH = h - THUMB_FOOT_H;
            // Copie à la taille de la vignette, pas la capture d'origine : huit
            // captures pleine résolution saturaient l'atlas d'icônes, et plus
            // rien ne s'affichait (voir UiRemoteImage#scaled).
            BufferedImage img = UiRemoteImage.scaled(item.url, texW, texH);
            if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                imageFade.markReady();
                float pad = 6f;
                float scale = Math.min((w - pad * 2) / img.getWidth(), (imgH - pad) / img.getHeight());
                float dw = img.getWidth() * scale, dh = img.getHeight() * scale;
                renderer.drawIcon(cacheKey, img, x + (w - dw) / 2f, imgY + (imgH - pad - dh) / 2f, dw, dh,
                    imageFade.alpha() * fade, vpWidth, vpHeight);
            } else {
                String loading = UiRemoteImage.failed(item.url) ? "Image indisponible" : "Chargement...";
                float lw = renderer.textWidth(loading, 0.36f);
                renderer.drawText(loading, x + (w - lw) / 2f, imgY + imgH / 2f - 5f,
                    UiTheme.TEXT_MUTED.multiplyAlpha(fade), 0.36f, vpWidth, vpHeight);
            }

            renderer.drawText(UiFont.BOLD, renderer.truncate(title, 0.42f, (w - 28f) * 0.94f), x + 14f, y + 34f,
                UiTheme.TEXT_PRIMARY.multiplyAlpha(fade), 0.42f, vpWidth, vpHeight);
            if (!date.isEmpty()) {
                renderer.drawText(date, x + 14f, y + 14f, UiTheme.TEXT_MUTED.multiplyAlpha(fade), 0.36f, vpWidth, vpHeight);
            }
        }
    }

    // ── En-tête : onglets et boutons ─────────────────────────────────────────

    /** Gélule de verre derrière les onglets — inerte. */
    private final class TabBar extends UiWidget {
        TabBar(float x, float y, float w, float h) { super(x, y, w, h); }

        @Override
        public boolean contains(double mx, double my) { return false; }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            UiColor border = renderer.isGlassAvailable() ? UiTheme.GLASS_BORDER : null;
            renderer.drawGlassPanel(x, y, x + w, y + h, h / 2f,
                UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_PANEL, UiTheme.PANEL_BG,
                border, Math.max(1f, UiTheme.scaled(1f)), vpWidth, vpHeight);
        }
    }

    private final class TabPill extends UiWidget {
        private final Tab target;
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        TabPill(float x, float y, float w, float h, Tab target) {
            super(x, y, w, h);
            this.target = target;
        }

        @Override
        public void onClick() { switchTab(target); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean active = tab == target;
            hoverAnim.setTarget(!active && contains(mouseX, mouseY) ? 1f : 0f);
            float hover = hoverAnim.get();
            if (active) {
                // Onglet actif : ACCENT atténué et texte ACCENT, comme la
                // pastille verte de Modrinth dans la couleur du client.
                renderer.drawRoundedRect(x, y, x + w, y + h, h / 2f,
                    UiTheme.ACCENT.multiplyAlpha(0.28f), vpWidth, vpHeight);
            } else if (hover > 0.01f) {
                renderer.drawRoundedRect(x, y, x + w, y + h, h / 2f,
                    UiTheme.CARD_HOVER.multiplyAlpha(0.6f * hover), vpWidth, vpHeight);
            }
            float tw = renderer.textWidth(UiFont.BOLD, target.label, 0.42f);
            renderer.drawText(UiFont.BOLD, target.label, x + (w - tw) / 2f, y + h / 2f - 5.2f,
                active ? UiTheme.ACCENT : UiTheme.TEXT_PRIMARY, 0.42f, vpWidth, vpHeight);
        }
    }

    /** Bouton d'état de l'en-tête : installe la DERNIÈRE version ; l'onglet Versions sert à en choisir une autre. */
    private final class StateButton extends UiWidget {
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

        StateButton(float x, float y, float w, float h) { super(x, y, w, h); }

        @Override
        public boolean contains(double mx, double my) {
            if (alreadyInstalled || parent.isBusy()) return false;
            return super.contains(mx, my);
        }

        @Override
        public void onClick() { parent.installFromDetail(hit); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean inst = parent.isInstalling(hit.projectId);
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            String label = inst ? ModrinthContentScreen.spinnerFrame() + " Installation"
                : alreadyInstalled ? "Installé" : "Installer";
            ModrinthContentScreen.drawStateButton(renderer, x, y, w, h, label, alreadyInstalled, inst,
                hoverAnim.get(), 1f, 0.44f, vpWidth, vpHeight);
        }
    }

    /** « Supprimer » sous le bouton d'état, visible une fois installé. Deux clics : le premier arme, le second supprime. */
    private final class DeleteButton extends UiWidget {
        private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);
        private long armedAtMs;

        DeleteButton(float x, float y, float w, float h) { super(x, y, w, h); }

        private boolean shown() { return alreadyInstalled && !parent.isInstalling(hit.projectId); }

        private boolean armed() {
            return armedAtMs != 0L && System.currentTimeMillis() - armedAtMs < DELETE_CONFIRM_MS;
        }

        @Override
        public boolean contains(double mx, double my) {
            return shown() && !parent.isBusy() && super.contains(mx, my);
        }

        @Override
        public void onClick() {
            if (armed()) {
                armedAtMs = 0L;
                parent.removeFromDetail(hit);
            } else {
                armedAtMs = System.currentTimeMillis();
            }
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            if (!shown()) return;
            hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            boolean armed = armed();
            String label = parent.isRemoving(hit.projectId) ? ModrinthContentScreen.spinnerFrame() + " Suppression"
                : armed ? "Confirmer ?" : "Supprimer";
            ModrinthContentScreen.drawDeleteButton(renderer, x, y, w, h, label, armed, hoverAnim.get(), 1f, 0.36f,
                vpWidth, vpHeight);
        }
    }
}
