package com.yuyuframe.launcheragent.runtime.ui.hud;

/**
 * Un élément HUD déplaçable — un mod déclare son élément ici (voir
 * HudRegistry.register) pour qu'il apparaisse à la fois dans l'éditeur
 * (UiHudEditorScreen, ouvert depuis la sidebar de l'accueil) ET en jeu
 * (HudOverlayRenderer) — même rendu dans les deux cas (voir HudPanelRenderer).
 *
 * Position stockée comme (ancre + décalage), PAS en coordonnées absolues —
 * voir HudAnchor. Le décalage lui-même ({@link #offsetX}/{@link #offsetY})
 * est stocké comme une FRACTION du viewport (0.05 = 5% de la largeur/hauteur
 * ACTUELLE), pas des pixels bruts : un décalage en pixels fixes ne "suit" pas
 * un changement de taille de fenêtre/résolution — un élément glissé vers le
 * centre de l'écran (donc à une grande distance en pixels de son ancre)
 * pouvait se retrouver hors écran si la fenêtre rétrécissait ensuite (bug
 * remonté par l'utilisateur : "la position ne s'adapte pas à la taille de
 * l'écran"). Les constructeurs prennent toujours des valeurs "en pixels" par
 * lisibilité (ex: {@code 8f, 8f} pour un coin) mais les convertissent en
 * fraction via {@link #REFERENCE_WIDTH}/{@link #REFERENCE_HEIGHT} — voir
 * {@link #screenX}/{@link #screenY} (fraction → pixels courants) et
 * {@link #setScreenPosition} (pixels glissés → fraction, sens inverse).
 *
 * {@code w}/{@code h} ne sont PLUS librement réglables indépendamment l'un de
 * l'autre — après inspection du fonctionnement réel d'OneConfig (constaté
 * bien meilleur : texte toujours bien placé, jamais "désolidarisé" de la
 * boîte), le principe retenu est le SIEN : une taille de base ({@link #naturalSize()},
 * calculée depuis le contenu réel) + un seul multiplicateur ({@link #scale}),
 * {@code w = naturalW * scale} et {@code h = naturalH * scale} TOUJOURS —
 * jamais étirées indépendamment. Voir {@link #setScale} (seul point d'entrée
 * pour changer la taille) et UiHudBox (poignée de redimensionnement
 * DIAGONALE UNIQUEMENT, qui ne fait que dériver un nouveau scale).
 *
 * Position gardée UNIQUEMENT en mémoire pour cette première passe — remise
 * aux valeurs par défaut à chaque relance de l'agent (pas encore de
 * sauvegarde disque, viendra avec le chantier persistance général).
 *
 * {@code locked}/{@code showWhenScreenOpen}/{@code paddingX}/{@code paddingY}/
 * {@code scale} — réglages génériques façon OneConfig, exposés
 * automatiquement dans la page de config d'un module qui possède un élément
 * HUD (voir ConfigScreenBuilder + runtime.ui.HudElementOwner) — PAS
 * repris : couleur de fond/bordure/coins personnalisés par élément (jugés
 * superflus, le panneau partagé HudPanelRenderer suffit) et le dropdown
 * "Position Alignment" d'OneConfig (redondant avec notre système d'ancre
 * HudAnchor déjà en place).
 */
public class HudElement {

    /** En dessous, le contenu devient illisible — plancher de {@link #scale}, voir setScale/UiHudBox. */
    public static final float MIN_SCALE = 0.5f;
    /** Au-dessus, la boîte devient déraisonnablement grande — plafond de {@link #scale}. */
    public static final float MAX_SCALE = 4f;

    /** Résolution de référence pour convertir les décalages "en pixels" des constructeurs en fraction du viewport — voir la javadoc de classe. Purement conventionnelle (1920x1080), aucun lien avec la résolution réelle du joueur. */
    private static final float REFERENCE_WIDTH = 1920f;
    private static final float REFERENCE_HEIGHT = 1080f;

    /** Fournit le contenu affiché (une ligne par entrée), recalculé à CHAQUE frame — voir runtime.module.FpsModule/PingModule/CoordsModule (leur ContentSource nichée) pour des exemples réels. */
    public interface ContentSource {
        String[] lines();
    }

    /**
     * Rendu personnalisé, pour un contenu qui ne tient pas dans un simple
     * empilement de lignes de texte (grille de touches, pastilles colorées
     * d'effets de potion...) — voir runtime.module.KeystrokesModule/
     * PotionEffectsModule (leur Renderer niché).
     * {@link HudPanelRenderer} dessine le panneau de fond PUIS calcule
     * lui-même la marge (padding de base + padding extra du module, voir
     * {@link HudElement#paddingX}/{@link HudElement#paddingY}) et ne délègue
     * que la zone déjà rétrécie à ce renderer — {@code x/y/w/h} reçus ici
     * sont donc DÉJÀ la zone de contenu utile, PAS la boîte totale — SAUF si
     * {@link #hasContent()} renvoie faux (rien dessiné du tout, ni fond ni
     * contenu) ou {@link #skipBackground()} renvoie vrai (fond sauté, ce
     * renderer gère alors LUI-MÊME sa propre position/apparence — voir
     * ArmorDurabilityModule, style "Vanilla").
     * {@link #naturalSize()} doit donc renvoyer une taille CONTENU SEUL, sans
     * ajouter sa propre marge (le moteur s'en charge, une seule fois, au même
     * endroit pour tous les modules — c'était auparavant dupliqué dans chaque
     * module, source d'incohérences). {@code scale} est le multiplicateur
     * générique de l'élément (voir {@link HudElement#scale}) — à appliquer
     * par le renderer à ses propres constantes de taille.
     */
    public interface CustomRenderer {
        void draw(com.yuyuframe.launcheragent.apigraphic.UiRenderer renderer,
                  float x, float y, float w, float h, float scale, int vpWidth, int vpHeight);

        /** Taille "naturelle" du CONTENU SEUL à scale=1 (sans marge — le moteur l'ajoute), {@code {largeur, hauteur}}. */
        float[] naturalSize();

        /**
         * Faux = rien à afficher CE frame (ex: aucune pièce d'armure
         * équipée) — {@link HudPanelRenderer} ne dessine ALORS ni fond ni
         * contenu, {@link #draw} n'est même pas appelé. Défaut vrai (aucun
         * changement de comportement pour les renderers existants qui ne
         * l'implémentent pas).
         */
        default boolean hasContent() { return true; }

        /**
         * Vrai = ce renderer gère ENTIÈREMENT son propre fond/position (voir
         * ArmorDurabilityModule, style "Vanilla" — case+sprite vanilla à un
         * endroit fixe, pas la carte HUD générique) — {@link HudPanelRenderer}
         * saute alors SON PROPRE panneau de fond mais appelle quand même
         * {@link #draw} normalement (avec le padding générique déjà
         * soustrait, comme d'habitude). Défaut faux.
         */
        default boolean skipBackground() { return false; }
    }

    public final String id;
    public final String displayName;
    /** DÉRIVÉS de naturalSize()*scale — jamais assignés indépendamment, voir setScale(). */
    public float w, h;
    public final ContentSource content;
    public final CustomRenderer customRenderer;
    /**
     * Couleur d'accent pour le contenu ContentSource — null = pas d'accent,
     * tout en UiTheme.TEXT_PRIMARY (comportement par défaut). Sans effet sur
     * un CustomRenderer, qui choisit déjà ses propres couleurs par ligne.
     * Si {@link #accentSuffix} est aussi défini ET qu'une ligne se termine par
     * ce suffixe littéral, SEUL le suffixe est peint dans cette couleur (le
     * reste — ex: la valeur numérique — reste TEXT_PRIMARY) ; sinon la ligne
     * entière est peinte dans cette couleur.
     */
    public com.yuyuframe.launcheragent.apigraphic.UiColor textColor;
    /** Suffixe littéral à isoler pour la coloration (ex: " FPS", " ms") — voir {@link #textColor}. */
    public String accentSuffix;

    public HudAnchor anchor;
    /** Fraction du viewport (voir javadoc de classe), PAS des pixels — ne jamais assigner de valeur "en pixels" directement ici, passer par {@link #setScreenPosition}. */
    public float offsetX, offsetY;

    // ── Réglages génériques façon OneConfig ─────────────────────────────────
    /** Empêche le glisser/redimensionner dans l'éditeur (voir UiHudBox). */
    public boolean locked = false;
    /** Reste visible même quand un écran NON custom (chat, inventaire, tout autre GUI vanilla/mod) est ouvert — voir HudOverlayRenderer.renderPersistent. */
    public boolean showWhenScreenOpen = false;
    public float paddingX = 0f, paddingY = 0f;
    /** Multiplicateur de taille — SEULE façon de changer w/h, voir setScale(). Ne jamais assigner directement (utiliser setScale, qui recalcule w/h en même temps). */
    public float scale = 1f;

    private final HudAnchor defaultAnchor;
    private final float defaultOffsetX, defaultOffsetY;

    /**
     * {@code offsetX}/{@code offsetY} ici sont des pixels "à la résolution de
     * référence" ({@link #REFERENCE_WIDTH}/{@link #REFERENCE_HEIGHT}) — juste
     * pour rester lisible dans le code des modules (ex: {@code 8f, 8f} pour
     * un coin) — convertis immédiatement en fraction, la SEULE forme stockée
     * (voir javadoc de classe).
     */
    public HudElement(String id, String displayName, HudAnchor anchor, float offsetX, float offsetY, ContentSource content) {
        this.id = id;
        this.displayName = displayName;
        this.anchor = anchor;
        this.content = content;
        this.customRenderer = null;
        recomputeSize(); // besoin de w/h AVANT la conversion bord->centre ci-dessous
        this.offsetX = edgeOffsetToCenterOffsetX(offsetX, anchor, w) / REFERENCE_WIDTH;
        this.offsetY = edgeOffsetToCenterOffsetY(offsetY, h) / REFERENCE_HEIGHT;
        this.defaultAnchor = anchor;
        this.defaultOffsetX = this.offsetX;
        this.defaultOffsetY = this.offsetY;
    }

    /** Variante rendu personnalisé — voir {@link CustomRenderer}. Mêmes unités "pixels de référence" que l'autre constructeur. */
    public HudElement(String id, String displayName, HudAnchor anchor, float offsetX, float offsetY, CustomRenderer customRenderer) {
        this.id = id;
        this.displayName = displayName;
        this.anchor = anchor;
        this.content = null;
        this.customRenderer = customRenderer;
        recomputeSize(); // besoin de w/h AVANT la conversion bord->centre ci-dessous
        this.offsetX = edgeOffsetToCenterOffsetX(offsetX, anchor, w) / REFERENCE_WIDTH;
        this.offsetY = edgeOffsetToCenterOffsetY(offsetY, h) / REFERENCE_HEIGHT;
        this.defaultAnchor = anchor;
        this.defaultOffsetX = this.offsetX;
        this.defaultOffsetY = this.offsetY;
    }

    /**
     * BUG TROUVÉ (retour utilisateur : "l'ancrage du HUD n'est pas bon...
     * si les FPS passent de 150 à 95 le HUD est plus petit et là il bouge —
     * si il est fixé par son centre il ne bougera pas") — {@link #offsetX}/
     * {@link #offsetY} mesuraient jusqu'ici la distance du coin d'écran au
     * BORD de la boîte (voir {@link #screenX}/{@link #screenY} avant ce
     * correctif) : un contenu de largeur variable (FPS "150"→"95") gardait
     * un bord fixe, donc l'autre bord — et le CENTRE visuel du HUD — se
     * déplaçait à chaque frame où le texte changeait de largeur/hauteur.
     * {@link #offsetX}/{@link #offsetY} mesurent désormais la distance du
     * coin d'écran au CENTRE de la boîte (voir screenX/screenY), invariant à
     * un changement de taille du contenu. Cette conversion (appliquée UNE
     * SEULE FOIS, ici, à la taille naturelle de départ) garde la position
     * VISUELLE de chaque module inchangée par rapport à avant ce correctif
     * — seul le comportement FUTUR (au changement de taille) change.
     */
    private static float edgeOffsetToCenterOffsetX(float edgeOffsetPx, HudAnchor anchor, float w) {
        if (anchor == HudAnchor.TOP_CENTER || anchor == HudAnchor.BOTTOM_CENTER) return edgeOffsetPx;
        return edgeOffsetPx + w / 2f;
    }

    /** Voir {@link #edgeOffsetToCenterOffsetX} — pas de variante Y "centrée" (aucune ancre TOP/BOTTOM médiane sur cet axe), la conversion s'applique donc à TOUTES les ancres. */
    private static float edgeOffsetToCenterOffsetY(float edgeOffsetPx, float h) {
        return edgeOffsetPx + h / 2f;
    }

    /**
     * SEUL point d'entrée pour changer la taille (voir UiHudBox, poignée
     * diagonale) — recalcule TOUJOURS w/h ensemble depuis naturalSize()*scale,
     * jamais l'un sans l'autre : c'est précisément ce qui manquait avant
     * (largeur/hauteur réglables indépendamment) et qui "désolidarisait" le
     * texte de sa boîte selon la façon dont elle avait été étirée.
     */
    public void setScale(float scale) {
        this.scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        recomputeSize();
    }

    /**
     * Recalcule w/h depuis naturalSize()*scale sans changer scale — à appeler
     * CHAQUE FRAME avant lecture de w/h (voir HudOverlayRenderer/UiHudBox) :
     * un contenu texte de largeur variable (FPS/Ping, "9 FPS" vs "144 FPS")
     * n'était mesuré QU'À LA CONSTRUCTION de l'élément (avant même que
     * MinecraftClient existe, donc sur un texte de repli), jamais remesuré
     * ensuite — la boîte restait figée sur cette largeur de repli alors que
     * le texte réel affiché changeait de largeur en jeu ("le calcul de la
     * taille était pas bon" : gros espace vide ou texte débordant selon le
     * texte de repli utilisé au démarrage).
     */
    public void refreshSize() {
        recomputeSize();
    }

    private void recomputeSize() {
        float[] size = naturalSize();
        this.w = size[0] * scale;
        this.h = size[1] * scale;
    }

    /**
     * Taille "naturelle" (contenu + marge) à scale=1, {@code {largeur, hauteur}}
     * — base de TOUT calcul de taille (voir recomputeSize()). La marge est
     * calculée UNE SEULE FOIS ici, jamais par les modules eux-mêmes : marge de
     * base ({@link HudPanelRenderer#PADDING}, la même pour tout le monde) +
     * marge extra optionnelle du module ({@link #paddingX}/{@link #paddingY},
     * 0 par défaut, réglable depuis la config — voir ConfigScreenBuilder).
     * Avant ce correctif chaque module (Keystrokes, Coords, ArmorDurability...)
     * réimplémentait sa propre constante PADDING en plus de celle-ci, et le
     * padding "extra" n'était ni pris en compte dans la taille de la boîte ni
     * mis à l'échelle avec {@link #scale} — d'où une marge tantôt trop grande
     * tantôt trop petite selon le module/l'échelle.
     */
    public float[] naturalSize() {
        float padX = HudPanelRenderer.PADDING + paddingX;
        float padY = HudPanelRenderer.PADDING + paddingY;
        if (customRenderer != null) {
            float[] contentSize;
            try {
                contentSize = customRenderer.naturalSize();
            } catch (Throwable t) {
                contentSize = new float[]{ 80f, 24f };
            }
            return new float[]{ contentSize[0] + 2 * padX, contentSize[1] + 2 * padY };
        }
        String[] lines;
        try {
            lines = content.lines();
        } catch (Throwable t) {
            lines = new String[]{ "--" };
        }
        float contentW = 0f;
        for (String line : lines) {
            contentW = Math.max(contentW, com.yuyuframe.launcheragent.apigraphic.UiFont.REGULAR.textWidth(line, HudPanelRenderer.TEXT_SCALE));
        }
        float naturalH = lines.length * HudPanelRenderer.LINE_H + 2 * padY;
        float naturalW = contentW + 2 * padX;
        return new float[]{ naturalW, naturalH };
    }

    /**
     * Coin bas-gauche de la boîte (espace pixels framebuffer, comme UiWidget)
     * pour un viewport donné — reconvertit offsetX (fraction) en pixels du
     * viewport COURANT à chaque appel, voir javadoc de classe.
     *
     * offsetX est la distance du coin d'écran au CENTRE de la boîte (voir
     * BUG TROUVÉ dans la javadoc des constructeurs) — le centre ({@code
     * centerX}) ne dépend donc JAMAIS de {@code w}, seul le bord retourné
     * ici ({@code centerX - w/2}) en dépend : un changement de largeur du
     * contenu fait grandir/rétrécir la boîte symétriquement autour de ce
     * centre fixe, au lieu de déplacer tout le HUD.
     *
     * Clampé au final dans {@code [0, vpWidth - w]} : la LARGEUR de la boîte
     * reste fixe en pixels (voir naturalSize()) alors que sa POSITION est
     * proportionnelle — un élément glissé loin de son ancre (ex: FPS ancré
     * TOP_LEFT mais glissé côté droit, offsetX proche de 1.0) voit sa position
     * proportionnelle se resserrer en rétrécissant la fenêtre SANS que la
     * largeur fixe de la boîte ne suive, ce qui peut pousser son bord au-delà
     * de l'écran ("des HUD sont en dehors de la fenêtre" en redimensionnant).
     * Ce clamp final est un filet de sécurité générique, pareil que celui déjà
     * appliqué pendant le glisser-déposer dans l'éditeur (voir UiHudBox) —
     * étendu ici à TOUS les chemins de rendu (jeu ET éditeur), pas seulement
     * pendant un drag actif.
     */
    public float screenX(int vpWidth) {
        float centerOffsetXPx = offsetX * vpWidth;
        float centerX;
        switch (anchor) {
            case TOP_RIGHT:
            case BOTTOM_RIGHT:
                centerX = vpWidth - centerOffsetXPx;
                break;
            case TOP_CENTER:
            case BOTTOM_CENTER:
                centerX = vpWidth / 2f + centerOffsetXPx;
                break;
            default: // TOP_LEFT, BOTTOM_LEFT
                centerX = centerOffsetXPx;
        }
        float x = centerX - w / 2f;
        return Math.max(0f, Math.min(vpWidth - w, x));
    }

    /** Même principe centre-invariant + même clamp final que {@link #screenX} — voir sa javadoc. */
    public float screenY(int vpHeight) {
        float centerOffsetYPx = offsetY * vpHeight;
        float centerY;
        switch (anchor) {
            case TOP_LEFT:
            case TOP_CENTER:
            case TOP_RIGHT:
                centerY = vpHeight - centerOffsetYPx;
                break;
            default: // BOTTOM_*
                centerY = centerOffsetYPx;
        }
        float y = centerY - h / 2f;
        return Math.max(0f, Math.min(vpHeight - h, y));
    }

    /**
     * Recalcule offsetX/offsetY (fraction, distance au CENTRE de la boîte —
     * voir javadoc de {@link #screenX}) à partir d'une position absolue
     * glissée en pixels ({@code absX,absY} = coin bas-gauche de la boîte
     * pendant le drag, voir UiHudBox — recomposé ici en centre via
     * {@code +w/2}/{@code +h/2} avant conversion selon l'ancre actuelle).
     */
    public void setScreenPosition(float absX, float absY, int vpWidth, int vpHeight) {
        float centerX = absX + w / 2f;
        float centerOffsetXPx;
        switch (anchor) {
            case TOP_RIGHT:
            case BOTTOM_RIGHT:
                centerOffsetXPx = vpWidth - centerX;
                break;
            case TOP_CENTER:
            case BOTTOM_CENTER:
                centerOffsetXPx = centerX - vpWidth / 2f;
                break;
            default:
                centerOffsetXPx = centerX;
        }
        offsetX = centerOffsetXPx / vpWidth;

        float centerY = absY + h / 2f;
        float centerOffsetYPx;
        switch (anchor) {
            case TOP_LEFT:
            case TOP_CENTER:
            case TOP_RIGHT:
                centerOffsetYPx = vpHeight - centerY;
                break;
            default:
                centerOffsetYPx = centerY;
        }
        offsetY = centerOffsetYPx / vpHeight;
    }

    /** Bouton "Réinitialiser la position" (voir ConfigScreenBuilder) — remet ancre+décalage tels que déclarés à la construction, PAS la taille/l'échelle (volontairement laissées telles quelles). */
    public void resetPosition() {
        this.anchor = defaultAnchor;
        this.offsetX = defaultOffsetX;
        this.offsetY = defaultOffsetY;
    }
}
