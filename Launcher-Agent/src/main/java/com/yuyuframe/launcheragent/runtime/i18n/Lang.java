package com.yuyuframe.launcheragent.runtime.i18n;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Traduction de l'UI in-game — texte source (français, tel qu'écrit dans les
 * annotations {@code @Config*}/constructeurs de module/chrome UI) utilisé
 * DIRECTEMENT comme clé de recherche dans un fichier {@code lang/<id>.json}
 * (voir {@link #tr}), plutôt qu'une clé abstraite dédiée — permet d'ajouter
 * une langue sans toucher à un seul fichier Java existant (aucune annotation
 * ne change), et {@link #tr} retombe TOUJOURS sur le texte source (français)
 * si la clé est absente d'un fichier de langue — jamais de texte vide/cassé,
 * même pour une traduction partielle.
 *
 * Sélectionné via {@link com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings#language}
 * (dropdown "Langue", catégorie "Général") — {@link #setLanguage} recharge la
 * map active depuis le JAR (ressources {@code lang/*.json}, packagées par
 * build.bat comme {@code mixins.launcheragent*.json}).
 *
 * Pas de lib JSON (voir {@code ModrinthJson}, même contrainte/même style dans
 * ce projet) — {@link #parseFlatObject} attend un objet JSON PLAT (chaîne →
 * chaîne uniquement, jamais de valeur imbriquée), suffisant pour une table de
 * traduction.
 */
public final class Lang {
    private Lang() {}

    public static final String[] LANGUAGE_IDS = { "fr_fr", "en_us", "es_es", "de_de", "pt_br", "ru_ru" };
    public static final String[] LANGUAGE_NAMES = { "Français", "English", "Español", "Deutsch", "Português (Brasil)", "Русский" };

    private static volatile Map<String, String> active = new HashMap<>();
    private static volatile int currentIndex = -1;

    /** Appelé depuis {@code GlobalUiSettings.onConfigChanged()} — no-op si déjà sur cette langue (évite de re-parser le JSON à chaque frame si onConfigChanged est rappelé sans changement réel). */
    public static synchronized void setLanguage(int index) {
        if (index < 0 || index >= LANGUAGE_IDS.length) index = 0;
        if (index == currentIndex) return;
        currentIndex = index;
        active = load(LANGUAGE_IDS[index]);
    }

    /** Traduit {@code text} (texte source FRANÇAIS, utilisé tel quel comme clé) vers la langue active — retombe sur {@code text} inchangé si absent du fichier de langue actif (ou si aucune langue n'a encore été chargée). */
    public static String tr(String text) {
        if (text == null || text.isEmpty()) return text;
        String t = active.get(text);
        return t != null ? t : text;
    }

    private static Map<String, String> load(String id) {
        Map<String, String> map = new HashMap<>();
        try (InputStream is = Lang.class.getClassLoader().getResourceAsStream("lang/" + id + ".json")) {
            if (is == null) {
                LauncherLog.warn("[Lang] fichier de langue introuvable dans le JAR : lang/" + id + ".json");
                return map;
            }
            String json = readAll(is);
            parseFlatObject(json, map);
            LauncherLog.info("[Lang] langue chargée : " + id + " (" + map.size() + " entrées)");
        } catch (Throwable t) {
            LauncherLog.err("[Lang] load(" + id + "): " + t);
        }
        return map;
    }

    // Java 8 (compatibilityLevel du projet) : pas d'InputStream.readAllBytes().
    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Objet JSON plat {@code {"clé": "valeur", ...}} — mêmes échappements que {@code ModrinthJson.jsonString} (\\n/\\t/\\r/\\b/\\f/\\"/\\\\/\\/ + \\uXXXX), jamais de tableau/objet imbriqué à gérer ici. */
    private static void parseFlatObject(String json, Map<String, String> out) {
        if (json == null) return;
        int i = json.indexOf('{');
        if (i < 0) return;
        i++;
        int len = json.length();
        while (i < len) {
            while (i < len && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ',')) i++;
            if (i >= len || json.charAt(i) == '}') break;
            if (json.charAt(i) != '"') break;
            int[] keyEnd = new int[1];
            String key = readJsonString(json, i, keyEnd);
            i = keyEnd[0];
            while (i < len && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ':')) i++;
            if (i >= len || json.charAt(i) != '"') break;
            int[] valEnd = new int[1];
            String value = readJsonString(json, i, valEnd);
            i = valEnd[0];
            out.put(key, value);
        }
    }

    /** {@code start} doit pointer sur le guillemet ouvrant — {@code outEnd[0]} reçoit l'index JUSTE APRÈS le guillemet fermant. */
    private static String readJsonString(String json, int start, int[] outEnd) {
        StringBuilder sb = new StringBuilder();
        int end = start + 1;
        int len = json.length();
        while (end < len && json.charAt(end) != '"') {
            char c = json.charAt(end);
            if (c == '\\' && end + 1 < len) {
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
                        if (end + 6 <= len) {
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
        outEnd[0] = end + 1;
        return sb.toString();
    }
}
