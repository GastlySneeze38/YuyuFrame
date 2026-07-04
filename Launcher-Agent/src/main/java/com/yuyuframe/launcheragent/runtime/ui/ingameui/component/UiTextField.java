package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;

import java.util.function.Consumer;

/**
 * Champ de texte MINIMAL — pas de curseur positionnable au clic, pas de
 * sélection/copier-coller, juste taper en fin et Backspace (voir
 * UiInputPoller.pollTextEdit) : suffisant pour un usage "barre de recherche",
 * pas pour de l'édition de texte riche. Pas de perte de focus automatique au
 * clic ailleurs (limitation connue, acceptable tant qu'un seul champ texte
 * existe à l'écran — voir UiMainMenuScreen).
 *
 * Bouton d'effacement (×) — seule façon fiable de vider le champ D'UN COUP
 * (Backspace répété reste la seule façon de supprimer caractère par
 * caractère, cette limitation de base n'a pas changé). Détecté dans
 * {@link #pollContinuous} (PAS via {@link #onClick()}, qui ne reçoit aucune
 * coordonnée — impossible d'y distinguer "clic sur le ×" de "clic ailleurs
 * dans le champ" sans changer la signature de UiWidget.onClick() pour TOUS
 * les widgets existants) : {@code UiInputPoller} expose déjà mouseX/mouseY/
 * leftDown en continu, largement suffisant ici.
 */
public class UiTextField extends UiWidget {

    private final StringBuilder text = new StringBuilder();
    private final String placeholder;
    private final Consumer<String> onChange;
    private boolean focused;
    private boolean prevLeftDown;
    private final UiAnimatedFloat clearHoverAnim = new UiAnimatedFloat(0f, 16f);

    public UiTextField(float x, float y, float w, float h, String placeholder, Consumer<String> onChange) {
        super(x, y, w, h);
        this.placeholder = placeholder;
        this.onChange = onChange;
    }

    public String text() { return text.toString(); }

    public void setFocused(boolean focused) { this.focused = focused; }

    /** Remplace le contenu SANS déclencher onChange (utilisé pour resynchroniser l'affichage depuis une autre source, ex: sliders du color picker — évite une boucle de rappel). */
    public void setText(String value) {
        text.setLength(0);
        if (value != null) text.append(value);
    }

    /** Zone carrée du × (côté = h, collée au bord droit) — seulement si du texte à effacer. */
    private boolean hasClearButton() { return text.length() > 0; }

    private boolean clearButtonContains(double mx, double my) {
        if (!hasClearButton()) return false;
        float bx = x + w - h;
        return mx >= bx && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM,
            focused ? UiTheme.ACCENT_DIM : UiTheme.PANEL_BG_ALT, vpWidth, vpHeight);

        float scale = UiTheme.scaled(0.48f);
        float baseline = y + h / 2f - UiTheme.scaled(5f);
        boolean clearVisible = hasClearButton();
        float textPad = UiTheme.scaled(8f);

        if (text.length() == 0 && !focused) {
            if (placeholder != null) renderer.drawText(placeholder, x + textPad, baseline, UiTheme.TEXT_MUTED, scale, vpWidth, vpHeight);
            return;
        }

        String shown = text.toString();
        // Curseur clignotant en fin de texte — pas de position interne
        // (toujours en fin, voir la limitation en tête de fichier).
        if (focused && (System.currentTimeMillis() / 500L) % 2 == 0) shown = shown + "|";
        renderer.drawText(shown, x + textPad, baseline, UiTheme.TEXT_PRIMARY, scale, vpWidth, vpHeight);

        if (clearVisible) {
            clearHoverAnim.setTarget(clearButtonContains(mouseX, mouseY) ? 1f : 0f);
            UiColor clearColor = UiColor.lerp(UiTheme.TEXT_MUTED, UiTheme.TEXT_PRIMARY, clearHoverAnim.get());
            float bx = x + w - h;
            float glyphScale = UiTheme.scaled(0.42f);
            float gw = renderer.textWidth("x", glyphScale);
            renderer.drawText("x", bx + (h - gw) / 2f, y + h / 2f - UiTheme.scaled(4f), clearColor, glyphScale, vpWidth, vpHeight);
        }
    }

    @Override
    public void onClick() {
        focused = true;
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        boolean justPressed = input.leftDown && !prevLeftDown;
        prevLeftDown = input.leftDown;
        if (justPressed && clearButtonContains(input.mouseX, input.mouseY)) {
            text.setLength(0);
            if (onChange != null) onChange.accept("");
            return;
        }

        if (!focused) return;
        int before = text.length();
        input.pollTextEdit(text);
        if (text.length() != before && onChange != null) onChange.accept(text.toString());
    }
}
