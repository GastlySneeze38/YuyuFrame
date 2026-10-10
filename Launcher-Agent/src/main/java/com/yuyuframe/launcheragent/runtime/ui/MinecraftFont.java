package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.game.ClientData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * La police de Minecraft pour l'interface du client (réglage « Police »).
 *
 * <p>Elle n'est pas embarquée : elle est lue dans les ressources du jeu, par
 * le gestionnaire de ressources — c'est donc celle du pack actif, et elle ne
 * coûte rien au poids de l'agent. Les glyphes sont agrandis sans lissage et
 * rangés dans un atlas de la même forme que celui de la police moderne
 * ({@link UiFont#pixel}), si bien que le moteur de texte ne fait aucune
 * différence entre les deux.
 *
 * <h2>Deux formats, selon la version du jeu</h2>
 *
 * <ul>
 *   <li><b>Versions récentes</b> : {@code font/include/default.json} décrit
 *       des fournisseurs « bitmap » — une image, une grille de caractères, une
 *       hauteur et une ligne de base. On les lit dans l'ordre, le premier qui
 *       définit un caractère l'emporte, comme dans le jeu.</li>
 *   <li><b>1.8.9</b> : pas de description. {@code ascii.png} suit la table
 *       figée du jeu (256 cases), et les lettres accentuées qu'elle n'a pas
 *       viennent de {@code unicode_page_00.png}, comme le fait le jeu.</li>
 * </ul>
 *
 * <p>Les ressources ne sont pas lisibles tout de suite au démarrage : la
 * police est donc appliquée par {@link #tick}, qui réessaie tant qu'elles ne
 * sont pas là. En attendant, et en cas d'échec, l'interface reste sur la
 * police moderne.
 */
public final class MinecraftFont {
    private MinecraftFont() {}

    /** Pixels d'atlas pour un pixel de la police du jeu. Pair : la page Unicode de la 1.8.9 est au demi-pixel. */
    private static final int UNIT = 6;
    /** Les lettres accentuées montent jusqu'à 10 pixels au-dessus de la ligne de base, et descendent de 2. */
    private static final int ASCENT = 10 * UNIT, DESCENT = 2 * UNIT;
    /** L'espace du jeu : 4 pixels. */
    private static final int SPACE = 4 * UNIT;

    private static volatile boolean wanted;
    private static boolean applied;
    private static UiFont regular, bold;
    private static long nextTry;
    private static int attempts;

    /** Le réglage : vrai = police de Minecraft. Appliqué au prochain {@link #tick}. */
    public static void want(boolean minecraft) {
        wanted = minecraft;
    }

    /** À chaque tick du jeu. Ne coûte rien quand il n'y a rien à changer. */
    public static void tick() {
        if (wanted == applied) return;
        if (!wanted) {
            UiFont.use(null, null);
            applied = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextTry || attempts >= 30) return;
        nextTry = now + 1000L;
        try {
            if (regular == null && !build()) {
                if (++attempts == 30) {
                    LauncherLog.err("[MinecraftFont] police du jeu illisible après 30 essais — l'interface garde la police moderne");
                }
                return;
            }
            UiFont.use(regular, bold);
            applied = true;
        } catch (Throwable t) {
            attempts = 30;
            LauncherLog.err("[MinecraftFont] construction de la police du jeu : " + t);
        }
    }

    /** @return faux si les ressources ne sont pas encore lisibles (on réessaiera). */
    private static boolean build() throws Exception {
        Set<Character> needed = new HashSet<>();
        for (int cp = 33; cp <= 126; cp++) needed.add((char) cp);
        for (int cp = 161; cp <= 255; cp++) needed.add((char) cp);
        needed.add((char) 338); // Œ
        needed.add((char) 339); // œ
        needed.add((char) 8734); // ∞
        needed.add((char) 8226); // •

        Map<Character, UiFont.PixelGlyph> glyphs = new HashMap<>();
        byte[] description = ClientData.resourceBytes("minecraft", "font/include/default.json");
        if (description != null) {
            if (!loadDescribed(new String(description, StandardCharsets.UTF_8), needed, glyphs)) return false;
        } else if (!loadLegacy(needed, glyphs)) {
            return false;
        }
        // La puce n'existe pas dans toutes les polices : le point médian en tient lieu.
        if (!glyphs.containsKey((char) 8226) && glyphs.containsKey((char) 183)) {
            glyphs.put((char) 8226, glyphs.get((char) 183));
        }
        UiFont r = UiFont.pixel(glyphs, UNIT, ASCENT, DESCENT, SPACE, false);
        UiFont b = UiFont.pixel(glyphs, UNIT, ASCENT, DESCENT, SPACE, true);
        if (r == null || b == null) {
            throw new IllegalStateException("la police du jeu n'a pas de « ? »");
        }
        regular = r;
        bold = b;
        LauncherLog.info("[MinecraftFont] police du jeu prête : " + glyphs.size() + " caractères sur " + needed.size());
        return true;
    }

    // ── Versions récentes : les fournisseurs « bitmap » de default.json ─────

    @SuppressWarnings("unchecked")
    private static boolean loadDescribed(String json, Set<Character> needed,
                                         Map<Character, UiFont.PixelGlyph> out) throws Exception {
        Object root = new Json(json).value();
        if (!(root instanceof Map)) throw new IllegalStateException("default.json : objet attendu");
        Object providers = ((Map<String, Object>) root).get("providers");
        if (!(providers instanceof List)) throw new IllegalStateException("default.json : pas de fournisseurs");
        boolean any = false;
        for (Object entry : (List<Object>) providers) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> provider = (Map<String, Object>) entry;
            if (!"bitmap".equals(provider.get("type"))) continue;
            Object file = provider.get("file"), chars = provider.get("chars");
            if (!(file instanceof String) || !(chars instanceof List)) continue;
            String id = (String) file;
            int sep = id.indexOf(':');
            String namespace = sep < 0 ? "minecraft" : id.substring(0, sep);
            String path = "textures/" + (sep < 0 ? id : id.substring(sep + 1));
            BufferedImage image = image(namespace, path);
            if (image == null) continue; // une image absente n'empêche pas les autres
            any = true;

            int height = provider.get("height") instanceof Number ? ((Number) provider.get("height")).intValue() : 8;
            int ascent = provider.get("ascent") instanceof Number ? ((Number) provider.get("ascent")).intValue() : 7;
            List<Object> rows = (List<Object>) chars;
            if (rows.isEmpty() || !(rows.get(0) instanceof String)) continue;
            int columns = ((String) rows.get(0)).codePointCount(0, ((String) rows.get(0)).length());
            if (columns == 0) continue;
            int cellW = image.getWidth() / columns, cellH = image.getHeight() / rows.size();
            if (cellW == 0 || cellH == 0) continue;
            for (int row = 0; row < rows.size(); row++) {
                if (!(rows.get(row) instanceof String)) continue;
                String line = (String) rows.get(row);
                int column = 0;
                for (int i = 0; i < line.length(); column++) {
                    int cp = line.codePointAt(i);
                    i += Character.charCount(cp);
                    if (cp > 0xFFFF || !needed.contains((char) cp) || out.containsKey((char) cp)) continue;
                    UiFont.PixelGlyph glyph = glyph(image, column * cellW, row * cellH, cellW, cellH, height, ascent, false);
                    if (glyph != null) out.put((char) cp, glyph);
                }
            }
        }
        return any;
    }

    // ── 1.8.9 : la table figée d'ascii.png, puis la page Unicode 00 ─────────

    /** Les 32 premières cases d'{@code ascii.png}, telles que le jeu les connaît (les 7 dernières sont vides). */
    private static final String LEGACY_HEAD = "ÀÁÂÈÊËÍÓÔÕÚßãõğİıŒœŞşŴŵžȇ";
    /** Les cases 128 à 175 : la page de code 437, avec les quatre remplacements du jeu (ø, Ø, ×, ®). */
    private static final String LEGACY_HIGH = "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜø£Ø×ƒáíóúñÑªº¿®¬½¼¡«»";

    private static boolean loadLegacy(Set<Character> needed, Map<Character, UiFont.PixelGlyph> out) throws Exception {
        BufferedImage ascii = image("minecraft", "textures/font/ascii.png");
        if (ascii == null) return false;
        char[] table = new char[256];
        for (int i = 0; i < LEGACY_HEAD.length(); i++) table[i] = LEGACY_HEAD.charAt(i);
        for (int cp = 32; cp <= 126; cp++) table[cp] = (char) cp;
        for (int i = 0; i < LEGACY_HIGH.length(); i++) table[128 + i] = LEGACY_HIGH.charAt(i);
        table[236] = (char) 8734; // ∞
        table[241] = (char) 177;  // ±
        table[246] = (char) 247;  // ÷
        table[248] = (char) 176;  // °
        table[250] = (char) 183;  // ·
        table[253] = (char) 178;  // ²
        int cellW = ascii.getWidth() / 16, cellH = ascii.getHeight() / 16;
        for (int index = 0; index < 256; index++) {
            char c = table[index];
            if (c == 0 || !needed.contains(c) || out.containsKey(c)) continue;
            UiFont.PixelGlyph glyph = glyph(ascii, (index % 16) * cellW, (index / 16) * cellH, cellW, cellH, 8, 7, false);
            if (glyph != null) out.put(c, glyph);
        }
        // Ce qui manque (Î, Ï, Ù, Û…) : la page Unicode, 256 cases dans l'ordre des codes.
        BufferedImage page = image("minecraft", "textures/font/unicode_page_00.png");
        if (page != null) {
            int pageW = page.getWidth() / 16, pageH = page.getHeight() / 16;
            for (int cp = 33; cp < 256; cp++) {
                char c = (char) cp;
                if (!needed.contains(c) || out.containsKey(c)) continue;
                UiFont.PixelGlyph glyph = glyph(page, (cp % 16) * pageW, (cp / 16) * pageH, pageW, pageH, 8, 7, true);
                if (glyph != null) out.put(c, glyph);
            }
        }
        return true;
    }

    // ── Un glyphe : découpé dans sa case, agrandi sans lissage ─────────────

    /**
     * @param height   hauteur de la case en pixels de POLICE (8 pour la police de base, quelle que soit
     *                 la résolution de l'image : un pack en 32× a des cases de 32 pixels pour 8 de police)
     * @param ascent   pixels de police entre le haut de la case et la ligne de base
     * @param trimLeft les glyphes de la page Unicode ne sont pas calés à gauche de leur case
     * @return {@code null} si la case est vide
     */
    private static UiFont.PixelGlyph glyph(BufferedImage image, int x0, int y0, int cellW, int cellH,
                                           int height, int ascent, boolean trimLeft) {
        int first = -1, last = -1;
        for (int x = 0; x < cellW; x++) {
            for (int y = 0; y < cellH; y++) {
                if ((image.getRGB(x0 + x, y0 + y) >>> 24) != 0) {
                    if (first < 0) first = x;
                    last = x;
                    break;
                }
            }
        }
        if (last < 0) return null;
        int from = trimLeft ? first : 0;
        int sourceW = last - from + 1;
        // Pixels d'atlas par pixel de l'image.
        float k = UNIT * height / (float) cellH;
        int w = Math.max(1, Math.round(sourceW * k)), h = Math.max(1, Math.round(cellH * k));
        boolean[] mask = new boolean[w * h];
        for (int ty = 0; ty < h; ty++) {
            int sy = Math.min(cellH - 1, (int) (ty / k));
            for (int tx = 0; tx < w; tx++) {
                int sx = from + Math.min(sourceW - 1, (int) (tx / k));
                mask[ty * w + tx] = (image.getRGB(x0 + sx, y0 + sy) >>> 24) != 0;
            }
        }
        return new UiFont.PixelGlyph(mask, w, h, ascent * UNIT);
    }

    private static BufferedImage image(String namespace, String path) throws Exception {
        byte[] bytes = ClientData.resourceBytes(namespace, path);
        return bytes == null ? null : ImageIO.read(new ByteArrayInputStream(bytes));
    }

    // ── Lecteur JSON minimal : objets, tableaux, chaînes, nombres, littéraux ─

    private static final class Json {
        private final String s;
        private int i;

        Json(String s) { this.s = s; }

        Object value() {
            skip();
            if (i >= s.length()) throw new IllegalStateException("JSON tronqué");
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> map = new LinkedHashMap<>();
                i++;
                skip();
                if (peek() == '}') { i++; return map; }
                while (true) {
                    skip();
                    String key = string();
                    skip();
                    expect(':');
                    map.put(key, value());
                    skip();
                    if (peek() == ',') { i++; continue; }
                    expect('}');
                    return map;
                }
            }
            if (c == '[') {
                List<Object> list = new ArrayList<>();
                i++;
                skip();
                if (peek() == ']') { i++; return list; }
                while (true) {
                    list.add(value());
                    skip();
                    if (peek() == ',') { i++; continue; }
                    expect(']');
                    return list;
                }
            }
            if (c == '"') return string();
            int start = i;
            while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) i++;
            String token = s.substring(start, i);
            if (token.equals("true")) return Boolean.TRUE;
            if (token.equals("false")) return Boolean.FALSE;
            if (token.equals("null")) return null;
            return Double.valueOf(token);
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: sb.append(e);
                }
            }
        }

        private char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        private void expect(char c) {
            if (peek() != c) throw new IllegalStateException("JSON : « " + c + " » attendu en position " + i);
            i++;
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
