package com.yuyuframe.launcheragent.apigraphic.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Nœud d'un arbre de layout à passer à {@link TaffyBridge#computeLayout} —
 * {@code id} identifie le nœud dans le résultat ({@link TaffyLayoutResult}),
 * doit être unique dans l'arbre (un id vide/omis rend le nœud "anonyme" :
 * toujours layouté normalement, mais absent du résultat — utile pour un
 * conteneur purement structurel dont seul le contenu importe).
 */
public final class TaffyNode {
    public final String id;
    public final TaffyStyle style;
    public final List<TaffyNode> children = new ArrayList<>();

    public TaffyNode(String id, TaffyStyle style) {
        this.id = id == null ? "" : id;
        this.style = style;
    }

    public TaffyNode(String id) {
        this(id, new TaffyStyle());
    }

    public TaffyNode child(TaffyNode c) {
        children.add(c);
        return this;
    }

    /** Sérialise l'arbre entier — voir content-core/src/layout.rs pour le schéma attendu côté Rust. */
    public String toJson() {
        StringBuilder out = new StringBuilder(256);
        writeJson(out);
        return out.toString();
    }

    private void writeJson(StringBuilder out) {
        out.append('{');
        out.append("\"id\":\"").append(escape(id)).append("\",");
        style.writeJson(out);
        out.append("\"children\":[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) out.append(',');
            children.get(i).writeJson(out);
        }
        out.append(']');
        out.append('}');
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
