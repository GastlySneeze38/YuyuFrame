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

    /**
     * Un fragment de texte de style uniforme À L'INTÉRIEUR d'un bloc — ajouté
     * le 2026-10-04 : jusque-là un bloc n'avait qu'un style, donc le gras, le
     * code et les liens du markdown étaient aplatis en texte nu (voir
     * {@link #parseInline}). Le rendu (police, couleur) reste l'affaire de
     * l'écran : ici on ne dit que ce que le fragment EST.
     */
    public static final class Inline {
        public final String text;
        public final boolean bold;
        /** {@code `code`} en ligne. */
        public final boolean code;
        /** Texte d'un lien (l'adresse n'est pas gardée : rien ne s'ouvre depuis le jeu). */
        public final boolean link;

        Inline(String text, boolean bold, boolean code, boolean link) {
            this.text = text;
            this.bold = bold;
            this.code = code;
            this.link = link;
        }
    }

    private static String plain(List<Inline> inlines) {
        StringBuilder sb = new StringBuilder();
        for (Inline in : inlines) sb.append(in.text);
        return sb.toString().trim();
    }

    public static final class HeadingBlock extends Block {
        public final int level;
        public final String text;
        public final List<Inline> inlines;
        HeadingBlock(int level, List<Inline> inlines) { this.level = level; this.inlines = inlines; this.text = plain(inlines); }
    }

    public static final class ParagraphBlock extends Block {
        public final String text;
        public final List<Inline> inlines;
        /** Terminé par un saut de ligne FORCÉ (deux espaces, {@code \} ou {@code <br>}) : le bloc suivant le suit de près, sans l'écart d'un vrai paragraphe. */
        public final boolean tight;
        ParagraphBlock(List<Inline> inlines, boolean tight) { this.inlines = inlines; this.tight = tight; this.text = plain(inlines); }
    }

    public static final class ListItemBlock extends Block {
        public final String bullet;
        public final String text;
        public final List<Inline> inlines;
        ListItemBlock(String bullet, List<Inline> inlines) { this.bullet = bullet; this.inlines = inlines; this.text = plain(inlines); }
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
                flushPara(blocks, para, false);
                continue; // ligne vide : NE flush PAS pendingRow — laisse des badges séparés par des blancs se regrouper
            }

            Matcher headerM = HEADER.matcher(trimmed);
            if (headerM.matches()) {
                flushPara(blocks, para, false);
                if (!pendingRow.isEmpty()) { blocks.add(new ImageRowBlock(new ArrayList<>(pendingRow))); pendingRow.clear(); }
                List<Inline> heading = parseInline(extractImages(headerM.group(2)).remaining);
                if (!heading.isEmpty()) blocks.add(new HeadingBlock(headerM.group(1).length(), heading));
                continue;
            }

            if (HR.matcher(trimmed).matches()) {
                flushPara(blocks, para, false);
                if (!pendingRow.isEmpty()) { blocks.add(new ImageRowBlock(new ArrayList<>(pendingRow))); pendingRow.clear(); }
                blocks.add(new DividerBlock());
                continue;
            }

            Matcher bulletM = BULLET_LIST.matcher(trimmed);
            Matcher orderedM = ORDERED_LIST.matcher(trimmed);
            if (bulletM.matches() || orderedM.matches()) {
                flushPara(blocks, para, false);
                if (!pendingRow.isEmpty()) { blocks.add(new ImageRowBlock(new ArrayList<>(pendingRow))); pendingRow.clear(); }
                String bullet = bulletM.matches() ? "•" : orderedM.group(1) + ".";
                String rest = bulletM.matches() ? bulletM.group(1) : orderedM.group(2);
                LineImages li = extractImages(rest);
                List<Inline> item = parseInline(li.remaining);
                if (!item.isEmpty()) blocks.add(new ListItemBlock(bullet, item));
                if (!li.images.isEmpty()) blocks.add(new ImageRowBlock(li.images));
                continue;
            }

            // Saut de ligne FORCÉ en fin de ligne (deux espaces, « \ » ou
            // <br>) : la ligne ferme son paragraphe au lieu de se fondre dans
            // la suivante. Relevé sur la ligne BRUTE — trim() a déjà mangé les
            // deux espaces. Une ligne réduite à ce marqueur n'affiche rien
            // (avant, un « \ » seul restait à l'écran).
            boolean hardBreak = HARD_BREAK.matcher(rawLine).find();
            String content = hardBreak ? HARD_BREAK.matcher(trimmed).replaceFirst("") : trimmed;
            // Citation « > texte » : rendue comme un paragraphe.
            if (content.startsWith(">")) content = content.substring(1).trim();

            LineImages li = extractImages(content);
            // Le paragraphe accumule le texte BRUT (mis en forme à sa
            // fermeture, voir flushPara) : un gras ouvert sur une ligne et
            // fermé sur la suivante reste un seul gras. cleaned ne sert qu'à
            // savoir s'il reste quelque chose à afficher.
            String cleaned = cleanInline(li.remaining);
            if (!li.images.isEmpty()) {
                flushPara(blocks, para, false);
                pendingRow.addAll(li.images);
                if (!cleaned.isEmpty()) {
                    blocks.add(new ImageRowBlock(new ArrayList<>(pendingRow)));
                    pendingRow.clear();
                    para.append(li.remaining.trim());
                    if (hardBreak) flushPara(blocks, para, true);
                }
                continue;
            }

            if (!pendingRow.isEmpty()) { blocks.add(new ImageRowBlock(new ArrayList<>(pendingRow))); pendingRow.clear(); }
            if (!cleaned.isEmpty()) {
                if (para.length() > 0) para.append(' ');
                para.append(li.remaining.trim());
            }
            if (hardBreak) flushPara(blocks, para, true);
        }

        flushPara(blocks, para, false);
        if (!pendingRow.isEmpty()) blocks.add(new ImageRowBlock(pendingRow));
        return blocks;
    }

    /** Ferme le paragraphe en cours (texte BRUT accumulé, mis en forme ici) ; rien si vide. */
    private static void flushPara(List<Block> blocks, StringBuilder para, boolean tight) {
        if (para.length() == 0) return;
        List<Inline> inlines = parseInline(para.toString());
        para.setLength(0);
        if (!inlines.isEmpty()) blocks.add(new ParagraphBlock(inlines, tight));
    }

    private static final Pattern HARD_BREAK = Pattern.compile("(?: {2,}|\\\\|<br\\s*/?>)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_BOLD = Pattern.compile("</?(?:b|strong)(?:\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_LINK = Pattern.compile("<a\\s[^>]*?href=[\"']([^\"']*)[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE);
    private static final Pattern MD_LINK_AT = Pattern.compile("\\[([^]]*)]\\(([^)]*)\\)");

    /**
     * Met en forme le texte d'un bloc : {@code **gras**} / {@code __gras__} /
     * {@code <b>} / {@code <strong>}, {@code `code`}, {@code [texte](adresse)}
     * et {@code <a href>}. L'italique ({@code *x*}, {@code _x_}) est reconnu
     * mais rendu en texte normal — la police du client n'a pas d'italique —
     * pour que ses marqueurs ne restent pas à l'écran. Les autres balises
     * HTML sont retirées, les entités décodées.
     *
     * <p>Un balayage caractère par caractère, pas une cascade de regex : le
     * gras peut contenir un lien, un lien du gras, et du code ne doit pas voir
     * ses astérisques interprétés — trois cas qu'une suite de remplacements
     * traite dans le mauvais ordre.
     */
    public static List<Inline> parseInline(String raw) {
        List<Inline> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        String s = HTML_BOLD.matcher(raw).replaceAll("**");
        s = HTML_LINK.matcher(s).replaceAll("[$2]($1)");
        s = HTML_BR.matcher(s).replaceAll(" ");
        s = ANY_TAG.matcher(s).replaceAll("");
        s = decodeEntities(s);
        s = s.replaceAll("[ \\t]+", " ").trim();

        StringBuilder buf = new StringBuilder();
        boolean bold = false;
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && "\\`*_{}[]()#+-.!|>~".indexOf(s.charAt(i + 1)) >= 0) {
                buf.append(s.charAt(i + 1)); // caractère échappé : littéral
                i += 2;
            } else if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end < 0) { buf.append(c); i++; continue; }
                emit(out, buf, bold);
                String code = s.substring(i + 1, end);
                if (!code.isEmpty()) out.add(new Inline(code, bold, true, false));
                i = end + 1;
            } else if ((c == '*' || c == '_') && i + 1 < n && s.charAt(i + 1) == c) {
                emit(out, buf, bold);
                bold = !bold;
                i += 2;
            } else if (c == '*') {
                // Italique : marqueur retiré s'il touche un mot ; un « * » isolé reste.
                boolean touches = (i + 1 < n && s.charAt(i + 1) != ' ') || (i > 0 && s.charAt(i - 1) != ' ');
                if (!touches) buf.append(c);
                i++;
            } else if (c == '_') {
                // Italique seulement en bord de mot : snake_case garde ses soulignés.
                boolean before = i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1));
                boolean after = i + 1 >= n || !Character.isLetterOrDigit(s.charAt(i + 1));
                if (!(before ^ after)) buf.append(c);
                i++;
            } else if (c == '[') {
                Matcher m = MD_LINK_AT.matcher(s);
                m.region(i, n);
                if (m.lookingAt()) {
                    emit(out, buf, bold);
                    String label = m.group(1).replace("**", "").replace("__", "").replace("`", "").replace("*", "").trim();
                    if (!label.isEmpty()) out.add(new Inline(label, bold, false, true));
                    i = m.end();
                } else {
                    buf.append(c);
                    i++;
                }
            } else {
                buf.append(c);
                i++;
            }
        }
        emit(out, buf, bold);
        return out;
    }

    private static void emit(List<Inline> out, StringBuilder buf, boolean bold) {
        if (buf.length() == 0) return;
        out.add(new Inline(buf.toString(), bold, false, false));
        buf.setLength(0);
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
