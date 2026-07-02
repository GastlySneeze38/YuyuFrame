package com.yuyuframe.launcheragent.runtime.ui.graphicapi;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

import java.lang.reflect.Method;
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
 * GLSL 120 volontairement (pas 330+) : compatible profil de compatibilité
 * GL2.1 (1.8.9/LWJGL2) ET GL3.2+ compat (1.21+/LWJGL3) sans variante par
 * version — à valider en jeu sur les deux (voir docs/LauncherAgent/index.md).
 */
public final class UiRenderer {

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

    private static UiRenderer instance;

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
        ensureRectShaderInit();
        // radius<=0 : bypass total du shader — bug dégénéré sinon. Dans
        // "alpha = 1 - smoothstep(radius-1, radius, dist)", avec radius=0 tout
        // pixel intérieur a dist=0, qui tombe EXACTEMENT sur le bord haut du
        // smoothstep(-1, 0, 0) → 1.0, donc alpha=0 partout : rect totalement
        // invisible malgré un dessin "réussi" (aucune exception). Observé en
        // test 1.8.9 : le fond plein écran (radius=0) ne s'affichait jamais.
        boolean useShader = rectProgram != -1 && !rectInitFailed && radius > 0f;

        // Chaque pop n'est tenté QUE si son push correspondant a réellement
        // réussi — sinon une exception entre pushAttrib/pushMatrix et son pop
        // (ex: résolution réflexion GL en échec) laisserait un popAttrib/
        // popMatrix orphelin dans le finally, qui dépile une pile déjà vide :
        // GL_STACK_UNDERFLOW ("Stack underflow"), observé en jeu sans lien
        // évident avec le dessin en cours.
        boolean attribPushed = false, projPushed = false, modelPushed = false;
        try {
            // État GL hérité de ce que le jeu a laissé à ce point précis du
            // render loop (texture encore bindée, depth test actif, blend non
            // configuré pour notre alpha...) — glPushAttrib/glPopAttrib
            // (legacy OpenGL, dispo GL2.1+) isole notre dessin sans affecter
            // la frame suivante du jeu.
            pushAttrib(0x00004000 | 0x00000001 | 0x00040000); // GL_ENABLE_BIT | GL_CURRENT_BIT | GL_TEXTURE_BIT
            attribPushed = true;
            glDisable(0x0DE1); // GL_TEXTURE_2D
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE — sinon un quad mal orienté (winding) par rapport à ce que
                                // le rendu 3D du monde a laissé actif peut être silencieusement éliminé,
                                // sans erreur : dessin "réussi" en apparence, rien de visible en jeu.
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
    public void drawVanillaItemIcon(Object itemStack, float x, float y, float size, int vpWidth, int vpHeight) {
        if (itemStack == null) return;
        boolean attribPushed = false, projPushed = false, modelPushed = false;
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object itemRenderer = McReflect.noArgMethod(mc.getClass(), "net/minecraft/client/MinecraftClient", "getItemRenderer").invoke(mc);
            if (itemRenderer == null) return;
            Method render = McReflect.method(itemRenderer.getClass(), "net/minecraft/client/render/item/ItemRenderer",
                "renderInGuiWithOverrides", itemStack.getClass(), int.class, int.class);
            if (render == null) return;

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
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA

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
            render.invoke(itemRenderer, itemStack, (int) vanillaX, (int) vanillaY);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawVanillaItemIcon: " + t);
        } finally {
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
        int texId = ensureFontTexture(font);
        if (texId < 0) return;
        ensureTextShaderInit();
        if (textInitFailed) return; // shader cassé : rien à faire de l'alpha-distance brute, mieux vaut ne rien dessiner

        // Voir drawRoundedRect : chaque pop n'est tenté que si son push a
        // réellement réussi, pour ne jamais dépiler une pile GL déjà vide
        // (GL_STACK_UNDERFLOW) si une exception survient entre les deux.
        boolean attribPushed = false, projPushed = false, modelPushed = false;
        try {
            pushAttrib(0x00004000 | 0x00000001 | 0x00040000); // GL_ENABLE_BIT | GL_CURRENT_BIT | GL_TEXTURE_BIT
            attribPushed = true;
            glEnable(0x0DE1);  // GL_TEXTURE_2D
            glDisable(0x0B71); // GL_DEPTH_TEST
            glDisable(0x0B44); // GL_CULL_FACE
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
    private void glUseProgram(int program) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUseProgram", int.class).invoke(null, program);
    }
    private void glUniform1f(int loc, float v) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUniform1f", int.class, float.class).invoke(null, loc, v);
    }
    private void glUniform1i(int loc, int v) throws Exception {
        gl("org.lwjgl.opengl.GL20", "glUniform1i", int.class, int.class).invoke(null, loc, v);
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
    private void glBindTexture(int target, int texture) throws Exception {
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
}
