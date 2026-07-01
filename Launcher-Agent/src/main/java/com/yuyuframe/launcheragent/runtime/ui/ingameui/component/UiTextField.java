package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

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
 */
public class UiTextField extends UiWidget {

    private final StringBuilder text = new StringBuilder();
    private final String placeholder;
    private final Consumer<String> onChange;
    private boolean focused;

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

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM,
            focused ? UiTheme.ACCENT_DIM : UiTheme.PANEL_BG_ALT, vpWidth, vpHeight);

        float scale = 0.4f;
        float baseline = y + h / 2f - 4f;
        if (text.length() == 0 && !focused) {
            if (placeholder != null) renderer.drawText(placeholder, x + 8, baseline, UiTheme.TEXT_MUTED, scale, vpWidth, vpHeight);
            return;
        }

        String shown = text.toString();
        // Curseur clignotant en fin de texte — pas de position interne
        // (toujours en fin, voir la limitation en tête de fichier).
        if (focused && (System.currentTimeMillis() / 500L) % 2 == 0) shown = shown + "|";
        renderer.drawText(shown, x + 8, baseline, UiTheme.TEXT_PRIMARY, scale, vpWidth, vpHeight);
    }

    @Override
    public void onClick() {
        focused = true;
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        if (!focused) return;
        int before = text.length();
        input.pollTextEdit(text);
        if (text.length() != before && onChange != null) onChange.accept(text.toString());
    }
}
