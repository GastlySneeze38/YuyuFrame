package com.yuyuframe.launcheragent.apigraphic.layout;

/**
 * Style d'un {@link TaffyNode} — miroir Java du schéma JSON attendu par
 * {@code content-core/src/layout.rs} (voir sa javadoc de module pour le
 * détail complet). Toute dimension (largeur/hauteur/marge/padding/gap/inset)
 * est une {@code String} : {@link #AUTO}, {@code "50%"} (pourcentage), ou un
 * nombre en pixels ({@code "120"}, voir {@link #px}) — même convention des
 * deux côtés du pont JNI, aucune ambiguïté nombre/chaîne à désambiguïser
 * côté Rust.
 *
 * Champs publics mutables façon {@link com.yuyuframe.launcheragent.apigraphic.core.UiColor}/
 * {@code UiTheme} (pas de vrai encapsulement dans ce moteur) — quelques
 * méthodes fluentes en plus pour les cas les plus fréquents (évite un mur de
 * {@code new TaffyStyle(); s.foo = ...; s.bar = ...;} à chaque site d'appel).
 */
public final class TaffyStyle {

    public static final String AUTO = "auto";

    /** Nombre entier/flottant de pixels — {@code "120"}, jamais {@code "120px"} (l'unité est implicite côté Rust). */
    public static String px(float v) { return trimTrailingZero(v); }

    /** Pourcentage relatif au parent — {@code "50%"}. */
    public static String pct(float v) { return trimTrailingZero(v) + "%"; }

    private static String trimTrailingZero(float v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    public String display = "flex";        // "flex" | "grid" | "none"
    public String flexDirection = "row";    // "row" | "column" | "row-reverse" | "column-reverse"
    public String flexWrap = "no-wrap";     // "no-wrap" | "wrap" | "wrap-reverse"
    /** {@code null} = hérite du défaut Taffy (flex-start) — voir content-core/src/layout.rs#parse_justify_content pour les valeurs acceptées. */
    public String justifyContent;
    public String alignItems;
    public String alignContent;
    public String gapRow = "0", gapCol = "0";
    /** top, right, bottom, left — comme CSS. */
    public String[] padding = { "0", "0", "0", "0" };
    public String[] margin = { "0", "0", "0", "0" };
    public String width = AUTO, height = AUTO;
    public String minWidth = AUTO, minHeight = AUTO;
    public String maxWidth = AUTO, maxHeight = AUTO;
    public float flexGrow = 0f, flexShrink = 1f;
    public String flexBasis = AUTO;
    public String position = "relative"; // "relative" | "absolute"
    /** top, right, bottom, left — utilisé seulement si {@link #position} = "absolute". */
    public String[] inset = { AUTO, AUTO, AUTO, AUTO };

    public TaffyStyle flexDirection(String v) { this.flexDirection = v; return this; }
    public TaffyStyle justifyContent(String v) { this.justifyContent = v; return this; }
    public TaffyStyle alignItems(String v) { this.alignItems = v; return this; }
    public TaffyStyle gap(float row, float col) { this.gapRow = px(row); this.gapCol = px(col); return this; }
    public TaffyStyle padding(float top, float right, float bottom, float left) {
        this.padding = new String[]{ px(top), px(right), px(bottom), px(left) };
        return this;
    }
    public TaffyStyle padding(float all) { return padding(all, all, all, all); }
    public TaffyStyle size(String w, String h) { this.width = w; this.height = h; return this; }
    public TaffyStyle grow(float v) { this.flexGrow = v; return this; }
    public TaffyStyle shrink(float v) { this.flexShrink = v; return this; }

    /** Sérialise UNIQUEMENT le style (sans id/children, voir {@link TaffyNode#toJson()} pour l'objet complet). */
    void writeJson(StringBuilder out) {
        out.append("\"display\":\"").append(display).append("\",");
        out.append("\"flexDirection\":\"").append(flexDirection).append("\",");
        out.append("\"flexWrap\":\"").append(flexWrap).append("\",");
        writeNullableString(out, "justifyContent", justifyContent);
        writeNullableString(out, "alignItems", alignItems);
        writeNullableString(out, "alignContent", alignContent);
        out.append("\"gapRow\":\"").append(gapRow).append("\",");
        out.append("\"gapCol\":\"").append(gapCol).append("\",");
        writeRect(out, "padding", padding);
        writeRect(out, "margin", margin);
        out.append("\"width\":\"").append(width).append("\",");
        out.append("\"height\":\"").append(height).append("\",");
        out.append("\"minWidth\":\"").append(minWidth).append("\",");
        out.append("\"minHeight\":\"").append(minHeight).append("\",");
        out.append("\"maxWidth\":\"").append(maxWidth).append("\",");
        out.append("\"maxHeight\":\"").append(maxHeight).append("\",");
        out.append("\"flexGrow\":").append(flexGrow).append(',');
        out.append("\"flexShrink\":").append(flexShrink).append(',');
        out.append("\"flexBasis\":\"").append(flexBasis).append("\",");
        out.append("\"position\":\"").append(position).append("\",");
        writeRect(out, "inset", inset);
    }

    private static void writeNullableString(StringBuilder out, String key, String val) {
        if (val == null) return;
        out.append('"').append(key).append("\":\"").append(val).append("\",");
    }

    private static void writeRect(StringBuilder out, String key, String[] rect) {
        out.append('"').append(key).append("\":[\"")
            .append(rect[0]).append("\",\"").append(rect[1]).append("\",\"")
            .append(rect[2]).append("\",\"").append(rect[3]).append("\"],");
    }
}
