package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiHitTest;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiWidget;

import java.util.ArrayList;
import java.util.List;

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

    private final float SCROLLBAR_W = UiTheme.scaled(6f);
    private final float SCROLLBAR_MARGIN = UiTheme.scaled(4f);
    private final float SCROLLBAR_MIN_H = UiTheme.scaled(24f);

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

    // Fondu d'activité de la scrollbar (façon overlay scrollbar macOS/Win11) —
    // pleine opacité pendant/juste après une interaction, sinon estompée sans
    // jamais disparaître complètement (reste repérable).
    private static final long IDLE_FADE_DELAY_MS = 900L;
    private static final float IDLE_ALPHA = 0.35f;
    private final UiAnimatedFloat scrollbarAlpha = new UiAnimatedFloat(1f, 6f);
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
        if (hasScrollbar()) {
            float th = thumbHeight();
            float tx = vx + vw - SCROLLBAR_W - SCROLLBAR_MARGIN;
            float hitPad = UiTheme.scaled(3f);
            overScrollbarArea = input.mouseX >= tx - hitPad && input.mouseX <= tx + SCROLLBAR_W + hitPad
                && input.mouseY >= vy && input.mouseY <= vy + vh;

            if (!thumbDragging) {
                float ty = thumbY(lastAnimatedScroll);
                boolean overThumb = overScrollbarArea && input.mouseY >= ty && input.mouseY <= ty + th;
                thumbHoverAnim.setTarget(overThumb ? 1f : 0f);

                if (input.leftClicked && overThumb) {
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
        scrollbarAlpha.setTarget(idle ? IDLE_ALPHA : 1f);

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
        List<UiWidget> visibleNow = new ArrayList<>();
        for (UiWidget w : content) {
            if (!visible(w)) continue;
            w.clipFade = edgeFade(w);
            w.pollContinuous(input);
            visibleNow.add(w);
        }
        if (input.leftClicked) {
            // Hit-test centralisé (roadmap Phase 5.6, retour utilisateur :
            // "c'est ce qui est visible qui doit être cliquable") — voir
            // UiHitTest pour la règle exacte (plus petite aire gagne, PAS
            // l'ordre d'insertion) : remplace l'ancien "premier match en
            // ordre d'insertion gagne", dont dépendait fragilement l'ordre
            // d'ajout cœur/bande dans UiMainMenuScreen (toujours correct
            // avec la nouvelle règle, sans dépendre de cet ordre).
            UiWidget clicked = UiHitTest.find(visibleNow, input.mouseX, input.mouseY);
            if (clicked != null) clicked.onClick();
        }
    }

    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        applyOffsets();
        renderer.beginScissor((int) vx, (int) vy, (int) vw, (int) vh);
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

        // Second passage — voir UiWidget#drawOverlay (BUG TROUVÉ, retour
        // utilisateur : "la modal [color picker] entière... se fait
        // chevaucher par tout") : un panneau déroulant dessiné dans le
        // premier passage ci-dessus reste soumis à l'ordre d'insertion —
        // n'importe quel widget plus bas dans la liste (donc plus bas à
        // l'écran) se dessine PAR-DESSUS lui s'ils se chevauchent. Ce
        // second passage, après TOUT le contenu normal ET la scrollbar,
        // garantit qu'un tel panneau flotte toujours au-dessus — même motif
        // que UiTooltip juste en dessous, déjà dessiné en dernier pour la
        // même raison.
        for (UiWidget w : content) {
            if (!visible(w)) continue;
            w.drawOverlay(renderer, mouseX, mouseY, vpWidth, vpHeight);
        }

        if (hoveredTooltip != null) UiTooltip.draw(renderer, hoveredTooltip, mouseX, mouseY, vpWidth, vpHeight);
    }

    private void drawScrollbar(UiRenderer renderer, int vpWidth, int vpHeight) {
        if (!hasScrollbar()) return;
        float alpha = scrollbarAlpha.get();
        float th = thumbHeight();
        float tx = vx + vw - SCROLLBAR_W - SCROLLBAR_MARGIN;
        float ty = thumbY(lastAnimatedScroll);
        renderer.drawRoundedRect(tx, vy, tx + SCROLLBAR_W, vy + vh, SCROLLBAR_W / 2f,
            UiTheme.TRACK_OFF.multiplyAlpha(alpha), vpWidth, vpHeight);
        UiColor thumbColor = thumbDragging ? UiTheme.ACCENT
            : UiColor.lerp(UiTheme.TEXT_MUTED, UiTheme.TEXT_SECONDARY, thumbHoverAnim.get());
        renderer.drawRoundedRect(tx, ty, tx + SCROLLBAR_W, ty + th, SCROLLBAR_W / 2f,
            thumbColor.multiplyAlpha(alpha), vpWidth, vpHeight);
    }
}
