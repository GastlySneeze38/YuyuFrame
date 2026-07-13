package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.content.ContentBridge;
import com.yuyuframe.launcheragent.runtime.content.ModrinthJson;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRemoteImage;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiLabel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private static final float MARGIN = 28f;
    private static final float BACK_W = 110f, BACK_H = 34f;
    private static final float INSTALL_BTN_W = 170f, INSTALL_BTN_H = 40f;
    // Titre/auteur/téléchargements/bouton Installer/statut, réservé au-dessus
    // du scroll — même esprit que ModrinthContentScreen.HEADER_H.
    private static final float HEADER_H = 168f;
    private static final float SCROLLBAR_CLEARANCE = 24f;
    private static final float LINE_H = 26f;

    // Galerie : un visualiseur "carousel" (une image à la fois, navigation
    // précédent/suivant) plutôt qu'une grille de miniatures affichées d'un
    // coup — demande explicite de l'utilisateur ("un truc ou on peut naviguer
    // entre les images facilement"), voir GalleryCarousel.
    private static final float CAROUSEL_H = 300f;
    private static final float CAROUSEL_ARROW_W = 44f;

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

        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            float titleMaxW = screenWidth - MARGIN * 2 - BACK_W - 16f;
            renderer.drawText(UiFont.BOLD, ModrinthContentScreen.truncate(renderer, hit.title, 0.72f, titleMaxW),
                MARGIN, screenHeight - 52f, UiTheme.TEXT_PRIMARY, 0.72f, screenWidth, screenHeight);

            String meta = (hit.author != null && !hit.author.isEmpty() ? hit.author + "  ·  " : "")
                + ModrinthContentScreen.formatDownloads(hit.downloads) + " téléchargements";
            renderer.drawText(meta, MARGIN, screenHeight - 86f, UiTheme.TEXT_SECONDARY, 0.42f, screenWidth, screenHeight);

            String status = detailReady ? parent.statusTextSnapshot() : "Chargement...";
            if (status != null && !status.isEmpty()) {
                renderer.drawText(status, MARGIN + INSTALL_BTN_W + 16f, screenHeight - MARGIN - INSTALL_BTN_H / 2f - 5f,
                    UiTheme.TEXT_MUTED, 0.4f, screenWidth, screenHeight);
            }

            if (content != null) content.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            drawRevealVeil(renderer);
        } catch (Throwable t) {
            LauncherLog.err("[ModrinthProjectDetailScreen] uiDraw: " + t);
        }
    }

    private boolean installing() {
        return parent.isInstalling(hit.projectId);
    }

    private void buildLayout() {
        widgets.clear();
        widgets.add(new UiButton(screenWidth - MARGIN - BACK_W, screenHeight - MARGIN - BACK_H, BACK_W, BACK_H,
            "Retour", () -> closeTo(parent)));
        widgets.add(new InstallButton(MARGIN, screenHeight - MARGIN - INSTALL_BTN_H, INSTALL_BTN_W, INSTALL_BTN_H));

        content = new UiScrollContainer(MARGIN, MARGIN, screenWidth - MARGIN * 2, screenHeight - MARGIN - HEADER_H);
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
                    y -= scale >= 0.54f ? 36f : 32f;
                }
                y -= 6f;
            } else if (block instanceof DividerBlock) {
                y -= 8f;
                content.add(new DividerWidget(MARGIN, y, innerW));
                y -= 20f;
            } else if (block instanceof ListItemBlock) {
                ListItemBlock lb = (ListItemBlock) block;
                float indent = 20f;
                boolean first = true;
                for (String line : wrapText(renderer, lb.text, 0.42f, innerW - indent)) {
                    content.add(new UiLabel(MARGIN + indent, y, (first ? lb.bullet + " " : "  ") + line, UiTheme.TEXT_SECONDARY, 0.42f));
                    y -= LINE_H;
                    first = false;
                }
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
                    y -= LINE_H;
                }
                y -= 8f;
            }
        }
    }

    // ── Mini-moteur markdown + HTML ──────────────────────────────────────────
    // "body" Modrinth mélange markdown standard ET balises HTML brutes — un
    // vrai rendu demanderait un moteur de mise en page complet (police/couleur
    // par RUN de texte, pas juste par ligne). Ce pipeline ne fait QUE des
    // appels drawText par ligne entière (voir UiLabel) : le compromis retenu
    // ici est un découpage en BLOCS (titre/paragraphe/liste/séparateur/image),
    // chaque bloc ayant un style uniforme — suffisant pour éliminer le bug
    // rapporté (balises visibles telles quelles) et retrouver une mise en
    // page proche de l'original, sans reconstruire un moteur de texte riche.

    private static final Pattern HEADER = Pattern.compile("(#{1,6})\\s+(.*)");
    private static final Pattern HR = Pattern.compile("[-*_]{3,}");
    private static final Pattern BULLET_LIST = Pattern.compile("[-*+]\\s+(.*)");
    private static final Pattern ORDERED_LIST = Pattern.compile("(\\d+)\\.\\s+(.*)");
    private static final Pattern CODE_FENCE = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern MD_IMG = Pattern.compile("!\\[[^]]*]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern HTML_IMG = Pattern.compile("<img\\s+[^>]*?src=[\"']([^\"']+)[\"'][^>]*?/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern WIDTH_ATTR = Pattern.compile("width=[\"']?(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("\\[([^]]*)]\\([^)]*\\)");
    private static final Pattern BOLD_MD = Pattern.compile("\\*\\*([^*]*)\\*\\*|__([^_]*)__");
    private static final Pattern CODE_INLINE = Pattern.compile("`([^`]*)`");
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");

    private static class Block {}

    private static final class HeadingBlock extends Block {
        final int level;
        final String text;
        HeadingBlock(int level, String text) { this.level = level; this.text = text; }
    }

    private static final class ParagraphBlock extends Block {
        final String text;
        ParagraphBlock(String text) { this.text = text; }
    }

    private static final class ListItemBlock extends Block {
        final String bullet;
        final String text;
        ListItemBlock(String bullet, String text) { this.bullet = bullet; this.text = text; }
    }

    private static final class DividerBlock extends Block {}

    private static final class ImageRowBlock extends Block {
        final List<ImgRef> images;
        ImageRowBlock(List<ImgRef> images) { this.images = images; }
    }

    /** Une image référencée en ligne — {@code declaredW} = attribut {@code width="..."} HTML si présent (indice de mise en page avant même que l'image soit chargée), {@code null} pour une image markdown {@code ![]()} (jamais d'attribut de taille). */
    private static final class ImgRef {
        final String url;
        final Integer declaredW;
        ImgRef(String url, Integer declaredW) { this.url = url; this.declaredW = declaredW; }
    }

    private static final class LineImages {
        final List<ImgRef> images = new ArrayList<>();
        String remaining = "";
    }

    /**
     * Découpe {@code rawBody} en blocs ordonnés. Ligne par ligne : titres/
     * séparateurs/listes markdown reconnus explicitement, images (markdown
     * `![]()` ET HTML `<img>`) extraites et regroupées en {@link ImageRowBlock}
     * (des images consécutives, même séparées par des lignes vides, forment
     * UNE rangée — typique des badges shields.io/logos, un par ligne dans le
     * markdown source), tout le reste accumulé en paragraphe jusqu'à une
     * ligne vide ou une frontière de bloc.
     */
    private static List<Block> parseBlocks(String rawBody) {
        List<Block> blocks = new ArrayList<>();
        if (rawBody == null || rawBody.isEmpty()) return blocks;

        String body = rawBody.replace("\r\n", "\n");
        body = CODE_FENCE.matcher(body).replaceAll("");

        StringBuilder para = new StringBuilder();
        List<ImgRef> pendingRow = new ArrayList<>();

        for (String rawLine : body.split("\n", -1)) {
            String trimmed = rawLine.trim();

            if (trimmed.isEmpty()) {
                if (para.length() > 0) { blocks.add(new ParagraphBlock(para.toString())); para.setLength(0); }
                continue; // ligne vide : NE flush PAS pendingRow — laisse des badges séparés par des blancs se regrouper
            }

            Matcher headerM = HEADER.matcher(trimmed);
            if (headerM.matches()) {
                if (para.length() > 0) { blocks.add(new ParagraphBlock(para.toString())); para.setLength(0); }
                if (!pendingRow.isEmpty()) { blocks.add(new ImageRowBlock(new ArrayList<>(pendingRow))); pendingRow.clear(); }
                blocks.add(new HeadingBlock(headerM.group(1).length(), cleanInline(headerM.group(2))));
                continue;
            }

            if (HR.matcher(trimmed).matches()) {
                if (para.length() > 0) { blocks.add(new ParagraphBlock(para.toString())); para.setLength(0); }
                if (!pendingRow.isEmpty()) { blocks.add(new ImageRowBlock(new ArrayList<>(pendingRow))); pendingRow.clear(); }
                blocks.add(new DividerBlock());
                continue;
            }

            Matcher bulletM = BULLET_LIST.matcher(trimmed);
            Matcher orderedM = ORDERED_LIST.matcher(trimmed);
            if (bulletM.matches() || orderedM.matches()) {
                if (para.length() > 0) { blocks.add(new ParagraphBlock(para.toString())); para.setLength(0); }
                if (!pendingRow.isEmpty()) { blocks.add(new ImageRowBlock(new ArrayList<>(pendingRow))); pendingRow.clear(); }
                String bullet = bulletM.matches() ? "•" : orderedM.group(1) + ".";
                String rest = bulletM.matches() ? bulletM.group(1) : orderedM.group(2);
                LineImages li = extractImages(rest);
                String cleaned = cleanInline(li.remaining);
                if (!cleaned.isEmpty()) blocks.add(new ListItemBlock(bullet, cleaned));
                if (!li.images.isEmpty()) blocks.add(new ImageRowBlock(li.images));
                continue;
            }

            LineImages li = extractImages(trimmed);
            String cleaned = cleanInline(li.remaining);
            if (!li.images.isEmpty()) {
                if (para.length() > 0) { blocks.add(new ParagraphBlock(para.toString())); para.setLength(0); }
                pendingRow.addAll(li.images);
                if (!cleaned.isEmpty()) {
                    blocks.add(new ImageRowBlock(new ArrayList<>(pendingRow)));
                    pendingRow.clear();
                    para.append(cleaned);
                }
                continue;
            }

            if (!pendingRow.isEmpty()) { blocks.add(new ImageRowBlock(new ArrayList<>(pendingRow))); pendingRow.clear(); }
            if (!cleaned.isEmpty()) {
                if (para.length() > 0) para.append(' ');
                para.append(cleaned);
            }
        }

        if (para.length() > 0) blocks.add(new ParagraphBlock(para.toString()));
        if (!pendingRow.isEmpty()) blocks.add(new ImageRowBlock(pendingRow));
        return blocks;
    }

    /** Extrait toutes les images (markdown puis HTML) d'une ligne, HTML d'abord (peut porter un attribut {@code width}) — retourne aussi le texte restant, PAS ENCORE nettoyé (voir cleanInline, appelé séparément par l'appelant). */
    private static LineImages extractImages(String line) {
        LineImages result = new LineImages();

        Matcher hm = HTML_IMG.matcher(line);
        StringBuffer afterHtml = new StringBuffer();
        while (hm.find()) {
            String tag = hm.group();
            Integer w = null;
            Matcher wm = WIDTH_ATTR.matcher(tag);
            if (wm.find()) {
                try { w = Integer.parseInt(wm.group(1)); } catch (NumberFormatException ignored) {}
            }
            result.images.add(new ImgRef(hm.group(1), w));
            hm.appendReplacement(afterHtml, "");
        }
        hm.appendTail(afterHtml);

        Matcher mm = MD_IMG.matcher(afterHtml.toString());
        StringBuffer afterMd = new StringBuffer();
        while (mm.find()) {
            result.images.add(new ImgRef(mm.group(1), null));
            mm.appendReplacement(afterMd, "");
        }
        mm.appendTail(afterMd);
        result.remaining = afterMd.toString();
        return result;
    }

    /** Nettoyage inline best-effort : liens -> texte, code -> texte, gras -> texte (sans mise en forme, un seul style par bloc ici), balises HTML restantes retirées, entités décodées. */
    private static String cleanInline(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = text;
        s = LINK.matcher(s).replaceAll("$1");
        s = CODE_INLINE.matcher(s).replaceAll("$1");
        s = BOLD_MD.matcher(s).replaceAll("$1$2");
        s = ANY_TAG.matcher(s).replaceAll("");
        s = decodeEntities(s);
        s = s.replaceAll("[ \\t]+", " ").trim();
        return s;
    }

    private static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ");
    }

    /** Découpe {@code text} en lignes tenant dans {@code maxWidth} — retours à la ligne explicites du texte source respectés (paragraphes), remplissage glouton mot par mot à l'intérieur de chacun. */
    private static List<String> wrapText(UiRenderer renderer, String text, float scale, float maxWidth) {
        return wrapText(renderer, text, scale, maxWidth, UiFont.REGULAR);
    }

    private static List<String> wrapText(UiRenderer renderer, String text, float scale, float maxWidth, UiFont font) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (word.isEmpty()) continue;
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (current.length() > 0 && renderer.textWidth(font, candidate, scale) > maxWidth) {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(candidate);
                }
            }
            if (current.length() > 0) lines.add(current.toString());
        }
        return lines;
    }

    // ── Widgets de mise en page ──────────────────────────────────────────────

    private static final class DividerWidget extends UiWidget {
        DividerWidget(float x, float y, float w) { super(x, y, w, 2f); }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            renderer.drawRoundedRect(x, y, x + w, y + 2f, 0f, UiTheme.TRACK_OFF.multiplyAlpha(clipFade), vpWidth, vpHeight);
        }
    }

    /** Bannière pleine largeur (capture d'écran...) — ratio d'aspect TOUJOURS préservé (jamais de crop/étirement), jamais agrandie au-delà de sa taille source (évite le flou d'une petite image forcée en grand). */
    private static final class InlineBannerImage extends UiWidget {
        private final String url;

        InlineBannerImage(float x, float y, float w, float h, String url) {
            super(x, y, w, h);
            this.url = url;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            BufferedImage img = UiRemoteImage.get(url);
            if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
                renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, UiTheme.PANEL_BG_ALT.multiplyAlpha(clipFade), vpWidth, vpHeight);
                return;
            }
            float scale = Math.min(Math.min(w / img.getWidth(), h / img.getHeight()), 1f);
            float dw = img.getWidth() * scale, dh = img.getHeight() * scale;
            renderer.drawIcon("banner:" + url, img, x + (w - dw) / 2f, y + (h - dh) / 2f, dw, dh, vpWidth, vpHeight);
        }
    }

    /** Rangée de petites images (badges/logos) côte à côte, hauteur fixe, ratio d'aspect préservé par image — voir {@link #parseBlocks} pour le regroupement. */
    private static final class InlineIconRow extends UiWidget {
        private final List<ImgRef> imgs;

        InlineIconRow(float x, float y, float w, float h, List<ImgRef> imgs) {
            super(x, y, w, h);
            this.imgs = imgs;
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            float targetH = h - 8f;
            float cx = x;
            for (ImgRef ref : imgs) {
                BufferedImage img = UiRemoteImage.get(ref.url);
                float iw;
                if (img != null && img.getHeight() > 0) {
                    iw = targetH * ((float) img.getWidth() / img.getHeight());
                } else {
                    iw = ref.declaredW != null ? Math.min(ref.declaredW, 120) : targetH;
                }
                if (cx + iw > x + w) break; // pas de retour à la ligne — troncature défensive, rare en pratique
                if (img != null) {
                    renderer.drawIcon("badge:" + ref.url, img, cx, y + 4f, iw, targetH, vpWidth, vpHeight);
                } else {
                    renderer.drawRoundedRect(cx, y + 4f, cx + iw, y + 4f + targetH, UiTheme.RADIUS_SM, UiTheme.PANEL_BG_ALT.multiplyAlpha(clipFade), vpWidth, vpHeight);
                }
                cx += iw + 10f;
            }
        }
    }

    /**
     * Galerie navigable — une image affichée à la fois (ratio préservé, sans
     * crop, contrairement à l'ancienne grille de miniatures carrées), flèches
     * précédent/suivant + compteur "n / total". Préchargement des images
     * voisines (index ± 1) pour une navigation fluide.
     *
     * Clic sur les flèches géré via {@code pollContinuous} (comme
     * {@code ResultCard.pollContinuous} dans ModrinthContentScreen) — pas via
     * onClick()/contains(), qui ne reçoivent aucune coordonnée et ne
     * pourraient donc pas distinguer flèche gauche/droite dans un seul widget.
     */
    private static final class GalleryCarousel extends UiWidget {
        private final List<String> urls;
        private int index;
        private boolean prevLeftDown;

        GalleryCarousel(float x, float y, float w, float h, List<String> urls) {
            super(x, y, w, h);
            this.urls = urls;
        }

        private boolean overLeftArrow(double mx, double my) {
            return mx >= x && mx <= x + CAROUSEL_ARROW_W && my >= y && my <= y + h;
        }

        private boolean overRightArrow(double mx, double my) {
            return mx >= x + w - CAROUSEL_ARROW_W && mx <= x + w && my >= y && my <= y + h;
        }

        @Override
        public void pollContinuous(UiInputPoller input) {
            boolean justPressed = input.leftDown && !prevLeftDown;
            prevLeftDown = input.leftDown;
            if (!justPressed || urls.size() <= 1) return;
            if (overLeftArrow(input.mouseX, input.mouseY)) index = (index - 1 + urls.size()) % urls.size();
            else if (overRightArrow(input.mouseX, input.mouseY)) index = (index + 1) % urls.size();
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            float fade = clipFade;
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_MD, UiTheme.PANEL_BG_ALT.multiplyAlpha(fade), vpWidth, vpHeight);

            String url = urls.get(index);
            BufferedImage img = UiRemoteImage.get(url);
            if (urls.size() > 1) {
                UiRemoteImage.get(urls.get((index + 1) % urls.size()));
                UiRemoteImage.get(urls.get((index - 1 + urls.size()) % urls.size()));
            }

            if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                float boxW = w - CAROUSEL_ARROW_W * 2 - 16f, boxH = h - 16f;
                float scale = Math.min(Math.min(boxW / img.getWidth(), boxH / img.getHeight()), 4f);
                float dw = img.getWidth() * scale, dh = img.getHeight() * scale;
                renderer.drawIcon("gallery-full:" + url, img, x + (w - dw) / 2f, y + (h - dh) / 2f, dw, dh, vpWidth, vpHeight);
            } else {
                String label = "Chargement...";
                float lw = renderer.textWidth(label, 0.42f);
                renderer.drawText(label, x + (w - lw) / 2f, y + h / 2f - 6f, UiTheme.TEXT_MUTED, 0.42f, vpWidth, vpHeight);
            }

            if (urls.size() > 1) {
                boolean hoverLeft = overLeftArrow(mouseX, mouseY);
                boolean hoverRight = overRightArrow(mouseX, mouseY);
                renderer.drawRoundedRect(x, y, x + CAROUSEL_ARROW_W, y + h, 0f, (hoverLeft ? UiTheme.CARD_HOVER : UiTheme.OVERLAY_BG).multiplyAlpha(fade * 0.9f), vpWidth, vpHeight);
                renderer.drawRoundedRect(x + w - CAROUSEL_ARROW_W, y, x + w, y + h, 0f, (hoverRight ? UiTheme.CARD_HOVER : UiTheme.OVERLAY_BG).multiplyAlpha(fade * 0.9f), vpWidth, vpHeight);
                renderer.drawText("<", x + CAROUSEL_ARROW_W / 2f - 5f, y + h / 2f - 8f, UiTheme.TEXT_PRIMARY, 0.55f, vpWidth, vpHeight);
                renderer.drawText(">", x + w - CAROUSEL_ARROW_W / 2f - 5f, y + h / 2f - 8f, UiTheme.TEXT_PRIMARY, 0.55f, vpWidth, vpHeight);

                String counter = (index + 1) + " / " + urls.size();
                float cw = renderer.textWidth(counter, 0.38f);
                renderer.drawText(counter, x + w - cw - 14f, y + 12f, UiTheme.TEXT_SECONDARY, 0.38f, vpWidth, vpHeight);
            }
        }
    }

    /** Bouton Installer/Installé/Installation... — état dynamique relu à CHAQUE frame (contrairement à UiButton, libellé figé à la construction) — même esprit que le bouton de ResultCard. */
    private final class InstallButton extends UiWidget {
        InstallButton(float x, float y, float w, float h) { super(x, y, w, h); }

        @Override
        public boolean contains(double mx, double my) {
            if (alreadyInstalled || installing() || parent.isBusy()) return false;
            return super.contains(mx, my);
        }

        @Override
        public void onClick() {
            if (!alreadyInstalled && !installing() && !parent.isBusy()) parent.installFromDetail(hit);
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
