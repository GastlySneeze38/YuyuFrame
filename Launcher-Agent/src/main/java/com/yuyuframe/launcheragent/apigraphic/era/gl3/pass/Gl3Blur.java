package com.yuyuframe.launcheragent.apigraphic.era.gl3.pass;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;

/**
 * Verre dépoli gl3 — flou dual-Kawase (Marius Bjørge, SIGGRAPH 2015), pendant
 * de {@code Blaze3DBlur}. Mêmes filtres (5 prélèvements à la descente, 8 à la
 * remontée), même nombre de niveaux, même stratégie de coût :
 *
 * <ul>
 *   <li><b>chaîne partagée par frame</b> ({@link #beginFrame}) : le fond est
 *       flouté UNE fois, puis chaque panneau de verre n'est qu'un composite
 *       d'un seul appel de dessin, quel que soit le nombre de panneaux ;</li>
 *   <li><b>recalcul une frame sur {@link #RECOMPUTE_INTERVAL}</b> : un fond
 *       flouté est une image basse fréquence, la recalculer à chaque frame ne
 *       se voit pas ;</li>
 *   <li><b>remontée arrêtée au quart d'écran</b> ({@link #COMPOSITE_LEVEL}) :
 *       la dernière remontée vers le demi-écran était la passe la plus chère
 *       pour un gain invisible une fois rééchantillonnée en bilinéaire.</li>
 * </ul>
 *
 * <h2>Différences de mécanique avec Blaze3D</h2>
 *
 * Pas de file de dessin : gl3 dessine tout de suite, donc la chaîne se calcule
 * au moment de l'appel, à partir d'une copie du fond ({@link Gl3Backdrop}).
 * Les niveaux sont des textures attachées à des framebuffers ; framebuffer,
 * viewport, scissor et stencil de l'appelant sont rendus tels quels
 * ({@link Gl3Core.OffscreenScope}).
 *
 * <p>Correctif par rapport à l'original : avec {@code passes = 1}, Blaze3D
 * composite depuis un niveau jamais écrit. Ici le composite lit le niveau le
 * plus profond RÉELLEMENT calculé.
 */
public final class Gl3Blur {

    private static final int LEVELS = 5;
    private static final int COMPOSITE_LEVEL = 1;
    /** Étages utilisés quand un panneau doit calculer sa propre chaîne. */
    private static final int DEFAULT_PASSES = 4;
    /** Recalcul de la chaîne partagée une frame sur N — même valeur que Blaze3D. */
    public static int RECOMPUTE_INTERVAL = 2;
    /** Au-delà, une chaîne n'est plus « de cette frame » : un panneau la recalcule. */
    private static final long STALE_NANOS = 250_000_000L;

    private static final String FULLSCREEN_VERTEX_SRC =
        "#version 150\n" +
        "in vec2 aPos;\n" +
        "in vec2 aTexCoord;\n" +
        "uniform mat4 uProjection;\n" +
        "out vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = uProjection * vec4(aPos, 0.0, 1.0);\n" +
        "    vTexCoord = aTexCoord;\n" +
        "}\n";

    /** Descente : {@code u_TexelSize} = texel de la texture SOURCE. Filtre de {@code Blaze3DBlur}. */
    private static final String DOWN_FRAGMENT_SRC =
        "#version 150\n" +
        "uniform sampler2D u_Source;\n" +
        "uniform vec2 u_TexelSize;\n" +
        "in vec2 vTexCoord;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec2 halfpixel = u_TexelSize * 0.5;\n" +
        "    vec2 uv = vTexCoord;\n" +
        "    vec4 sum = texture(u_Source, uv) * 4.0;\n" +
        "    sum += texture(u_Source, uv - halfpixel);\n" +
        "    sum += texture(u_Source, uv + halfpixel);\n" +
        "    sum += texture(u_Source, uv + vec2(halfpixel.x, -halfpixel.y));\n" +
        "    sum += texture(u_Source, uv - vec2(halfpixel.x, -halfpixel.y));\n" +
        "    fragColor = sum / 8.0;\n" +
        "}\n";

    /** Remontée, motif « tente » à 8 prélèvements. Filtre de {@code Blaze3DBlur}. */
    private static final String UP_FRAGMENT_SRC =
        "#version 150\n" +
        "uniform sampler2D u_Source;\n" +
        "uniform vec2 u_TexelSize;\n" +
        "in vec2 vTexCoord;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec2 halfpixel = u_TexelSize * 0.5;\n" +
        "    vec2 uv = vTexCoord;\n" +
        "    vec4 sum = texture(u_Source, uv + vec2(-halfpixel.x * 2.0, 0.0));\n" +
        "    sum += texture(u_Source, uv + vec2(-halfpixel.x, halfpixel.y)) * 2.0;\n" +
        "    sum += texture(u_Source, uv + vec2(0.0, halfpixel.y * 2.0));\n" +
        "    sum += texture(u_Source, uv + vec2(halfpixel.x, halfpixel.y)) * 2.0;\n" +
        "    sum += texture(u_Source, uv + vec2(halfpixel.x * 2.0, 0.0));\n" +
        "    sum += texture(u_Source, uv + vec2(halfpixel.x, -halfpixel.y)) * 2.0;\n" +
        "    sum += texture(u_Source, uv + vec2(0.0, -halfpixel.y * 2.0));\n" +
        "    sum += texture(u_Source, uv + vec2(-halfpixel.x, -halfpixel.y)) * 2.0;\n" +
        "    fragColor = sum / 12.0;\n" +
        "}\n";

    private static final String COMPOSITE_VERTEX_SRC =
        "#version 150\n" +
        "in vec2 aPos;\n" +
        "uniform mat4 uProjection;\n" +
        "void main() {\n" +
        "    gl_Position = uProjection * vec4(aPos, 0.0, 1.0);\n" +
        "}\n";

    /** Formule du composite de {@code Blaze3DBlur} : fond flouté teinté, découpé en boîte arrondie. */
    private static final String COMPOSITE_FRAGMENT_SRC =
        "#version 150\n" +
        "uniform sampler2D u_Blurred;\n" +
        "uniform vec4 u_Rect;\n" +
        "uniform vec4 u_Radii;\n" +
        "uniform vec2 u_ScreenSize;\n" +
        "uniform vec4 u_TintAndStrength;\n" +
        "uniform float u_Opacity;\n" +
        "out vec4 fragColor;\n" +
        Gl3Core.GLSL_ROUNDED_BOX +
        "void main() {\n" +
        "    float coverage = roundedBoxCoverage(gl_FragCoord.xy, u_Rect, u_Radii);\n" +
        "    float a = coverage * u_Opacity;\n" +
        "    if (a < 0.01) discard;\n" +
        "    vec3 backdrop = texture(u_Blurred, gl_FragCoord.xy / u_ScreenSize).rgb;\n" +
        "    vec3 tinted = mix(backdrop, u_TintAndStrength.rgb, u_TintAndStrength.a);\n" +
        "    fragColor = vec4(tinted, a);\n" +
        "}\n";

    private final Gl3Backdrop backdrop;
    private final Gl3VertexStream fullscreen = new Gl3VertexStream(2, 2);
    private final Gl3VertexStream compositeQuad = new Gl3VertexStream(2);
    private final Gl3Core.OffscreenScope offscreen = new Gl3Core.OffscreenScope();

    private final int[] levelTex = new int[LEVELS];
    private final int[] levelFbo = new int[LEVELS];
    private final int[] levelW = new int[LEVELS];
    private final int[] levelH = new int[LEVELS];
    private int chainVpW, chainVpH;
    /** Niveau lu par le composite, {@code -1} = aucune chaîne calculée. */
    private int resultLevel = -1;
    private long chainNanos;
    private int frameCounter;

    private int downProgram = -1, upProgram = -1, compositeProgram = -1;
    private int downProj, downSource, downTexel, upProj, upSource, upTexel;
    private int cProj, cBlurred, cRect, cRadii, cScreen, cTint, cOpacity;
    private boolean initFailed;
    private int failureLogs;

    public Gl3Blur(Gl3Backdrop backdrop) {
        this.backdrop = backdrop;
        java.util.Arrays.fill(levelTex, -1);
        java.util.Arrays.fill(levelFbo, -1);
    }

    /** Les programmes du verre compilent-ils sur ce contexte ? */
    public boolean isAvailable() {
        return ensurePrograms();
    }

    /** Chaîne partagée de la frame — voir la javadoc de classe. */
    public boolean beginFrame(int passes, int vpWidth, int vpHeight) {
        if (!ensurePrograms()) return false;
        boolean sizeChanged = vpWidth != chainVpW || vpHeight != chainVpH;
        if (!sizeChanged && resultLevel >= 0 && RECOMPUTE_INTERVAL > 1
                && (++frameCounter % RECOMPUTE_INTERVAL) != 0) {
            chainNanos = System.nanoTime();
            return true;
        }
        return computeChain(passes, vpWidth, vpHeight);
    }

    /**
     * Panneau de verre sur la chaîne partagée. Filet de sécurité de Blaze3D :
     * sans chaîne valide (appelant qui a oublié {@link #beginFrame}, chaîne
     * d'une autre taille ou trop ancienne), le panneau calcule la sienne.
     *
     * <p>Rayons dans l'ordre POSITIONNEL de {@link Gl3Rect} — celui que prend
     * aussi le repli aplat de la façade, pour que verre et repli coïncident.
     */
    public boolean glassPanel(float x1, float y1, float x2, float y2,
                              float r0, float r1, float r2, float r3,
                              UiColor tint, float tintStrength, float opacity, int vpWidth, int vpHeight) {
        if (!ensurePrograms()) return false;
        boolean usable = resultLevel >= 0 && chainVpW == vpWidth && chainVpH == vpHeight
            && System.nanoTime() - chainNanos < STALE_NANOS;
        if (!usable && !computeChain(DEFAULT_PASSES, vpWidth, vpHeight)) return false;
        return composite(x1, y1, x2, y2, r0, r1, r2, r3, tint, tintStrength, opacity, vpWidth, vpHeight);
    }

    /**
     * Panneau flouté AUTONOME : recalcule la chaîne sur le fond actuel (donc
     * y compris l'interface déjà dessinée — le cas d'une modale sur un écran
     * de verre), puis composite.
     */
    public boolean blurredPanel(float x1, float y1, float x2, float y2,
                                float r0, float r1, float r2, float r3,
                                int passes, UiColor tint, float tintStrength, int vpWidth, int vpHeight) {
        if (!ensurePrograms()) return false;
        if (!computeChain(passes, vpWidth, vpHeight)) return false;
        return composite(x1, y1, x2, y2, r0, r1, r2, r3, tint, tintStrength, 1f, vpWidth, vpHeight);
    }

    // ── Chaîne ────────────────────────────────────────────────────────────

    private boolean computeChain(int passes, int vpWidth, int vpHeight) {
        int p = Math.max(1, Math.min(passes, LEVELS));
        offscreen.begin();
        try {
            // Copie du fond AVANT de toucher aux niveaux : leur (re)création lie
            // leurs framebuffers, et la copie lirait alors un niveau vide.
            int source = backdrop.capture(vpWidth, vpHeight);
            ensureLevels(vpWidth, vpHeight);
            int srcW = vpWidth, srcH = vpHeight;
            GL11.glDisable(GL11.GL_BLEND);

            GL20.glUseProgram(downProgram);
            GL20.glUniform1i(downSource, 0);
            for (int i = 0; i < p; i++) {
                renderInto(i, source, srcW, srcH, downProj, downTexel);
                source = levelTex[i];
                srcW = levelW[i];
                srcH = levelH[i];
            }

            int result = p - 1;
            if (p - 1 > COMPOSITE_LEVEL) {
                GL20.glUseProgram(upProgram);
                GL20.glUniform1i(upSource, 0);
                for (int i = p - 1; i > COMPOSITE_LEVEL; i--) {
                    renderInto(i - 1, levelTex[i], levelW[i], levelH[i], upProj, upTexel);
                }
                result = COMPOSITE_LEVEL;
            }
            resultLevel = result;
            chainNanos = System.nanoTime();
            return true;
        } catch (Throwable t) {
            resultLevel = -1;
            if (failureLogs++ < 5) LauncherLog.err("[Gl3Blur] chaîne de flou : " + t);
            return false;
        } finally {
            GL20.glUseProgram(0);
            offscreen.end();
        }
    }

    /** Une passe plein cadre de {@code source} vers le niveau {@code target}. */
    private void renderInto(int target, int source, int srcW, int srcH, int uProj, int uTexel) {
        int w = levelW[target], h = levelH[target];
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, levelFbo[target]);
        GL11.glViewport(0, 0, w, h);
        Gl3Core.ortho(uProj, w, h);
        GL20.glUniform2f(uTexel, 1f / srcW, 1f / srcH);
        Gl3Core.bindTexture0(source);
        FloatBuffer out = fullscreen.begin(6);
        vertex(out, 0, 0, 0, 0);
        vertex(out, w, 0, 1, 0);
        vertex(out, w, h, 1, 1);
        vertex(out, 0, 0, 0, 0);
        vertex(out, w, h, 1, 1);
        vertex(out, 0, h, 0, 1);
        fullscreen.drawTriangles();
    }

    private static void vertex(FloatBuffer out, float x, float y, float u, float v) {
        out.put(x).put(y).put(u).put(v);
    }

    private void ensureLevels(int vpWidth, int vpHeight) {
        if (vpWidth == chainVpW && vpHeight == chainVpH && levelTex[0] != -1) return;
        int w = vpWidth, h = vpHeight;
        for (int i = 0; i < LEVELS; i++) {
            if (levelFbo[i] != -1) GL30.glDeleteFramebuffers(levelFbo[i]);
            if (levelTex[i] != -1) GL11.glDeleteTextures(levelTex[i]);
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            levelW[i] = w;
            levelH[i] = h;
            levelTex[i] = Gl3Core.createTexture(w, h);
            levelFbo[i] = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, levelFbo[i]);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, levelTex[i], 0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("framebuffer de flou " + i + " incomplet (0x"
                    + Integer.toHexString(status) + ")");
            }
        }
        chainVpW = vpWidth;
        chainVpH = vpHeight;
        resultLevel = -1;
    }

    // ── Composite ─────────────────────────────────────────────────────────

    private boolean composite(float x1, float y1, float x2, float y2,
                              float r0, float r1, float r2, float r3,
                              UiColor tint, float tintStrength, float opacity, int vpWidth, int vpHeight) {
        try {
            float minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
            float minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
            Gl3Core.uiState();
            GL20.glUseProgram(compositeProgram);
            Gl3Core.ortho(cProj, vpWidth, vpHeight);
            GL20.glUniform1i(cBlurred, 0);
            GL20.glUniform4f(cRect, minX, minY, maxX, maxY);
            GL20.glUniform4f(cRadii,
                Gl3Core.clampRadius(r0, minX, minY, maxX, maxY), Gl3Core.clampRadius(r1, minX, minY, maxX, maxY),
                Gl3Core.clampRadius(r2, minX, minY, maxX, maxY), Gl3Core.clampRadius(r3, minX, minY, maxX, maxY));
            GL20.glUniform2f(cScreen, vpWidth, vpHeight);
            GL20.glUniform4f(cTint, tint.r, tint.g, tint.b, tintStrength);
            GL20.glUniform1f(cOpacity, opacity);
            Gl3Core.bindTexture0(levelTex[resultLevel]);
            FloatBuffer out = compositeQuad.begin(6);
            Gl3VertexStream.quad(out, minX, minY, maxX, maxY);
            compositeQuad.drawTriangles();
            return true;
        } catch (Throwable t) {
            if (failureLogs++ < 5) LauncherLog.err("[Gl3Blur] composite : " + t);
            return false;
        } finally {
            GL20.glUseProgram(0);
            Gl3Core.bindTexture0(0);
        }
    }

    private boolean ensurePrograms() {
        if (compositeProgram != -1) return true;
        if (initFailed) return false;
        downProgram = Gl3Core.program("blurDown", FULLSCREEN_VERTEX_SRC, DOWN_FRAGMENT_SRC, "aPos", "aTexCoord");
        upProgram = Gl3Core.program("blurUp", FULLSCREEN_VERTEX_SRC, UP_FRAGMENT_SRC, "aPos", "aTexCoord");
        int composite = Gl3Core.program("blurComposite", COMPOSITE_VERTEX_SRC, COMPOSITE_FRAGMENT_SRC, "aPos");
        if (downProgram == -1 || upProgram == -1 || composite == -1) {
            initFailed = true;
            LauncherLog.err("[Gl3Blur] programmes du verre indisponibles — repli aplat");
            return false;
        }
        downProj = GL20.glGetUniformLocation(downProgram, "uProjection");
        downSource = GL20.glGetUniformLocation(downProgram, "u_Source");
        downTexel = GL20.glGetUniformLocation(downProgram, "u_TexelSize");
        upProj = GL20.glGetUniformLocation(upProgram, "uProjection");
        upSource = GL20.glGetUniformLocation(upProgram, "u_Source");
        upTexel = GL20.glGetUniformLocation(upProgram, "u_TexelSize");
        cProj = GL20.glGetUniformLocation(composite, "uProjection");
        cBlurred = GL20.glGetUniformLocation(composite, "u_Blurred");
        cRect = GL20.glGetUniformLocation(composite, "u_Rect");
        cRadii = GL20.glGetUniformLocation(composite, "u_Radii");
        cScreen = GL20.glGetUniformLocation(composite, "u_ScreenSize");
        cTint = GL20.glGetUniformLocation(composite, "u_TintAndStrength");
        cOpacity = GL20.glGetUniformLocation(composite, "u_Opacity");
        compositeProgram = composite;
        return true;
    }
}
