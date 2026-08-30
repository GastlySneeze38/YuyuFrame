package com.yuyuframe.launcheragent.runtime.ui.ingameui.modrinth;

import com.yuyuframe.launcheragent.runtime.content.ContentBridge;
import com.yuyuframe.launcheragent.runtime.content.ModrinthJson;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAsyncFade;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.core.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.layout.LayoutSolver;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyNode;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle;
import com.yuyuframe.launcheragent.apigraphic.core.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiLabel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;

import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.Block;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.DividerBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.HeadingBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.ImageRowBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.ImgRef;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.ListItemBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.ParagraphBlock;
import static com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.parseBlocks;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.DividerWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.InlineBannerImage;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.InlineIconRow;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.GalleryCarousel;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Page de détail d'un projet Modrinth — description complète + galerie
 * d'images, "comme sur Modrinth" (demande explicite de l'utilisateur).
 * Ouverte en cliquant sur le CORPS d'une {@code ResultCard} (hors bouton
 * Installer, voir sa javadoc {@code pollContinuous}) depuis
 * {@link ModrinthContentScreen}, qui reste la SEULE source de vérité pour la
 * recherche/l'installation (cet écran délègue via
 * {@link ModrinthContentScreen#installFromDetail}/{@code isInstalling}/
 * {@code isBusy}, ne duplique jamais cette logique).
 *
 * L'API de recherche Modrinth (voir {@code ModrinthJson.Hit}) ne renvoie
 * QUE des champs courts (titre, description tronquée, icône) — la
 * description LONGUE ("body", markdown + HTML mélangés) et la galerie
 * n'existent que sur l'endpoint détail (voir {@code ContentBridge.getProject}/
 * {@code content-core::get_project}), chargé ici en arrière-plan à
 * l'ouverture.
 *
 * "body" mélange RÉELLEMENT markdown ET balises HTML brutes (`<center>`,
 * `<img width="...">`, `<div>`...) — un simple nettoyage regex "markdown
 * uniquement" laissait les balises HTML visibles telles quelles (BUG
 * RAPPORTÉ, captures d'écran utilisateur). {@link #parseBlocks} fait donc une
 * VRAIE mise en page par blocs (titres, paragraphes, listes, séparateurs,
 * images inline) au lieu d'un simple nettoyage texte — voir sa javadoc.
 */
public final class ModrinthProjectDetailScreen extends UiScreenBase {

    private static final int MAX_GALLERY = 12;

    /** Étages de flou — même valeur que les autres écrans (voir UiRenderer#beginGlassFrame). */
    private static final int GLASS_PASSES = 4;
    private static final float MARGIN = 28f;
    private static final float BACK_W = 110f, BACK_H = 34f;
    private static final float INSTALL_BTN_W = 170f, INSTALL_BTN_H = 40f;
    // Titre/auteur/téléchargements, réservé au-dessus du scroll — même esprit
    // que ModrinthContentScreen.HEADER_H. Chaque *_TOP_GAP est une distance
    // depuis le HAUT de l'écran (convention Y-UP : y = screenHeight - gap) —
    // BUG CORRIGÉ (utilisateur : capture d'écran, titre et texte de statut
    // visuellement superposés) : ces éléments étaient auparavant positionnés
    // par des formules indépendantes qui retombaient toutes dans la même
    // bande de ~20px au lieu d'être empilés du haut vers le bas. Le bouton
    // Installer n'est PLUS dans cette bande (voir InstallButton flottant
    // ci-dessous) — HEADER_H réduit d'autant, plus de hauteur rendue au
    // scroll de la description.
    private static final float TITLE_TOP_GAP = 42f;
    private static final float META_TOP_GAP = 76f;
    private static final float HEADER_H = 110f;
    private static final float SCROLLBAR_CLEARANCE = 24f;

    // Galerie : un visualiseur "carousel" (une image à la fois, navigation
    // précédent/suivant) plutôt qu'une grille de miniatures affichées d'un
    // coup — demande explicite de l'utilisateur ("un truc ou on peut naviguer
    // entre les images facilement"), voir GalleryCarousel.
    private static final float CAROUSEL_H = 300f;

    // Images inline de la description : une SEULE image (pas de voisine sur
    // la même "ligne") avec une largeur déclarée absente ou > 140px = bannière
    // pleine largeur (captures d'écran) ; plusieurs images regroupées ou une
    // largeur déclarée petite = rangée de badges/icônes (shields.io, logos de
    // plateformes...) — voir ImageRowBlock/parseBlocks.
    private static final float BANNER_H = 240f;
    private static final float ICON_ROW_H = 40f;

    private final ModrinthContentScreen parent;
    private final ModrinthJson.Hit hit;
    private boolean alreadyInstalled; // pas final — rafraîchi après une install déclenchée depuis CETTE page, voir uiDraw
    private boolean wasInstalling;

    private UiScrollContainer content;
    private InstallButton installButton;
    private boolean layoutBuilt;

    // Chargement du détail (body + galerie) — même motif "pendingXxx consommé
    // une seule fois par le thread de rendu" que ModrinthContentScreen.pendingResults.
    // Le parsing markdown+HTML (parseBlocks) tourne sur le thread de fetch
    // (pas le thread de rendu) — pas de dépendance à UiRenderer/GL là-dedans,
    // seulement du texte, donc sûr à faire en arrière-plan.
    private volatile boolean detailReady;
    private volatile boolean pendingRebuild;
    private volatile List<Block> parsedBlocks = Collections.emptyList();
    private volatile List<String> galleryUrls = Collections.emptyList();

    public ModrinthProjectDetailScreen(ModrinthContentScreen parent, ModrinthJson.Hit hit, boolean alreadyInstalled) {
        super("Modrinth · " + hit.title);
        this.parent = parent;
        this.hit = hit;
        this.alreadyInstalled = alreadyInstalled;
        escapeTarget = parent; // Échap = "Retour" direct vers la liste (pas de tiroir/modal sur cet écran)
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
                    galleryUrls = ModrinthJson.parseGalleryUrls(json, MAX_GALLERY);
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

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        if (content != null) content.pollInput(input);
        // installButton n'est PLUS dans `widgets` (voir buildLayout/uiDraw,
        // dessiné à la main APRÈS le scroll pour flotter par-dessus) — son
        // clic doit donc être détecté ici manuellement, même motif que
        // ResultCard.pollContinuous/GalleryCarousel.pollContinuous déjà
        // utilisé dans ce fichier pour des widgets à zone de clic autonome.
        if (installButton != null) installButton.pollContinuous(input);
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (!layoutBuilt && screenWidth > 0 && screenHeight > 0) {
            buildLayout();
            layoutBuilt = true;
        }
        if (pendingRebuild) {
            pendingRebuild = false;
            buildContent();
        }
        // Une install déclenchée DEPUIS cette page vient de se terminer —
        // rafraîchit le badge Installé/Installer (scanné une fois par
        // ModrinthContentScreen à l'ouverture de LA LISTE, jamais mis à jour
        // en direct autrement — voir isNowInstalled).
        boolean nowInstalling = installing();
        if (wasInstalling && !nowInstalling) alreadyInstalled = parent.isNowInstalled(hit);
        wasInstalling = nowInstalling;

        // Chaîne de flou partagée — voir UiRenderer#beginGlassFrame.
        UiRenderer.get(getClass().getClassLoader()).beginGlassFrame(GLASS_PASSES, screenWidth, screenHeight);
        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            float titleMaxW = screenWidth - MARGIN * 2 - BACK_W - 16f;
            renderer.drawText(UiFont.BOLD, renderer.truncate(hit.title, 0.72f, titleMaxW),
                MARGIN, screenHeight - TITLE_TOP_GAP, UiTheme.TEXT_PRIMARY, 0.72f, screenWidth, screenHeight);

            String meta = (hit.author != null && !hit.author.isEmpty() ? hit.author + "  ·  " : "")
                + ModrinthContentScreen.formatDownloads(hit.downloads) + " téléchargements";
            renderer.drawText(meta, MARGIN, screenHeight - META_TOP_GAP, UiTheme.TEXT_SECONDARY, 0.42f, screenWidth, screenHeight);

            // content D'ABORD, installButton APRÈS (voir plus bas) : l'ordre
            // de dessin fait l'ordre Z sur ce moteur (Blaze3D : dernier
            // empilé = dessiné par-dessus, voir tout l'historique de bugs de
            // z-order documenté dans ce projet) — le bouton doit rester
            // visible AU-DESSUS du texte de description qui défile en
            // dessous de lui (demande explicite de l'utilisateur : "fixed en
            // bas à droite pour que même avec le scroll on puisse
            // l'installer"), donc il doit être dessiné EN DERNIER.
            if (content != null) content.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);

            // Statut affiché UNIQUEMENT pendant une install déclenchée DEPUIS
            // cette page — parent.statusTextSnapshot() est un champ PARTAGÉ
            // avec les messages de comptage de résultats de la liste
            // (ModrinthContentScreen.statusText, ex. "34 résultat(s)") :
            // l'afficher sans condition ici faisait apparaître un vieux
            // message de recherche sans rapport, superposé au titre (BUG
            // RAPPORTÉ, capture d'écran utilisateur).
            if (nowInstalling) {
                String status = parent.statusTextSnapshot();
                if (status != null && !status.isEmpty()) {
                    float sw = renderer.textWidth(status, 0.4f);
                    renderer.drawText(status, installButtonX() - sw - 14f, installButtonY() + INSTALL_BTN_H / 2f - 5f,
                        UiTheme.TEXT_MUTED, 0.4f, screenWidth, screenHeight);
                }
            }

            if (installButton != null) installButton.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            drawRevealVeil(renderer);
        } catch (Throwable t) {
            LauncherLog.err("[ModrinthProjectDetailScreen] uiDraw: " + t);
        }
    }

    private boolean installing() {
        return parent.isInstalling(hit.projectId);
    }

    // Bouton Installer FLOTTANT, fixe en bas à droite de l'ÉCRAN (pas du
    // contenu défilant) — demande explicite de l'utilisateur : rester
    // accessible sans avoir à remonter tout en haut, quelle que soit la
    // position du scroll. Décalé de SCROLLBAR_CLEARANCE + une marge propre
    // pour ne jamais chevaucher la scrollbar de `content`, qui vit dans la
    // même bande verticale à l'extrême droite du conteneur.
    private float installButtonX() {
        return screenWidth - MARGIN - SCROLLBAR_CLEARANCE - INSTALL_BTN_W - 8f;
    }

    private float installButtonY() {
        return MARGIN;
    }

    private void buildLayout() {
        widgets.clear();

        // ── Arbre de layout du CHROME (rework 2026-08-27) ───────────────────
        //
        // Portée volontairement limitée au chrome (bouton retour, bouton
        // installer, zone défilante). Le CONTENU de la description, lui, reste
        // sur son curseur vertical (voir buildContent) — ce n'est pas un
        // oubli : les widgets qu'il empile sont des UiLabel, dont x/y désignent
        // une LIGNE DE BASE de texte et non une boîte (leur w/h valent 0, voir
        // sa javadoc). Les passer à Taffy demanderait d'inventer une boîte par
        // ligne puis de reconvertir boîte -> ligne de base via l'ascendante de
        // la police : ça déplacerait potentiellement CHAQUE ligne de la
        // description, et changerait aussi l'étendue de défilement (calculée
        // par UiScrollContainer depuis ces mêmes y/h). Un flux de texte
        // séquentiel est par ailleurs exactement ce pour quoi un curseur est
        // fait — Taffy n'y apporterait aucune décision de mise en page.
        TaffyNode screen = new TaffyNode("screen", new TaffyStyle()
            .size(TaffyStyle.px(screenWidth), TaffyStyle.px(screenHeight))
            .flexDirection("column"));
        screen.style.padding = new String[]{ TaffyStyle.px(HEADER_H), TaffyStyle.px(MARGIN),
            TaffyStyle.px(MARGIN), TaffyStyle.px(MARGIN) };
        TaffyStyle contentStyle = new TaffyStyle();
        contentStyle.flexGrow = 1f;
        screen.child(new TaffyNode("content", contentStyle));
        // Les deux boutons sont ANCRÉS (hors flux) : ils flottent par-dessus la
        // zone défilante, ils ne doivent pas la rétrécir.
        screen.child(LayoutSolver.anchored("back", BACK_W, BACK_H, MARGIN, MARGIN, null, null));
        // Décalé de SCROLLBAR_CLEARANCE + 8 pour ne jamais chevaucher la barre
        // de défilement, qui vit dans la même bande verticale à droite.
        screen.child(LayoutSolver.anchored("install", INSTALL_BTN_W, INSTALL_BTN_H,
            null, MARGIN + SCROLLBAR_CLEARANCE + 8f, MARGIN, null));

        LayoutSolver.Solved layout = LayoutSolver.solve(screen, screenWidth, screenHeight);

        UiButton backBtn = new UiButton(screenWidth - MARGIN - BACK_W, screenHeight - MARGIN - BACK_H, BACK_W, BACK_H,
            "Retour", () -> closeTo(parent));
        if (layout != null) layout.apply("back", backBtn);
        widgets.add(backBtn);

        // PAS ajouté à `widgets` (contrairement à avant) — voir sa javadoc et
        // uiDraw/uiPollInput : dessiné/cliqué à la main pour flotter au-dessus
        // du contenu défilant plutôt que suivre le dispatch générique.
        installButton = new InstallButton(installButtonX(), installButtonY(), INSTALL_BTN_W, INSTALL_BTN_H);
        if (layout != null) layout.apply("install", installButton);

        TaffyLayoutResult.Rect vp = layout == null ? null : layout.get("content");
        content = vp != null
            ? new UiScrollContainer(vp.x, vp.y, vp.w, Math.max(1f, vp.h))
            : new UiScrollContainer(MARGIN, MARGIN, screenWidth - MARGIN * 2, screenHeight - MARGIN - HEADER_H);
        if (detailReady) buildContent();
    }

    /** (Re)construit galerie + description — appelé dès que le détail arrive, et au premier buildLayout() si déjà arrivé entre-temps (rare, requête très rapide). */
    private void buildContent() {
        content.clear();
        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
        float innerW = screenWidth - MARGIN * 2 - SCROLLBAR_CLEARANCE;
        float y = -8f;

        if (!galleryUrls.isEmpty()) {
            content.add(new UiLabel(MARGIN, y, "Galerie", UiTheme.TEXT_MUTED, 0.46f));
            y -= 34f;
            content.add(new GalleryCarousel(MARGIN, y - CAROUSEL_H, innerW, CAROUSEL_H, galleryUrls));
            y -= CAROUSEL_H + 44f;
        }

        content.add(new UiLabel(MARGIN, y, "Description", UiTheme.TEXT_MUTED, 0.46f));
        y -= 34f;

        List<Block> blocks = parsedBlocks;
        if (blocks.isEmpty()) {
            content.add(new UiLabel(MARGIN, y, "Aucune description disponible.", UiTheme.TEXT_MUTED, 0.42f));
            return;
        }

        for (Block block : blocks) {
            if (block instanceof HeadingBlock) {
                HeadingBlock hb = (HeadingBlock) block;
                float scale = hb.level <= 1 ? 0.60f : (hb.level == 2 ? 0.54f : 0.48f);
                y -= 10f;
                for (String line : wrapText(renderer, hb.text, scale, innerW, UiFont.BOLD)) {
                    content.add(new UiLabel(MARGIN, y, line, UiTheme.TEXT_PRIMARY, scale, UiFont.BOLD));
                    y -= lineHeight(UiFont.BOLD, scale);
                }
                y -= 6f;
            } else if (block instanceof DividerBlock) {
                // BUG CORRIGÉ (utilisateur : capture d'écran, une liste
                // touchait le séparateur qui la suit) : marges avant/après
                // augmentées (8→16 / 20→26) ET ListItemBlock (juste
                // au-dessous) a maintenant sa propre marge de fin — avant, un
                // bloc liste suivi d'un séparateur n'avait QUE la marge du
                // séparateur pour les séparer, aucune marge de fin propre à
                // la liste (contrairement à ParagraphBlock, qui en avait déjà
                // une, voir plus bas).
                y -= 16f;
                content.add(new DividerWidget(MARGIN, y, innerW));
                y -= 26f;
            } else if (block instanceof ListItemBlock) {
                ListItemBlock lb = (ListItemBlock) block;
                float indent = 20f;
                boolean first = true;
                for (String line : wrapText(renderer, lb.text, 0.42f, innerW - indent)) {
                    content.add(new UiLabel(MARGIN + indent, y, (first ? lb.bullet + " " : "  ") + line, UiTheme.TEXT_SECONDARY, 0.42f));
                    y -= lineHeight(UiFont.REGULAR, 0.42f);
                    first = false;
                }
                y -= 6f;
            } else if (block instanceof ImageRowBlock) {
                ImageRowBlock ib = (ImageRowBlock) block;
                boolean banner = ib.images.size() == 1 && (ib.images.get(0).declaredW == null || ib.images.get(0).declaredW > 140);
                if (banner) {
                    y -= 4f;
                    content.add(new InlineBannerImage(MARGIN, y - BANNER_H, innerW, BANNER_H, ib.images.get(0).url));
                    y -= BANNER_H + 18f;
                } else {
                    content.add(new InlineIconRow(MARGIN, y - ICON_ROW_H, innerW, ICON_ROW_H, ib.images));
                    y -= ICON_ROW_H + 14f;
                }
            } else if (block instanceof ParagraphBlock) {
                ParagraphBlock pb = (ParagraphBlock) block;
                for (String line : wrapText(renderer, pb.text, 0.42f, innerW)) {
                    content.add(new UiLabel(MARGIN, y, line, UiTheme.TEXT_SECONDARY, 0.42f));
                    y -= lineHeight(UiFont.REGULAR, 0.42f);
                }
                y -= 8f;
            }
        }
    }


    /**
     * BUG CORRIGÉ (retour utilisateur : capture d'écran, des lignes
     * consécutives d'un MÊME paragraphe/item de liste se chevauchaient) :
     * l'espacement entre lignes utilisait avant une constante à plat
     * (26px) inventée à la main, sans lien avec la taille RÉELLE des
     * glyphes rendus à cette échelle — {@code UiFont.lineHeight(scale)}
     * (ascendant+descendant RÉELS de la police, voir sa javadoc) est la
     * source de vérité déjà utilisée ailleurs dans ce moteur pour ce genre
     * de calcul ; un magic number à plat pouvait diverger de cette valeur
     * réelle selon la police système chargée par AWT. +25% de plomb
     * (interligne) ajouté par-dessus la valeur brute ascendant+descendant
     * pour un espacement confortablement lisible (pratique typographique
     * standard), pas juste la boîte de collision minimale du glyphe.
     */
    private static float lineHeight(UiFont font, float scale) {
        return font.lineHeight(scale) * 1.25f;
    }

    /** Découpe {@code text} en lignes tenant dans {@code maxWidth} — retours à la ligne explicites du texte source respectés (paragraphes), remplissage glouton mot par mot à l'intérieur de chacun. */
    private static List<String> wrapText(UiRenderer renderer, String text, float scale, float maxWidth) {
        return wrapText(renderer, text, scale, maxWidth, UiFont.REGULAR);
    }

    /**
     * BUG DE PERFORMANCE CORRIGÉ (retour utilisateur : "beaucoup de latence
     * dans l'interface") : la version précédente recalculait
     * {@code renderer.textWidth(candidate, scale)} sur la ligne ENTIÈRE en
     * cours de remplissage à CHAQUE mot ajouté (re-mesure tous les caractères
     * déjà comptés) — coût proche du carré du nombre de mots par ligne, tout
     * ça de façon SYNCHRONE sur le thread de rendu dès l'ouverture d'une page
     * (voir {@code buildContent}), perceptible comme un à-coup pour une
     * longue description. {@code UiFont.textWidth} n'a AUCUN kerning (simple
     * somme d'avances par caractère, voir sa javadoc/implémentation) : la
     * largeur d'un mot ajouté à une ligne peut donc être accumulée
     * INCRÉMENTALEMENT (largeur du mot mesurée UNE SEULE fois + largeur d'une
     * espace) sans perte de précision — chaque mot n'est plus mesuré qu'une
     * seule fois au total, au lieu d'une fois par candidat de ligne.
     */
    private static List<String> wrapText(UiRenderer renderer, String text, float scale, float maxWidth, UiFont font) {
        List<String> lines = new ArrayList<>();
        float spaceWidth = renderer.textWidth(font, " ", scale);
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder current = new StringBuilder();
            float currentWidth = 0f;
            for (String word : paragraph.split(" ")) {
                if (word.isEmpty()) continue;
                float wordWidth = renderer.textWidth(font, word, scale);
                float candidateWidth = current.length() == 0 ? wordWidth : currentWidth + spaceWidth + wordWidth;
                if (current.length() > 0 && candidateWidth > maxWidth) {
                    lines.add(current.toString());
                    current.setLength(0);
                    current.append(word);
                    currentWidth = wordWidth;
                } else {
                    if (current.length() > 0) {
                        current.append(' ');
                        currentWidth += spaceWidth;
                    }
                    current.append(word);
                    currentWidth += wordWidth;
                }
            }
            if (current.length() > 0) lines.add(current.toString());
        }
        return lines;
    }





    /** Bouton Installer/Installé/Installation... — état dynamique relu à CHAQUE frame (contrairement à UiButton, libellé figé à la construction) — même esprit que le bouton de ResultCard. */
    /**
     * Bouton Installer/Installé/Installation... — FLOTTANT, fixe en bas à
     * droite de l'écran (voir {@code installButtonX/Y}), pas dans
     * {@code widgets} (retiré exprès, voir {@code buildLayout}/{@code uiDraw})
     * pour pouvoir être dessiné APRÈS le scroll de description et rester
     * visible par-dessus. N'étant plus dans {@code widgets}, il n'est plus
     * atteint par {@code UiScreenBase.dispatchClick()} — le clic est détecté
     * ici en autonome via {@code pollContinuous} (appelé à la main depuis
     * {@code uiPollInput}), même motif déjà établi dans ce fichier pour
     * {@code ResultCard}/{@code GalleryCarousel}.
     */
    private final class InstallButton extends UiWidget {
        private boolean prevLeftDown;

        InstallButton(float x, float y, float w, float h) { super(x, y, w, h); }

        @Override
        public boolean contains(double mx, double my) {
            if (alreadyInstalled || installing() || parent.isBusy()) return false;
            return super.contains(mx, my);
        }

        @Override
        public void pollContinuous(UiInputPoller input) {
            boolean justPressed = input.leftDown && !prevLeftDown;
            prevLeftDown = input.leftDown;
            if (justPressed && contains(input.mouseX, input.mouseY)) parent.installFromDetail(hit);
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            boolean inst = installing();
            boolean hover = contains(mouseX, mouseY);
            UiColor bg = inst ? UiTheme.ACCENT_DIM
                : alreadyInstalled ? UiTheme.PANEL_BG_ALT
                : (hover ? UiTheme.ACCENT : UiTheme.CARD_HOVER);
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
            String label = inst ? ModrinthContentScreen.spinnerFrame() + " Installation" : (alreadyInstalled ? "Installé" : "Installer");
            float scale = 0.5f;
            float lw = renderer.textWidth(label, scale);
            renderer.drawText(label, x + (w - lw) / 2f, y + h / 2f - 6f,
                (alreadyInstalled && !inst) ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_PRIMARY, scale, vpWidth, vpHeight);
        }
    }
}
