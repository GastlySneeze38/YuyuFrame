package com.yuyuframe.launcheragent.runtime.ui.graphicapi;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.version.MinecraftVersionDetector;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Rendu de rects arrondis via shader GLSL, indépendant de tout mod loader et
 * de toute lib externe (pas de NanoVG/LWJGL3 backporté, pas d'Elementa/
 * UniversalCraft) — même technique que Elementa/UIRoundedRectangle.kt (un quad
 * + un fragment shader qui calcule la distance au bord arrondi), mais réécrite
 * de zéro pour tourner sur le contexte GL DÉJÀ ouvert par le jeu (LWJGL2 en
 * 1.8.9, LWJGL3 en 1.21+ — les deux exposent org.lwjgl.opengl.GL20/GL11 sous
 * le même nom de paquet, donc résolus par réflexion comme le reste de
 * l'agent : org.lwjgl n'est PAS obfusqué, pas besoin de MappingsRegistry ici).
 *
 * DEUX PIPELINES DE RENDU (voir {@link #modern}, historique du projet) :
 *  - LEGACY (1.8.9) : pipeline fixe OpenGL 1.x/2.x (glBegin/glVertex2f,
 *    glMatrixMode/glPushMatrix/glOrtho, ftransform()/gl_Color côté shader) —
 *    confirmé fonctionnel en jeu sur 1.8.9 (contexte GL2.1 compatibilité).
 *  - MODERNE (1.21.11+) : ce même pipeline fixe crashe NATIVEMENT (JVM,
 *    0xC0000409) sur cette version — confirmé en test réel, diagnostic
 *    ligne par ligne, jusqu'à isoler `glMatrixMode` lui-même comme point de
 *    crash (après un premier correctif ayant déjà isolé et supprimé
 *    `glPushAttrib`, également fautif). Le contexte GL de Minecraft 1.21.11
 *    n'accepte donc PLUS aucune fonction de la pile de matrices ni du mode
 *    immédiat. Remplacé par un pipeline VAO/VBO + matrice de projection
 *    explicite en uniform + shaders GLSL 150 (in/out, pas de gl_Vertex/
 *    gl_Color/ftransform), voir ensure*ShaderInitModern / draw*Modern.
 */
public final class UiRenderer {

    /** Déterminé une fois à la construction — voir la javadoc de la classe. */
    private final boolean modern;

    /** true si ce renderer utilise le pipeline moderne (1.21.11+), false si legacy (1.8.9) — voir javadoc de la classe. */
    public boolean isModern() { return modern; }

    /** Voir drawVanillaItemIcon — log de diagnostic une seule fois, pas à chaque frame/icône. */
    private static boolean DIAG_LOGGED = false;
    /** Voir drawVanillaItemIcon — compteur d'appels pour limiter le log glGetError() aux ~2 premières frames seulement. */
    private static int DIAG_CALLS = 0;
    private static final int DIAG_CALL_LIMIT = 12;

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

    // ── Shader de texte SDF (distance field) — voir UiFont pour le pourquoi :
    // l'alpha de l'atlas encode une distance signée au bord du glyphe, pas
    // une couverture directe. dFdx/dFdy/fwidth sont cœur GLSL 1.10+ pour un
    // fragment shader (aucune extension à déclarer), donc dispo aussi bien en
    // GL2.1 compat (1.8.9/LWJGL2) qu'en GL3.2+ compat (1.21+/LWJGL3).
    private static final String TEXT_VERTEX_SRC =
        "void main() {\n" +
        "    gl_Position = ftransform();\n" +
        "    gl_FrontColor = gl_Color;\n" +
        "    gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
        "}\n";

    // BIAS : sans lui, les traits fins (barres de "i"/"l"/"j") disparaissent
    // presque entièrement dans le texte le plus petit de l'UI (descriptions,
    // ~8px de haut affiché) — leur trait est alors plus étroit que la zone de
    // transition du champ de distance elle-même, donc quasiment aucun texel
    // n'atteint franchement "dedans" (dist > 0.5). Décaler la distance vers
    // "dedans" avant le seuillage épaissit légèrement TOUT le texte (effet
    // "gras" standard en rendu SDF) pour que ces traits fins restent visibles,
    // au prix d'un contour à peine plus épais partout ailleurs — imperceptible
    // sur le texte de taille normale/grande.
    private static final String TEXT_FRAGMENT_SRC =
        "uniform sampler2D u_Tex;\n" +
        "const float BIAS = 0.06;\n" +
        "void main() {\n" +
        "    float dist = texture2D(u_Tex, gl_TexCoord[0].xy).a + BIAS;\n" +
        "    float w = fwidth(dist);\n" +
        "    float alpha = smoothstep(0.5 - w, 0.5 + w, dist);\n" +
        "    gl_FragColor = vec4(gl_Color.rgb, gl_Color.a * alpha);\n" +
        "}\n";

    private int textProgram = -1;
    private int uTex = -1;
    private boolean textInitFailed = false;

    private final Map<String, Method> glMethods = new HashMap<>();
    private ClassLoader gameClassLoader;

    // Un textureId GL par UiFont (REGULAR/BOLD) — uploadé une seule fois au
    // premier drawText(), jamais régénéré ensuite (l'atlas ne change pas).
    private final Map<UiFont, Integer> fontTextures = new HashMap<>();

    // ══════════════════════════════════════════════════════════════════════
    // ── Pipeline MODERNE (1.21.11+) — voir javadoc de la classe pour le
    // pourquoi. GLSL 150 (in/out, pas de builtins fixed-function), matrice de
    // projection explicite en uniform (pas de glMatrixMode/glOrtho), VAO/VBO +
    // glDrawArrays (pas de glBegin/glVertex2f). Un seul VAO/VBO partagé par
    // les 3 shaders (même layout de vertex : vec2 position + vec2 texCoord =
    // 4 floats/sommet, texCoord ignoré par les shaders rect/vignette).
    // ══════════════════════════════════════════════════════════════════════

    private static final String VERTEX_SRC_MODERN =
        "#version 150\n" +
        "in vec2 aPos;\n" +
        "in vec2 aTexCoord;\n" +
        "uniform mat4 uProjection;\n" +
        "out vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = uProjection * vec4(aPos, 0.0, 1.0);\n" +
        "    vTexCoord = aTexCoord;\n" +
        "}\n";

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

    private static final String TEXT_FRAGMENT_SRC_MODERN =
        "#version 150\n" +
        "uniform sampler2D u_Tex;\n" +
        "uniform vec4 uColor;\n" +
        "in vec2 vTexCoord;\n" +
        "out vec4 fragColor;\n" +
        "const float BIAS = 0.06;\n" +
        "void main() {\n" +
        "    float dist = texture(u_Tex, vTexCoord).a + BIAS;\n" +
        "    float w = fwidth(dist);\n" +
        "    float alpha = smoothstep(0.5 - w, 0.5 + w, dist);\n" +
        "    fragColor = vec4(uColor.rgb, uColor.a * alpha);\n" +
        "}\n";

    private int textProgramModern = -1;
    private int uTexModern = -1, uColorTextModern = -1, uProjectionTextModern = -1;
    private boolean textInitFailedModern = false;

    // ── Icône RGBA quelconque (pastille de mod/pack téléchargée) — simple
    // passthrough texture (PAS le shader SDF du texte : une icône a ses
    // propres couleurs réelles, rien à seuiller/teinter). Utilisé UNIQUEMENT
    // sur les brackets pré-era-E (le dispatcher drawIcon route era E vers
    // UiTextBlaze3D, qui a son propre chemin Blaze3D complet).
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

    private int modernVao = -1, modernVbo = -1;
    private boolean modernBuffersInitFailed = false;
    // Capacité courante du VBO en sommets (redimensionné au besoin — drawText
    // peut avoir besoin de bien plus de 4 sommets pour une chaîne entière,
    // 6 sommets/glyphe car GL_TRIANGLES, pas de fan possible pour des quads
    // disjoints contrairement à rect/vignette qui n'en ont besoin que d'UN).
    private int modernVboCapacityVerts = 0;

    private static UiRenderer instance;

    /**
     * "moderne" = dessin exclusivement par shaders/VAO/VBO (Core Profile GL
     * 3.2+, obligatoire depuis la 1.17) ; "legacy" = dessin immédiat
     * (glBegin/glMatrixMode), possible sur 1.8.9 ET sur 1.13-1.16.x (ces
     * dernières utilisent déjà LWJGL3/GLFW pour la fenêtre/l'input — voir
     * UiInputPollerModern côté Mixin — mais leur contexte GL reste en
     * dessous de 3.2, donc le pipeline fixe y fonctionne encore) — voir
     * {@link MinecraftVersionDetector#supportsFixedFunctionDrawing}. La
     * version MC est déjà posée en system property par
     * {@code IsolatedBootstrap.start()} avant que quoi que ce soit ne
     * s'affiche, donc toujours dispo ici.
     */
    private UiRenderer() {
        String mcVersion = System.getProperty("launcheragent.mcVersion", "");
        this.modern = !MinecraftVersionDetector.supportsFixedFunctionDrawing(mcVersion);
    }

    public static UiRenderer get(ClassLoader gameClassLoader) {
        if (instance == null) instance = new UiRenderer();
        instance.gameClassLoader = gameClassLoader;
        return instance;
    }

    private void ensureRectShaderInit() {
        if (rectProgram != -1 || rectInitFailed) return;
        try {
            int vsh = glCreateShader(0x8B31); // GL_VERTEX_SHADER
            glShaderSource(vsh, VERTEX_SRC);
            glCompileShader(vsh);

            int fsh = glCreateShader(0x8B30); // GL_FRAGMENT_SHADER
            glShaderSource(fsh, FRAGMENT_SRC);
            glCompileShader(fsh);

            rectProgram = glCreateProgram();
            glAttachShader(rectProgram, vsh);
            glAttachShader(rectProgram, fsh);
            glLinkProgram(rectProgram);

            uRect = glGetUniformLocation(rectProgram, "u_Rect");
            uRadius = glGetUniformLocation(rectProgram, "u_Radius");

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
            rectProgramModern = compileModernProgram(VERTEX_SRC_MODERN, FRAGMENT_SRC_MODERN);
            uRectModern = glGetUniformLocation(rectProgramModern, "u_Rect");
            uRadiusModern = glGetUniformLocation(rectProgramModern, "u_Radius");
            uColorRectModern = glGetUniformLocation(rectProgramModern, "uColor");
            uProjectionRectModern = glGetUniformLocation(rectProgramModern, "uProjection");
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
            flatProgramModern = compileModernProgram(VERTEX_SRC_MODERN, FLAT_FRAGMENT_SRC_MODERN);
            uColorFlatModern = glGetUniformLocation(flatProgramModern, "uColor");
            uProjectionFlatModern = glGetUniformLocation(flatProgramModern, "uProjection");
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
            int vsh = glCreateShader(0x8B31); // GL_VERTEX_SHADER
            glShaderSource(vsh, VERTEX_SRC);
            glCompileShader(vsh);

            int fsh = glCreateShader(0x8B30); // GL_FRAGMENT_SHADER
            glShaderSource(fsh, VIGNETTE_FRAGMENT_SRC);
            glCompileShader(fsh);

            vignetteProgram = glCreateProgram();
            glAttachShader(vignetteProgram, vsh);
            glAttachShader(vignetteProgram, fsh);
            glLinkProgram(vignetteProgram);

            uViewportSize = glGetUniformLocation(vignetteProgram, "u_ViewportSize");
            uVSize = glGetUniformLocation(vignetteProgram, "u_VSize");

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
            vignetteProgramModern = compileModernProgram(VERTEX_SRC_MODERN, VIGNETTE_FRAGMENT_SRC_MODERN);
            uViewportSizeModern = glGetUniformLocation(vignetteProgramModern, "u_ViewportSize");
            uVSizeModern = glGetUniformLocation(vignetteProgramModern, "u_VSize");
            uColorVignetteModern = glGetUniformLocation(vignetteProgramModern, "uColor");
            uProjectionVignetteModern = glGetUniformLocation(vignetteProgramModern, "uProjection");
            LauncherLog.ui(1, "[UiRenderer] shader vignette (moderne) compilé, program=" + vignetteProgramModern);
        } catch (Throwable t) {
            vignetteInitFailedModern = true;
            LauncherLog.err("[UiRenderer] échec compilation shader vignette moderne : " + t);
        }
    }

    private void ensureFxShaderInit() {
        if (fxProgram != -1 || fxInitFailed) return;
        try {
            int vsh = glCreateShader(0x8B31); // GL_VERTEX_SHADER
            glShaderSource(vsh, VERTEX_SRC);
            glCompileShader(vsh);

            int fsh = glCreateShader(0x8B30); // GL_FRAGMENT_SHADER
            glShaderSource(fsh, FX_FRAGMENT_SRC);
            glCompileShader(fsh);

            fxProgram = glCreateProgram();
            glAttachShader(fxProgram, vsh);
            glAttachShader(fxProgram, fsh);
            glLinkProgram(fxProgram);

            uFxRect = glGetUniformLocation(fxProgram, "u_Rect");
            uFxRadius = glGetUniformLocation(fxProgram, "u_Radius");
            uFxBlur = glGetUniformLocation(fxProgram, "u_Blur");
            uFxBorderWidth = glGetUniformLocation(fxProgram, "u_BorderWidth");
            uFxColorA = glGetUniformLocation(fxProgram, "u_ColorA");
            uFxColorB = glGetUniformLocation(fxProgram, "u_ColorB");
            uFxGradient = glGetUniformLocation(fxProgram, "u_Gradient");

            LauncherLog.ui(1, "[UiRenderer] shader FX compilé, program=" + fxProgram);
        } catch (Throwable t) {
            fxInitFailed = true;
            LauncherLog.err("[UiRenderer] échec compilation shader FX (ombre/contour/dégradé) : " + t);
        }
    }

    private void ensureFxShaderInitModern() {
        if (fxProgramModern != -1 || fxInitFailedModern) return;
        try {
            fxProgramModern = compileModernProgram(VERTEX_SRC_MODERN, FX_FRAGMENT_SRC_MODERN);
            uFxRectModern = glGetUniformLocation(fxProgramModern, "u_Rect");
            uFxRadiusModern = glGetUniformLocation(fxProgramModern, "u_Radius");
            uFxBlurModern = glGetUniformLocation(fxProgramModern, "u_Blur");
            uFxBorderWidthModern = glGetUniformLocation(fxProgramModern, "u_BorderWidth");
            uFxColorAModern = glGetUniformLocation(fxProgramModern, "u_ColorA");
            uFxColorBModern = glGetUniformLocation(fxProgramModern, "u_ColorB");
            uFxGradientModern = glGetUniformLocation(fxProgramModern, "u_Gradient");
            uProjectionFxModern = glGetUniformLocation(fxProgramModern, "uProjection");
            LauncherLog.ui(1, "[UiRenderer] shader FX (moderne) compilé, program=" + fxProgramModern);
        } catch (Throwable t) {
            fxInitFailedModern = true;
            LauncherLog.err("[UiRenderer] échec compilation shader FX moderne : " + t);
        }
    }

    /** {@code true} si le dégradé GPU est utilisable — sinon l'appelant peut se replier sur une approximation par bandes. */
    public boolean isVignetteAvailable() {
        if (modern) {
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
        if (modern) {
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
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
            glDisable(0x0C11); // GL_SCISSOR_TEST
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            glUseProgram(vignetteProgramModern);
            glUniform2f(uViewportSizeModern, vpWidth, vpHeight);
            glUniform1f(uVSizeModern, vSize);
            glUniform4f(uColorVignetteModern, edgeColor.r, edgeColor.g, edgeColor.b, edgeColor.a);
            uploadProjectionModern(uProjectionVignetteModern, vpWidth, vpHeight);
            drawQuadModern(0, 0, vpWidth, vpHeight);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawEdgeVignetteModern: " + t);
        } finally {
            try { glUseProgram(0); } catch (Throwable ignored) {}
        }
    }

    private void drawEdgeVignetteLegacy(UiColor edgeColor, float vSize, int vpWidth, int vpHeight) {
        ensureVignetteShaderInit();
        if (vignetteInitFailed) return;

        LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        try {
            // Legacy (1.8.9, Compatibility Profile) — voir captureLegacyGlState/
            // restoreLegacyGlState : sans restauration, nos glDisable(...)
            // restent appliqués en permanence après ce dessin, cassant le
            // rendu vanilla suivant (régression confirmée : monde/HUD tout
            // blanc + gros lag).
            savedGlState = captureLegacyGlState();
            glDisable(0x0DE1); // GL_TEXTURE_2D
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
            // GL_ALPHA_TEST : vanilla l'active avec glAlphaFunc(GL_GREATER, 0.1)
            // pour les textures découpées (feuilles, vitres...) — laissé actif
            // depuis le rendu du monde juste avant ce hook, TOUT pixel de notre
            // dégradé sous ~10% d'opacité (0.1) serait REJETÉ (pas blendé, pas
            // dessiné du tout) au lieu de fondre vers 0 — un rejet binaire, pas
            // un blend, d'où un "mur" net et invariant à toute courbe/opacité
            // testée jusqu'ici. C'était la vraie cause.
            glDisable(0x0BC0); // GL_ALPHA_TEST
            // Un GL_SCISSOR_TEST resté actif (ex: UiScrollContainer, si
            // endScissor() a sauté suite à une exception, voir son correctif)
            // découperait ce quad plein écran à un rectangle sans rapport —
            // symptôme observé : dégradé net et INVARIANT à toute retouche
            // d'opacité/courbe (un clip est binaire, pas un blend).
            glDisable(0x0C11); // GL_SCISSOR_TEST
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            matrixMode(0x1701); // GL_PROJECTION
            pushMatrix();
            projPushed = true;
            loadIdentity();
            glOrtho(0, vpWidth, 0, vpHeight, -1, 1);
            matrixMode(0x1700); // GL_MODELVIEW
            pushMatrix();
            modelPushed = true;
            loadIdentity();

            glUseProgram(vignetteProgram);
            glUniform2f(uViewportSize, vpWidth, vpHeight);
            glUniform1f(uVSize, vSize);
            drawQuad(0, 0, vpWidth, vpHeight, edgeColor);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawEdgeVignette: " + t);
        } finally {
            try { glUseProgram(0); } catch (Throwable ignored) {}
            try {
                if (modelPushed) { matrixMode(0x1700); popMatrix(); }
            } catch (Throwable ignored) {}
            try {
                if (projPushed) { matrixMode(0x1701); popMatrix(); }
            } catch (Throwable ignored) {}
            restoreLegacyGlState(savedGlState);
        }
    }

    private void ensureTextShaderInit() {
        if (textProgram != -1 || textInitFailed) return;
        try {
            int vsh = glCreateShader(0x8B31); // GL_VERTEX_SHADER
            glShaderSource(vsh, TEXT_VERTEX_SRC);
            glCompileShader(vsh);

            int fsh = glCreateShader(0x8B30); // GL_FRAGMENT_SHADER
            glShaderSource(fsh, TEXT_FRAGMENT_SRC);
            glCompileShader(fsh);

            textProgram = glCreateProgram();
            glAttachShader(textProgram, vsh);
            glAttachShader(textProgram, fsh);
            glLinkProgram(textProgram);

            uTex = glGetUniformLocation(textProgram, "u_Tex");

            LauncherLog.ui(1, "[UiRenderer] shader texte (SDF) compilé, program=" + textProgram + " uTex=" + uTex);
        } catch (Throwable t) {
            textInitFailed = true;
            LauncherLog.err("[UiRenderer] échec compilation shader texte SDF — texte non affiché : " + t);
        }
    }

    private void ensureTextShaderInitModern() {
        if (textProgramModern != -1 || textInitFailedModern) return;
        try {
            textProgramModern = compileModernProgram(VERTEX_SRC_MODERN, TEXT_FRAGMENT_SRC_MODERN);
            uTexModern = glGetUniformLocation(textProgramModern, "u_Tex");
            uColorTextModern = glGetUniformLocation(textProgramModern, "uColor");
            uProjectionTextModern = glGetUniformLocation(textProgramModern, "uProjection");
            LauncherLog.ui(1, "[UiRenderer] shader texte moderne (SDF) compilé, program=" + textProgramModern);
        } catch (Throwable t) {
            textInitFailedModern = true;
            LauncherLog.err("[UiRenderer] échec compilation shader texte SDF moderne — texte non affiché : " + t);
        }
    }

    private void ensureIconShaderInit() {
        if (iconProgram != -1 || iconInitFailed) return;
        try {
            int vsh = glCreateShader(0x8B31); // GL_VERTEX_SHADER
            glShaderSource(vsh, TEXT_VERTEX_SRC); // générique (ftransform + texcoord passthrough) — pas besoin d'un vertex shader dédié
            glCompileShader(vsh);
            int fsh = glCreateShader(0x8B30); // GL_FRAGMENT_SHADER
            glShaderSource(fsh, ICON_FRAGMENT_SRC);
            glCompileShader(fsh);
            iconProgram = glCreateProgram();
            glAttachShader(iconProgram, vsh);
            glAttachShader(iconProgram, fsh);
            glLinkProgram(iconProgram);
            uTexIcon = glGetUniformLocation(iconProgram, "u_Tex");
            uAlphaIcon = glGetUniformLocation(iconProgram, "u_Alpha");
            LauncherLog.ui(1, "[UiRenderer] shader icône compilé, program=" + iconProgram);
        } catch (Throwable t) {
            iconInitFailed = true;
            LauncherLog.err("[UiRenderer] échec compilation shader icône — icône non affichée : " + t);
        }
    }

    private void ensureIconShaderInitModern() {
        if (iconProgramModern != -1 || iconInitFailedModern) return;
        try {
            iconProgramModern = compileModernProgram(VERTEX_SRC_MODERN, ICON_FRAGMENT_SRC_MODERN);
            uTexIconModern = glGetUniformLocation(iconProgramModern, "u_Tex");
            uAlphaIconModern = glGetUniformLocation(iconProgramModern, "u_Alpha");
            uProjectionIconModern = glGetUniformLocation(iconProgramModern, "uProjection");
            LauncherLog.ui(1, "[UiRenderer] shader icône moderne compilé, program=" + iconProgramModern);
        } catch (Throwable t) {
            iconInitFailedModern = true;
            LauncherLog.err("[UiRenderer] échec compilation shader icône moderne — icône non affichée : " + t);
        }
    }

    /**
     * Charge (si pas déjà en cache pour {@code cacheKey}) et dessine une
     * icône RGBA quelconque (pastille de mod/pack téléchargée) — PAS un
     * ItemStack vanilla (voir {@link #drawVanillaItemIcon} pour ça,
     * désactivé sur era E). {@code x,y} = coin BAS-GAUCHE (origine bas-
     * gauche écran, comme drawRoundedRect/drawText — Y croissant vers le
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
     * DÉJÀ un rectangle arbitraire ({@code UiTextBlaze3D.queueIcon(x0,y0,x1,y1)}
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
     * côté Blaze3D (voir {@code UiTextBlaze3D#queueIcon}).
     */
    public void drawIcon(String cacheKey, java.awt.image.BufferedImage img, float x, float y, float w, float h,
                          float alpha, int vpWidth, int vpHeight) {
        if (img == null) return;
        if (UiTextBlaze3D.isAvailable()) {
            UiTextBlaze3D.queueIcon(cacheKey, img, x, y, x + w, y + h, alpha, vpWidth, vpHeight);
            return;
        }
        int texId = ensureIconTexture(cacheKey, img);
        if (texId < 0) return;

        if (modern) {
            ensureIconShaderInitModern();
            if (iconInitFailedModern) return;
            try {
                glDisable(0x0B71); // GL_DEPTH_TEST
                glDisable(0x0B44); // GL_CULL_FACE
                // PAS de glDisable(GL_SCISSOR_TEST) ici (contrairement à
                // drawEdgeVignette*, effet plein écran qui doit légitimement
                // l'ignorer) — BUG TROUVÉ (utilisateur : "il faut que toute la
                // card passe à travers pour disparaître", voir UiScrollContainer)
                // : ce disable, copié-collé du garde-fou de drawEdgeVignette,
                // défaisait silencieusement le clip actif de UiScrollContainer
                // pour CHAQUE icône dessinée à l'intérieur — un scissor actif
                // (posé par UiScrollContainer.beginScissor) doit au contraire
                // continuer à s'appliquer ici.
                glEnable(0x0BE2);  // GL_BLEND
                glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA
                glActiveTexture(0x84C0); // GL_TEXTURE0
                glBindTexture(0x0DE1, texId);
                glUseProgram(iconProgramModern);
                glUniform1i(uTexIconModern, 0);
                glUniform1f(uAlphaIconModern, alpha);
                uploadProjectionModern(uProjectionIconModern, vpWidth, vpHeight);

                // UV : (x,y+h)=visuel HAUT-gauche (Y-up) ↔ (0,0)=image
                // haut-gauche (convention image standard) — même
                // correspondance que UiTextBlaze3D.drawIcon (voir sa javadoc).
                ensureModernBuffersInit();
                if (!modernBuffersInitFailed) {
                    FloatBuffer verts = floatBuffer(4 * 4);
                    putVertex(verts, x, y + h, 0f, 0f);
                    putVertex(verts, x, y, 0f, 1f);
                    putVertex(verts, x + w, y, 1f, 1f);
                    putVertex(verts, x + w, y + h, 1f, 0f);
                    verts.flip();
                    uploadAndDraw(verts, 6, 4); // GL_TRIANGLE_FAN
                }
            } catch (Throwable t) {
                LauncherLog.err("[UiRenderer] drawIcon (moderne): " + t);
            } finally {
                try { glUseProgram(0); } catch (Throwable ignored) {}
            }
            return;
        }

        ensureIconShaderInit();
        if (iconInitFailed) return;
        LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        try {
            savedGlState = captureLegacyGlState();
            glEnable(0x0DE1);  // GL_TEXTURE_2D
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
            glDisable(0x0BC0); // GL_ALPHA_TEST
            // PAS de glDisable(GL_SCISSOR_TEST) — voir le commentaire équivalent
            // dans la branche moderne juste au-dessus pour le pourquoi (BUG
            // TROUVÉ, UiScrollContainer).
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303);
            glBindTexture(0x0DE1, texId);
            glUseProgram(iconProgram);
            glUniform1i(uTexIcon, 0);
            glUniform1f(uAlphaIcon, alpha);

            matrixMode(0x1701); // GL_PROJECTION
            pushMatrix();
            projPushed = true;
            loadIdentity();
            glOrtho(0, vpWidth, 0, vpHeight, -1, 1);
            matrixMode(0x1700); // GL_MODELVIEW
            pushMatrix();
            modelPushed = true;
            loadIdentity();

            glColor4f(1f, 1f, 1f, 1f);
            glBegin(7); // GL_QUADS
            glTexCoord2f(0f, 0f); glVertex2f(x, y + h);
            glTexCoord2f(0f, 1f); glVertex2f(x, y);
            glTexCoord2f(1f, 1f); glVertex2f(x + w, y);
            glTexCoord2f(1f, 0f); glVertex2f(x + w, y + h);
            glEnd();
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawIcon (legacy): " + t);
        } finally {
            try { glUseProgram(0); } catch (Throwable ignored) {}
            if (modelPushed) { try { matrixMode(0x1700); popMatrix(); } catch (Throwable ignored) {} }
            if (projPushed) { try { matrixMode(0x1701); popMatrix(); } catch (Throwable ignored) {} }
            if (savedGlState != null) restoreLegacyGlState(savedGlState);
        }
    }

    /** Upload GL brut (glTexImage2D), mis en cache par cacheKey — voir createFontTextureRaw pour le même motif appliqué aux polices. */
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

            int texId = glGenTextures();
            glBindTexture(0x0DE1, texId);
            glTexImage2D(0x0DE1, 0, 0x1908, w, h, 0, 0x1908, 0x1401, buf); // GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE
            glTexParameteri(0x0DE1, 0x2801, 0x2601); // GL_TEXTURE_MIN_FILTER, GL_LINEAR (pas de mipmap — icônes rarement minifiées fortement)
            glTexParameteri(0x0DE1, 0x2800, 0x2601); // GL_TEXTURE_MAG_FILTER, GL_LINEAR
            glTexParameteri(0x0DE1, 0x2802, 0x812F); // GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE
            glTexParameteri(0x0DE1, 0x2803, 0x812F); // GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE
            // Même précaution ZGC que createFontTextureRaw (voir son
            // commentaire pour le pourquoi) — coût négligeable ici (upload
            // UNE SEULE FOIS par icône, jamais par frame).
            glFinish();
            reachabilityFence(buf);

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
     * radius=0 → rect plein classique. Fallback silencieux vers un quad plein
     * (pas d'arrondi) si la compilation shader a échoué sur cette version/GPU.
     *
     * @param vpWidth  largeur totale du viewport (framebuffer), PAS la largeur
     *                 de ce rect précis — nécessaire pour poser une projection
     *                 orthographique correcte (voir plus bas), indépendamment
     *                 de la taille du rect dessiné.
     * @param vpHeight idem, hauteur totale du viewport.
     */
    // BUG TROUVÉ #1 (glisser un panneau HUD dans l'éditeur — rectangles et
    // texte désynchronisés visuellement) : essayé de différer juste les
    // rectangles GL bruts d'une frame (pour rester synchronisés avec le
    // texte, lui-même différé, voir UiTextBlaze3D) — SANS AUCUN EFFET une
    // fois testé (preuve que ce n'était PAS un problème de timing, voir BUG
    // TROUVÉ #2 dans UiTextBlaze3D — axe Y de la projection inversé).
    //
    // BUG TROUVÉ #2 (une fois le texte enfin bien positionné, HUD ET menus) :
    // TOUT rect GL brut (TAIL, APRÈS presentTexture()) compose TOUJOURS
    // par-dessus TOUT texte Blaze3D (HEAD, déjà présenté) — pas seulement le
    // HUD : les cartes/lignes de la liste de mods, les onglets des
    // paramètres, etc. avaient EXACTEMENT le même problème (texte illisible/
    // invisible sous le fond). Fix définitif, généralisé à drawRoundedRect
    // LUI-MÊME (pas juste une variante "Hud" séparée) : sur era E, route par
    // le MÊME pipeline Blaze3D que le texte (voir UiTextBlaze3D#queueRect —
    // même file d'attente, même ordre d'insertion que l'appelant : un fond
    // empilé AVANT son texte est composé AVANT lui, garantissant le bon
    // z-order PARTOUT, pas juste en jeu). Coins arrondis simulés via une
    // texture de masque (9-slice), le pipeline GUI_TEXT réutilisé n'ayant pas
    // de shader à distance signée dédié.
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
        if (UiTextBlaze3D.isAvailable()) {
            UiTextBlaze3D.queueRect(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
            return;
        }
        if (modern) {
            drawRoundedRectModern(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
            return;
        }
        drawRoundedRectLegacy(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
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
        if (UiTextBlaze3D.isAvailable()) return;
        drawFx(x1 - spread, y1 - spread, x2 + spread, y2 + spread, radius + spread, blur, 0f,
            color, color, false, vpWidth, vpHeight);
    }

    /** Contour creux (anneau) d'épaisseur {@code borderWidth}, coins arrondis — le rect lui-même reste transparent (à dessiner par-dessus un fond déjà posé avec {@link #drawRoundedRect}, pas à sa place). */
    public void drawRoundedRectBorder(float x1, float y1, float x2, float y2, float radius, float borderWidth,
                                       UiColor color, int vpWidth, int vpHeight) {
        drawFx(x1, y1, x2, y2, radius, 0f, borderWidth, color, color, false, vpWidth, vpHeight);
    }

    /**
     * Dégradé vertical {@code colorBottom} (bord y1) → {@code colorTop} (bord y2), coins arrondis optionnels (radius=0 = rect plein).
     * Sur era E, route par Blaze3D (même z-order garanti que le texte, voir
     * {@link #drawRoundedRect}) — BUG TROUVÉ (fond de sidebar, en GL brut,
     * composait par-dessus le texte des items de la sidebar, Blaze3D : texte
     * invisible) : couleur portée PAR SOMMET (interpolée par le GPU),
     * ColorModulator neutre, voir UiTextBlaze3D#drawGradientRect.
     */
    public void drawGradientRect(float x1, float y1, float x2, float y2, float radius,
                                  UiColor colorBottom, UiColor colorTop, int vpWidth, int vpHeight) {
        if (UiTextBlaze3D.isAvailable()) {
            UiTextBlaze3D.queueGradientRect(x1, y1, x2, y2, radius, colorBottom, colorTop, vpWidth, vpHeight);
            return;
        }
        drawFx(x1, y1, x2, y2, radius, 0f, 0f, colorBottom, colorTop, true, vpWidth, vpHeight);
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
        if (modern) {
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
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            if (useShader) {
                glUseProgram(rectProgramModern);
                glUniform4f(uRectModern, x1, y1, x2, y2);
                glUniform1f(uRadiusModern, radius);
                glUniform4f(uColorRectModern, color.r, color.g, color.b, color.a);
                uploadProjectionModern(uProjectionRectModern, vpWidth, vpHeight);
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
                    glUseProgram(flatProgramModern);
                    glUniform4f(uColorFlatModern, color.r, color.g, color.b, color.a);
                    uploadProjectionModern(uProjectionFlatModern, vpWidth, vpHeight);
                } else {
                    return;
                }
            }
            drawQuadModern(x1, y1, x2, y2);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawRoundedRectModern: " + t);
        } finally {
            try { glUseProgram(0); } catch (Throwable ignored) {}
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
        LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        try {
            // Legacy (1.8.9) — voir captureLegacyGlState()/drawEdgeVignetteLegacy.
            savedGlState = captureLegacyGlState();
            glDisable(0x0DE1); // GL_TEXTURE_2D
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE — sinon un quad mal orienté (winding) par rapport à ce que
                                // le rendu 3D du monde a laissé actif peut être silencieusement éliminé,
                                // sans erreur : dessin "réussi" en apparence, rien de visible en jeu.
            glDisable(0x0BC0); // GL_ALPHA_TEST — voir drawEdgeVignette pour le pourquoi
            // PAS de glDisable(GL_SCISSOR_TEST) — voir UiScrollContainer
            // (javadoc de classe) : ce disable défaisait le clip actif d'un
            // scroll container pour CHAQUE rect dessiné à l'intérieur.
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            // ftransform() (vertex shader) applique la matrice modelview/projection
            // COURANTE — à ce point précis (TAIL de GameRenderer.render()), rien ne
            // garantit qu'elle soit une projection 2D pixel-space : ça peut encore
            // être la perspective 3D du monde, auquel cas nos coordonnées pixel
            // (ex: 1920,1057) sortent totalement du frustum et sont clippées, d'où
            // rien de visible malgré un dessin "réussi" côté code. On pose donc
            // NOTRE PROPRE ortho, empilée puis restaurée, indépendante de l'état
            // ambiant. Bas-gauche origine Y-up (glOrtho(0,w,0,h,...)) — cohérent
            // avec gl_FragCoord et le reste du pipeline (mouse/UI), pas de flip.
            matrixMode(0x1701); // GL_PROJECTION
            pushMatrix();
            projPushed = true;
            loadIdentity();
            glOrtho(0, vpWidth, 0, vpHeight, -1, 1);
            matrixMode(0x1700); // GL_MODELVIEW
            pushMatrix();
            modelPushed = true;
            loadIdentity();

            if (useShader) {
                glUseProgram(rectProgram);
                glUniform4f(uRect, x1, y1, x2, y2);
                glUniform1f(uRadius, radius);
            }
            drawQuad(x1, y1, x2, y2, color);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawRoundedRect: " + t);
        } finally {
            try {
                if (useShader) glUseProgram(0);
            } catch (Throwable ignored) {}
            try {
                if (modelPushed) { matrixMode(0x1700); popMatrix(); }
            } catch (Throwable ignored) {}
            try {
                if (projPushed) { matrixMode(0x1701); popMatrix(); }
            } catch (Throwable ignored) {}
            restoreLegacyGlState(savedGlState);
        }
    }

    private void drawFxModern(float x1, float y1, float x2, float y2, float radius, float blur, float borderWidth,
                               UiColor colorA, UiColor colorB, boolean gradient, int vpWidth, int vpHeight) {
        ensureFxShaderInitModern();
        if (fxInitFailedModern) return;
        try {
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
            // PAS de glDisable(GL_SCISSOR_TEST) — voir UiScrollContainer
            // (javadoc de classe) : défaisait le clip actif d'un scroll
            // container pour chaque ombre/bordure/dégradé dessiné à l'intérieur.
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            glUseProgram(fxProgramModern);
            glUniform4f(uFxRectModern, x1, y1, x2, y2);
            glUniform1f(uFxRadiusModern, radius);
            glUniform1f(uFxBlurModern, blur);
            glUniform1f(uFxBorderWidthModern, borderWidth);
            glUniform4f(uFxColorAModern, colorA.r, colorA.g, colorA.b, colorA.a);
            glUniform4f(uFxColorBModern, colorB.r, colorB.g, colorB.b, colorB.a);
            glUniform1f(uFxGradientModern, gradient ? 1f : 0f);
            uploadProjectionModern(uProjectionFxModern, vpWidth, vpHeight);
            // u_Rect (ci-dessus) garde la boîte LOGIQUE exacte (nécessaire au
            // calcul de distance) mais le quad RASTÉRISÉ doit déborder de
            // "blur" pixels au-delà — sinon aucun pixel n'existe au-delà de
            // (x1,y1)-(x2,y2) pour recevoir la fin du dégradé, qui se
            // retrouve coupé net exactement sur ce bord (confirmé en jeu :
            // "carré" visible autour d'un halo pourtant mathématiquement
            // circulaire — la formule de distance était correcte, seule la
            // géométrie dessinée était trop petite pour la montrer en entier).
            float pad = Math.max(blur, 1f);
            drawQuadModern(x1 - pad, y1 - pad, x2 + pad, y2 + pad);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawFxModern: " + t);
        } finally {
            try { glUseProgram(0); } catch (Throwable ignored) {}
        }
    }

    private void drawFxLegacy(float x1, float y1, float x2, float y2, float radius, float blur, float borderWidth,
                               UiColor colorA, UiColor colorB, boolean gradient, int vpWidth, int vpHeight) {
        ensureFxShaderInit();
        if (fxInitFailed) return;

        LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        try {
            // Même garde que drawRoundedRectLegacy — voir son commentaire pour
            // le détail de chaque état désactivé/pourquoi.
            savedGlState = captureLegacyGlState();
            glDisable(0x0DE1); // GL_TEXTURE_2D
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
            glDisable(0x0BC0); // GL_ALPHA_TEST
            // PAS de glDisable(GL_SCISSOR_TEST) — voir UiScrollContainer
            // (javadoc de classe).
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            matrixMode(0x1701); // GL_PROJECTION
            pushMatrix();
            projPushed = true;
            loadIdentity();
            glOrtho(0, vpWidth, 0, vpHeight, -1, 1);
            matrixMode(0x1700); // GL_MODELVIEW
            pushMatrix();
            modelPushed = true;
            loadIdentity();

            glUseProgram(fxProgram);
            glUniform4f(uFxRect, x1, y1, x2, y2);
            glUniform1f(uFxRadius, radius);
            glUniform1f(uFxBlur, blur);
            glUniform1f(uFxBorderWidth, borderWidth);
            glUniform4f(uFxColorA, colorA.r, colorA.g, colorA.b, colorA.a);
            glUniform4f(uFxColorB, colorB.r, colorB.g, colorB.b, colorB.a);
            glUniform1f(uFxGradient, gradient ? 1f : 0f);
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
            try { glUseProgram(0); } catch (Throwable ignored) {}
            try {
                if (modelPushed) { matrixMode(0x1700); popMatrix(); }
            } catch (Throwable ignored) {}
            try {
                if (projPushed) { matrixMode(0x1701); popMatrix(); }
            } catch (Throwable ignored) {}
            restoreLegacyGlState(savedGlState);
        }
    }

    private void drawQuad(float x1, float y1, float x2, float y2, UiColor c) {
        try {
            glColor4f(c.r, c.g, c.b, c.a);
            glBegin(7); // GL_QUADS
            glVertex2f(x1, y1);
            glVertex2f(x1, y2);
            glVertex2f(x2, y2);
            glVertex2f(x2, y1);
            glEnd();
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawQuad: " + t);
        }
    }

    // ── Helpers partagés du pipeline MODERNE (voir javadoc de la classe) ────

    /**
     * Compile+lie un programme moderne (GLSL 150) — {@code aPos}/{@code aTexCoord}
     * liés respectivement aux emplacements 0/1 AVANT le link (glBindAttribLocation),
     * pour que {@link #ensureModernBuffersInit()} puisse configurer UN SEUL VAO
     * réutilisable par les 3 shaders (rect/vignette/texte), au lieu d'interroger
     * un emplacement différent par programme.
     */
    /**
     * VÉRIFICATION JAMAIS FAITE JUSQU'ICI (voir historique du projet) : une
     * erreur de compilation/link GLSL ne lève AUCUNE exception Java —
     * glCompileShader/glLinkProgram "réussissent" toujours du point de vue
     * Java même si le shader résultant est invalide, seul
     * glGetShaderiv(GL_COMPILE_STATUS)/glGetProgramiv(GL_LINK_STATUS)
     * révèle le vrai résultat. Utiliser un programme qui a échoué à lier est
     * un comportement indéfini côté spec — concrètement, observé ici : draws
     * qui s'exécutent sans aucune erreur mais qui n'affichent RIEN, aucune
     * exception nulle part.
     */
    private void checkShaderCompile(int shader, String label) throws Exception {
        int status = glGetShaderi(shader, 0x8B81); // GL_COMPILE_STATUS
        if (status == 0) {
            String log = glGetShaderInfoLog(shader);
            LauncherLog.err("[UiRenderer] ÉCHEC COMPILATION shader " + label + ": " + log);
        }
    }

    private void checkProgramLink(int program, String label) throws Exception {
        int status = glGetProgrami(program, 0x8B82); // GL_LINK_STATUS
        if (status == 0) {
            String log = glGetProgramInfoLog(program);
            LauncherLog.err("[UiRenderer] ÉCHEC LINK programme " + label + ": " + log);
        } else {
            LauncherLog.ui(1, "[UiRenderer] programme " + label + " lié avec succès (program=" + program + ")");
        }
    }

    private int compileModernProgram(String vertexSrc, String fragmentSrc) throws Exception {
        int vsh = glCreateShader(0x8B31); // GL_VERTEX_SHADER
        glShaderSource(vsh, vertexSrc);
        glCompileShader(vsh);
        checkShaderCompile(vsh, "vertex");

        int fsh = glCreateShader(0x8B30); // GL_FRAGMENT_SHADER
        glShaderSource(fsh, fragmentSrc);
        glCompileShader(fsh);
        checkShaderCompile(fsh, "fragment");

        int program = glCreateProgram();
        glAttachShader(program, vsh);
        glAttachShader(program, fsh);
        glBindAttribLocation(program, 0, "aPos");
        glBindAttribLocation(program, 1, "aTexCoord");
        glLinkProgram(program);
        checkProgramLink(program, "modern(" + vsh + "," + fsh + ")");
        return program;
    }

    /** VAO + VBO partagés — layout fixe : vec2 position (loc 0) + vec2 texCoord (loc 1), 4 floats/sommet. */
    private void ensureModernBuffersInit() {
        if (modernVao != -1 || modernBuffersInitFailed) return;
        try {
            LauncherLog.info("[UiRenderer] DIAG3: avant glGenVertexArrays");
            modernVao = glGenVertexArrays();
            LauncherLog.info("[UiRenderer] DIAG3: avant glBindVertexArray vao=" + modernVao);
            glBindVertexArray(modernVao);
            LauncherLog.info("[UiRenderer] DIAG3: avant glGenBuffers");
            modernVbo = glGenBuffers();
            LauncherLog.info("[UiRenderer] DIAG3: avant glBindBuffer vbo=" + modernVbo);
            glBindBuffer(0x8892, modernVbo); // GL_ARRAY_BUFFER
            LauncherLog.info("[UiRenderer] DIAG3: avant glEnableVertexAttribArray/glVertexAttribPointer");
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 2, 0x1406, false, 16, 0L);  // GL_FLOAT, stride=4*4=16, offset=0
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 2, 0x1406, false, 16, 8L);  // offset=2*4=8 (après x,y)
            glBindVertexArray(0);
            LauncherLog.ui(1, "[UiRenderer] VAO/VBO modernes initialisés (vao=" + modernVao + ", vbo=" + modernVbo + ")");
        } catch (Throwable t) {
            modernBuffersInitFailed = true;
            LauncherLog.err("[UiRenderer] échec init VAO/VBO moderne — rien ne sera dessiné (pipeline moderne) : " + t);
            return;
        }
        // TENTATIVE ABANDONNÉE (voir historique de session) : préchauffer les
        // 2 atlas de police ICI (juste après l'init VAO/VBO, à froid) pour
        // éviter une création "tardive" de BOLD — RÉGRESSION CONSTATÉE EN JEU :
        // le crash natif se produit maintenant sur le TOUT PREMIER
        // glTexImage2D (REGULAR), immédiatement, alors qu'avant ce
        // changement REGULAR réussissait de façon fiable sur PLUSIEURS
        // sessions de test. La cause n'est donc PAS "création tardive après
        // beaucoup d'activité GL" (hypothèse infirmée) — le crash semble
        // plus intermittent/imprévisible qu'un simple ordre de création,
        // possible piste : un vrai crash driver GPU (TDR) sans rapport
        // causal direct avec CE glTexImage2D précis, qui ne fait que se
        // trouver être l'appel GL le plus distinctif en cours au moment où
        // le driver plante. Retiré — retour au comportement paresseux
        // d'origine (chaque police créée à la demande, seulement quand un
        // texte l'utilisant est dessiné pour la première fois).
    }

    /** Direct, comme exigé par tout buffer réellement uploadé en GL (glBufferData attend un buffer NIO natif). */
    private FloatBuffer floatBuffer(int capacityFloats) {
        return java.nio.ByteBuffer.allocateDirect(capacityFloats * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private void putVertex(FloatBuffer buf, float x, float y, float u, float v) {
        buf.put(x).put(y).put(u).put(v);
    }

    /** Un seul quad plein écran/rect (rect arrondi, vignette) — 4 sommets, GL_TRIANGLE_FAN (même topologie que l'ancien GL_QUADS). */
    private void drawQuadModern(float x1, float y1, float x2, float y2) {
        ensureModernBuffersInit();
        if (modernBuffersInitFailed) return;
        try {
            FloatBuffer verts = floatBuffer(4 * 4);
            putVertex(verts, x1, y1, 0f, 0f);
            putVertex(verts, x1, y2, 0f, 1f);
            putVertex(verts, x2, y2, 1f, 1f);
            putVertex(verts, x2, y1, 1f, 0f);
            verts.flip();
            uploadAndDraw(verts, 6, 4); // GL_TRIANGLE_FAN
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawQuadModern: " + t);
        }
    }

    /** Sommets déjà préparés en GL_TRIANGLES (ex: texte, un ou plusieurs quads disjoints, 6 sommets/quad). */
    private void drawTrianglesModern(FloatBuffer verts) {
        ensureModernBuffersInit();
        if (modernBuffersInitFailed) return;
        try {
            uploadAndDraw(verts, 4, verts.remaining() / 4); // GL_TRIANGLES
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawTrianglesModern: " + t);
        }
    }

    private static boolean fboDiagLogged = false;

    private void uploadAndDraw(FloatBuffer verts, int glMode, int vertexCount) throws Exception {
        // DIAGNOSTIC : quel framebuffer est actif à ce point précis (TAIL de
        // GameRenderer.render()) ? 0x8CA6 = GL_FRAMEBUFFER_BINDING.
        //
        // BUG TROUVÉ (comparaison 1.16.5 vs 1.20.4, voir historique de
        // session) : ce log vaut TOUJOURS 0 en 1.16.5 (le blit vers la
        // fenêtre se fait DANS GameRenderer.render() avant notre TAIL), mais
        // vaut 1 (un FBO hors-écran, le vrai "main target" de Minecraft,
        // MinecraftClient.getFramebuffer()) en 1.20.4 — le blit final vers la
        // fenêtre s'y fait APRÈS le retour de render(), donc PLUS TARD que
        // notre hook. L'ancien code forçait ICI un rebind vers 0 ("par
        // sécurité"), ce qui envoyait nos dessins dans un framebuffer JAMAIS
        // affiché sur ce bracket (rien de visible malgré des draws sans
        // erreur, alors même que l'écran vanilla sous-jacent s'assombrissait
        // normalement). Ne JAMAIS forcer 0 : le framebuffer déjà lié à ce
        // point est, empiriquement sur les deux brackets testés, TOUJOURS
        // celui qui finit par être affiché — s'y fier plutôt que d'imposer
        // une cible fixe.
        if (!fboDiagLogged) {
            fboDiagLogged = true;
            try {
                int fb = glGetInteger(0x8CA6);
                LauncherLog.info("[UiRenderer] DIAG5: framebuffer actif au moment du dessin = " + fb
                    + " (0 = framebuffer par défaut/fenêtre — non-zéro = FBO hors-écran, dessiné dedans quand même)");
            } catch (Throwable t) {
                LauncherLog.err("[UiRenderer] DIAG5: échec lecture framebuffer actif: " + t);
            }
        }

        glBindVertexArray(modernVao);
        glBindBuffer(0x8892, modernVbo); // GL_ARRAY_BUFFER
        // GL_DYNAMIC_DRAW (0x88E8) : contenu réécrit à chaque draw (HUD redessiné
        // chaque frame), jamais GL_STATIC_DRAW qui suppose un contenu stable.
        glBufferData(0x8892, verts, 0x88E8);
        glDrawArrays(glMode, 0, vertexCount);
        glBindVertexArray(0);
    }

    /**
     * Matrice de projection orthographique équivalente à
     * {@code glOrtho(0, vpWidth, 0, vpHeight, -1, 1)} (voir drawRoundedRectLegacy
     * pour le pourquoi de cette convention bas-gauche origine, Y-up) — uploadée
     * en tant que {@code uniform mat4}, remplace la pile de matrices fixe
     * (glMatrixMode/glPushMatrix/glOrtho), absente/cassée en Core Profile.
     * Colonne-majeure (convention OpenGL/GLSL).
     */
    private static boolean loggedBadProjectionLoc = false;

    private void uploadProjectionModern(int uniformLoc, int vpWidth, int vpHeight) throws Exception {
        // -1 = uniform introuvable/optimisé — glUniformMatrix4fv est alors un
        // NO-OP SILENCIEUX (spec GL) : le shader garderait sa valeur par
        // défaut (matrice ZÉRO), donc gl_Position = 0 pour CHAQUE sommet —
        // tout devient un point dégénéré invisible, sans aucune erreur nulle
        // part. Log une seule fois si ça arrive, ça confirmerait direct la cause.
        if (uniformLoc < 0 && !loggedBadProjectionLoc) {
            loggedBadProjectionLoc = true;
            LauncherLog.err("[UiRenderer] DIAG6: uProjection introuvable (location=" + uniformLoc
                + ") — la matrice ne sera JAMAIS appliquée, rendu invisible garanti");
        }
        FloatBuffer m = floatBuffer(16);
        float w = vpWidth, h = vpHeight;
        m.put(2f / w).put(0f).put(0f).put(0f);
        m.put(0f).put(2f / h).put(0f).put(0f);
        m.put(0f).put(0f).put(-1f).put(0f);
        m.put(-1f).put(-1f).put(0f).put(1f);
        m.flip();
        glUniformMatrix4fv(uniformLoc, false, m);
    }

    // ── Icône d'objet vanilla (ItemRenderer, immediate-mode/fixed-function) ──

    /**
     * Dessine l'icône RÉELLE d'un ItemStack (modèle vanilla, pas un rectangle
     * de substitution) via {@code ItemRenderer.renderInGuiWithOverrides}, en
     * pontant vers le pipeline fixed-function de vanilla depuis notre propre
     * pipeline shader — PREMIÈRE utilisation de ce pont dans le projet, voir
     * ArmorDurabilityModule pour le premier appelant.
     *
     * {@code x}/{@code y} en NOTRE convention (coin BAS-gauche de l'icône,
     * origine bas-gauche écran, comme drawRoundedRect/drawText) — convertis en
     * interne vers la convention vanilla (origine HAUT-gauche, Y vers le bas)
     * car {@code renderInGuiWithOverrides} attend ses coordonnées ainsi.
     *
     * {@code size} — taille RÉELLE souhaitée en pixels physiques (mêmes
     * unités que le reste de notre UI). {@code renderInGuiWithOverrides}
     * dessine TOUJOURS un carré de 16 unités, point — sans notre propre mise à
     * l'échelle ({@code glScalef}), l'icône ressortait à 16 pixels PHYSIQUES
     * bruts, minuscule sur un écran moderne (vanilla ne paraît correct que
     * multiplié par son "GUI Scale", que notre pipeline ignore volontairement
     * partout ailleurs — d'où la nécessité de compenser ici spécifiquement).
     *
     * Ortho (0,vpW, vpH,0, 1000,3000) + translate(0,0,-2000) : convention de
     * profondeur GUI vanilla historique (LWJGL2) — sans elle, le zLevel
     * interne du rendu d'item (petit, proche de 0) tomberait hors de la plage
     * de clipping et l'icône resterait invisible malgré un appel "réussi".
     */
    private static boolean modernItemIconWarned = false;

    // AUDIT PERF (demandé explicitement par l'utilisateur, "gratter des fps
    // 26.1.2") : guiScale() fait 2 invocations de réflexion (getWindow +
    // getScaledWidth) — appelé plusieurs fois PAR FRAME (ArmorDurabilityModule
    // style "Vanilla" : une fois pour drawVanillaHotbarRow, puis une fois DE
    // PLUS par icône dans drawVanillaItemIconModern*, jusqu'à 5x/frame pour ce
    // seul module). La valeur ne dépend QUE de vpWidth ET du réglage "GUI
    // Scale" vanilla — quasi-constante frame à frame (change seulement au
    // resize de fenêtre ou changement du réglage, pas en continu) : cache
    // court (200ms) plutôt qu'un cache infini keyed sur vpWidth seul — un
    // changement du réglage "GUI Scale" SANS resize de fenêtre (vpWidth
    // inchangé) doit rester détecté, juste avec un délai borné au lieu
    // d'être invisible indéfiniment.
    private static float cachedGuiScale = 1f;
    private static int cachedGuiScaleVpWidth = -1;
    private static long cachedGuiScaleAtNanos;
    private static final long GUI_SCALE_CACHE_NANOS = 200_000_000L; // 200 ms

    /**
     * Ratio pixels FRAMEBUFFER / pixels GUI-SCALED de vanilla (voir "GUI
     * Scale" dans les options vidéo) — même calcul que celui dupliqué en
     * interne par drawVanillaItemIconModern* (voir plus bas), exposé ici pour
     * les modules qui doivent positionner du contenu en coordonnées relatives
     * au HUD vanilla (ex: ArmorDurabilityModule, style "Vanilla" — aligné sur
     * la vraie hotbar, 182x22 GUI-pixels, largeur/hauteur bien connues et
     * stables depuis toujours). {@code 1f} si non résolvable (aucune fenêtre
     * trouvée) — mieux qu'une exception pour un appelant qui ferait juste
     * {@code framebufferPx / guiScale(vpWidth)}.
     */
    public static float guiScale(int vpWidth) {
        long now = System.nanoTime();
        if (vpWidth == cachedGuiScaleVpWidth && (now - cachedGuiScaleAtNanos) < GUI_SCALE_CACHE_NANOS) {
            return cachedGuiScale;
        }
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return 1f;
            Object window = McReflect.method(mc.getClass(), "net/minecraft/client/MinecraftClient", "getWindow", "getWindow").invoke(mc);
            int scaledW = (int) McReflect.method(window.getClass(), "net/minecraft/client/util/Window", "getScaledWidth", "getGuiScaledWidth").invoke(window);
            cachedGuiScale = scaledW > 0 ? (float) vpWidth / scaledW : 1f;
            cachedGuiScaleVpWidth = vpWidth;
            cachedGuiScaleAtNanos = now;
            return cachedGuiScale;
        } catch (Throwable t) {
            return 1f;
        }
    }

    public void drawVanillaItemIcon(Object itemStack, float x, float y, float size, int vpWidth, int vpHeight) {
        drawVanillaItemIcon(itemStack, x, y, size, vpWidth, vpHeight, false);
    }

    /**
     * Blit BRUT (pas via l'atlas de sprites, contrairement au fond de case
     * "hud/hotbar_offhand_left" utilisé par {@link #drawVanillaItemIcon}
     * withDurabilityBar) d'une texture GUI vanilla ARBITRAIRE — ex: fond de
     * fenêtre de conteneur ({@code "textures/gui/container/shulker_box.png"},
     * 256x256, région visible 176x166 dans son coin haut-gauche) — pour
     * {@code ShulkerPreviewModule}, qui veut le VRAI fond vanilla (donc
     * personnalisable par resource pack) plutôt qu'un panneau recréé.
     *
     * @param texturePath chemin RELATIF (sans "assets/minecraft/", avec
     *     l'extension ".png") — ex: {@code "textures/gui/container/shulker_box.png"}.
     * @param x,y,w,h position/taille d'affichage à l'ÉCRAN, en pixels
     *     framebuffer (repère bas-gauche de ce projet — voir javadoc de
     *     classe), PAS en GUI-pixels — même convention que drawVanillaItemIcon.
     * @param u,v,texW,texH région source dans la texture ET dimensions
     *     RÉELLES du fichier PNG (256x256 pour shulker_box.png, PAS
     *     176x166 — c'est un atlas, voir javadoc de ShulkerPreviewModule).
     *
     * Bracket 26.1.2/1.21.11 (chemin "Deferred", voir
     * modernUsesDeferredGuiRenderer) — mise en file, flush différé (voir
     * GuiFlushMixin). 1.20.4/1.21.4 (chemin "Immediate") : voir {@link
     * #drawVanillaContainerTextureModernImmediate} — dessin synchrone, pas de
     * file d'attente. 1.8.9 : no-op silencieux, aucun appelant actuel ne le cible.
     */
    public void drawVanillaContainerTexture(String texturePath, float x, float y, float w, float h,
                                             float u, float v, float texW, float texH, int vpWidth, int vpHeight) {
        if (!modern) return;
        if (modernUsesDeferredGuiRenderer()) {
            try {
                float guiScale = guiScale(vpWidth);
                int guiX = Math.round(x / guiScale);
                int guiY = Math.round((vpHeight - y - h) / guiScale);
                int guiW = Math.round(w / guiScale);
                int guiH = Math.round(h / guiScale);
                synchronized (pendingModernGuiBlits) {
                    pendingModernGuiBlits.add(new PendingGuiBlit(texturePath, guiX, guiY, guiW, guiH, u, v, texW, texH));
                }
            } catch (Throwable ignored) {}
        } else {
            drawVanillaContainerTextureModernImmediate(texturePath, x, y, w, h, u, v, texW, texH, vpWidth, vpHeight);
        }
    }

    private static Method drawTextureMethodImmediate;
    private static Method getGuiTexturedMethodImmediate;
    private static Object guiTexturedFunctionProxyImmediate;
    private static boolean containerBlitImmediateResolveFailed;

    /**
     * Bracket 1.20.4/1.21.4 (pipeline "Immediate", pas de {@code
     * RenderPipeline}/{@code GuiRenderState} — voir {@link
     * #drawVanillaItemIconModernImmediate}) — {@code DrawContext.drawTexture}
     * y prend un {@code java.util.function.Function<Identifier,RenderLayer>}
     * en premier paramètre (PAS un {@code RenderPipeline} direct comme sur
     * 1.21.11/26.1.2 — {@code RenderPipeline}/{@code RenderLayer} sont deux
     * abstractions DIFFÉRENTES, la seconde antérieure à la première, voir
     * l'audit modules pour le contexte), résolu à la compilation vanilla via
     * une référence de méthode statique ({@code RenderLayer::getGuiTextured})
     * — vérifié par désassemblage bytecode de {@code HandledScreen} (jar
     * 1.21.4 réel, table BootstrapMethods : {@code REF_invokeStatic
     * gmj.H:(Lakv;)Lgmj;}, où {@code gmj}=RenderLayer, confirmé Yarn named
     * "getGuiTextured"). Comme {@code java.util.function.Function} est une
     * interface JDK standard (jamais obfusquée), on peut construire nous-
     * mêmes un {@link java.lang.reflect.Proxy} qui délègue {@code apply()} à
     * cette méthode statique — pas besoin de reproduire le lambda vanilla.
     */
    /**
     * BUG TROUVÉ ET CORRIGÉ (audit modules, "TODO 1.21.4" point 3) : cette
     * méthode construisait sa PROPRE instance {@code DrawContext} isolée
     * (comme {@code drawVanillaItemIconModernImmediate} avant son propre fix,
     * voir bug "mauvaise APPROCHE" dans module-bracket-audit.md) — même
     * classe de problème structurel (état GL imprévisible hors du flux de
     * rendu vivant), jamais porté sur le correctif "file d'attente + point
     * d'accroche vivant" qui a débloqué l'armure, car ce fond de fenêtre a
     * une contrainte de z-order DIFFÉRENTE : doit apparaître PAR-DESSUS
     * l'écran d'inventaire ouvert, donc se dessiner APRÈS
     * {@code Screen.render()} — {@code InGameHud.render()} (point d'accroche
     * de l'armure) s'exécute AVANT l'écran, inutilisable ici tel quel.
     *
     * Correctif : mise en FILE D'ATTENTE ({@code pendingModernGuiBlits},
     * partagée avec le chemin "Deferred") au lieu d'un dessin synchrone
     * isolé — vidée par {@link #flushPendingImmediateGuiBlits(Object)},
     * appelé depuis un NOUVEAU point d'accroche Mixin en TAIL de {@code
     * HandledScreen.drawForeground(DrawContext,I,I)V} (voir {@code
     * HandledScreenBlitFlushMixin1214}) — déclaré DIRECTEMENT sur {@code
     * HandledScreen} (PAS la classe {@code Screen} partagée par tous les
     * écrans, y compris nos écrans custom — voir la leçon de
     * {@code ShulkerPreviewModule} sur ce risque précis), et appelé APRÈS le
     * fond/les cases/objets du conteneur mais AVANT les tooltips vanilla —
     * exactement où doit apparaître notre panneau. Décalage d'une frame
     * comme pour les icônes (imperceptible tant que Maj reste maintenue).
     */
    private void drawVanillaContainerTextureModernImmediate(String texturePath, float x, float y, float w, float h,
                                                              float u, float v, float texW, float texH, int vpWidth, int vpHeight) {
        try {
            float guiScale = guiScale(vpWidth);
            int guiX = Math.round(x / guiScale);
            int guiY = Math.round((vpHeight - y - h) / guiScale);
            int guiW = Math.round(w / guiScale);
            int guiH = Math.round(h / guiScale);
            synchronized (pendingModernGuiBlits) {
                pendingModernGuiBlits.add(new PendingGuiBlit(texturePath, guiX, guiY, guiW, guiH, u, v, texW, texH));
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Appelé depuis {@code HandledScreenBlitFlushMixin1214}, en TAIL de
     * {@code HandledScreen.drawForeground(DrawContext,I,I)V}, avec le VRAI
     * paramètre {@code DrawContext} de cet appel (celui que l'écran
     * d'inventaire ouvert utilise lui-même pour tout son rendu — jamais une
     * instance reconstruite). Voir la javadoc de
     * {@link #drawVanillaContainerTextureModernImmediate} pour le pourquoi.
     */
    public void flushPendingImmediateGuiBlits(Object realDrawContext) {
        java.util.List<PendingGuiBlit> batchBlits;
        synchronized (pendingModernGuiBlits) {
            if (pendingModernGuiBlits.isEmpty()) return;
            batchBlits = new java.util.ArrayList<>(pendingModernGuiBlits);
            pendingModernGuiBlits.clear();
        }
        try {
            if (!ensureContainerBlitImmediateResolved(realDrawContext.getClass())) return;
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            ClassLoader cl = mc.getClass().getClassLoader();
            for (PendingGuiBlit blit : batchBlits) {
                Object identifier = resolveTextureIdentifier(cl, blit.texturePath);
                if (identifier == null) continue;
                drawTextureMethodImmediate.invoke(realDrawContext, guiTexturedFunctionProxyImmediate, identifier,
                    blit.guiX, blit.guiY, blit.u, blit.v, blit.guiW, blit.guiH,
                    Math.round(blit.texW), Math.round(blit.texH));
            }
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] flushPendingImmediateGuiBlits: " + t);
        }
    }

    private boolean ensureContainerBlitImmediateResolved(Class<?> drawContextClass) {
        if (drawTextureMethodImmediate != null) return true;
        if (containerBlitImmediateResolveFailed) return false;
        try {
            Class<?> identifierClass = McReflect.yarnClass("net/minecraft/util/Identifier", "net.minecraft.resources.Identifier");
            if (identifierClass == null) { containerBlitImmediateResolveFailed = true; return false; }

            Class<?> renderLayerClass = McReflect.yarnClass("net/minecraft/client/render/RenderLayer");
            if (renderLayerClass == null) { containerBlitImmediateResolveFailed = true; return false; }
            getGuiTexturedMethodImmediate = McReflect.methodOnClass(
                "net/minecraft/client/render/RenderLayer", "getGuiTextured", identifierClass);
            if (getGuiTexturedMethodImmediate == null) { containerBlitImmediateResolveFailed = true; return false; }

            Class<?> functionClass = java.util.function.Function.class;
            Method finalMethod = getGuiTexturedMethodImmediate;
            guiTexturedFunctionProxyImmediate = java.lang.reflect.Proxy.newProxyInstance(
                drawContextClass.getClassLoader(), new Class<?>[]{ functionClass },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "apply": return finalMethod.invoke(null, args[0]);
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default: return "GuiTexturedFunctionProxy";
                    }
                });

            drawTextureMethodImmediate = McReflect.method(drawContextClass, "net/minecraft/client/gui/DrawContext",
                "drawTexture", functionClass, identifierClass, int.class, int.class,
                float.class, float.class, int.class, int.class, int.class, int.class);
            if (drawTextureMethodImmediate == null) { containerBlitImmediateResolveFailed = true; return false; }
            return true;
        } catch (Throwable t) {
            containerBlitImmediateResolveFailed = true;
            LauncherLog.err("[UiRenderer] ensureContainerBlitImmediateResolved: " + t);
            return false;
        }
    }

    /**
     * @param withDurabilityBar en plus de l'icône, dessine la VRAIE barre de
     *     durabilité vanilla (DrawContext.drawItemBar / GuiGraphicsExtractor.itemBar,
     *     vérifié par mappings Yarn 1.21.4/1.21.11 et javap 26.1.2 réel) — pour
     *     le style "Vanilla" d'ArmorDurabilityModule, voir sa javadoc. PAS
     *     supporté sur le pipeline legacy (1.8.9, ignoré silencieusement) ni
     *     sur le bracket 1.20.4 (pas de drawItemBar dans ses mappings Yarn —
     *     résolution échoue proprement, aucune barre dessinée, pas d'erreur).
     */
    public void drawVanillaItemIcon(Object itemStack, float x, float y, float size, int vpWidth, int vpHeight, boolean withDurabilityBar) {
        if (itemStack == null) return;
        if (modern) {
            drawVanillaItemIconModern(itemStack, x, y, size, vpWidth, vpHeight, withDurabilityBar);
            return;
        }
        LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        // Diagnostic glGetError() limité aux DIAG_CALL_LIMIT premiers appels
        // (sinon spam à chaque frame) — glGetError() ne lève PAS d'exception
        // Java, une corruption silencieuse de l'état GL (GL_INVALID_OPERATION
        // etc.) est donc invisible dans les logs habituels malgré aucune
        // exception observée jusqu'ici.
        int diagCall = ++DIAG_CALLS;
        boolean diag = diagCall <= DIAG_CALL_LIMIT;
        if (diag) {
            try { LauncherLog.info("[UiRenderer] icon#" + diagCall + " stack=" + itemStack + " entryErr=" + drainGlErrors()); } catch (Throwable ignored) {}
        }
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object itemRenderer = McReflect.noArgMethod(mc.getClass(), "net/minecraft/client/MinecraftClient", "getItemRenderer").invoke(mc);
            if (itemRenderer == null) return;
            Method render = McReflect.method(itemRenderer.getClass(), "net/minecraft/client/render/item/ItemRenderer",
                "renderInGuiWithOverrides", itemStack.getClass(), int.class, int.class);
            if (render == null) return;

            // DiffuseLighting = renommage Yarn de RenderHelper (MCP) — voir
            // PvP-Mod/ArmorDurabilityHud.java (référence Forge 1.8.9, seule
            // autre source du repo appelant ce rendu d'item plusieurs fois par
            // frame) : elle pose RenderHelper.enableGUIStandardItemLighting()
            // avant CHAQUE appel et disableStandardItemLighting() après —
            // jamais fait ici avant ce correctif. Sans ces 2 lumières GL
            // positionnées, le modèle 3D de l'item rend avec un éclairage
            // résiduel non garanti d'un appel à l'autre : la 1ère icône profite
            // par chance de l'état laissé par le jeu juste avant le hook HUD,
            // les suivantes héritent de l'état CONSOMMÉ par le rendu précédent
            // — d'où les icônes 2+ affichées comme des formes blanches
            // fragmentées (éclairage/texture incorrects) au lieu du vrai item.
            Class<?> diffuseLighting = McReflect.yarnClass("net/minecraft/client/render/DiffuseLighting");
            Method enableLighting = diffuseLighting != null ? McReflect.method(diffuseLighting, "net/minecraft/client/render/DiffuseLighting", "enable") : null;
            Method disableLighting = diffuseLighting != null ? McReflect.method(diffuseLighting, "net/minecraft/client/render/DiffuseLighting", "disable") : null;
            // Diagnostic UNE SEULE FOIS (voir DIAG_LOGGED) : vérifier que la
            // résolution par réflexion réussit vraiment plutôt que d'échouer
            // silencieusement (yarnClass()/method() avalent leurs exceptions
            // et renvoient null sans logguer) — sinon ce correctif pourrait
            // n'avoir aucun effet sans qu'on le sache.
            if (!DIAG_LOGGED) {
                DIAG_LOGGED = true;
                LauncherLog.info("[UiRenderer] drawVanillaItemIcon diag: diffuseLighting=" + diffuseLighting
                    + " enableLighting=" + enableLighting + " disableLighting=" + disableLighting);
            }

            // Notre shader SDF custom (drawText) OU celui de drawRoundedRect
            // peut être encore actif si une icône PRÉCÉDENTE de cette même
            // boucle a échoué avant d'atteindre son propre glUseProgram(0) de
            // nettoyage (ex: exception avalée) — vanilla rend cette icône en
            // pipeline FIXE (glBegin/glEnd, pas de shader) et interprèterait
            // alors les données de texture RGBA normales de l'item À TRAVERS
            // notre shader SDF (qui les lit comme un champ de distance signée
            // dans le canal alpha) : exactement le genre de rendu "cassé"
            // observé (formes fragmentées au lieu de la vraie icône).
            glUseProgram(0);
            // Legacy (1.8.9) — voir captureLegacyGlState()/drawEdgeVignetteLegacy.
            savedGlState = captureLegacyGlState();
            glEnable(0x0DE1); // GL_TEXTURE_2D
            glEnable(0x0B71); // GL_DEPTH_TEST — vanilla s'appuie dessus pour l'ordre icône/overlay
            // Sans ce clear, le depth buffer garde les valeurs laissées par la
            // scène 3D derrière le HUD (ou par l'icône précédente dessinée
            // cette même frame, voir ArmorDurabilityModule qui appelle cette
            // méthode plusieurs fois de suite) — le test de profondeur d'un
            // appel ultérieur pouvait alors échouer au hasard contre ce
            // résidu, rendant certaines icônes invisibles alors que le stack
            // n'était pas null ("seule la première icône s'affiche").
            glClear(0x00000100); // GL_DEPTH_BUFFER_BIT
            glDisable(0x0B44); // GL_CULL_FACE
            // PAS de glDisable(GL_SCISSOR_TEST) — voir UiScrollContainer
            // (javadoc de classe).
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA
            // Unité de texture 0 explicitement — l'overlay (glint d'enchant,
            // barre de durabilité) d'un appel précédent peut avoir laissé une
            // unité de multitexturing non-0 active, faisant échouer le bind
            // de texture du prochain appel (texture "blanche"/non trouvée).
            try { glActiveTexture(0x84C0); } catch (Throwable ignored) {} // GL_TEXTURE0

            matrixMode(0x1701); // GL_PROJECTION
            pushMatrix();
            projPushed = true;
            loadIdentity();
            // Convention GUI vanilla : origine HAUT-gauche, Y vers le bas (INVERSE de la nôtre) — voir javadoc.
            glOrtho(0, vpWidth, vpHeight, 0, 1000, 3000);
            matrixMode(0x1700); // GL_MODELVIEW
            pushMatrix();
            modelPushed = true;
            loadIdentity();
            glTranslatef(0f, 0f, -2000f);

            float zoom = size / 16f;
            glScalef(zoom, zoom, 1f);

            // Position calculée en pixels PHYSIQUES (convention vanilla, coin
            // haut-gauche), puis divisée par zoom car glScalef s'applique à
            // TOUT ce qui suit — y compris les coordonnées passées à
            // render.invoke ci-dessous, qui doivent donc être exprimées dans
            // l'espace NON zoomé pour retomber au bon endroit une fois zoomées.
            float vanillaXPhysical = x;
            float vanillaYPhysical = vpHeight - y - size;
            float vanillaX = vanillaXPhysical / zoom;
            float vanillaY = vanillaYPhysical / zoom;

            // Posée/déposée à CHAQUE appel (pas seulement au début/fin du lot
            // de 5 icônes) — exactement le pattern PvP-Mod/ArmorDurabilityHud.
            if (enableLighting != null) { try { enableLighting.invoke(null); } catch (Throwable ignored) {} }
            if (diag) { try { LauncherLog.info("[UiRenderer] icon#" + diagCall + " preRenderErr=" + drainGlErrors()); } catch (Throwable ignored) {} }
            try {
                render.invoke(itemRenderer, itemStack, (int) vanillaX, (int) vanillaY);
            } finally {
                if (disableLighting != null) { try { disableLighting.invoke(null); } catch (Throwable ignored) {} }
            }
            if (diag) { try { LauncherLog.info("[UiRenderer] icon#" + diagCall + " postRenderErr=" + drainGlErrors()); } catch (Throwable ignored) {} }
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawVanillaItemIcon: " + t);
        } finally {
            try { glActiveTexture(0x84C0); glBindTexture(0x0DE1, 0); } catch (Throwable ignored) {} // GL_TEXTURE0, unbind
            try {
                if (modelPushed) { matrixMode(0x1700); popMatrix(); }
            } catch (Throwable ignored) {}
            try {
                if (projPushed) { matrixMode(0x1701); popMatrix(); }
            } catch (Throwable ignored) {}
            restoreLegacyGlState(savedGlState);
        }
    }

    // ── Icône d'objet vanilla — pipeline MODERNE (1.21.11+, 26.1+) ───────────
    //
    // ANCIENNE APPROCHE ABANDONNÉE (voir historique de session) : construire
    // notre PROPRE instance indépendante de GuiRenderState/DrawContext et
    // appeler une méthode "flush" dessus. Invalidée par désassemblage complet
    // du VRAI GuiRenderState (classe non obfusquée en 26.1.2, javap direct) :
    // c'est une PURE STRUCTURE DE DONNÉES (add*/forEach*/traverse/reset — pas
    // de méthode "flush vers le GPU"). Le flush réel exige de participer au
    // GuiRenderState PARTAGÉ que vanilla envoie lui-même au GPU chaque frame
    // via GuiRenderer.render(GpuBufferSlice), appelé DEPUIS
    // GameRenderer.render(...) — tracé bytecode complet (javap 1.21.11 +
    // classdump maison sur 26.1.2, class file version 69 illisible par javap)
    // confirmant la chaîne : GameRenderer.guiRenderer (champ, type
    // GuiRenderer) → GuiRenderer.state/renderState (champ, type
    // GuiRenderState, "state" en Yarn 1.21.11 / "renderState" en 26.1.2 réel)
    // → GuiRenderer.render(GpuBufferSlice) consomme CET état précis.
    //
    // Conséquence : dessiner dans NOTRE PROPRE instance ne sert à rien (jamais
    // consommée par aucun flush) — il faut ajouter nos commandes DANS l'état
    // VIVANT que GameRenderer.guiRenderer va lui-même vider ce frame-ci.
    //
    // Fenêtre de timing (confirmée par trace bytecode complète de
    // GameRenderer.render(DeltaTracker,boolean) réel en 26.1.2, aucun appel
    // reset()/clear() sur guiRenderState nulle part dans cette méthode —
    // l'extraction/peuplement du HUD vanilla dans l'état se fait dans une
    // passe "extract" SÉPARÉE, appelée AVANT que GameRenderer.render() ne
    // soit invoqué) : n'importe quel point de cette méthode AVANT l'appel à
    // guiRenderer.render(GpuBufferSlice) convient — HEAD est le plus simple
    // et le plus sûr (pas de dépendance à un point d'injection au milieu
    // d'une méthode). NON REVÉRIFIÉ bytecode par bytecode pour 1.21.11 lui-
    // même (javap, pas le classdump maison) mais même famille d'architecture
    // (GuiRenderer/GuiRenderState identiques dans les deux versions) — voir
    // GuiFlushMixin (1.21.11) / GuiFlushMixin261 (26.1.2), point d'accroche
    // qui appelle {@link #flushPendingModernItemIcons}.
    //
    // D'où la FILE D'ATTENTE ci-dessous : ArmorDurabilityModule (et tout
    // futur appelant) tourne dans la passe de dessin DU MOD (GlobalUiPresentMixin,
    // APRÈS le blit — voir sa javadoc), donc APRÈS que GameRenderer.render()
    // ait déjà fini d'envoyer l'état au GPU pour CE frame-ci. drawVanillaItemIconModern
    // ne fait donc que METTRE EN FILE la demande (ItemStack + position déjà
    // convertie en coordonnées GUI-scaled) ; elle n'est réellement soumise à
    // l'état vivant qu'au TOUT DÉBUT du frame SUIVANT, par flushPendingModernItemIcons
    // — décalage d'une frame (~8-16ms), imperceptible, technique standard pour
    // participer à une passe de rendu qui s'est déjà terminée pour ce frame.
    //
    // NON VÉRIFIÉ EN JEU (pas d'accès à un client Minecraft depuis cet
    // environnement) : voir les logs "[UiRenderer] itemIconModern" en cas
    // d'icône toujours invisible.
    private static final class PendingItemIcon {
        final Object itemStack; final int guiX; final int guiY; final boolean vanillaExtras;
        PendingItemIcon(Object itemStack, int guiX, int guiY, boolean vanillaExtras) {
            this.itemStack = itemStack; this.guiX = guiX; this.guiY = guiY; this.vanillaExtras = vanillaExtras;
        }
    }

    private static final java.util.List<PendingItemIcon> pendingModernItemIcons = new java.util.ArrayList<>();

    /**
     * File d'attente jumelle de {@link PendingItemIcon} mais pour un blit de
     * texture vanilla BRUTE (pas via l'atlas de sprites — voir
     * {@link #drawVanillaContainerTexture}) : fond de fenêtre de conteneur
     * (ex: {@code textures/gui/container/shulker_box.png}). Même décalage
     * d'une frame, même point de vidage ({@link #flushPendingModernItemIcons}).
     */
    private static final class PendingGuiBlit {
        final String texturePath; final int guiX, guiY, guiW, guiH; final float u, v, texW, texH;
        PendingGuiBlit(String texturePath, int guiX, int guiY, int guiW, int guiH, float u, float v, float texW, float texH) {
            this.texturePath = texturePath; this.guiX = guiX; this.guiY = guiY; this.guiW = guiW; this.guiH = guiH;
            this.u = u; this.v = v; this.texW = texW; this.texH = texH;
        }
    }

    private static final java.util.List<PendingGuiBlit> pendingModernGuiBlits = new java.util.ArrayList<>();
    private static Method blitMethodModern;
    private static boolean blitResolveFailed = false;
    private static final java.util.Map<String, Object> containerTextureIdentifierCache = new java.util.HashMap<>();

    private static java.lang.reflect.Field guiRendererFieldModern;
    private static java.lang.reflect.Field guiStateFieldModern;
    private static java.lang.reflect.Constructor<?> drawContextCtorModern;
    private static Method drawItemMethodModern;
    /** Résolu UNE FOIS dans le même bloc que drawContextCtorModern (voir plus bas) — reste {@code null} pour de bon si introuvable (1.20.4 : pas de drawItemBar dans ses mappings Yarn), voir drawVanillaItemIcon(withDurabilityBar). */
    private static Method drawItemBarMethodModern;
    // Fond de case VANILLA — sprite réel "hud/hotbar_offhand_left.png"
    // (29x24, confirmé présent tel quel dans le jar 26.1.2 réel), PAS une
    // case recréée. Recherché sur GitHub à la demande explicite de
    // l'utilisateur (mods de référence consultés : aucun n'expose de fond de
    // case isolé réutilisable en Java). PREMIER essai avec
    // "container/slot.png" (18x18) — ABANDONNÉ : présent dans le jar et
    // référencé par AbstractContainerScreen (donc pas un fichier mort), mais
    // test utilisateur avec un vrai resource pack (custom UI) confirmé sans
    // effet visuel — la plupart des resource packs de type "clean UI" ne
    // personnalisent QUE les sprites de la famille "hud/hotbar_*" (déjà
    // utilisés nativement pour LA VRAIE case de main secondaire à côté de la
    // hotbar, exactement le même contexte visuel que nos cases d'armure), pas
    // "container/slot.png" (utilisé seulement dans certains écrans
    // d'inventaire spécifiques). Décodage manuel du PNG réel (RGBA8, pas de
    // lib PIL disponible dans l'environnement — parseur zlib+filtres de
    // scanline écrit à la main) : case visible = coin arrondi occupant grosso
    // modo x=[0,22] y=[1,23] du canvas 29x24 (le reste, x>22, est
    // transparent — laissé tel quel, PAS recadré, pour rester au plus près
    // du sprite vanilla réel), zone intérieure (alpha faible/dégradé,
    // destinée à l'icône) = EXACTEMENT 16x16 à l'offset (3,4) depuis le
    // coin haut-gauche du sprite — coïncide pile avec la taille native
    // 16x16 de nos icônes, aucune supposition nécessaire.
    //
    // Dessiné via DrawContext.drawGuiTexture(RenderPipeline,Identifier,x,y,w,h)V
    // (Yarn 1.21.11, method_52706) / GuiGraphicsExtractor.blitSprite (vrai
    // nom 26.1.2, même descripteur) — DISPONIBLE UNIQUEMENT sur les brackets
    // "Deferred" (1.21.11/26.1.2, voir modernUsesDeferredGuiRenderer) : sur
    // 1.20.4/1.21.4 (chemin Immediate), method_52706 existe MAIS avec un
    // descripteur DIFFÉRENT par bracket (1.20.4 : pas de RenderPipeline du
    // tout ; 1.21.4 : un Function<Identifier,RenderPipeline> au lieu d'un
    // RenderPipeline direct — 3 signatures différentes pour le même ID
    // intermediary selon la version, vérifié dans les 3 jeux de mappings
    // Yarn correspondants) — non implémenté pour ces deux brackets (barre de
    // durabilité seule pour 1.21.4, ni case ni barre pour 1.20.4).
    private static final int SLOT_SPRITE_W = 29;
    private static final int SLOT_SPRITE_H = 24;
    /** Décalage (icône 16x16 depuis le coin haut-gauche du sprite 29x24) — voir javadoc ci-dessus. */
    private static final int SLOT_SPRITE_ICON_DX = 3;
    private static final int SLOT_SPRITE_ICON_DY = 4;
    /** Fichier PNG BRUT du sprite (PAS son nom d'atlas "hud/hotbar_offhand_left") — voir bug z-order dans flushIntoGuiState : nécessaire pour un blit "texture brute" avec u/v explicites (crop du cadre uniquement), l'atlas de sprites ne permettant aucun contrôle de région. */
    /** Fichier PNG BRUT du sprite (PAS son nom d'atlas "hud/hotbar_offhand_left") — utilisé par le chemin Immediate (1.21.4, voir flushPendingImmediateItemIcons), qui a besoin d'un chemin de texture direct (pas d'accès à l'atlas de sprites côté résolution "Immediate"). */
    private static final String VANILLA_SLOT_SPRITE_PATH = "textures/gui/sprites/hud/hotbar_offhand_left.png";
    private static Method drawGuiTextureMethodModern;
    private static Object renderPipelineGuiTexturedModern;
    private static Object slotSpriteIdentifierModern;
    private static boolean slotSpriteResolveFailed = false;
    private static boolean modernItemIconResolveFailed = false;

    // GuiRenderer/GuiRenderState (voir ci-dessus) N'EXISTENT PAS en 1.20.4 ni
    // 1.21.4 (confirmé absent des deux mappings Yarn correspondants,
    // grep -c ==0 sur les deux) — architecture introduite entre la 1.21.4 et
    // la 1.21.11. Sur ces deux brackets, DrawContext.drawItem dessine dans un
    // VertexConsumerProvider.Immediate CLASSIQUE, avec sa PROPRE méthode
    // draw()V (method_51452 en 1.20.4, method_51452 aussi en 1.21.4 — même ID
    // intermediary stable) qui flush IMMÉDIATEMENT, en autonomie — pas besoin
    // de participer à un état partagé ni d'un second point d'accroche Mixin.
    // Détection automatique (essai de résolution de GuiRenderer) plutôt que
    // par bracket en dur : suffisant et se généralise tout seul si une future
    // version régresse ou avance cette bascule d'architecture.
    private static Boolean modernUsesDeferredGuiRenderer;

    private static boolean modernUsesDeferredGuiRenderer() {
        if (modernUsesDeferredGuiRenderer == null) {
            modernUsesDeferredGuiRenderer = McReflect.yarnClass(
                "net/minecraft/client/gui/render/GuiRenderer",
                "net.minecraft.client.gui.render.GuiRenderer") != null;
        }
        return modernUsesDeferredGuiRenderer;
    }

    private void drawVanillaItemIconModern(Object itemStack, float x, float y, float size, int vpWidth, int vpHeight, boolean withDurabilityBar) {
        if (modernUsesDeferredGuiRenderer()) {
            drawVanillaItemIconModernDeferred(itemStack, x, y, size, vpWidth, vpHeight, withDurabilityBar);
        } else {
            drawVanillaItemIconModernImmediate(itemStack, x, y, size, vpWidth, vpHeight, withDurabilityBar);
        }
    }

    /**
     * BUG TROUVÉ (test utilisateur : icônes dans le "mauvais ordre" par
     * rapport au texte de durabilité) : {@code guiY = y / guiScale} traitait
     * {@code y} (convention de CE projet, origine BAS-gauche — voir javadoc
     * de classe et drawIcon) comme si c'était DÉJÀ une coordonnée GUI vanilla
     * (origine HAUT-gauche) — un simple ratio, JAMAIS de flip. Le pipeline
     * LEGACY (1.8.9, drawVanillaItemIcon ci-dessus, ligne ~1649) fait ce flip
     * correctement depuis le début : {@code vpHeight - y - size}, oublié ici
     * lors de l'écriture initiale du chemin moderne. Sans lui, la position
     * verticale de CHAQUE icône est mal calculée de façon cohérente dans la
     * MÊME direction — visuellement, la pile entière d'icônes apparaît dans
     * l'ordre inverse par rapport au texte (qui, lui, utilise le pipeline de
     * texte custom de ce projet, PAS DrawContext, donc jamais concerné).
     */
    private void drawVanillaItemIconModernDeferred(Object itemStack, float x, float y, float size, int vpWidth, int vpHeight, boolean withDurabilityBar) {
        try {
            // DrawContext/GuiGraphicsExtractor attend des coordonnées
            // GUI-SCALED (comme tout le rendu vanilla), PAS les pixels
            // framebuffer bruts que le reste de notre pipeline utilise
            // partout ailleurs — conversion via le ratio framebuffer/
            // scaledWidth de la fenêtre courante, ET flip d'axe Y (voir javadoc).
            // guiScale() met en cache ce ratio en interne (audit perf — voir
            // sa javadoc) : plus besoin de résoudre mc/window ici nous-mêmes.
            float guiScale = guiScale(vpWidth);
            int guiX = Math.round(x / guiScale);
            int guiY = Math.round((vpHeight - y - size) / guiScale);

            synchronized (pendingModernItemIcons) {
                pendingModernItemIcons.add(new PendingItemIcon(itemStack, guiX, guiY, withDurabilityBar));
            }
        } catch (Throwable t) {
            if (!modernItemIconWarned) {
                modernItemIconWarned = true;
                LauncherLog.err("[UiRenderer] drawVanillaItemIconModernDeferred: " + t);
            }
        }
    }

    private static java.lang.reflect.Constructor<?> drawContextImmediateCtorModern;
    private static Method getBufferBuildersMethodModern;
    private static Method getEntityVertexConsumersMethodModern;
    private static Method drawItemMethodImmediateModern;
    private static Method drawFlushMethodImmediateModern;
    private static boolean modernImmediateResolveFailed = false;

    /**
     * Bracket 1.20.4 / 1.21.4 (voir détection ci-dessus) : DrawContext gère
     * son propre buffer immédiat, auto-suffisant — construit avec le
     * VertexConsumerProvider.Immediate PARTAGÉ de vanilla
     * (MinecraftClient.getBufferBuilders().getEntityVertexConsumers(), déjà
     * lié au bon contexte GL/render target à cet instant) plutôt qu'une
     * instance isolée, puis vidé immédiatement via son propre draw()V — pas
     * de file d'attente ni de second point d'accroche Mixin nécessaires ici
     * (contrairement au bracket 1.21.11+/26.1+, voir drawVanillaItemIconModernDeferred).
     */
    /**
     * BUG TROUVÉ ET CORRIGÉ EN PROFONDEUR (test utilisateur, builds v559-v563,
     * après recherche externe — "regarde comment d'autres bibliothèques
     * open-source font") : trois correctifs successifs (rebind VAO, cache
     * shader RenderSystem invalidé/restauré) ont fait disparaître toute
     * erreur GL mesurable, mais l'icône restait invisible malgré tout — signe
     * que le problème n'était PAS une case de GL mal configurée en particulier,
     * mais l'APPROCHE ELLE-MÊME : construire notre PROPRE {@code DrawContext}
     * isolé (avec son propre {@code VertexConsumerProvider.Immediate}) APRÈS
     * que tout le rendu vanilla de la frame (monde, HUD, écran) ait déjà eu
     * lieu et potentiellement déjà fermé/soumis ses propres batches, plutôt
     * que de PARTICIPER au rendu vanilla EN COURS.
     *
     * Aucun mod Fabric "normal" ne fait ça — {@code HudRenderCallback}
     * (l'équivalent standard pour dessiner par-dessus le HUD) hooke
     * directement {@code InGameHud.render(DrawContext, RenderTickCounter)}
     * et reçoit en paramètre l'instance RÉELLE et VIVANTE de
     * {@code DrawContext} que vanilla lui-même utilise pour TOUT le HUD de
     * cette frame — jamais une instance reconstruite à la main après coup.
     * En participant à CETTE instance (déjà dans le bon état GL/shader/VAO,
     * puisque c'est litéralement celle que vanilla utilise), aucun des
     * contournements ci-dessus (VAO, cache shader) n'est nécessaire : c'est
     * vanilla lui-même qui la construit, la configure ET la vide.
     *
     * Correctif : mise en FILE D'ATTENTE (comme le pipeline "Deferred"
     * 1.21.11/26.1.2, {@link #drawVanillaItemIconModernDeferred}/{@link
     * #pendingModernItemIcons}) au lieu d'un dessin synchrone isolé — vidée
     * par {@link #flushPendingImmediateItemIcons(Object)}, appelé depuis un
     * NOUVEAU point d'accroche Mixin en TAIL de {@code InGameHud.render(...)}
     * (voir {@code HudItemFlushMixin1214}), avec le VRAI paramètre
     * {@code DrawContext} de cet appel. Décalage d'une frame comme le
     * pipeline Deferred (imperceptible, ~8-16ms) : l'icône demandée dans
     * cette frame-ci est effectivement dessinée au tout début du rendu HUD de
     * la frame SUIVANTE.
     */
    private void drawVanillaItemIconModernImmediate(Object itemStack, float x, float y, float size, int vpWidth, int vpHeight, boolean withDurabilityBar) {
        try {
            float guiScale = guiScale(vpWidth);
            int guiX = Math.round(x / guiScale);
            int guiY = Math.round((vpHeight - y - size) / guiScale);

            synchronized (pendingModernItemIcons) {
                pendingModernItemIcons.add(new PendingItemIcon(itemStack, guiX, guiY, withDurabilityBar));
            }
        } catch (Throwable t) {
            if (!modernItemIconWarned) {
                modernItemIconWarned = true;
                LauncherLog.err("[UiRenderer] drawVanillaItemIconModernImmediate: " + t);
            }
        }
    }

    /**
     * Appelé depuis {@code HudItemFlushMixin1214}, en TAIL de
     * {@code InGameHud.render(DrawContext, RenderTickCounter)}, avec le VRAI
     * paramètre {@code DrawContext} de cet appel (l'instance vivante que
     * vanilla utilise pour tout le HUD de cette frame — jamais une instance
     * reconstruite). Voir la javadoc de {@link #drawVanillaItemIconModernImmediate}
     * pour le pourquoi complet. Ni VAO ni cache shader à gérer ici : cette
     * instance est déjà dans l'état GL correct puisque c'est celle de vanilla
     * lui-même — on ne fait qu'y AJOUTER nos propres commandes de dessin,
     * flushées par vanilla via SON PROPRE mécanisme, pas le nôtre.
     */
    public void flushPendingImmediateItemIcons(Object realDrawContext) {
        java.util.List<PendingItemIcon> batch;
        synchronized (pendingModernItemIcons) {
            if (pendingModernItemIcons.isEmpty()) return;
            batch = new java.util.ArrayList<>(pendingModernItemIcons);
            pendingModernItemIcons.clear();
        }
        try {
            if (!ensureModernImmediateResolved(batch.get(0).itemStack)) {
                if (!modernItemIconWarned) {
                    modernItemIconWarned = true;
                    LauncherLog.warn("[UiRenderer] flushPendingImmediateItemIcons: résolution réflexion échouée — icônes non dessinées (voir logs diag)");
                }
                return;
            }
            // Case vanilla ("TODO 1.21.4" point 1, jamais implémentée sur ce
            // bracket avant cette session) — best-effort, ne fait jamais
            // échouer le dessin de l'icône même en cas d'échec ici. Contrairement
            // au chemin "Deferred" (1.21.11/26.1.2, voir flushIntoGuiState), PAS
            // besoin de découper en 4 bandes ici : ce chemin est SYNCHRONE
            // (aucune catégorisation GuiRenderState qui imposerait un ordre de
            // dessin fixe) — l'ordre d'APPEL détermine directement l'ordre de
            // dessin, donc le sprite COMPLET dessiné AVANT l'icône suffit.
            boolean hasVanillaExtras = false;
            for (PendingItemIcon icon : batch) if (icon.vanillaExtras) { hasVanillaExtras = true; break; }
            if (hasVanillaExtras) ensureContainerBlitImmediateResolved(realDrawContext.getClass());

            for (PendingItemIcon icon : batch) {
                if (icon.vanillaExtras && drawTextureMethodImmediate != null) {
                    Object mc = McReflect.minecraftClient();
                    Object spriteIdentifier = mc != null
                        ? resolveTextureIdentifier(mc.getClass().getClassLoader(), VANILLA_SLOT_SPRITE_PATH) : null;
                    if (spriteIdentifier != null) {
                        drawTextureMethodImmediate.invoke(realDrawContext, guiTexturedFunctionProxyImmediate, spriteIdentifier,
                            icon.guiX - SLOT_SPRITE_ICON_DX, icon.guiY - SLOT_SPRITE_ICON_DY, 0f, 0f,
                            SLOT_SPRITE_W, SLOT_SPRITE_H, SLOT_SPRITE_W, SLOT_SPRITE_H);
                    }
                }
                drawItemMethodImmediateModern.invoke(realDrawContext, icon.itemStack, icon.guiX, icon.guiY);
                // drawItemBar absent sur 1.20.4 (pas de mapping Yarn pour ce
                // bracket, voir ensureModernImmediateResolved) — résolution à
                // null, ignoré silencieusement (pas de barre, pas d'erreur).
                if (icon.vanillaExtras && drawItemBarMethodModernImmediate != null) {
                    drawItemBarMethodModernImmediate.invoke(realDrawContext, icon.itemStack, icon.guiX, icon.guiY);
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] flushPendingImmediateItemIcons: " + t);
        }
    }

    private static Method getShaderMethodModern;
    private static Method setShaderMethodModern;
    private static boolean shaderReflectionResolveFailed;

    /**
     * Résout {@code RenderSystem.getShader()}/{@code setShader(ShaderProgram)}
     * — voir la javadoc de {@link #drawVanillaItemIconModernImmediate} pour
     * le mécanisme complet (sauvegarde/invalidation/restauration du cache
     * shader de RenderSystem, portée strictement à notre propre appel).
     * `RenderSystem` reste un nom RÉEL même sur un bracket obfusqué
     * (com.mojang.*, confirmé dans mappings/mappings.tiny) — seul le type
     * du paramètre/retour ({@code ShaderProgram}) est obfusqué.
     */
    private static boolean ensureRenderSystemShaderReflectionResolved() {
        if (getShaderMethodModern != null && setShaderMethodModern != null) return true;
        if (shaderReflectionResolveFailed) return false;
        try {
            Class<?> renderSystemClass = Class.forName("com.mojang.blaze3d.systems.RenderSystem");
            Class<?> shaderProgramClass = McReflect.yarnClass("net/minecraft/client/gl/ShaderProgram");
            if (shaderProgramClass == null) { shaderReflectionResolveFailed = true; return false; }
            getShaderMethodModern = renderSystemClass.getDeclaredMethod("getShader");
            getShaderMethodModern.setAccessible(true);
            setShaderMethodModern = renderSystemClass.getDeclaredMethod("setShader", shaderProgramClass);
            setShaderMethodModern.setAccessible(true);
            return true;
        } catch (Throwable t) {
            shaderReflectionResolveFailed = true;
            return false;
        }
    }

    private static Method drawItemBarMethodModernImmediate;

    private boolean ensureModernImmediateResolved(Object itemStack) {
        if (drawItemMethodImmediateModern != null) return true;
        if (modernImmediateResolveFailed) return false;
        try {
            Class<?> mcClass = McReflect.yarnClass("net/minecraft/client/MinecraftClient");
            getBufferBuildersMethodModern = McReflect.noArgMethod(mcClass,
                "net/minecraft/client/MinecraftClient", "getBufferBuilders");

            Class<?> bufferBuilderStorageClass = McReflect.yarnClass("net/minecraft/client/render/BufferBuilderStorage");
            getEntityVertexConsumersMethodModern = McReflect.noArgMethod(bufferBuilderStorageClass,
                "net/minecraft/client/render/BufferBuilderStorage", "getEntityVertexConsumers");

            Class<?> drawContextClass = McReflect.yarnClass("net/minecraft/client/gui/DrawContext");
            Class<?> vcpImmediateClass = getEntityVertexConsumersMethodModern.getReturnType();
            drawContextImmediateCtorModern = drawContextClass.getDeclaredConstructor(mcClass, vcpImmediateClass);
            drawContextImmediateCtorModern.setAccessible(true);

            drawItemMethodImmediateModern = McReflect.method(drawContextClass, "net/minecraft/client/gui/DrawContext",
                "drawItem", itemStack.getClass(), int.class, int.class);
            drawFlushMethodImmediateModern = McReflect.noArgMethod(drawContextClass,
                "net/minecraft/client/gui/DrawContext", "draw");
            // Absent des mappings Yarn 1.20.4 (introduit en 1.21.4) —
            // McReflect.method() ne lève pas d'exception dans ce cas, retourne
            // simplement null (voir javadoc drawVanillaItemIconModernImmediate).
            drawItemBarMethodModernImmediate = McReflect.method(drawContextClass, "net/minecraft/client/gui/DrawContext",
                "drawItemBar", itemStack.getClass(), int.class, int.class);

            LauncherLog.info("[UiRenderer] itemIconModernImmediate diag: résolution OK — drawContextCtor="
                + drawContextImmediateCtorModern + " drawItem=" + drawItemMethodImmediateModern
                + " draw=" + drawFlushMethodImmediateModern + " drawItemBar=" + drawItemBarMethodModernImmediate);
            return drawItemMethodImmediateModern != null && drawFlushMethodImmediateModern != null;
        } catch (Throwable t) {
            modernImmediateResolveFailed = true;
            LauncherLog.err("[UiRenderer] itemIconModernImmediate: résolution échouée : " + t);
            return false;
        }
    }

    /**
     * Appelé depuis GuiFlushMixin261 (26.1.2 — voir sa javadoc) avec {@code
     * gameRenderer == this} (l'instance VIVANTE, fusionnée par Mixin). Résout
     * la chaîne {@code gameRenderer.guiRenderer.state} par réflexion puis
     * délègue à {@link #flushIntoGuiState}.
     *
     * Bracket 1.21.11 : voir {@link #flushPendingModernItemIconsFromState},
     * appelé directement avec le GuiRenderState — PAS ce chemin-ci (voir
     * javadoc de GuiFlushMixin pour le pourquoi : remonter depuis
     * GameRenderer.render() en HEAD ajoutait nos icônes AVANT que
     * GuiRenderState.clear() ne les efface).
     */
    public static void flushPendingModernItemIcons(Object gameRenderer) {
        try {
            // BUG TROUVÉ (test utilisateur, 26.1.2) : résoudre GameRenderer
            // par NOM (McReflect.yarnClass/Class.forName + classloader du
            // thread courant) échouait silencieusement (guiRendererFieldModern
            // restait null) alors que GuiRenderer, résolu par le MÊME patron
            // de code juste après, réussissait — le thread de rendu n'a
            // apparemment pas de façon fiable Knot comme
            // Thread.currentThread().getContextClassLoader() à ce point
            // précis de l'exécution (contrairement au chemin interne de
            // MappingsRegistry.loadClass, qui retombe sur le classloader de
            // l'APPELANT — Knot, puisque MappingsRegistry est une de NOS
            // classes — quand le premier essai échoue, ce qui explique
            // pourquoi GuiRenderer "marchait par coïncidence"). Fix : on a
            // déjà l'instance VIVANTE de GameRenderer ici (paramètre) — on
            // résout ses champs directement sur SA classe réelle
            // (gameRenderer.getClass()), zéro ambiguïté de classloader
            // possible, plutôt que de deviner un nom qualifié + un
            // classloader séparément.
            if (guiRendererFieldModern == null) {
                guiRendererFieldModern = findFieldByNameInHierarchy(gameRenderer.getClass(),
                    MappingsRegistry.getObfFieldName("net/minecraft/client/render/GameRenderer", "guiRenderer"),
                    "guiRenderer");
                if (guiRendererFieldModern == null) {
                    modernItemIconResolveFailed = true;
                    LauncherLog.err("[UiRenderer] itemIconModern: champ guiRenderer introuvable sur "
                        + gameRenderer.getClass());
                    return;
                }
            }

            Object guiRenderer = guiRendererFieldModern.get(gameRenderer);
            if (guiRenderer == null) return;

            // Même logique : champ GuiRenderState résolu sur la classe réelle
            // de l'instance guiRenderer VIVANTE qu'on vient d'obtenir — nommé
            // "state" par Yarn (1.21.11) mais "renderState" en vrai nom
            // Mojang (26.1.2, confirmé par javap).
            if (guiStateFieldModern == null) {
                guiStateFieldModern = findFieldByNameInHierarchy(guiRenderer.getClass(),
                    MappingsRegistry.getObfFieldName("net/minecraft/client/gui/render/GuiRenderer", "state"),
                    "state", "renderState");
                if (guiStateFieldModern == null) {
                    modernItemIconResolveFailed = true;
                    LauncherLog.err("[UiRenderer] itemIconModern: champ state/renderState introuvable sur "
                        + guiRenderer.getClass());
                    return;
                }
            }

            Object guiState = guiStateFieldModern.get(guiRenderer);
            if (guiState == null) return;
            flushIntoGuiState(guiState, guiRenderer.getClass().getClassLoader());
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] flushPendingModernItemIcons: " + t);
        }
    }

    /**
     * Bracket 1.21.11 — appelé depuis GuiFlushMixin, en TAIL de
     * {@code GuiRenderState.clear()} (Yarn {@code net/minecraft/client/gui/render/state/GuiRenderState},
     * official {@code gqg}, méthode official {@code e()V} == named {@code
     * clear()V}, vérifié directement dans {@code mappings/mappings.tiny}),
     * avec {@code guiState == this} (l'instance VIVANTE fusionnée par Mixin).
     *
     * BUG TROUVÉ (audit modules, cette session) — remplace l'ancien hook HEAD
     * sur {@code GameRenderer.render()} : trace bytecode d'une session
     * précédente (voir historique) avait déjà repéré que {@code
     * GuiRenderState.clear()} (alors identifié seulement par son nom obfusqué
     * "gqg.e()V", sans entrée Yarn named connue à l'époque) est appelé DANS
     * {@code render()}, APRÈS le point HEAD — toute icône mise en file par
     * HEAD était donc effacée avant le flush GPU réel. Le nom named "clear"
     * EXISTE bien dans les mappings (juste sous un chemin différent de celui
     * cherché à l'époque, {@code .../gui/render/state/...} et pas
     * {@code .../gui/render/...}) — permet de cibler {@code clear()V}
     * directement via {@code @Inject(method=...)}, traduit pour Fabric par le
     * refmap comme n'importe quelle autre entrée de REFMAP_ENTRIES (voir
     * LauncherMixinService), plutôt que d'improviser un {@code @At(INVOKE,
     * target=...)} vers un nom obfusqué brut qui aurait cassé sous Fabric
     * (intermediary) et risqué de faire échouer TOUT le tissage de
     * GameRenderer en cascade (voir leçon ClearOverlaysMixin dans
     * module-bracket-audit.md).
     *
     * Injecter directement sur GuiRenderState.clear() plutôt que d'imiter le
     * hook 26.1.2 (INVOKE après Lighting.setupFor dans GameRenderer.render())
     * évite aussi d'avoir à retrouver le nom obfusqué de l'appel imbriqué
     * ({@code this.k.i.t().a(...)}) sur ce bracket — on obtient directement
     * l'instance GuiRenderState vivante en {@code this}, plus besoin de
     * remonter depuis GameRenderer.guiRenderer.state comme dans
     * {@link #flushPendingModernItemIcons}.
     *
     * NON VÉRIFIÉ EN JEU (pas d'accès à un client 1.21.11 depuis cet
     * environnement) — voir les logs "[UiRenderer] itemIconModern" en cas
     * d'icône toujours invisible malgré ce correctif.
     */
    public static void flushPendingModernItemIconsFromState(Object guiState) {
        if (guiState == null) return;
        flushIntoGuiState(guiState, guiState.getClass().getClassLoader());
    }

    /**
     * BUG TROUVÉ (test utilisateur, 1.21.11) : le panneau shulker s'affichait
     * enfin (fix `drawTexture` ci-dessus) mais DERRIÈRE l'écran d'inventaire
     * — mauvais z-order. Cause : {@code GuiFlushMixin} (voir sa javadoc)
     * accrochait le flush en TAIL de {@code GuiRenderState.clear()V} —
     * vérifié par désassemblage de {@code GameRenderer.render()V} (jar
     * 1.21.11 réel, javap) que {@code clear()} est appelé TRÈS TÔT dans la
     * méthode (juste après la config lumière), AVANT {@code
     * InGameHud.render(...)V} ET AVANT {@code Screen.render(...)V} (l'écran
     * d'inventaire lui-même). Nos icônes/fond ajoutés juste après clear()
     * étaient donc les TOUT PREMIERS éléments de la liste de dessin de
     * GuiRenderState pour cette frame — dessinés EN PREMIER, donc DERRIÈRE
     * tout ce qui est ajouté après (HUD, puis l'écran).
     *
     * Trace bytecode complète de {@code GameRenderer.render()V} : {@code
     * clear()V} → {@code new DrawContext(...)} → {@code InGameHud.render(DrawContext,RenderTickCounter)V}
     * → {@code Screen.render(DrawContext,I,I,F)V} (SI un écran est ouvert) →
     * toasts/subtitles → {@code GuiRenderer.render(GpuBufferSlice)V} (soumission
     * GPU réelle, TOUT le contenu accumulé de la frame y compris l'écran est
     * déjà dans l'état à ce point) → {@code GuiRenderer.incrementFrame()V}.
     *
     * Fix : flush déplacé en HEAD de {@code GuiRenderer.render(GpuBufferSlice)V}
     * (voir {@code GuiFlushMixin}, retargeté) — APRÈS que Screen.render() ait
     * fini d'ajouter tout le contenu de l'écran ouvert, JUSTE AVANT la
     * soumission GPU : nos icônes/fond, ajoutés en DERNIER, se retrouvent
     * dessinés PAR-DESSUS tout le reste, z-order correct. {@code this} dans
     * le nouveau hook est directement l'instance {@code GuiRenderer} (pas
     * {@code GuiRenderState}) — cette méthode résout le champ {@code state}
     * dessus (même résolution que {@link #flushPendingModernItemIcons},
     * jamais exercée sur ce bracket jusqu'ici puisque 1.21.11 utilisait
     * {@link #flushPendingModernItemIconsFromState} directement) avant de
     * déléguer à {@link #flushIntoGuiState}.
     */
    public static void flushPendingModernItemIconsFromGuiRenderer(Object guiRenderer) {
        if (guiRenderer == null) return;
        try {
            if (guiStateFieldModern == null) {
                guiStateFieldModern = findFieldByNameInHierarchy(guiRenderer.getClass(),
                    MappingsRegistry.getObfFieldName("net/minecraft/client/gui/render/GuiRenderer", "state"),
                    "state", "renderState");
                if (guiStateFieldModern == null) {
                    LauncherLog.err("[UiRenderer] itemIconModern: champ state/renderState introuvable sur "
                        + guiRenderer.getClass());
                    return;
                }
            }
            Object guiState = guiStateFieldModern.get(guiRenderer);
            if (guiState == null) return;
            flushIntoGuiState(guiState, guiRenderer.getClass().getClassLoader());
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] flushPendingModernItemIconsFromGuiRenderer: " + t);
        }
    }

    /**
     * Vide la file et soumet chaque icône/blit dans le VRAI
     * DrawContext/GuiGraphicsExtractor wrappant l'état PARTAGÉ {@code
     * guiState} (pas une instance isolée) — condition nécessaire pour que le
     * flush EXISTANT de vanilla, plus loin dans le frame, inclue nos icônes.
     * Partagé par les deux brackets modernes (26.1.2 via {@link
     * #flushPendingModernItemIcons}, 1.21.11 via {@link
     * #flushPendingModernItemIconsFromState}) — seule la façon d'OBTENIR
     * {@code guiState} diffère entre les deux.
     */
    private static void flushIntoGuiState(Object guiState, ClassLoader cl) {
        java.util.List<PendingItemIcon> batch;
        synchronized (pendingModernItemIcons) {
            batch = pendingModernItemIcons.isEmpty() ? java.util.Collections.emptyList()
                : new java.util.ArrayList<>(pendingModernItemIcons);
            pendingModernItemIcons.clear();
        }
        java.util.List<PendingGuiBlit> batchBlits;
        synchronized (pendingModernGuiBlits) {
            batchBlits = pendingModernGuiBlits.isEmpty() ? java.util.Collections.emptyList()
                : new java.util.ArrayList<>(pendingModernGuiBlits);
            pendingModernGuiBlits.clear();
        }
        if (batch.isEmpty() && batchBlits.isEmpty()) return;
        if (modernItemIconResolveFailed) return;
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;

            if (drawContextCtorModern == null) {
                // DrawContext (Yarn 1.21.11) == GuiGraphicsExtractor (vrai
                // nom Mojang 26.1.2, confirmé par javap — PAS "GuiGraphics" :
                // la classe a été repositionnée en "extracteur" d'état vers
                // GuiRenderState dans la nouvelle architecture de rendu différé).
                Class<?> drawContextClass = resolveClassByLoader(cl,
                    MappingsRegistry.getObfClassDot("net/minecraft/client/gui/DrawContext"),
                    "net.minecraft.client.gui.GuiGraphicsExtractor");
                if (drawContextClass == null) {
                    modernItemIconResolveFailed = true;
                    LauncherLog.err("[UiRenderer] itemIconModern: classe DrawContext/GuiGraphicsExtractor introuvable");
                    return;
                }
                // guiState.getClass() (type RUNTIME concret) plutôt qu'un
                // type de champ mis en cache : évite de dépendre de
                // guiStateFieldModern (jamais résolu sur le chemin 1.21.11,
                // voir flushPendingModernItemIconsFromState) tout en restant
                // correct pour le chemin 26.1.2 (guiState y est de toute façon
                // déjà une instance concrète, jamais une sous-classe).
                drawContextCtorModern = drawContextClass.getDeclaredConstructor(
                    mc.getClass(), guiState.getClass(), int.class, int.class);
                drawContextCtorModern.setAccessible(true);
            }
            Class<?> drawContextClass = drawContextCtorModern.getDeclaringClass();

            // "drawItem"/"drawItemBar" (Yarn) nécessitent une classe
            // ItemStack CONCRÈTE pour se résoudre (voir findMethodByNameInHierarchy) —
            // reportés ici, guardés par batch non-vide, plutôt que dans le
            // bloc drawContextCtorModern==null ci-dessus : un fond de
            // conteneur (batchBlits) peut arriver SEUL, sans la moindre
            // icône, sur une shulker box entièrement vide (voir
            // ShulkerPreviewModule) — resterait bloqué pour toujours si cette
            // résolution dépendait de batch.get(0).
            if (!batch.isEmpty() && drawItemMethodModern == null) {
                // "drawItem" (Yarn 1.21.11) == "item" (vrai nom Mojang
                // 26.1.2, confirmé par javap).
                drawItemMethodModern = findMethodByNameInHierarchy(drawContextClass,
                    batch.get(0).itemStack.getClass(), "net/minecraft/client/gui/DrawContext", "drawItem", "item");
                if (drawItemMethodModern == null) {
                    modernItemIconResolveFailed = true;
                    LauncherLog.err("[UiRenderer] itemIconModern: méthode drawItem/item introuvable sur " + drawContextClass);
                    return;
                }
                // "drawItemBar" (Yarn 1.21.11) == "itemBar" (vrai nom Mojang
                // 26.1.2, confirmé par javap) — best-effort, jamais fatal si
                // introuvable (reste null, barre juste pas dessinée).
                drawItemBarMethodModern = findMethodByNameInHierarchy(drawContextClass,
                    batch.get(0).itemStack.getClass(), "net/minecraft/client/gui/DrawContext", "drawItemBar", "itemBar");

                // Fond de case vanilla (voir javadoc du champ) — best-effort,
                // ne fait jamais échouer la résolution du reste (icône/barre
                // continuent de fonctionner même si CETTE partie échoue).
                resolveSlotSpriteModern(cl, drawContextClass);
            }

            Object drawContext = drawContextCtorModern.newInstance(mc, guiState, 0, 0);

            // Fond de fenêtre de conteneur (ShulkerPreviewModule) — dessiné
            // AVANT les icônes (sinon il les recouvrirait), voir
            // drawVanillaContainerTexture.
            if (!batchBlits.isEmpty()) {
                resolveSlotSpriteModern(cl, drawContextClass); // pipeline GUI_TEXTURED partagée (voir javadoc du champ)
                resolveContainerBlitModern(cl, drawContextClass);
                if (blitMethodModern != null && renderPipelineGuiTexturedModern != null) {
                    for (PendingGuiBlit blit : batchBlits) {
                        Object identifier = resolveTextureIdentifier(cl, blit.texturePath);
                        if (identifier == null) continue;
                        blitMethodModern.invoke(drawContext, renderPipelineGuiTexturedModern, identifier,
                            blit.guiX, blit.guiY, blit.u, blit.v, blit.guiW, blit.guiH,
                            Math.round(blit.texW), Math.round(blit.texH));
                    }
                }
            }

            // Taille NATIVE (16x16 GUI-pixels, comme vanilla) — pas de mise à
            // l'échelle ici (pas de manipulation du Matrix3x2fStack de
            // DrawContext pour l'instant, contrairement au glScalef legacy) :
            // simplification volontaire pour cette première passe, voir
            // ArmorDurabilityModule pour l'effet (icônes affichées à leur
            // taille vanilla plutôt qu'au "size" demandé).
            for (PendingItemIcon icon : batch) {
                if (icon.vanillaExtras && drawGuiTextureMethodModern != null) {
                    // Sprite 29x24, icône 16x16 à l'offset (3,4) en son sein
                    // (voir javadoc du champ) — AVANT l'icône, sinon elle la
                    // recouvrirait.
                    drawGuiTextureMethodModern.invoke(drawContext, renderPipelineGuiTexturedModern,
                        slotSpriteIdentifierModern, icon.guiX - SLOT_SPRITE_ICON_DX, icon.guiY - SLOT_SPRITE_ICON_DY,
                        SLOT_SPRITE_W, SLOT_SPRITE_H);
                }
                drawItemMethodModern.invoke(drawContext, icon.itemStack, icon.guiX, icon.guiY);
                if (icon.vanillaExtras && drawItemBarMethodModern != null) {
                    drawItemBarMethodModern.invoke(drawContext, icon.itemStack, icon.guiX, icon.guiY);
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] flushPendingModernItemIcons: " + t);
        }
    }

    /**
     * Résout {@code GuiGraphicsExtractor.blit(RenderPipeline,Identifier,I,I,F,F,I,I,I,I)V}
     * (vérifié par désassemblage bytecode de {@code ShulkerBoxScreen.extractBackground}
     * dans le vrai jar 26.1.2 — appel BRUT, pas via l'atlas de sprites,
     * utilisé par TOUS les fonds de fenêtre de conteneur vanilla) — best-effort,
     * ne fait jamais échouer la résolution des icônes même en cas d'échec ici.
     */
    private static void resolveContainerBlitModern(ClassLoader cl, Class<?> drawContextClass) {
        if (blitMethodModern != null || blitResolveFailed) return;
        try {
            Class<?> identifierClass = resolveClassByLoader(cl,
                MappingsRegistry.getObfClassDot("net/minecraft/util/Identifier"),
                "net.minecraft.resources.Identifier");
            if (identifierClass == null) { blitResolveFailed = true; return; }
            // BUG TROUVÉ #1 (test utilisateur, 1.21.11) : "blit" comparé ICI
            // tel quel, jamais traduit via MappingsRegistry — corrigé une
            // première fois en traduisant "blit" via getObfMethodName.
            //
            // BUG TROUVÉ #2 (test utilisateur suivant, log :
            // "containerBlitModern: aucune méthode 10-arg trouvée... 96
            // méthodes déclarées", blitRuntimeName=blit — la traduction avait
            // ÉCHOUÉ et renvoyé le nom Yarn tel quel, silencieusement) :
            // "blit" n'est PAS le nom Yarn 1.21.11 de cette méthode — vérifié
            // directement dans mappings/mappings.tiny (section DrawContext,
            // classe "gir") : AUCUNE entrée "blit" n'existe. Le nom Yarn
            // 1.21.11 est en réalité {@code drawTexture} (4 surcharges,
            // method_25290/91/02/93 — la 10-arg exacte recherchée ici est
            // method_25291, {@code (RenderPipeline,Identifier,I,I,F,F,I,I,I,I)V}).
            // Mojang a renommé cette méthode en "blit" seulement PLUS TARD,
            // entre 1.21.11 et 26.1.2 (confirmé par javap sur 26.1.2, où
            // "blit" est bien le nom réel) — l'hypothèse "même nom des deux
            // côtés" n'avait jamais été vérifiée pour CE nom précis,
            // contrairement à drawItem/drawGuiTexture juste au-dessus.
            String blitRuntimeName = MappingsRegistry.getObfMethodName("net/minecraft/client/gui/DrawContext", "drawTexture");
            for (Method m : drawContextClass.getDeclaredMethods()) {
                if (!m.getName().equals(blitRuntimeName) && !m.getName().equals("blit")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 10) continue;
                if (!identifierClass.isAssignableFrom(p[1])) continue;
                if (p[2] != int.class || p[3] != int.class) continue;
                if (p[4] != float.class || p[5] != float.class) continue;
                if (p[6] != int.class || p[7] != int.class || p[8] != int.class || p[9] != int.class) continue;
                m.setAccessible(true);
                blitMethodModern = m;
                break;
            }
            if (blitMethodModern == null) {
                blitResolveFailed = true;
                LauncherLog.err("[UiRenderer] containerBlitModern: aucune méthode 10-arg trouvée sur " + drawContextClass
                    + " (blitRuntimeName=" + blitRuntimeName + ", " + drawContextClass.getDeclaredMethods().length + " méthodes déclarées)");
            }
        } catch (Throwable t) {
            blitResolveFailed = true;
            LauncherLog.warn("[UiRenderer] containerBlitModern: résolution échouée : " + t);
        }
    }

    /** {@code Identifier.withDefaultNamespace(path)}, mis en cache par chemin — voir resolveSlotSpriteModern pour "ofVanilla"/"withDefaultNamespace". */
    private static Object resolveTextureIdentifier(ClassLoader cl, String path) {
        Object cached = containerTextureIdentifierCache.get(path);
        if (cached != null) return cached;
        try {
            Class<?> identifierClass = resolveClassByLoader(cl,
                MappingsRegistry.getObfClassDot("net/minecraft/util/Identifier"),
                "net.minecraft.resources.Identifier");
            if (identifierClass == null) return null;
            Method ofVanilla = findStaticStringMethod(identifierClass, "net/minecraft/util/Identifier", "ofVanilla", "withDefaultNamespace");
            if (ofVanilla == null) return null;
            Object id = ofVanilla.invoke(null, path);
            containerTextureIdentifierCache.put(path, id);
            return id;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Résout drawGuiTexture/blitSprite + le pipeline GUI_TEXTURED + l'Identifier
     * du sprite "hud/hotbar_offhand_left" (29x24, vrai sprite vanilla — voir
     * javadoc du champ) — best-effort, ne lève jamais (appelant continue
     * avec le reste même en cas d'échec ici, juste pas de fond de case).
     */
    private static void resolveSlotSpriteModern(ClassLoader cl, Class<?> drawContextClass) {
        if (drawGuiTextureMethodModern != null || slotSpriteResolveFailed) return;
        try {
            Class<?> renderPipelinesClass = resolveClassByLoader(cl,
                MappingsRegistry.getObfClassDot("net/minecraft/client/gl/RenderPipelines"),
                "net.minecraft.client.renderer.RenderPipelines");
            Class<?> identifierClass = resolveClassByLoader(cl,
                MappingsRegistry.getObfClassDot("net/minecraft/util/Identifier"),
                "net.minecraft.resources.Identifier");
            if (renderPipelinesClass == null || identifierClass == null) {
                slotSpriteResolveFailed = true;
                return;
            }

            // BUG TROUVÉ ET CORRIGÉ (même cause que drawItem/item, voir la
            // javadoc de findMethodByNameInHierarchy) : "GUI_TEXTURED" est un
            // nom de CHAMP Yarn, jamais traduit ici avant cette session —
            // obfusqué en une lettre courte sur 1.21.11 tout comme les noms
            // de méthode. Pré-traduit ici via MappingsRegistry.getObfFieldName,
            // même pattern que guiRendererFieldModern/guiStateFieldModern
            // plus haut dans ce fichier (candidat obfusqué en premier, nom
            // Yarn en repli — no-op sûr si la traduction échoue).
            java.lang.reflect.Field guiTexturedField = findFieldByNameInHierarchy(renderPipelinesClass,
                MappingsRegistry.getObfFieldName("net/minecraft/client/gl/RenderPipelines", "GUI_TEXTURED"),
                "GUI_TEXTURED");
            if (guiTexturedField == null) { slotSpriteResolveFailed = true; return; }
            renderPipelineGuiTexturedModern = guiTexturedField.get(null);

            // "ofVanilla" (Yarn 1.21.11) == "withDefaultNamespace" (vrai nom
            // Mojang 26.1.2, confirmé par javap) — les deux prennent juste le
            // chemin, namespace "minecraft" implicite.
            java.lang.reflect.Method ofVanilla = findStaticStringMethod(identifierClass, "net/minecraft/util/Identifier", "ofVanilla", "withDefaultNamespace");
            if (ofVanilla == null) { slotSpriteResolveFailed = true; return; }
            slotSpriteIdentifierModern = ofVanilla.invoke(null, "hud/hotbar_offhand_left");

            // "drawGuiTexture" (Yarn 1.21.11/1.21.4/1.20.4, method_52706) ==
            // "blitSprite" (vrai nom Mojang 26.1.2, confirmé par désassemblage
            // GameRenderer/Gui réels) — descripteur (RenderPipeline,Identifier,I,I,I,I)V
            // vérifié IDENTIQUE sur 1.21.11 et 26.1.2 (les deux seuls brackets
            // couverts par le chemin "Deferred", voir javadoc du champ — 1.20.4/
            // 1.21.4 ont un descripteur différent, non géré ici). Nom traduit
            // via MappingsRegistry (même correctif que ci-dessus) — "drawGuiTexture"
            // littéral ne matchait jamais rien sur 1.21.11 (obfusqué).
            Class<?> renderPipelineType = guiTexturedField.getType();
            String drawGuiTextureRuntimeName = MappingsRegistry.getObfMethodName(
                "net/minecraft/client/gui/DrawContext", "drawGuiTexture");
            for (Method m : drawContextClass.getDeclaredMethods()) {
                if ((m.getName().equals(drawGuiTextureRuntimeName) || m.getName().equals("blitSprite"))
                        && m.getParameterCount() == 6
                        && renderPipelineType.isAssignableFrom(m.getParameterTypes()[0])
                        && identifierClass.isAssignableFrom(m.getParameterTypes()[1])
                        && m.getParameterTypes()[2] == int.class && m.getParameterTypes()[3] == int.class
                        && m.getParameterTypes()[4] == int.class && m.getParameterTypes()[5] == int.class) {
                    m.setAccessible(true);
                    drawGuiTextureMethodModern = m;
                    break;
                }
            }
            if (drawGuiTextureMethodModern == null) slotSpriteResolveFailed = true;
        } catch (Throwable t) {
            slotSpriteResolveFailed = true;
            LauncherLog.warn("[UiRenderer] itemIconModern: fond de case vanilla indisponible : " + t);
        }
    }

    /** {@code candidateNames} = noms YARN, traduits via MappingsRegistry avant comparaison — même correctif que {@link #findMethodByNameInHierarchy}. */
    private static java.lang.reflect.Method findStaticStringMethod(Class<?> owner, String yarnClass, String... candidateNames) {
        for (String name : candidateNames) {
            String runtimeName = MappingsRegistry.getObfMethodName(yarnClass, name);
            for (Method m : owner.getDeclaredMethods()) {
                if (m.getName().equals(runtimeName) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /** Cherche {@code candidateNames} (dans l'ordre) comme nom de champ déclaré, en remontant la hiérarchie de {@code owner}. */
    private static java.lang.reflect.Field findFieldByNameInHierarchy(Class<?> owner, String... candidateNames) {
        for (String name : candidateNames) {
            if (name == null) continue;
            Class<?> c = owner;
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        }
        return null;
    }

    /**
     * Cherche {@code candidateNames} (dans l'ordre, noms YARN — ex: "drawItem")
     * comme méthode déclarée à 1 argument (assignable depuis {@code argType}) +
     * (int,int), en remontant la hiérarchie de {@code owner}.
     *
     * BUG TROUVÉ ET CORRIGÉ (test utilisateur, 1.21.11, "icônes toujours
     * invisibles" même après le fix GuiFlushMixin) : cette méthode comparait
     * {@code m.getName()} DIRECTEMENT au nom Yarn littéral ("drawItem"/
     * "item"), sans AUCUNE traduction — fonctionne par coïncidence sur 26.1.2
     * (noms Mojang réels, non obfusqués) mais jamais sur un bracket obfusqué
     * comme 1.21.11, où le nom RUNTIME de `DrawContext.drawItem` est une
     * simple lettre obfusquée ("a", confirmé dans mappings/mappings.tiny —
     * 4 surcharges de "drawItem" partagent d'ailleurs TOUTES le même nom
     * officiel "a", désambiguïsées ensuite par le filtre de type ci-dessous).
     * Log réel : `[UiRenderer] itemIconModern: méthode drawItem/item
     * introuvable sur class gir` en boucle, sur CHAQUE frame, alors même que
     * `drawContextClass` (gir) était correctement résolu — la classe était
     * bonne, seule la recherche du NOM DE MÉTHODE dedans ne traduisait rien.
     *
     * Correctif : {@code yarnClass} ajouté, chaque candidat traduit via
     * {@link MappingsRegistry#getObfMethodName(String, String)} avant
     * comparaison — no-op sûr pour les candidats qui ne sont PAS un nom Yarn
     * connu (ex: "item", repli 26.1.2 : aucune entrée Yarn ne matche, la
     * traduction renvoie le nom inchangé). Même pattern que
     * {@code McReflect.method()}, qui fait déjà ça correctement ailleurs dans
     * ce projet — cette copie locale avait simplement été écrite sans cette
     * étape.
     */
    private static Method findMethodByNameInHierarchy(Class<?> owner, Class<?> argType, String yarnClass, String... candidateNames) {
        for (String name : candidateNames) {
            if (name == null) continue;
            String runtimeName = MappingsRegistry.getObfMethodName(yarnClass, name);
            Class<?> c = owner;
            while (c != null) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(runtimeName) || m.getParameterCount() != 3) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p[0].isAssignableFrom(argType) && p[1] == int.class && p[2] == int.class) {
                        m.setAccessible(true);
                        return m;
                    }
                }
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** Essaie chaque nom de classe (dans l'ordre) via {@code Class.forName} avec le classloader EXPLICITE donné — jamais le classloader ambiant du thread courant, voir flushPendingModernItemIcons pour le pourquoi. */
    private static Class<?> resolveClassByLoader(ClassLoader cl, String... candidateNames) {
        for (String name : candidateNames) {
            if (name == null) continue;
            try {
                return Class.forName(name, false, cl);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ── Texte (police bitmap UiFont) ──────────────────────────────────────────

    public float textWidth(String text, float scale) { return UiFont.REGULAR.textWidth(text, scale); }

    public float textWidth(UiFont font, String text, float scale) { return font.textWidth(text, scale); }

    public void drawText(String text, float x, float y, UiColor color, float scale, int vpWidth, int vpHeight) {
        drawText(UiFont.REGULAR, text, x, y, color, scale, vpWidth, vpHeight);
    }

    /**
     * Dessine {@code text} avec la ligne de base à {@code y} (espace pixels
     * framebuffer, origine bas-gauche — comme drawRoundedRect). Shader SDF
     * dédié (voir TEXT_FRAGMENT_SRC/UiFont) : l'atlas encode une distance
     * signée au bord du glyphe dans son canal alpha, pas une couverture
     * directe — un simple GL_MODULATE fixe ne saurait pas l'interpréter
     * (donnerait un halo flou au lieu d'un bord net), d'où ce programme
     * séparé de celui de drawRoundedRect.
     */
    public void drawText(UiFont font, String text, float x, float y, UiColor color, float scale,
                          int vpWidth, int vpHeight) {
        if (text == null || text.isEmpty()) return;
        if (modern) {
            drawTextModern(font, text, x, y, color, scale, vpWidth, vpHeight);
            return;
        }
        drawTextLegacy(font, text, x, y, color, scale, vpWidth, vpHeight);
    }

    /**
     * Variante avec ombre portée — capacité absente du moteur jusqu'ici
     * (voir audit runtime/ui/ : {@link #drawText} n'a aucun paramètre
     * shadow, aucun site n'appelait drawText deux fois avec un offset).
     * Composition pure sur {@link #drawText} (passe ombre décalée PUIS
     * passe principale) : aucune modification du shader SDF nécessaire,
     * fonctionne donc identiquement sur les 3 pipelines, era E Blaze3D
     * inclus (contrairement à drawGlow/drawSkeletonShimmer, qui eux
     * dépendent de {@link #drawFx}).
     */
    public void drawTextShadowed(UiFont font, String text, float x, float y, UiColor color, UiColor shadowColor,
                                  float shadowOffsetX, float shadowOffsetY, float scale, int vpWidth, int vpHeight) {
        if (text == null || text.isEmpty()) return;
        drawText(font, text, x + shadowOffsetX, y + shadowOffsetY, shadowColor, scale, vpWidth, vpHeight);
        drawText(font, text, x, y, color, scale, vpWidth, vpHeight);
    }

    public void drawTextShadowed(String text, float x, float y, UiColor color, UiColor shadowColor,
                                  float scale, int vpWidth, int vpHeight) {
        // Décalage 1px/1px à l'échelle du texte — convention "drop shadow"
        // standard (Minecraft vanilla utilise le même décalage relatif pour
        // son propre texte HUD).
        drawTextShadowed(UiFont.REGULAR, text, x, y, color, shadowColor, scale, scale, scale, vpWidth, vpHeight);
    }

    private void drawTextModern(UiFont font, String text, float x, float y, UiColor color, float scale,
                                 int vpWidth, int vpHeight) {
        // Era E (Blaze3D 1.21.6+) : passe EXCLUSIVEMENT par le vrai pipeline du
        // moteur (RenderPipelines.GUI_TEXT via GpuDevice/RenderPass, voir
        // UiTextBlaze3D) — jamais de repli sur le pipeline SDF ci-dessous sur
        // ces brackets, même si UiTextBlaze3D échoue : le SDF y est corrompu
        // de façon non-déterministe (confirmé sur toute la session, voir
        // historique) — un texte absent (échec silencieux, loggé côté
        // UiTextBlaze3D) vaut mieux qu'un texte parfois illisible. Sur les
        // brackets antérieurs (1.8.9→1.21.4), UiTextBlaze3D.isAvailable() est
        // {@code false} (classes Blaze3D absentes) — le pipeline SDF
        // ci-dessous reste alors le SEUL chemin, INCHANGÉ, exactement comme
        // avant cette era E.
        if (UiTextBlaze3D.isAvailable()) {
            // queueDraw (pas drawText direct) : voir UiTextBlaze3D pour le
            // pourquoi (rendu différé d'une frame, nécessaire pour que le
            // texte atterrisse dans la texture qui sera présentée).
            UiTextBlaze3D.queueDraw(font, text, x, y, color, scale, vpWidth, vpHeight);
            return;
        }

        int texId = ensureFontTexture(font);
        if (texId < 0) return;
        ensureTextShaderInitModern();
        if (textInitFailedModern) return;
        try {
            // Voir drawEdgeVignetteModern : GL_TEXTURE_2D en tant que CAPACITÉ
            // (glEnable/glDisable) retiré — GL_INVALID_ENUM en Core Profile.
            // glBindTexture(GL_TEXTURE_2D, ...) juste en dessous reste lui
            // parfaitement valide : c'est une CIBLE de bind, pas une capacité
            // fixed-function, ces deux usages du même enum sont indépendants.
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
            // PAS de glDisable(GL_SCISSOR_TEST) — BUG TROUVÉ (voir
            // UiScrollContainer, javadoc de classe) : défaisait le clip actif
            // d'un scroll container pour CHAQUE texte dessiné à l'intérieur.
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA
            // BUG TROUVÉ (era E, 1.21.11 — texte corrompu/glyphes illisibles,
            // alors que les rects/couleurs unies restent parfaits) : notre
            // hook de dessin tourne désormais APRÈS Framebuffer.blitToScreen()
            // (voir GlobalUiPresentMixin), donc APRÈS TOUTE la composition de
            // frame interne de Blaze3D — qui utilise plusieurs UNITÉS de
            // texture actives (multi-texturing). glBindTexture() seul bind
            // sur l'unité COURANTE, pas forcément l'unité 0 — si Blaze3D a
            // laissé une unité différente active, notre atlas se bind au
            // mauvais endroit pendant que le shader (uTexModern, fixé à
            // l'unité 0 juste en dessous) lit une texture parasite laissée là
            // par le rendu vanilla. Jamais un problème sur les brackets C/D
            // (1.20.4/1.21.4), dont le hook tourne AVANT ce genre de
            // composition multi-unité tardive.
            glActiveTexture(0x84C0); // GL_TEXTURE0
            glBindTexture(0x0DE1, texId);
            glUseProgram(textProgramModern);
            glUniform1i(uTexModern, 0);
            glUniform4f(uColorTextModern, color.r, color.g, color.b, color.a);
            uploadProjectionModern(uProjectionTextModern, vpWidth, vpHeight);

            float cs = scale * UiFont.SIZE_CORRECTION;
            float penX = Math.round(x);
            float yTop = Math.round(y + font.ascent * cs);
            float yBottom = Math.round(y - font.descent * cs);

            // 6 sommets/glyphe (2 triangles, GL_TRIANGLES — pas de fan possible,
            // chaque glyphe est un quad DISJOINT des autres, contrairement au
            // rect/vignette qui n'ont besoin que d'UN seul quad).
            FloatBuffer verts = floatBuffer(text.length() * 6 * 4);
            for (int i = 0; i < text.length(); i++) {
                UiFont.Glyph g = font.glyph(text.charAt(i));
                float gw = Math.round(g.width * cs);
                float x0 = penX, x1 = penX + gw;
                // v0=haut-gauche, v1=bas-gauche, v2=bas-droite, v3=haut-droite — même ordre que le mode immédiat legacy.
                putVertex(verts, x0, yTop, g.u0, g.v0);
                putVertex(verts, x0, yBottom, g.u0, g.v1);
                putVertex(verts, x1, yBottom, g.u1, g.v1);
                putVertex(verts, x0, yTop, g.u0, g.v0);
                putVertex(verts, x1, yBottom, g.u1, g.v1);
                putVertex(verts, x1, yTop, g.u1, g.v0);
                penX += Math.round(g.advance * cs);
            }
            verts.flip();
            drawTrianglesModern(verts);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawTextModern: " + t);
        } finally {
            try { glUseProgram(0); } catch (Throwable ignored) {}
            try { glBindTexture(0x0DE1, 0); } catch (Throwable ignored) {}
        }
    }

    private void drawTextLegacy(UiFont font, String text, float x, float y, UiColor color, float scale,
                          int vpWidth, int vpHeight) {
        int texId = ensureFontTexture(font);
        if (texId < 0) return;
        ensureTextShaderInit();
        if (textInitFailed) return; // shader cassé : rien à faire de l'alpha-distance brute, mieux vaut ne rien dessiner

        // Legacy (1.8.9) — voir captureLegacyGlState()/drawEdgeVignetteLegacy.
        LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        try {
            savedGlState = captureLegacyGlState();
            glEnable(0x0DE1);  // GL_TEXTURE_2D
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
            glDisable(0x0BC0); // GL_ALPHA_TEST — voir drawEdgeVignette pour le pourquoi
            // PAS de glDisable(GL_SCISSOR_TEST) — BUG TROUVÉ (voir
            // UiScrollContainer, javadoc de classe) : défaisait le clip actif
            // d'un scroll container pour CHAQUE texte dessiné à l'intérieur.
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA
            glBindTexture(0x0DE1, texId);
            glUseProgram(textProgram);
            glUniform1i(uTex, 0); // texture unit 0 (celle qu'on vient de bind)

            matrixMode(0x1701); // GL_PROJECTION
            pushMatrix();
            projPushed = true;
            loadIdentity();
            glOrtho(0, vpWidth, 0, vpHeight, -1, 1);
            matrixMode(0x1700); // GL_MODELVIEW
            pushMatrix();
            modelPushed = true;
            loadIdentity();

            glColor4f(color.r, color.g, color.b, color.a);

            // cs ("scale corrigé") compense UiFont.RASTER_PX (résolution de
            // rasterisation, un curseur de QUALITÉ) pour que la taille
            // affichée ne dépende que de "scale", calibré une fois pour
            // toutes sur UiFont.REFERENCE_PX — voir UiFont pour le pourquoi.
            float cs = scale * UiFont.SIZE_CORRECTION;

            // Alignement pixel entier — LA vraie cause du flou observé (pas la
            // résolution de l'atlas, déjà testée x8 sans aucun effet visible) :
            // des coordonnées de quad en sous-pixel (ex: y=412.63) forcent le
            // GPU à échantillonner la texture ENTRE deux texels, brouillant le
            // bord des lettres même avec un filtrage parfait. Minecraft aligne
            // son propre texte sur des pixels entiers pour cette raison. Chaque
            // avance de plume est elle-même arrondie (pas juste la position de
            // départ) pour que l'arrondi ne dérive pas caractère après caractère.
            float penX = Math.round(x);
            float yTop = Math.round(y + font.ascent * cs);
            float yBottom = Math.round(y - font.descent * cs);
            glBegin(7); // GL_QUADS
            for (int i = 0; i < text.length(); i++) {
                UiFont.Glyph g = font.glyph(text.charAt(i));
                float gw = Math.round(g.width * cs);
                glTexCoord2f(g.u0, g.v0); glVertex2f(penX, yTop);
                glTexCoord2f(g.u0, g.v1); glVertex2f(penX, yBottom);
                glTexCoord2f(g.u1, g.v1); glVertex2f(penX + gw, yBottom);
                glTexCoord2f(g.u1, g.v0); glVertex2f(penX + gw, yTop);
                penX += Math.round(g.advance * cs);
            }
            glEnd();
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawText: " + t);
        } finally {
            try { glUseProgram(0); } catch (Throwable ignored) {}
            try { glBindTexture(0x0DE1, 0); } catch (Throwable ignored) {}
            try {
                if (modelPushed) { matrixMode(0x1700); popMatrix(); }
            } catch (Throwable ignored) {}
            try {
                if (projPushed) { matrixMode(0x1701); popMatrix(); }
            } catch (Throwable ignored) {}
            restoreLegacyGlState(savedGlState);
        }
    }

    private int ensureFontTexture(UiFont font) {
        Integer cached = fontTextures.get(font);
        if (cached != null) return cached;

        ensureNativeTextureApiResolved();
        if (nativeTextureApiAvailable) {
            try {
                int texId = createFontTextureViaNativeImage(font);
                syncAfterFontUpload(texId);
                fontTextures.put(font, texId);
                LauncherLog.ui(1, "[UiRenderer] atlas police uploadé via NativeImage/TextureManager, texId=" + texId);
                return texId;
            } catch (Throwable t) {
                LauncherLog.err("[UiRenderer] createFontTextureViaNativeImage a échoué, repli sur glTexImage2D brut : " + t);
                // repli ci-dessous
            }
        }
        try {
            int texId = createFontTextureRaw(font);
            syncAfterFontUpload(texId);
            fontTextures.put(font, texId);
            LauncherLog.ui(1, "[UiRenderer] atlas police uploadé (repli brut), texId=" + texId);
            return texId;
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] ensureFontTexture: " + t);
            fontTextures.put(font, -1);
            return -1;
        }
    }

    /**
     * BUG TROUVÉ (era E, texte REGULAR corrompu un lancement sur deux,
     * confirmé par capture d'écran : deux sessions IDENTIQUES de la même
     * instance, mêmes réglages, l'une nette et l'autre corrompue — donc pas
     * un bug déterministe de code, un vrai résultat différent produit par le
     * pilote GPU d'un lancement à l'autre) : le log de démarrage confirme
     * explicitement que CETTE machine a un contournement Sodium actif pour
     * "NVIDIA_THREADED_OPTIMIZATIONS_BROKEN" — la soumission de commandes en
     * thread séparé du pilote NVIDIA est documentée bogguée ICI. Sodium
     * applique ses propres contournements pour SES draws, mais notre
     * `glTexImage2D`/`glGenerateMipmap` (injectés via Mixin, hors du contrôle
     * de Sodium) n'en bénéficient pas : rien n'empêche le pilote de renvoyer
     * la main avant d'avoir RÉELLEMENT terminé l'upload/la génération des
     * mipmaps en arrière-plan, laissant échantillonner une texture
     * partiellement écrite (garbage) — l'atlas n'étant créé qu'une seule
     * fois par lancement, ce résultat de course reste figé pour toute la
     * session, cohérent avec TOUT ce qui a été observé. Fix : `glFinish()`
     * juste après l'upload, une seule fois par police par lancement (aucun
     * risque de perf) — force le pilote à réellement terminer avant qu'on
     * ne considère la texture prête à être échantillonnée.
     */
    private void syncAfterFontUpload(int texId) {
        if (texId < 0) return;
        try {
            glFinish();
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] syncAfterFontUpload (texId=" + texId + "): " + t);
        }
    }

    private static Class<?> nativeImageClass, nativeImageBackedTextureClass, textureManagerClass, abstractTextureClass, identifierClass;
    private static Object nativeImageFormatRgba;
    private static java.lang.reflect.Constructor<?> nativeImageCtor, nativeImageBackedTextureCtor;
    private static boolean nativeImageBackedTextureNeedsLabel;
    private static java.lang.reflect.Field nativeImagePointerField;
    private static Method nativeImageSetColor, nativeImageCloseMethod, textureUploadMethod, textureGetGlIdMethod,
        textureBindTextureMethod, textureManagerRegisterTextureMethod, identifierOfMethod, mcGetTextureManagerMethod,
        memCopyMethod, memAddressMethod;
    private static boolean nativeTextureApiResolveAttempted, nativeTextureApiAvailable, bulkCopyAvailable;
    private static int fontTextureCounter;

    /**
     * BUG TROUVÉ (1.21.4, crash natif confirmé par bissection — voir
     * historique de session) : créer notre PROPRE texture GL brute
     * (glGenTextures/glTexImage2D/glGenerateMipmap/glTexParameteri via
     * réflexion) pour l'atlas de police plantait le process de façon
     * imprévisible (parfois REGULAR, parfois BOLD, jamais un point de code
     * fixe) — le déplacement de l'ordre de création n'a fait que déplacer le
     * crash, pas le résoudre. Recherche sur les mods Fabric open-source
     * confirmée : AUCUN mod sérieux ne crée de texture dynamique en GL brut
     * — tous passent par {@code NativeImage} + {@code
     * NativeImageBackedTexture} + {@code TextureManager.registerTexture()},
     * le chemin de création de texture SUIVI par le système de gestion
     * d'état interne de Minecraft ({@code GlStateManager}/{@code
     * RenderSystem}). Notre ancien code contournait entièrement ce suivi —
     * hypothèse retenue : ça désynchronisait l'état GL que le rendu vanilla
     * (qui tourne dans la même frame) suppose cohérent, plantage
     * imprévisible selon ce qui se dessine à côté. Résolu dynamiquement
     * (aucune classe Minecraft compilée en dur) ; repli sur l'ancien chemin
     * brut UNIQUEMENT si cette résolution échoue entièrement (ex: signature
     * qui aurait changé sur une future version).
     */
    private void ensureNativeTextureApiResolved() {
        if (nativeTextureApiResolveAttempted) return;
        nativeTextureApiResolveAttempted = true;
        try {
            nativeImageClass = McReflect.yarnClass("net/minecraft/client/texture/NativeImage");
            nativeImageBackedTextureClass = McReflect.yarnClass("net/minecraft/client/texture/NativeImageBackedTexture");
            textureManagerClass = McReflect.yarnClass("net/minecraft/client/texture/TextureManager");
            abstractTextureClass = McReflect.yarnClass("net/minecraft/client/texture/AbstractTexture");
            identifierClass = McReflect.yarnClass("net/minecraft/util/Identifier");
            Class<?> formatClass = McReflect.yarnClass("net/minecraft/client/texture/NativeImage$Format");
            if (nativeImageClass == null || nativeImageBackedTextureClass == null || textureManagerClass == null
                    || abstractTextureClass == null || identifierClass == null || formatClass == null) {
                LauncherLog.warn("[UiRenderer] résolution NativeImage/TextureManager : une classe introuvable, repli brut");
                return;
            }

            String rgbaObf = MappingsRegistry.getObfFieldName("net/minecraft/client/texture/NativeImage$Format", "RGBA");
            java.lang.reflect.Field rgbaField = formatClass.getDeclaredField(rgbaObf);
            rgbaField.setAccessible(true);
            nativeImageFormatRgba = rgbaField.get(null);

            nativeImageCtor = nativeImageClass.getDeclaredConstructor(formatClass, int.class, int.class, boolean.class);
            nativeImageCtor.setAccessible(true);
            // BUG TROUVÉ (era E, 1.21.11) : NativeImageBackedTexture(NativeImage)
            // (le seul constructeur utilisé jusqu'à la 1.21.4) n'existe plus —
            // Blaze3D ajoute un label de debug obligatoire en 1er paramètre
            // (Supplier<String>), confirmé via mappings 1.21.11 :
            // "(Ljava/util/function/Supplier;Lfyh;)V <init>" (fyh=NativeImage) —
            // AUCUN constructeur 1-arg NativeImage-seul n'existe plus du tout sur
            // cette version. Essaie l'ancien d'abord (1.20.4/1.21.4), puis le
            // nouveau (Supplier<String>, NativeImage) en repli.
            try {
                nativeImageBackedTextureCtor = nativeImageBackedTextureClass.getDeclaredConstructor(nativeImageClass);
                nativeImageBackedTextureNeedsLabel = false;
            } catch (NoSuchMethodException e) {
                nativeImageBackedTextureCtor = nativeImageBackedTextureClass.getDeclaredConstructor(java.util.function.Supplier.class, nativeImageClass);
                nativeImageBackedTextureNeedsLabel = true;
            }
            nativeImageBackedTextureCtor.setAccessible(true);

            nativeImageSetColor = McReflect.method(nativeImageClass, "net/minecraft/client/texture/NativeImage", "setColor", int.class, int.class, int.class);
            nativeImageCloseMethod = McReflect.noArgMethod(nativeImageClass, "net/minecraft/client/texture/NativeImage", "close");

            // BUG DE PERFORMANCE TROUVÉ (v347, signalé par l'utilisateur : "je
            // lag à 8 FPS") : remplir un atlas 1024x2048 (2 millions de pixels)
            // via setColor() UN PAR UN — chaque appel étant une réflexion Java
            // (Method.invoke, avec autoboxing) — coûte des millions
            // d'invocations réflexives par police créée. Repli : accès direct
            // à la mémoire native de NativeImage (champ "pointer", un long
            // pointant vers le buffer hors-tas) via MemoryUtil.memCopy — une
            // SEULE copie mémoire brute au lieu de 2 millions d'appels
            // individuels. Notre ByteBuffer existant (octets R,G,B,A
            // consécutifs) a EXACTEMENT le même agencement mémoire que le
            // format "RGBA petit-boutiste" de NativeImage (petit-boutiste :
            // R d'abord en mémoire, A en dernier) — aucune conversion
            // supplémentaire nécessaire, juste une copie brute octet à octet.
            try {
                String pointerObf = MappingsRegistry.getObfFieldName("net/minecraft/client/texture/NativeImage", "pointer");
                nativeImagePointerField = nativeImageClass.getDeclaredField(pointerObf);
                nativeImagePointerField.setAccessible(true);
                memCopyMethod = gl("org.lwjgl.system.MemoryUtil", "memCopy", long.class, long.class, long.class);
                memAddressMethod = gl("org.lwjgl.system.MemoryUtil", "memAddress", java.nio.ByteBuffer.class);
                bulkCopyAvailable = true;
            } catch (Throwable t) {
                LauncherLog.warn("[UiRenderer] copie mémoire en bloc (MemoryUtil) indisponible, repli sur setColor() pixel par pixel (lent) : " + t);
                bulkCopyAvailable = false;
            }
            textureUploadMethod = McReflect.noArgMethod(nativeImageBackedTextureClass, "net/minecraft/client/texture/NativeImageBackedTexture", "upload");
            textureGetGlIdMethod = McReflect.noArgMethod(abstractTextureClass, "net/minecraft/client/texture/AbstractTexture", "getGlId");
            textureBindTextureMethod = McReflect.noArgMethod(abstractTextureClass, "net/minecraft/client/texture/AbstractTexture", "bindTexture");
            textureManagerRegisterTextureMethod = McReflect.method(textureManagerClass, "net/minecraft/client/texture/TextureManager",
                "registerTexture", identifierClass, abstractTextureClass);
            identifierOfMethod = McReflect.method(identifierClass, "net/minecraft/util/Identifier", "of", String.class, String.class);
            Object mc = McReflect.minecraftClient();
            mcGetTextureManagerMethod = mc != null
                ? McReflect.noArgMethod(mc.getClass(), "net/minecraft/client/MinecraftClient", "getTextureManager")
                : null;

            nativeTextureApiAvailable = nativeImageSetColor != null && textureUploadMethod != null
                && textureGetGlIdMethod != null && textureBindTextureMethod != null
                && textureManagerRegisterTextureMethod != null && identifierOfMethod != null
                && mcGetTextureManagerMethod != null && nativeImageFormatRgba != null;
            LauncherLog.info("[UiRenderer] API NativeImage/TextureManager résolue : disponible=" + nativeTextureApiAvailable);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] résolution API NativeImage/TextureManager échouée, repli brut : " + t);
            nativeTextureApiAvailable = false;
        }
    }

    private int createFontTextureViaNativeImage(UiFont font) throws Exception {
        java.awt.image.BufferedImage img = font.atlasImage();
        int w = img.getWidth(), h = img.getHeight();

        Object nativeImage = nativeImageCtor.newInstance(nativeImageFormatRgba, w, h, false);
        try {
            if (bulkCopyAvailable) {
                // Chemin rapide : une seule copie mémoire brute (voir
                // ensureNativeTextureApiResolved pour le pourquoi détaillé).
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
                long srcAddr = (long) memAddressMethod.invoke(null, buf);
                long dstAddr = (long) nativeImagePointerField.get(nativeImage);
                memCopyMethod.invoke(null, srcAddr, dstAddr, (long) buf.remaining());
            } else {
                // Repli lent (voir ensureNativeTextureApiResolved) — évite
                // juste de ne RIEN dessiner si MemoryUtil ne se résout pas.
                int[] row = new int[w];
                for (int y = 0; y < h; y++) {
                    img.getRGB(0, y, w, 1, row, 0, w);
                    for (int x = 0; x < w; x++) {
                        int argb = row[x];
                        int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                        // NativeImage.setColor attend du RGBA petit-boutiste, donc
                        // ABGR en notation big-endian habituelle (alpha=octet le
                        // plus significatif, rouge=le moins significatif) — voir
                        // sa javadoc Yarn ("little-endian RGBA, or big-endian ABGR").
                        int nativeColor = (a << 24) | (b << 16) | (g << 8) | r;
                        nativeImageSetColor.invoke(nativeImage, x, y, nativeColor);
                    }
                }
            }

            Object texture = nativeImageBackedTextureNeedsLabel
                ? nativeImageBackedTextureCtor.newInstance((java.util.function.Supplier<String>) () -> "yuyuframe_font", nativeImage)
                : nativeImageBackedTextureCtor.newInstance(nativeImage);
            textureUploadMethod.invoke(texture); // fait le VRAI glTexImage2D, via le chemin suivi par Minecraft
            int texId = (int) textureGetGlIdMethod.invoke(texture);

            // Filtre trilinéaire + clamp-to-edge (voir historique de session
            // pour le pourquoi) — appliqués APRÈS coup sur une texture déjà
            // créée par la voie sûre : bind via AbstractTexture.bindTexture()
            // (passe par GlStateManager, pas notre glBindTexture brut) avant
            // ces quelques réglages, qui eux restent des appels GL directs
            // mais bien plus anodins qu'une création de texture complète.
            textureBindTextureMethod.invoke(texture);
            glGenerateMipmap(0x0DE1);
            glTexParameteri(0x0DE1, 0x2801, 0x2703); // GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR
            glTexParameteri(0x0DE1, 0x2800, 0x2601); // GL_TEXTURE_MAG_FILTER, GL_LINEAR
            glTexParameteri(0x0DE1, 0x2802, 0x812F); // GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE
            glTexParameteri(0x0DE1, 0x2803, 0x812F); // GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE

            Object textureManager = mcGetTextureManagerMethod.invoke(McReflect.minecraftClient());
            Object id = identifierOfMethod.invoke(null, "yuyuframe", "font_" + (fontTextureCounter++));
            textureManagerRegisterTextureMethod.invoke(textureManager, id, texture);
            return texId;
        } finally {
            // NativeImage possède de la mémoire HORS-TAS (native) — doit être
            // explicitement libérée, contrairement au ByteBuffer direct de
            // l'ancien chemin (géré par le GC, voir Cleaner de
            // ByteBuffer.allocateDirect) : celui-ci ne l'est pas.
            if (nativeImageCloseMethod != null) {
                try { nativeImageCloseMethod.invoke(nativeImage); } catch (Throwable ignored) {}
            }
        }
    }

    /** Ancien chemin (GL brut via réflexion) — conservé UNIQUEMENT en repli si la résolution NativeImage échoue. */
    private int createFontTextureRaw(UiFont font) throws Exception {
        java.awt.image.BufferedImage img = font.atlasImage();
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

        // DIAG-FONTCORRUPT (era E, bug "texte corrompu une fois sur deux" —
        // stable sur TOUTE une session, jamais un flicker en cours de route,
        // donc lié à CETTE création unique/mise en cache, pas au dessin par
        // frame) : cette méthode n'est appelée QU'UNE SEULE FOIS par police
        // par lancement (voir ensureFontTexture, résultat mis en cache) — un
        // seul appel de log ici, aucun risque de perf, même motif que DIAG7
        // (voir historique de session) mais volontairement gardé cette fois
        // (pas par frame).
        int activeUnitBefore = glGetInteger(0x84E0); // GL_ACTIVE_TEXTURE
        int boundTexBefore = glGetInteger(0x8069);   // GL_TEXTURE_BINDING_2D
        int errBefore = drainGlErrors();

        int texId = glGenTextures();
        glBindTexture(0x0DE1, texId); // GL_TEXTURE_2D
        int errAfterBind = drainGlErrors();
        glTexImage2D(0x0DE1, 0, 0x1908, w, h, 0, 0x1908, 0x1401, buf); // GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE
        int errAfterTexImage = drainGlErrors();
        glGenerateMipmap(0x0DE1);
        int errAfterMipmap = drainGlErrors();
        glTexParameteri(0x0DE1, 0x2801, 0x2703); // GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR
        glTexParameteri(0x0DE1, 0x2800, 0x2601); // GL_TEXTURE_MAG_FILTER, GL_LINEAR
        glTexParameteri(0x0DE1, 0x2802, 0x812F); // GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE
        glTexParameteri(0x0DE1, 0x2803, 0x812F); // GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE
        int errAfterParams = drainGlErrors();

        // BUG TROUVÉ (era E, texte REGULAR corrompu de façon non-déterministe
        // — confirmé toujours présent MÊME en vanilla sans aucun mod, donc
        // rien à voir avec Sodium/le threading NVIDIA, hypothèse infirmée) :
        // `buf` est un ByteBuffer DIRECT (mémoire hors-tas, libérée par un
        // Cleaner quand l'objet Java devient inatteignable) — la JVM tourne
        // ici avec ZGC (voir JVM Arguments dans les logs de lancement,
        // -XX:+UseZGC -XX:+ZGenerational), un collecteur concurrent
        // particulièrement agressif. Rien n'empêche la JIT/le GC de
        // considérer `buf` "mort" dès la dernière ligne qui le RÉFÉRENCE
        // explicitement (le `glTexImage2D` juste au-dessus) : si le pilote
        // NVIDIA ne copie pas les octets de façon strictement synchrone
        // pendant cet appel (contrairement à ce qu'exige la spec OpenGL, mais
        // des bugs de pilote de ce type existent), un GC concurrent
        // déclenché entre-temps peut libérer cette mémoire AVANT que le
        // pilote ait fini de la lire — le pilote lit alors de la mémoire déjà
        // réutilisée/libérée = texture corrompue, sans qu'aucune erreur GL ne
        // soit levée (cohérent avec `DIAG-FONTCORRUPT` : jamais un seul
        // `glErr` observé). Un `glFinish()` seul (tenté juste avant, sans
        // effet) n'empêche PAS ça : il attend la fin d'une commande qui a
        // DÉJÀ lu la mauvaise mémoire, trop tard pour corriger le résultat.
        // Fix : `glFinish()` ICI (PAS seulement dans syncAfterFontUpload,
        // appelé APRÈS le retour de cette méthode — trop tard, `buf` ne
        // serait alors déjà plus protégé) suivi de
        // `Reference.reachabilityFence(buf)` — l'ordre est capital : glFinish
        // garantit que le pilote a RÉELLEMENT fini de lire `buf`, et la
        // reachabilityFence juste après garantit que la JVM n'a PAS pu
        // libérer sa mémoire native PENDANT cette attente, quelle que soit
        // l'agressivité du GC.
        glFinish();
        reachabilityFence(buf);

        // BUG TROUVÉ (era E) : le diagnostic glGetTexImage tenté ici en v362
        // a provoqué un CRASH JVM natif (EXCEPTION_ACCESS_VIOLATION, écriture
        // hors bornes côté pilote NVIDIA+DSA — voir hs_err_pid*.log,
        // confirmé pointer exactement sur cet appel). Retiré définitivement
        // — ne JAMAIS réintroduire un glGetTexImage ici sans un moyen plus
        // sûr de vérifier au préalable la taille réellement allouée côté
        // pilote (ex: glGetTexLevelParameteriv AVANT de dimensionner le
        // buffer de lecture, jamais en supposant que w/h côté Java
        // correspondent forcément à ce que le pilote a alloué).
        glBindTexture(0x0DE1, 0);

        LauncherLog.info("[LauncherAgent] DIAG-FONTCORRUPT: texId=" + texId + " atlasW=" + w + " atlasH=" + h
            + " activeUnitBefore=0x" + Integer.toHexString(activeUnitBefore)
            + " boundTex2DBefore=" + boundTexBefore
            + " glErr(before=" + errBefore + ", afterBind=" + errAfterBind
            + ", afterTexImage=" + errAfterTexImage + ", afterMipmap=" + errAfterMipmap
            + ", afterParams=" + errAfterParams + ")");
        return texId;
    }

    // ── Scissor (clipping rectangulaire — utilisé par UiScrollContainer) ─────

    public void beginScissor(int x, int y, int w, int h) {
        try {
            glEnable(0x0C11); // GL_SCISSOR_TEST
            glScissor(x, y, w, h);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] beginScissor: " + t);
        }
    }

    public void endScissor() {
        try { glDisable(0x0C11); } catch (Throwable ignored) {}
    }

    // ── GL calls via réflexion (org.lwjgl.opengl.GL20/GL11 — noms publics,
    // pas obfusqués, identiques LWJGL2/LWJGL3, donc pas besoin de MappingsRegistry) ──

    private Method gl(String cls, String method, Class<?>... params) throws Exception {
        String key = cls + "#" + method + java.util.Arrays.toString(params);
        Method m = glMethods.get(key);
        if (m != null) return m;
        Class<?> c = Class.forName(cls, true, gameClassLoader);
        m = c.getMethod(method, params);
        glMethods.put(key, m);
        return m;
    }

    private int glGetError() throws Exception {
        return (int) gl("org.lwjgl.opengl.GL11", "glGetError").invoke(null);
    }
    /** Vide tous les codes d'erreur en attente, retourne le premier non-zéro rencontré (0 = aucune erreur). */
    private int drainGlErrors() throws Exception {
        int first = 0, code;
        int guard = 0;
        while ((code = glGetError()) != 0 && guard++ < 16) {
            if (first == 0) first = code;
        }
        return first;
    }

    private int glCreateShader(int type) throws Exception {
        return (int) gl("org.lwjgl.opengl.GL20", "glCreateShader", int.class).invoke(null, type);
    }
    private void glShaderSource(int shader, String src) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glShaderSource", int.class, CharSequence.class).invoke(null, shader, src);
    }
    private void glCompileShader(int shader) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glCompileShader", int.class).invoke(null, shader);
    }
    private int glCreateProgram() throws Exception {
        return (int) gl("org.lwjgl.opengl.GL20", "glCreateProgram").invoke(null);
    }
    private void glAttachShader(int program, int shader) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glAttachShader", int.class, int.class).invoke(null, program, shader);
    }
    private void glLinkProgram(int program) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glLinkProgram", int.class).invoke(null, program);
    }
    private int glGetUniformLocation(int program, String name) throws Exception {
        return (int) gl("org.lwjgl.opengl.GL20", "glGetUniformLocation", int.class, CharSequence.class)
            .invoke(null, program, name);
    }
    private int glGetShaderi(int shader, int pname) throws Exception {
        return (int) gl("org.lwjgl.opengl.GL20", "glGetShaderi", int.class, int.class).invoke(null, shader, pname);
    }
    private String glGetShaderInfoLog(int shader) throws Exception {
        return (String) gl("org.lwjgl.opengl.GL20", "glGetShaderInfoLog", int.class).invoke(null, shader);
    }
    private int glGetProgrami(int program, int pname) throws Exception {
        return (int) gl("org.lwjgl.opengl.GL20", "glGetProgrami", int.class, int.class).invoke(null, program, pname);
    }
    private String glGetProgramInfoLog(int program) throws Exception {
        return (String) gl("org.lwjgl.opengl.GL20", "glGetProgramInfoLog", int.class).invoke(null, program);
    }
    private void glUseProgram(int program) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUseProgram", int.class).invoke(null, program);
    }
    private void glUniform1f(int loc, float v) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUniform1f", int.class, float.class).invoke(null, loc, v);
    }
    private void glUniform1i(int loc, int v) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUniform1i", int.class, int.class).invoke(null, loc, v);
    }
    private void glUniform2f(int loc, float a, float b) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUniform2f", int.class, float.class, float.class).invoke(null, loc, a, b);
    }
    private void glUniform4f(int loc, float a, float b, float c, float d) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUniform4f", int.class, float.class, float.class, float.class, float.class)
            .invoke(null, loc, a, b, c, d);
    }
    private void glColor4f(float r, float g, float b, float a) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glColor4f", float.class, float.class, float.class, float.class)
            .invoke(null, r, g, b, a);
    }
    private void glBegin(int mode) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glBegin", int.class).invoke(null, mode);
    }
    private void glVertex2f(float x, float y) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glVertex2f", float.class, float.class).invoke(null, x, y);
    }
    private void glEnd() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glEnd").invoke(null);
    }
    private void glEnable(int cap) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glEnable", int.class).invoke(null, cap);
    }
    private void glDisable(int cap) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glDisable", int.class).invoke(null, cap);
    }
    private boolean glIsEnabled(int cap) throws Exception {
        return (boolean) gl("org.lwjgl.opengl.GL11", "glIsEnabled", int.class).invoke(null, cap);
    }
    private void glBlendFunc(int sfactor, int dfactor) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glBlendFunc", int.class, int.class).invoke(null, sfactor, dfactor);
    }
    private void glClear(int mask) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glClear", int.class).invoke(null, mask);
    }
    /** GL13, pas GL11 — sélection d'unité de texture (multitexturing), voir drawVanillaItemIcon. */
    /** Voir glBindTexture pour le mécanisme — même cache logiciel côté GlStateManager, désync possible pour l'unité de texture active elle-même, pas seulement la texture bindée. */
    private static Method glStateManagerActiveTexture;
    private static boolean glStateManagerActiveTextureResolved;

    /**
     * BUG TROUVÉ (era E, 1.21.11 — cause RÉELLE de la corruption de texte
     * "aléatoire d'un lancement à l'autre", après avoir écarté rastérisation/
     * SDF/upload GPU/GC, tous confirmés innocents par diagnostic direct) :
     * {@code GlStateManager} (la couche GL de Blaze3D) maintient DEUX caches
     * logiciels — un par unité de texture bindée (déjà connu, voir
     * {@link #glBindTexture}) ET un pour l'UNITÉ ACTIVE elle-même (champ
     * `activeTexture`, vérifié par désassemblage de
     * {@code com.mojang.blaze3d.opengl.GlStateManager._activeTexture(int)} :
     * si le cache dit déjà cette unité, le vrai {@code glActiveTexture} est
     * SAUTÉ). Nos appels précédents en {@code GL13.glActiveTexture} brut
     * changeaient l'unité RÉELLE sans jamais mettre à jour ce cache — un
     * appel Blaze3D ultérieur (n'importe quel rendu vanilla après le nôtre,
     * variable d'une frame/d'un lancement à l'autre selon ce qui a été
     * dessiné juste avant) qui CROIT être déjà sur la bonne unité saute son
     * propre {@code glActiveTexture}, laissant la VRAIE unité active être
     * celle où NOUS l'avons laissée — son {@code bindTexture} suivant se
     * retrouve alors à binder SA texture sur NOTRE unité (ou vice-versa),
     * un draw échantillonnant une texture totalement étrangère avec des UV
     * qui n'ont aucun sens pour elle = bruit visuel, exactement le symptôme
     * observé, non-déterministe puisqu'il dépend de l'historique de rendu de
     * CETTE frame précise. Seul {@code glBindTexture} avait été routé via
     * GlStateManager jusqu'ici (fix plus ancien, pour un bug similaire sur
     * les icônes d'armure) — {@code glActiveTexture} ne l'a jamais été,
     * sur AUCUN bracket, ce trou existant depuis toujours mais invisible
     * tant que rien ne changeait volontairement d'unité de texture avant
     * cette session (drawTextModern, ajouté pour l'era E, est le premier
     * code de ce projet à le faire explicitement).
     */
    private void glActiveTexture(int texture) throws Exception {
        if (!glStateManagerActiveTextureResolved) {
            glStateManagerActiveTextureResolved = true;
            glStateManagerActiveTexture = resolveGlStateManagerMethod("activeTexture", "_activeTexture");
        }
        if (glStateManagerActiveTexture != null) {
            try {
                glStateManagerActiveTexture.invoke(null, texture);
                return;
            } catch (Throwable ignored) {} // repli sur l'appel brut ci-dessous
        }
        gl("org.lwjgl.opengl.GL13", "glActiveTexture", int.class).invoke(null, texture);
    }

    /**
     * Résout {@code GlStateManager.<oldName>(int)} (package
     * {@code com.mojang.blaze3d.platform}, brackets antérieurs à Blaze3D) ou,
     * à défaut, {@code GlStateManager.<newName>(int)} (package
     * {@code com.mojang.blaze3d.opengl}, era E/Blaze3D 1.21.6+ — nom de
     * méthode préfixé {@code _}, vérifié par désassemblage direct du jar
     * client 1.21.11, PAS supposé). Classes NON obfusquées (bibliothèque
     * Blaze3D fournie telle quelle, jamais remappée par Yarn) — {@link
     * McReflect#rawClass} est le bon outil, PAS {@code yarnClass}/
     * {@code MappingsRegistry} (réservés aux classes obfusquées "net.minecraft").
     */
    private static Method resolveGlStateManagerMethod(String oldName, String newName) {
        try {
            Class<?> oldClass = McReflect.rawClass("com.mojang.blaze3d.platform.GlStateManager");
            if (oldClass != null) {
                try { return oldClass.getMethod(oldName, int.class); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> newClass = McReflect.rawClass("com.mojang.blaze3d.opengl.GlStateManager");
            if (newClass != null) {
                try { return newClass.getMethod(newName, int.class); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }
    /**
     * BUG TROUVÉ (retrouvé dans l'historique du projet, confirmé responsable
     * du crash NATIF 0xC0000409 sur 1.21.11 ET reproduit sur 1.21.4) : {@code
     * glPushAttrib}/{@code glPopAttrib} sont des fonctions de pile
     * d'attributs OpenGL 1.x très anciennes, quasiment jamais utilisées par
     * les applications modernes, et un point d'instabilité CONNU des pilotes
     * GPU récents — même sous Compatibility Profile (1.8.9), où elles
     * n'avaient encore jamais crashé jusqu'ici, mais rien ne garantit que ça
     * reste vrai sur tout pilote/GPU. Décision : ne plus JAMAIS les appeler,
     * nulle part dans ce fichier — remplacées partout par une capture/
     * restauration manuelle et CIBLÉE des seuls drapeaux GL réellement
     * modifiés par nos méthodes *Legacy (voir {@link #captureLegacyGlState}/
     * {@link #restoreLegacyGlState}), au lieu de s'en remettre à une pile
     * d'attributs entière pour un besoin bien plus restreint.
     */
    private static final int[] LEGACY_TOGGLE_CAPS = {
        0x0DE1, // GL_TEXTURE_2D
        0x0B71, // GL_DEPTH_TEST
        0x0B44, // GL_CULL_FACE
        0x0BC0, // GL_ALPHA_TEST
        0x0C11, // GL_SCISSOR_TEST
        0x0BE2, // GL_BLEND
    };

    private static final class LegacyGlState {
        final boolean[] enabled;
        final int blendSrc, blendDst;
        LegacyGlState(boolean[] enabled, int blendSrc, int blendDst) {
            this.enabled = enabled;
            this.blendSrc = blendSrc;
            this.blendDst = blendDst;
        }
    }

    /** Capture l'état AVANT modification — voir {@link #LEGACY_TOGGLE_CAPS}. */
    private LegacyGlState captureLegacyGlState() throws Exception {
        boolean[] enabled = new boolean[LEGACY_TOGGLE_CAPS.length];
        for (int i = 0; i < LEGACY_TOGGLE_CAPS.length; i++) enabled[i] = glIsEnabled(LEGACY_TOGGLE_CAPS[i]);
        int blendSrc = glGetInteger(0x0BE1); // GL_BLEND_SRC
        int blendDst = glGetInteger(0x0BE0); // GL_BLEND_DST
        return new LegacyGlState(enabled, blendSrc, blendDst);
    }

    /** {@code null} si la capture n'a jamais réussi (exception avant) — no-op silencieux dans ce cas. */
    private void restoreLegacyGlState(LegacyGlState state) {
        if (state == null) return;
        for (int i = 0; i < LEGACY_TOGGLE_CAPS.length; i++) {
            try {
                if (state.enabled[i]) glEnable(LEGACY_TOGGLE_CAPS[i]);
                else glDisable(LEGACY_TOGGLE_CAPS[i]);
            } catch (Throwable ignored) {}
        }
        try { glBlendFunc(state.blendSrc, state.blendDst); } catch (Throwable ignored) {}
    }
    private void matrixMode(int mode) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glMatrixMode", int.class).invoke(null, mode);
    }
    private void pushMatrix() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glPushMatrix").invoke(null);
    }
    private void popMatrix() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glPopMatrix").invoke(null);
    }
    private void loadIdentity() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glLoadIdentity").invoke(null);
    }
    private void glOrtho(double left, double right, double bottom, double top, double near, double far) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glOrtho", double.class, double.class, double.class, double.class, double.class, double.class)
            .invoke(null, left, right, bottom, top, near, far);
    }
    private void glTranslatef(float x, float y, float z) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glTranslatef", float.class, float.class, float.class).invoke(null, x, y, z);
    }
    private void glScalef(float x, float y, float z) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glScalef", float.class, float.class, float.class).invoke(null, x, y, z);
    }
    private void glTexCoord2f(float u, float v) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glTexCoord2f", float.class, float.class).invoke(null, u, v);
    }
    private int glGenTextures() throws Exception {
        return (int) gl("org.lwjgl.opengl.GL11", "glGenTextures").invoke(null);
    }
    private void glFinish() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glFinish").invoke(null);
    }

    /**
     * Équivalent de {@code java.lang.ref.Reference.reachabilityFence(Object)}
     * (JDK 9+) via réflexion — ce fichier compile en {@code --release 8}
     * (compat multi-version, voir build.bat), qui masque toute API postérieure
     * à Java 8 à la COMPILATION (contrairement à un simple `-source 8`) : un
     * appel direct à `reachabilityFence` ne compile pas ("cannot find
     * symbol"), même si la JVM d'exécution réelle (21 ici) le possède bien.
     * Résolu paresseusement, mis en cache, jamais réessayé après un premier
     * échec (même motif que les autres wrappers `gl*` de ce fichier).
     */
    private static volatile java.lang.reflect.Method reachabilityFenceMethod;
    private static void reachabilityFence(Object ref) {
        try {
            java.lang.reflect.Method m = reachabilityFenceMethod;
            if (m == null) {
                m = java.lang.ref.Reference.class.getMethod("reachabilityFence", Object.class);
                reachabilityFenceMethod = m;
            }
            m.invoke(null, ref);
        } catch (Throwable ignored) {
            // Best-effort — sans cette méthode (JDK < 9, ne devrait jamais
            // arriver au runtime réel), aucune protection supplémentaire,
            // comportement identique à avant ce fix.
        }
    }
    /** Voir glBindTexture — résolu paresseusement, mis en cache, jamais réassigné après un premier échec (évite de retenter la réflexion à chaque frame). */
    private static Method glStateManagerBindTexture;
    private static boolean glStateManagerBindTextureResolved;

    /**
     * Route le bind GL_TEXTURE_2D via {@code GlStateManager.bindTexture(int)}
     * (Yarn "bfl.i", `method_9839`) plutôt que l'appel LWJGL brut — CRUCIAL :
     * `GlStateManager` maintient son PROPRE cache Java de "texture active par
     * unité" (`field_10722 activeTexture` / `field_10715 TEXTURES`, vérifié
     * dans mappings-1.8.9.tiny) et SAUTE le vrai `glBindTexture` GL s'il croit
     * que la texture demandée est déjà active. Nos appels précédents en
     * `GL11.glBindTexture` brut changeaient la texture RÉELLE sans jamais
     * mettre à jour ce cache — désynchronisant la croyance de GlStateManager
     * de l'état GL réel. Résultat concret : après un drawText() (police,
     * bind brut), l'appel vanilla suivant à `renderInGuiWithOverrides`
     * (armor icon) demande à re-binder l'atlas de blocs via
     * `GlStateManager.bindTexture(...)`, qui CROIT l'avoir déjà fait (son
     * cache dit "atlas déjà actif") et SAUTE le bind réel — l'icône se
     * retrouve alors dessinée avec la texture RÉELLEMENT active, notre atlas
     * de police SDF, d'où les formes blanches fragmentées. Router NOS PROPRES
     * binds à travers ce même GlStateManager élimine le désync à la racine
     * (nos binds ET ceux de vanilla passent désormais par la même source de
     * vérité), au lieu d'un fix ponctuel côté rendu d'item seulement.
     */
    private void glBindTexture(int target, int texture) throws Exception {
        if (target == 0x0DE1 && !glStateManagerBindTextureResolved) { // GL_TEXTURE_2D
            glStateManagerBindTextureResolved = true;
            // Era E (Blaze3D 1.21.6+) : classe déplacée vers
            // com.mojang.blaze3d.opengl.GlStateManager, méthode renommée
            // "_bindTexture" (vérifié par désassemblage direct, voir
            // resolveGlStateManagerMethod) — l'ancienne résolution ne
            // couvrait que "com/mojang/blaze3d/platform/GlStateManager"/
            // "bindTexture" (brackets antérieurs), silencieusement null sur
            // 1.21.11, d'où un repli permanent sur l'appel brut désynchronisant
            // le cache de GlStateManager (cause racine du texte corrompu).
            glStateManagerBindTexture = resolveGlStateManagerMethod("bindTexture", "_bindTexture");
        }
        if (target == 0x0DE1 && glStateManagerBindTexture != null) {
            try {
                glStateManagerBindTexture.invoke(null, texture);
                return;
            } catch (Throwable ignored) {} // repli sur l'appel brut ci-dessous
        }
        gl("org.lwjgl.opengl.GL11", "glBindTexture", int.class, int.class).invoke(null, target, texture);
    }
    private void glTexParameteri(int target, int pname, int param) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glTexParameteri", int.class, int.class, int.class).invoke(null, target, pname, param);
    }
    private void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                               int format, int type, java.nio.ByteBuffer pixels) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glTexImage2D", int.class, int.class, int.class, int.class, int.class,
            int.class, int.class, int.class, java.nio.ByteBuffer.class)
            .invoke(null, target, level, internalFormat, width, height, border, format, type, pixels);
    }
    private void glScissor(int x, int y, int w, int h) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glScissor", int.class, int.class, int.class, int.class).invoke(null, x, y, w, h);
    }
    private void glGenerateMipmap(int target) throws Exception {
        // GL30 (promu depuis GL_ARB_framebuffer_object) — dispo aussi bien
        // sous LWJGL2 (1.8.9, contexte GL2.1) que LWJGL3 (1.21), l'extension
        // sous-jacente étant supportée par tout GPU ~2006+.
        gl("org.lwjgl.opengl.GL30", "glGenerateMipmap", int.class).invoke(null, target);
    }

    // ── GL réflexion — pipeline MODERNE uniquement (VAO/VBO, GL15/GL20/GL30) ──

    private void glDrawArrays(int mode, int first, int count) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glDrawArrays", int.class, int.class, int.class).invoke(null, mode, first, count);
    }
    private int glGetInteger(int pname) throws Exception {
        return (int) gl("org.lwjgl.opengl.GL11", "glGetInteger", int.class).invoke(null, pname);
    }
    private int glGenVertexArrays() throws Exception {
        return (int) gl("org.lwjgl.opengl.GL30", "glGenVertexArrays").invoke(null);
    }
    private void glBindVertexArray(int array) throws Exception {
        gl("org.lwjgl.opengl.GL30", "glBindVertexArray", int.class).invoke(null, array);
    }
    private int glGenBuffers() throws Exception {
        return (int) gl("org.lwjgl.opengl.GL15", "glGenBuffers").invoke(null);
    }
    private void glBindBuffer(int target, int buffer) throws Exception {
        gl("org.lwjgl.opengl.GL15", "glBindBuffer", int.class, int.class).invoke(null, target, buffer);
    }
    private void glBufferData(int target, FloatBuffer data, int usage) throws Exception {
        gl("org.lwjgl.opengl.GL15", "glBufferData", int.class, FloatBuffer.class, int.class).invoke(null, target, data, usage);
    }
    private void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glVertexAttribPointer", int.class, int.class, int.class, boolean.class, int.class, long.class)
            .invoke(null, index, size, type, normalized, stride, pointer);
    }
    private void glEnableVertexAttribArray(int index) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glEnableVertexAttribArray", int.class).invoke(null, index);
    }
    private void glBindAttribLocation(int program, int index, String name) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glBindAttribLocation", int.class, int.class, CharSequence.class).invoke(null, program, index, name);
    }
    private void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUniformMatrix4fv", int.class, boolean.class, FloatBuffer.class).invoke(null, location, transpose, value);
    }
    private void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.ByteBuffer pixels) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glReadPixels", int.class, int.class, int.class, int.class, int.class, int.class, java.nio.ByteBuffer.class)
            .invoke(null, x, y, width, height, format, type, pixels);
    }

    /**
     * DIAGNOSTIC : lit directement le framebuffer actif à la coordonnée
     * (x,y) (origine bas-gauche, même convention que le reste du pipeline
     * moderne) juste après un dessin — permet de trancher définitivement
     * entre "le draw n'écrit rien" (readback ≠ couleur attendue) et "le
     * draw écrit bien mais quelque chose APRÈS notre hook TAIL écrase
     * l'image avant présentation" (readback = couleur attendue MALGRÉ
     * rien de visible à l'écran pour le joueur).
     */
    public int[] debugReadPixel(int x, int y) {
        try {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(4);
            glReadPixels(x, y, 1, 1, 0x1908 /*GL_RGBA*/, 0x1401 /*GL_UNSIGNED_BYTE*/, buf);
            return new int[]{buf.get(0) & 0xFF, buf.get(1) & 0xFF, buf.get(2) & 0xFF, buf.get(3) & 0xFF};
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] debugReadPixel: " + t);
            return null;
        }
    }
}
