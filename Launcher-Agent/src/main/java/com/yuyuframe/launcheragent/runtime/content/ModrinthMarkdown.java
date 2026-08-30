package com.yuyuframe.launcheragent.runtime.content;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Découpage du champ {@code body} d'un projet Modrinth (markdown + HTML mêlés)
 * en BLOCS de style uniforme — titre, paragraphe, item de liste, séparateur,
 * rangée d'images.
 *
 * <p>Extrait de {@code ModrinthProjectDetailScreen} le 2026-08-27 : ce parseur
 * y occupait ~190 lignes et 8 classes internes, alors qu'il ne dessine RIEN et
 * ne dépend d'aucun rendu. Le sortir le rend lisible, testable seul, et
 * réutilisable — jusqu'ici il était enfermé dans un écran.
 *
 * <p>Sa place est {@code runtime/content/} et non {@code apigraphic} : il ne
 * connaît que le format de données de Modrinth (voir {@link ModrinthJson},
 * même paquet), jamais la façon dont on l'affiche.
 */
public final class ModrinthMarkdown {
    private ModrinthMarkdown() {}

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

    public static class Block {}

    public static final class HeadingBlock extends Block {
        public final int level;
        public final String text;
        HeadingBlock(int level, String text) { this.level = level; this.text = text; }
    }

    public static final class ParagraphBlock extends Block {
        public final String text;
        ParagraphBlock(String text) { this.text = text; }
    }

    public static final class ListItemBlock extends Block {
        public final String bullet;
        public final String text;
        ListItemBlock(String bullet, String text) { this.bullet = bullet; this.text = text; }
    }

    public static final class DividerBlock extends Block {}

    public static final class ImageRowBlock extends Block {
        public final List<ImgRef> images;
        ImageRowBlock(List<ImgRef> images) { this.images = images; }
    }

    /** Une image référencée en ligne — {@code declaredW} = attribut {@code width="..."} HTML si présent (indice de mise en page avant même que l'image soit chargée), {@code null} pour une image markdown {@code ![]()} (jamais d'attribut de taille). {@code cacheKey} précalculé UNE FOIS ici (pas reconcaténé à chaque frame dans {@code draw()}, voir InlineIconRow — évite une allocation String + un recalcul de hashCode inutiles 60×/s par badge). */
    public static final class ImgRef {
        public final String url;
        public final Integer declaredW;
        public final String cacheKey;
        ImgRef(String url, Integer declaredW) {
            this.url = url;
            this.declaredW = declaredW;
            this.cacheKey = "badge:" + url;
        }
    }

    public static final class LineImages {
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
    public static List<Block> parseBlocks(String rawBody) {
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
}
