package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.widget.UiFocusable;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

/**
 * Champ de texte mono-ligne — curseur positionnable (clic/flèches), sélection
 * (glisser-déposer, Maj+flèches/Origine/Fin, Ctrl+A) et presse-papiers système
 * (Ctrl+C/X/V, voir {@link #copyToClipboard}/{@link #pasteFromClipboard}, via
 * {@code java.awt.Toolkit} — indépendant de LWJGL2/GLFW, donc identique sur
 * toutes les versions). Retour arrière/Suppr se répètent tant que la touche
 * reste enfoncée (voir {@link UiInputPoller#keyRepeatFire}). PAS de retour à
 * la ligne/formatage riche — reste un champ de recherche/saisie courte, pas un
 * éditeur de texte multi-ligne.
 *
 * Bouton d'effacement (×) — vide le champ D'UN COUP, en plus de Backspace/Suppr
 * caractère par caractère. Détecté dans {@link #pollContinuous} (comme le
 * positionnement du curseur au clic et le glisser pour sélectionner) : {@code
 * UiInputPoller} expose mouseX/mouseY/leftDown en continu, pas besoin de
 * changer la signature de {@link UiWidget#onClick()} (qui ne reçoit aucune
 * coordonnée) pour aucun de ces trois usages.
 */
public class UiTextField extends UiWidget implements UiFocusable {

    private final StringBuilder text = new StringBuilder();
    private String placeholder;
    private final Consumer<String> onChange;
    private boolean focused;
    private boolean prevLeftDown;
    private boolean dragging;
    private int caret;
    /** Ancre de sélection ; {@code -1} = pas de sélection. Plage sélectionnée = [min(anchor,caret), max(anchor,caret)]. */
    private int selAnchor = -1;
    private final UiAnimatedFloat clearHoverAnim = new UiAnimatedFloat(0f, 16f);
    private final UiAnimatedFloat focusAnim = new UiAnimatedFloat(0f, 10f);

    private boolean searchIcon;
    /** Déclenché par Entrée (ou Entrée pavé numérique) pendant que ce champ a le focus — voir {@link UiInputPoller#pollTextEdit}. Null = pas de soumission explicite (juste onChange à chaque frappe). */
    private Runnable onSubmit;

    public UiTextField(float x, float y, float w, float h, String placeholder, Consumer<String> onChange) {
        super(x, y, w, h);
        this.placeholder = placeholder;
        this.onChange = onChange;
    }

    /** Active l'icône loupe à gauche (barre de recherche) — décoratif, décale juste le texte/placeholder. Fluent. */
    public UiTextField searchIcon() {
        this.searchIcon = true;
        return this;
    }

    /**
     * Masque le contenu à l'affichage (mot de passe) — le texte RÉEL reste
     * intact, seul le rendu change. Fluent.
     *
     * <p>Le masquage porte sur {@link #displayed()}, pas sur {@link #text},
     * pour que la sélection, le placement du curseur et le presse-papiers
     * continuent de travailler sur la vraie chaîne. Une seule exception
     * volontaire : la copie est refusée tant que le champ est masqué, voir
     * {@link #copySelection}.
     */
    public UiTextField masked() {
        this.masked = true;
        return this;
    }

    private boolean masked;
    private boolean revealed;

    /** {@code true} si ce champ masque son contenu — l'appelant peut alors proposer un œil, voir {@link #setRevealed}. */
    public boolean isMasked() { return masked; }

    /** L'œil : montre temporairement le contenu d'un champ masqué. */
    public void setRevealed(boolean revealed) { this.revealed = revealed; }

    public boolean isRevealed() { return revealed; }

    /**
     * Ce que l'utilisateur VOIT — points de masquage ou texte réel.
     *
     * <p>Le caractère « • » (U+2022) est hors Latin-1, il a donc fallu
     * l'ajouter au jeu de caractères d'{@code UiFont} : sans ça on obtenait le
     * glyphe de repli, exactement comme pour le « ∞ » de la saturation.
     */
    private String displayed() {
        if (!masked || revealed) return text.toString();
        StringBuilder dots = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) dots.append('•');
        return dots.toString();
    }

    /** Callback de soumission (Entrée) — fluent, voir {@link #onSubmit}. */
    public UiTextField onSubmit(Runnable callback) {
        this.onSubmit = callback;
        return this;
    }

    public String text() { return text.toString(); }

    public boolean focused() { return focused; }

    /** SEUL point d'entrée du focus (voir aussi {@link #onClick}, qui appelle celui-ci) — synchronise {@link UiInputPoller#textInputActive}, voir sa javadoc pour pourquoi c'est nécessaire. */
    public void setFocused(boolean focused) {
        this.focused = focused;
        UiInputPoller.textInputActive = focused;
    }

    /** Change le texte indicatif affiché quand le champ est vide et non focus (ex: bascule d'onglet Resource Packs/Shaders, voir ModrinthContentScreen). */
    public void setPlaceholder(String placeholder) { this.placeholder = placeholder; }

    /** Remplace le contenu SANS déclencher onChange (utilisé pour resynchroniser l'affichage depuis une autre source, ex: sliders du color picker — évite une boucle de rappel). */
    public void setText(String value) {
        text.setLength(0);
        if (value != null) text.append(value);
        caret = text.length();
        selAnchor = -1;
    }

    /** Zone carrée du × (côté = h, collée au bord droit) — seulement si du texte à effacer. */
    private boolean hasClearButton() { return text.length() > 0; }

    private boolean clearButtonContains(double mx, double my) {
        if (!hasClearButton()) return false;
        float bx = x + w - h;
        return mx >= bx && mx <= x + w && my >= y && my <= y + h;
    }

    /** Largeur réservée à l'icône loupe (0 si désactivée) — décale le texte, voir {@link #textStartX}. */
    private float iconAreaWidth() { return searchIcon ? h * 0.85f : 0f; }

    private float scale() { return UiTheme.scaled(0.48f); }

    private float textStartX() { return x + UiTheme.scaled(8f) + iconAreaWidth(); }

    // Curseur/surlignage proportionnés à la taille RÉELLE du texte affiché
    // (UiFont.REFERENCE_PX * scale = taille de police effective à l'écran,
    // voir sa javadoc), PAS à h du champ — 78%/22% = répartition ascendant/
    // descendant typique d'une police latine, suffisant pour un repère visuel
    // (pas besoin des vraies métriques ascent/descent de UiFont ici).
    private static float glyphTopOffset(float scale) { return UiFont.REFERENCE_PX * scale * 0.78f; }

    private static float glyphBottomOffset(float scale) { return UiFont.REFERENCE_PX * scale * 0.22f; }

    private boolean hasSelection() { return selAnchor >= 0 && selAnchor != caret; }

    private int selectionStart() { return Math.min(selAnchor, caret); }

    private int selectionEnd() { return Math.max(selAnchor, caret); }

    private void deleteSelection() {
        if (!hasSelection()) return;
        int s = selectionStart(), e = selectionEnd();
        text.delete(s, e);
        caret = s;
        selAnchor = -1;
    }

    /** Index de caractère le plus proche de {@code mx} (espace framebuffer) — recherche linéaire sur les largeurs de préfixe, largement assez rapide pour une requête de recherche. */
    private int caretIndexForX(UiRenderer renderer, double mx) {
        // displayed() : sur un champ masqué, les points n'ont pas la largeur
        // des vrais caractères — mesurer le texte réel placerait le curseur
        // à côté du clic. Les INDEX, eux, sont les mêmes dans les deux
        // chaînes (un point par caractère), donc le résultat reste valide
        // pour la vraie chaîne.
        String s = displayed();
        float startX = textStartX();
        float sc = scale();
        float best = Float.MAX_VALUE;
        int bestIdx = s.length();
        for (int i = 0; i <= s.length(); i++) {
            float charX = startX + renderer.textWidth(s.substring(0, i), sc);
            float dist = Math.abs((float) mx - charX);
            if (dist < best) {
                best = dist;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static void copyToClipboard(String s) {
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
        } catch (Throwable ignored) {
            // Presse-papiers indisponible (environnement restreint) — pas bloquant, juste inopérant.
        }
    }

    private static String pasteFromClipboard() {
        try {
            Object data = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            return data == null ? null : data.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        // Pilule de verre (rework UI 2026-08-27) — c'est sur une petite
        // surface comme celle-ci que le flou du décor se lit le mieux, d'où la
        // teinte la plus légère de la palette (GLASS_STRENGTH_FIELD). Le fond
        // ACCENT_DIM du focus, lui, RESTE un aplat par-dessus le verre : c'est
        // un signal d'état, il doit garder exactement la même couleur quel que
        // soit le décor derrière (un accent qui change de teinte selon le
        // paysage ne se lit plus comme un état).
        // Anneau de focus / contour permanent posés DERRIÈRE le panneau — voir
        // la variante à contour de drawGlassPanel : drawRoundedRectBorder est
        // inerte sur era E (GL brut, corrompait l'état GPU).
        focusAnim.setTarget(focused ? 1f : 0f);
        float ringT = focusAnim.get();
        UiColor ringColor = UiColor.lerp(
            renderer.isGlassAvailable() ? UiTheme.GLASS_BORDER : UiColor.TRANSPARENT,
            UiTheme.ACCENT, ringT);
        renderer.drawGlassPanel(x, y, x + w, y + h, UiTheme.RADIUS_SM,
            UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD, UiTheme.PANEL_BG_ALT,
            ringColor, Math.max(1f, UiTheme.scaled(1f)) + ringT * 0.5f, vpWidth, vpHeight);
        if (focused) {
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, UiTheme.ACCENT_DIM, vpWidth, vpHeight);
        }


        float scale = scale();
        float baseline = y + h / 2f - UiTheme.scaled(5f);
        boolean clearVisible = hasClearButton();
        float textPad = textStartX() - x;

        if (searchIcon) {
            float iconSize = h * 0.5f;
            float iconX = x + UiTheme.scaled(8f) + (iconAreaWidth() - iconSize) / 2f;
            float iconY = y + (h - iconSize) / 2f;
            renderer.drawIcon(focused ? "ui:search-glyph-active" : "ui:search-glyph-muted",
                searchGlyph(focused), iconX, iconY, iconSize, vpWidth, vpHeight);
        }

        if (text.length() == 0 && !focused) {
            if (placeholder != null) renderer.drawText(placeholder, x + textPad, baseline, UiTheme.TEXT_MUTED, scale, vpWidth, vpHeight);
            return;
        }

        // displayed() et non text : sur un champ masqué, tout ce qui suit
        // (surlignage, position du curseur) doit se mesurer sur ce qui est
        // RÉELLEMENT dessiné, sinon le curseur se placerait selon la largeur
        // du vrai texte et flotterait à côté des points.
        String shown = displayed();

        // Surlignage de sélection — DERRIÈRE le texte, voir ordre de dessin.
        if (focused && hasSelection()) {
            int s = selectionStart(), e = selectionEnd();
            float xs = x + textPad + renderer.textWidth(shown.substring(0, s), scale);
            float xe = x + textPad + renderer.textWidth(shown.substring(0, e), scale);
            renderer.drawRoundedRect(xs, baseline - glyphBottomOffset(scale), xe, baseline + glyphTopOffset(scale), 2f,
                UiTheme.ACCENT.multiplyAlpha(0.4f), vpWidth, vpHeight);
        }

        renderer.drawText(shown, x + textPad, baseline, UiTheme.TEXT_PRIMARY, scale, vpWidth, vpHeight);

        // Curseur clignotant à sa VRAIE position (plus un "|" toujours en fin
        // de texte) — masqué pendant qu'une sélection non vide est affichée
        // (convention standard : la sélection elle-même sert de repère visuel).
        // Dimensionné sur la taille RÉELLE du texte affiché (voir
        // glyphTopOffset/glyphBottomOffset) — un premier jet calé sur h du
        // champ (~28px) donnait une grosse barre disproportionnée par rapport
        // au texte (~15px), confirmé en jeu ("bar clignotante trop grande").
        if (focused && !hasSelection() && (System.currentTimeMillis() / 500L) % 2 == 0) {
            float cx = x + textPad + renderer.textWidth(shown.substring(0, caret), scale);
            renderer.drawRoundedRect(cx, baseline - glyphBottomOffset(scale), cx + UiTheme.scaled(1.2f), baseline + glyphTopOffset(scale),
                0f, UiTheme.TEXT_PRIMARY, vpWidth, vpHeight);
        }

        if (clearVisible) {
            clearHoverAnim.setTarget(clearButtonContains(mouseX, mouseY) ? 1f : 0f);
            UiColor clearColor = UiColor.lerp(UiTheme.TEXT_MUTED, UiTheme.TEXT_PRIMARY, clearHoverAnim.get());
            float bx = x + w - h;
            float glyphScale = UiTheme.scaled(0.42f);
            float gw = renderer.textWidth("x", glyphScale);
            renderer.drawText("x", bx + (h - gw) / 2f, y + h / 2f - UiTheme.scaled(4f), clearColor, glyphScale, vpWidth, vpHeight);
        }
    }

    // ── Icône loupe — bitmap AWT (PAS vectoriel/primitives UiRenderer) ──────
    //
    // Un premier jet composait l'icône à partir de drawRoundedRect(Border) —
    // anneau correct, mais le "manche" de la loupe (une diagonale, aucune
    // primitive de ligne/rotation dans UiRenderer) était approximé par des
    // petits carrés arrondis qui se chevauchent : à la taille réelle d'affichage
    // (~10-14px), chaque carré ne fait que 2-3px, largement sous le seuil où
    // l'antialiasing des coins arrondis lisse quoi que ce soit — résultat
    // confirmé PIXELISÉ en jeu. Fix : dessiner le glyphe UNE FOIS en AWT
    // (Graphics2D supporte nativement une VRAIE ligne à 45° avec antialiasing
    // + bouts arrondis) dans un raster 64×64, puis le faire passer par le
    // pipeline de texture déjà existant ({@link UiRenderer#drawIcon}, prouvé
    // par les icônes de packs Modrinth) — le GPU le rééchantillonne ensuite en
    // bilinéaire à la taille d'affichage réelle, lisse à n'importe quelle
    // échelle. Couleur BAKÉE dans le raster (drawIcon ne teinte pas la
    // texture) — deux variantes mises en cache (focus/non-focus), régénérées
    // une seule fois par couleur (TEXT_MUTED/TEXT_SECONDARY sont `final`, donc
    // stables — pas de risque de désynchronisation avec un thème réassigné en
    // direct, contrairement à ACCENT).
    private static volatile BufferedImage searchGlyphMuted;
    private static volatile BufferedImage searchGlyphActive;

    private static BufferedImage searchGlyph(boolean active) {
        if (active) {
            if (searchGlyphActive == null) searchGlyphActive = renderSearchGlyph(UiTheme.TEXT_SECONDARY);
            return searchGlyphActive;
        }
        if (searchGlyphMuted == null) searchGlyphMuted = renderSearchGlyph(UiTheme.TEXT_MUTED);
        return searchGlyphMuted;
    }

    private static BufferedImage renderSearchGlyph(UiColor color) {
        int size = 64;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(new Color(color.r, color.g, color.b, color.a));
            float stroke = size * 0.10f;
            g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            float c = size * 0.40f; // centre de l'anneau (même valeur sur X/Y — glyphe symétrique)
            float r = size * 0.24f;
            g.draw(new Ellipse2D.Float(c - r, c - r, r * 2, r * 2));

            float diag = 0.7071f; // cos(45°) = sin(45°)
            float handleLen = size * 0.30f;
            float x1 = c + r * diag, y1 = c + r * diag;
            float x2 = c + (r + handleLen) * diag, y2 = c + (r + handleLen) * diag;
            g.draw(new Line2D.Float(x1, y1, x2, y2));
        } finally {
            g.dispose();
        }
        return img;
    }

    @Override
    public void onClick() {
        setFocused(true);
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        boolean justPressed = input.leftDown && !prevLeftDown;
        boolean justReleased = !input.leftDown && prevLeftDown;
        prevLeftDown = input.leftDown;

        if (justPressed && clearButtonContains(input.mouseX, input.mouseY)) {
            text.setLength(0);
            caret = 0;
            selAnchor = -1;
            dragging = false;
            if (onChange != null) onChange.accept("");
            return;
        }

        // Positionnement du curseur au clic + glisser pour sélectionner — AVANT
        // le "if (!focused) return" ci-dessous : doit fonctionner dès le tout
        // premier clic qui donne le focus (onClick() ne reçoit aucune
        // coordonnée, voir javadoc de classe), pas seulement une fois déjà focus.
        if (justPressed && contains(input.mouseX, input.mouseY)) {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            caret = caretIndexForX(renderer, input.mouseX);
            selAnchor = -1;
            dragging = true;
        } else if (justReleased) {
            dragging = false;
        } else if (dragging && input.leftDown) {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            int idx = caretIndexForX(renderer, input.mouseX);
            if (selAnchor < 0) selAnchor = caret;
            caret = idx;
        }

        if (!focused) return;

        // Renseigne input.edit* pour cette frame (voir UiInputPoller — champs
        // plats, PAS un objet porte-données dédié : un type imbriqué causait
        // un LinkageError inter-classloader sous Fabric, voir sa javadoc).
        input.pollTextEdit();
        boolean textChanged = false;

        if (!input.editTyped.isEmpty()) {
            deleteSelection();
            text.insert(caret, input.editTyped);
            caret += input.editTyped.length();
            selAnchor = -1;
            textChanged = true;
        }
        if (input.editBackspace) {
            if (hasSelection()) {
                deleteSelection();
                textChanged = true;
            } else if (caret > 0) {
                text.deleteCharAt(caret - 1);
                caret--;
                textChanged = true;
            }
        }
        if (input.editDelete) {
            if (hasSelection()) {
                deleteSelection();
                textChanged = true;
            } else if (caret < text.length()) {
                text.deleteCharAt(caret);
                textChanged = true;
            }
        }
        if (input.editLeft) {
            if (input.editShiftHeld) {
                if (selAnchor < 0) selAnchor = caret;
                if (caret > 0) caret--;
            } else {
                caret = hasSelection() ? selectionStart() : Math.max(0, caret - 1);
                selAnchor = -1;
            }
        }
        if (input.editRight) {
            if (input.editShiftHeld) {
                if (selAnchor < 0) selAnchor = caret;
                if (caret < text.length()) caret++;
            } else {
                caret = hasSelection() ? selectionEnd() : Math.min(text.length(), caret + 1);
                selAnchor = -1;
            }
        }
        if (input.editHome) {
            if (input.editShiftHeld) { if (selAnchor < 0) selAnchor = caret; } else selAnchor = -1;
            caret = 0;
        }
        if (input.editEnd) {
            if (input.editShiftHeld) { if (selAnchor < 0) selAnchor = caret; } else selAnchor = -1;
            caret = text.length();
        }
        if (input.editSelectAll && text.length() > 0) {
            selAnchor = 0;
            caret = text.length();
        }
        if (input.editCopy || input.editCut) {
            // Copie REFUSÉE sur un champ masqué non révélé : masquer à
            // l'écran puis laisser Ctrl+C extraire le mot de passe en clair
            // n'aurait aucun sens. La coupe reste possible une fois l'œil
            // ouvert, où le contenu est déjà visible.
            String toCopy = (masked && !revealed) ? ""
                : (hasSelection() ? text.substring(selectionStart(), selectionEnd()) : text.toString());
            if (!toCopy.isEmpty()) {
                copyToClipboard(toCopy);
                if (input.editCut) {
                    if (hasSelection()) deleteSelection();
                    else { text.setLength(0); caret = 0; }
                    textChanged = true;
                }
            }
        }
        if (input.editPaste) {
            String clip = pasteFromClipboard();
            if (clip != null && !clip.isEmpty()) {
                deleteSelection();
                String filtered = clip.replace("\r", "").replace("\n", " ").replace("\t", " ");
                text.insert(caret, filtered);
                caret += filtered.length();
                selAnchor = -1;
                textChanged = true;
            }
        }

        caret = Math.max(0, Math.min(caret, text.length()));
        if (selAnchor > text.length()) selAnchor = text.length();

        if (textChanged && onChange != null) onChange.accept(text.toString());
        if (input.editEnter && onSubmit != null) onSubmit.run();
    }
}
