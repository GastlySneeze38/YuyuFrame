package com.yuyuframe.launcheragent.runtime.content;

import java.util.ArrayList;
import java.util.List;

/**
 * Extraction JSON minimaliste pour les réponses Modrinth — même style que
 * {@code LauncherAgent.jsonString()} déjà utilisé pour mixins.launcheragent.json.
 * Pas de dépendance à une lib JSON : les champs attendus sont des chaînes
 * simples (pas de tableaux/objets imbriqués à extraire côté Java, content-core
 * fait déjà le travail lourd côté Rust pour getLatestFile()).
 */
public final class ModrinthJson {

    private ModrinthJson() {}

    public static final class Hit {
        public final String title;
        public final String projectId;
        public final String author;
        public final long downloads;
        public final String description;
        public final String iconUrl;

        public Hit(String title, String projectId, String author, long downloads,
                    String description, String iconUrl) {
            this.title = title;
            this.projectId = projectId;
            this.author = author;
            this.downloads = downloads;
            this.description = description;
            this.iconUrl = iconUrl;
        }
    }

    /** Extrait jusqu'à {@code maxResults} entrées de {@code "hits": [...]}. */
    public static List<Hit> parseHits(String json, int maxResults) {
        List<Hit> hits = new ArrayList<>();
        if (json == null) return hits;

        int hitsIdx = json.indexOf("\"hits\"");
        if (hitsIdx < 0) return hits;
        int arrStart = json.indexOf('[', hitsIdx);
        if (arrStart < 0) return hits;
        int arrEnd = matchingBracket(json, arrStart, '[', ']');
        if (arrEnd < 0) return hits;

        int pos = arrStart + 1;
        while (pos < arrEnd && hits.size() < maxResults) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0 || objStart >= arrEnd) break;
            int objEnd = matchingBracket(json, objStart, '{', '}');
            if (objEnd < 0) break;

            String obj = json.substring(objStart, objEnd + 1);
            String title = jsonString(obj, "title");
            String projectId = jsonString(obj, "project_id");
            String author = jsonString(obj, "author");
            long downloads = jsonLong(obj, "downloads");
            String description = jsonString(obj, "description");
            String iconUrl = jsonString(obj, "icon_url");
            if (title != null && projectId != null) {
                hits.add(new Hit(title, projectId, author, downloads, description, iconUrl));
            }
            pos = objEnd + 1;
        }
        return hits;
    }

    /**
     * Extrait une valeur chaîne simple {@code "key":"value"}.
     *
     * BUG TROUVÉ (utilisateur : page de détail avec galerie/description —
     * "body", la description longue d'un projet, contient des retours à la
     * ligne) : l'échappement JSON n'était PAS vraiment décodé jusqu'ici,
     * juste "un backslash = ignorer le backslash, garder le caractère
     * suivant tel quel" — {@code \n} devenait le caractère LETTRE "n" (pas
     * un retour à la ligne), {@code \t} devenait "t", etc. Invisible tant
     * que seuls des champs COURTS et SANS retour à la ligne (titre, auteur,
     * description courte des résultats de recherche) étaient parsés — "body"
     * (multi-paragraphes) l'aurait rendu evident (toute la description sur
     * une seule ligne, avec des "n"/"t" parasites à la place des sauts de
     * ligne). Fix : vrai décodage des échappements JSON standards
     * (\n/\t/\r/\b/\f/\"/\\/\/) + \\uXXXX (unicode).
     */
    public static String jsonString(String json, String key) {
        if (json == null) return null;
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        i = json.indexOf('"', json.indexOf(':', i) + 1);
        if (i < 0) return null;
        int end = i + 1;
        StringBuilder sb = new StringBuilder();
        while (end < json.length() && json.charAt(end) != '"') {
            char c = json.charAt(end);
            if (c == '\\' && end + 1 < json.length()) {
                char esc = json.charAt(end + 1);
                switch (esc) {
                    case 'n': sb.append('\n'); end += 2; break;
                    case 't': sb.append('\t'); end += 2; break;
                    case 'r': sb.append('\r'); end += 2; break;
                    case 'b': sb.append('\b'); end += 2; break;
                    case 'f': sb.append('\f'); end += 2; break;
                    case '"': sb.append('"'); end += 2; break;
                    case '\\': sb.append('\\'); end += 2; break;
                    case '/': sb.append('/'); end += 2; break;
                    case 'u':
                        if (end + 6 <= json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(end + 2, end + 6), 16));
                                end += 6;
                            } catch (NumberFormatException nfe) {
                                sb.append(esc);
                                end += 2;
                            }
                        } else {
                            sb.append(esc);
                            end += 2;
                        }
                        break;
                    default:
                        sb.append(esc);
                        end += 2;
                }
            } else {
                sb.append(c);
                end++;
            }
        }
        return sb.toString();
    }

    /**
     * Extrait jusqu'à {@code max} URLs d'images depuis {@code "gallery":
     * [...]} (détail complet d'un projet — voir
     * ContentBridge.getProject/ModrinthProjectDetailScreen ; absent des
     * résultats de recherche). Ordre du tableau Modrinth conservé tel quel
     * (l'image "featured" est déjà généralement en premier côté serveur).
     */
    public static List<String> parseGalleryUrls(String json, int max) {
        List<String> urls = new ArrayList<>();
        if (json == null) return urls;

        int galleryIdx = json.indexOf("\"gallery\"");
        if (galleryIdx < 0) return urls;
        int arrStart = json.indexOf('[', galleryIdx);
        if (arrStart < 0) return urls;
        int arrEnd = matchingBracket(json, arrStart, '[', ']');
        if (arrEnd < 0) return urls;

        int pos = arrStart + 1;
        while (pos < arrEnd && urls.size() < max) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0 || objStart >= arrEnd) break;
            int objEnd = matchingBracket(json, objStart, '{', '}');
            if (objEnd < 0) break;

            String obj = json.substring(objStart, objEnd + 1);
            String url = jsonString(obj, "url");
            if (url != null && !url.isEmpty()) urls.add(url);
            pos = objEnd + 1;
        }
        return urls;
    }

    /** Une image de la galerie d'un projet — voir {@link #parseGallery}. */
    public static final class GalleryItem {
        public final String url;
        /** Titre donné par l'auteur, ou chaîne vide. */
        public final String title;
        /** Date ISO-8601 de mise en ligne, ou chaîne vide. */
        public final String created;

        public GalleryItem(String url, String title, String created) {
            this.url = url;
            this.title = title;
            this.created = created;
        }
    }

    /** Comme {@link #parseGalleryUrls}, avec le titre et la date de chaque image. */
    public static List<GalleryItem> parseGallery(String json, int max) {
        List<GalleryItem> items = new ArrayList<>();
        for (String obj : parseObjects(json, "gallery", max)) {
            String url = jsonString(obj, "url");
            if (url == null || url.isEmpty()) continue;
            // Titre souvent à null dans la réponse : jsonString irait alors
            // chercher la chaîne SUIVANTE (le nom de la clé d'après).
            String title = isStringValue(obj, "title") ? jsonString(obj, "title") : null;
            String created = isStringValue(obj, "created") ? jsonString(obj, "created") : null;
            items.add(new GalleryItem(url, title == null ? "" : title, created == null ? "" : created));
        }
        return items;
    }

    /** Une version publiée d'un projet — voir {@code ContentBridge.getVersions}. */
    public static final class Version {
        public final String number;
        /** {@code release}, {@code beta} ou {@code alpha}. */
        public final String type;
        public final List<String> gameVersions;
        public final List<String> loaders;
        /** Date ISO-8601 de publication. */
        public final String published;
        public final long downloads;
        public final String url;
        public final String filename;

        public Version(String number, String type, List<String> gameVersions, List<String> loaders,
                       String published, long downloads, String url, String filename) {
            this.number = number;
            this.type = type;
            this.gameVersions = gameVersions;
            this.loaders = loaders;
            this.published = published;
            this.downloads = downloads;
            this.url = url;
            this.filename = filename;
        }
    }

    /** Lit {@code {"versions":[…]}} rendu par {@code ContentBridge.getVersions} ; une entrée sans fichier est écartée. */
    public static List<Version> parseVersions(String json) {
        List<Version> versions = new ArrayList<>();
        for (String obj : parseObjects(json, "versions", 100)) {
            String url = jsonString(obj, "url");
            String filename = jsonString(obj, "filename");
            if (url == null || url.isEmpty() || filename == null || filename.isEmpty()) continue;
            versions.add(new Version(
                orEmpty(jsonString(obj, "version_number")),
                orEmpty(jsonString(obj, "version_type")),
                splitCsv(jsonString(obj, "game_versions")),
                splitCsv(jsonString(obj, "loaders")),
                orEmpty(jsonString(obj, "date_published")),
                jsonLong(obj, "downloads"),
                url, filename));
        }
        return versions;
    }

    private static String orEmpty(String s) { return s == null ? "" : s; }

    /** Vrai si la valeur de {@code key} est bien une chaîne (et pas {@code null}, un nombre, un objet…). */
    private static boolean isStringValue(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return false;
        i = json.indexOf(':', i) + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        return i < json.length() && json.charAt(i) == '"';
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String part : csv.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /** Les objets {@code {…}} du tableau {@code "arrayKey":[…]}, en texte brut, {@code max} au plus. */
    public static List<String> parseObjects(String json, String arrayKey, int max) {
        List<String> objects = new ArrayList<>();
        if (json == null) return objects;
        int keyIdx = json.indexOf("\"" + arrayKey + "\"");
        if (keyIdx < 0) return objects;
        int arrStart = json.indexOf('[', keyIdx);
        if (arrStart < 0) return objects;
        int arrEnd = matchingBracket(json, arrStart, '[', ']');
        if (arrEnd < 0) return objects;

        int pos = arrStart + 1;
        while (pos < arrEnd && objects.size() < max) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0 || objStart >= arrEnd) break;
            int objEnd = matchingBracket(json, objStart, '{', '}');
            if (objEnd < 0) break;
            objects.add(json.substring(objStart, objEnd + 1));
            pos = objEnd + 1;
        }
        return objects;
    }

    /** Extrait une valeur numérique simple {@code "key":1234} (pas de guillemets). */
    public static long jsonLong(String json, String key) {
        if (json == null) return 0L;
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return 0L;
        i = json.indexOf(':', i) + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int end = i;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (end == i) return 0L;
        try { return Long.parseLong(json.substring(i, end)); }
        catch (NumberFormatException e) { return 0L; }
    }

    /** Index du caractère fermant correspondant à {@code open} à la position {@code openPos}. */
    private static int matchingBracket(String s, int openPos, char open, char close) {
        int depth = 0;
        boolean inString = false;
        for (int i = openPos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') { i++; }
                else if (c == '"') { inString = false; }
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
