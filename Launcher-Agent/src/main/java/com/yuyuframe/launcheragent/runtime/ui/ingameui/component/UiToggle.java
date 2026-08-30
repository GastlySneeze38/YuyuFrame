package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiWidget;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;

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

    /**
     * Skin "cœur" (demandé explicitement, référence visuelle style Lunar) —
     * remplace ENTIÈREMENT le rendu piste+bouton ci-dessous par un cœur
     * plein (activé) ou juste son contour (désactivé), voir {@link
     * #heartImage}. La logique de clic/état ({@link #onClick}, {@link
     * #value}) reste identique — seul le rendu change, ce widget reste le
     * même mécanisme cliquable partout. Utilisé par UiMainMenuScreen comme
     * marqueur FAVORI (voir {@link com.yuyuframe.launcheragent.runtime.ui.LauncherModule#favorite})
     * — précision utilisateur après une 1ère version où le cœur pilotait
     * l'activation du mod : "en fait la bande du dessous est cliquable pour
     * activer/désactiver le module et le cœur c'est un système de favori",
     * PAS la même chose. Voir aussi {@link #invisible} pour ce 2ᵉ toggle
     * (activation, désormais toute la bande — sans rendu propre).
     */
    private boolean heartStyle = false;

    /**
     * Toggle purement fonctionnel (clic + valeur), SANS rendu — utilisé
     * quand un widget EXTÉRIEUR dessine déjà l'apparence à sa place (voir
     * ModCard#drawIconGrid, qui colore lui-même toute la bande du bas selon
     * {@code pairedToggle.value()}). Sans ce flag, le rendu piste+bouton par
     * défaut se dessinerait PAR-DESSUS cette bande, incohérent avec elle.
     */
    private boolean invisible = false;

    public UiToggle(float x, float y, boolean initial, Consumer<Boolean> onChange) {
        this(x, y, UiTheme.scaled(44f), UiTheme.scaled(24f), initial, onChange);
    }

    /** Taille personnalisée (voir {@link #heartStyle}, où le cœur est carré et bien plus petit que la piste 44x24 par défaut). */
    public UiToggle(float x, float y, float w, float h, boolean initial, Consumer<Boolean> onChange) {
        super(x, y, w, h);
        this.value = initial;
        this.onChange = onChange;
        this.anim = new UiAnimatedFloat(initial ? 1f : 0f, ANIM_SPEED);
    }

    /**
     * Source de vérité EXTERNE, ou {@code null} si ce toggle se souvient
     * lui-même de son état.
     *
     * <p>BUG TROUVÉ (2026-08-30, retour utilisateur : "désynchronisation
     * d'état activé/désactivé entre la card du module et sa config dans le
     * groupe") : {@code value} était une COPIE, figée à la construction du
     * widget. Deux toggles portant le même module — celui de sa carte sur
     * l'accueil et celui de sa section dans l'écran de groupe — vivaient donc
     * chacun avec sa propre copie : basculer l'un laissait l'autre afficher
     * l'ancien état jusqu'à une reconstruction complète de l'écran.
     *
     * <p>Lié à une source, le widget n'a plus d'état du tout : il lit le
     * modèle à chaque frame. C'est aussi ce qui permet de basculer un module
     * depuis ailleurs (voir {@code UiMainMenuScreen}, carte d'un module sans
     * réglages) sans reconstruire la grille — donc sans le clignotement que
     * cette reconstruction provoquait.
     */
    private java.util.function.BooleanSupplier source;

    /** Lie ce toggle à l'état réel du modèle — voir {@link #source}. Setter fluent. */
    public UiToggle boundTo(java.util.function.BooleanSupplier source) {
        this.source = source;
        if (source != null) {
            this.value = source.getAsBoolean();
            this.anim.snapTo(this.value ? 1f : 0f);
        }
        return this;
    }

    /** Recale {@link #value}/l'animation sur la source, s'il y en a une — appelé au début de chaque {@code draw}. */
    private void syncFromSource() {
        if (source == null) return;
        boolean live = source.getAsBoolean();
        if (live != value) {
            value = live;
            anim.setTarget(live ? 1f : 0f);
        }
    }

    public boolean value() { return source != null ? source.getAsBoolean() : value; }

    public void setExternalAlpha(float alpha) { this.externalAlpha = alpha; }

    /** Setter fluent — voir {@link #invisible}. */
    public UiToggle invisibleStyle() { this.invisible = true; return this; }

    /** Voir {@link #useOwnClipFade} — appelé UNE FOIS par {@code ModCard#pairToggle}. */
    public void useExternalAlphaOnly() { this.useOwnClipFade = false; }

    /** Setter fluent — voir {@link #heartStyle}. */
    public UiToggle heartStyle() { this.heartStyle = true; return this; }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        // AVANT le repli "invisible" : un toggle invisible (bande de carte en
        // mode Grille) ne dessine rien lui-même, mais ModCard lit sa valeur
        // pour colorer la bande — il doit donc rester synchronisé lui aussi.
        syncFromSource();
        if (invisible) return;
        float drawAlpha = useOwnClipFade ? (clipFade * externalAlpha) : externalAlpha;
        if (heartStyle) {
            drawHeart(renderer, drawAlpha, vpWidth, vpHeight);
            return;
        }
        float t = anim.get();
        // Piste en dégradé (haut plus clair, bas = accent normal) plutôt
        // qu'une couleur plate à l'état ON — petit reflet "glossy" cohérent
        // avec les jeux de lumière du reste de l'appli (voir ModCard/
        // SidebarItem). Le OFF reste plat (TRACK_OFF des deux côtés) : le
        // dégradé n'apparaît qu'en se rapprochant de ON.
        UiColor top = UiColor.lerp(UiTheme.TRACK_OFF, UiTheme.accentLight(), t).multiplyAlpha(drawAlpha);
        UiColor bottom = UiColor.lerp(UiTheme.TRACK_OFF, UiTheme.ACCENT, t).multiplyAlpha(drawAlpha);
        renderer.drawGradientRect(x, y, x + w, y + h, h / 2f, bottom, top, vpWidth, vpHeight);

        float knobD = h - 4f;
        float knobXOff = 2f + t * (w - knobD - 4f); // 2f (position OFF) -> w-knobD-2f (position ON)
        renderer.drawRoundedRect(x + knobXOff, y + 2f, x + knobXOff + knobD, y + h - 2f, knobD / 2f, UiTheme.TEXT_PRIMARY.multiplyAlpha(drawAlpha), vpWidth, vpHeight);
    }

    private static final Map<String, BufferedImage> HEART_CACHE = new HashMap<>();

    /**
     * Cœur baké en texture CPU (comme {@code UiColorPicker.wheelImage} —
     * même motif éprouvé cette session : un shader custom pour une forme
     * vectorielle simple est fragile/invisible sur Blaze3D era E, une image
     * dessinée une fois via {@code java.awt.Graphics2D} puis uploadée comme
     * n'importe quelle icône via {@link UiRenderer#drawIcon} fonctionne sur
     * les 3 pipelines sans risque). Construit par union de 2 cercles (lobes
     * du haut) + un triangle (pointe du bas) via {@link Area} — ABANDONNÉ
     * (retour utilisateur, capture à l'appui : "les courbes du cœur ne sont
     * pas bonnes") : les 2 cercles ne se chevauchaient que très légèrement
     * (à peine tangents) et le triangle recoupait leurs arcs, donnant un
     * contour bosselé/anguleux au lieu d'un vrai cœur lisse, surtout visible
     * en simple contour (non rempli). Remplacé par le tracé du cœur
     * "favori" Material Design (4 courbes de Bézier cubiques, chemin SVG
     * standard {@code M12,21.35l-1.45-1.32C5.4,15.36,2,12.28,2,8.5C2,5.42,
     * 4.42,3,7.5,3c1.74,0,3.41,0.81,4.5,2.09C13.09,3.81,14.76,3,16.5,3C19.58,3,
     * 22,5.42,22,8.5c0,3.78-3.4,6.86-8.55,11.54L12,21.35z}, coordonnées
     * simplement divisées par 24 (viewBox d'origine) puis mises à l'échelle
     * de {@code px} ici) — silhouette éprouvée, aucune géométrie ad-hoc.
     */
    private static BufferedImage heartImage(int px, boolean filled) {
        String key = px + "_" + filled;
        BufferedImage cached = HEART_CACHE.get(key);
        if (cached != null) return cached;

        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Path2D.Float heart = new Path2D.Float();
        heart.moveTo(px * 0.5f, px * 0.889583f);
        heart.lineTo(px * 0.439583f, px * 0.834583f);
        heart.curveTo(px * 0.225f, px * 0.64f, px * 0.083333f, px * 0.511667f, px * 0.083333f, px * 0.354167f);
        heart.curveTo(px * 0.083333f, px * 0.225833f, px * 0.184167f, px * 0.125f, px * 0.3125f, px * 0.125f);
        heart.curveTo(px * 0.385f, px * 0.125f, px * 0.454583f, px * 0.15875f, px * 0.5f, px * 0.212083f);
        heart.curveTo(px * 0.545417f, px * 0.15875f, px * 0.615f, px * 0.125f, px * 0.6875f, px * 0.125f);
        heart.curveTo(px * 0.815833f, px * 0.125f, px * 0.916667f, px * 0.225833f, px * 0.916667f, px * 0.354167f);
        heart.curveTo(px * 0.916667f, px * 0.511667f, px * 0.775f, px * 0.64f, px * 0.560417f, px * 0.835f);
        heart.lineTo(px * 0.5f, px * 0.889583f);
        heart.closePath();

        g.setColor(java.awt.Color.WHITE);
        if (filled) {
            g.fill(heart);
        } else {
            g.setStroke(new BasicStroke(Math.max(1.5f, px * 0.09f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(heart);
        }
        g.dispose();

        HEART_CACHE.put(key, img);
        return img;
    }

    private void drawHeart(UiRenderer renderer, float drawAlpha, int vpWidth, int vpHeight) {
        int px = Math.max(8, Math.round(Math.max(w, h) * 4f));
        BufferedImage img = heartImage(px, value);
        float size = Math.min(w, h);
        float ix = x + (w - size) / 2f, iy = y + (h - size) / 2f;
        renderer.drawIcon("la$heart_" + px + "_" + value, img, ix, iy, size, size, drawAlpha, vpWidth, vpHeight);
    }

    @Override
    public void onClick() {
        // Part de la valeur RÉELLE (source liée si présente) — sinon un
        // changement venu d'ailleurs ferait basculer ce toggle dans le
        // mauvais sens au clic suivant.
        boolean next = !value();
        value = next;
        anim.setTarget(next ? 1f : 0f);
        if (onChange != null) onChange.accept(next);
    }
}
