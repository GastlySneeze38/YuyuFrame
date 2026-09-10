package com.yuyuframe.launcheragent.apigraphic.era.gl2;

import com.yuyuframe.launcheragent.apigraphic.era.glsupport.GlBridge;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.IconTextures;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiGradientType;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

/**
 * Primitives de l'ère gl2 — pipeline fixe (≤ 1.12 / 1.16) : pile de matrices,
 * mode immédiat {@code glBegin/glEnd}, GLSL sans directive {@code #version}
 * (donc GLSL 1.10, avec {@code ftransform()}, {@code gl_Color} et
 * {@code gl_FragColor}).
 *
 * <p>Six effets, chacun avec SON shader, SON programme et SES uniformes : rect
 * arrondi, vignette, icône, dégradé bilinéaire, dégradé multi-paliers, FX.
 * Tous extraits à la main de {@code render/UiPrimitiveRenderer} le
 * 2026-09-10, un effet à la fois — voir {@code render/package-info.java} pour
 * pourquoi ça ne pouvait pas se scripter.
 *
 * <p>Cinq de ces six effets partagent {@link #VERTEX_SRC} (ftransform +
 * gl_FrontColor) et {@link #drawQuad} (mode immédiat) : les shaders de cette
 * ère ne diffèrent que par leur fragment. L'icône fait exception — elle
 * réutilise le vertex shader du texte, qui propage en plus les coordonnées de
 * texture.
 *
 * <p>Une seule ère est chargée par process : le registre de backends résout
 * l'ère active une fois, et cette classe n'existe en mémoire que sur les
 * versions ≤ 1.16. C'est ce qui rend le multiversion gratuit à l'exécution.
 */
public final class Gl2PrimitiveRenderer {

    private final GlBridge gl;

    public Gl2PrimitiveRenderer(GlBridge gl) {
        this.gl = gl;
    }

    // ── Rect arrondi ──────────────────────────────────────────────────────

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

            LauncherLog.ui(1, "[Gl2] shader rect compilé, program=" + rectProgram
                + " uRect=" + uRect + " uRadius=" + uRadius);
        } catch (Throwable t) {
            rectInitFailed = true;
            LauncherLog.err("[Gl2] échec compilation shader rect — repli sur rects non arrondis : " + t);
        }
    }

    public void roundedRect(float x1, float y1, float x2, float y2, float radius, UiColor color,
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
            LauncherLog.err("[Gl2] roundedRect: " + t);
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

    // ── Vignette de bord ──────────────────────────────────────────────────

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

            LauncherLog.ui(1, "[Gl2] shader vignette compilé, program=" + vignetteProgram
                + " uViewportSize=" + uViewportSize + " uVSize=" + uVSize);
        } catch (Throwable t) {
            vignetteInitFailed = true;
            LauncherLog.err("[Gl2] échec compilation shader vignette : " + t);
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
            LauncherLog.err("[Gl2] vignette: " + t);
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

    // ── Icône RGBA quelconque ─────────────────────────────────────────────

    // Pastille de mod/pack téléchargée — simple passthrough texture (PAS le
    // shader SDF du texte : une icône a ses propres couleurs réelles, rien à
    // seuiller/teinter).
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

    private int iconProgram = -1;
    private int uTexIcon = -1, uAlphaIcon = -1;
    private boolean iconInitFailed = false;

    /** Cache image → texture GL, propre à cette ère (une seule est chargée par process). */
    private IconTextures icons;

    private void ensureIconShaderInit() {
        if (iconProgram != -1 || iconInitFailed) return;
        try {
            int vsh = gl.glCreateShader(0x8B31); // GL_VERTEX_SHADER
            gl.glShaderSource(vsh, Gl2TextRenderer.TEXT_VERTEX_SRC); // générique (ftransform + texcoord passthrough) — pas besoin d'un vertex shader dédié
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
            LauncherLog.ui(1, "[Gl2] shader icône compilé, program=" + iconProgram);
        } catch (Throwable t) {
            iconInitFailed = true;
            LauncherLog.err("[Gl2] échec compilation shader icône — icône non affichée : " + t);
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
        GlBridge.LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        try {
            savedGlState = gl.captureLegacyGlState();
            gl.glEnable(0x0DE1);  // GL_TEXTURE_2D
            gl.glDisable(0x0B71); // GL_DEPTH_TEST
            gl.glDisable(0x0B44); // GL_CULL_FACE
            gl.glDisable(0x0BC0); // GL_ALPHA_TEST
            // PAS de glDisable(GL_SCISSOR_TEST) — BUG TROUVÉ (utilisateur :
            // "il faut que toute la card passe à travers pour disparaître",
            // voir UiScrollContainer) : ce disable, copié-collé du garde-fou
            // légitime de la vignette (effet plein écran), défaisait
            // silencieusement le clip actif pour CHAQUE icône dessinée à
            // l'intérieur d'un scroll container.
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
            LauncherLog.err("[Gl2] icon: " + t);
        } finally {
            try { gl.glUseProgram(0); } catch (Throwable ignored) {}
            if (modelPushed) { try { gl.matrixMode(0x1700); gl.popMatrix(); } catch (Throwable ignored) {} }
            if (projPushed) { try { gl.matrixMode(0x1701); gl.popMatrix(); } catch (Throwable ignored) {} }
            if (savedGlState != null) gl.restoreLegacyGlState(savedGlState);
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

            LauncherLog.ui(1, "[Gl2] shader Gradient2D compilé, program=" + gradient2DProgram);
        } catch (Throwable t) {
            gradient2DInitFailed = true;
            LauncherLog.err("[Gl2] échec compilation shader Gradient2D : " + t);
        }
    }

    public void gradientRect2D(float x1, float y1, float x2, float y2, float radius,
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
            LauncherLog.err("[Gl2] gradientRect2D: " + t);
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

    // ── Dégradé multi-paliers (linéaire / radial / conique) ───────────────

    // u_Start/u_End définissent l'axe — LINEAR : t=0/t=1 ; RADIAL : centre/
    // point qui fixe le rayon (= |end-start|) ; CONIC : centre/direction de
    // l'angle "0".
    //
    // 8 paliers maximum, en uniforms NOMMÉS INDIVIDUELLEMENT (u_Stop0Color..
    // u_Stop7Color / u_Stop0Pos..u_Stop7Pos) plutôt qu'un tableau uniform —
    // l'indexation DYNAMIQUE d'un tableau uniform dans un fragment shader
    // n'est PAS garantie par GLSL 1.10 (cette ère cible du matériel ancien,
    // 1.8.9) ; beaucoup de compilateurs déroulent une boucle à borne
    // constante et ça fonctionnerait probablement, mais invérifiable sans
    // accès à du matériel d'époque — la chaîne if/else entièrement dépliée
    // ci-dessous est portable par construction, aucune hypothèse à faire.
    //
    // Paliers inutilisés : DÉJÀ remplis avec la position/couleur du dernier
    // palier réel par draw/geometry/GradientStops, en amont et une seule fois
    // pour les trois ères — la chaîne if/else résout alors toujours sur la
    // bonne couleur via la clause finale "else", sans qu'aucune branche shader
    // n'ait besoin de connaître le nombre réel de paliers.
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

            LauncherLog.ui(1, "[Gl2] shader MultiStopGradient compilé, program=" + multiStopGradientProgram);
        } catch (Throwable t) {
            multiStopGradientInitFailed = true;
            LauncherLog.err("[Gl2] échec compilation shader MultiStopGradient : " + t);
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
            LauncherLog.err("[Gl2] multiStopGradientRect: " + t);
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

            LauncherLog.ui(1, "[Gl2] shader FX compilé, program=" + fxProgram);
        } catch (Throwable t) {
            fxInitFailed = true;
            LauncherLog.err("[Gl2] échec compilation shader FX (ombre/contour/dégradé) : " + t);
        }
    }

    public void fx(float x1, float y1, float x2, float y2, float radius, float blur, float borderWidth,
                   UiColor colorA, UiColor colorB, boolean gradient, int vpWidth, int vpHeight) {
        ensureFxShaderInit();
        if (fxInitFailed) return;

        GlBridge.LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        try {
            // Même garde que roundedRect — voir son commentaire pour le détail
            // de chaque état désactivé/pourquoi.
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
            // Quad débordé de "blur" pixels au-delà de u_Rect — sinon aucun
            // pixel n'existe au-delà de (x1,y1)-(x2,y2) pour recevoir la fin
            // du dégradé, qui se retrouve coupé net exactement sur ce bord
            // (confirmé en jeu : "carré" visible autour d'un halo pourtant
            // mathématiquement circulaire).
            float pad = Math.max(blur, 1f);
            drawQuad(x1 - pad, y1 - pad, x2 + pad, y2 + pad, colorA);
        } catch (Throwable t) {
            LauncherLog.err("[Gl2] fx: " + t);
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

    // ── Helper commun au mode immédiat ────────────────────────────────────

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
            LauncherLog.err("[Gl2] drawQuad: " + t);
        }
    }
}
