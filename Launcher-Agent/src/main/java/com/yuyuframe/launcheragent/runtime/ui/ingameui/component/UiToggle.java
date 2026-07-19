package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;

import java.util.function.Consumer;

/** Switch booléen style OneConfig — piste arrondie + bouton rond qui glisse, transition animée (voir UiAnimatedFloat). */
public class UiToggle extends UiWidget {

    private static final float ANIM_SPEED = 14f;

    private boolean value;
    private final Consumer<Boolean> onChange;
    private final UiAnimatedFloat anim;

    /**
     * Opacité EXTERNE (0..1, défaut 1) — PAS l'animation ON/OFF interne
     * ({@link #anim}, nom local {@code t} dans {@link #draw}, sans rapport).
     * BUG TROUVÉ (retour utilisateur : "les toggle ne sont pas soumis au
     * fade-in") — ce toggle est un widget SÉPARÉ de sa carte (voir
     * UiMainMenuScreen.ModCard#pairedToggle) : {@link #clipFade} (fondu de
     * bord de viewport) est déjà posé indépendamment par UiScrollContainer,
     * mais le fondu D'ENTRÉE EN CASCADE d'une nouvelle carte (son propre
     * {@code enterAnim}) n'a, lui, aucun lien avec ce widget — sans ce champ,
     * le toggle apparaissait immédiatement à pleine opacité pendant que sa
     * carte, elle, était encore en train de s'estomper depuis 0. ModCard
     * pousse sa valeur ici chaque frame (même motif que {@code pairedToggle.y}).
     */
    private float externalAlpha = 1f;

    /**
     * BUG TROUVÉ (retour utilisateur : "vu que sa taille est plus grande la
     * card peut être presque invisible alors que le toggle est bien
     * visible") — {@link #clipFade} est posé INDÉPENDAMMENT par
     * UiScrollContainer, calculé sur la taille PROPRE de CE widget ; pour
     * une carte de mod (bien plus haute que son toggle), le bord du
     * viewport "mange" beaucoup plus de la carte que du petit toggle qui y
     * est posé — deux valeurs de fondu différentes pour un seul élément
     * visuel. Désactivé par {@code ModCard#pairToggle} (jamais par un
     * toggle de config standard, config screens) : l'opacité vient alors
     * ENTIÈREMENT de {@link #externalAlpha}, poussée par la carte elle-même
     * (qui, elle, combine bien SON PROPRE clipFade + son fondu d'entrée).
     */
    private boolean useOwnClipFade = true;

    public UiToggle(float x, float y, boolean initial, Consumer<Boolean> onChange) {
        super(x, y, UiTheme.scaled(44f), UiTheme.scaled(24f));
        this.value = initial;
        this.onChange = onChange;
        this.anim = new UiAnimatedFloat(initial ? 1f : 0f, ANIM_SPEED);
    }

    public boolean value() { return value; }

    public void setExternalAlpha(float alpha) { this.externalAlpha = alpha; }

    /** Voir {@link #useOwnClipFade} — appelé UNE FOIS par {@code ModCard#pairToggle}. */
    public void useExternalAlphaOnly() { this.useOwnClipFade = false; }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        float t = anim.get();
        // Piste en dégradé (haut plus clair, bas = accent normal) plutôt
        // qu'une couleur plate à l'état ON — petit reflet "glossy" cohérent
        // avec les jeux de lumière du reste de l'appli (voir ModCard/
        // SidebarItem). Le OFF reste plat (TRACK_OFF des deux côtés) : le
        // dégradé n'apparaît qu'en se rapprochant de ON.
        float drawAlpha = useOwnClipFade ? (clipFade * externalAlpha) : externalAlpha;
        UiColor top = UiColor.lerp(UiTheme.TRACK_OFF, UiTheme.accentLight(), t).multiplyAlpha(drawAlpha);
        UiColor bottom = UiColor.lerp(UiTheme.TRACK_OFF, UiTheme.ACCENT, t).multiplyAlpha(drawAlpha);
        renderer.drawGradientRect(x, y, x + w, y + h, h / 2f, bottom, top, vpWidth, vpHeight);

        float knobD = h - 4f;
        float knobXOff = 2f + t * (w - knobD - 4f); // 2f (position OFF) -> w-knobD-2f (position ON)
        renderer.drawRoundedRect(x + knobXOff, y + 2f, x + knobXOff + knobD, y + h - 2f, knobD / 2f, UiTheme.TEXT_PRIMARY.multiplyAlpha(drawAlpha), vpWidth, vpHeight);
    }

    @Override
    public void onClick() {
        value = !value;
        anim.setTarget(value ? 1f : 0f);
        if (onChange != null) onChange.accept(value);
    }
}
