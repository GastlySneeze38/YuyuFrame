package com.yuyuframe.launcheragent.apigraphic.text;

import com.yuyuframe.launcheragent.apigraphic.value.UiFont;

/**
 * Mesure et troncature de texte — <b>calcul pur</b>, aucune dépendance au jeu
 * ni à OpenGL, donc vérifiable sans lancer Minecraft.
 *
 * <p>Extrait de {@code UiTextRenderer} le 2026-09-09. Ces trois méthodes y
 * étaient enfermées avec 700 lignes de shaders, d'upload de texture et de
 * gestion d'état GL — impossible de mesurer une chaîne sans un contexte
 * graphique vivant, alors que la mesure ne fait qu'additionner des avances de
 * glyphes.
 *
 * <p>C'est la première brique de la couche « calcul » de l'arborescence cible :
 * ce qui décide de la GÉOMÉTRIE et de la MISE EN PAGE ne connaît ni l'ère de
 * rendu ni le jeu, et vit au-dessus des deux.
 */
public final class UiTextLayout {

    private UiTextLayout() {}

    /** Largeur du texte dans la police par défaut, à l'échelle donnée. */
    public static float textWidth(String text, float scale) {
        return UiFont.REGULAR.textWidth(text, scale);
    }

    /** Largeur du texte dans une police précise. */
    public static float textWidth(UiFont font, String text, float scale) {
        return font.textWidth(text, scale);
    }

    /**
     * Tronque {@code text} (avec "...") pour tenir dans {@code maxWidth}
     * pixels à l'échelle donnée — sans effet (retourne {@code text} tel
     * quel) tant qu'il tient déjà dans cette largeur, donc directement
     * applicable partout SANS condition sur le mode d'échelle : un titre/
     * sous-titre ne déborde alors que quand il n'y a réellement plus la
     * place (ex: cartes du menu principal en "Taille de l'interface" =
     * Grande, voir UiMainMenuScreen.ModCard), jamais de retour à la ligne.
     * Déplacée dans le moteur depuis ModrinthContentScreen (où elle vivait à
     * l'origine, spécifique à cet écran) — devenue un besoin partagé, pas un
     * utilitaire propre à Modrinth.
     */
    public static String truncate(String text, float scale, float maxWidth) {
        if (text == null) return "";
        if (maxWidth <= 0 || textWidth(text, scale) <= maxWidth) return text;
        String ellipsis = "...";
        int len = text.length();
        while (len > 0 && textWidth(text.substring(0, len) + ellipsis, scale) > maxWidth) len--;
        return len <= 0 ? ellipsis : text.substring(0, len) + ellipsis;
    }
}
