package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPollerModern;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiWidget;

import java.util.function.Consumer;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;

/**
 * Bouton de réassignation de touche — clic pour entrer en mode "écoute", puis
 * la prochaine touche pressée (voir UiInputPoller.pollAnyKeyJustPressed) est
 * capturée et affichée. Stocke un NOM de touche (ex: "A", "F1", "MOUSE4"),
 * pas un code numérique brut : LWJGL2 (1.8.9) et GLFW (1.21+) n'utilisent pas
 * le même référentiel de codes, un nom textuel reste portable entre versions.
 *
 * <p>Les BOUTONS DE SOURIS sont capturables au même titre que les touches
 * (« MOUSE1 » à « MOUSE8 ») — voir {@code
 * UiInputPollerModern.mouseIndexForName}.
 */
public class UiKeybindButton extends UiWidget {

    private static final UiColor BASE  = new UiColor(40, 40, 49, 255);
    private static final UiColor HOVER = new UiColor(52, 52, 62, 255);

    private String keyName;
    private boolean listening;
    private final Consumer<String> onChange;
    private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

    public UiKeybindButton(float x, float y, float w, float h, String initialKeyName, Consumer<String> onChange) {
        super(x, y, w, h);
        this.keyName = initialKeyName != null ? initialKeyName : "NONE";
        this.onChange = onChange;
    }

    public String keyName() { return keyName; }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
        UiColor bg = listening ? UiTheme.ACCENT_DIM : UiColor.lerp(BASE, HOVER, hoverAnim.get());
        renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);

        // Pendant la capture, on montre ce qui est déjà retenu : sans ce
        // retour, composer une combinaison se fait à l'aveugle.
        String label = listening
            ? (captured.isEmpty() ? "..." : String.join("+", captured))
            : keyName;
        float scale = UiTheme.scaled(0.48f);
        float tw = renderer.textWidth(label, scale);
        // Une combinaison à trois touches déborde du bouton — on rétrécit
        // plutôt que de laisser le texte sortir de son fond.
        float maxW = w - UiTheme.scaled(8f);
        if (tw > maxW && tw > 0f) {
            scale *= maxW / tw;
            tw = renderer.textWidth(label, scale);
        }
        float baseline = y + h / 2f - (UiFont.REGULAR.ascent - UiFont.REGULAR.descent) * scale * UiFont.SIZE_CORRECTION / 2f;
        renderer.drawText(label, x + (w - tw) / 2f, baseline, listening ? UiTheme.ACCENT : UiTheme.TEXT_PRIMARY, scale, vpWidth, vpHeight);
    }

    /**
     * Une capture est en cours quelque part à l'écran.
     *
     * <p>Statique parce que le consommateur — {@code UiScreenBase.handleEscape}
     * — n'a aucun moyen de savoir QUEL widget écoute, et qu'il ne peut y avoir
     * qu'une capture à la fois de toute façon. Même idiome que
     * {@code UiInputPoller.textInputActive}, pour la même raison : Échap doit
     * être arbitré globalement, avant d'arriver aux widgets.
     */
    private static boolean capturing;

    public static boolean captureInProgress() { return capturing; }

    @Override
    public void onClick() {
        listening = true;
        capturing = true;
        captured.clear();
        // Le clic qui ARME la capture est dispatché à l'ENFONCEMENT (voir
        // UiScreenBase.dispatchClick, appelé depuis mouseClicked de vanilla) :
        // le bouton gauche est donc encore maintenu à cet instant. Depuis que
        // les boutons de souris sont capturables, sans ce garde-fou la capture
        // retiendrait « MOUSE1 » immédiatement et se validerait au
        // relâchement — rendant impossible d'assigner quoi que ce soit
        // d'autre. On attend donc que TOUT soit relâché avant d'écouter.
        awaitingRelease = true;
    }

    /** Voir {@link #onClick()} — vrai tant que le clic d'armement n'est pas relâché. */
    private boolean awaitingRelease;

    /** Nombre maximum de touches d'une combinaison — trois, comme demandé. */
    private static final int MAX_KEYS = 3;

    /**
     * Touches vues maintenues depuis le début de la capture. {@code Linked}
     * pour garder l'ordre canonique fourni par le poller (modificateurs
     * d'abord), pas l'ordre d'arrivée.
     */
    private final java.util.LinkedHashSet<String> captured = new java.util.LinkedHashSet<String>();

    /**
     * Capture une COMBINAISON jusqu'à {@link #MAX_KEYS} touches.
     *
     * <p>Principe : tant que la capture est active, on accumule tout ce qui
     * est maintenu ; la combinaison est validée au RELÂCHEMENT complet. C'est
     * ce qui permet « Ctrl + Maj + K » — valider à la première touche pressée,
     * comme le faisait la version précédente, rendrait toute combinaison
     * impossible à saisir, puisqu'un modificateur descend forcément en
     * premier.
     *
     * <p>Le poller donne l'ordre canonique à chaque frame, donc on repart de
     * sa liste plutôt que d'ajouter au fil de l'eau : deux captures de la même
     * combinaison produisent ainsi exactement la même chaîne, quel que soit
     * l'ordre d'appui.
     */
    @Override
    public void pollContinuous(UiInputPoller input) {
        if (!listening) return;
        if (!(input instanceof UiInputPollerModern)) {
            // Bracket sans poller moderne (1.8.9) : on garde l'ancien
            // comportement à une touche plutôt que de ne rien capturer.
            String pressed = input.pollAnyKeyJustPressed();
            if (pressed != null) {
                keyName = pressed;
                listening = false;
                capturing = false;
                if (onChange != null) onChange.accept(keyName);
            }
            return;
        }

        java.util.List<String> held = ((UiInputPollerModern) input).heldCapturableKeys();
        if (awaitingRelease) {
            if (held.isEmpty()) awaitingRelease = false;
            return;
        }
        if (!held.isEmpty()) {
            for (String name : held) {
                if (captured.size() >= MAX_KEYS && !captured.contains(name)) continue;
                captured.add(name);
            }
            // Réordonne selon l'instantané courant : held est déjà canonique.
            java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<String>();
            for (String name : held) if (captured.contains(name)) ordered.add(name);
            for (String name : captured) ordered.add(name);
            captured.clear();
            captured.addAll(ordered);
            return;
        }

        if (captured.isEmpty()) return;   // rien encore appuyé, on attend

        // Tout relâché : on valide. Échap seul = désassignation, convention
        // habituelle — sans ça, une touche mal choisie serait impossible à
        // retirer autrement qu'en éditant le fichier de configuration.
        String result = captured.size() == 1 && captured.contains("ESCAPE")
            ? "NONE" : String.join("+", captured);
        captured.clear();
        listening = false;
        capturing = false;
        keyName = result;
        if (onChange != null) onChange.accept(keyName);
    }
}
