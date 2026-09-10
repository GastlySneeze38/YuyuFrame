package com.yuyuframe.launcheragent.apigraphic.render;

import com.yuyuframe.launcheragent.apigraphic.backend.UiBackendRegistry;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.GlBridge;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

/**
 * Ce qui restait de l'ancien fichier à deux ères, une fois la séparation
 * gl2/gl3 terminée (2026-09-10) : le <b>clip à coins arrondis par stencil</b>,
 * et deux entrées de rect arrondi qui repassent par le registre de backends.
 *
 * <p>Il n'y a plus une seule ligne de GLSL ici, plus un seul champ de
 * programme, plus un seul {@code owner.isModern()} qui décide d'un dessin :
 * les six effets (rect arrondi, vignette, icône, dégradé bilinéaire, dégradé
 * multi-paliers, FX) vivent dans {@code era/gl2/Gl2PrimitiveRenderer} et
 * {@code era/gl3/Gl3PrimitiveRenderer}, chacun câblé sur son backend.
 *
 * <p>Le clip stencil reste ici parce qu'il est <b>identique dans les deux
 * ères</b> : {@code glEnable(GL_STENCIL_TEST)}, un masque écrit par un rect
 * arrondi, puis {@code glStencilFunc(GL_EQUAL, …)}. Rien à dupliquer par ère
 * — seul le rect qui peuple le masque est spécifique, et il passe par le
 * registre comme n'importe quel autre appelant.
 */
public final class UiPrimitiveRenderer {

    private final UiRenderer owner;
    private final GlBridge gl;

    public UiPrimitiveRenderer(UiRenderer owner, GlBridge gl) {
        this.owner = owner;
        this.gl = gl;
    }

    // ── Diagnostic gamma/espace colorimétrique (roadmap Phase 5.4) ──────────
    //
    // Investigation faite : AUCUN des 3 pipelines (legacy/modern/Blaze3D) ne
    // touche à un format de texture/framebuffer sRGB ni à une conversion
    // gamma quelconque (grep sur GL_SRGB*/GL_FRAMEBUFFER_SRGB/TextureFormat
    // sRGB dans tout apigraphic/ : zéro résultat) — Blaze3D utilise
    // explicitement TextureFormat.RGBA8 (non-sRGB, voir
    // Blaze3DCore#fieldTextureFormatRgba8), donc CÔTÉ NOTRE CODE, les 3
    // backends traitent les couleurs de façon strictement identique (mélange
    // linéaire naïf sur des valeurs gamma-encodées, comme la quasi-totalité
    // des moteurs d'UI 2D — pas une erreur en soi, juste pas "physiquement
    // correct" au sens rendu 3D/PBR).
    //
    // Ce que notre code NE PEUT PAS voir : si Minecraft lui-même active
    // GL_FRAMEBUFFER_SRGB (conversion sRGB→linéaire AUTOMATIQUE côté GPU à
    // l'écriture, invisible à notre niveau) différemment selon le bracket —
    // plausible (Mojang a fait évoluer son pipeline de rendu au fil des
    // versions) et EXACTEMENT le genre de divergence "dégradés délavés/trop
    // saturés selon la version" que cet item de roadmap visait, mais
    // impossible à confirmer sans lancer les DEUX brackets côte à côte. Log
    // UNE FOIS l'état réel (au lieu de le deviner) — donnée de diagnostic
    // prête pour une investigation future si le symptôme est un jour signalé.
    private static volatile boolean srgbDiagLogged;

    private void logSrgbDiagOnce() {
        if (srgbDiagLogged) return;
        srgbDiagLogged = true;
        try {
            boolean srgbEnabled = gl.glIsEnabled(0x8DB9); // GL_FRAMEBUFFER_SRGB
            LauncherLog.info("[UiRenderer] Diagnostic gamma (Phase 5.4) : GL_FRAMEBUFFER_SRGB="
                + srgbEnabled + " (bracket " + (owner.isModern() ? "moderne" : "legacy") + ")");
        } catch (Throwable ignored) {
            // Blaze3D (era E) : pas de GL brut à interroger ainsi, ignoré silencieusement — Blaze3DCore confirme déjà TextureFormat.RGBA8 par construction.
        }
    }



    // ══════════════════════════════════════════════════════════════════════
    // Séparation gl2/gl3 TERMINÉE le 2026-09-10 : les six effets (rect
    // arrondi, vignette, icône, dégradé bilinéaire, dégradé multi-paliers,
    // FX) sont partis avec LEURS shaders, LEURS programmes et LEURS uniformes
    // vers era/gl2/Gl2PrimitiveRenderer et era/gl3/Gl3PrimitiveRenderer, un
    // effet à la fois, la main, avec une compilation verte après chacun.
    //
    // Ce qui reste ici ne connaît plus aucune ère : le clip stencil (du GL
    // brut identique des deux côtés) et deux compositions qui repassent par
    // le registre de backends.
    // ══════════════════════════════════════════════════════════════════════


    /**
     * Dessine un rect avec coins arrondis, en pixels physiques écran (x1,y1)-(x2,y2).
     * radius=0 → rect plein classique. Sur era E, route par le pipeline
     * Blaze3D (même z-order garanti que le texte, voir ci-dessus) ; sur les
     * autres brackets, GL classique immédiat (comportement inchangé, aucun
     * risque de régression) — fallback silencieux vers un quad plein (pas
     * d'arrondi) si la compilation shader a échoué sur cette version/GPU.
     *
     * @param vpWidth  largeur totale du viewport (framebuffer), PAS la largeur
     *                 de ce rect précis — nécessaire pour poser une projection
     *                 orthographique correcte (voir plus bas), indépendamment
     *                 de la taille du rect dessiné.
     * @param vpHeight idem, hauteur totale du viewport.
     */
    public void drawRoundedRect(float x1, float y1, float x2, float y2, float radius, UiColor color,
                                 int vpWidth, int vpHeight) {
        logSrgbDiagOnce();
        // PREMIER effet entièrement découpé par ère (2026-09-10) : le code GL
        // vit désormais dans era/gl2/Gl2PrimitiveRenderer et
        // era/gl3/Gl3PrimitiveRenderer, chacun câblé sur son backend.
        //
        // On repasse par le registre plutôt que par owner.drawRoundedRect() —
        // celui-ci interroge déjà le backend PUIS retombe ici, donc l'appeler
        // d'ici serait une récursion infinie dès que le backend décline. Les
        // appelants qui arrivent encore là (composition par coin, masque de
        // clip stencil) doivent quand même atteindre l'ère.
        UiBackendRegistry.get().roundedRect(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
    }

    /**
     * Rayon PAR COIN — {@code topLeft}/{@code topRight} près de {@code y1},
     * {@code bottomLeft}/{@code bottomRight} près de {@code y2} (convention
     * de l'appelant : {@code y1 < y2}, {@code y1}="haut" du widget tel qu'il
     * l'écrit, {@code y2}="bas" — voir {@link Blaze3DCore#RECT_FRAGMENT_SRC}
     * pour pourquoi c'est robuste indépendamment de l'axe GL). Vrai support
     * shader SEULEMENT sur Blaze3D (era E) ; legacy/modern GL n'ont pas ce
     * réglage par coin dans leur shader — repli sur l'ancien hack "rect
     * arrondi + rect plat par-dessus" (comportement inchangé là-bas, pas la
     * cible du bug signalé).
     */
    public void drawRoundedRect(float x1, float y1, float x2, float y2,
                                 float radiusTopLeft, float radiusTopRight, float radiusBottomLeft, float radiusBottomRight,
                                 UiColor color, int vpWidth, int vpHeight) {
        float maxRadius = Math.max(Math.max(radiusTopLeft, radiusTopRight), Math.max(radiusBottomLeft, radiusBottomRight));
        drawRoundedRect(x1, y1, x2, y2, maxRadius, color, vpWidth, vpHeight);
        float splitY = y1 + maxRadius;
        if (splitY < y2) {
            if (radiusTopLeft < maxRadius || radiusTopRight < maxRadius) {
                drawRoundedRect(x1, y1, x2, splitY, 0f, color, vpWidth, vpHeight);
            }
            if (radiusBottomLeft < maxRadius || radiusBottomRight < maxRadius) {
                drawRoundedRect(x1, splitY, x2, y2, 0f, color, vpWidth, vpHeight);
            }
        }
    }

    /** @deprecated identique à {@link #drawRoundedRect} depuis que celui-ci route par Blaze3D sur era E — gardé pour ne pas retoucher HudPanelRenderer/KeystrokesModule. */
    @Deprecated
    public void drawRoundedRectHud(float x1, float y1, float x2, float y2, float radius, UiColor color,
                                    int vpWidth, int vpHeight) {
        drawRoundedRect(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
    }

    // L'ombre portée et le contour creux sont partis le 2026-09-10 dans
    // UiRenderer : ce sont des COMPOSITIONS sur le FX (spread = boîte
    // agrandie avant flou ; contour = flou nul, épaisseur non nulle), pas du
    // code d'ère. Elles y sont écrites une fois, au-dessus du contrat de
    // backend, et n'ont plus rien à faire dans un fichier d'implémentation.

    // ── Clip aux coins arrondis via stencil buffer (roadmap Phase 5.1) ──────
    //
    // Remplace glScissor (rectangle axis-aligned strict) pour les cas où la
    // zone de clip doit suivre un rect ARRONDI (modale/dropdown/scroll à
    // coins arrondis) — avec glScissor seul, le contenu clippé se fait
    // couper en angle droit pile sur un coin qui, lui, est visuellement
    // arrondi : un défaut visible (coin "carré dans un coin rond").
    //
    // Legacy/modern GL UNIQUEMENT (comme drawShadow/drawGlow) — sur Blaze3D
    // (era E), les dessins passent par une file DIFFÉRÉE (Blaze3DCore.queued,
    // exécutée une frame plus tard, voir UiScrollContainer §javadoc) : un
    // vrai stencil GPU nécessiterait de faire traverser une région de clip à
    // travers CHAQUE lambda empilée jusqu'à un RenderPass Blaze3D — écarté
    // pour la même raison déjà actée pour le scissor Blaze3D (trop risqué à
    // l'aveugle, aucun moyen de tester en jeu depuis cet environnement sur un
    // pipeline déjà fragile). No-op silencieux là-bas — clipFade
    // (UiScrollContainer) reste le filet de sécurité existant sur ce bracket.
    //
    // "Anti-aliasé" au sens : la frontière du masque SUIT la courbe analytique
    // du rect arrondi pixel par pixel (même shader FRAGMENT_SRC que
    // drawRoundedRect, avec son propre smoothstep 1px — un fragment sous le
    // seuil alpha est discard, donc jamais écrit dans le stencil), PAS un
    // blend sub-pixel : le stencil buffer reste un masque entier 0/1, comme
    // toute technique stencil — largement suffisant pour éliminer le vrai
    // défaut visible actuel (coin carré qui mord sur un coin rond).
    private static final int GL_STENCIL_TEST = 0x0B90;
    private static final int GL_STENCIL_BUFFER_BIT = 0x00000400;
    private static final int GL_ALWAYS = 0x0207;
    private static final int GL_EQUAL = 0x0202;
    private static final int GL_KEEP = 0x1E00;
    private static final int GL_REPLACE = 0x1E01;

    private boolean roundedClipActive;

    /**
     * Démarre un clip à coins arrondis — tout dessin ENTRE cet appel et
     * {@link #endRoundedClip()} n'est visible que dans le rect arrondi donné.
     * No-op sur Blaze3D (era E) — voir le commentaire de section ci-dessus.
     */
    public void beginRoundedClip(float x1, float y1, float x2, float y2, float radius, int vpWidth, int vpHeight) {
        try {
            gl.glEnable(GL_STENCIL_TEST);
            // Passe 1 : écrit le masque, sans toucher au framebuffer couleur
            // ni au test de profondeur (on ne dessine ici QUE pour peupler le
            // stencil, pas pour afficher quoi que ce soit).
            gl.glClear(GL_STENCIL_BUFFER_BIT);
            gl.glColorMask(false, false, false, false);
            gl.glStencilFunc(GL_ALWAYS, 1, 0xFF);
            gl.glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
            gl.glStencilMask(0xFF);
            // Couleur/alpha réels sans importance (glColorMask bloque toute
            // écriture couleur) SAUF l'alpha, qui doit rester >= le seuil de
            // discard du shader (drawRoundedRect) pour que le stencil soit
            // bien écrit PARTOUT à l'intérieur de la forme arrondie.
            drawRoundedRect(x1, y1, x2, y2, radius, new UiColor(1f, 1f, 1f, 1f), vpWidth, vpHeight);
            // Passe 2 : réactive l'écriture couleur, seul le stencil déjà
            // posé (==1) laisse désormais passer les dessins suivants.
            gl.glColorMask(true, true, true, true);
            gl.glStencilFunc(GL_EQUAL, 1, 0xFF);
            gl.glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            gl.glStencilMask(0x00);
            roundedClipActive = true;
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] beginRoundedClip: " + t);
            roundedClipActive = false;
        }
    }

    /** Referme le clip ouvert par {@link #beginRoundedClip}. No-op si aucun clip actif (sur Blaze3D, ou si {@code beginRoundedClip} a échoué). */
    public void endRoundedClip() {
        if (!roundedClipActive) return;
        roundedClipActive = false;
        try {
            gl.glStencilMask(0xFF);
            gl.glDisable(GL_STENCIL_TEST);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] endRoundedClip: " + t);
        }
    }

    // Les deux dégradés (bilinéaire 4 coins, multi-paliers) sont partis le
    // 2026-09-10 dans era/gl2 et era/gl3, avec leur documentation.
}
