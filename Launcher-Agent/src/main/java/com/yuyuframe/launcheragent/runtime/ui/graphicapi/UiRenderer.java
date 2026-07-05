package com.yuyuframe.launcheragent.runtime.ui.graphicapi;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
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

        boolean attribPushed = false, projPushed = false, modelPushed = false;
        try {
            // Legacy (1.8.9, Compatibility Profile) — pushAttrib n'y a jamais
            // crashé (voir pushAttrib()), contrairement au pipeline moderne.
            // Sans lui, nos glDisable(...) restent appliqués en permanence
            // après ce dessin, cassant le rendu vanilla suivant (régression
            // confirmée : monde/HUD tout blanc + gros lag).
            pushAttrib(0x00004000 | 0x00000001 | 0x00040000); // GL_COLOR_BUFFER_BIT | GL_CURRENT_BIT | GL_TEXTURE_BIT
            attribPushed = true;
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
            try {
                if (attribPushed) popAttrib();
            } catch (Throwable ignored) {}
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
    public void drawRoundedRect(float x1, float y1, float x2, float y2, float radius, UiColor color,
                                 int vpWidth, int vpHeight) {
        if (modern) {
            drawRoundedRectModern(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
            return;
        }
        drawRoundedRectLegacy(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
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
        drawFx(x1 - spread, y1 - spread, x2 + spread, y2 + spread, radius + spread, blur, 0f,
            color, color, false, vpWidth, vpHeight);
    }

    /** Contour creux (anneau) d'épaisseur {@code borderWidth}, coins arrondis — le rect lui-même reste transparent (à dessiner par-dessus un fond déjà posé avec {@link #drawRoundedRect}, pas à sa place). */
    public void drawRoundedRectBorder(float x1, float y1, float x2, float y2, float radius, float borderWidth,
                                       UiColor color, int vpWidth, int vpHeight) {
        drawFx(x1, y1, x2, y2, radius, 0f, borderWidth, color, color, false, vpWidth, vpHeight);
    }

    /** Dégradé vertical {@code colorBottom} (bord y1) → {@code colorTop} (bord y2), coins arrondis optionnels (radius=0 = rect plein). */
    public void drawGradientRect(float x1, float y1, float x2, float y2, float radius,
                                  UiColor colorBottom, UiColor colorTop, int vpWidth, int vpHeight) {
        drawFx(x1, y1, x2, y2, radius, 0f, 0f, colorBottom, colorTop, true, vpWidth, vpHeight);
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
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
            glDisable(0x0C11); // GL_SCISSOR_TEST
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
        boolean attribPushed = false, projPushed = false, modelPushed = false;
        try {
            // Legacy (1.8.9) — voir pushAttrib()/drawEdgeVignetteLegacy pour le
            // pourquoi (rétabli, jamais crashé sur cette version).
            pushAttrib(0x00004000 | 0x00000001 | 0x00040000); // GL_ENABLE_BIT | GL_CURRENT_BIT | GL_TEXTURE_BIT
            attribPushed = true;
            glDisable(0x0DE1); // GL_TEXTURE_2D
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE — sinon un quad mal orienté (winding) par rapport à ce que
                                // le rendu 3D du monde a laissé actif peut être silencieusement éliminé,
                                // sans erreur : dessin "réussi" en apparence, rien de visible en jeu.
            glDisable(0x0BC0); // GL_ALPHA_TEST — voir drawEdgeVignette pour le pourquoi
            glDisable(0x0C11); // GL_SCISSOR_TEST — voir drawEdgeVignette pour le pourquoi
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
            try {
                if (attribPushed) popAttrib();
            } catch (Throwable ignored) {}
        }
    }

    private void drawFxModern(float x1, float y1, float x2, float y2, float radius, float blur, float borderWidth,
                               UiColor colorA, UiColor colorB, boolean gradient, int vpWidth, int vpHeight) {
        ensureFxShaderInitModern();
        if (fxInitFailedModern) return;
        try {
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
            glDisable(0x0C11); // GL_SCISSOR_TEST
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

        boolean attribPushed = false, projPushed = false, modelPushed = false;
        try {
            // Même garde que drawRoundedRectLegacy — voir son commentaire pour
            // le détail de chaque état désactivé/pourquoi.
            pushAttrib(0x00004000 | 0x00000001 | 0x00040000); // GL_ENABLE_BIT | GL_CURRENT_BIT | GL_TEXTURE_BIT
            attribPushed = true;
            glDisable(0x0DE1); // GL_TEXTURE_2D
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
            glDisable(0x0BC0); // GL_ALPHA_TEST
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
            try {
                if (attribPushed) popAttrib();
            } catch (Throwable ignored) {}
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
        }
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
        // GameRenderer.render()) ? Si non-zéro, nos dessins partent vers une
        // cible hors-écran (FBO) au lieu de la fenêtre réellement affichée —
        // hypothèse plausible pour "rien de visible malgré des draws sans
        // erreur". 0x8CA6 = GL_FRAMEBUFFER_BINDING.
        if (!fboDiagLogged) {
            fboDiagLogged = true;
            try {
                int fb = glGetInteger(0x8CA6);
                LauncherLog.info("[UiRenderer] DIAG5: framebuffer actif au moment du dessin = " + fb
                    + " (0 = framebuffer par défaut/fenêtre — non-zéro = FBO hors-écran)");
            } catch (Throwable t) {
                LauncherLog.err("[UiRenderer] DIAG5: échec lecture framebuffer actif: " + t);
            }
        }
        // Sécurité : force le framebuffer par défaut, au cas où quelque chose
        // (Sodium, un post-process) en aurait laissé un autre actif à ce point.
        glBindFramebuffer(0x8D40, 0); // GL_FRAMEBUFFER, 0 = fenêtre

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

    public void drawVanillaItemIcon(Object itemStack, float x, float y, float size, int vpWidth, int vpHeight) {
        if (itemStack == null) return;
        if (modern) {
            // Pas encore réécrit pour le pipeline moderne (voir javadoc de la
            // classe) : cette méthode pose SA PROPRE pile de matrices legacy
            // (glMatrixMode/glOrtho/glTranslatef/glScalef, confirmées cassées
            // en 1.21.11) pour établir la convention de coordonnées GUI
            // vanilla attendue par renderInGuiWithOverrides — jamais exercée
            // par aucun test de crash de cette session (seul
            // ArmorDurabilityModule l'appelle, désactivé par défaut), donc pas
            // de preuve directe qu'il faille la réécrire, mais l'appeler
            // telle quelle risquerait le même crash natif que le reste de ce
            // fichier avant correctif. Skip volontaire plutôt que deviner —
            // à traiter si/quand un module l'utilisant est activé en 1.21+.
            if (!modernItemIconWarned) {
                modernItemIconWarned = true;
                LauncherLog.warn("[UiRenderer] drawVanillaItemIcon: pas encore supporté sur le pipeline moderne (1.21+) — icône non dessinée");
            }
            return;
        }
        boolean attribPushed = false, projPushed = false, modelPushed = false;
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
            // Legacy (1.8.9) — voir pushAttrib()/drawEdgeVignetteLegacy.
            pushAttrib(0x00004000 | 0x00000001 | 0x00040000 | 0x00100000 | 0x00080000); // GL_ENABLE_BIT|GL_CURRENT_BIT|GL_TEXTURE_BIT|GL_TRANSFORM_BIT|GL_LIGHTING_BIT
            attribPushed = true;
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
            glDisable(0x0C11); // GL_SCISSOR_TEST — voir drawEdgeVignette pour le pourquoi
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
            try {
                if (attribPushed) popAttrib();
            } catch (Throwable ignored) {}
        }
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

    private void drawTextModern(UiFont font, String text, float x, float y, UiColor color, float scale,
                                 int vpWidth, int vpHeight) {
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
            glDisable(0x0C11); // GL_SCISSOR_TEST
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA
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

        // Legacy (1.8.9) — voir pushAttrib()/drawEdgeVignetteLegacy.
        boolean attribPushed = false, projPushed = false, modelPushed = false;
        try {
            pushAttrib(0x00004000 | 0x00000001 | 0x00040000); // GL_ENABLE_BIT | GL_CURRENT_BIT | GL_TEXTURE_BIT
            attribPushed = true;
            glEnable(0x0DE1);  // GL_TEXTURE_2D
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
            glDisable(0x0BC0); // GL_ALPHA_TEST — voir drawEdgeVignette pour le pourquoi
            glDisable(0x0C11); // GL_SCISSOR_TEST — voir drawEdgeVignette pour le pourquoi
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
            try {
                if (attribPushed) popAttrib();
            } catch (Throwable ignored) {}
        }
    }

    private int ensureFontTexture(UiFont font) {
        Integer cached = fontTextures.get(font);
        if (cached != null) return cached;
        try {
            java.awt.image.BufferedImage img = font.atlasImage();
            int w = img.getWidth(), h = img.getHeight();

            // BufferedImage.getRGB renvoie du ARGB par ligne (row 0 = haut) —
            // reconverti en RGBA 1 octet/composante, ordre attendu par
            // glTexImage2D(GL_RGBA, GL_UNSIGNED_BYTE, ...). Row 0 uploadée en
            // premier = mappée à v=0 : cohérent avec la construction des UV
            // dans UiFont (v0 = haut du glyphe), donc AUCUN flip nécessaire ici.
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
            glBindTexture(0x0DE1, texId); // GL_TEXTURE_2D
            glTexImage2D(0x0DE1, 0, 0x1908, w, h, 0, 0x1908, 0x1401, buf); // GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE
            // L'atlas est rasterisé à BASE_PX puis réduit au dessin (scale
            // ~0.35-0.6 pour du texte courant) — un simple filtre bilinéaire
            // MIN_FILTER (une seule passe, un seul niveau de mip) laissait
            // encore de l'aliasing visible à ce ratio de réduction. Trilinéaire
            // (mipmaps + LINEAR_MIPMAP_LINEAR) échantillonne un niveau
            // pré-réduit adapté au ratio réel, nettement plus net.
            glGenerateMipmap(0x0DE1);
            glTexParameteri(0x0DE1, 0x2801, 0x2703); // GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR
            glTexParameteri(0x0DE1, 0x2800, 0x2601); // GL_TEXTURE_MAG_FILTER, GL_LINEAR
            // CLAMP_TO_EDGE (pas le défaut GL_REPEAT) : un glyphe échantillonné
            // pile à son bord u0/u1 pourrait sinon piocher un texel de l'autre
            // côté de l'atlas (wraparound) au lieu de simplement dupliquer son
            // propre bord — ceinture-bretelles avec la marge de UiFont contre
            // le bleed de mipmap (opacité incohérente entre lettres).
            glTexParameteri(0x0DE1, 0x2802, 0x812F); // GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE
            glTexParameteri(0x0DE1, 0x2803, 0x812F); // GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE
            glBindTexture(0x0DE1, 0);

            fontTextures.put(font, texId);
            LauncherLog.ui(1, "[UiRenderer] atlas police uploadé (" + w + "x" + h + "), texId=" + texId);
            return texId;
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] ensureFontTexture: " + t);
            fontTextures.put(font, -1);
            return -1;
        }
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
    private void glBlendFunc(int sfactor, int dfactor) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glBlendFunc", int.class, int.class).invoke(null, sfactor, dfactor);
    }
    private void glClear(int mask) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glClear", int.class).invoke(null, mask);
    }
    /** GL13, pas GL11 — sélection d'unité de texture (multitexturing), voir drawVanillaItemIcon. */
    private void glActiveTexture(int texture) throws Exception {
        gl("org.lwjgl.opengl.GL13", "glActiveTexture", int.class).invoke(null, texture);
    }
    /**
     * RÉTABLI (voir historique du projet) : glPushAttrib/glPopAttrib avaient
     * été supprimés PARTOUT dans ce fichier suite au crash natif 0xC0000409
     * confirmé sur 1.21.11 (Core Profile — cette fonction n'y est plus
     * implémentée). Mais cette suppression a aussi touché les méthodes
     * *Legacy (1.8.9, Compatibility Profile, où pushAttrib n'a JAMAIS crashé)
     * — sans save/restore, nos glDisable(GL_TEXTURE_2D/GL_ALPHA_TEST/...)
     * restaient appliqués en PERMANENCE après notre dessin (rien ne les
     * réactive avant la frame suivante côté vanilla 1.8.9, qui suppose cet
     * état stable), cassant tout rendu texturé ultérieur (monde blanc,
     * lag lié à la corruption d'état) — régression confirmée en jeu. Donc :
     * gardé RETIRÉ des méthodes *Modern (1.21.11), RÉTABLI dans les méthodes
     * *Legacy uniquement (voir chaque appelant).
     */
    private void pushAttrib(int mask) throws Exception {
        gl("org.lwjgl.opengl.GL11", "glPushAttrib", int.class).invoke(null, mask);
    }
    private void popAttrib() throws Exception {
        gl("org.lwjgl.opengl.GL11", "glPopAttrib").invoke(null);
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
            try {
                Class<?> glStateManager = McReflect.yarnClass("com/mojang/blaze3d/platform/GlStateManager");
                glStateManagerBindTexture = glStateManager != null
                    ? McReflect.method(glStateManager, "com/mojang/blaze3d/platform/GlStateManager", "bindTexture", int.class)
                    : null;
            } catch (Throwable ignored) {}
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
    private void glBindFramebuffer(int target, int framebuffer) throws Exception {
        gl("org.lwjgl.opengl.GL30", "glBindFramebuffer", int.class, int.class).invoke(null, target, framebuffer);
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
