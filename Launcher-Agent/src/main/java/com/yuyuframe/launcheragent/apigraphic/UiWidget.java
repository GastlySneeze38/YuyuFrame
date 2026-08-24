package com.yuyuframe.launcheragent.apigraphic;

/**
 * Widget dessiné/cliqué à la main — jamais un vrai ButtonWidget/ClickableWidget
 * vanilla (voir UiDrawable). Bounds en pixels framebuffer (même espace que
 * UiInputPoller.mouseX/Y et gl_FragCoord).
 */
public abstract class UiWidget {

    public float x, y, w, h;

    /** Texte affiché dans une bulle près du curseur au survol — null = pas de tooltip. Voir UiTooltip (ingameui.component). */
    public String tooltip;

    /**
     * Multiplicateur d'opacité (0..1) appliqué par un conteneur défilant
     * (voir {@code UiScrollContainer}) pour un widget qui chevauche un bord
     * du viewport — {@code 1f} par défaut (aucun effet, comportement
     * inchangé pour tout widget hors scroll). PAS un vrai clip pixel (aucune
     * primitive de scissor fiable sur le pipeline Blaze3D era E, voir
     * UiScrollContainer pour le détail) : une carte qui dépasse un bord
     * s'estompe PROGRESSIVEMENT au lieu d'apparaître/disparaître d'un coup
     * sec en franchissant la limite — suffisant pour éviter le chevauchement
     * visuel avec le contenu au-dessus/en-dessous du viewport, sans risquer
     * de casser le rendu de texte Blaze3D (historiquement très fragile, voir
     * UiTextBlaze3D) en y ajoutant un vrai scissor GPU. Un widget doit LIRE
     * ce champ lui-même et l'appliquer à ses propres couleurs (voir
     * ResultCard.draw() dans ModrinthContentScreen) — ignoré silencieusement
     * par tout widget qui ne le fait pas (juste le cut-off classique au bord
     * du viewport, comportement d'avant).
     */
    public float clipFade = 1f;

    public UiWidget(float x, float y, float w, float h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
    }

    /** Setter fluent — ex: {@code scroll.add(new UiLabel(...).tooltip("explication du réglage"))}. */
    public UiWidget tooltip(String text) {
        this.tooltip = text;
        return this;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /**
     * Dessine ce widget — mouseX/mouseY en pixels framebuffer, pour l'état hover.
     * vpWidth/vpHeight : dimensions totales du viewport, requises par
     * UiRenderer pour poser sa projection orthographique (voir
     * UiRenderer.drawRoundedRect) — PAS les dimensions de ce widget.
     */
    public abstract void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight);

    /** Appelé quand ce widget est cliqué (leftClicked, curseur dans ses bounds). */
    public void onClick() {}

    /**
     * Appelé CHAQUE frame, pour tout widget (contrairement à onClick, un seul
     * "premier widget sous le curseur" par frame) — nécessaire au drag continu
     * (UiSlider) : leftDown doit rester suivi même quand la souris sort des
     * bounds pendant le glissement, ce qu'un simple contains()+onClick() ne
     * permet pas. No-op par défaut (boutons/toggles n'en ont pas besoin).
     */
    public void pollContinuous(UiInputPoller input) {}

    /**
     * Contenu FLOTTANT qui doit toujours passer AU-DESSUS de tout le reste
     * de la liste (voir {@code UiScrollContainer#draw}, appelé dans un
     * second passage APRÈS tous les {@link #draw} — même motif que
     * {@code UiTooltip}, déjà dessiné en dernier pour la même raison) — BUG
     * TROUVÉ (retour utilisateur : "la modal entière... se fait chevaucher
     * par tout") : un panneau déroulant (color picker, dropdown...) qui
     * dessine son contenu flottant DANS {@link #draw} reste soumis à
     * l'ordre d'insertion de la liste — n'importe quel widget ajouté APRÈS
     * lui (donc plus bas dans l'écran) se dessine PAR-DESSUS s'il chevauche
     * spatialement, même si ce panneau doit visuellement flotter au-dessus
     * de tout. No-op par défaut — seuls les widgets avec un vrai popover
     * (voir UiColorPicker/UiDropdown) le surchargent.
     */
    public void drawOverlay(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {}
}
