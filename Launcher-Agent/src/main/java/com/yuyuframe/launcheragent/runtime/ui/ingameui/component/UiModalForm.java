package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingModal;

import java.util.ArrayList;
import java.util.List;

/**
 * Rendu d'un {@link SettingModal} — un panneau centré qui pose UNE question à
 * la fois.
 *
 * <p>Les widgets sont reconstruits à chaque changement d'étape plutôt que
 * masqués : une étape porte soit un bouton de capture de touche, soit un champ
 * de texte, jamais les deux, et garder les deux vivants ferait cohabiter deux
 * cibles de saisie invisibles l'une pour l'autre.
 *
 * <p>Les réponses déjà données sont conservées ({@link #answers}) : revenir en
 * arrière ne les efface pas, ce qui est tout l'intérêt d'un formulaire par
 * étapes plutôt que d'une suite de fenêtres jetables.
 */
public final class UiModalForm {

    private static final float PANEL_W = 420f;
    private static final float PANEL_H = 210f;
    /** Valeur « aucune touche » — la même que {@code UiKeybindButton} rend pour Échap. */
    private static final String NO_KEY = "NONE";

    private final SettingModal modal;
    private final List<String> answers = new ArrayList<String>();
    private final Runnable onClose;

    private int step;
    private final List<UiWidget> widgets = new ArrayList<UiWidget>();
    private float px, py, pw, ph;
    private int lastW = -1, lastH = -1;
    private float lastScale = -1f;
    private int builtStep = -1;

    public UiModalForm(SettingModal modal, Runnable onClose) {
        this.modal = modal;
        this.onClose = onClose;
        // Une étape de touche démarre sur « NONE », la valeur que produit
        // UiKeybindButton pour « aucune touche » (Échap). Une chaîne vide
        // affichait un bouton VIDE et passait « Suivant » sans rien assigner
        // avec une valeur qu'aucun autre réglage de touche n'utilise.
        for (int i = 0; i < modal.steps.size(); i++) {
            answers.add(modal.steps.get(i).keybind ? NO_KEY : "");
        }
    }

    /** Widgets actifs — passés tels quels à {@code UiScreenBase.modalWidgets()}, ce qui leur réserve TOUS les clics. */
    public List<UiWidget> widgets() { return widgets; }

    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (vpWidth != lastW || vpHeight != lastH || UiTheme.UI_SCALE != lastScale || step != builtStep) {
            build(vpWidth, vpHeight);
            lastW = vpWidth; lastH = vpHeight; lastScale = UiTheme.UI_SCALE; builtStep = step;
        }

        // Voile plein écran : il assombrit le fond ET absorbe les clics à côté
        // du panneau, puisque modalWidgets() rend cette liste exclusive.
        renderer.drawRoundedRect(0, 0, vpWidth, vpHeight, 0f, new UiColor(0, 0, 0, 120), vpWidth, vpHeight);
        renderer.drawGlassPanel(px, py, px + pw, py + ph, UiTheme.RADIUS_MD,
            UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD, UiTheme.PANEL_BG,
            UiTheme.GLASS_BORDER, Math.max(1f, UiTheme.scaled(1f)), vpWidth, vpHeight);

        float pad = UiTheme.scaled(20f);
        renderer.drawText(UiFont.BOLD, Lang.tr(modal.title), px + pad, py + ph - UiTheme.scaled(30f),
            UiTheme.TEXT_PRIMARY, UiTheme.scaled(0.6f), vpWidth, vpHeight);

        SettingModal.Step current = modal.steps.get(step);
        renderer.drawText(Lang.tr(current.title), px + pad, py + ph - UiTheme.scaled(62f),
            UiTheme.TEXT_SECONDARY, UiTheme.scaled(0.5f), vpWidth, vpHeight);

        // « Étape 2 / 3 » — sans ce repère, un formulaire par étapes donne
        // l'impression de ne jamais finir.
        String progress = (step + 1) + " / " + modal.steps.size();
        float pw2 = renderer.textWidth(UiFont.REGULAR, progress, UiTheme.scaled(0.45f));
        renderer.drawText(progress, px + pw - pad - pw2, py + ph - UiTheme.scaled(30f),
            UiTheme.TEXT_MUTED, UiTheme.scaled(0.45f), vpWidth, vpHeight);

        for (UiWidget w : widgets) {
            try {
                w.draw(renderer, mouseX, mouseY, vpWidth, vpHeight);
            } catch (Throwable ignored) {}
        }
    }

    private void build(int vpWidth, int vpHeight) {
        widgets.clear();
        pw = UiTheme.scaled(PANEL_W);
        ph = UiTheme.scaled(PANEL_H);
        px = (vpWidth - pw) / 2f;
        py = (vpHeight - ph) / 2f;

        float pad = UiTheme.scaled(20f);
        float ctrlH = UiTheme.scaled(28f);
        float ctrlY = py + ph - UiTheme.scaled(112f);
        float ctrlW = pw - pad * 2f;

        SettingModal.Step current = modal.steps.get(step);
        final int index = step;
        if (current.keybind) {
            widgets.add(new UiKeybindButton(px + pad, ctrlY, UiTheme.scaled(150f), ctrlH,
                answers.get(index), v -> answers.set(index, v)));
        } else {
            UiTextField field = new UiTextField(px + pad, ctrlY, ctrlW, ctrlH,
                current.placeholder, v -> answers.set(index, v));
            field.setText(answers.get(index));
            widgets.add(field);
        }

        float btnH = UiTheme.scaled(28f);
        float btnW = UiTheme.scaled(110f);
        float btnY = py + UiTheme.scaled(20f);

        widgets.add(new UiButton(px + pad, btnY, btnW, btnH,
            step == 0 ? "Annuler" : "Précédent", () -> {
                if (step == 0) close();
                else step--;
            }));

        boolean last = step == modal.steps.size() - 1;
        widgets.add(new UiButton(px + pw - pad - btnW, btnY, btnW, btnH,
            last ? "Terminer" : "Suivant", () -> {
                if (!last) { step++; return; }
                try {
                    modal.onComplete.accept(new ArrayList<String>(answers));
                } finally {
                    // Fermeture DANS un finally : si le module lève en créant
                    // l'entrée, la modale doit quand même disparaître, sinon
                    // l'écran reste bloqué sans moyen d'en sortir.
                    close();
                }
            }));
    }

    private void close() {
        if (onClose != null) onClose.run();
    }
}
