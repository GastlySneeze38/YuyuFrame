package com.yuyuframe.launcheragent.apigraphic.era.gl3.pass;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.GlBridge;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.IconTextures;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiGradientType;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.nio.FloatBuffer;

/**
 * Primitives de l'ère gl3 — Core Profile 3.2 (1.17 – 1.21.x) : pas de pile de
 * matrices, VAO/VBO obligatoires, GLSL {@code #version 150}.
 *
 * <p>Six effets, chacun avec SON shader, SON programme et SES uniformes : rect
 * arrondi, vignette, icône, dégradé bilinéaire, dégradé multi-paliers, FX.
 * Tous extraits à la main de {@code render/UiPrimitiveRenderer} le
 * 2026-09-10, un effet à la fois — voir {@code render/package-info.java} pour
 * pourquoi ça ne pouvait pas se scripter.
 *
 * <p>Une seule ère est chargée par process : le registre de backends résout
 * l'ère active une fois, et cette classe n'existe en mémoire que sur 1.17 –
 * 1.21.x. C'est ce qui rend le multiversion gratuit à l'exécution.
 */
public final class Gl3PrimitiveRenderer {

    private final UiRenderer owner;
    private final GlBridge gl;

    public Gl3PrimitiveRenderer(UiRenderer owner, GlBridge gl) {
        this.owner = owner;
        this.gl = gl;
    }

    // ── Rect arrondi ──────────────────────────────────────────────────────

    private static final String FRAGMENT_SRC =
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
     * atteint alpha≈0 quasi partout → rect INVISIBLE partout sauf
     * littéralement sur son contour. Confirmé en jeu (carré de test radius=0
     * invisible).
     */
    private static final String FLAT_FRAGMENT_SRC =
        "#version 150\n" +
        "uniform vec4 uColor;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    fragColor = uColor;\n" +
        "}\n";

    private int flatProgram = -1;
    private int uColorFlat = -1, uProjectionFlat = -1;
    private boolean flatInitFailed = false;

    private int rectProgram = -1;
    private int uRect = -1, uRadius = -1, uColorRect = -1, uProjectionRect = -1;
    private boolean rectInitFailed = false;

    private void ensureRectShaderInit() {
        if (rectProgram != -1 || rectInitFailed) return;
        try {
            rectProgram = owner.compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, FRAGMENT_SRC);
            uRect = gl.glGetUniformLocation(rectProgram, "u_Rect");
            uRadius = gl.glGetUniformLocation(rectProgram, "u_Radius");
            uColorRect = gl.glGetUniformLocation(rectProgram, "uColor");
            uProjectionRect = gl.glGetUniformLocation(rectProgram, "uProjection");
            LauncherLog.ui(1, "[Gl3] shader rect compilé, program=" + rectProgram
                + " uRect=" + uRect + " uRadius=" + uRadius
                + " uColor=" + uColorRect + " uProjection=" + uProjectionRect);
        } catch (Throwable t) {
            rectInitFailed = true;
            LauncherLog.err("[Gl3] échec compilation shader rect — repli sur rects non arrondis : " + t);
        }
    }

    private void ensureFlatShaderInit() {
        if (flatProgram != -1 || flatInitFailed) return;
        try {
            flatProgram = owner.compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, FLAT_FRAGMENT_SRC);
            uColorFlat = gl.glGetUniformLocation(flatProgram, "uColor");
            uProjectionFlat = gl.glGetUniformLocation(flatProgram, "uProjection");
            LauncherLog.ui(1, "[Gl3] shader plat compilé, program=" + flatProgram);
        } catch (Throwable t) {
            flatInitFailed = true;
            LauncherLog.err("[Gl3] échec compilation shader plat : " + t);
        }
    }

    public void roundedRect(float x1, float y1, float x2, float y2, float radius, UiColor color,
                            int vpWidth, int vpHeight) {
        ensureRectShaderInit();
        // radius<=0 : bypass total du shader. Dans
        // "alpha = 1 - smoothstep(radius-1, radius, dist)", avec radius=0 tout
        // pixel intérieur a dist=0, qui tombe EXACTEMENT sur le bord haut du
        // smoothstep(-1, 0, 0) → 1.0, donc alpha=0 partout : rect totalement
        // invisible malgré un dessin "réussi" (aucune exception).
        boolean useShader = rectProgram != -1 && !rectInitFailed && radius > 0f;
        try {
            // GL_TEXTURE_2D/GL_ALPHA_TEST retirés (GL_INVALID_ENUM en Core
            // Profile, concepts fixed-function inexistants ici).
            // PAS de glDisable(GL_SCISSOR_TEST) — BUG TROUVÉ (voir
            // UiScrollContainer) : ce disable, copié-collé du garde-fou
            // légitime de la vignette (effet plein écran), défaisait
            // silencieusement TOUT clip actif — une carte devait entièrement
            // sortir du viewport pour disparaître au lieu d'être clippée.
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            if (useShader) {
                gl.glUseProgram(rectProgram);
                gl.glUniform4f(uRect, x1, y1, x2, y2);
                gl.glUniform1f(uRadius, radius);
                gl.glUniform4f(uColorRect, color.r, color.g, color.b, color.a);
                owner.uploadProjectionModern(uProjectionRect, vpWidth, vpHeight);
            } else {
                // radius<=0 OU échec de compilation : il faut quand même UN
                // programme actif (le Core Profile n'a pas d'équivalent "sans
                // shader" du mode immédiat) — d'où le shader plat.
                ensureFlatShaderInit();
                if (!flatInitFailed) {
                    gl.glUseProgram(flatProgram);
                    gl.glUniform4f(uColorFlat, color.r, color.g, color.b, color.a);
                    owner.uploadProjectionModern(uProjectionFlat, vpWidth, vpHeight);
                } else {
                    return;
                }
            }
            owner.drawQuadModern(x1, y1, x2, y2);
        } catch (Throwable t) {
            LauncherLog.err("[Gl3] roundedRect: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
        }
    }

    // ── Vignette de bord ──────────────────────────────────────────────────

    private static final String VIGNETTE_FRAGMENT_SRC =
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

    private int vignetteProgram = -1;
    private int uViewportSize = -1, uVSize = -1, uColorVignette = -1, uProjectionVignette = -1;
    private boolean vignetteInitFailed = false;

    private void ensureVignetteShaderInit() {
        if (vignetteProgram != -1 || vignetteInitFailed) return;
        try {
            vignetteProgram = owner.compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, VIGNETTE_FRAGMENT_SRC);
            uViewportSize = gl.glGetUniformLocation(vignetteProgram, "u_ViewportSize");
            uVSize = gl.glGetUniformLocation(vignetteProgram, "u_VSize");
            uColorVignette = gl.glGetUniformLocation(vignetteProgram, "uColor");
            uProjectionVignette = gl.glGetUniformLocation(vignetteProgram, "uProjection");
            LauncherLog.ui(1, "[Gl3] shader vignette compilé, program=" + vignetteProgram);
        } catch (Throwable t) {
            vignetteInitFailed = true;
            LauncherLog.err("[Gl3] échec compilation shader vignette : " + t);
        }
    }

    /** {@code true} si le dégradé GPU est utilisable — sinon l'appelant peut se replier sur une approximation par bandes. */
    public boolean vignetteAvailable() {
        ensureVignetteShaderInit();
        return !vignetteInitFailed;
    }

    public void vignette(UiColor edgeColor, float vSize, int vpWidth, int vpHeight) {
        if (vSize <= 0f) return;
        ensureVignetteShaderInit();
        if (vignetteInitFailed) return;
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

            gl.glUseProgram(vignetteProgram);
            gl.glUniform2f(uViewportSize, vpWidth, vpHeight);
            gl.glUniform1f(uVSize, vSize);
            gl.glUniform4f(uColorVignette, edgeColor.r, edgeColor.g, edgeColor.b, edgeColor.a);
            owner.uploadProjectionModern(uProjectionVignette, vpWidth, vpHeight);
            owner.drawQuadModern(0, 0, vpWidth, vpHeight);
        } catch (Throwable t) {
            LauncherLog.err("[Gl3] vignette: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
        }
    }

    // ── Icône RGBA quelconque ─────────────────────────────────────────────

    // Pastille de mod/pack téléchargée — simple passthrough texture (PAS le
    // shader SDF du texte : une icône a ses propres couleurs réelles, rien à
    // seuiller/teinter).
    // u_Alpha : multiplicateur d'opacité (1.0 = comportement d'origine,
    // inchangé) — ajouté pour permettre un fondu d'entrée sur du contenu
    // asynchrone (icônes Modrinth qui arrivent en HTTP, voir UiAsyncFade)
    // sans dupliquer tout le pipeline icône pour un simple multiplicateur.
    private static final String ICON_FRAGMENT_SRC =
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
    private int uTexIcon = -1, uAlphaIcon = -1, uProjectionIcon = -1;
    private boolean iconInitFailed = false;

    /** Cache image → texture GL, propre à cette ère (une seule est chargée par process). */
    private IconTextures icons;

    private void ensureIconShaderInit() {
        if (iconProgram != -1 || iconInitFailed) return;
        try {
            iconProgram = owner.compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, ICON_FRAGMENT_SRC);
            uTexIcon = gl.glGetUniformLocation(iconProgram, "u_Tex");
            uAlphaIcon = gl.glGetUniformLocation(iconProgram, "u_Alpha");
            uProjectionIcon = gl.glGetUniformLocation(iconProgram, "uProjection");
            LauncherLog.ui(1, "[Gl3] shader icône compilé, program=" + iconProgram);
        } catch (Throwable t) {
            iconInitFailed = true;
            LauncherLog.err("[Gl3] échec compilation shader icône — icône non affichée : " + t);
        }
    }

    /**
     * {@code x,y} = coin BAS-GAUCHE (origine bas-gauche écran, comme
     * roundedRect — Y croissant vers le haut). {@code cacheKey} identifie la
     * TEXTURE GPU déjà uploadée, pas l'image elle-même.
     */
    public void icon(String cacheKey, java.awt.image.BufferedImage img, float x, float y, float w, float h,
                     float alpha, int vpWidth, int vpHeight) {
        if (img == null) return;
        if (icons == null) icons = new IconTextures(gl);
        int texId = icons.ensureIconTexture(cacheKey, img);
        if (texId < 0) return;

        ensureIconShaderInit();
        if (iconInitFailed) return;
        try {
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            // PAS de glDisable(GL_SCISSOR_TEST) ici (contrairement à la
            // vignette, effet plein écran qui doit légitimement l'ignorer) —
            // BUG TROUVÉ (utilisateur : "il faut que toute la card passe à
            // travers pour disparaître", voir UiScrollContainer) : ce disable,
            // copié-collé du garde-fou de la vignette, défaisait silencieusement
            // le clip actif de UiScrollContainer pour CHAQUE icône dessinée à
            // l'intérieur — un scissor actif (posé par
            // UiScrollContainer.beginScissor) doit au contraire continuer à
            // s'appliquer ici.
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA
            gl.glActiveTexture(0x84C0); // GL_TEXTURE0
            gl.glBindTexture(0x0DE1, texId);
            gl.glUseProgram(iconProgram);
            gl.glUniform1i(uTexIcon, 0);
            gl.glUniform1f(uAlphaIcon, alpha);
            owner.uploadProjectionModern(uProjectionIcon, vpWidth, vpHeight);

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
            LauncherLog.err("[Gl3] icon: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
        }
    }

    // ── Dégradé bilinéaire 4 coins ────────────────────────────────────────

    // Dégradé BILINÉAIRE entre 4 couleurs de coin. Contrairement au shader FX
    // (dégradé 1D vertical SEULEMENT), celui-ci interpole horizontalement PUIS
    // verticalement entre 4 couleurs indépendantes — usage typique : un vrai
    // carré Saturation/Luminosité de color picker (coin bas-gauche ET
    // bas-droite = noir, haut-gauche = blanc, haut-droite = la teinte pleine à
    // saturation/luminosité maximales) — un dégradé BILINÉAIRE entre ces 4
    // coins précis est mathématiquement IDENTIQUE à la formule HSB->RGB
    // standard à teinte fixe (pas juste une approximation visuelle : à
    // luminosité v et saturation s, HSBtoRGB(h,s,v) == v * lerp(blanc,
    // HSBtoRGB(h,1,1), s), et les deux coins du bas valent 0 dans les deux cas
    // puisque v=0 → noir quelle que soit la saturation).
    //
    // Même masque de coin arrondi (SDF, formule Inigo Quilez) que roundedRect
    // — bord NET anti-aliasé 1px, PAS de flou (contrairement au shader FX,
    // pensé lui pour l'ombre portée/le contour).
    private static final String GRADIENT2D_FRAGMENT_SRC =
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

    private int gradient2DProgram = -1;
    private int uG2dRect = -1, uG2dRadius = -1, uG2dColorBL = -1, uG2dColorBR = -1,
        uG2dColorTL = -1, uG2dColorTR = -1, uProjectionGradient2D = -1;
    private boolean gradient2DInitFailed = false;

    private void ensureGradient2DShaderInit() {
        if (gradient2DProgram != -1 || gradient2DInitFailed) return;
        try {
            gradient2DProgram = owner.compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, GRADIENT2D_FRAGMENT_SRC);
            uG2dRect = gl.glGetUniformLocation(gradient2DProgram, "u_Rect");
            uG2dRadius = gl.glGetUniformLocation(gradient2DProgram, "u_Radius");
            uG2dColorBL = gl.glGetUniformLocation(gradient2DProgram, "u_ColorBL");
            uG2dColorBR = gl.glGetUniformLocation(gradient2DProgram, "u_ColorBR");
            uG2dColorTL = gl.glGetUniformLocation(gradient2DProgram, "u_ColorTL");
            uG2dColorTR = gl.glGetUniformLocation(gradient2DProgram, "u_ColorTR");
            uProjectionGradient2D = gl.glGetUniformLocation(gradient2DProgram, "uProjection");
            LauncherLog.ui(1, "[Gl3] shader Gradient2D compilé, program=" + gradient2DProgram);
        } catch (Throwable t) {
            gradient2DInitFailed = true;
            LauncherLog.err("[Gl3] échec compilation shader Gradient2D : " + t);
        }
    }

    public void gradientRect2D(float x1, float y1, float x2, float y2, float radius,
                               UiColor bl, UiColor br, UiColor tl, UiColor tr, int vpWidth, int vpHeight) {
        ensureGradient2DShaderInit();
        if (gradient2DInitFailed) return;
        try {
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            // PAS de glDisable(GL_SCISSOR_TEST) — voir UiScrollContainer (javadoc de classe).
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            gl.glUseProgram(gradient2DProgram);
            gl.glUniform4f(uG2dRect, x1, y1, x2, y2);
            gl.glUniform1f(uG2dRadius, radius);
            gl.glUniform4f(uG2dColorBL, bl.r, bl.g, bl.b, bl.a);
            gl.glUniform4f(uG2dColorBR, br.r, br.g, br.b, br.a);
            gl.glUniform4f(uG2dColorTL, tl.r, tl.g, tl.b, tl.a);
            gl.glUniform4f(uG2dColorTR, tr.r, tr.g, tr.b, tr.a);
            owner.uploadProjectionModern(uProjectionGradient2D, vpWidth, vpHeight);
            owner.drawQuadModern(x1, y1, x2, y2);
        } catch (Throwable t) {
            LauncherLog.err("[Gl3] gradientRect2D: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
        }
    }

    // ── Dégradé multi-paliers (linéaire / radial / conique) ───────────────

    // u_Start/u_End définissent l'axe — LINEAR : t=0/t=1 ; RADIAL : centre/
    // point qui fixe le rayon (= |end-start|) ; CONIC : centre/direction de
    // l'angle "0".
    //
    // 8 paliers maximum, en uniforms NOMMÉS INDIVIDUELLEMENT (u_Stop0Color..
    // u_Stop7Color / u_Stop0Pos..u_Stop7Pos) plutôt qu'un tableau uniform.
    // Le pendant gl2 en a besoin (indexation dynamique d'un tableau uniform
    // non garantie par GLSL 1.10) ; ici la même forme est gardée pour que les
    // deux shaders restent lisibles côte à côte.
    //
    // Paliers inutilisés : DÉJÀ remplis avec la position/couleur du dernier
    // palier réel par draw/geometry/GradientStops, en amont et une seule fois
    // pour les trois ères — la chaîne if/else résout alors toujours sur la
    // bonne couleur via la clause finale "else", sans qu'aucune branche shader
    // n'ait besoin de connaître le nombre réel de paliers.
    private static final String MULTISTOP_GRADIENT_FRAGMENT_SRC =
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

    private int multiStopGradientProgram = -1;
    private int uMsgRect = -1, uMsgRadius = -1, uMsgGradType = -1, uMsgStart = -1,
        uMsgEnd = -1, uProjectionMultiStopGradient = -1;
    private final int[] uMsgStopColor = new int[8], uMsgStopPos = new int[8];
    private boolean multiStopGradientInitFailed = false;

    private void ensureMultiStopGradientShaderInit() {
        if (multiStopGradientProgram != -1 || multiStopGradientInitFailed) return;
        try {
            multiStopGradientProgram = owner.compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, MULTISTOP_GRADIENT_FRAGMENT_SRC);
            uMsgRect = gl.glGetUniformLocation(multiStopGradientProgram, "u_Rect");
            uMsgRadius = gl.glGetUniformLocation(multiStopGradientProgram, "u_Radius");
            uMsgGradType = gl.glGetUniformLocation(multiStopGradientProgram, "u_GradType");
            uMsgStart = gl.glGetUniformLocation(multiStopGradientProgram, "u_Start");
            uMsgEnd = gl.glGetUniformLocation(multiStopGradientProgram, "u_End");
            for (int i = 0; i < 8; i++) {
                uMsgStopColor[i] = gl.glGetUniformLocation(multiStopGradientProgram, "u_Stop" + i + "Color");
                uMsgStopPos[i] = gl.glGetUniformLocation(multiStopGradientProgram, "u_Stop" + i + "Pos");
            }
            uProjectionMultiStopGradient = gl.glGetUniformLocation(multiStopGradientProgram, "uProjection");
            LauncherLog.ui(1, "[Gl3] shader MultiStopGradient compilé, program=" + multiStopGradientProgram);
        } catch (Throwable t) {
            multiStopGradientInitFailed = true;
            LauncherLog.err("[Gl3] échec compilation shader MultiStopGradient : " + t);
        }
    }

    private static float gradTypeCode(UiGradientType type) {
        switch (type) {
            case RADIAL: return 1f;
            case CONIC: return 2f;
            default: return 0f;
        }
    }

    /** {@code colors}/{@code positions} : DÉJÀ normalisés à huit entrées en amont. */
    public void multiStopGradientRect(float x1, float y1, float x2, float y2, float radius,
                                      UiGradientType type, float startX, float startY, float endX, float endY,
                                      UiColor[] colors, float[] positions, int vpWidth, int vpHeight) {
        ensureMultiStopGradientShaderInit();
        if (multiStopGradientInitFailed) return;
        try {
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

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
            owner.uploadProjectionModern(uProjectionMultiStopGradient, vpWidth, vpHeight);
            owner.drawQuadModern(x1, y1, x2, y2);
        } catch (Throwable t) {
            LauncherLog.err("[Gl3] multiStopGradientRect: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
        }
    }

    // ── "FX" : ombre portée / contour / dégradé vertical ──────────────────

    // Un seul shader pour les 3 effets, tous dérivés de la MÊME distance
    // signée à un rectangle arrondi (formule Inigo Quilez : contrairement au
    // shader rect plus haut, dont le calcul de distance n'est correct QUE près
    // des coins arrondis — les bords droits ont toujours dist=0 —, celle-ci
    // donne une distance signée valide PARTOUT, nécessaire pour un contour
    // d'épaisseur uniforme sur les 4 côtés et un flou d'ombre cohérent) :
    //  - u_BorderWidth > 0  → contour creux (anneau de cette épaisseur)
    //  - u_Blur > 0 (et u_BorderWidth == 0) → bord adouci sur u_Blur pixels
    //    (ombre portée façon CSS box-shadow — u_Rect déjà agrandi du "spread"
    //    par l'appelant, pas géré ici)
    //  - u_Gradient > 0.5 → interpole u_ColorA (bord y1) → u_ColorB (bord y2)
    //    verticalement, au lieu de la couleur plate u_ColorA
    private static final String FX_FRAGMENT_SRC =
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

    private int fxProgram = -1;
    private int uFxRect = -1, uFxRadius = -1, uFxBlur = -1, uFxBorderWidth = -1,
        uFxColorA = -1, uFxColorB = -1, uFxGradient = -1, uProjectionFx = -1;
    private boolean fxInitFailed = false;

    private void ensureFxShaderInit() {
        if (fxProgram != -1 || fxInitFailed) return;
        try {
            fxProgram = owner.compileModernProgram(UiRenderer.VERTEX_SRC_MODERN, FX_FRAGMENT_SRC);
            uFxRect = gl.glGetUniformLocation(fxProgram, "u_Rect");
            uFxRadius = gl.glGetUniformLocation(fxProgram, "u_Radius");
            uFxBlur = gl.glGetUniformLocation(fxProgram, "u_Blur");
            uFxBorderWidth = gl.glGetUniformLocation(fxProgram, "u_BorderWidth");
            uFxColorA = gl.glGetUniformLocation(fxProgram, "u_ColorA");
            uFxColorB = gl.glGetUniformLocation(fxProgram, "u_ColorB");
            uFxGradient = gl.glGetUniformLocation(fxProgram, "u_Gradient");
            uProjectionFx = gl.glGetUniformLocation(fxProgram, "uProjection");
            LauncherLog.ui(1, "[Gl3] shader FX compilé, program=" + fxProgram);
        } catch (Throwable t) {
            fxInitFailed = true;
            LauncherLog.err("[Gl3] échec compilation shader FX (ombre/contour/dégradé) : " + t);
        }
    }

    public void fx(float x1, float y1, float x2, float y2, float radius, float blur, float borderWidth,
                   UiColor colorA, UiColor colorB, boolean gradient, int vpWidth, int vpHeight) {
        ensureFxShaderInit();
        if (fxInitFailed) return;
        try {
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            // PAS de glDisable(GL_SCISSOR_TEST) — voir UiScrollContainer
            // (javadoc de classe) : défaisait le clip actif d'un scroll
            // container pour chaque ombre/bordure/dégradé dessiné à l'intérieur.
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

            gl.glUseProgram(fxProgram);
            gl.glUniform4f(uFxRect, x1, y1, x2, y2);
            gl.glUniform1f(uFxRadius, radius);
            gl.glUniform1f(uFxBlur, blur);
            gl.glUniform1f(uFxBorderWidth, borderWidth);
            gl.glUniform4f(uFxColorA, colorA.r, colorA.g, colorA.b, colorA.a);
            gl.glUniform4f(uFxColorB, colorB.r, colorB.g, colorB.b, colorB.a);
            gl.glUniform1f(uFxGradient, gradient ? 1f : 0f);
            owner.uploadProjectionModern(uProjectionFx, vpWidth, vpHeight);
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
            LauncherLog.err("[Gl3] fx: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
        }
    }
}
