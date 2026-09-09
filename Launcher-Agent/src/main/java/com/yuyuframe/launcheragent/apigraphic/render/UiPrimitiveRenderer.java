package com.yuyuframe.launcheragent.apigraphic.render;

import com.yuyuframe.launcheragent.apigraphic.era.glsupport.GlBridge;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiGradientType;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DCore;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DGradient;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DRect;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Rendu de rects arrondis / vignette / ombre-contour-dégradé ("FX") / dégradé
 * bilinéaire / icônes RGBA génériques via shader GLSL — extrait de UiRenderer
 * (voir sa javadoc de classe pour l'architecture générale à 2 pipelines et le
 * pourquoi de la technique, même approche qu'Elementa/UIRoundedRectangle.kt).
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



    private static final String VERTEX_SRC =
        "void main() {\n" +
        "    gl_Position = ftransform();\n" +
        "    gl_FrontColor = gl_Color;\n" +
        "}\n";

    // u_Rect = (left, top, right, bottom) en coordonnées écran (mêmes unités
    // que gl_FragCoord, donc "scaled" GUI * scaleFactor — l'appelant doit
    // passer des coordonnées déjà en pixels physiques, pas en unités GUI).
    private static final String FRAGMENT_SRC =
        "uniform vec4 u_Rect;\n" +
        "uniform float u_Radius;\n" +
        "void main() {\n" +
        "    vec2 p = gl_FragCoord.xy;\n" +
        "    vec2 innerMin = u_Rect.xy + vec2(u_Radius);\n" +
        "    vec2 innerMax = u_Rect.zw - vec2(u_Radius);\n" +
        "    vec2 clamped = clamp(p, innerMin, innerMax);\n" +
        "    float dist = length(p - clamped);\n" +
        "    float alpha = 1.0 - smoothstep(u_Radius - 1.0, u_Radius, dist);\n" +
        "    gl_FragColor = vec4(gl_Color.rgb, gl_Color.a * alpha);\n" +
        "}\n";

    private int rectProgram = -1;
    private int uRect = -1;
    private int uRadius = -1;
    private boolean rectInitFailed = false;

    // ── Shader de vignette (dégradé continu depuis les 4 bords) — voir
    // LowHealthTintModule : dessiner le dégradé comme des bandes de rects
    // empilées (seule option sans shader dédié) produit des paliers visibles
    // à l'œil nu (l'alpha change par MARCHES, pas en continu), même une fois
    // le chevauchement des coins corrigé. Ici l'alpha de CHAQUE PIXEL est
    // calculé directement par le GPU à partir de sa distance au bord le plus
    // proche — un seul quad plein écran, dégradé parfaitement lisse, et les
    // coins se traitent naturellement (min des 4 distances, jamais de double
    // comptage contrairement à des rects superposés).
    private static final String VIGNETTE_FRAGMENT_SRC =
        "uniform vec2 u_ViewportSize;\n" +
        "uniform float u_VSize;\n" +
        "void main() {\n" +
        "    vec2 p = gl_FragCoord.xy;\n" +
        "    float distTop = u_ViewportSize.y - p.y;\n" +
        "    float distBottom = p.y;\n" +
        "    float distLeft = p.x;\n" +
        "    float distRight = u_ViewportSize.x - p.x;\n" +
        "    float distEdge = min(min(distTop, distBottom), min(distLeft, distRight));\n" +
        // Revenu à smootherstep (Ken Perlin, 6t^5-15t^4+10t^3) — la tentative
        // "ease-out" (1-t)^3 n'était pas nécessaire : la vraie cause du bord
        // net était GL_ALPHA_TEST resté actif (rejet binaire des pixels sous
        // ~10% d'alpha, voir plus bas/pushAttrib), pas la forme de la courbe.
        // smootherstep reste la référence standard pour ce type de dégradé
        // (dérivée première ET seconde nulles aux deux bornes).
        "    float t = clamp(distEdge / u_VSize, 0.0, 1.0);\n" +
        "    float eased = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);\n" +
        "    float alpha = 1.0 - eased;\n" +
        // Le framebuffer ne code que 256 niveaux par canal — même une courbe
        // parfaitement lisse en maths QUANTIFIE en un nombre limité de paliers
        // réellement affichables sur une zone large/fort contraste (encore
        // visible en jeu après smootherstep). NanoVG (PvP-Mod) anticrénèle en
        // interne, on n'a pas cet équivalent ici — on ajoute donc un bruit
        // (dithering, hash pseudo-aléatoire par pixel) de l'ordre d'1 LSB pour
        // casser les paliers résiduels, technique standard contre le banding
        // sur les dégradés écran (ciel, vignette...).
        "    float dither = fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;\n" +
        "    alpha = clamp(alpha + dither / 128.0, 0.0, 1.0);\n" +
        "    gl_FragColor = vec4(gl_Color.rgb, gl_Color.a * alpha);\n" +
        "}\n";

    private int vignetteProgram = -1;
    private int uViewportSize = -1;
    private int uVSize = -1;
    private boolean vignetteInitFailed = false;

    // ── Shader "FX" (ombre portée / contour / dégradé) — un seul shader pour
    // les 3 effets, tous dérivés de la MÊME distance signée à un rectangle
    // arrondi (formule Inigo Quilez : contrairement au shader rect ci-dessus,
    // dont le calcul de distance n'est correct QUE près des coins arrondis
    // — les bords droits ont toujours dist=0 —, celle-ci donne une distance
    // signée valide PARTOUT, nécessaire pour un contour d'épaisseur uniforme
    // sur les 4 côtés et un flou d'ombre cohérent) :
    //  - u_BorderWidth > 0  → contour creux (anneau de cette épaisseur)
    //  - u_Blur > 0 (et u_BorderWidth == 0) → bord adouci sur u_Blur pixels
    //    (ombre portée façon CSS box-shadow — u_Rect déjà agrandi du "spread"
    //    par l'appelant, pas géré ici)
    //  - u_Gradient > 0.5 → interpole u_ColorA (bord y1) → u_ColorB (bord y2)
    //    verticalement, au lieu de la couleur plate u_ColorA
    private static final String FX_FRAGMENT_SRC =
        "uniform vec4 u_Rect;\n" +
        "uniform float u_Radius;\n" +
        "uniform float u_Blur;\n" +
        "uniform float u_BorderWidth;\n" +
        "uniform vec4 u_ColorA;\n" +
        "uniform vec4 u_ColorB;\n" +
        "uniform float u_Gradient;\n" +
        "void main() {\n" +
        "    vec2 center = (u_Rect.xy + u_Rect.zw) * 0.5;\n" +
        "    vec2 halfSize = (u_Rect.zw - u_Rect.xy) * 0.5;\n" +
        "    vec2 p = gl_FragCoord.xy - center;\n" +
        "    vec2 d = abs(p) - halfSize + u_Radius;\n" +
        "    float dist = length(max(d, vec2(0.0))) + min(max(d.x, d.y), 0.0) - u_Radius;\n" +
        "    float alpha;\n" +
        "    if (u_BorderWidth > 0.0) {\n" +
        "        float outer = 1.0 - smoothstep(-1.0, 0.0, dist);\n" +
        "        float inner = 1.0 - smoothstep(-1.0, 0.0, dist + u_BorderWidth);\n" +
        "        alpha = outer - inner;\n" +
        "    } else {\n" +
        "        float b = max(u_Blur, 1.0);\n" +
        "        alpha = 1.0 - smoothstep(-b, b, dist);\n" +
        "    }\n" +
        "    vec3 rgb = u_ColorA.rgb;\n" +
        "    if (u_Gradient > 0.5) {\n" +
        "        float t = clamp((gl_FragCoord.y - u_Rect.y) / max(u_Rect.w - u_Rect.y, 1.0), 0.0, 1.0);\n" +
        "        rgb = mix(u_ColorA.rgb, u_ColorB.rgb, t);\n" +
        "    }\n" +
        "    gl_FragColor = vec4(rgb, u_ColorA.a * alpha);\n" +
        "}\n";

    private int fxProgram = -1;
    private int uFxRect = -1, uFxRadius = -1, uFxBlur = -1, uFxBorderWidth = -1, uFxColorA = -1, uFxColorB = -1, uFxGradient = -1;
    private boolean fxInitFailed = false;

    // ── Shader "Gradient2D" — dégradé BILINÉAIRE entre 4 couleurs de coin,
    // capacité moteur générique ajoutée sur demande explicite ("fait la
    // partie du moteur qui fait un dégradé 2D"). Contrairement au shader FX
    // ci-dessus (dégradé 1D vertical SEULEMENT, u_ColorA/u_ColorB), celui-ci
    // interpole horizontalement PUIS verticalement entre 4 couleurs
    // indépendantes — usage typique : un vrai carré Saturation/Luminosité de
    // color picker (coin bas-gauche ET bas-droite = noir, haut-gauche =
    // blanc, haut-droite = la teinte pleine à saturation/luminosité
    // maximales) — un dégradé BILINÉAIRE entre ces 4 coins précis est
    // mathématiquement IDENTIQUE à la formule HSB->RGB standard à teinte
    // fixe (pas juste une approximation visuelle : à luminosité v et
    // saturation s, HSBtoRGB(h,s,v) == v * lerp(blanc, HSBtoRGB(h,1,1), s),
    // et les deux coins du bas valent 0 dans les deux cas puisque v=0 →
    // noir quelle que soit la saturation).
    //
    // Même masque de coin arrondi (SDF, formule Inigo Quilez) que
    // drawRoundedRect — bord NET anti-aliasé 1px, PAS de flou (contrairement
    // au shader FX, pensé lui pour l'ombre portée/le contour).
    private static final String GRADIENT2D_FRAGMENT_SRC =
        "uniform vec4 u_Rect;\n" +
        "uniform float u_Radius;\n" +
        "uniform vec4 u_ColorBL;\n" +
        "uniform vec4 u_ColorBR;\n" +
        "uniform vec4 u_ColorTL;\n" +
        "uniform vec4 u_ColorTR;\n" +
        "void main() {\n" +
        "    vec2 center = (u_Rect.xy + u_Rect.zw) * 0.5;\n" +
        "    vec2 halfSize = (u_Rect.zw - u_Rect.xy) * 0.5;\n" +
        "    vec2 p = gl_FragCoord.xy - center;\n" +
        "    vec2 d = abs(p) - halfSize + u_Radius;\n" +
        "    float dist = length(max(d, vec2(0.0))) + min(max(d.x, d.y), 0.0) - u_Radius;\n" +
        "    float alpha = 1.0 - smoothstep(-1.0, 0.0, dist);\n" +
        "    float u = clamp((gl_FragCoord.x - u_Rect.x) / max(u_Rect.z - u_Rect.x, 1.0), 0.0, 1.0);\n" +
        "    float v = clamp((gl_FragCoord.y - u_Rect.y) / max(u_Rect.w - u_Rect.y, 1.0), 0.0, 1.0);\n" +
        "    vec4 bottom = mix(u_ColorBL, u_ColorBR, u);\n" +
        "    vec4 top = mix(u_ColorTL, u_ColorTR, u);\n" +
        "    vec4 col = mix(bottom, top, v);\n" +
        "    gl_FragColor = vec4(col.rgb, col.a * alpha);\n" +
        "}\n";

    private int gradient2DProgram = -1;
    private int uG2dRect = -1, uG2dRadius = -1, uG2dColorBL = -1, uG2dColorBR = -1, uG2dColorTL = -1, uG2dColorTR = -1;
    private boolean gradient2DInitFailed = false;

    // ── Shader "MultiStopGradient" — dégradé LINÉAIRE/RADIAL/CONIQUE à N
    // stops (roadmap Phase 5.1, "extension du shader Gradient2D existant" —
    // capacité ajoutée en famille, PAS une fusion dans le même programme
    // GLSL : Gradient2D fait un blend BILINÉAIRE à 4 coins, un mécanisme
    // fondamentalement différent d'un balayage à N stops le long d'un axe/
    // rayon/angle — les deux restent des shaders séparés, comme FX/Gradient2D
    // le sont déjà l'un de l'autre).
    //
    // Paramétrage commun aux 3 types (voir UiGradientType) :
    //   u_Start/u_End définissent l'axe — LINEAR : t=0/t=1 ; RADIAL : centre/
    //   point qui fixe le rayon (= |end-start|) ; CONIC : centre/direction de
    //   l'angle "0".
    //
    // 8 stops maximum, en uniforms NOMMÉS INDIVIDUELLEMENT (u_Stop0Color..
    // u_Stop7Color / u_Stop0Pos..u_Stop7Pos) plutôt qu'un tableau uniform —
    // l'indexation DYNAMIQUE d'un tableau uniform dans un fragment shader
    // n'est PAS garantie par GLSL 1.10 (le bracket "legacy" cible du matériel
    // ancien, 1.8.9) ; beaucoup de compilateurs déroulent une boucle à borne
    // constante et ça fonctionnerait probablement, mais invérifiable sans
    // accès à du matériel d'époque — la chaîne if/else entièrement dépliée
    // ci-dessous est portable par construction, aucune hypothèse à faire.
    //
    // Stops inutilisés (au-delà de stopCount) : le CÔTÉ JAVA les remplit avec
    // la position/couleur du DERNIER stop réel (voir drawMultiStopGradient*)
    // — la chaîne if/else résout alors TOUJOURS sur la bonne couleur via la
    // clause finale "else", sans qu'aucune branche shader n'ait besoin de
    // connaître le nombre réel de stops.
    private static final String MULTISTOP_GRADIENT_FRAGMENT_SRC =
        "uniform vec4 u_Rect;\n" +
        "uniform float u_Radius;\n" +
        "uniform float u_GradType;\n" +
        "uniform vec2 u_Start;\n" +
        "uniform vec2 u_End;\n" +
        "uniform vec4 u_Stop0Color;\n" + "uniform float u_Stop0Pos;\n" +
        "uniform vec4 u_Stop1Color;\n" + "uniform float u_Stop1Pos;\n" +
        "uniform vec4 u_Stop2Color;\n" + "uniform float u_Stop2Pos;\n" +
        "uniform vec4 u_Stop3Color;\n" + "uniform float u_Stop3Pos;\n" +
        "uniform vec4 u_Stop4Color;\n" + "uniform float u_Stop4Pos;\n" +
        "uniform vec4 u_Stop5Color;\n" + "uniform float u_Stop5Pos;\n" +
        "uniform vec4 u_Stop6Color;\n" + "uniform float u_Stop6Pos;\n" +
        "uniform vec4 u_Stop7Color;\n" + "uniform float u_Stop7Pos;\n" +
        "float segT(float t, float p0, float p1) {\n" +
        "    float span = p1 - p0;\n" +
        "    return span < 1e-6 ? 0.0 : clamp((t - p0) / span, 0.0, 1.0);\n" +
        "}\n" +
        "void main() {\n" +
        "    vec2 center = (u_Rect.xy + u_Rect.zw) * 0.5;\n" +
        "    vec2 halfSize = (u_Rect.zw - u_Rect.xy) * 0.5;\n" +
        "    vec2 p = gl_FragCoord.xy - center;\n" +
        "    vec2 d = abs(p) - halfSize + u_Radius;\n" +
        "    float dist = length(max(d, vec2(0.0))) + min(max(d.x, d.y), 0.0) - u_Radius;\n" +
        "    float alpha = 1.0 - smoothstep(-1.0, 0.0, dist);\n" +
        "    float t;\n" +
        "    if (u_GradType < 0.5) {\n" +
        "        vec2 dir = u_End - u_Start;\n" +
        "        float len2 = dot(dir, dir);\n" +
        "        t = len2 < 1e-6 ? 0.0 : dot(gl_FragCoord.xy - u_Start, dir) / len2;\n" +
        "    } else if (u_GradType < 1.5) {\n" +
        "        float rad = length(u_End - u_Start);\n" +
        "        t = rad < 1e-6 ? 0.0 : length(gl_FragCoord.xy - u_Start) / rad;\n" +
        "    } else {\n" +
        "        vec2 dir = u_End - u_Start;\n" +
        "        float baseAngle = atan(dir.y, dir.x);\n" +
        "        vec2 q = gl_FragCoord.xy - u_Start;\n" +
        "        float ang = (atan(q.y, q.x) - baseAngle) / 6.28318530718;\n" +
        "        t = ang - floor(ang);\n" +
        "    }\n" +
        "    t = clamp(t, 0.0, 1.0);\n" +
        "    vec4 col;\n" +
        "    if (t <= u_Stop0Pos) col = u_Stop0Color;\n" +
        "    else if (t <= u_Stop1Pos) col = mix(u_Stop0Color, u_Stop1Color, segT(t, u_Stop0Pos, u_Stop1Pos));\n" +
        "    else if (t <= u_Stop2Pos) col = mix(u_Stop1Color, u_Stop2Color, segT(t, u_Stop1Pos, u_Stop2Pos));\n" +
        "    else if (t <= u_Stop3Pos) col = mix(u_Stop2Color, u_Stop3Color, segT(t, u_Stop2Pos, u_Stop3Pos));\n" +
        "    else if (t <= u_Stop4Pos) col = mix(u_Stop3Color, u_Stop4Color, segT(t, u_Stop3Pos, u_Stop4Pos));\n" +
        "    else if (t <= u_Stop5Pos) col = mix(u_Stop4Color, u_Stop5Color, segT(t, u_Stop4Pos, u_Stop5Pos));\n" +
        "    else if (t <= u_Stop6Pos) col = mix(u_Stop5Color, u_Stop6Color, segT(t, u_Stop5Pos, u_Stop6Pos));\n" +
        "    else if (t <= u_Stop7Pos) col = mix(u_Stop6Color, u_Stop7Color, segT(t, u_Stop6Pos, u_Stop7Pos));\n" +
        "    else col = u_Stop7Color;\n" +
        "    gl_FragColor = vec4(col.rgb, col.a * alpha);\n" +
        "}\n";

    private int multiStopGradientProgram = -1;
    private int uMsgRect = -1, uMsgRadius = -1, uMsgGradType = -1, uMsgStart = -1, uMsgEnd = -1;
    private final int[] uMsgStopColor = new int[8], uMsgStopPos = new int[8];
    private boolean multiStopGradientInitFailed = false;

    // ══════════════════════════════════════════════════════════════════════
    // ── Pipeline MODERNE (1.21.11+) — voir javadoc de UiRenderer.
    // ══════════════════════════════════════════════════════════════════════

    private static final String FRAGMENT_SRC_MODERN =
        "#version 150\n" +
        "uniform vec4 u_Rect;\n" +
        "uniform float u_Radius;\n" +
        "uniform vec4 uColor;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec2 p = gl_FragCoord.xy;\n" +
        "    vec2 innerMin = u_Rect.xy + vec2(u_Radius);\n" +
        "    vec2 innerMax = u_Rect.zw - vec2(u_Radius);\n" +
        "    vec2 clamped = clamp(p, innerMin, innerMax);\n" +
        "    float dist = length(p - clamped);\n" +
        "    float alpha = 1.0 - smoothstep(u_Radius - 1.0, u_Radius, dist);\n" +
        "    fragColor = vec4(uColor.rgb, uColor.a * alpha);\n" +
        "}\n";

    /**
     * Shader "couleur plate" — AUCUN calcul de distance/alpha, juste
     * {@code fragColor = uColor} tel quel. Nécessaire pour radius&lt;=0 (rect
     * plein sans coins arrondis) : réutiliser le shader vignette avec un
     * {@code u_VSize} proche de 0 (tentative initiale) est FAUX — ce shader
     * calcule un dégradé du BORD vers le CENTRE (opaque au bord, transparent
     * au centre), l'inverse de ce qu'il faut ; avec u_VSize≈0 le dégradé
     * atteint alpha≈0 quasi partout (t=distEdge/u_VSize sature à 1 dès que
     * distEdge dépasse quelques centièmes de pixel) → rect INVISIBLE partout
     * sauf littéralement sur son contour. Confirmé en jeu (carré de test
     * radius=0 invisible) — voir historique du projet.
     */
    private static final String FLAT_FRAGMENT_SRC_MODERN =
        "#version 150\n" +
        "uniform vec4 uColor;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    fragColor = uColor;\n" +
        "}\n";

    private int flatProgramModern = -1;
    private int uColorFlatModern = -1, uProjectionFlatModern = -1;
    private boolean flatInitFailedModern = false;

    private int rectProgramModern = -1;
    private int uRectModern = -1, uRadiusModern = -1, uColorRectModern = -1, uProjectionRectModern = -1;
    private boolean rectInitFailedModern = false;

    private static final String VIGNETTE_FRAGMENT_SRC_MODERN =
        "#version 150\n" +
        "uniform vec2 u_ViewportSize;\n" +
        "uniform float u_VSize;\n" +
        "uniform vec4 uColor;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec2 p = gl_FragCoord.xy;\n" +
        "    float distTop = u_ViewportSize.y - p.y;\n" +
        "    float distBottom = p.y;\n" +
        "    float distLeft = p.x;\n" +
        "    float distRight = u_ViewportSize.x - p.x;\n" +
        "    float distEdge = min(min(distTop, distBottom), min(distLeft, distRight));\n" +
        "    float t = clamp(distEdge / u_VSize, 0.0, 1.0);\n" +
        "    float eased = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);\n" +
        "    float alpha = 1.0 - eased;\n" +
        "    float dither = fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;\n" +
        "    alpha = clamp(alpha + dither / 128.0, 0.0, 1.0);\n" +
        "    fragColor = vec4(uColor.rgb, uColor.a * alpha);\n" +
        "}\n";

    private int vignetteProgramModern = -1;
    private int uViewportSizeModern = -1, uVSizeModern = -1, uColorVignetteModern = -1, uProjectionVignetteModern = -1;
    private boolean vignetteInitFailedModern = false;

    // ── Shader "FX" moderne — même logique que FX_FRAGMENT_SRC (legacy), voir
    // son commentaire pour le détail des 3 modes (contour/ombre/dégradé).
    private static final String FX_FRAGMENT_SRC_MODERN =
        "#version 150\n" +
        "uniform vec4 u_Rect;\n" +
        "uniform float u_Radius;\n" +
        "uniform float u_Blur;\n" +
        "uniform float u_BorderWidth;\n" +
        "uniform vec4 u_ColorA;\n" +
        "uniform vec4 u_ColorB;\n" +
        "uniform float u_Gradient;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec2 center = (u_Rect.xy + u_Rect.zw) * 0.5;\n" +
        "    vec2 halfSize = (u_Rect.zw - u_Rect.xy) * 0.5;\n" +
        "    vec2 p = gl_FragCoord.xy - center;\n" +
        "    vec2 d = abs(p) - halfSize + u_Radius;\n" +
        "    float dist = length(max(d, vec2(0.0))) + min(max(d.x, d.y), 0.0) - u_Radius;\n" +
        "    float alpha;\n" +
        "    if (u_BorderWidth > 0.0) {\n" +
        "        float outer = 1.0 - smoothstep(-1.0, 0.0, dist);\n" +
        "        float inner = 1.0 - smoothstep(-1.0, 0.0, dist + u_BorderWidth);\n" +
        "        alpha = outer - inner;\n" +
        "    } else {\n" +
        "        float b = max(u_Blur, 1.0);\n" +
        "        alpha = 1.0 - smoothstep(-b, b, dist);\n" +
        "    }\n" +
        "    vec3 rgb = u_ColorA.rgb;\n" +
        "    if (u_Gradient > 0.5) {\n" +
        "        float t = clamp((gl_FragCoord.y - u_Rect.y) / max(u_Rect.w - u_Rect.y, 1.0), 0.0, 1.0);\n" +
        "        rgb = mix(u_ColorA.rgb, u_ColorB.rgb, t);\n" +
        "    }\n" +
        "    fragColor = vec4(rgb, u_ColorA.a * alpha);\n" +
        "}\n";

    private int fxProgramModern = -1;
    private int uFxRectModern = -1, uFxRadiusModern = -1, uFxBlurModern = -1, uFxBorderWidthModern = -1,
        uFxColorAModern = -1, uFxColorBModern = -1, uFxGradientModern = -1, uProjectionFxModern = -1;
    private boolean fxInitFailedModern = false;

    // ── Shader "Gradient2D" moderne — même logique que GRADIENT2D_FRAGMENT_SRC
    // (legacy), voir son commentaire pour le détail/le pourquoi.
    private static final String GRADIENT2D_FRAGMENT_SRC_MODERN =
        "#version 150\n" +
        "uniform vec4 u_Rect;\n" +
        "uniform float u_Radius;\n" +
        "uniform vec4 u_ColorBL;\n" +
        "uniform vec4 u_ColorBR;\n" +
        "uniform vec4 u_ColorTL;\n" +
        "uniform vec4 u_ColorTR;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec2 center = (u_Rect.xy + u_Rect.zw) * 0.5;\n" +
        "    vec2 halfSize = (u_Rect.zw - u_Rect.xy) * 0.5;\n" +
        "    vec2 p = gl_FragCoord.xy - center;\n" +
        "    vec2 d = abs(p) - halfSize + u_Radius;\n" +
        "    float dist = length(max(d, vec2(0.0))) + min(max(d.x, d.y), 0.0) - u_Radius;\n" +
        "    float alpha = 1.0 - smoothstep(-1.0, 0.0, dist);\n" +
        "    float u = clamp((gl_FragCoord.x - u_Rect.x) / max(u_Rect.z - u_Rect.x, 1.0), 0.0, 1.0);\n" +
        "    float v = clamp((gl_FragCoord.y - u_Rect.y) / max(u_Rect.w - u_Rect.y, 1.0), 0.0, 1.0);\n" +
        "    vec4 bottom = mix(u_ColorBL, u_ColorBR, u);\n" +
        "    vec4 top = mix(u_ColorTL, u_ColorTR, u);\n" +
        "    vec4 col = mix(bottom, top, v);\n" +
        "    fragColor = vec4(col.rgb, col.a * alpha);\n" +
        "}\n";

    private int gradient2DProgramModern = -1;
    private int uG2dRectModern = -1, uG2dRadiusModern = -1, uG2dColorBLModern = -1, uG2dColorBRModern = -1,
        uG2dColorTLModern = -1, uG2dColorTRModern = -1, uProjectionGradient2DModern = -1;
    private boolean gradient2DInitFailedModern = false;

    // ── Shader "MultiStopGradient" moderne — même logique que
    // MULTISTOP_GRADIENT_FRAGMENT_SRC (legacy), voir son commentaire pour le
    // détail/le pourquoi (uniforms nommés individuellement, pas de tableau).
    private static final String MULTISTOP_GRADIENT_FRAGMENT_SRC_MODERN =
        "#version 150\n" +
        "uniform vec4 u_Rect;\n" +
        "uniform float u_Radius;\n" +
        "uniform float u_GradType;\n" +
        "uniform vec2 u_Start;\n" +
        "uniform vec2 u_End;\n" +
        "uniform vec4 u_Stop0Color;\n" + "uniform float u_Stop0Pos;\n" +
        "uniform vec4 u_Stop1Color;\n" + "uniform float u_Stop1Pos;\n" +
        "uniform vec4 u_Stop2Color;\n" + "uniform float u_Stop2Pos;\n" +
        "uniform vec4 u_Stop3Color;\n" + "uniform float u_Stop3Pos;\n" +
        "uniform vec4 u_Stop4Color;\n" + "uniform float u_Stop4Pos;\n" +
        "uniform vec4 u_Stop5Color;\n" + "uniform float u_Stop5Pos;\n" +
        "uniform vec4 u_Stop6Color;\n" + "uniform float u_Stop6Pos;\n" +
        "uniform vec4 u_Stop7Color;\n" + "uniform float u_Stop7Pos;\n" +
        "out vec4 fragColor;\n" +
        "float segT(float t, float p0, float p1) {\n" +
        "    float span = p1 - p0;\n" +
        "    return span < 1e-6 ? 0.0 : clamp((t - p0) / span, 0.0, 1.0);\n" +
        "}\n" +
        "void main() {\n" +
        "    vec2 center = (u_Rect.xy + u_Rect.zw) * 0.5;\n" +
        "    vec2 halfSize = (u_Rect.zw - u_Rect.xy) * 0.5;\n" +
        "    vec2 p = gl_FragCoord.xy - center;\n" +
        "    vec2 d = abs(p) - halfSize + u_Radius;\n" +
        "    float dist = length(max(d, vec2(0.0))) + min(max(d.x, d.y), 0.0) - u_Radius;\n" +
        "    float alpha = 1.0 - smoothstep(-1.0, 0.0, dist);\n" +
        "    float t;\n" +
        "    if (u_GradType < 0.5) {\n" +
        "        vec2 dir = u_End - u_Start;\n" +
        "        float len2 = dot(dir, dir);\n" +
        "        t = len2 < 1e-6 ? 0.0 : dot(gl_FragCoord.xy - u_Start, dir) / len2;\n" +
        "    } else if (u_GradType < 1.5) {\n" +
        "        float rad = length(u_End - u_Start);\n" +
        "        t = rad < 1e-6 ? 0.0 : length(gl_FragCoord.xy - u_Start) / rad;\n" +
        "    } else {\n" +
        "        vec2 dir = u_End - u_Start;\n" +
        "        float baseAngle = atan(dir.y, dir.x);\n" +
        "        vec2 q = gl_FragCoord.xy - u_Start;\n" +
        "        float ang = (atan(q.y, q.x) - baseAngle) / 6.28318530718;\n" +
        "        t = ang - floor(ang);\n" +
        "    }\n" +
        "    t = clamp(t, 0.0, 1.0);\n" +
        "    vec4 col;\n" +
        "    if (t <= u_Stop0Pos) col = u_Stop0Color;\n" +
        "    else if (t <= u_Stop1Pos) col = mix(u_Stop0Color, u_Stop1Color, segT(t, u_Stop0Pos, u_Stop1Pos));\n" +
        "    else if (t <= u_Stop2Pos) col = mix(u_Stop1Color, u_Stop2Color, segT(t, u_Stop1Pos, u_Stop2Pos));\n" +
        "    else if (t <= u_Stop3Pos) col = mix(u_Stop2Color, u_Stop3Color, segT(t, u_Stop2Pos, u_Stop3Pos));\n" +
        "    else if (t <= u_Stop4Pos) col = mix(u_Stop3Color, u_Stop4Color, segT(t, u_Stop3Pos, u_Stop4Pos));\n" +
        "    else if (t <= u_Stop5Pos) col = mix(u_Stop4Color, u_Stop5Color, segT(t, u_Stop4Pos, u_Stop5Pos));\n" +
        "    else if (t <= u_Stop6Pos) col = mix(u_Stop5Color, u_Stop6Color, segT(t, u_Stop5Pos, u_Stop6Pos));\n" +
        "    else if (t <= u_Stop7Pos) col = mix(u_Stop6Color, u_Stop7Color, segT(t, u_Stop6Pos, u_Stop7Pos));\n" +
        "    else col = u_Stop7Color;\n" +
        "    fragColor = vec4(col.rgb, col.a * alpha);\n" +
        "}\n";

    private int multiStopGradientProgramModern = -1;
    private int uMsgRectModern = -1, uMsgRadiusModern = -1, uMsgGradTypeModern = -1, uMsgStartModern = -1,
        uMsgEndModern = -1, uProjectionMultiStopGradientModern = -1;
    private final int[] uMsgStopColorModern = new int[8], uMsgStopPosModern = new int[8];
    private boolean multiStopGradientInitFailedModern = false;

    // ── Icône RGBA quelconque (pastille de mod/pack téléchargée) — simple
    // passthrough texture (PAS le shader SDF du texte : une icône a ses
    // propres couleurs réelles, rien à seuiller/teinter). Utilisé UNIQUEMENT
    // sur les brackets pré-era-E (le dispatcher drawIcon route era E vers
    // Blaze3DRect, qui a son propre chemin Blaze3D complet).
    // u_Alpha : multiplicateur d'opacité (1.0 = comportement d'origine,
    // inchangé) — ajouté pour permettre un fondu d'entrée sur du contenu
    // asynchrone (icônes Modrinth qui arrivent en HTTP, voir UiAsyncFade)
    // sans dupliquer tout le pipeline icône pour un simple multiplicateur.
    private static final String ICON_FRAGMENT_SRC =
        "uniform sampler2D u_Tex;\n" +
        "uniform float u_Alpha;\n" +
        "void main() {\n" +
        "    vec4 c = texture2D(u_Tex, gl_TexCoord[0].xy);\n" +
        "    gl_FragColor = vec4(c.rgb, c.a * u_Alpha);\n" +
        "}\n";

    private static final String ICON_FRAGMENT_SRC_MODERN =
        "#version 150\n" +
        "uniform sampler2D u_Tex;\n" +
        "uniform float u_Alpha;\n" +
        "in vec2 vTexCoord;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec4 c = texture(u_Tex, vTexCoord);\n" +
        "    fragColor = vec4(c.rgb, c.a * u_Alpha);\n" +
        "}\n";

    private int iconProgram = -1;
    private int uTexIcon = -1, uAlphaIcon = -1;
    private boolean iconInitFailed = false;

    private int iconProgramModern = -1;
    private int uTexIconModern = -1, uAlphaIconModern = -1, uProjectionIconModern = -1;
    private boolean iconInitFailedModern = false;

    private final Map<String, Integer> iconTextures = new HashMap<>();

    private void ensureRectShaderInit() {
        if (rectProgram != -1 || rectInitFailed) return;
        try {
            int vsh = gl.glCreateShader(0x8B31); // GL_VERTEX_SHADER
            gl.glShaderSource(vsh, VERTEX_SRC);
            gl.glCompileShader(vsh);

            int fsh = gl.glCreateShader(0x8B30); // GL_FRAGMENT_SHADER
            gl.glShaderSource(fsh, FRAGMENT_SRC);
            gl.glCompileShader(fsh);

            rectProgram = gl.glCreateProgram();
            gl.glAttachShader(rectProgram, vsh);
            gl.glAttachShader(rectProgram, fsh);
            gl.glLinkProgram(rectProgram);

            uRect = gl.glGetUniformLocation(rectProgram, "u_Rect");
            uRadius = gl.glGetUniformLocation(rectProgram, "u_Radius");

            LauncherLog.ui(1, "[UiRenderer] shader rect compilé, program=" + rectProgram
                + " uRect=" + uRect + " uRadius=" + uRadius);
        } catch (Throwable t) {
            rectInitFailed = true;
            LauncherLog.err("[UiRenderer] échec compilation shader rect — repli sur rects non arrondis : " + t);
        }
    }

    private void ensureRectShaderInitModern() {
        if (rectProgramModern != -1 || rectInitFailedModern) return;
        try {
            rectProgramModern = owner.compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, FRAGMENT_SRC_MODERN);
            uRectModern = gl.glGetUniformLocation(rectProgramModern, "u_Rect");
            uRadiusModern = gl.glGetUniformLocation(rectProgramModern, "u_Radius");
            uColorRectModern = gl.glGetUniformLocation(rectProgramModern, "uColor");
            uProjectionRectModern = gl.glGetUniformLocation(rectProgramModern, "uProjection");
            LauncherLog.ui(1, "[UiRenderer] shader rect (moderne) compilé, program=" + rectProgramModern
                + " uRect=" + uRectModern + " uRadius=" + uRadiusModern
                + " uColor=" + uColorRectModern + " uProjection=" + uProjectionRectModern);
        } catch (Throwable t) {
            rectInitFailedModern = true;
            LauncherLog.err("[UiRenderer] échec compilation shader rect moderne — repli sur rects non arrondis : " + t);
        }
    }

    private void ensureFlatShaderInitModern() {
        if (flatProgramModern != -1 || flatInitFailedModern) return;
        try {
            flatProgramModern = owner.compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, FLAT_FRAGMENT_SRC_MODERN);
            uColorFlatModern = gl.glGetUniformLocation(flatProgramModern, "uColor");
            uProjectionFlatModern = gl.glGetUniformLocation(flatProgramModern, "uProjection");
            LauncherLog.ui(1, "[UiRenderer] shader plat (moderne) compilé, program=" + flatProgramModern
                + " uColor=" + uColorFlatModern + " uProjection=" + uProjectionFlatModern);
        } catch (Throwable t) {
            flatInitFailedModern = true;
            LauncherLog.err("[UiRenderer] échec compilation shader plat moderne : " + t);
        }
    }

    private void ensureVignetteShaderInit() {
        if (vignetteProgram != -1 || vignetteInitFailed) return;
        try {
            int vsh = gl.glCreateShader(0x8B31); // GL_VERTEX_SHADER
            gl.glShaderSource(vsh, VERTEX_SRC);
            gl.glCompileShader(vsh);

            int fsh = gl.glCreateShader(0x8B30); // GL_FRAGMENT_SHADER
            gl.glShaderSource(fsh, VIGNETTE_FRAGMENT_SRC);
            gl.glCompileShader(fsh);

            vignetteProgram = gl.glCreateProgram();
            gl.glAttachShader(vignetteProgram, vsh);
            gl.glAttachShader(vignetteProgram, fsh);
            gl.glLinkProgram(vignetteProgram);

            uViewportSize = gl.glGetUniformLocation(vignetteProgram, "u_ViewportSize");
            uVSize = gl.glGetUniformLocation(vignetteProgram, "u_VSize");

            LauncherLog.ui(1, "[UiRenderer] shader vignette compilé, program=" + vignetteProgram
                + " uViewportSize=" + uViewportSize + " uVSize=" + uVSize);
        } catch (Throwable t) {
            vignetteInitFailed = true;
            LauncherLog.err("[UiRenderer] échec compilation shader vignette : " + t);
        }
    }

    private void ensureVignetteShaderInitModern() {
        if (vignetteProgramModern != -1 || vignetteInitFailedModern) return;
        try {
            vignetteProgramModern = owner.compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, VIGNETTE_FRAGMENT_SRC_MODERN);
            uViewportSizeModern = gl.glGetUniformLocation(vignetteProgramModern, "u_ViewportSize");
            uVSizeModern = gl.glGetUniformLocation(vignetteProgramModern, "u_VSize");
            uColorVignetteModern = gl.glGetUniformLocation(vignetteProgramModern, "uColor");
            uProjectionVignetteModern = gl.glGetUniformLocation(vignetteProgramModern, "uProjection");
            LauncherLog.ui(1, "[UiRenderer] shader vignette (moderne) compilé, program=" + vignetteProgramModern);
        } catch (Throwable t) {
            vignetteInitFailedModern = true;
            LauncherLog.err("[UiRenderer] échec compilation shader vignette moderne : " + t);
        }
    }

    private void ensureFxShaderInit() {
        if (fxProgram != -1 || fxInitFailed) return;
        try {
            int vsh = gl.glCreateShader(0x8B31); // GL_VERTEX_SHADER
            gl.glShaderSource(vsh, VERTEX_SRC);
            gl.glCompileShader(vsh);

            int fsh = gl.glCreateShader(0x8B30); // GL_FRAGMENT_SHADER
            gl.glShaderSource(fsh, FX_FRAGMENT_SRC);
            gl.glCompileShader(fsh);

            fxProgram = gl.glCreateProgram();
            gl.glAttachShader(fxProgram, vsh);
            gl.glAttachShader(fxProgram, fsh);
            gl.glLinkProgram(fxProgram);

            uFxRect = gl.glGetUniformLocation(fxProgram, "u_Rect");
            uFxRadius = gl.glGetUniformLocation(fxProgram, "u_Radius");
            uFxBlur = gl.glGetUniformLocation(fxProgram, "u_Blur");
            uFxBorderWidth = gl.glGetUniformLocation(fxProgram, "u_BorderWidth");
            uFxColorA = gl.glGetUniformLocation(fxProgram, "u_ColorA");
            uFxColorB = gl.glGetUniformLocation(fxProgram, "u_ColorB");
            uFxGradient = gl.glGetUniformLocation(fxProgram, "u_Gradient");

            LauncherLog.ui(1, "[UiRenderer] shader FX compilé, program=" + fxProgram);
        } catch (Throwable t) {
            fxInitFailed = true;
            LauncherLog.err("[UiRenderer] échec compilation shader FX (ombre/contour/dégradé) : " + t);
        }
    }

    private void ensureFxShaderInitModern() {
        if (fxProgramModern != -1 || fxInitFailedModern) return;
        try {
            fxProgramModern = owner.compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, FX_FRAGMENT_SRC_MODERN);
            uFxRectModern = gl.glGetUniformLocation(fxProgramModern, "u_Rect");
            uFxRadiusModern = gl.glGetUniformLocation(fxProgramModern, "u_Radius");
            uFxBlurModern = gl.glGetUniformLocation(fxProgramModern, "u_Blur");
            uFxBorderWidthModern = gl.glGetUniformLocation(fxProgramModern, "u_BorderWidth");
            uFxColorAModern = gl.glGetUniformLocation(fxProgramModern, "u_ColorA");
            uFxColorBModern = gl.glGetUniformLocation(fxProgramModern, "u_ColorB");
            uFxGradientModern = gl.glGetUniformLocation(fxProgramModern, "u_Gradient");
            uProjectionFxModern = gl.glGetUniformLocation(fxProgramModern, "uProjection");
            LauncherLog.ui(1, "[UiRenderer] shader FX (moderne) compilé, program=" + fxProgramModern);
        } catch (Throwable t) {
            fxInitFailedModern = true;
            LauncherLog.err("[UiRenderer] échec compilation shader FX moderne : " + t);
        }
    }

    private void ensureGradient2DShaderInit() {
        if (gradient2DProgram != -1 || gradient2DInitFailed) return;
        try {
            int vsh = gl.glCreateShader(0x8B31); // GL_VERTEX_SHADER
            gl.glShaderSource(vsh, VERTEX_SRC);
            gl.glCompileShader(vsh);

            int fsh = gl.glCreateShader(0x8B30); // GL_FRAGMENT_SHADER
            gl.glShaderSource(fsh, GRADIENT2D_FRAGMENT_SRC);
            gl.glCompileShader(fsh);

            gradient2DProgram = gl.glCreateProgram();
            gl.glAttachShader(gradient2DProgram, vsh);
            gl.glAttachShader(gradient2DProgram, fsh);
            gl.glLinkProgram(gradient2DProgram);

            uG2dRect = gl.glGetUniformLocation(gradient2DProgram, "u_Rect");
            uG2dRadius = gl.glGetUniformLocation(gradient2DProgram, "u_Radius");
            uG2dColorBL = gl.glGetUniformLocation(gradient2DProgram, "u_ColorBL");
            uG2dColorBR = gl.glGetUniformLocation(gradient2DProgram, "u_ColorBR");
            uG2dColorTL = gl.glGetUniformLocation(gradient2DProgram, "u_ColorTL");
            uG2dColorTR = gl.glGetUniformLocation(gradient2DProgram, "u_ColorTR");

            LauncherLog.ui(1, "[UiRenderer] shader Gradient2D compilé, program=" + gradient2DProgram);
        } catch (Throwable t) {
            gradient2DInitFailed = true;
            LauncherLog.err("[UiRenderer] échec compilation shader Gradient2D : " + t);
        }
    }

    private void ensureGradient2DShaderInitModern() {
        if (gradient2DProgramModern != -1 || gradient2DInitFailedModern) return;
        try {
            gradient2DProgramModern = owner.compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, GRADIENT2D_FRAGMENT_SRC_MODERN);
            uG2dRectModern = gl.glGetUniformLocation(gradient2DProgramModern, "u_Rect");
            uG2dRadiusModern = gl.glGetUniformLocation(gradient2DProgramModern, "u_Radius");
            uG2dColorBLModern = gl.glGetUniformLocation(gradient2DProgramModern, "u_ColorBL");
            uG2dColorBRModern = gl.glGetUniformLocation(gradient2DProgramModern, "u_ColorBR");
            uG2dColorTLModern = gl.glGetUniformLocation(gradient2DProgramModern, "u_ColorTL");
            uG2dColorTRModern = gl.glGetUniformLocation(gradient2DProgramModern, "u_ColorTR");
            uProjectionGradient2DModern = gl.glGetUniformLocation(gradient2DProgramModern, "uProjection");
            LauncherLog.ui(1, "[UiRenderer] shader Gradient2D (moderne) compilé, program=" + gradient2DProgramModern);
        } catch (Throwable t) {
            gradient2DInitFailedModern = true;
            LauncherLog.err("[UiRenderer] échec compilation shader Gradient2D moderne : " + t);
        }
    }

    private void ensureMultiStopGradientShaderInit() {
        if (multiStopGradientProgram != -1 || multiStopGradientInitFailed) return;
        try {
            int vsh = gl.glCreateShader(0x8B31); // GL_VERTEX_SHADER
            gl.glShaderSource(vsh, VERTEX_SRC);
            gl.glCompileShader(vsh);

            int fsh = gl.glCreateShader(0x8B30); // GL_FRAGMENT_SHADER
            gl.glShaderSource(fsh, MULTISTOP_GRADIENT_FRAGMENT_SRC);
            gl.glCompileShader(fsh);

            multiStopGradientProgram = gl.glCreateProgram();
            gl.glAttachShader(multiStopGradientProgram, vsh);
            gl.glAttachShader(multiStopGradientProgram, fsh);
            gl.glLinkProgram(multiStopGradientProgram);

            uMsgRect = gl.glGetUniformLocation(multiStopGradientProgram, "u_Rect");
            uMsgRadius = gl.glGetUniformLocation(multiStopGradientProgram, "u_Radius");
            uMsgGradType = gl.glGetUniformLocation(multiStopGradientProgram, "u_GradType");
            uMsgStart = gl.glGetUniformLocation(multiStopGradientProgram, "u_Start");
            uMsgEnd = gl.glGetUniformLocation(multiStopGradientProgram, "u_End");
            for (int i = 0; i < 8; i++) {
                uMsgStopColor[i] = gl.glGetUniformLocation(multiStopGradientProgram, "u_Stop" + i + "Color");
                uMsgStopPos[i] = gl.glGetUniformLocation(multiStopGradientProgram, "u_Stop" + i + "Pos");
            }

            LauncherLog.ui(1, "[UiRenderer] shader MultiStopGradient compilé, program=" + multiStopGradientProgram);
        } catch (Throwable t) {
            multiStopGradientInitFailed = true;
            LauncherLog.err("[UiRenderer] échec compilation shader MultiStopGradient : " + t);
        }
    }

    private void ensureMultiStopGradientShaderInitModern() {
        if (multiStopGradientProgramModern != -1 || multiStopGradientInitFailedModern) return;
        try {
            multiStopGradientProgramModern = owner.compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, MULTISTOP_GRADIENT_FRAGMENT_SRC_MODERN);
            uMsgRectModern = gl.glGetUniformLocation(multiStopGradientProgramModern, "u_Rect");
            uMsgRadiusModern = gl.glGetUniformLocation(multiStopGradientProgramModern, "u_Radius");
            uMsgGradTypeModern = gl.glGetUniformLocation(multiStopGradientProgramModern, "u_GradType");
            uMsgStartModern = gl.glGetUniformLocation(multiStopGradientProgramModern, "u_Start");
            uMsgEndModern = gl.glGetUniformLocation(multiStopGradientProgramModern, "u_End");
            for (int i = 0; i < 8; i++) {
                uMsgStopColorModern[i] = gl.glGetUniformLocation(multiStopGradientProgramModern, "u_Stop" + i + "Color");
                uMsgStopPosModern[i] = gl.glGetUniformLocation(multiStopGradientProgramModern, "u_Stop" + i + "Pos");
            }
            uProjectionMultiStopGradientModern = gl.glGetUniformLocation(multiStopGradientProgramModern, "uProjection");
            LauncherLog.ui(1, "[UiRenderer] shader MultiStopGradient (moderne) compilé, program=" + multiStopGradientProgramModern);
        } catch (Throwable t) {
            multiStopGradientInitFailedModern = true;
            LauncherLog.err("[UiRenderer] échec compilation shader MultiStopGradient moderne : " + t);
        }
    }

    /** {@code true} si le dégradé GPU est utilisable — sinon l'appelant peut se replier sur une approximation par bandes. */
    public boolean isVignetteAvailable() {
        if (owner.isModern()) {
            ensureVignetteShaderInitModern();
            return !vignetteInitFailedModern;
        }
        ensureVignetteShaderInit();
        return !vignetteInitFailed;
    }

    /**
     * Dessine un dégradé plein écran depuis les 4 bords vers le centre —
     * {@code edgeColor.a} est l'opacité AU BORD (0 au-delà de {@code vSize}
     * pixels de distance du bord le plus proche). Voir VIGNETTE_FRAGMENT_SRC :
     * un seul quad, alpha calculé par pixel côté GPU, aucun palier possible.
     */
    public void drawEdgeVignette(UiColor edgeColor, float vSize, int vpWidth, int vpHeight) {
        if (vSize <= 0f) return;
        if (owner.isModern()) {
            drawEdgeVignetteModern(edgeColor, vSize, vpWidth, vpHeight);
            return;
        }
        drawEdgeVignetteLegacy(edgeColor, vSize, vpWidth, vpHeight);
    }

    private void drawEdgeVignetteModern(UiColor edgeColor, float vSize, int vpWidth, int vpHeight) {
        ensureVignetteShaderInitModern();
        if (vignetteInitFailedModern) return;
        try {
            // GL_TEXTURE_2D/GL_ALPHA_TEST : concepts du pipeline fixe, qui
            // n'existent PLUS DU TOUT en Core Profile (texturage/test alpha
            // toujours gérés par le shader ici, jamais par un état fixe) —
            // les activer/désactiver renvoie GL_INVALID_ENUM (confirmé par le
            // debug log OpenGL en jeu : "Cannot enable <cap> in the current
            // profile"). Contrairement à glPushAttrib/glMatrixMode, ça ne
            // plante pas, mais ça reste une erreur GL inutile à chaque frame.
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            gl.glDisable(0x0C11); // GL_SCISSOR_TEST
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            gl.glUseProgram(vignetteProgramModern);
            gl.glUniform2f(uViewportSizeModern, vpWidth, vpHeight);
            gl.glUniform1f(uVSizeModern, vSize);
            gl.glUniform4f(uColorVignetteModern, edgeColor.r, edgeColor.g, edgeColor.b, edgeColor.a);
            owner.uploadProjectionModern(uProjectionVignetteModern, vpWidth, vpHeight);
            owner.drawQuadModern(0, 0, vpWidth, vpHeight);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawEdgeVignetteModern: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
        }
    }

    private void drawEdgeVignetteLegacy(UiColor edgeColor, float vSize, int vpWidth, int vpHeight) {
        ensureVignetteShaderInit();
        if (vignetteInitFailed) return;

        GlBridge.LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        try {
            // Legacy (1.8.9, Compatibility Profile) — voir captureLegacyGlState/
            // restoreLegacyGlState : sans restauration, nos glDisable(...)
            // restent appliqués en permanence après ce dessin, cassant le
            // rendu vanilla suivant (régression confirmée : monde/HUD tout
            // blanc + gros lag).
            savedGlState = gl.captureLegacyGlState();
            gl.glDisable(0x0DE1); // GL_TEXTURE_2D
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            // GL_ALPHA_TEST : vanilla l'active avec glAlphaFunc(GL_GREATER, 0.1)
            // pour les textures découpées (feuilles, vitres...) — laissé actif
            // depuis le rendu du monde juste avant ce hook, TOUT pixel de notre
            // dégradé sous ~10% d'opacité (0.1) serait REJETÉ (pas blendé, pas
            // dessiné du tout) au lieu de fondre vers 0 — un rejet binaire, pas
            // un blend, d'où un "mur" net et invariant à toute courbe/opacité
            // testée jusqu'ici. C'était la vraie cause.
            gl.glDisable(0x0BC0); // GL_ALPHA_TEST
            // Un GL_SCISSOR_TEST resté actif (ex: UiScrollContainer, si
            // endScissor() a sauté suite à une exception, voir son correctif)
            // découperait ce quad plein écran à un rectangle sans rapport —
            // symptôme observé : dégradé net et INVARIANT à toute retouche
            // d'opacité/courbe (un clip est binaire, pas un blend).
            gl.glDisable(0x0C11); // GL_SCISSOR_TEST
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            gl.matrixMode(0x1701); // GL_PROJECTION
            gl.pushMatrix();
            projPushed = true;
            gl.loadIdentity();
            gl.glOrtho(0, vpWidth, 0, vpHeight, -1, 1);
            gl.matrixMode(0x1700); // GL_MODELVIEW
            gl.pushMatrix();
            modelPushed = true;
            gl.loadIdentity();

            gl.glUseProgram(vignetteProgram);
            gl.glUniform2f(uViewportSize, vpWidth, vpHeight);
            gl.glUniform1f(uVSize, vSize);
            drawQuad(0, 0, vpWidth, vpHeight, edgeColor);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawEdgeVignette: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
            try {
                if (modelPushed) { gl.matrixMode(0x1700); gl.popMatrix(); }
            } catch (Throwable ignored) {}
            try {
                if (projPushed) { gl.matrixMode(0x1701); gl.popMatrix(); }
            } catch (Throwable ignored) {}
            gl.restoreLegacyGlState(savedGlState);
        }
    }

    private void ensureIconShaderInit() {
        if (iconProgram != -1 || iconInitFailed) return;
        try {
            int vsh = gl.glCreateShader(0x8B31); // GL_VERTEX_SHADER
            gl.glShaderSource(vsh, com.yuyuframe.launcheragent.apigraphic.era.gl2.Gl2TextRenderer.TEXT_VERTEX_SRC); // générique (ftransform + texcoord passthrough) — pas besoin d'un vertex shader dédié
            gl.glCompileShader(vsh);
            int fsh = gl.glCreateShader(0x8B30); // GL_FRAGMENT_SHADER
            gl.glShaderSource(fsh, ICON_FRAGMENT_SRC);
            gl.glCompileShader(fsh);
            iconProgram = gl.glCreateProgram();
            gl.glAttachShader(iconProgram, vsh);
            gl.glAttachShader(iconProgram, fsh);
            gl.glLinkProgram(iconProgram);
            uTexIcon = gl.glGetUniformLocation(iconProgram, "u_Tex");
            uAlphaIcon = gl.glGetUniformLocation(iconProgram, "u_Alpha");
            LauncherLog.ui(1, "[UiRenderer] shader icône compilé, program=" + iconProgram);
        } catch (Throwable t) {
            iconInitFailed = true;
            LauncherLog.err("[UiRenderer] échec compilation shader icône — icône non affichée : " + t);
        }
    }

    private void ensureIconShaderInitModern() {
        if (iconProgramModern != -1 || iconInitFailedModern) return;
        try {
            iconProgramModern = owner.compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, ICON_FRAGMENT_SRC_MODERN);
            uTexIconModern = gl.glGetUniformLocation(iconProgramModern, "u_Tex");
            uAlphaIconModern = gl.glGetUniformLocation(iconProgramModern, "u_Alpha");
            uProjectionIconModern = gl.glGetUniformLocation(iconProgramModern, "uProjection");
            LauncherLog.ui(1, "[UiRenderer] shader icône moderne compilé, program=" + iconProgramModern);
        } catch (Throwable t) {
            iconInitFailedModern = true;
            LauncherLog.err("[UiRenderer] échec compilation shader icône moderne — icône non affichée : " + t);
        }
    }

    /**
     * Charge (si pas déjà en cache pour {@code cacheKey}) et dessine une
     * icône RGBA quelconque (pastille de mod/pack téléchargée) — PAS un
     * ItemStack vanilla (voir {@link UiVanillaItemRenderer#drawVanillaItemIcon}
     * pour ça, désactivé sur era E). {@code x,y} = coin BAS-GAUCHE (origine
     * bas-gauche écran, comme drawRoundedRect/drawText — Y croissant vers le
     * haut), {@code size} = largeur ET hauteur (icône toujours carrée).
     *
     * @param cacheKey identifie la TEXTURE GPU déjà uploadée (jamais
     *                 ré-uploadée tant que la clé ne change pas) — PAS
     *                 l'image elle-même, qui peut être re-décodée par
     *                 l'appelant sans repayer le coût GPU si la clé est stable.
     * @param img      image DÉJÀ décodée/redimensionnée par l'appelant (pur
     *                 java.awt, ImageIO — AUCUNE dépendance à NativeImage/
     *                 TextureManager vanilla, contrairement à IconWidgets
     *                 qui, lui, pilote un vrai widget d'écran vanilla — ici
     *                 on reste dans NOTRE pipeline, version-générique).
     */
    public void drawIcon(String cacheKey, java.awt.image.BufferedImage img, float x, float y, float size, int vpWidth, int vpHeight) {
        drawIcon(cacheKey, img, x, y, size, size, 1f, vpWidth, vpHeight);
    }

    /**
     * Variante RECTANGULAIRE (largeur/hauteur indépendantes) — ajoutée pour
     * la page de détail Modrinth (bannières/galerie devant garder leur
     * ratio d'aspect d'origine, voir ModrinthProjectDetailScreen) : la
     * version carrée ci-dessus délègue simplement ici avec {@code w=h=size},
     * aucun appelant existant à modifier. Le chemin Blaze3D (era E) acceptait
     * DÉJÀ un rectangle arbitraire ({@code Blaze3DRect.queueIcon(x0,y0,x1,y1)}
     * — seule cette méthode, et les deux branches GL brut ci-dessous,
     * forçaient artificiellement un carré via un unique paramètre {@code size}.
     */
    public void drawIcon(String cacheKey, java.awt.image.BufferedImage img, float x, float y, float w, float h, int vpWidth, int vpHeight) {
        drawIcon(cacheKey, img, x, y, w, h, 1f, vpWidth, vpHeight);
    }

    /**
     * Variante avec opacité ({@code alpha} 0..1, 1 = comportement d'origine
     * identique aux deux surcharges ci-dessus) — capacité moteur ajoutée pour
     * permettre un fondu d'entrée sur du contenu asynchrone (icône Modrinth
     * qui vient d'arriver en HTTP, voir {@link UiAsyncFade}) sans que
     * l'appelant ait à dessiner un rect de transition séparé. Threadé sur les
     * 3 pipelines (GL legacy, GL moderne, Blaze3D era E) via {@code u_Alpha}
     * (voir ICON_FRAGMENT_SRC/_MODERN) et le 4ᵉ composant du ColorModulator
     * côté Blaze3D (voir {@code Blaze3DRect#queueIcon}).
     */
    public void drawIcon(String cacheKey, java.awt.image.BufferedImage img, float x, float y, float w, float h,
                          float alpha, int vpWidth, int vpHeight) {
        if (img == null) return;
        if (Blaze3DCore.isAvailable()) {
            Blaze3DRect.queueIcon(cacheKey, img, x, y, x + w, y + h, alpha, vpWidth, vpHeight);
            return;
        }
        int texId = ensureIconTexture(cacheKey, img);
        if (texId < 0) return;

        if (owner.isModern()) {
            ensureIconShaderInitModern();
            if (iconInitFailedModern) return;
            try {
                gl.glDisable(0x0B71); // GL_DEPTH_TEST
                gl.glDisable(0x0B44); // GL_CULL_FACE
                // PAS de glDisable(GL_SCISSOR_TEST) ici (contrairement à
                // drawEdgeVignette*, effet plein écran qui doit légitimement
                // l'ignorer) — BUG TROUVÉ (utilisateur : "il faut que toute la
                // card passe à travers pour disparaître", voir UiScrollContainer)
                // : ce disable, copié-collé du garde-fou de drawEdgeVignette,
                // défaisait silencieusement le clip actif de UiScrollContainer
                // pour CHAQUE icône dessinée à l'intérieur — un scissor actif
                // (posé par UiScrollContainer.beginScissor) doit au contraire
                // continuer à s'appliquer ici.
                gl.glEnable(0x0BE2);  // GL_BLEND
                gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA
                gl.glActiveTexture(0x84C0); // GL_TEXTURE0
                gl.glBindTexture(0x0DE1, texId);
                gl.glUseProgram(iconProgramModern);
                gl.glUniform1i(uTexIconModern, 0);
                gl.glUniform1f(uAlphaIconModern, alpha);
                owner.uploadProjectionModern(uProjectionIconModern, vpWidth, vpHeight);

                // UV : (x,y+h)=visuel HAUT-gauche (Y-up) ↔ (0,0)=image
                // haut-gauche (convention image standard) — même
                // correspondance que Blaze3DRect.drawIcon (voir sa javadoc).
                owner.ensureModernBuffersInit();
                if (!owner.modernBuffersInitFailed()) {
                    FloatBuffer verts = owner.floatBuffer(4 * 4);
                    owner.putVertex(verts, x, y + h, 0f, 0f);
                    owner.putVertex(verts, x, y, 0f, 1f);
                    owner.putVertex(verts, x + w, y, 1f, 1f);
                    owner.putVertex(verts, x + w, y + h, 1f, 0f);
                    verts.flip();
                    owner.uploadAndDraw(verts, 6, 4); // GL_TRIANGLE_FAN
                }
            } catch (Throwable t) {
                LauncherLog.err("[UiRenderer] drawIcon (moderne): " + t);
            } finally {
                try { gl.glUseProgram(0); } catch (Throwable ignored) {}
            }
            return;
        }

        ensureIconShaderInit();
        if (iconInitFailed) return;
        GlBridge.LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        try {
            savedGlState = gl.captureLegacyGlState();
            gl.glEnable(0x0DE1);  // GL_TEXTURE_2D
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            gl.glDisable(0x0BC0); // GL_ALPHA_TEST
            // PAS de glDisable(GL_SCISSOR_TEST) — voir le commentaire équivalent
            // dans la branche moderne juste au-dessus pour le pourquoi (BUG
            // TROUVÉ, UiScrollContainer).
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303);
            gl.glBindTexture(0x0DE1, texId);
            gl.glUseProgram(iconProgram);
            gl.glUniform1i(uTexIcon, 0);
            gl.glUniform1f(uAlphaIcon, alpha);

            gl.matrixMode(0x1701); // GL_PROJECTION
            gl.pushMatrix();
            projPushed = true;
            gl.loadIdentity();
            gl.glOrtho(0, vpWidth, 0, vpHeight, -1, 1);
            gl.matrixMode(0x1700); // GL_MODELVIEW
            gl.pushMatrix();
            modelPushed = true;
            gl.loadIdentity();

            gl.glColor4f(1f, 1f, 1f, 1f);
            gl.glBegin(7); // GL_QUADS
            gl.glTexCoord2f(0f, 0f); gl.glVertex2f(x, y + h);
            gl.glTexCoord2f(0f, 1f); gl.glVertex2f(x, y);
            gl.glTexCoord2f(1f, 1f); gl.glVertex2f(x + w, y);
            gl.glTexCoord2f(1f, 0f); gl.glVertex2f(x + w, y + h);
            gl.glEnd();
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawIcon (legacy): " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
            if (modelPushed) { try { gl.matrixMode(0x1700); gl.popMatrix(); } catch (Throwable ignored) {} }
            if (projPushed) { try { gl.matrixMode(0x1701); gl.popMatrix(); } catch (Throwable ignored) {} }
            if (savedGlState != null) gl.restoreLegacyGlState(savedGlState);
        }
    }

    /** Upload GL brut (glTexImage2D), mis en cache par cacheKey — voir FontAtlasTextures#createFontTextureRaw pour le même motif appliqué aux polices. */
    private int ensureIconTexture(String cacheKey, java.awt.image.BufferedImage img) {
        Integer cached = iconTextures.get(cacheKey);
        if (cached != null) return cached;
        try {
            int w = img.getWidth(), h = img.getHeight();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(w * h * 4);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                img.getRGB(0, y, w, 1, row, 0, w);
                for (int x = 0; x < w; x++) {
                    int argb = row[x];
                    buf.put((byte) ((argb >> 16) & 0xFF)); // R
                    buf.put((byte) ((argb >> 8) & 0xFF));  // G
                    buf.put((byte) (argb & 0xFF));         // B
                    buf.put((byte) ((argb >> 24) & 0xFF)); // A
                }
            }
            buf.flip();

            int texId = gl.glGenTextures();
            gl.glBindTexture(0x0DE1, texId);
            gl.glTexImage2D(0x0DE1, 0, 0x1908, w, h, 0, 0x1908, 0x1401, buf); // GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE
            gl.glTexParameteri(0x0DE1, 0x2801, 0x2601); // GL_TEXTURE_MIN_FILTER, GL_LINEAR (pas de mipmap — icônes rarement minifiées fortement)
            gl.glTexParameteri(0x0DE1, 0x2800, 0x2601); // GL_TEXTURE_MAG_FILTER, GL_LINEAR
            gl.glTexParameteri(0x0DE1, 0x2802, 0x812F); // GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE
            gl.glTexParameteri(0x0DE1, 0x2803, 0x812F); // GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE
            // Même précaution ZGC que createFontTextureRaw (voir son
            // commentaire pour le pourquoi) — coût négligeable ici (upload
            // UNE SEULE FOIS par icône, jamais par frame).
            gl.glFinish();
            GlBridge.reachabilityFence(buf);

            iconTextures.put(cacheKey, texId);
            LauncherLog.ui(1, "[UiRenderer] icône '" + cacheKey + "' uploadée (glTexImage2D), texId=" + texId + " w=" + w + " h=" + h);
            return texId;
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] ensureIconTexture(" + cacheKey + "): " + t);
            iconTextures.put(cacheKey, -1);
            return -1;
        }
    }

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
        if (Blaze3DCore.isAvailable()) {
            Blaze3DRect.queueRect(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
            return;
        }
        if (owner.isModern()) {
            drawRoundedRectModern(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
            return;
        }
        drawRoundedRectLegacy(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
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
        if (Blaze3DCore.isAvailable()) {
            Blaze3DRect.queueRect(x1, y1, x2, y2, radiusTopLeft, radiusTopRight, radiusBottomLeft, radiusBottomRight, color, vpWidth, vpHeight);
            return;
        }
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

    /**
     * Ombre portée façon CSS box-shadow (flou + spread), même distance signée
     * que {@link #drawRoundedRect} mais valide sur TOUS les bords (pas juste
     * les coins) — voir le commentaire du shader FX. À dessiner AVANT le
     * panneau/la carte elle-même (pas après, sinon l'ombre recouvre le
     * contenu).
     *
     * @param blur   largeur du flou en pixels (plus grand = ombre plus diffuse/étalée).
     * @param spread agrandissement du rectangle source AVANT flou (positif =
     *               ombre qui déborde du contour de la carte, négatif = ombre
     *               rétractée à l'intérieur) — 0 = ombre calée exactement sur
     *               les bords de {@code (x1,y1)-(x2,y2)}.
     */
    public void drawShadow(float x1, float y1, float x2, float y2, float radius, float blur, float spread,
                            UiColor color, int vpWidth, int vpHeight) {
        // BUG TROUVÉ (carte de mod entièrement noire) : cette ombre est
        // dessinée AVANT le fond de la carte dans le code appelant (pour
        // rester dessous), mais reste en GL brut (TAIL, APRÈS
        // presentTexture()) alors que le fond (drawRoundedRect) passe
        // maintenant par Blaze3D (HEAD, AVANT presentTexture()) — l'ombre
        // composait donc TOUJOURS par-dessus le fond, quel que soit l'ordre
        // d'appel dans le code (c'est le moment HEAD/TAIL qui détermine
        // l'ordre de composition final, pas l'ordre d'appel). Porter le flou
        // vers Blaze3D demanderait un vrai flou gaussien, impossible avec le
        // pipeline GUI_TEXT réutilisé (pas de shader dédié, juste un masque
        // de coin pré-calculé) — plutôt qu'une ombre mal composée par-dessus
        // le contenu, on saute l'ombre entièrement sur era E (perte
        // cosmétique mineure, contenu jamais assombri par erreur).
        if (Blaze3DCore.isAvailable()) return;
        drawFx(x1 - spread, y1 - spread, x2 + spread, y2 + spread, radius + spread, blur, 0f,
            color, color, false, vpWidth, vpHeight);
    }

    /**
     * Contour creux (anneau) d'épaisseur {@code borderWidth}, coins arrondis —
     * le rect lui-même reste transparent (à dessiner par-dessus un fond déjà
     * posé avec {@link #drawRoundedRect}, pas à sa place).
     *
     * <p><b>NO-OP sur era E (Blaze3D)</b> — MÊME garde et MÊME raison que
     * {@link #drawShadow} : {@link #drawFx} n'a aucune branche Blaze3D, il
     * exécute du GL BRUT ({@code glUseProgram}, changements d'état) au TAIL,
     * APRÈS {@code presentTexture()}, alors que tout le reste passe par la file
     * différée Blaze3D exécutée au HEAD, AVANT. Deux conséquences, toutes deux
     * observées : le contour composait TOUJOURS par-dessus tout le reste quel
     * que soit l'ordre d'appel, et surtout ces appels GL bruts entrelacés avec
     * les passes Blaze3D corrompaient l'état GPU — d'où les BUGS DE TEXTURE
     * signalés (retour utilisateur sur le fond de verre du HUD).
     *
     * <p>Le garde manquait ici alors qu'il existait déjà sur {@code drawShadow}
     * : la primitive était donc déjà mal employée sur ce bracket avant le
     * rework verre (voir {@code UiTextField}, {@code ModrinthProjectDetailScreen}),
     * lequel n'a fait qu'en multiplier les appels et rendre le défaut visible.
     *
     * <p>Pour un vrai contour sur era E, voir
     * {@link com.yuyuframe.launcheragent.apigraphic.UiRenderer#drawGlassPanel(float, float, float, float, float, float, float, float, UiColor, float, UiColor, UiColor, int, int)}
     * — il pose le contour comme un rect PLUS GRAND dessiné DERRIÈRE le
     * panneau, donc entièrement dans la file différée, sans aucun GL brut.
     */
    public void drawRoundedRectBorder(float x1, float y1, float x2, float y2, float radius, float borderWidth,
                                       UiColor color, int vpWidth, int vpHeight) {
        if (Blaze3DCore.isAvailable()) return;
        drawFx(x1, y1, x2, y2, radius, 0f, borderWidth, color, color, false, vpWidth, vpHeight);
    }

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
        if (Blaze3DCore.isAvailable()) return;
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

    /**
     * Dégradé vertical {@code colorBottom} (bord y1) → {@code colorTop} (bord y2), coins arrondis optionnels (radius=0 = rect plein).
     * Sur era E, route par Blaze3D (même z-order garanti que le texte, voir
     * {@link #drawRoundedRect}) — BUG TROUVÉ (fond de sidebar, en GL brut,
     * composait par-dessus le texte des items de la sidebar, Blaze3D : texte
     * invisible) : couleur portée PAR SOMMET (interpolée par le GPU),
     * ColorModulator neutre, voir Blaze3DGradient#drawGradientRect.
     */
    public void drawGradientRect(float x1, float y1, float x2, float y2, float radius,
                                  UiColor colorBottom, UiColor colorTop, int vpWidth, int vpHeight) {
        if (Blaze3DCore.isAvailable()) {
            Blaze3DGradient.queueGradientRect(x1, y1, x2, y2, radius, colorBottom, colorTop, vpWidth, vpHeight);
            return;
        }
        drawFx(x1, y1, x2, y2, radius, 0f, 0f, colorBottom, colorTop, true, vpWidth, vpHeight);
    }

    /**
     * Dégradé BILINÉAIRE entre 4 couleurs de coin — voir le commentaire du
     * shader Gradient2D (constantes GRADIENT2D_FRAGMENT_SRC*) pour le détail
     * mathématique complet (pipelines Legacy/Modern). Coins arrondis
     * optionnels (radius=0 = rect plein), bord anti-aliasé NET — pas de
     * flou, contrairement à drawShadow/drawGlow.
     *
     * Routé sur Blaze3D era E via {@link Blaze3DGradient#queueGradientRect2D}
     * — CONTRAIREMENT à drawShadow/drawGlow (no-op sur ce pipeline), ce
     * dégradé ne nécessite AUCUN shader custom côté Blaze3D : 4 couleurs de
     * sommet suffisent (le pipeline vertex-color déjà utilisé par
     * queueGradientRect les interpole nativement), là où la roue
     * Teinte/Saturation avait initialement (et à tort) tenté un vrai shader
     * GLSL — jamais routé sur ce pipeline, voir l'historique dans
     * UiColorPicker pour ce qui a été corrigé.
     */
    public void drawGradientRect2D(float x1, float y1, float x2, float y2, float radius,
                                    UiColor colorBottomLeft, UiColor colorBottomRight,
                                    UiColor colorTopLeft, UiColor colorTopRight, int vpWidth, int vpHeight) {
        if (Blaze3DCore.isAvailable()) {
            Blaze3DGradient.queueGradientRect2D(x1, y1, x2, y2, radius, colorBottomLeft, colorBottomRight, colorTopLeft, colorTopRight, vpWidth, vpHeight);
            return;
        }
        if (owner.isModern()) {
            drawGradient2DModern(x1, y1, x2, y2, radius, colorBottomLeft, colorBottomRight, colorTopLeft, colorTopRight, vpWidth, vpHeight);
            return;
        }
        drawGradient2DLegacy(x1, y1, x2, y2, radius, colorBottomLeft, colorBottomRight, colorTopLeft, colorTopRight, vpWidth, vpHeight);
    }

    private void drawGradient2DModern(float x1, float y1, float x2, float y2, float radius,
                                       UiColor bl, UiColor br, UiColor tl, UiColor tr, int vpWidth, int vpHeight) {
        ensureGradient2DShaderInitModern();
        if (gradient2DInitFailedModern) return;
        try {
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            // PAS de glDisable(GL_SCISSOR_TEST) — voir UiScrollContainer (javadoc de classe).
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            gl.glUseProgram(gradient2DProgramModern);
            gl.glUniform4f(uG2dRectModern, x1, y1, x2, y2);
            gl.glUniform1f(uG2dRadiusModern, radius);
            gl.glUniform4f(uG2dColorBLModern, bl.r, bl.g, bl.b, bl.a);
            gl.glUniform4f(uG2dColorBRModern, br.r, br.g, br.b, br.a);
            gl.glUniform4f(uG2dColorTLModern, tl.r, tl.g, tl.b, tl.a);
            gl.glUniform4f(uG2dColorTRModern, tr.r, tr.g, tr.b, tr.a);
            owner.uploadProjectionModern(uProjectionGradient2DModern, vpWidth, vpHeight);
            owner.drawQuadModern(x1, y1, x2, y2);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawGradient2DModern: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
        }
    }

    private void drawGradient2DLegacy(float x1, float y1, float x2, float y2, float radius,
                                       UiColor bl, UiColor br, UiColor tl, UiColor tr, int vpWidth, int vpHeight) {
        ensureGradient2DShaderInit();
        if (gradient2DInitFailed) return;

        GlBridge.LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        try {
            savedGlState = gl.captureLegacyGlState();
            gl.glDisable(0x0DE1); // GL_TEXTURE_2D
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            gl.glDisable(0x0BC0); // GL_ALPHA_TEST
            // PAS de glDisable(GL_SCISSOR_TEST) — voir UiScrollContainer (javadoc de classe).
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            gl.matrixMode(0x1701); // GL_PROJECTION
            gl.pushMatrix();
            projPushed = true;
            gl.loadIdentity();
            gl.glOrtho(0, vpWidth, 0, vpHeight, -1, 1);
            gl.matrixMode(0x1700); // GL_MODELVIEW
            gl.pushMatrix();
            modelPushed = true;
            gl.loadIdentity();

            gl.glUseProgram(gradient2DProgram);
            gl.glUniform4f(uG2dRect, x1, y1, x2, y2);
            gl.glUniform1f(uG2dRadius, radius);
            gl.glUniform4f(uG2dColorBL, bl.r, bl.g, bl.b, bl.a);
            gl.glUniform4f(uG2dColorBR, br.r, br.g, br.b, br.a);
            gl.glUniform4f(uG2dColorTL, tl.r, tl.g, tl.b, tl.a);
            gl.glUniform4f(uG2dColorTR, tr.r, tr.g, tr.b, tr.a);
            // gl_Color ignorée par ce shader — appel conservé pour réutiliser drawQuad() tel quel.
            drawQuad(x1, y1, x2, y2, bl);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawGradient2DLegacy: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
            try {
                if (modelPushed) { gl.matrixMode(0x1700); gl.popMatrix(); }
            } catch (Throwable ignored) {}
            try {
                if (projPushed) { gl.matrixMode(0x1701); gl.popMatrix(); }
            } catch (Throwable ignored) {}
            gl.restoreLegacyGlState(savedGlState);
        }
    }

    /**
     * Dégradé multi-stop (2 à 8 couleurs) linéaire/radial/conique — voir
     * {@link UiGradientType} pour le paramétrage de {@code startX/Y}/{@code
     * endX/Y} selon le type. {@code stopColors}/{@code stopPositions} doivent
     * avoir la MÊME longueur (2..8, tronqué au-delà) — {@code stopPositions}
     * croissant dans [0,1] (comportement non garanti sinon, voir le shader).
     * Routage identique à {@link #drawGradientRect2D} : Blaze3D era E d'abord
     * (voir {@link Blaze3DGradient#queueMultiStopGradientRect}), puis moderne/
     * legacy selon {@link UiRenderer#isModern()}.
     */
    public void drawMultiStopGradientRect(float x1, float y1, float x2, float y2, float radius,
                                    UiGradientType type, float startX, float startY, float endX, float endY,
                                    UiColor[] stopColors, float[] stopPositions, int vpWidth, int vpHeight) {
        if (stopColors == null || stopPositions == null || stopColors.length == 0
                || stopColors.length != stopPositions.length) return;
        int n = Math.min(stopColors.length, 8);
        // Stops au-delà de n : dupliquent le DERNIER stop réel (couleur+position)
        // — voir la javadoc de MULTISTOP_GRADIENT_FRAGMENT_SRC pour pourquoi la
        // chaîne if/else du shader résout ça correctement sans connaître n.
        UiColor[] colors = new UiColor[8];
        float[] positions = new float[8];
        for (int i = 0; i < n; i++) { colors[i] = stopColors[i]; positions[i] = stopPositions[i]; }
        for (int i = n; i < 8; i++) { colors[i] = colors[n - 1]; positions[i] = positions[n - 1]; }

        if (Blaze3DCore.isAvailable()) {
            Blaze3DGradient.queueMultiStopGradientRect(x1, y1, x2, y2, radius, type, startX, startY, endX, endY, colors, positions, vpWidth, vpHeight);
            return;
        }
        if (owner.isModern()) {
            drawMultiStopGradientModern(x1, y1, x2, y2, radius, type, startX, startY, endX, endY, colors, positions, vpWidth, vpHeight);
            return;
        }
        drawMultiStopGradientLegacy(x1, y1, x2, y2, radius, type, startX, startY, endX, endY, colors, positions, vpWidth, vpHeight);
    }

    private float gradTypeCode(UiGradientType type) {
        switch (type) {
            case RADIAL: return 1f;
            case CONIC: return 2f;
            default: return 0f;
        }
    }

    private void drawMultiStopGradientModern(float x1, float y1, float x2, float y2, float radius,
                                              UiGradientType type, float startX, float startY, float endX, float endY,
                                              UiColor[] colors, float[] positions, int vpWidth, int vpHeight) {
        ensureMultiStopGradientShaderInitModern();
        if (multiStopGradientInitFailedModern) return;
        try {
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            gl.glUseProgram(multiStopGradientProgramModern);
            gl.glUniform4f(uMsgRectModern, x1, y1, x2, y2);
            gl.glUniform1f(uMsgRadiusModern, radius);
            gl.glUniform1f(uMsgGradTypeModern, gradTypeCode(type));
            gl.glUniform2f(uMsgStartModern, startX, startY);
            gl.glUniform2f(uMsgEndModern, endX, endY);
            for (int i = 0; i < 8; i++) {
                UiColor c = colors[i];
                gl.glUniform4f(uMsgStopColorModern[i], c.r, c.g, c.b, c.a);
                gl.glUniform1f(uMsgStopPosModern[i], positions[i]);
            }
            owner.uploadProjectionModern(uProjectionMultiStopGradientModern, vpWidth, vpHeight);
            owner.drawQuadModern(x1, y1, x2, y2);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawMultiStopGradientModern: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
        }
    }

    private void drawMultiStopGradientLegacy(float x1, float y1, float x2, float y2, float radius,
                                              UiGradientType type, float startX, float startY, float endX, float endY,
                                              UiColor[] colors, float[] positions, int vpWidth, int vpHeight) {
        ensureMultiStopGradientShaderInit();
        if (multiStopGradientInitFailed) return;

        GlBridge.LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        try {
            savedGlState = gl.captureLegacyGlState();
            gl.glDisable(0x0DE1); // GL_TEXTURE_2D
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            gl.glDisable(0x0BC0); // GL_ALPHA_TEST
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            gl.matrixMode(0x1701); // GL_PROJECTION
            gl.pushMatrix();
            projPushed = true;
            gl.loadIdentity();
            gl.glOrtho(0, vpWidth, 0, vpHeight, -1, 1);
            gl.matrixMode(0x1700); // GL_MODELVIEW
            gl.pushMatrix();
            modelPushed = true;
            gl.loadIdentity();

            gl.glUseProgram(multiStopGradientProgram);
            gl.glUniform4f(uMsgRect, x1, y1, x2, y2);
            gl.glUniform1f(uMsgRadius, radius);
            gl.glUniform1f(uMsgGradType, gradTypeCode(type));
            gl.glUniform2f(uMsgStart, startX, startY);
            gl.glUniform2f(uMsgEnd, endX, endY);
            for (int i = 0; i < 8; i++) {
                UiColor c = colors[i];
                gl.glUniform4f(uMsgStopColor[i], c.r, c.g, c.b, c.a);
                gl.glUniform1f(uMsgStopPos[i], positions[i]);
            }
            // gl_Color ignorée par ce shader — appel conservé pour réutiliser drawQuad() tel quel.
            drawQuad(x1, y1, x2, y2, colors[0]);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawMultiStopGradientLegacy: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
            try {
                if (modelPushed) { gl.matrixMode(0x1700); gl.popMatrix(); }
            } catch (Throwable ignored) {}
            try {
                if (projPushed) { gl.matrixMode(0x1701); gl.popMatrix(); }
            } catch (Throwable ignored) {}
            gl.restoreLegacyGlState(savedGlState);
        }
    }

    /**
     * Halo lumineux centré sur {@code (x1,y1)-(x2,y2)} — capacité moteur
     * dédiée (voir audit runtime/ui/ : "glow" n'existait auparavant que
     * comme nom de variable locale réutilisant {@link #drawShadow} à un seul
     * site, UiMainMenuScreen). Empile {@code layers} passes de
     * {@link #drawFx} à blur croissant/alpha décroissant plutôt qu'un seul
     * flou plat — un vrai halo s'éteint progressivement, pas en un seul
     * palier — {@code intensity} (0..1) module l'alpha de départ.
     *
     * MÊME LIMITATION que {@link #drawShadow} sur era E (1.21.6+, Blaze3D) :
     * {@code drawFx} y est un no-op (pas de flou gaussien réalisable dans le
     * pipeline GUI_TEXT réutilisé, voir sa javadoc) — cette méthode hérite
     * donc silencieusement de la même absence de rendu sur ce bracket,
     * jusqu'à ce qu'une solution Blaze3D dédiée existe (hors scope ici).
     */
    public void drawGlow(float x1, float y1, float x2, float y2, float radius, float intensity, UiColor color,
                          int vpWidth, int vpHeight) {
        int layers = 3;
        for (int i = 0; i < layers; i++) {
            float t = (i + 1f) / layers;               // 0.33 / 0.66 / 1.0
            float blur = radius * 0.6f + 18f * t * t;   // flou croissant, non-linéaire (le halo s'étale plus vite qu'il ne s'assombrit)
            float alpha = intensity * (1f - t) * 0.55f; // alpha décroissant, jamais 0 pour la couche la plus large
            if (alpha <= 0.003f) continue;
            drawFx(x1, y1, x2, y2, radius, blur, 0f, color.withAlpha(alpha), color, false, vpWidth, vpHeight);
        }
    }

    /**
     * Cercle plein qui s'agrandit en s'estompant — retour tactile au clic
     * (capacité absente du moteur jusqu'ici, voir audit runtime/ui/ : le
     * hover ne fait qu'un lerp de couleur, rien à l'appui). Stateless comme
     * le reste de UiRenderer : {@code progress01} (0 au clic, 1 en fin de
     * ripple) et l'alpha de départ sont calculés par l'appelant (typiquement
     * via {@link UiAnimatedFloat} ou {@link UiTransition}, déjà existants —
     * aucune nouvelle classe d'animation nécessaire pour ce primitive).
     * Construit uniquement sur {@link #drawRoundedRect} (cercle = carré
     * entièrement arrondi, radius = moitié du côté) — fonctionne donc sur
     * les 3 pipelines, Blaze3D era E inclus, sans limitation contrairement à
     * {@link #drawGlow}.
     */
    public void drawRipple(float centerX, float centerY, float maxRadius, float progress01, float startAlpha,
                            UiColor color, int vpWidth, int vpHeight) {
        float p = Math.max(0f, Math.min(1f, progress01));
        float r = maxRadius * p;
        if (r <= 0.5f) return;
        float alpha = startAlpha * (1f - p);
        if (alpha <= 0.003f) return;
        drawRoundedRect(centerX - r, centerY - r, centerX + r, centerY + r, r, color.withAlpha(alpha), vpWidth, vpHeight);
    }

    /**
     * Placeholder de chargement (skeleton) + balayage lumineux (shimmer) —
     * capacités absentes du moteur jusqu'ici (voir audit runtime/ui/ :
     * UiRemoteImage n'a ni placeholder animé ni transition, apparition
     * brute dès que le fetch HTTP termine). {@code phase01} (0..1, boucle
     * en continu) positionne la barre lumineuse de gauche à droite —
     * calculé par l'appelant, ex. {@code (System.currentTimeMillis() %
     * periodMs) / (float) periodMs}, même philosophie que {@link UiStagger}
     * qui laisse déjà le timing au consommateur plutôt que de le cacher
     * dans une classe d'état supplémentaire.
     *
     * Base posée via {@link #drawRoundedRect} (fonctionne partout, era E
     * inclus) ; barre lumineuse via {@link #drawShadow} (flou) — hérite donc
     * de la MÊME LIMITATION era E que {@link #drawGlow} : le skeleton reste
     * visible sur ce bracket (fond plat correct), seul le balayage lumineux
     * n'apparaît pas tant qu'aucune solution de flou Blaze3D n'existe.
     */
    public void drawSkeletonShimmer(float x1, float y1, float x2, float y2, float radius, float phase01,
                                     UiColor baseColor, UiColor highlightColor, int vpWidth, int vpHeight) {
        drawRoundedRect(x1, y1, x2, y2, radius, baseColor, vpWidth, vpHeight);
        float width = x2 - x1;
        float bandWidth = Math.max(24f, width * 0.28f);
        float bx = x1 - bandWidth + (width + bandWidth * 2f) * Math.max(0f, Math.min(1f, phase01));
        drawShadow(bx - bandWidth * 0.15f, y1, bx + bandWidth * 0.15f, y2, radius, bandWidth * 0.5f, 0f,
            highlightColor, vpWidth, vpHeight);
    }

    /**
     * Spinner de chargement rotatif — capacité absente du moteur jusqu'ici
     * (voir audit runtime/ui/). Choix délibéré : {@code dotCount} points
     * disposés en cercle avec un dégradé d'alpha façon "comète" plutôt
     * qu'un véritable arc balayé — un arc angulaire correct demanderait un
     * 5ᵉ programme GLSL dédié (distance signée + test d'angle atan2, ni
     * {@link #drawRoundedRect} ni le shader FX existant ne calculent
     * d'angle) pour un gain visuel marginal à ce stade. Construit
     * uniquement sur {@link #drawRoundedRect} (cercle plein par point) :
     * fonctionne sur les 3 pipelines sans limitation, contrairement à
     * {@link #drawGlow}/{@link #drawSkeletonShimmer}.
     *
     * @param rotationDeg angle de la tête de la comète, calculé par
     *                    l'appelant (ex. {@code (System.currentTimeMillis() %
     *                    periodMs) / (float) periodMs * 360f}).
     */
    public void drawSpinner(float centerX, float centerY, float radius, float dotRadius, float rotationDeg,
                             UiColor color, int vpWidth, int vpHeight) {
        int dotCount = 8;
        for (int i = 0; i < dotCount; i++) {
            float t = i / (float) dotCount;                 // 0..1 autour du cercle
            float angleDeg = rotationDeg + t * 360f;
            double angleRad = Math.toRadians(angleDeg);
            float dx = centerX + (float) Math.cos(angleRad) * radius;
            float dy = centerY + (float) Math.sin(angleRad) * radius;
            // Alpha décroissant depuis la tête (t=0, la plus opaque) vers la
            // queue de la comète (t proche de 1, quasi invisible).
            float alpha = color.a * (1f - t) * (1f - t);
            if (alpha <= 0.02f) continue;
            drawRoundedRect(dx - dotRadius, dy - dotRadius, dx + dotRadius, dy + dotRadius, dotRadius,
                color.withAlpha(alpha), vpWidth, vpHeight);
        }
    }

    private void drawFx(float x1, float y1, float x2, float y2, float radius, float blur, float borderWidth,
                         UiColor colorA, UiColor colorB, boolean gradient, int vpWidth, int vpHeight) {
        if (owner.isModern()) {
            drawFxModern(x1, y1, x2, y2, radius, blur, borderWidth, colorA, colorB, gradient, vpWidth, vpHeight);
            return;
        }
        drawFxLegacy(x1, y1, x2, y2, radius, blur, borderWidth, colorA, colorB, gradient, vpWidth, vpHeight);
    }

    private void drawRoundedRectModern(float x1, float y1, float x2, float y2, float radius, UiColor color,
                                        int vpWidth, int vpHeight) {
        ensureRectShaderInitModern();
        // Voir drawRoundedRectLegacy pour le pourquoi de ce garde-fou radius<=0.
        boolean useShader = rectProgramModern != -1 && !rectInitFailedModern && radius > 0f;
        try {
            // Voir drawEdgeVignetteModern : GL_TEXTURE_2D/GL_ALPHA_TEST retirés
            // (GL_INVALID_ENUM en Core Profile, concepts fixed-function inexistants ici).
            // PAS de glDisable(GL_SCISSOR_TEST) — BUG TROUVÉ (voir
            // UiScrollContainer, javadoc de classe) : ce disable, copié-collé
            // du garde-fou légitime de drawEdgeVignette (effet plein écran),
            // défaisait silencieusement TOUT clip actif de UiScrollContainer —
            // une carte devait entièrement sortir du viewport pour disparaître
            // au lieu d'être proprement clippée au bord.
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            if (useShader) {
                gl.glUseProgram(rectProgramModern);
                gl.glUniform4f(uRectModern, x1, y1, x2, y2);
                gl.glUniform1f(uRadiusModern, radius);
                gl.glUniform4f(uColorRectModern, color.r, color.g, color.b, color.a);
                owner.uploadProjectionModern(uProjectionRectModern, vpWidth, vpHeight);
            } else {
                // radius<=0 (rect plein sans arrondi) OU échec de compilation
                // du shader rect : besoin quand même d'UN programme actif
                // (le pipeline moderne n'a pas d'équivalent "sans shader" du
                // mode immédiat legacy) — shader "couleur plate" dédié, voir
                // FLAT_FRAGMENT_SRC_MODERN pour le pourquoi (le shader
                // vignette utilisé initialement ici était FAUX : son dégradé
                // rendait tout invisible sauf le contour, confirmé en jeu).
                ensureFlatShaderInitModern();
                if (!flatInitFailedModern) {
                    gl.glUseProgram(flatProgramModern);
                    gl.glUniform4f(uColorFlatModern, color.r, color.g, color.b, color.a);
                    owner.uploadProjectionModern(uProjectionFlatModern, vpWidth, vpHeight);
                } else {
                    return;
                }
            }
            owner.drawQuadModern(x1, y1, x2, y2);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawRoundedRectModern: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
        }
    }

    private void drawRoundedRectLegacy(float x1, float y1, float x2, float y2, float radius, UiColor color,
                                 int vpWidth, int vpHeight) {
        ensureRectShaderInit();
        // radius<=0 : bypass total du shader — bug dégénéré sinon. Dans
        // "alpha = 1 - smoothstep(radius-1, radius, dist)", avec radius=0 tout
        // pixel intérieur a dist=0, qui tombe EXACTEMENT sur le bord haut du
        // smoothstep(-1, 0, 0) → 1.0, donc alpha=0 partout : rect totalement
        // invisible malgré un dessin "réussi" (aucune exception). Observé en
        // test 1.8.9 : le fond plein écran (radius=0) ne s'affichait jamais.
        boolean useShader = rectProgram != -1 && !rectInitFailed && radius > 0f;

        // Chaque pop n'est tenté QUE si son push correspondant a réellement
        // réussi — sinon une exception entre pushMatrix et son pop (ex:
        // résolution réflexion GL en échec) laisserait un popMatrix orphelin
        // dans le finally, qui dépile une pile déjà vide : GL_STACK_UNDERFLOW
        // ("Stack underflow"), observé en jeu sans lien évident avec le dessin
        // en cours.
        GlBridge.LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        try {
            // Legacy (1.8.9) — voir captureLegacyGlState()/drawEdgeVignetteLegacy.
            savedGlState = gl.captureLegacyGlState();
            gl.glDisable(0x0DE1); // GL_TEXTURE_2D
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE — sinon un quad mal orienté (winding) par rapport à ce que
                                // le rendu 3D du monde a laissé actif peut être silencieusement éliminé,
                                // sans erreur : dessin "réussi" en apparence, rien de visible en jeu.
            gl.glDisable(0x0BC0); // GL_ALPHA_TEST — voir drawEdgeVignette pour le pourquoi
            // PAS de glDisable(GL_SCISSOR_TEST) — voir UiScrollContainer
            // (javadoc de classe) : ce disable défaisait le clip actif d'un
            // scroll container pour CHAQUE rect dessiné à l'intérieur.
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            // ftransform() (vertex shader) applique la matrice modelview/projection
            // COURANTE — à ce point précis (TAIL de GameRenderer.render()), rien ne
            // garantit qu'elle soit une projection 2D pixel-space : ça peut encore
            // être la perspective 3D du monde, auquel cas nos coordonnées pixel
            // (ex: 1920,1057) sortent totalement du frustum et sont clippées, d'où
            // rien de visible malgré un dessin "réussi" côté code. On pose donc
            // NOTRE PROPRE ortho, empilée puis restaurée, indépendante de l'état
            // ambiant. Bas-gauche origine Y-up (glOrtho(0,w,0,h,...)) — cohérent
            // avec gl_FragCoord et le reste du pipeline (mouse/UI), pas de flip.
            gl.matrixMode(0x1701); // GL_PROJECTION
            gl.pushMatrix();
            projPushed = true;
            gl.loadIdentity();
            gl.glOrtho(0, vpWidth, 0, vpHeight, -1, 1);
            gl.matrixMode(0x1700); // GL_MODELVIEW
            gl.pushMatrix();
            modelPushed = true;
            gl.loadIdentity();

            if (useShader) {
                gl.glUseProgram(rectProgram);
                gl.glUniform4f(uRect, x1, y1, x2, y2);
                gl.glUniform1f(uRadius, radius);
            }
            drawQuad(x1, y1, x2, y2, color);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawRoundedRect: " + t);
        } finally {
            try {
                if (useShader) gl.glUseProgram(0);
            } catch (Throwable ignored) {}
            try {
                if (modelPushed) { gl.matrixMode(0x1700); gl.popMatrix(); }
            } catch (Throwable ignored) {}
            try {
                if (projPushed) { gl.matrixMode(0x1701); gl.popMatrix(); }
            } catch (Throwable ignored) {}
            gl.restoreLegacyGlState(savedGlState);
        }
    }

    private void drawFxModern(float x1, float y1, float x2, float y2, float radius, float blur, float borderWidth,
                               UiColor colorA, UiColor colorB, boolean gradient, int vpWidth, int vpHeight) {
        ensureFxShaderInitModern();
        if (fxInitFailedModern) return;
        try {
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            // PAS de glDisable(GL_SCISSOR_TEST) — voir UiScrollContainer
            // (javadoc de classe) : défaisait le clip actif d'un scroll
            // container pour chaque ombre/bordure/dégradé dessiné à l'intérieur.
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            gl.glUseProgram(fxProgramModern);
            gl.glUniform4f(uFxRectModern, x1, y1, x2, y2);
            gl.glUniform1f(uFxRadiusModern, radius);
            gl.glUniform1f(uFxBlurModern, blur);
            gl.glUniform1f(uFxBorderWidthModern, borderWidth);
            gl.glUniform4f(uFxColorAModern, colorA.r, colorA.g, colorA.b, colorA.a);
            gl.glUniform4f(uFxColorBModern, colorB.r, colorB.g, colorB.b, colorB.a);
            gl.glUniform1f(uFxGradientModern, gradient ? 1f : 0f);
            owner.uploadProjectionModern(uProjectionFxModern, vpWidth, vpHeight);
            // u_Rect (ci-dessus) garde la boîte LOGIQUE exacte (nécessaire au
            // calcul de distance) mais le quad RASTÉRISÉ doit déborder de
            // "blur" pixels au-delà — sinon aucun pixel n'existe au-delà de
            // (x1,y1)-(x2,y2) pour recevoir la fin du dégradé, qui se
            // retrouve coupé net exactement sur ce bord (confirmé en jeu :
            // "carré" visible autour d'un halo pourtant mathématiquement
            // circulaire — la formule de distance était correcte, seule la
            // géométrie dessinée était trop petite pour la montrer en entier).
            float pad = Math.max(blur, 1f);
            owner.drawQuadModern(x1 - pad, y1 - pad, x2 + pad, y2 + pad);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawFxModern: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
        }
    }

    private void drawFxLegacy(float x1, float y1, float x2, float y2, float radius, float blur, float borderWidth,
                               UiColor colorA, UiColor colorB, boolean gradient, int vpWidth, int vpHeight) {
        ensureFxShaderInit();
        if (fxInitFailed) return;

        GlBridge.LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        try {
            // Même garde que drawRoundedRectLegacy — voir son commentaire pour
            // le détail de chaque état désactivé/pourquoi.
            savedGlState = gl.captureLegacyGlState();
            gl.glDisable(0x0DE1); // GL_TEXTURE_2D
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            gl.glDisable(0x0BC0); // GL_ALPHA_TEST
            // PAS de glDisable(GL_SCISSOR_TEST) — voir UiScrollContainer
            // (javadoc de classe).
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            gl.matrixMode(0x1701); // GL_PROJECTION
            gl.pushMatrix();
            projPushed = true;
            gl.loadIdentity();
            gl.glOrtho(0, vpWidth, 0, vpHeight, -1, 1);
            gl.matrixMode(0x1700); // GL_MODELVIEW
            gl.pushMatrix();
            modelPushed = true;
            gl.loadIdentity();

            gl.glUseProgram(fxProgram);
            gl.glUniform4f(uFxRect, x1, y1, x2, y2);
            gl.glUniform1f(uFxRadius, radius);
            gl.glUniform1f(uFxBlur, blur);
            gl.glUniform1f(uFxBorderWidth, borderWidth);
            gl.glUniform4f(uFxColorA, colorA.r, colorA.g, colorA.b, colorA.a);
            gl.glUniform4f(uFxColorB, colorB.r, colorB.g, colorB.b, colorB.a);
            gl.glUniform1f(uFxGradient, gradient ? 1f : 0f);
            // Couleur "courante" (gl_Color) ignorée par ce shader (les
            // couleurs viennent des uniforms u_ColorA/B ci-dessus) — appel
            // conservé uniquement pour réutiliser drawQuad() tel quel.
            // Quad débordé de "blur" pixels au-delà de u_Rect — voir
            // drawFxModern pour le pourquoi (même correction des deux côtés).
            float pad = Math.max(blur, 1f);
            drawQuad(x1 - pad, y1 - pad, x2 + pad, y2 + pad, colorA);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawFxLegacy: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
            try {
                if (modelPushed) { gl.matrixMode(0x1700); gl.popMatrix(); }
            } catch (Throwable ignored) {}
            try {
                if (projPushed) { gl.matrixMode(0x1701); gl.popMatrix(); }
            } catch (Throwable ignored) {}
            gl.restoreLegacyGlState(savedGlState);
        }
    }

    private void drawQuad(float x1, float y1, float x2, float y2, UiColor c) {
        try {
            gl.glColor4f(c.r, c.g, c.b, c.a);
            gl.glBegin(7); // GL_QUADS
            gl.glVertex2f(x1, y1);
            gl.glVertex2f(x1, y2);
            gl.glVertex2f(x2, y2);
            gl.glVertex2f(x2, y1);
            gl.glEnd();
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawQuad: " + t);
        }
    }
}
