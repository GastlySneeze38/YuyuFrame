package com.yuyuframe.launcheragent.apigraphic.value;

/**
 * LE fichier de design de l'interface en jeu : toutes les couleurs, les
 * surfaces de verre, les espacements, les rayons, les tailles de texte, les
 * vitesses d'animation et l'échelle. Rien de tout ça ne doit être écrit
 * ailleurs : un composant ou un écran qui a besoin d'une valeur la prend ici
 * (refonte du 2026-11-17).
 *
 * <h2>Couleurs</h2>
 * Une palette = un objet {@link Palette}. Il en existe deux, {@link Palette#DARK}
 * et {@link Palette#LIGHT} ; {@link #applyMode} recopie la palette choisie dans
 * les champs statiques ({@link #ACCENT}, {@link #TEXT_PRIMARY}...) que tout le
 * code lit au moment de dessiner. Ces champs restent modifiables : les
 * réglages ({@code GlobalUiSettings}) y écrivent l'accent choisi par le joueur,
 * et le changement se voit à l'image suivante sans rien reconstruire.
 *
 * <h2>Tailles</h2>
 * Toute longueur (espacement, rayon, taille de police) est une valeur de
 * référence, pensée pour une fenêtre de 1920x1080 à l'échelle « Normale », et
 * passe par {@link #scaled}/{@link #px} au moment de l'utiliser. L'échelle
 * ({@link #UI_SCALE}) combine le réglage « Taille de l'interface » et la taille
 * réelle de la fenêtre (voir {@link #setViewport}).
 */
public final class UiTheme {
    private UiTheme() {}

    // ═════════════════════════════════════════════════════════════════════
    //  Palettes
    // ═════════════════════════════════════════════════════════════════════

    public enum Mode { DARK, LIGHT }

    /**
     * Toutes les couleurs d'un thème. Une couleur nouvelle s'ajoute ICI (un
     * champ, une valeur dans chaque thème, une ligne dans {@link #apply}) —
     * jamais en dur dans un écran.
     */
    public static final class Palette {
        // Fonds
        public final UiColor overlayBg, sidebarBg, sidebarHover, sidebarActive;
        public final UiColor panelBg, panelBgAlt, cardBg, cardHover;
        // Accent et états
        public final UiColor accent, accentDim, danger, trackOff;
        // Textes
        public final UiColor textPrimary, textSecondary, textMuted, textShadow;
        /** Texte posé sur un aplat d'accent (bande de la grille d'icônes) : un blanc adouci, pas le blanc pur. */
        public final UiColor textOnAccent;
        // Verre
        public final UiColor glassTint, glassHairline, glassScrim, glassBorder, glassBorderHover;
        // Traits et voiles
        /** Séparateur fin entre deux lignes d'une liste. */
        public final UiColor divider;
        /** Piste éteinte d'un indicateur (trait entre deux paliers, anneau vide). */
        public final UiColor dividerStrong;
        /** Voile posé sur ce qui est inactif (tête d'un ami hors ligne). */
        public final UiColor dimmer;
        // Ombres portées
        public final UiColor shadowSoft, shadowCard, shadowPanel, shadowFloating;

        private Palette(UiColor overlayBg, UiColor sidebarBg, UiColor sidebarHover, UiColor sidebarActive,
                        UiColor panelBg, UiColor panelBgAlt, UiColor cardBg, UiColor cardHover,
                        UiColor accent, UiColor accentDim, UiColor danger, UiColor trackOff,
                        UiColor textPrimary, UiColor textSecondary, UiColor textMuted, UiColor textShadow, UiColor textOnAccent,
                        UiColor glassTint, UiColor glassHairline, UiColor glassScrim, UiColor glassBorder, UiColor glassBorderHover,
                        UiColor divider, UiColor dividerStrong, UiColor dimmer,
                        UiColor shadowSoft, UiColor shadowCard, UiColor shadowPanel, UiColor shadowFloating) {
            this.overlayBg = overlayBg; this.sidebarBg = sidebarBg; this.sidebarHover = sidebarHover; this.sidebarActive = sidebarActive;
            this.panelBg = panelBg; this.panelBgAlt = panelBgAlt; this.cardBg = cardBg; this.cardHover = cardHover;
            this.accent = accent; this.accentDim = accentDim; this.danger = danger; this.trackOff = trackOff;
            this.textPrimary = textPrimary; this.textSecondary = textSecondary; this.textMuted = textMuted;
            this.textShadow = textShadow; this.textOnAccent = textOnAccent;
            this.glassTint = glassTint; this.glassHairline = glassHairline; this.glassScrim = glassScrim;
            this.glassBorder = glassBorder; this.glassBorderHover = glassBorderHover;
            this.divider = divider; this.dividerStrong = dividerStrong; this.dimmer = dimmer;
            this.shadowSoft = shadowSoft; this.shadowCard = shadowCard; this.shadowPanel = shadowPanel; this.shadowFloating = shadowFloating;
        }

        /**
         * Thème sombre (palette assombrie du 2026-10-11 : « le blanc et le
         * clair sont beaucoup trop prononcés »). Le verre est dense, les
         * contours et reflets discrets.
         */
        public static final Palette DARK = new Palette(
            new UiColor(8, 8, 12, 235),        // overlayBg
            new UiColor(14, 14, 18, 255),      // sidebarBg
            new UiColor(255, 255, 255, 16),    // sidebarHover
            new UiColor(104, 90, 214, 34),     // sidebarActive
            new UiColor(22, 22, 28, 255),      // panelBg
            new UiColor(28, 28, 36, 255),      // panelBgAlt
            new UiColor(25, 25, 32, 255),      // cardBg
            new UiColor(33, 33, 42, 255),      // cardHover
            new UiColor(104, 90, 214, 255),    // accent
            new UiColor(104, 90, 214, 70),     // accentDim
            new UiColor(200, 82, 82, 255),     // danger
            new UiColor(44, 44, 53, 255),      // trackOff
            new UiColor(204, 204, 212, 255),   // textPrimary
            new UiColor(156, 156, 168, 255),   // textSecondary
            new UiColor(122, 122, 134, 255),   // textMuted
            new UiColor(0, 0, 0, 150),         // textShadow
            new UiColor(0.84f, 0.84f, 0.88f, 1f), // textOnAccent
            new UiColor(13, 13, 19, 255),      // glassTint
            new UiColor(255, 255, 255, 16),    // glassHairline
            new UiColor(6, 6, 10, 176),        // glassScrim
            new UiColor(255, 255, 255, 30),    // glassBorder
            new UiColor(255, 255, 255, 64),    // glassBorderHover
            new UiColor(255, 255, 255, 13),    // divider
            new UiColor(255, 255, 255, 22),    // dividerStrong
            new UiColor(10, 10, 14, 120),      // dimmer
            new UiColor(0, 0, 0, 70),          // shadowSoft
            new UiColor(0, 0, 0, 170),         // shadowCard
            new UiColor(0, 0, 0, 110),         // shadowPanel
            new UiColor(0, 0, 0, 150));        // shadowFloating

        /**
         * Thème clair : fond quasi blanc légèrement violet, même hiérarchie de
         * contraste que le sombre (pas une inversion mécanique). Les contours
         * du verre y sont SOMBRES, le blanc y serait invisible.
         */
        public static final Palette LIGHT = new Palette(
            new UiColor(250, 250, 252, 235),
            new UiColor(242, 241, 247, 255),
            new UiColor(20, 18, 30, 18),
            new UiColor(139, 124, 255, 40),
            new UiColor(255, 255, 255, 255),
            new UiColor(246, 245, 250, 255),
            new UiColor(255, 255, 255, 255),
            new UiColor(238, 236, 246, 255),
            new UiColor(112, 94, 240, 255),
            new UiColor(112, 94, 240, 55),
            new UiColor(205, 60, 60, 255),
            new UiColor(214, 212, 222, 255),
            new UiColor(24, 22, 32, 255),
            new UiColor(90, 87, 102, 255),
            new UiColor(140, 137, 150, 255),
            new UiColor(255, 255, 255, 150),
            new UiColor(0.98f, 0.98f, 1f, 1f),
            new UiColor(250, 250, 253, 255),
            new UiColor(255, 255, 255, 120),
            new UiColor(245, 245, 250, 130),
            new UiColor(20, 18, 30, 45),
            new UiColor(20, 18, 30, 95),
            new UiColor(20, 18, 30, 18),
            new UiColor(20, 18, 30, 30),
            new UiColor(255, 255, 255, 120),
            new UiColor(0, 0, 0, 40),
            new UiColor(0, 0, 0, 70),
            new UiColor(0, 0, 0, 50),
            new UiColor(0, 0, 0, 70));
    }

    private static Mode currentMode = Mode.DARK;
    private static Palette palette = Palette.DARK;

    public static Mode currentMode() { return currentMode; }

    /** La palette du thème courant (sans l'accent choisi par le joueur, qui vit dans {@link #ACCENT}). */
    public static Palette palette() { return palette; }

    // Champs lus par tout le code au moment de dessiner — remplis par apply().
    public static UiColor OVERLAY_BG, SIDEBAR_BG, SIDEBAR_HOVER, SIDEBAR_ACTIVE;
    public static UiColor PANEL_BG, PANEL_BG_ALT, CARD_BG, CARD_HOVER;
    public static UiColor ACCENT, ACCENT_DIM, DANGER, TRACK_OFF;
    public static UiColor TEXT_PRIMARY, TEXT_SECONDARY, TEXT_MUTED, TEXT_SHADOW, TEXT_ON_ACCENT;
    public static UiColor GLASS_TINT, GLASS_HAIRLINE, GLASS_SCRIM, GLASS_BORDER, GLASS_BORDER_HOVER;
    public static UiColor DIVIDER, DIVIDER_STRONG, DIMMER;
    public static UiColor SHADOW_SOFT, SHADOW_CARD, SHADOW_PANEL, SHADOW_FLOATING;

    /** « C'est fait » : le pendant de {@link #DANGER}. Le même dans les deux thèmes. */
    public static final UiColor SUCCESS = new UiColor(92, 196, 128, 255);

    /** Pastilles de présence, mêmes couleurs que le launcher ({@code STATUS_COLORS}). Les mêmes dans les deux thèmes. */
    public static final UiColor STATUS_ONLINE = new UiColor(59, 165, 93, 255);
    public static final UiColor STATUS_AWAY = new UiColor(250, 166, 26, 255);
    public static final UiColor STATUS_DND = new UiColor(237, 66, 69, 255);
    public static final UiColor STATUS_OFFLINE = new UiColor(128, 132, 142, 255);

    /**
     * Opacité des icônes des cartes : ce sont des images blanches, les rendre
     * un peu transparentes sur un fond sombre est ce qui les grise.
     */
    public static float ICON_ALPHA = 0.78f;

    static {
        apply(Palette.DARK);
    }

    /** Change de thème : toutes les couleurs d'un coup, visibles à l'image suivante. */
    public static void applyMode(Mode mode) {
        currentMode = mode;
        apply(mode == Mode.LIGHT ? Palette.LIGHT : Palette.DARK);
    }

    private static void apply(Palette p) {
        palette = p;
        OVERLAY_BG = p.overlayBg; SIDEBAR_BG = p.sidebarBg; SIDEBAR_HOVER = p.sidebarHover; SIDEBAR_ACTIVE = p.sidebarActive;
        PANEL_BG = p.panelBg; PANEL_BG_ALT = p.panelBgAlt; CARD_BG = p.cardBg; CARD_HOVER = p.cardHover;
        ACCENT = p.accent; ACCENT_DIM = p.accentDim; DANGER = p.danger; TRACK_OFF = p.trackOff;
        TEXT_PRIMARY = p.textPrimary; TEXT_SECONDARY = p.textSecondary; TEXT_MUTED = p.textMuted;
        TEXT_SHADOW = p.textShadow; TEXT_ON_ACCENT = p.textOnAccent;
        GLASS_TINT = p.glassTint; GLASS_HAIRLINE = p.glassHairline; GLASS_SCRIM = p.glassScrim;
        GLASS_BORDER = p.glassBorder; GLASS_BORDER_HOVER = p.glassBorderHover;
        DIVIDER = p.divider; DIVIDER_STRONG = p.dividerStrong; DIMMER = p.dimmer;
        SHADOW_SOFT = p.shadowSoft; SHADOW_CARD = p.shadowCard; SHADOW_PANEL = p.shadowPanel; SHADOW_FLOATING = p.shadowFloating;
    }

    /** Variante claire de l'accent COURANT (recalculée à chaque appel : l'accent change en direct). Pour les jeux de lumière, jamais pour un aplat. */
    public static UiColor accentLight() { return UiColor.lerp(ACCENT, new UiColor(255, 255, 255, 255), 0.16f); }
    public static UiColor accentDark() { return UiColor.lerp(ACCENT, new UiColor(0, 0, 0, 255), 0.35f); }

    /** Couleur de la pastille d'un état de présence ({@code online}, {@code away}, {@code dnd}, autre = hors ligne). */
    public static UiColor statusColor(String state) {
        if ("online".equals(state)) return STATUS_ONLINE;
        if ("away".equals(state)) return STATUS_AWAY;
        if ("dnd".equals(state)) return STATUS_DND;
        return STATUS_OFFLINE;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Verre dépoli
    // ═════════════════════════════════════════════════════════════════════
    //
    // Une surface de verre n'est pas un aplat translucide : le décor derrière
    // est flouté puis teinté par GLASS_TINT, à hauteur de la « force » de la
    // surface. Le résultat est opaque dans les bornes du panneau ; ce qui se
    // règle ici, c'est combien de décor flouté transparaît. Sans flou possible
    // (pas de Blaze3D), chaque surface retombe sur son aplat opaque.

    /** Les sortes de surfaces de verre. Dessinées par {@code UiSurface}. */
    public enum Glass {
        /** Barre latérale : la plus teintée, elle porte la navigation sur n'importe quel décor. */
        SIDEBAR(0.78f),
        /** Cartes : un peu plus transparentes, elles sont nombreuses. */
        CARD(0.72f),
        /** Champs, pilules, petits boutons : les plus légers. */
        FIELD(0.66f),
        /** Panneaux de section : texte dense sur de grandes surfaces. */
        PANEL(0.80f),
        /** Modales et tiroirs : coupent franchement avec ce qu'il y a derrière. */
        MODAL(0.88f);

        public float strength;

        Glass(float strength) { this.strength = strength; }

        /** L'aplat utilisé quand le verre n'est pas disponible. */
        public UiColor fallback() {
            switch (this) {
                case SIDEBAR: return SIDEBAR_BG;
                case FIELD:
                case CARD: return CARD_BG;
                default: return PANEL_BG;
            }
        }
    }

    // Raccourcis historiques des forces de verre (lus par les écrans pas encore refaits).
    public static float GLASS_STRENGTH_SIDEBAR = Glass.SIDEBAR.strength;
    public static float GLASS_STRENGTH_CARD = Glass.CARD.strength;
    public static float GLASS_STRENGTH_FIELD = Glass.FIELD.strength;
    public static float GLASS_STRENGTH_PANEL = Glass.PANEL.strength;
    public static float GLASS_STRENGTH_MODAL = Glass.MODAL.strength;

    /** Étages de flou du fond partagé de l'écran (voir {@code UiRenderer.beginGlassFrame}) : le 5e n'apporte rien de visible. */
    public static final int GLASS_PASSES = 4;

    // ═════════════════════════════════════════════════════════════════════
    //  Espacements, rayons, textes, mouvement
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Les espacements autorisés, en pixels de référence. Un écart qui n'est pas
     * dans cette liste est presque toujours une erreur d'œil : prendre le plus
     * proche. Toujours passés par {@link #px}.
     */
    public static final class Space {
        private Space() {}
        public static final float S1 = 1f, S2 = 2f, S3 = 3f, S4 = 4f, S5 = 5f, S6 = 6f, S8 = 8f, S10 = 10f,
            S12 = 12f, S14 = 14f, S16 = 16f, S18 = 18f, S20 = 20f, S24 = 24f, S28 = 28f, S32 = 32f,
            S36 = 36f, S40 = 40f, S48 = 48f, S64 = 64f;
    }

    /**
     * Trois rayons d'arrondi (plus XS pour les fins traits), en pixels non
     * mis à l'échelle comme depuis toujours. La forme des coins est ce qui
     * fait d'un ensemble UN ensemble : chaque surface prend l'un des trois.
     */
    public static final class Radius {
        private Radius() {}
        /** Traits fins, liserés, barres de progression. */
        public static final float XS = 1.5f;
        /** Petites surfaces : boutons, champs, pastilles, listes déroulantes fermées. */
        public static final float SM = 6f;
        /** Cartes, listes dépliées, bulles. */
        public static final float MD = 10f;
        /** Grands panneaux et modales. */
        public static final float LG = 16f;
    }

    public static final float RADIUS_SM = Radius.SM;
    /** Modifiable : réglage d'arrondi des cartes (voir GlobalUiSettings). */
    public static float RADIUS_MD = Radius.MD;
    public static final float RADIUS_LG = Radius.LG;

    /**
     * Tailles de texte (facteur d'échelle de la police, avant {@link #px}).
     *
     * <p>SIX niveaux seulement ({@code XS} à {@code XXL}) : des écarts de
     * 0,02 ne se lisent pas comme une hiérarchie, seulement comme des
     * différences. Les noms par rôle en dessous ne sont que des alias vers
     * ces six niveaux — un texte nouveau prend le rôle qui lui correspond, ou
     * directement un niveau.
     */
    public static final class Text {
        private Text() {}
        /** Capitales de section, nom dans une bande. */
        public static final float XS = 0.36f;
        /** Descriptions, messages d'état. */
        public static final float SM = 0.40f;
        /** Texte courant. */
        public static final float MD = 0.42f;
        /** Libellés : boutons, navigation, champs, noms, valeurs. */
        public static final float LG = 0.48f;
        /** Titres de bloc. */
        public static final float XL = 0.56f;
        /** Titre d'écran, logo. */
        public static final float XXL = 0.68f;

        public static final float CAPTION = XS;
        public static final float SMALL = SM;
        public static final float BODY = MD;
        public static final float BODY_STRONG = MD;
        public static final float BUTTON = LG;
        public static final float LABEL = LG;
        public static final float VALUE = LG;
        public static final float ITEM = LG;
        public static final float NAME = LG;
        public static final float HEADING = XL;
        public static final float CARD_TITLE = XL;
        public static final float INITIAL = XL;
        public static final float SUBTITLE = XL;
        public static final float TITLE = XXL;
    }

    /** Vitesses et durées d'animation — mêmes réglages partout pour que tout « réponde » pareil. */
    public static final class Motion {
        private Motion() {}
        /** Vitesse d'approche du survol (voir {@code UiAnimatedFloat}). */
        public static final float HOVER = 16f;
        /** Vitesse de l'enfoncement d'un bouton. */
        public static final float PRESS = 22f;
        /** Vitesse d'apparition/disparition d'une modale ou d'un panneau flottant. */
        public static final float OVERLAY = 14f;
        /** Éclat de clic (rebond). */
        public static final float CLICK_PULSE_S = 0.42f;
        /** Glissement d'une carte qui change de place. */
        public static final float MOVE_S = 0.38f;
        /** Entrée d'une carte. */
        public static final float ENTER_S = 0.5f;
        /** Délai de fermeture d'une bulle de survol, le temps d'y amener la souris. */
        public static final long BUBBLE_GRACE_MS = 160L;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Échelle
    // ═════════════════════════════════════════════════════════════════════

    /** Taille de référence pour laquelle toutes les longueurs de ce fichier sont pensées. */
    public static final float REF_WIDTH = 1920f, REF_HEIGHT = 1080f;
    /** Plus petit facteur dû à la fenêtre : en dessous, le texte devient illisible ; la mise en page s'adapte à la place. */
    private static final float VIEWPORT_MIN = 0.65f;
    /** Plus grand facteur dû à la fenêtre (4K et plus). */
    private static final float VIEWPORT_MAX = 1.6f;
    /**
     * Au-dessus de la référence, l'interface ne suit que la moitié de ce que
     * gagne la fenêtre : grossir en proportion exacte rendait la barre de
     * l'écran-titre « énorme » en plein écran (retour utilisateur du
     * 2026-10-10) ; la place gagnée va au contenu.
     */
    private static final float VIEWPORT_GROWTH = 0.5f;

    /** Réglage « Taille de l'interface » (Petite 1, Normale 1.18, Grande 1.4). */
    private static float userScale = 1.18f;
    /** Facteur dû à la taille de la fenêtre, 1 à 1920x1080. */
    private static float viewportFactor = 1f;

    /**
     * Échelle effective = réglage x fenêtre. Lue partout (et comparée par les
     * écrans pour savoir s'il faut refaire leur mise en page). À ne pas écrire
     * directement : voir {@link #setUserScale} et {@link #setViewport}.
     */
    public static float UI_SCALE = userScale;

    /** Le réglage « Taille de l'interface ». */
    public static void setUserScale(float scale) {
        userScale = scale;
        UI_SCALE = userScale * viewportFactor;
    }

    public static float userScale() { return userScale; }

    /**
     * La taille de la fenêtre, en pixels réels — appelé à chaque image par ce
     * qui dessine (écrans, barre de l'écran-titre). Plus petite que la
     * référence : tout rétrécit en proportion ; plus grande : tout grandit, à
     * moitié.
     */
    public static void setViewport(int width, int height) {
        if (width <= 0 || height <= 0) return;
        float ratio = Math.min(width / REF_WIDTH, height / REF_HEIGHT);
        float f = ratio <= 1f ? ratio : 1f + (ratio - 1f) * VIEWPORT_GROWTH;
        f = Math.max(VIEWPORT_MIN, Math.min(VIEWPORT_MAX, f));
        // Arrondi au centième : un redimensionnement d'un pixel ne doit pas
        // refaire la mise en page de tous les écrans.
        f = Math.round(f * 100f) / 100f;
        if (f != viewportFactor) {
            viewportFactor = f;
            UI_SCALE = userScale * viewportFactor;
        }
    }

    /** Une longueur de référence à l'échelle effective. */
    public static float scaled(float v) { return v * UI_SCALE; }

    /** Alias court de {@link #scaled}, pour les écrans qui en enchaînent beaucoup. */
    public static float px(float v) { return v * UI_SCALE; }

    /** Une longueur au seul réglage du joueur, sans le facteur de la fenêtre — pour qui gère la fenêtre lui-même (barre de l'écran-titre). */
    public static float userScaled(float v) { return v * userScale; }

    /** Épaisseur d'un trait fin : un pixel au moins, quelle que soit l'échelle. */
    public static float hairline() { return Math.max(1f, scaled(1f)); }
}
