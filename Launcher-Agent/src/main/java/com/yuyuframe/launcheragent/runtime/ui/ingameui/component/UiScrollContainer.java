package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.widget.UiHitTest;
import com.yuyuframe.launcheragent.apigraphic.widget.UiOverlayStack;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;

import java.util.ArrayList;
import java.util.List;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

/**
 * Défilement vertical + clipping (scissor) pour une liste de widgets dont la
 * hauteur totale dépasse la fenêtre visible (page de config avec beaucoup de
 * réglages). Les widgets ajoutés gardent leur y d'origine comme position "de
 * base" (baseY) — chaque frame, {@code widget.y} est recalculé à partir de
 * baseY + un décalage qui aligne le contenu sur le haut du viewport à
 * scroll=0, jamais muté de façon cumulative (pas de dérive possible).
 *
 * {@code scrollTarget} est la position "logique" (clampée, utilisée pour le
 * calcul de la barre) ; le défilement RENDU/testé au clic passe par
 * {@code scrollAnim} (UiAnimatedFloat) pour une décélération douce plutôt
 * qu'un saut instantané par cran de molette.
 *
 * BUG TROUVÉ (utilisateur : "il faut que toute la card passe à travers pour
 * qu'elle disparaisse, ce qui cause un chevauchement") : {@code
 * beginScissor()/endScissor()} (voir UiRenderer) sont EFFECTIVEMENT sans
 * effet dans les deux pipelines :
 *   - Legacy/Modern (GL immédiat, pré-1.21.6) — CHAQUE primitive de dessin
 *     (drawRoundedRect/drawText/drawFx) désactive inconditionnellement
 *     GL_SCISSOR_TEST au tout début de son propre setup (glDisable copié-collé
 *     du garde-fou légitime de drawEdgeVignette, qui LUI doit ignorer tout
 *     scissor actif car c'est un effet plein écran — mais ce garde-fou n'a
 *     RIEN à faire dans les primitives générales utilisées À L'INTÉRIEUR d'un
 *     scroll). Corrigé séparément dans UiRenderer (voir son historique).
 *   - Blaze3D (era E, 1.21.6+) — les dessins sont EMPILÉS (UiTextBlaze3D.queued)
 *     et exécutés en différé, une frame plus tard, à un tout autre moment que
 *     beginScissor()/endScissor() (synchrones, immédiats) : le scissor est
 *     déjà retombé bien avant que le dessin réel ne s'exécute. Ajouter un vrai
 *     scissor GPU dans ce pipeline nécessiterait de faire traverser un
 *     rectangle de clip à travers CHAQUE lambda empilée jusqu'à un RenderPass
 *     Blaze3D — trop risqué à l'aveugle (aucun moyen de tester en jeu depuis
 *     cet environnement) sur un pipeline déjà extrêmement fragile (voir
 *     l'historique de bugs de UiTextBlaze3D).
 * Plutôt que du clipping pixel réel côté Blaze3D, {@link UiWidget#clipFade}
 * (0..1) est calculé ICI selon le chevauchement avec les bords du viewport et
 * appliqué par les widgets qui le lisent (voir ResultCard.draw() dans
 * ModrinthContentScreen) — un widget s'estompe PROGRESSIVEMENT en sortant du
 * viewport au lieu d'apparaître/disparaître d'un coup sec en le franchissant.
 * Fonctionne IDENTIQUEMENT sur les trois pipelines (implémenté en pur Java,
 * aucune dépendance GL) — et reste un filet de sécurité utile même une fois
 * le vrai scissor GL corrigé côté Legacy/Modern (l'effet visuel "disparaît en
 * douceur" est de toute façon plus agréable qu'un cut-off nette).
 */
public class UiScrollContainer {

    // Non static — dépendent de UiTheme.UI_SCALE au moment de la construction
    // (voir GlobalUiSettings, réglage "Taille de l'interface"), même motif que
    // UiSlider/UiToggle/UiColorPicker.
    private final float SCROLL_STEP_PX = UiTheme.scaled(36f);
    // Marge haut/bas — les UiLabel ont une hauteur de widget nulle (leur y est
    // la ligne de base, voir UiLabel), donc contentTop/Bottom ne couvrent pas
    // les ascendantes/descendantes du texte réellement dessiné. Sans cette
    // marge, la première/dernière ligne se ferait tronquer pile au bord du
    // scissor en position de scroll extrême.
    private final float EDGE_PADDING = UiTheme.scaled(14f);

    // Distance (pixels écran) sur laquelle un widget s'estompe en chevauchant
    // un bord du viewport — voir clipFade dans la javadoc de classe.
    private final float EDGE_FADE_ZONE = UiTheme.scaled(46f);

    // La barre de défilement (refaite le 2026-11-20) : au repos un fil de
    // 2 px à moitié transparent ; au survol, en défilant ou en la tirant,
    // elle s'élargit en dégradé d'accent lumineux et sa piste apparaît. Une
    // liste découpée en sections y pose un point par section (voir
    // setSections) : le point de la section courante s'allume, son nom
    // apparaît au survol, un clic y saute.
    private final float RAIL_IDLE_W = UiTheme.scaled(2f);
    private final float RAIL_ACTIVE_W = UiTheme.scaled(6f);
    private final float RAIL_DRAG_W = UiTheme.scaled(8f);
    /** Demi-largeur de la zone qu'on survole et qu'on clique autour du fil. */
    private final float RAIL_HIT = UiTheme.scaled(11f);
    private final float RAIL_INSET = UiTheme.scaled(6f);
    private final float MARK_SIZE = UiTheme.scaled(8f);
    private final float SCROLLBAR_MIN_H = UiTheme.scaled(40f);

    /** Abscisse du fil (écran) ; {@code NaN} = juste à l'intérieur du bord droit de la liste. */
    private float railCenterX = Float.NaN;
    private final UiAnimatedFloat railActive = new UiAnimatedFloat(0f, UiTheme.Motion.HOVER);
    private final UiAnimatedFloat railDrag = new UiAnimatedFloat(0f, UiTheme.Motion.HOVER);

    private final List<String> sectionNames = new ArrayList<>();
    private final List<Float> sectionAnchors = new ArrayList<>();
    private final List<UiAnimatedFloat> sectionLit = new ArrayList<>();
    private int hoveredMark = -1;
    private final UiAnimatedFloat labelAnim = new UiAnimatedFloat(0f, UiTheme.Motion.HOVER);
    private int labelMark = -1;

    // Accélération molette — deux crans consécutifs dans la MÊME direction en
    // moins de ACCEL_WINDOW_MS augmentent le pas effectif, jusqu'à un
    // plafond — motif "scroll qui accélère" d'un vrai trackpad/souris moderne
    // (ex: Windows/macOS), pas un simple pas fixe par cran.
    private static final long ACCEL_WINDOW_MS = 220L;
    private static final float ACCEL_STEP = 0.4f;
    private static final float ACCEL_MAX = 3.2f;
    private long lastScrollAtMs;
    private int lastScrollSign;
    private float scrollVelocity = 1f;

    // La barre s'éveille pendant une interaction et juste après, puis
    // redevient un fil (sans jamais disparaître : elle reste repérable).
    private static final long IDLE_FADE_DELAY_MS = 900L;
    private long lastActivityAtMs;

    private final UiAnimatedFloat thumbHoverAnim = new UiAnimatedFloat(0f, 14f);

    private final float vx, vy, vw, vh; // viewport en espace écran, (vx,vy) = coin bas-gauche
    private final List<UiWidget> content = new ArrayList<>();
    private final List<Float> baseY = new ArrayList<>();
    private float contentTop = 0f, contentBottom = 0f;

    private float scrollTarget;
    private final UiAnimatedFloat scrollAnim = new UiAnimatedFloat(0f, 16f);
    private float lastAnimatedScroll;

    private boolean thumbDragging;
    private float lastDragMouseY;

    public UiScrollContainer(float vx, float vy, float vw, float vh) {
        this.vx = vx; this.vy = vy; this.vw = vw; this.vh = vh;
    }

    /** Enregistre un widget déjà positionné (w.y/w.x) dans son propre repère "contenu" (top-down, arbitraire). */
    public void add(UiWidget w) {
        if (content.isEmpty()) {
            contentTop = w.y + w.h;
            contentBottom = w.y;
        } else {
            contentTop = Math.max(contentTop, w.y + w.h);
            contentBottom = Math.min(contentBottom, w.y);
        }
        content.add(w);
        baseY.add(w.y);
    }

    /**
     * Pose le fil de la barre à cette abscisse (écran) au lieu du bord de la
     * liste — l'accueil le colle au bord droit de la fenêtre.
     */
    public UiScrollContainer railAt(float centerX) {
        this.railCenterX = centerX;
        return this;
    }

    /**
     * Les sections de la liste, dans l'ordre : leur nom (déjà traduit) et le
     * haut de leur en-tête, dans le même repère que {@link #scrollToAnchor}.
     * Un point par section sur la barre.
     */
    public void setSections(List<String> names, List<Float> anchorTops) {
        sectionNames.clear();
        sectionAnchors.clear();
        sectionLit.clear();
        int n = Math.min(names.size(), anchorTops.size());
        for (int i = 0; i < n; i++) {
            sectionNames.add(names.get(i));
            sectionAnchors.add(anchorTops.get(i));
            sectionLit.add(new UiAnimatedFloat(0f, UiTheme.Motion.HOVER));
        }
    }

    /** Ajoute une section : {@code header} est son en-tête, déjà ajouté à la liste. */
    public void addSection(String name, UiWidget header) {
        sectionNames.add(name);
        sectionAnchors.add(header.y + header.h);
        sectionLit.add(new UiAnimatedFloat(0f, UiTheme.Motion.HOVER));
    }

    private float railX() {
        return Float.isNaN(railCenterX) ? vx + vw - RAIL_INSET : railCenterX;
    }

    /** Hauteur (écran) du point de la section {@code i} sur la barre. */
    private float markY(int i) {
        return vy + vh - baseYFraction(sectionAnchors.get(i)) * vh;
    }

    /** La section où l'on se trouve : la dernière dont l'en-tête est passé sous le haut de la liste. */
    private int currentSection() {
        if (sectionAnchors.isEmpty()) return -1;
        if (scrollProgress() >= 0.999f) return sectionAnchors.size() - 1;
        int current = 0;
        for (int i = 0; i < sectionAnchors.size(); i++) {
            if (contentTop - sectionAnchors.get(i) <= lastAnimatedScroll + UiTheme.scaled(40f)) current = i;
        }
        return current;
    }

    public void clear() {
        content.clear();
        baseY.clear();
        scrollTarget = 0f;
        scrollAnim.setTarget(0f);
        contentTop = 0f;
        contentBottom = 0f;
    }

    private float maxScroll() {
        return Math.max(0f, (contentTop - contentBottom) + EDGE_PADDING * 2 - vh);
    }

    /**
     * Fait défiler pour amener le widget dont le bord HAUT (baseY + hauteur)
     * vaut {@code anchorTop} juste sous le haut du viewport — même référence
     * que le tout premier widget à scroll=0 (voir applyOffsets : {@code off
     * = vy+vh-EDGE_PADDING-contentTop}, donc {@code screenY(w) = baseY(w) +
     * off}). Résolu en posant {@code screenY(anchor)+h = vy+vh-EDGE_PADDING}
     * (même position que le début du contenu à scroll=0), d'où
     * {@code scrollTarget = contentTop - anchorTop}. Utilisé pour la
     * navigation "catégorie -> section" d'UiModConfigScreen : une seule
     * liste continue, les onglets ne font que défiler jusqu'au bon endroit
     * au lieu de basculer entre des pages séparées.
     */
    public void scrollToAnchor(float anchorTop) {
        scrollTarget = clampScroll(contentTop - anchorTop);
        scrollAnim.setTarget(scrollTarget);
        markActivity();
    }

    /**
     * Progression du scroll en POURCENTAGE (0 = tout en haut, 1 = tout en
     * bas) — remplace l'ancienne approche par seuil de pixels fixe (voir
     * historique : {@code visibleTopBaseY() - décalage arbitraire}, retour
     * utilisateur "le calcul est mal fait"). Demande explicite : "quand on a
     * scrollé tout en bas on est au dernier module" — un pourcentage garantit
     * mathématiquement ce résultat (1.0 exactement à scroll max), ce qu'un
     * seuil de pixels fixe ne garantissait pas (pouvait ne jamais atteindre
     * la dernière section selon le nombre/la hauteur des sections).
     */
    public float scrollProgress() {
        float max = maxScroll();
        if (max <= 0.001f) return 0f;
        return Math.max(0f, Math.min(1f, lastAnimatedScroll / max));
    }

    /**
     * Position {@code baseY} convertie en pourcentage du contenu TOTAL (0 =
     * tout en haut du contenu, 1 = tout en bas) — indépendant de la hauteur
     * du viewport (contrairement à {@code scrollProgress}, qui elle est
     * bornée par {@code maxScroll}). Comparer les deux revient à demander
     * "en pourcentage, ai-je déjà dépassé le début de cette section ?" —
     * voir UiModConfigScreen#updateActiveCategory pour l'usage.
     */
    public float baseYFraction(float baseY) {
        float span = contentTop - contentBottom;
        if (span <= 0.001f) return 0f;
        return Math.max(0f, Math.min(1f, (contentTop - baseY) / span));
    }

    private float clampScroll(float v) {
        return Math.max(0f, Math.min(maxScroll(), v));
    }

    /** Défilement courant en pixels — à capturer AVANT une reconstruction, voir {@link #restoreScroll}. */
    public float scrollOffset() { return scrollTarget; }

    /**
     * Restaure un défilement capturé via {@link #scrollOffset()} sur un
     * conteneur FRAÎCHEMENT reconstruit.
     *
     * <p>Un écran qui se reconstruit sans perdre son contexte (ici : basculer
     * un favori, qui réordonne la grille) crée un conteneur NEUF, donc remis à
     * zéro — la liste resaute en haut alors que l'utilisateur n'a rien demandé
     * de tel. C'est une bonne moitié de la raison pour laquelle le geste
     * devient illisible : le repère visuel disparaît en même temps que la
     * carte change de place.
     *
     * <p>À appeler APRÈS tous les {@link #add} : le clamp dépend de
     * {@link #maxScroll()}, donc du contenu déjà enregistré. Pose la valeur
     * SANS animer ({@code snapTo}) — restaurer un état n'est pas un mouvement.
     */
    public void restoreScroll(float offset) {
        scrollTarget = clampScroll(offset);
        scrollAnim.snapTo(scrollTarget);
        lastAnimatedScroll = scrollTarget;
    }

    private void applyOffsets() {
        lastAnimatedScroll = scrollAnim.get();
        float off = (vy + vh - EDGE_PADDING - contentTop) + lastAnimatedScroll;
        for (int i = 0; i < content.size(); i++) content.get(i).y = baseY.get(i) + off;
    }

    /** Élargi de EDGE_FADE_ZONE au-delà du viewport — un widget doit rester "vivant" (dessiné, avec clipFade qui tend déjà vers 0) jusqu'à la fin de son fondu, pas disparaître d'un coup sec juste avant qu'il n'ait fini de s'estomper. */
    private boolean visible(UiWidget w) {
        return w.y + w.h >= vy - EDGE_FADE_ZONE && w.y <= vy + vh + EDGE_FADE_ZONE;
    }

    /** Voir {@link UiWidget#clipFade} — 1 = pleinement opaque (entièrement dans le viewport), dégressif vers 0 en chevauchant un bord, sur EDGE_FADE_ZONE pixels. */
    private float edgeFade(UiWidget w) {
        float topOverflow = (w.y + w.h) - (vy + vh);
        float bottomOverflow = vy - w.y;
        float fade = 1f;
        if (topOverflow > 0f) fade = Math.min(fade, Math.max(0f, 1f - topOverflow / EDGE_FADE_ZONE));
        if (bottomOverflow > 0f) fade = Math.min(fade, Math.max(0f, 1f - bottomOverflow / EDGE_FADE_ZONE));
        return fade;
    }

    private boolean hasScrollbar() { return maxScroll() > 0.5f; }

    private float thumbHeight() {
        float contentHeight = (contentTop - contentBottom) + EDGE_PADDING * 2f;
        float ratio = contentHeight > 0 ? Math.min(1f, vh / contentHeight) : 1f;
        return Math.max(SCROLLBAR_MIN_H, vh * ratio);
    }

    private float thumbY(float scrollValue) {
        float th = thumbHeight();
        float max = maxScroll();
        float t = max > 0 ? scrollValue / max : 0f;
        float travel = vh - th;
        return vy + vh - th - t * travel;
    }

    private void markActivity() {
        lastActivityAtMs = System.currentTimeMillis();
    }

    public void pollInput(UiInputPoller input) {
        // Une modale est ouverte : ce qui est derrière ne défile pas et ne se clique pas.
        if (UiOverlayStack.active().blocksInput()) return;
        boolean overViewport = input.mouseX >= vx && input.mouseX <= vx + vw
            && input.mouseY >= vy && input.mouseY <= vy + vh;
        if (overViewport && input.scrollDelta != 0) {
            // Accélération : crans consécutifs dans la même direction, assez
            // rapprochés dans le temps, augmentent le pas effectif — voir
            // ACCEL_STEP/ACCEL_MAX. Un changement de sens ou une pause reset
            // immédiatement à la vitesse de base (pas d'accélération résiduelle
            // surprenante après avoir changé d'avis).
            long now = System.currentTimeMillis();
            int sign = Integer.signum(input.scrollDelta);
            if (sign == lastScrollSign && now - lastScrollAtMs < ACCEL_WINDOW_MS) {
                scrollVelocity = Math.min(ACCEL_MAX, scrollVelocity + ACCEL_STEP);
            } else {
                scrollVelocity = 1f;
            }
            lastScrollSign = sign;
            lastScrollAtMs = now;

            // Molette positive ("vers le haut") = veut voir le début du contenu -> scrollTarget diminue.
            scrollTarget = clampScroll(scrollTarget - input.scrollDelta * SCROLL_STEP_PX * scrollVelocity);
            scrollAnim.setTarget(scrollTarget);
            markActivity();
        }

        boolean overScrollbarArea = false;
        hoveredMark = -1;
        if (hasScrollbar()) {
            float th = thumbHeight();
            float cx = railX();
            overScrollbarArea = Math.abs(input.mouseX - cx) <= RAIL_HIT
                && input.mouseY >= vy && input.mouseY <= vy + vh;
            if (overScrollbarArea && !thumbDragging) {
                for (int i = 0; i < sectionAnchors.size(); i++) {
                    if (Math.abs(input.mouseY - markY(i)) <= MARK_SIZE) hoveredMark = i;
                }
            }

            if (!thumbDragging) {
                float ty = thumbY(lastAnimatedScroll);
                boolean overThumb = overScrollbarArea && input.mouseY >= ty && input.mouseY <= ty + th;
                thumbHoverAnim.setTarget(overThumb ? 1f : 0f);

                if (input.leftClicked && hoveredMark >= 0) {
                    // Un point de section : on y saute.
                    scrollToAnchor(sectionAnchors.get(hoveredMark));
                } else if (input.leftClicked && overThumb) {
                    thumbDragging = true;
                    lastDragMouseY = (float) input.mouseY;
                    markActivity();
                } else if (input.leftClicked && overScrollbarArea) {
                    // Clic sur la piste HORS du thumb — saut d'une page vers le
                    // point cliqué (au-dessus du thumb = page précédente, en
                    // dessous = page suivante), comme une scrollbar OS classique.
                    float pageAmount = vh * 0.9f;
                    if (input.mouseY > ty + th) {
                        scrollTarget = clampScroll(scrollTarget - pageAmount);
                    } else if (input.mouseY < ty) {
                        scrollTarget = clampScroll(scrollTarget + pageAmount);
                    }
                    scrollAnim.setTarget(scrollTarget);
                    markActivity();
                }
            } else if (!input.leftDown) {
                thumbDragging = false;
            } else {
                float travel = vh - th;
                if (travel > 0.01f) {
                    float dy = (float) input.mouseY - lastDragMouseY;
                    scrollTarget = clampScroll(scrollTarget - dy * (maxScroll() / travel));
                    scrollAnim.setTarget(scrollTarget);
                }
                lastDragMouseY = (float) input.mouseY;
                markActivity();
            }
        } else {
            thumbDragging = false;
            thumbHoverAnim.setTarget(0f);
        }

        if (overScrollbarArea || thumbDragging) markActivity();
        boolean idle = System.currentTimeMillis() - lastActivityAtMs > IDLE_FADE_DELAY_MS;
        railActive.setTarget(idle ? 0f : 1f);
        railDrag.setTarget(thumbDragging ? 1f : 0f);

        applyOffsets();

        // BUG ÉVITÉ (pas rencontré en jeu, repéré à l'écriture) : NE JAMAIS
        // réutiliser une même liste "scratch" mutée en place ici — le cache
        // de UiHitTest#find se fie à l'ÉGALITÉ DE RÉFÉRENCE de la liste pour
        // savoir s'il doit recalculer ; une liste réutilisée (clear+refill)
        // garde TOUJOURS la même référence même quand son CONTENU change
        // (ex: après un scroll, les widgets visibles à cette position souris
        // changent) — le cache renverrait alors un résultat périmé tant que
        // la souris elle-même n'a pas bougé. Une liste FRAÎCHE à chaque appel
        // change de référence à chaque fois, donc le cache se réinvalide
        // correctement dès que le CONTENU (pas juste la position souris) change.
        // Un élément flottant de l'écran (liste déroulée, bulle, modale) couvre
        // la souris ? Le clic est pour lui (voir UiOverlayStack) — un élément
        // en train de se fermer couvre encore sa surface, donc le clic qui
        // vient de choisir une ligne ne retombe pas sur le réglage dessous.
        boolean overFloating = UiOverlayStack.active().capturesPoint(input.mouseX, input.mouseY);

        List<UiWidget> visibleNow = new ArrayList<>();
        for (UiWidget w : content) {
            if (!visible(w)) continue;
            w.clipFade = edgeFade(w);
            w.pollContinuous(input);
            visibleNow.add(w);
        }
        // BUG TROUVÉ (audit input, symétrie avec UiScreenBase#dispatchClick) :
        // seul input.leftClicked était lu ici, et le widget touché recevait
        // l'ancien onClick() sans argument — un widget DANS un scroll (grille
        // de cartes, la quasi-totalité du contenu réel de ce moteur) ne
        // pouvait donc JAMAIS recevoir de clic droit/milieu, contrairement à
        // un widget d'écran plat (UiScreenBase.dispatchClick gère déjà les 3
        // boutons). Pas de doubleClick ici : UiInputPoller (poll brut, pas le
        // vrai dispatch Click/MouseButtonEvent vanilla) n'a aucune notion de
        // double-clic — limite structurelle assumée, pas une régression.
        int clickedButton = input.leftClicked ? 0 : input.rightClicked ? 1 : input.middleClicked ? 2 : -1;
        if (clickedButton >= 0) {
            // Hit-test centralisé (roadmap Phase 5.6, retour utilisateur :
            // "c'est ce qui est visible qui doit être cliquable") — voir
            // UiHitTest pour la règle exacte (plus petite aire gagne, PAS
            // l'ordre d'insertion) : remplace l'ancien "premier match en
            // ordre d'insertion gagne", dont dépendait fragilement l'ordre
            // d'ajout cœur/bande dans UiMainMenuScreen (toujours correct
            // avec la nouvelle règle, sans dépendre de cet ordre).
            //
            // BUG TROUVÉ (retour utilisateur 2026-10-04 : « la hit box de la
            // card passe en haut, on peut cliquer sur la card sans la voir ») :
            // visibleNow déborde du viewport de EDGE_FADE_ZONE (les widgets y
            // finissent leur fondu), donc un widget déjà estompé au-dessus ou
            // en dessous du cadre recevait encore les clics. Un clic HORS du
            // viewport ne touche plus aucun widget du contenu — il sert
            // seulement, ci-dessous, à retirer le focus des champs.
            //
            // BUG TROUVÉ (retour utilisateur 2026-10-11 : « sur toutes les
            // listes déroulantes les éléments en dessous restent cliquables ») :
            // la liste déroulée traite son clic elle-même, puis cette recherche
            // trouvait le réglage qu'elle RECOUVRE et lui envoyait le même clic.
            // Un clic sur un panneau flottant ne touche plus rien d'autre.
            // Un clic sur la barre (fil, point de section) est pour elle seule.
            UiWidget clicked = overViewport && !overFloating && !overScrollbarArea
                ? UiHitTest.find(visibleNow, input.mouseX, input.mouseY) : null;

            // PERTE DE FOCUS au clic extérieur — même règle que
            // UiScreenBase.dispatchClick, qui ne pouvait pas l'appliquer ici :
            // il ne parcourt que les widgets de l'ÉCRAN, alors que la
            // quasi-totalité des champs de saisie réels vivent dans un scroll.
            //
            // BUG TROUVÉ (retour utilisateur 2026-08-31 : « on ne peut pas
            // un focus au click exterieur les champ de texte dans l'écrant de
            // config ») : un champ gardait son curseur clignotant
            // indéfiniment, et continuait de capter la frappe, même après un
            // clic ailleurs. On parcourt TOUT le contenu et pas seulement le
            // visible : un champ focus puis défilé hors du viewport doit
            // perdre le focus lui aussi.
            for (UiWidget w : content) {
                if (w instanceof UiTextField && w != clicked) ((UiTextField) w).setFocused(false);
            }

            if (clicked != null) clicked.onClick(clickedButton, false);
        }
    }

    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        applyOffsets();
        // Marge autour du viewport (2026-09-16) : sur les ères GL, où ce
        // scissor coupe réellement, un clip calé au pixel près sur le viewport
        // rognait tout ce qui déborde légitimement des widgets — contour des
        // cartes de verre (posé 1 px À L'EXTÉRIEUR, voir
        // UiRenderer.drawGlassPanel), soulèvement au survol, éclat de clic.
        // Constaté en 1.8.9 : contour gauche des cartes de la 1re colonne
        // coupé. Rien ne défile horizontalement, d'où la marge large sur X ;
        // sur Y, le défilement est estompé par clipFade, une petite marge suffit.
        int marginX = Math.round(UiTheme.scaled(8f));
        int marginY = Math.round(UiTheme.scaled(2f));
        int scissorX = Math.max(0, (int) Math.floor(vx) - marginX);
        int scissorY = Math.max(0, (int) Math.floor(vy) - marginY);
        renderer.beginScissor(scissorX, scissorY,
            (int) Math.ceil(vx + vw) + marginX - scissorX,
            (int) Math.ceil(vy + vh) + marginY - scissorY);
        // try/finally OBLIGATOIRE ici : sans lui, une exception dans UN SEUL
        // w.draw() (widget de la liste) saute endScissor() — GL_SCISSOR_TEST
        // reste actif indéfiniment (aucun code ailleurs ne le redésactive de
        // lui-même), avec CE rectangle comme zone de clip pour TOUT rendu GL
        // suivant, frame après frame, jusqu'au prochain begin/endScissor
        // symétrique — y compris des éléments totalement indépendants comme
        // LowHealthTintModule, dont le dégradé se retrouvait alors découpé
        // net à ce rectangle, invariant à toute retouche d'opacité/courbe
        // (un scissor test est un clip binaire, pas un blend d'alpha).
        String hoveredTooltip = null;
        try {
            for (UiWidget w : content) {
                if (!visible(w)) continue;
                w.clipFade = edgeFade(w);
                w.draw(renderer, mouseX, mouseY, vpWidth, vpHeight);
                if (w.tooltip != null && w.contains(mouseX, mouseY)) hoveredTooltip = w.tooltip;
            }
        } finally {
            renderer.endScissor();
        }

        drawScrollbar(renderer, vpWidth, vpHeight);

        // Les panneaux flottants des widgets (liste déroulée, couleurs) ne
        // sont plus dessinés ici : ils vivent dans la pile de l'écran
        // (UiOverlayStack), au-dessus de tout. L'infobulle aussi part en fin
        // d'image, après eux.
        if (hoveredTooltip != null) UiTooltip.defer(hoveredTooltip);
    }

    private void drawScrollbar(UiRenderer renderer, int vpWidth, int vpHeight) {
        if (!hasScrollbar()) return;
        float a = railActive.get();
        float drag = railDrag.get();
        float hoverThumb = thumbHoverAnim.get();
        float cx = railX();

        // La piste : un trait fin, seulement quand la barre est éveillée.
        float trackW = UiTheme.hairline() * 2f;
        if (a > 0.01f) {
            renderer.drawRoundedRect(cx - trackW / 2f, vy, cx + trackW / 2f, vy + vh, trackW / 2f,
                new UiColor(255, 255, 255, Math.round(18 * a)), vpWidth, vpHeight);
        }

        // Les points de section, sous le pouce : petits au repos, pleins éveillés.
        int current = currentSection();
        for (int i = 0; i < sectionAnchors.size(); i++) {
            UiAnimatedFloat lit = sectionLit.get(i);
            lit.setTarget(i == current ? 1f : 0f);
            float l = lit.get();
            float hov = i == hoveredMark ? 1f : 0f;
            float size = MARK_SIZE * (0.55f + 0.45f * Math.max(a, hov));
            float my = markY(i);
            UiColor dot = UiColor.lerp(new UiColor(255, 255, 255, 60), UiTheme.accentLight(), Math.max(l, hov));
            renderer.drawRoundedRect(cx - size / 2f, my - size / 2f, cx + size / 2f, my + size / 2f, size / 2f,
                dot.multiplyAlpha(0.5f + 0.5f * Math.max(a, l)), vpWidth, vpHeight);
        }

        // Le pouce : un fil au repos, une gélule en dégradé d'accent éveillée.
        float w = RAIL_IDLE_W + (RAIL_ACTIVE_W - RAIL_IDLE_W) * Math.max(a, hoverThumb)
            + (RAIL_DRAG_W - RAIL_ACTIVE_W) * drag;
        float th = thumbHeight();
        float ty = thumbY(lastAnimatedScroll);
        float alpha = 0.45f + 0.55f * Math.max(a, hoverThumb);
        if (a > 0.01f) {
            renderer.drawGlow(cx - w / 2f, ty, cx + w / 2f, ty + th, w / 2f, 0.55f * a,
                UiTheme.ACCENT.withAlpha(0.55f), vpWidth, vpHeight);
        }
        renderer.drawGradientRect(cx - w / 2f, ty, cx + w / 2f, ty + th, w / 2f,
            UiTheme.ACCENT.multiplyAlpha(alpha), UiTheme.accentLight().multiplyAlpha(alpha), vpWidth, vpHeight);

        // Le nom de la section survolée, dans une bulle à gauche du fil.
        if (hoveredMark >= 0) labelMark = hoveredMark;
        labelAnim.setTarget(hoveredMark >= 0 ? 1f : 0f);
        float la = labelAnim.get();
        if (la > 0.02f && labelMark >= 0 && labelMark < sectionNames.size()) {
            String name = sectionNames.get(labelMark);
            float s = UiTheme.px(UiTheme.Text.SM);
            float pad = UiTheme.px(UiTheme.Space.S10);
            float bw = UiText.width(UiFont.BOLD, name, s) + pad * 2f;
            float bh = UiTheme.px(UiTheme.Space.S28);
            float my = markY(labelMark);
            // Elle sort du point vers la gauche : glisse de quelques pixels en apparaissant.
            float bx2 = cx - RAIL_HIT - UiTheme.px(UiTheme.Space.S4) + (1f - la) * UiTheme.px(UiTheme.Space.S6);
            float bx1 = bx2 - bw, by1 = my - bh / 2f;
            renderer.drawShadow(bx1, by1, bx2, by1 + bh, bh / 2f, 12f, 0f, new UiColor(0, 0, 0, Math.round(110 * la)),
                vpWidth, vpHeight);
            renderer.drawRoundedRect(bx1, by1, bx2, by1 + bh, bh / 2f, UiTheme.PANEL_BG_ALT.multiplyAlpha(la),
                vpWidth, vpHeight);
            renderer.drawRoundedRectBorder(bx1, by1, bx2, by1 + bh, bh / 2f, UiTheme.hairline(),
                UiTheme.ACCENT.withAlpha(0.35f * la), vpWidth, vpHeight);
            renderer.drawText(UiFont.BOLD, name, bx1 + pad, UiText.centeredBaseline(UiFont.BOLD, by1, bh, s),
                UiTheme.TEXT_PRIMARY.multiplyAlpha(la), s, vpWidth, vpHeight);
        }
    }
}
