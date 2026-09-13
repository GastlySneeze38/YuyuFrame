package com.yuyuframe.launcheragent.apigraphic.backend;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.GlBridge;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;

/**
 * Ce que toute ère doit savoir faire — le contrat que {@code era/} implémente
 * et que la façade {@code UiRenderer} appelle.
 *
 * <h2>État : une seule primitive, volontairement</h2>
 *
 * Le contrat démarre avec {@link #roundedRect} et rien d'autre. C'est délibéré :
 * la forme d'une interface de rendu ne se décide pas sur le papier, elle se
 * vérifie sur un cas réel. Le rect arrondi est le bon cas — il existe déjà dans
 * les trois chemins (gl2, gl3, Blaze3D), avec les mêmes uniformes et la même
 * règle {@code radius<=0}, donc il exerce la frontière sans rien inventer.
 *
 * <p>Les autres primitives (icône, texte, vignette, dégradé, flou, clip) le
 * rejoindront au fur et à mesure du découpage des trois renderers de
 * {@code render/} — voir le {@code package-info} de ce paquet-là.
 *
 * <h2>Pourquoi un {@code boolean} de retour</h2>
 *
 * {@code true} = « j'ai dessiné ». {@code false} = « pas mon affaire, prends
 * ton chemin habituel ».
 *
 * <p>C'est une forme de TRANSITION, pas la forme finale. Elle permet
 * d'introduire le contrat sans toucher au comportement : une ère sans backend
 * répond {@code false}, l'appelant retombe exactement sur le code qu'il
 * exécutait hier. Quand les trois renderers auront été découpés et que chaque
 * ère aura un backend complet, ce booléen disparaîtra — un backend ne pourra
 * plus décliner.
 */
public interface UiBackend {

    /** Identifiant lisible, pour le log. */
    String id();

    /**
     * Fournit au backend ce qu'il ne peut pas se procurer seul — appelé UNE
     * fois par {@code UiRenderer} juste après la résolution.
     *
     * <p>Les backends sont instanciés par réflexion (constructeur sans
     * argument, voir {@code UiBackendRegistry}), donc ils ne peuvent pas
     * recevoir la façade ni le pont GL par constructeur. Les ères GL en ont
     * besoin ; l'ère Blaze3D, qui passe par des points d'entrée statiques,
     * l'ignore — d'où un {@code default} vide plutôt qu'une méthode à
     * implémenter partout.
     */
    default void attach(UiRenderer owner, GlBridge gl) {}

    /**
     * Texte, ligne de base à {@code y}.
     *
     * @return {@code true} si l'ère l'a pris en charge
     */
    boolean text(UiFont font, String content, float x, float y, UiColor color, float scale,
                 int vpWidth, int vpHeight);

    /** Rectangle arrondi à RAYON PAR COIN. */
    boolean roundedRect(float x1, float y1, float x2, float y2,
                        float radiusBottomLeft, float radiusBottomRight,
                        float radiusTopLeft, float radiusTopRight,
                        UiColor color, int vpWidth, int vpHeight);

    /**
     * Rectangle arrondi destiné au HUD — même géométrie, mais l'ère peut le
     * router vers un autre point de la frame (sur Blaze3D, l'état GUI plutôt
     * qu'une passe propre), d'où une entrée distincte plutôt qu'un drapeau.
     */
    boolean roundedRectHud(float x1, float y1, float x2, float y2, float radius,
                           UiColor color, int vpWidth, int vpHeight);

    /** Vignette de bord (assombrissement périphérique plein écran). */
    boolean vignette(UiColor edgeColor, float vSize, int vpWidth, int vpHeight);

    /** Image arbitraire (icône Modrinth, bannière…), {@code alpha} 0..1. */
    boolean icon(String cacheKey, java.awt.image.BufferedImage img, float x, float y, float w, float h,
                 float alpha, int vpWidth, int vpHeight);

    // ── Capacités que toutes les ères n'ont pas ───────────────────────────
    //
    // Verre dépoli, lot de texte, vignette : une ère qui ne sait pas les faire
    // n'a RIEN à écrire — le défaut décline, et l'appelant prend son repli
    // (un aplat pour le verre, un dessin non groupé pour le texte).
    //
    // C'est le patron « objet nul » appliqué à la capacité plutôt qu'au
    // backend entier : plus de « if (isGlassAvailable()) » chez l'appelant,
    // c'est la réponse du backend qui porte l'information.

    /** Le verre dépoli est-il réalisable sur cette ère et dans cette frame ? */
    default boolean glassAvailable() { return false; }

    /** Ouvre la chaîne de flou partagée de la frame. */
    default boolean beginGlassFrame(int passes, int vpWidth, int vpHeight) { return false; }

    /**
     * Panneau de verre.
     *
     * @return {@code false} pour que l'appelant dessine l'aplat de repli —
     *         c'est le cas normal quand la chaîne de flou n'a pas pu être
     *         calculée pour cette frame, pas une erreur
     */
    default boolean glassPanel(float x1, float y1, float x2, float y2,
                               float radiusTopLeft, float radiusTopRight,
                               float radiusBottomLeft, float radiusBottomRight,
                               UiColor tint, float tintStrength, UiColor fallback,
                               int vpWidth, int vpHeight) { return false; }

    /** Panneau flouté autonome (sans la chaîne partagée de la frame). */
    default boolean blurredPanel(float x1, float y1, float x2, float y2,
                                 float radiusTopLeft, float radiusTopRight,
                                 float radiusBottomLeft, float radiusBottomRight,
                                 int passes, UiColor tint, float tintStrength,
                                 int vpWidth, int vpHeight) { return false; }

    /** Ouvre un lot de texte (un seul maillage pour toutes les chaînes qui suivent). */
    default boolean beginTextBatch() { return false; }

    /** Ferme le lot ouvert par {@link #beginTextBatch}. */
    default boolean endTextBatch(int vpWidth, int vpHeight) { return false; }

    /** La vignette est-elle dessinable en l'état ? */
    default boolean vignetteAvailable() { return false; }

    // ── Primitives dont le test d'ère vivait DANS UiPrimitiveRenderer ─────
    //
    // Contrairement aux précédentes, celles-ci n'avaient aucun test dans la
    // façade : elle appelait directement les primitives, qui branchaient en
    // interne. D'où des comportements par ère qu'on ne voyait qu'en ouvrant le
    // fichier — l'ombre et la bordure, par exemple, ne dessinent RIEN sur
    // Blaze3D (pas de vrai flou gaussien disponible sur ce chemin).

    /** Ombre portée (flou + écartement). */
    default boolean shadow(float x1, float y1, float x2, float y2, float radius, float blur, float spread,
                           UiColor color, int vpWidth, int vpHeight) { return false; }

    /** Contour de rectangle arrondi. */
    default boolean roundedRectBorder(float x1, float y1, float x2, float y2, float radius, float borderWidth,
                                      UiColor color, int vpWidth, int vpHeight) { return false; }

    /** Dégradé vertical bas→haut. */
    default boolean gradientRect(float x1, float y1, float x2, float y2, float radius,
                                 UiColor colorBottom, UiColor colorTop,
                                 int vpWidth, int vpHeight) { return false; }

    /** Dégradé bilinéaire (une couleur par coin). */
    default boolean gradientRect2D(float x1, float y1, float x2, float y2, float radius,
                                   UiColor colorBottomLeft, UiColor colorBottomRight,
                                   UiColor colorTopLeft, UiColor colorTopRight,
                                   int vpWidth, int vpHeight) { return false; }

    /**
     * Dégradé multi-paliers. Les tableaux reçus sont DÉJÀ normalisés à huit
     * entrées — voir {@code draw/geometry/GradientStops}, la validation est
     * faite une seule fois, en amont, pour toutes les ères.
     */
    default boolean multiStopGradientRect(float x1, float y1, float x2, float y2, float radius,
                                          com.yuyuframe.launcheragent.apigraphic.value.UiGradientType type,
                                          float startX, float startY, float endX, float endY,
                                          UiColor[] colors, float[] positions,
                                          int vpWidth, int vpHeight) { return false; }

    /**
     * Rectangle arrondi à flou et/ou contour — la primitive GÉNÉRALE dont
     * {@link #shadow} (contour nul, une seule couleur) et
     * {@link #roundedRectBorder} (flou nul) sont des cas particuliers.
     *
     * <p>Exposée parce que les formes composées de {@code draw/shape/} en ont
     * besoin : {@code glow} empile trois passes de flou décroissant, ce qu'on
     * ne peut pas exprimer avec {@code shadow} seul (celui-ci force les deux
     * couleurs identiques).
     */
    default boolean fx(float x1, float y1, float x2, float y2, float radius, float blur, float borderWidth,
                       UiColor colorA, UiColor colorB, boolean gradient,
                       int vpWidth, int vpHeight) { return false; }

    // ── Pont vers les renderers DU JEU (icônes d'objet, textures de GUI) ──
    //
    // Ces trois entrées remplacent l'ancien UiVanillaItemRenderer, qui empilait
    // les trois ères dans un seul fichier et les distinguait par des sondages à
    // l'exécution (owner.isModern(), puis « la classe GuiRenderer existe-t-elle
    // ? »). Ces sondages ont disparu : l'ère EST le dispatch, et chacune sait
    // seule comment participer au rendu vanilla de sa génération.

    /**
     * Icône RÉELLE d'un {@code ItemStack} (modèle vanilla, pas un rectangle de
     * substitution). {@code itemStack} est une poignée OPAQUE, jamais inspectée
     * par le moteur.
     *
     * <p>Coordonnées en convention du projet (coin BAS-gauche, origine
     * bas-gauche écran, pixels framebuffer) ; c'est l'ère qui convertit.
     *
     * @param vanillaExtras dessiner aussi le fond de case et la barre de
     *        durabilité vanilla
     */
    default boolean vanillaItemIcon(Object itemStack, float x, float y, float size,
                                    boolean vanillaExtras, int vpWidth, int vpHeight) { return false; }

    /**
     * Blit BRUT d'une texture GUI vanilla arbitraire — fond de fenêtre de
     * conteneur, par exemple. « Brut » = région source explicite, par
     * opposition à l'atlas de sprites.
     *
     * @param texturePath chemin RELATIF avec extension, ex.
     *        {@code "textures/gui/container/shulker_box.png"}
     * @param u,v,texW,texH région source ET dimensions RÉELLES du fichier PNG
     */
    default boolean vanillaGuiBlit(String texturePath, float x, float y, float w, float h,
                                   float u, float v, float texW, float texH,
                                   int vpWidth, int vpHeight) { return false; }

    /**
     * Cycle de vie d'une frame : un point d'accroche du jeu passe le contexte
     * de rendu VIVANT qu'il tient, et l'ère y soumet ce qu'elle a en attente.
     *
     * <p>C'est la décision que le découpage de {@code UiVanillaItemRenderer}
     * attendait. Le moteur ne peut pas aller chercher ce contexte : il n'existe
     * qu'à l'intérieur d'une méthode du jeu, le temps de quelques
     * microsecondes. Le hook le DONNE, {@link VanillaFlushHost} dit ce que
     * c'est, et l'ère ignore les hôtes qui ne sont pas les siens.
     */
    default void flushVanillaFrame(com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaFlushHost host,
                                   Object hostObject) {}

    /** Ouvre un clip à coins arrondis (stencil). */
    default boolean beginRoundedClip(float x1, float y1, float x2, float y2, float radius,
                                     int vpWidth, int vpHeight) { return false; }

    /** Ferme le clip ouvert par {@link #beginRoundedClip}. */
    default boolean endRoundedClip() { return false; }

    /**
     * Rectangle à coins arrondis.
     *
     * @return {@code true} si l'ère l'a pris en charge ; {@code false} pour
     *         laisser l'appelant continuer sur son chemin historique
     */
    boolean roundedRect(float x1, float y1, float x2, float y2, float radius,
                        UiColor color, int vpWidth, int vpHeight);
}
