package com.yuyuframe.launcheragent.apigraphic.layout;

import java.util.HashMap;
import java.util.Map;

/**
 * Parse le JSON retourné par {@link TaffyBridge#computeLayout} — même style
 * d'extraction minimaliste que {@code ModrinthJson} (pas de dépendance à une
 * lib JSON, voir sa javadoc de classe), adapté à la forme différente ici
 * (tableau plat d'objets à 5 champs, tous scalaires — pas de tableaux/objets
 * imbriqués à extraire, contrairement à {@code ModrinthJson.parseHits}).
 */
public final class TaffyLayoutResult {
    private TaffyLayoutResult() {}

    public static final class Rect {
        public final float x, y, w, h;
        public Rect(float x, float y, float w, float h) { this.x = x; this.y = y; this.w = w; this.h = h; }
        @Override public String toString() { return "Rect(" + x + "," + y + "," + w + "x" + h + ")"; }
    }

    /**
     * @return map id -> rect résolu, ou {@code null} si {@code json} est une
     * erreur ({@code {"error":"..."}}) — voir {@link #lastError} pour le
     * message dans ce cas.
     */
    public static Map<String, Rect> parse(String json) {
        lastError = null;
        if (json == null || json.isEmpty()) {
            lastError = "réponse vide";
            return null;
        }
        int errIdx = json.indexOf("\"error\"");
        if (errIdx >= 0 && json.trim().startsWith("{")) {
            lastError = jsonStringField(json, "error");
            return null;
        }

        Map<String, Rect> out = new HashMap<>();
        int pos = 0;
        while (true) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = matchingBrace(json, objStart);
            if (objEnd < 0) break;
            String obj = json.substring(objStart, objEnd + 1);
            String id = jsonStringField(obj, "id");
            Float x = jsonFloatField(obj, "x");
            Float y = jsonFloatField(obj, "y");
            Float w = jsonFloatField(obj, "w");
            Float h = jsonFloatField(obj, "h");
            if (id != null && x != null && y != null && w != null && h != null) {
                out.put(id, new Rect(x, y, w, h));
            }
            pos = objEnd + 1;
        }
        return out;
    }

    /** Dernier message d'erreur rencontré par {@link #parse} — {@code null} tant qu'aucun appel n'a échoué. */
    public static volatile String lastError;

    private static int matchingBrace(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String jsonStringField(String obj, String key) {
        String needle = "\"" + key + "\"";
        int keyIdx = obj.indexOf(needle);
        if (keyIdx < 0) return null;
        int colon = obj.indexOf(':', keyIdx + needle.length());
        if (colon < 0) return null;
        int quoteStart = obj.indexOf('"', colon + 1);
        if (quoteStart < 0) return null;
        StringBuilder sb = new StringBuilder();
        int i = quoteStart + 1;
        while (i < obj.length() && obj.charAt(i) != '"') {
            char c = obj.charAt(i);
            if (c == '\\' && i + 1 < obj.length()) {
                char next = obj.charAt(i + 1);
                if (next == 'n') sb.append('\n');
                else if (next == 't') sb.append('\t');
                else if (next == 'r') sb.append('\r');
                else sb.append(next);
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static Float jsonFloatField(String obj, String key) {
        String needle = "\"" + key + "\"";
        int keyIdx = obj.indexOf(needle);
        if (keyIdx < 0) return null;
        int colon = obj.indexOf(':', keyIdx + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) i++;
        int start = i;
        while (i < obj.length() && (Character.isDigit(obj.charAt(i)) || obj.charAt(i) == '-' || obj.charAt(i) == '+' || obj.charAt(i) == '.' || obj.charAt(i) == 'e' || obj.charAt(i) == 'E')) i++;
        if (i == start) return null;
        try {
            return Float.parseFloat(obj.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
