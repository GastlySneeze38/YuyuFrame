package com.yuyuframe.launcheragent.apigraphic.text;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;

/**
 * Un "run" de texte à style uniforme (roadmap Phase 5.3, rich text) — une
 * chaîne de {@link UiRichText} mélange plusieurs spans (gras/couleur/lien)
 * dans un même paragraphe, exactement le besoin identifié pour les
 * changelogs/descriptions de mods (texte Markdown-like venant de Modrinth).
 * AUCUN nouvel atlas/police requis : un span "gras" utilise simplement
 * {@link UiFont#BOLD} au lieu de {@link UiFont#REGULAR} au dessin — les deux
 * atlas existent déjà et sont construits une seule fois au démarrage (voir
 * {@code UiFont}), rien à recharger pour mélanger les deux dans une même ligne.
 */
public final class UiTextSpan {
    public final String text;
    public final boolean bold;
    public final UiColor color;
    /** {@code null} = span normal ; non-null = span cliquable, identifié par cette valeur (voir {@link UiRichText#hitTestLink}). */
    public final String linkId;
    /**
     * Multiplicateur appliqué à l'échelle de base du paragraphe pour CE span
     * (défaut 1.0) — ex: {@code 1.4f} pour un mot mis en avant plus gros dans
     * une ligne de taille normale. AUCUN nouvel atlas requis (contrairement à
     * une police bitmap classique à tailles fixes) : le texte est du SDF
     * (voir {@code UiFont}), donc résolution-indépendant par nature — changer
     * l'échelle d'un span ne recharge/régénère RIEN.
     */
    public final float sizeScale;

    public UiTextSpan(String text, boolean bold, UiColor color, String linkId, float sizeScale) {
        this.text = text;
        this.bold = bold;
        this.color = color;
        this.linkId = linkId;
        this.sizeScale = sizeScale;
    }

    public static UiTextSpan plain(String text, UiColor color) { return new UiTextSpan(text, false, color, null, 1f); }
    public static UiTextSpan bold(String text, UiColor color) { return new UiTextSpan(text, true, color, null, 1f); }
    public static UiTextSpan link(String text, UiColor color, String linkId) { return new UiTextSpan(text, false, color, linkId, 1f); }
    public static UiTextSpan sized(String text, UiColor color, float sizeScale) { return new UiTextSpan(text, false, color, null, sizeScale); }
}
