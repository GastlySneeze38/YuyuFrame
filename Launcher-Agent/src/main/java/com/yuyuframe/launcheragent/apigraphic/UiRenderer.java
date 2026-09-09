package com.yuyuframe.launcheragent.apigraphic;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.VanillaGuiTarget;
import com.yuyuframe.launcheragent.apigraphic.value.UiGradientType;
import com.yuyuframe.launcheragent.apigraphic.era.glsupport.GlBridge;
import com.yuyuframe.launcheragent.apigraphic.render.UiPrimitiveRenderer;
import com.yuyuframe.launcheragent.apigraphic.render.UiTextRenderer;
import com.yuyuframe.launcheragent.apigraphic.render.UiVanillaItemRenderer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.version.MinecraftVersionDetector;

import java.nio.FloatBuffer;

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
 *
 * Découpé en 4 classes (voir {@link GlBridge}, {@link UiPrimitiveRenderer},
 * {@link UiVanillaItemRenderer}, {@link UiTextRenderer}) — cette classe reste
 * l'orchestrateur : singleton, pipeline moderne partagé (VAO/VBO, projection,
 * compilation de programme), scissor, et façade déléguant à chaque
 * sous-renderer pour garder l'API publique inchangée (~40 appelants externes).
 */
public final class UiRenderer {

    /** Déterminé une fois à la construction — voir la javadoc de la classe. */
    private final boolean modern;

    /** true si ce renderer utilise le pipeline moderne (1.21.11+), false si legacy (1.8.9) — voir javadoc de la classe. */
    public boolean isModern() { return modern; }

    private final GlBridge glBridge;
    private final UiPrimitiveRenderer primitives;
    private final UiVanillaItemRenderer vanillaItems;
    private final UiTextRenderer text;

    // ══════════════════════════════════════════════════════════════════════
    // ── Pipeline MODERNE (1.21.11+) — voir javadoc de la classe pour le
    // pourquoi. GLSL 150 (in/out, pas de builtins fixed-function), matrice de
    // projection explicite en uniform (pas de glMatrixMode/glOrtho), VAO/VBO +
    // glDrawArrays (pas de glBegin/glVertex2f). Un seul VAO/VBO partagé par
    // les 3 shaders (même layout de vertex : vec2 position + vec2 texCoord =
    // 4 floats/sommet, texCoord ignoré par les shaders rect/vignette).
    // ══════════════════════════════════════════════════════════════════════

    public static final String VERTEX_SRC_MODERN =
        "#version 150\n" +
        "in vec2 aPos;\n" +
        "in vec2 aTexCoord;\n" +
        "uniform mat4 uProjection;\n" +
        "out vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = uProjection * vec4(aPos, 0.0, 1.0);\n" +
        "    vTexCoord = aTexCoord;\n" +
        "}\n";

    private int modernVao = -1, modernVbo = -1;
    private boolean modernBuffersInitFailed = false;

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
        this.glBridge = new GlBridge();
        this.primitives = new UiPrimitiveRenderer(this, glBridge);
        this.vanillaItems = new UiVanillaItemRenderer(this, glBridge);
        this.text = new UiTextRenderer(this, glBridge);
    }

    public static UiRenderer get(ClassLoader gameClassLoader) {
        if (instance == null) instance = new UiRenderer();
        instance.glBridge.gameClassLoader = gameClassLoader;
        return instance;
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
        int status = glBridge.glGetShaderi(shader, 0x8B81); // GL_COMPILE_STATUS
        if (status == 0) {
            String log = glBridge.glGetShaderInfoLog(shader);
            LauncherLog.err("[UiRenderer] ÉCHEC COMPILATION shader " + label + ": " + log);
        }
    }

    private void checkProgramLink(int program, String label) throws Exception {
        int status = glBridge.glGetProgrami(program, 0x8B82); // GL_LINK_STATUS
        if (status == 0) {
            String log = glBridge.glGetProgramInfoLog(program);
            LauncherLog.err("[UiRenderer] ÉCHEC LINK programme " + label + ": " + log);
        } else {
            LauncherLog.ui(1, "[UiRenderer] programme " + label + " lié avec succès (program=" + program + ")");
        }
    }

    public int compileModernProgram(String vertexSrc, String fragmentSrc) throws Exception {
        int vsh = glBridge.glCreateShader(0x8B31); // GL_VERTEX_SHADER
        glBridge.glShaderSource(vsh, vertexSrc);
        glBridge.glCompileShader(vsh);
        checkShaderCompile(vsh, "vertex");

        int fsh = glBridge.glCreateShader(0x8B30); // GL_FRAGMENT_SHADER
        glBridge.glShaderSource(fsh, fragmentSrc);
        glBridge.glCompileShader(fsh);
        checkShaderCompile(fsh, "fragment");

        int program = glBridge.glCreateProgram();
        glBridge.glAttachShader(program, vsh);
        glBridge.glAttachShader(program, fsh);
        glBridge.glBindAttribLocation(program, 0, "aPos");
        glBridge.glBindAttribLocation(program, 1, "aTexCoord");
        glBridge.glLinkProgram(program);
        checkProgramLink(program, "modern(" + vsh + "," + fsh + ")");
        return program;
    }

    /** {@code true} si l'init a échoué (VAO/VBO indisponibles) — voir {@link #ensureModernBuffersInit()}. */
    public boolean modernBuffersInitFailed() { return modernBuffersInitFailed; }

    /** VAO + VBO partagés — layout fixe : vec2 position (loc 0) + vec2 texCoord (loc 1), 4 floats/sommet. */
    public void ensureModernBuffersInit() {
        if (modernVao != -1 || modernBuffersInitFailed) return;
        try {
            LauncherLog.info("[UiRenderer] DIAG3: avant glGenVertexArrays");
            modernVao = glBridge.glGenVertexArrays();
            LauncherLog.info("[UiRenderer] DIAG3: avant glBindVertexArray vao=" + modernVao);
            glBridge.glBindVertexArray(modernVao);
            LauncherLog.info("[UiRenderer] DIAG3: avant glGenBuffers");
            modernVbo = glBridge.glGenBuffers();
            LauncherLog.info("[UiRenderer] DIAG3: avant glBindBuffer vbo=" + modernVbo);
            glBridge.glBindBuffer(0x8892, modernVbo); // GL_ARRAY_BUFFER
            LauncherLog.info("[UiRenderer] DIAG3: avant glEnableVertexAttribArray/glVertexAttribPointer");
            glBridge.glEnableVertexAttribArray(0);
            glBridge.glVertexAttribPointer(0, 2, 0x1406, false, 16, 0L);  // GL_FLOAT, stride=4*4=16, offset=0
            glBridge.glEnableVertexAttribArray(1);
            glBridge.glVertexAttribPointer(1, 2, 0x1406, false, 16, 8L);  // offset=2*4=8 (après x,y)
            glBridge.glBindVertexArray(0);
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
    public FloatBuffer floatBuffer(int capacityFloats) {
        return java.nio.ByteBuffer.allocateDirect(capacityFloats * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
    }

    public void putVertex(FloatBuffer buf, float x, float y, float u, float v) {
        buf.put(x).put(y).put(u).put(v);
    }

    /** Un seul quad plein écran/rect (rect arrondi, vignette) — 4 sommets, GL_TRIANGLE_FAN (même topologie que l'ancien GL_QUADS). */
    public void drawQuadModern(float x1, float y1, float x2, float y2) {
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
    public void drawTrianglesModern(FloatBuffer verts) {
        ensureModernBuffersInit();
        if (modernBuffersInitFailed) return;
        try {
            uploadAndDraw(verts, 4, verts.remaining() / 4); // GL_TRIANGLES
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawTrianglesModern: " + t);
        }
    }

    private static boolean fboDiagLogged = false;

    public void uploadAndDraw(FloatBuffer verts, int glMode, int vertexCount) throws Exception {
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
                int fb = glBridge.glGetInteger(0x8CA6);
                LauncherLog.info("[UiRenderer] DIAG5: framebuffer actif au moment du dessin = " + fb
                    + " (0 = framebuffer par défaut/fenêtre — non-zéro = FBO hors-écran, dessiné dedans quand même)");
            } catch (Throwable t) {
                LauncherLog.err("[UiRenderer] DIAG5: échec lecture framebuffer actif: " + t);
            }
        }

        glBridge.glBindVertexArray(modernVao);
        glBridge.glBindBuffer(0x8892, modernVbo); // GL_ARRAY_BUFFER
        // GL_DYNAMIC_DRAW (0x88E8) : contenu réécrit à chaque draw (HUD redessiné
        // chaque frame), jamais GL_STATIC_DRAW qui suppose un contenu stable.
        glBridge.glBufferData(0x8892, verts, 0x88E8);
        glBridge.glDrawArrays(glMode, 0, vertexCount);
        glBridge.glBindVertexArray(0);
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

    public void uploadProjectionModern(int uniformLoc, int vpWidth, int vpHeight) throws Exception {
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
        glBridge.glUniformMatrix4fv(uniformLoc, false, m);
    }

    // ── Scissor (clipping rectangulaire — utilisé par UiScrollContainer) ─────

    public void beginScissor(int x, int y, int w, int h) {
        try {
            glBridge.glEnable(0x0C11); // GL_SCISSOR_TEST
            glBridge.glScissor(x, y, w, h);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] beginScissor: " + t);
        }
    }

    public void endScissor() {
        try { glBridge.glDisable(0x0C11); } catch (Throwable ignored) {}
    }

    /**
     * Clip aux coins arrondis via stencil buffer (roadmap Phase 5.1) —
     * remplace {@link #beginScissor} quand la zone de clip doit suivre un
     * rect ARRONDI (scissor seul coupe en angle droit même sur un coin
     * visuellement rond). Legacy/modern GL uniquement (no-op sur Blaze3D era
     * E, voir {@link UiPrimitiveRenderer#beginRoundedClip}).
     */
    public void beginRoundedClip(float x1, float y1, float x2, float y2, float radius, int vpWidth, int vpHeight) {
        primitives.beginRoundedClip(x1, y1, x2, y2, radius, vpWidth, vpHeight);
    }

    public void endRoundedClip() {
        primitives.endRoundedClip();
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
            glBridge.glReadPixels(x, y, 1, 1, 0x1908 /*GL_RGBA*/, 0x1401 /*GL_UNSIGNED_BYTE*/, buf);
            return new int[]{buf.get(0) & 0xFF, buf.get(1) & 0xFF, buf.get(2) & 0xFF, buf.get(3) & 0xFF};
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] debugReadPixel: " + t);
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── Façade — délègue à chaque sous-renderer, API publique 100% inchangée
    // (voir GlBridge/UiPrimitiveRenderer/UiVanillaItemRenderer/UiTextRenderer).
    // ══════════════════════════════════════════════════════════════════════

    // ── UiPrimitiveRenderer (rect/vignette/fx/gradient2D/icône) ──────────────

    /**
     * {@code true} si le dégradé GPU est utilisable — sinon l'appelant peut se
     * replier sur une approximation par bandes.
     *
     * <p>Toujours vrai quand la cible vanilla est armée : dans la passe GUI, le
     * repli par bandes n'a pas lieu d'être (il existait pour les GPU où la
     * compilation GLSL du chemin GL brut échouait) et un échec du pipeline se
     * traduit par « rien à l'écran », jamais par du dessin GL brut — voir
     * {@link VanillaGuiTarget#vignette}.
     */
    public boolean isVignetteAvailable() {
        if (VanillaGuiTarget.isArmed()) return true;
        return primitives.isVignetteAvailable();
    }

    public void drawEdgeVignette(UiColor edgeColor, float vSize, int vpWidth, int vpHeight) {
        if (VanillaGuiTarget.vignette(edgeColor, vSize, vpWidth, vpHeight)) return;
        primitives.drawEdgeVignette(edgeColor, vSize, vpWidth, vpHeight);
    }

    public void drawIcon(String cacheKey, java.awt.image.BufferedImage img, float x, float y, float size, int vpWidth, int vpHeight) {
        drawIcon(cacheKey, img, x, y, size, size, 1f, vpWidth, vpHeight);
    }

    public void drawIcon(String cacheKey, java.awt.image.BufferedImage img, float x, float y, float w, float h, int vpWidth, int vpHeight) {
        drawIcon(cacheKey, img, x, y, w, h, 1f, vpWidth, vpHeight);
    }

    /**
     * Routé vers l'état de GUI de vanilla quand la cible est armée (2026-08-31)
     * — c'était la dernière primitive du HUD à ne pas l'être, donc la dernière
     * à passer par-dessus le chat. Voir {@code IconElement}.
     *
     * <p>{@code x}/{@code y} sont le coin BAS-GAUCHE et {@code w}/{@code h} des
     * tailles, contrairement aux coins opposés qu'attend la cible — d'où
     * l'addition ici plutôt que chez elle.
     */
    public void drawIcon(String cacheKey, java.awt.image.BufferedImage img, float x, float y, float w, float h,
                          float alpha, int vpWidth, int vpHeight) {
        if (VanillaGuiTarget.icon(cacheKey, img, x, y, x + w, y + h, alpha, vpWidth, vpHeight)) return;
        primitives.drawIcon(cacheKey, img, x, y, w, h, alpha, vpWidth, vpHeight);
    }

    // ── Cible « état de GUI vanilla » ────────────────────────────────────────
    //
    // Quand elle est armée (pendant la passe GUI de vanilla, voir
    // VanillaGuiTarget), les primitives que le HUD utilise réellement sont
    // émises DANS l'état de GUI de vanilla au lieu de la file Blaze3D. C'est
    // ce qui rend le z-order du HUD choisissable — voir
    // docs/LauncherAgent/rendering-pipeline.md.
    //
    // Un seul commutateur ici plutôt qu'un paramètre chez chaque appelant :
    // les modules HUD écrivent `renderer.drawText(...)`, ils n'ont pas à
    // savoir où ça atterrit.

    public void drawRoundedRect(float x1, float y1, float x2, float y2, float radius, UiColor color,
                                 int vpWidth, int vpHeight) {
        if (VanillaGuiTarget.roundedRect(x1, y1, x2, y2, radius, color, vpWidth, vpHeight)) return;
        primitives.drawRoundedRect(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
    }

    /**
     * Rayon PAR COIN — remplace le hack "2 rects superposés" pour un rayon
     * différent par côté.
     *
     * <p><b>ORDRE RÉEL : bas-gauche, bas-droit, haut-gauche, haut-droit.</b>
     * Les paramètres portaient les noms {@code radiusTopLeft/TopRight/
     * BottomLeft/BottomRight}, qui décrivaient l'INVERSE de leur effet —
     * corrigé ici après l'avoir vérifié dans les DEUX backends : le shader
     * Blaze3D ({@code Blaze3DCore.RECT_FRAGMENT_SRC}) mappe les deux premiers
     * à {@code p.y < 0}, c'est-à-dire SOUS le centre dans ce repère Y-MONTANT
     * (celui de {@code gl_FragCoord}, voir la javadoc de classe) ; et le repli
     * legacy ({@code UiPrimitiveRenderer}) découpe de la même façon en partant
     * de {@code y1}, le bord BAS. Le piège venait de noms calqués sur CSS,
     * dont l'axe Y descend.
     */
    public void drawRoundedRect(float x1, float y1, float x2, float y2,
                                 float radiusBottomLeft, float radiusBottomRight, float radiusTopLeft, float radiusTopRight,
                                 UiColor color, int vpWidth, int vpHeight) {
        if (VanillaGuiTarget.roundedRect(x1, y1, x2, y2,
                radiusBottomLeft, radiusBottomRight, radiusTopLeft, radiusTopRight,
                color, vpWidth, vpHeight)) return;
        primitives.drawRoundedRect(x1, y1, x2, y2, radiusBottomLeft, radiusBottomRight, radiusTopLeft, radiusTopRight, color, vpWidth, vpHeight);
    }

    /** @deprecated identique à {@link #drawRoundedRect} depuis que celui-ci route par Blaze3D sur era E — gardé pour ne pas retoucher HudPanelRenderer/KeystrokesModule. */
    @Deprecated
    public void drawRoundedRectHud(float x1, float y1, float x2, float y2, float radius, UiColor color,
                                    int vpWidth, int vpHeight) {
        if (VanillaGuiTarget.roundedRect(x1, y1, x2, y2, radius, color, vpWidth, vpHeight)) return;
        primitives.drawRoundedRectHud(x1, y1, x2, y2, radius, color, vpWidth, vpHeight);
    }

    public void drawShadow(float x1, float y1, float x2, float y2, float radius, float blur, float spread,
                            UiColor color, int vpWidth, int vpHeight) {
        primitives.drawShadow(x1, y1, x2, y2, radius, blur, spread, color, vpWidth, vpHeight);
    }

    /**
     * Panneau "verre dépoli" (flou dual-Kawase, roadmap Phase 5.1) — fond =
     * backdrop courant flouté + teinté, coins arrondis par coin. Era E
     * (Blaze3D) uniquement pour l'instant — pas d'implémentation legacy/
     * modern GL (no-op silencieux ailleurs, voir {@link
     * com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DBlur}).
     *
     * @param passes       étages downsample/upsample, {@code [1,5]} — plus haut = flou plus fort et plus coûteux.
     * @param tint         couleur mélangée par-dessus le flou.
     * @param tintStrength {@code [0,1]} — 0 = flou pur, 1 = couleur plate.
     */
    public void drawBlurredPanel(float x1, float y1, float x2, float y2,
                                  float radiusTopLeft, float radiusTopRight, float radiusBottomLeft, float radiusBottomRight,
                                  int passes, UiColor tint, float tintStrength, int vpWidth, int vpHeight) {
        com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DBlur.queueBlurredPanel(
            x1, y1, x2, y2, radiusTopLeft, radiusTopRight, radiusBottomLeft, radiusBottomRight,
            passes, tint, tintStrength, vpWidth, vpHeight);
    }

    /**
     * {@code true} si {@link #drawGlassPanel} produira un VRAI panneau de
     * verre (flou du décor derrière) plutôt que son repli opaque — permet à un
     * écran d'adapter le RESTE de sa composition (voile de fond plus léger,
     * liseré de lumière...) au lieu de poser un décor conçu pour du verre sur
     * un bracket qui n'en aura jamais. Era E (Blaze3D) uniquement pour
     * l'instant.
     */
    public boolean isGlassAvailable() {
        return com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DBlur.isGlassAvailable();
    }

    /**
     * Ouvre un frame "verre dépoli" — calcule UNE SEULE FOIS l'arrière-plan
     * flouté que tous les {@link #drawGlassPanel} de ce frame partageront.
     * À appeler AVANT eux (typiquement en toute première ligne du {@code
     * uiDraw} d'un écran), sinon chaque panneau paye sa propre chaîne de flou
     * — voir {@link com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DBlur#queueFrameChain}
     * pour le détail du coût (9 passes plein écran pour TOUT le frame ici, vs
     * 9 PAR PANNEAU sans ça) et la conséquence visuelle assumée (le verre
     * floute le monde du jeu, jamais l'UI dessinée avant lui).
     *
     * No-op silencieux hors era E — {@link #drawGlassPanel} bascule alors sur
     * son repli, rien à changer côté appelant.
     *
     * @param passes étages de flou {@code [1,5]} — 4 = "verre dépoli" franc, 2 = voile léger.
     */
    public void beginGlassFrame(int passes, int vpWidth, int vpHeight) {
        // Voie vanilla : la chaîne est calculée TOUT DE SUITE et non mise en
        // file — sinon elle arriverait après la soumission de la GUI, et les
        // panneaux échantillonneraient le flou de la frame précédente.
        if (VanillaGuiTarget.beginGlassFrame(passes, vpWidth, vpHeight)) return;
        com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DBlur.queueFrameChain(passes, vpWidth, vpHeight);
    }

    /**
     * Surface de verre : le décor derrière est flouté (chaîne partagée du
     * frame, voir {@link #beginGlassFrame}) puis teinté par {@code tint}/{@code
     * tintStrength}, découpé aux coins arrondis PAR COIN.
     *
     * <p>{@code fallback} est la couleur PLEINE utilisée quand le verre n'est
     * pas disponible (legacy/modern GL, où aucun flou n'existe) — paramètre
     * EXPLICITE plutôt qu'une couleur devinée à partir de {@code tint} : un
     * verre à 35% de teinte sur fond flouté et un aplat à 35% d'opacité sur
     * fond net ne se ressemblent pas du tout, et c'est l'écran (pas le moteur)
     * qui sait de quoi son panneau doit avoir l'air quand il ne peut pas être
     * du verre. Passer le token de thème habituel du panneau (ex:
     * {@code UiTheme.PANEL_BG}) y redonne exactement l'apparence d'avant ce
     * rework.
     *
     * <p>L'ALPHA de {@code fallback} pilote aussi l'opacité du VERRE — un seul
     * réglage pour les deux chemins, impossible à désynchroniser : un appelant
     * qui estompe son panneau (carte en bord de zone défilante via {@code
     * clipFade}, apparition en cascade {@code UiStagger}) passe simplement
     * {@code CARD_BG.multiplyAlpha(a)} et obtient le même fondu avec ou sans
     * verre, sans paramètre supplémentaire à penser.
     *
     * @param tintStrength {@code [0,1]} — 0 = flou pur, 1 = couleur plate (verre invisible).
     */
    public void drawGlassPanel(float x1, float y1, float x2, float y2,
                                float radiusTopLeft, float radiusTopRight, float radiusBottomLeft, float radiusBottomRight,
                                UiColor tint, float tintStrength, UiColor fallback, int vpWidth, int vpHeight) {
        // Voie vanilla : un GuiElementRenderState qui échantillonne la chaîne
        // de flou. Retombe sur l'aplat ci-dessous si la chaîne n'a pas pu être
        // calculée pour cette frame.
        if (VanillaGuiTarget.glassPanel(x1, y1, x2, y2,
                radiusBottomLeft, radiusBottomRight, radiusTopLeft, radiusTopRight,
                tint, fallback, vpWidth, vpHeight)) return;
        if (isGlassAvailable() && !VanillaGuiTarget.isArmed()) {
            com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DBlur.queueGlassPanel(
                x1, y1, x2, y2, radiusTopLeft, radiusTopRight, radiusBottomLeft, radiusBottomRight,
                tint, tintStrength, fallback.a, vpWidth, vpHeight);
        } else {
            drawRoundedRect(x1, y1, x2, y2, radiusTopLeft, radiusTopRight, radiusBottomLeft, radiusBottomRight,
                fallback, vpWidth, vpHeight);
        }
    }

    /** Raccourci rayon uniforme — voir {@link #drawGlassPanel(float, float, float, float, float, float, float, float, UiColor, float, UiColor, int, int)}. */
    public void drawGlassPanel(float x1, float y1, float x2, float y2, float radius,
                                UiColor tint, float tintStrength, UiColor fallback, int vpWidth, int vpHeight) {
        drawGlassPanel(x1, y1, x2, y2, radius, radius, radius, radius, tint, tintStrength, fallback, vpWidth, vpHeight);
    }

    /**
     * Panneau de verre AVEC contour — le contour est posé comme un rect PLUS
     * GRAND dessiné DERRIÈRE le panneau, dont seule une bande de {@code
     * borderWidth} dépasse.
     *
     * <p>C'est la SEULE façon d'avoir un contour correct sur era E.
     * {@link #drawRoundedRectBorder} y est volontairement inerte : il repose
     * sur du GL brut exécuté APRÈS la présentation de l'image, alors que tout
     * le reste passe par la file différée Blaze3D exécutée AVANT — il
     * composait donc toujours par-dessus tout, et surtout ses appels GL
     * entrelacés avec les passes Blaze3D corrompaient l'état GPU (bugs de
     * texture constatés). Ici, contour et panneau passent tous deux par la
     * file : ordre garanti, aucun GL brut.
     *
     * <p>Fonctionne parce que le panneau est OPAQUE dans ses bornes (le
     * composite écrit alpha = 1 × couverture) : il masque entièrement le
     * centre du rect de contour. Ne conviendrait donc PAS à un contour sur
     * fond transparent.
     *
     * @param borderColor {@code null} = pas de contour (équivaut à l'appel sans contour).
     */
    public void drawGlassPanel(float x1, float y1, float x2, float y2,
                                float radiusBottomLeft, float radiusBottomRight, float radiusTopLeft, float radiusTopRight,
                                UiColor tint, float tintStrength, UiColor fallback,
                                UiColor borderColor, float borderWidth, int vpWidth, int vpHeight) {
        if (borderColor != null && borderWidth > 0f) {
            // Rayons agrandis d'autant que le rect, pour que la bande visible
            // garde une épaisseur CONSTANTE le long des coins arrondis — un
            // rayon inchangé sur un rect plus grand donnerait un contour plus
            // fin dans les angles. Un coin CARRÉ (rayon 0) le reste : 0 + bw
            // arrondirait un coin voulu net (voir HudPanelRenderer.edgeAwareRadii).
            drawRoundedRect(x1 - borderWidth, y1 - borderWidth, x2 + borderWidth, y2 + borderWidth,
                radiusBottomLeft > 0f ? radiusBottomLeft + borderWidth : 0f,
                radiusBottomRight > 0f ? radiusBottomRight + borderWidth : 0f,
                radiusTopLeft > 0f ? radiusTopLeft + borderWidth : 0f,
                radiusTopRight > 0f ? radiusTopRight + borderWidth : 0f,
                borderColor, vpWidth, vpHeight);
        }
        drawGlassPanel(x1, y1, x2, y2, radiusBottomLeft, radiusBottomRight, radiusTopLeft, radiusTopRight,
            tint, tintStrength, fallback, vpWidth, vpHeight);
    }

    /** Raccourci rayon uniforme — voir la variante à 4 rayons avec contour. */
    public void drawGlassPanel(float x1, float y1, float x2, float y2, float radius,
                                UiColor tint, float tintStrength, UiColor fallback,
                                UiColor borderColor, float borderWidth, int vpWidth, int vpHeight) {
        drawGlassPanel(x1, y1, x2, y2, radius, radius, radius, radius,
            tint, tintStrength, fallback, borderColor, borderWidth, vpWidth, vpHeight);
    }

    public void drawRoundedRectBorder(float x1, float y1, float x2, float y2, float radius, float borderWidth,
                                       UiColor color, int vpWidth, int vpHeight) {
        primitives.drawRoundedRectBorder(x1, y1, x2, y2, radius, borderWidth, color, vpWidth, vpHeight);
    }

    public void drawGradientRect(float x1, float y1, float x2, float y2, float radius,
                                  UiColor colorBottom, UiColor colorTop, int vpWidth, int vpHeight) {
        primitives.drawGradientRect(x1, y1, x2, y2, radius, colorBottom, colorTop, vpWidth, vpHeight);
    }

    public void drawGradientRect2D(float x1, float y1, float x2, float y2, float radius,
                                    UiColor colorBottomLeft, UiColor colorBottomRight,
                                    UiColor colorTopLeft, UiColor colorTopRight, int vpWidth, int vpHeight) {
        primitives.drawGradientRect2D(x1, y1, x2, y2, radius, colorBottomLeft, colorBottomRight, colorTopLeft, colorTopRight, vpWidth, vpHeight);
    }

    /**
     * Dégradé multi-stop (2 à 8 couleurs) linéaire/radial/conique — voir
     * {@link UiGradientType} pour {@code startX/Y}/{@code endX/Y} et
     * {@link UiPrimitiveRenderer#drawMultiStopGradientRect} pour le détail
     * complet (roadmap Phase 5.1).
     */
    public void drawMultiStopGradientRect(float x1, float y1, float x2, float y2, float radius,
                                           UiGradientType type, float startX, float startY, float endX, float endY,
                                           UiColor[] stopColors, float[] stopPositions, int vpWidth, int vpHeight) {
        primitives.drawMultiStopGradientRect(x1, y1, x2, y2, radius, type, startX, startY, endX, endY, stopColors, stopPositions, vpWidth, vpHeight);
    }

    public void drawGlow(float x1, float y1, float x2, float y2, float radius, float intensity, UiColor color,
                          int vpWidth, int vpHeight) {
        primitives.drawGlow(x1, y1, x2, y2, radius, intensity, color, vpWidth, vpHeight);
    }

    public void drawRipple(float centerX, float centerY, float maxRadius, float progress01, float startAlpha,
                            UiColor color, int vpWidth, int vpHeight) {
        primitives.drawRipple(centerX, centerY, maxRadius, progress01, startAlpha, color, vpWidth, vpHeight);
    }

    public void drawSkeletonShimmer(float x1, float y1, float x2, float y2, float radius, float phase01,
                                     UiColor baseColor, UiColor highlightColor, int vpWidth, int vpHeight) {
        primitives.drawSkeletonShimmer(x1, y1, x2, y2, radius, phase01, baseColor, highlightColor, vpWidth, vpHeight);
    }

    public void drawSpinner(float centerX, float centerY, float radius, float dotRadius, float rotationDeg,
                             UiColor color, int vpWidth, int vpHeight) {
        primitives.drawSpinner(centerX, centerY, radius, dotRadius, rotationDeg, color, vpWidth, vpHeight);
    }

    // ── UiVanillaItemRenderer (icône ItemStack vanilla / fond de conteneur) ──

    /** Voir {@link UiVanillaItemRenderer#guiScale}. */
    public static float guiScale(int vpWidth) { return UiVanillaItemRenderer.guiScale(vpWidth); }

    public void drawVanillaItemIcon(Object itemStack, float x, float y, float size, int vpWidth, int vpHeight) {
        vanillaItems.drawVanillaItemIcon(itemStack, x, y, size, vpWidth, vpHeight);
    }

    public void drawVanillaItemIcon(Object itemStack, float x, float y, float size, int vpWidth, int vpHeight, boolean withDurabilityBar) {
        vanillaItems.drawVanillaItemIcon(itemStack, x, y, size, vpWidth, vpHeight, withDurabilityBar);
    }

    public void drawVanillaContainerTexture(String texturePath, float x, float y, float w, float h,
                                             float u, float v, float texW, float texH, int vpWidth, int vpHeight) {
        vanillaItems.drawVanillaContainerTexture(texturePath, x, y, w, h, u, v, texW, texH, vpWidth, vpHeight);
    }

    public void flushPendingImmediateGuiBlits(Object realDrawContext) {
        vanillaItems.flushPendingImmediateGuiBlits(realDrawContext);
    }

    public void flushPendingImmediateItemIcons(Object realDrawContext) {
        vanillaItems.flushPendingImmediateItemIcons(realDrawContext);
    }

    /** Voir {@link UiVanillaItemRenderer#flushPendingModernItemIcons}. */
    public static void flushPendingModernItemIcons(Object gameRenderer) {
        UiVanillaItemRenderer.flushPendingModernItemIcons(gameRenderer);
    }

    /** Voir {@link UiVanillaItemRenderer#flushPendingModernItemIconsFromState}. */
    public static void flushPendingModernItemIconsFromState(Object guiState) {
        UiVanillaItemRenderer.flushPendingModernItemIconsFromState(guiState);
    }

    /** Voir {@link UiVanillaItemRenderer#flushPendingModernItemIconsFromGuiRenderer}. */
    public static void flushPendingModernItemIconsFromGuiRenderer(Object guiRenderer) {
        UiVanillaItemRenderer.flushPendingModernItemIconsFromGuiRenderer(guiRenderer);
    }

    // ── UiTextRenderer (police bitmap UiFont) ─────────────────────────────────

    /**
     * Ouvre un lot de texte : tous les {@code drawText} suivants sont
     * accumulés et rendus en UNE passe par police à la fermeture, au lieu
     * d'une passe chacun.
     *
     * <p>Sur era E, chaque {@code drawText} ouvrait sa propre passe de rendu
     * GPU. Sur un HUD, le texte en produit plus que les fonds (multi-lignes +
     * suffixes d'accent dessinés à part), c'était donc le premier poste de
     * coût du rendu.
     *
     * <p><b>ORDRE Z</b> — tout le texte du lot est empilé au moment du
     * {@link #endTextBatch} : il passe donc AU-DESSUS de tout ce qui a été
     * dessiné entre l'ouverture et la fermeture. C'est ce qu'on veut pour un
     * HUD (le texte doit couvrir les fonds de panneaux) ; à ne PAS utiliser
     * là où du texte doit passer SOUS un élément dessiné après lui.
     *
     * <p>Opt-in délibéré : fusionner automatiquement des dessins consécutifs
     * casserait l'ordre Z ailleurs dans le moteur (même raison que pour les
     * rects batchés). No-op hors era E.
     */
    public void beginTextBatch() {
        // La voie vanilla a SON lot (voir VanillaGuiTarget) : même rôle qu'ici,
        // regrouper tout le texte pour n'ouvrir qu'un maillage au lieu d'un par
        // chaîne.
        if (VanillaGuiTarget.beginTextBatch()) return;
        com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DText.beginBatch();
    }

    /** Ferme le lot ouvert par {@link #beginTextBatch} et empile son rendu (une passe par police). */
    public void endTextBatch(int vpWidth, int vpHeight) {
        if (VanillaGuiTarget.endTextBatch()) return;
        com.yuyuframe.launcheragent.apigraphic.era.blaze3d.Blaze3DText.endBatch(vpWidth, vpHeight);
    }

    public float textWidth(String text, float scale) { return this.text.textWidth(text, scale); }

    public float textWidth(UiFont font, String text, float scale) { return this.text.textWidth(font, text, scale); }

    public String truncate(String text, float scale, float maxWidth) { return this.text.truncate(text, scale, maxWidth); }

    public void drawText(String text, float x, float y, UiColor color, float scale, int vpWidth, int vpHeight) {
        if (VanillaGuiTarget.text(UiFont.REGULAR, text, x, y, color, scale, vpWidth, vpHeight)) return;
        this.text.drawText(text, x, y, color, scale, vpWidth, vpHeight);
    }

    public void drawText(UiFont font, String text, float x, float y, UiColor color, float scale,
                          int vpWidth, int vpHeight) {
        if (VanillaGuiTarget.text(font, text, x, y, color, scale, vpWidth, vpHeight)) return;
        this.text.drawText(font, text, x, y, color, scale, vpWidth, vpHeight);
    }

    public void drawTextShadowed(UiFont font, String text, float x, float y, UiColor color, UiColor shadowColor,
                                  float shadowOffsetX, float shadowOffsetY, float scale, int vpWidth, int vpHeight) {
        this.text.drawTextShadowed(font, text, x, y, color, shadowColor, shadowOffsetX, shadowOffsetY, scale, vpWidth, vpHeight);
    }

    public void drawTextShadowed(String text, float x, float y, UiColor color, UiColor shadowColor,
                                  float scale, int vpWidth, int vpHeight) {
        this.text.drawTextShadowed(text, x, y, color, shadowColor, scale, vpWidth, vpHeight);
    }
}
