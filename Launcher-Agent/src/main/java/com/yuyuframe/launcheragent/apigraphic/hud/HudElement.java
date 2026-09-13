package com.yuyuframe.launcheragent.apigraphic.hud;

/**
 * Un élément HUD déplaçable — un mod déclare son élément ici (voir
 * HudRegistry.register) pour qu'il apparaisse à la fois dans l'éditeur
 * (UiHudEditorScreen, ouvert depuis la sidebar de l'accueil) ET en jeu
 * (HudOverlayRenderer) — même rendu dans les deux cas (voir HudPanelRenderer).
 *
 * Position stockée comme (ancre + écart en pixels au bord d'ancrage), PAS en
 * coordonnées absolues — voir {@link #marginX} (refonte du 2026-09-13, qui
 * remplace l'ancienne fraction d'écran mesurée au centre) et
 * {@link #setScreenPositionAutoAnchor}, qui choisit l'ancre au dépôt.
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
    public com.yuyuframe.launcheragent.apigraphic.value.UiColor textColor;

    /**
     * Opacité PROPRE à cet élément, multipliée par le réglage global
     * "Opacité du HUD" (voir {@code HudPanelRenderer.PANEL_BG}).
     *
     * <p>Le réglage global seul ne suffisait pas : un module discret
     * (coordonnées) et un module important (vie basse) étaient forcés de
     * partager la même opacité, alors que c'est précisément la hiérarchie
     * entre eux qu'on veut pouvoir régler. Multiplicatif et non absolu, pour
     * que le réglage global garde son rôle de "gradateur" d'ensemble.
     */
    public float opacity = 1f;
    /** Suffixe littéral à isoler pour la coloration (ex: " FPS", " ms") — voir {@link #textColor}. */
    public String accentSuffix;

    public HudAnchor anchor;

    /**
     * Écart au bord d'ancrage, en pixels framebuffer — REFONTE du 2026-09-13,
     * voir {@link #screenX}. Horizontal : du bord gauche de l'écran au bord
     * gauche de la boîte ({@code *_LEFT}), du bord droit au bord droit
     * ({@code *_RIGHT}), ou du centre de l'écran au centre de la boîte
     * ({@code *_CENTER}, signé). Vertical : du haut au haut ({@code TOP_*}) ou
     * du bas au bas ({@code BOTTOM_*}).
     *
     * <p>Remplace l'ancien {@code offsetX}/{@code offsetY}, une FRACTION de
     * l'écran mesurée au CENTRE de la boîte. Retour utilisateur : un élément
     * calé contre un bord ou contre un voisin se décalait dès que son contenu
     * changeait de largeur (FPS 99 → 144) ou que la fenêtre changeait de
     * taille (l'écart suivait la proportion de l'écran). Un écart en pixels,
     * mesuré depuis le bord que l'élément touche, garde l'alignement dans les
     * deux cas ; l'ancre est choisie automatiquement au dépôt
     * ({@link #setScreenPositionAutoAnchor}), ce qui couvre aussi l'ancien
     * souci « élément glissé au milieu, sorti de l'écran en rétrécissant ».
     */
    public float marginX, marginY;

    /**
     * Position lue dans un fichier ANTÉRIEUR (fraction d'écran, au centre),
     * pas encore convertie : la conversion demande la taille d'écran, connue
     * seulement au premier {@link #screenX}/{@link #screenY}. Voir
     * {@link #setLegacyFractionOffsets}.
     */
    private boolean legacyX, legacyY;
    private float legacyOffsetX, legacyOffsetY;

    // ── Réglages génériques façon OneConfig ─────────────────────────────────
    /** Empêche le glisser/redimensionner dans l'éditeur (voir UiHudBox). */
    public boolean locked = false;
    /** Reste visible même quand un écran NON custom (chat, inventaire, tout autre GUI vanilla/mod) est ouvert — voir HudOverlayRenderer.renderPersistent. */
    public boolean showWhenScreenOpen = false;
    public float paddingX = 0f, paddingY = 0f;
    /** Multiplicateur de taille — SEULE façon de changer w/h, voir setScale(). Ne jamais assigner directement (utiliser setScale, qui recalcule w/h en même temps). */
    public float scale = 1f;

    private final HudAnchor defaultAnchor;
    private final float defaultMarginX, defaultMarginY;

    /**
     * {@code offsetX}/{@code offsetY} : écart en pixels au bord d'ancrage
     * (voir {@link #marginX}) — pour {@code *_CENTER}, décalage du centre.
     * C'était déjà la convention des modules ({@code 8f, 8f} pour un coin) :
     * les valeurs déclarées gardent leur sens, elles ne sont simplement plus
     * converties en fraction d'écran.
     */
    public HudElement(String id, String displayName, HudAnchor anchor, float offsetX, float offsetY, ContentSource content) {
        this.id = id;
        this.displayName = displayName;
        this.anchor = anchor;
        this.content = content;
        this.customRenderer = null;
        recomputeSize();
        this.marginX = offsetX;
        this.marginY = offsetY;
        this.defaultAnchor = anchor;
        this.defaultMarginX = offsetX;
        this.defaultMarginY = offsetY;
    }

    /** Variante rendu personnalisé — voir {@link CustomRenderer}. Mêmes unités que l'autre constructeur. */
    public HudElement(String id, String displayName, HudAnchor anchor, float offsetX, float offsetY, CustomRenderer customRenderer) {
        this.id = id;
        this.displayName = displayName;
        this.anchor = anchor;
        this.content = null;
        this.customRenderer = customRenderer;
        recomputeSize();
        this.marginX = offsetX;
        this.marginY = offsetY;
        this.defaultAnchor = anchor;
        this.defaultMarginX = offsetX;
        this.defaultMarginY = offsetY;
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
     * Lignes de contenu de la frame COURANTE.
     *
     * <p>AUDIT PERF : {@code content.lines()} était appelé DEUX FOIS par frame
     * et par élément — une fois par {@link #naturalSize()} (pour mesurer la
     * boîte) et une fois par {@code HudPanelRenderer.draw} (pour dessiner).
     * Chaque appel alloue un {@code String[]} ET fait de la concaténation dans
     * le module (ex: {@code fps + " FPS"}), donc tout était payé deux fois.
     * Invalidé par {@link #refreshSize()}, appelé une fois par frame juste
     * avant le dessin.
     */
    private String[] cachedLines;

    public String[] contentLines() {
        if (cachedLines == null) {
            try {
                cachedLines = content != null ? content.lines() : new String[]{ "--" };
            } catch (Throwable t) {
                cachedLines = new String[]{ "--" };
            }
        }
        return cachedLines;
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
        cachedLines = null; // nouvelle frame : le contenu a pu changer
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
            lines = contentLines();
        } catch (Throwable t) {
            lines = new String[]{ "--" };
        }
        float contentW = 0f;
        for (String line : lines) {
            contentW = Math.max(contentW, com.yuyuframe.launcheragent.apigraphic.value.UiFont.REGULAR.textWidth(line, HudPanelRenderer.TEXT_SCALE));
        }
        float naturalH = lines.length * HudPanelRenderer.LINE_H + 2 * padY;
        float naturalW = contentW + 2 * padX;
        return new float[]{ naturalW, naturalH };
    }

    private static boolean isRight(HudAnchor a) { return a == HudAnchor.TOP_RIGHT || a == HudAnchor.BOTTOM_RIGHT; }
    private static boolean isCenter(HudAnchor a) { return a == HudAnchor.TOP_CENTER || a == HudAnchor.BOTTOM_CENTER; }
    private static boolean isTop(HudAnchor a) { return a == HudAnchor.TOP_LEFT || a == HudAnchor.TOP_CENTER || a == HudAnchor.TOP_RIGHT; }

    /**
     * Coin bas-gauche de la boîte (pixels framebuffer, comme UiWidget).
     *
     * <p>Le bord d'ancrage reste fixe quand le contenu change de largeur : un
     * élément calé à droite grandit vers la gauche, un élément centré grandit
     * des deux côtés. Voir {@link #marginX} pour le pourquoi.
     *
     * <p>Clampé dans {@code [0, vpWidth − w]} : filet de sécurité si la fenêtre
     * devient plus petite que l'écart enregistré.
     */
    public float screenX(int vpWidth) {
        if (legacyX) convertLegacyX(vpWidth);
        float x;
        if (isRight(anchor)) x = vpWidth - w - marginX;
        else if (isCenter(anchor)) x = vpWidth / 2f + marginX - w / 2f;
        else x = marginX;
        return Math.max(0f, Math.min(vpWidth - w, x));
    }

    /** Même principe que {@link #screenX} — repère Y vers le HAUT, {@code TOP_*} mesure depuis le haut. */
    public float screenY(int vpHeight) {
        if (legacyY) convertLegacyY(vpHeight);
        float y = isTop(anchor) ? vpHeight - h - marginY : marginY;
        return Math.max(0f, Math.min(vpHeight - h, y));
    }

    /** Recalcule l'écart au bord d'ancrage ACTUEL depuis une position absolue (coin bas-gauche). */
    public void setScreenPosition(float absX, float absY, int vpWidth, int vpHeight) {
        legacyX = false;
        legacyY = false;
        if (isRight(anchor)) marginX = vpWidth - (absX + w);
        else if (isCenter(anchor)) marginX = (absX + w / 2f) - vpWidth / 2f;
        else marginX = absX;
        marginY = isTop(anchor) ? vpHeight - (absY + h) : absY;
    }

    /**
     * Comme {@link #setScreenPosition}, en choisissant l'ancre d'après la
     * position : tiers gauche/centre/droit de l'écran selon le centre de la
     * boîte, moitié haute/basse. Appelé au DÉPÔT dans l'éditeur (pas pendant
     * le glisser, où l'ancre changerait sous la souris).
     *
     * <p>Un élément calé contre le bord droit a son centre dans le tiers droit,
     * il prend donc l'ancre droite et y reste collé quelle que soit la taille
     * de fenêtre — c'est l'objet même de cette méthode.
     */
    public void setScreenPositionAutoAnchor(float absX, float absY, int vpWidth, int vpHeight) {
        float cx = absX + w / 2f, cy = absY + h / 2f;
        boolean top = cy >= vpHeight / 2f;
        if (cx < vpWidth / 3f) anchor = top ? HudAnchor.TOP_LEFT : HudAnchor.BOTTOM_LEFT;
        else if (cx > vpWidth * 2f / 3f) anchor = top ? HudAnchor.TOP_RIGHT : HudAnchor.BOTTOM_RIGHT;
        else anchor = top ? HudAnchor.TOP_CENTER : HudAnchor.BOTTOM_CENTER;
        setScreenPosition(absX, absY, vpWidth, vpHeight);
    }

    /**
     * Position d'un fichier de configuration ANTÉRIEUR : fraction de l'écran,
     * mesurée au CENTRE de la boîte. Convertie paresseusement au premier
     * rendu, à la position VISUELLE qu'elle avait, puis sauvegardée au nouveau
     * format à la prochaine écriture. Personne ne perd sa disposition.
     */
    public void setLegacyFractionOffsets(float fractionX, float fractionY) {
        legacyOffsetX = fractionX;
        legacyOffsetY = fractionY;
        legacyX = true;
        legacyY = true;
    }

    /**
     * Ancienne formule de centre, reprise telle quelle pour retrouver la
     * position visuelle — puis ANCRE RECHOISIE comme au dépôt. Sans ça, un
     * élément glissé à droite sous l'ancien format garderait son ancre gauche
     * d'origine : désormais mesuré en pixels depuis la gauche, il sortirait de
     * l'écran en rétrécissant la fenêtre. Un axe par méthode : chacune ne
     * touche qu'à SA moitié de l'ancre, l'ordre des deux conversions est donc
     * indifférent.
     */
    private void convertLegacyX(int vpWidth) {
        legacyX = false;
        float off = legacyOffsetX * vpWidth;
        float centerX;
        if (isRight(anchor)) centerX = vpWidth - off;
        else if (isCenter(anchor)) centerX = vpWidth / 2f + off;
        else centerX = off;
        boolean top = isTop(anchor);
        if (centerX < vpWidth / 3f) anchor = top ? HudAnchor.TOP_LEFT : HudAnchor.BOTTOM_LEFT;
        else if (centerX > vpWidth * 2f / 3f) anchor = top ? HudAnchor.TOP_RIGHT : HudAnchor.BOTTOM_RIGHT;
        else anchor = top ? HudAnchor.TOP_CENTER : HudAnchor.BOTTOM_CENTER;
        float absX = centerX - w / 2f;
        if (isRight(anchor)) marginX = vpWidth - (absX + w);
        else if (isCenter(anchor)) marginX = centerX - vpWidth / 2f;
        else marginX = absX;
    }

    private void convertLegacyY(int vpHeight) {
        legacyY = false;
        float off = legacyOffsetY * vpHeight;
        float centerY = isTop(anchor) ? vpHeight - off : off;
        boolean top = centerY >= vpHeight / 2f;
        if (isRight(anchor)) anchor = top ? HudAnchor.TOP_RIGHT : HudAnchor.BOTTOM_RIGHT;
        else if (isCenter(anchor)) anchor = top ? HudAnchor.TOP_CENTER : HudAnchor.BOTTOM_CENTER;
        else anchor = top ? HudAnchor.TOP_LEFT : HudAnchor.BOTTOM_LEFT;
        float absY = centerY - h / 2f;
        marginY = top ? vpHeight - (absY + h) : absY;
    }

    /**
     * Défauts capturés PARESSEUSEMENT au premier {@link #captureDefaults()} —
     * pas dans le constructeur : {@link #textColor}/{@link #paddingX} sont
     * posés par le module APRÈS l'appel à {@code super(...)} (voir FpsModule,
     * qui écrit {@code hudElement().textColor} depuis son propre
     * constructeur), donc les lire dans le constructeur de HudElement
     * capturerait {@code null}/0 au lieu de la vraie valeur voulue.
     */
    private boolean defaultsCaptured;
    private float defaultScale = 1f, defaultPaddingX, defaultPaddingY, defaultOpacity = 1f;
    private com.yuyuframe.launcheragent.apigraphic.value.UiColor defaultTextColor;

    /** À appeler une fois que le module a fini de personnaliser cet élément — voir {@code ModuleRegistry.register}. Sans effet aux appels suivants. */
    public void captureDefaults() {
        if (defaultsCaptured) return;
        defaultsCaptured = true;
        defaultScale = scale;
        defaultPaddingX = paddingX;
        defaultPaddingY = paddingY;
        defaultOpacity = opacity;
        defaultTextColor = textColor;
    }

    /** Bouton "Réinitialiser la position" (voir ConfigScreenBuilder) — remet ancre+écart tels que déclarés à la construction, PAS la taille/l'échelle (volontairement laissées telles quelles). */
    public void resetPosition() {
        this.anchor = defaultAnchor;
        this.marginX = defaultMarginX;
        this.marginY = defaultMarginY;
        this.legacyX = false;
        this.legacyY = false;
    }

    /** {@code true} tant qu'une position d'ancien format attend sa conversion — voir {@link #setLegacyFractionOffsets}. */
    public boolean hasPendingLegacyPosition() {
        return legacyX || legacyY;
    }

    /** Fractions d'ancien format encore non converties — pour les réécrire telles quelles si la sauvegarde passe avant tout rendu. */
    public float[] legacyFractionOffsets() {
        return new float[]{ legacyOffsetX, legacyOffsetY };
    }

    /**
     * Remet TOUS les réglages de cet élément à leur valeur d'origine —
     * position, mais aussi taille, marges, opacité et couleur de texte.
     *
     * <p>{@link #resetPosition()} ne rendait que la position : après avoir
     * bidouillé l'échelle ou les marges, il fallait les remettre une par une à
     * la main, en devinant les valeurs d'origine (jamais affichées nulle
     * part). Les défauts sont capturés à la construction, donc y compris ceux
     * qu'un module a posés lui-même (couleur d'accent de FPS/Ping...).
     */
    public void resetAll() {
        resetPosition();
        setScale(defaultScale);
        this.paddingX = defaultPaddingX;
        this.paddingY = defaultPaddingY;
        this.opacity = defaultOpacity;
        this.textColor = defaultTextColor;
    }
}
