package com.yuyuframe.launcheragent.apigraphic.render.blaze3d;

import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiGradientType;
import com.yuyuframe.launcheragent.apigraphic.shader.ShaderPipelineFactory;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

import static com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DCore.*;

import java.nio.ByteBuffer;
import java.util.OptionalInt;

/**
 * Dégradés era E (Blaze3D) — 2-couleurs ({@code drawGradientRect}) et
 * 4-coins bilinéaire ({@code drawGradientRect2D}) via couleur de sommet sur
 * le pipeline "maison" partagé ({@link Blaze3DCore#HOME_VERTEX_SRC}) ; N-stop
 * linéaire/radial/conique ({@code drawMultiStopGradientRect}) via SA PROPRE
 * pipeline dédiée ({@link #GRADIENT_VERTEX_SRC}/{@link #GRADIENT_FRAGMENT_SRC},
 * calcul RÉEL par pixel — voir project_home_shader_pipeline en mémoire projet
 * pour l'historique complet, ancienne approximation par grille CPU supprimée
 * le 2026-08-26). Scindé depuis l'ancien {@code UiTextBlaze3D.java} — voir
 * {@link Blaze3DCore} pour l'infra partagée.
 */
public final class Blaze3DGradient {
    private Blaze3DGradient() {}

    // ── Pipeline dégradé multi-stop (roadmap Phase 5.1, remplace la grille CPU — voir project_home_shader_pipeline) ──

    /**
     * Position uniquement (Color/UV2 du format hérité de GUI_TEXT restent
     * bindés dans le buffer mais volontairement PAS déclarés ici, inutiles —
     * même principe que {@code UiSolidPipelinePoc}) + UV0 (pour le masque de
     * coin arrondi, {@code Sampler0}). {@code fragPos} = position brute en
     * pixels framebuffer, réutilisée telle quelle côté fragment pour calculer
     * {@code t} PAR PIXEL (linéaire/radial/conique) — élimine le besoin
     * d'une grille CPU, contrairement à l'ancienne approche.
     */
    private static final String GRADIENT_VERTEX_SRC =
        "#version 330\n" +
        "layout(std140) uniform DynamicTransforms {\n" +
        "    mat4 ModelViewMat;\n" +
        "    vec4 ColorModulator;\n" +
        "    vec3 ModelOffset;\n" +
        "    mat4 TextureMat;\n" +
        "};\n" +
        "layout(std140) uniform Projection {\n" +
        "    mat4 ProjMat;\n" +
        "};\n" +
        "in vec3 Position;\n" +
        "in vec2 UV0;\n" +
        "out vec2 fragPos;\n" +
        "out vec2 texCoord0;\n" +
        "void main() {\n" +
        "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n" +
        "    fragPos = Position.xy;\n" +
        "    texCoord0 = UV0;\n" +
        "}\n";

    /**
     * {@code GradientParams} — tout en blocs de 4 floats (vec4), DÉLIBÉRÉMENT
     * pas de {@code float[8]} (piège std140 classique : un array de scalaires
     * a un stride de 16 octets par élément, pas 4 — emballer 4 positions par
     * vec4 évite le problème par construction, voir le pack Java côté
     * {@code writeGradientParams}). Boucle réelle sur {@code u_StopColors[i]}
     * (indexation dynamique — GLSL 330 la garantit, contrairement à GLSL 1.10
     * legacy qui a motivé la cascade if/else déroulée là-bas) — même algèbre
     * t que {@code multiStopColorAt}/{@code
     * UiPrimitiveRenderer.MULTISTOP_GRADIENT_FRAGMENT_SRC}, écrite ici une
     * seule fois au lieu d'être dupliquée à la main dans 3 endroits.
     */
    private static final String GRADIENT_FRAGMENT_SRC =
        "#version 330\n" +
        "layout(std140) uniform DynamicTransforms {\n" +
        "    mat4 ModelViewMat;\n" +
        "    vec4 ColorModulator;\n" +
        "    vec3 ModelOffset;\n" +
        "    mat4 TextureMat;\n" +
        "};\n" +
        "layout(std140) uniform GradientParams {\n" +
        "    vec4 u_StartEnd;\n" +
        "    vec4 u_TypeRadius;\n" +
        "    vec4 u_StopPos0123;\n" +
        "    vec4 u_StopPos4567;\n" +
        "    vec4 u_StopColors[8];\n" +
        "};\n" +
        "uniform sampler2D Sampler0;\n" +
        "in vec2 fragPos;\n" +
        "in vec2 texCoord0;\n" +
        "out vec4 fragColor;\n" +
        "float stopPos(int i) {\n" +
        "    if (i < 4) {\n" +
        "        if (i == 0) return u_StopPos0123.x;\n" +
        "        if (i == 1) return u_StopPos0123.y;\n" +
        "        if (i == 2) return u_StopPos0123.z;\n" +
        "        return u_StopPos0123.w;\n" +
        "    }\n" +
        "    if (i == 4) return u_StopPos4567.x;\n" +
        "    if (i == 5) return u_StopPos4567.y;\n" +
        "    if (i == 6) return u_StopPos4567.z;\n" +
        "    return u_StopPos4567.w;\n" +
        "}\n" +
        "void main() {\n" +
        "    vec2 start = u_StartEnd.xy;\n" +
        "    vec2 end = u_StartEnd.zw;\n" +
        "    vec2 d = end - start;\n" +
        "    int gradType = int(u_TypeRadius.x);\n" +
        "    float t;\n" +
        "    if (gradType == 1) {\n" +
        "        float radius = length(d);\n" +
        "        vec2 dp = fragPos - start;\n" +
        "        t = radius < 1e-6 ? 0.0 : length(dp) / radius;\n" +
        "    } else if (gradType == 2) {\n" +
        "        float baseAngle = atan(d.y, d.x);\n" +
        "        float ang = (atan(fragPos.y - start.y, fragPos.x - start.x) - baseAngle) / (2.0 * 3.14159265);\n" +
        "        t = fract(ang);\n" +
        "    } else {\n" +
        "        float len2 = dot(d, d);\n" +
        "        t = len2 < 1e-6 ? 0.0 : dot(fragPos - start, d) / len2;\n" +
        "    }\n" +
        "    t = clamp(t, 0.0, 1.0);\n" +
        "    vec4 col = u_StopColors[0];\n" +
        "    if (t <= stopPos(0)) {\n" +
        "        col = u_StopColors[0];\n" +
        "    } else {\n" +
        "        for (int i = 0; i < 7; i++) {\n" +
        "            float p0 = stopPos(i);\n" +
        "            float p1 = stopPos(i + 1);\n" +
        "            if (t <= p1) {\n" +
        "                float span = p1 - p0;\n" +
        "                float localT = span < 1e-6 ? 0.0 : clamp((t - p0) / span, 0.0, 1.0);\n" +
        "                col = mix(u_StopColors[i], u_StopColors[i + 1], localT);\n" +
        "                break;\n" +
        "            }\n" +
        "            col = u_StopColors[7];\n" +
        "        }\n" +
        "    }\n" +
        "    float maskAlpha = texture(Sampler0, texCoord0).a;\n" +
        "    vec4 color = col * ColorModulator;\n" +
        "    color.a *= maskAlpha;\n" +
        "    if (color.a < 0.1) {\n" +
        "        discard;\n" +
        "    }\n" +
        "    fragColor = color;\n" +
        "}\n";

    private static Object gradientPipeline, gradientShaderSource;
    private static Object gradientParamsBuffer;

    /**
     * Construit le pipeline dégradé — appelé DEPUIS {@code Blaze3DCore.resolve()}
     * (pas l'inverse) : c'est CE fichier qui possède le GLSL/les identifiants,
     * il possède donc aussi le code qui les compile ; {@code Blaze3DCore}
     * se contente d'appeler et de vérifier le booléen retourné. Les exceptions
     * remontent telles quelles jusqu'au {@code catch} unique de {@code
     * resolve()} — même comportement qu'avant le découpage (un échec ici
     * fait échouer TOUT resolve(), pas seulement le dégradé).
     */
    static boolean resolveGradientPipeline() throws Exception {
        Object gradVertexId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d_gradient.vsh");
        Object gradFragmentId = ShaderPipelineFactory.identifier("yuyuframe", "shader/ui_blaze3d_gradient.fsh");
        gradientPipeline = ShaderPipelineFactory.buildPipeline("ui_blaze3d_gradient", gradVertexId, gradFragmentId,
            new String[]{ "Sampler0" }, new String[]{ "DynamicTransforms", "Projection", "GradientParams" });
        gradientShaderSource = ShaderPipelineFactory.shaderSource(gradVertexId, GRADIENT_VERTEX_SRC, gradFragmentId, GRADIENT_FRAGMENT_SRC);
        return gradientPipeline != null;
    }

    // ── Buffer GradientParams (192 octets, valeurs réécrites à CHAQUE draw — contrairement à la projection, le dégradé change à chaque appel) ──

    private static Object ensureGradientParamsBuffer(Object device) throws Exception {
        if (gradientParamsBuffer == null) {
            java.util.function.Supplier<String> label = () -> "yuyuframe_gradient_params";
            gradientParamsBuffer = mCreateBufferSized.invoke(device, label, usageBufferUniform | usageBufferCopyDst, 192L);
        }
        return gradientParamsBuffer;
    }

    /**
     * Pack {@code GradientParams} en std140 — voir la disposition dans
     * {@link #GRADIENT_FRAGMENT_SRC}. {@code colors}/{@code positions} DOIVENT
     * déjà être complétés à 8 entrées (même convention que l'ancien {@code
     * multiStopColorAt}, stops au-delà du nombre réel = copie du dernier
     * stop réel) — voir {@code UiPrimitiveRenderer.drawMultiStopGradientRect},
     * seul appelant en amont.
     */
    private static Object writeGradientParams(Object device, Object encoder, UiGradientType type,
            float startX, float startY, float endX, float endY, UiColor[] colors, float[] positions) throws Exception {
        Object buffer = ensureGradientParamsBuffer(device);
        ByteBuffer data = ByteBuffer.allocateDirect(192).order(java.nio.ByteOrder.nativeOrder());
        data.putFloat(startX).putFloat(startY).putFloat(endX).putFloat(endY);
        float gradTypeCode = type == UiGradientType.RADIAL ? 1f : type == UiGradientType.CONIC ? 2f : 0f;
        data.putFloat(gradTypeCode).putFloat(0f).putFloat(0f).putFloat(0f);
        for (int i = 0; i < 8; i++) data.putFloat(positions[i]);
        for (int i = 0; i < 8; i++) {
            UiColor c = colors[i];
            data.putFloat(c.r).putFloat(c.g).putFloat(c.b).putFloat(c.a);
        }
        data.flip();
        Object slice = mBufferSlice.invoke(buffer, 0L, 192L);
        mWriteToBuffer.invoke(encoder, slice, data);
        return slice;
    }

    /**
     * Comme {@link #queueRect} mais dégradé vertical {@code colorBottom}→{@code colorTop}
     * (voir {@code UiRenderer.drawGradientRect}) — appelé depuis
     * `UiRenderer.drawGradientRect` (fond de sidebar, pastille d'icône de
     * carte mod...) : même bug de z-order que les fonds unis (GL brut TAIL
     * composant par-dessus le texte Blaze3D HEAD, ex. texte de sidebar
     * invisible sous son propre fond dégradé).
     */
    public static void queueGradientRect(float x0, float y0, float x1, float y1, float radius,
                                          UiColor colorBottom, UiColor colorTop, int vpWidth, int vpHeight) {
        if (!isAvailable()) return;
        Blaze3DCore.enqueue(() -> drawGradientRect(x0, y0, x1, y1, radius, colorBottom, colorTop, vpWidth, vpHeight));
    }

    /**
     * Comme {@link #queueGradientRect} mais dégradé BILINÉAIRE à 4 coins
     * indépendants (voir {@code UiRenderer.drawGradientRect2D}) — appelé
     * depuis `UiRenderer.drawGradientRect2D` (ex. bandes Luminosité/Opacité
     * du color picker, voir UiColorPicker). Réutilise EXACTEMENT le même
     * pipeline vertex-color que {@link #queueGradientRect} (aucun nouveau
     * shader) : chaque sommet reçoit sa propre couleur RGBA, déjà interpolée
     * linéairement (voir {@link #lerpRgba2D}) — le rasteriseur GPU fait le
     * reste (interpolation barycentrique standard entre sommets, comme pour
     * n'importe quel vertex-color classique). C'est ce qui manquait à la
     * première tentative de roue Teinte/Saturation (shader GLSL custom
     * jamais routé sur ce pipeline, voir historique UiColorPicker) : ici,
     * AUCUN shader custom n'est nécessaire, seulement des couleurs de
     * sommet — donc ça fonctionne nativement sur Blaze3D era E.
     */
    public static void queueGradientRect2D(float x0, float y0, float x1, float y1, float radius,
                                            UiColor colorBottomLeft, UiColor colorBottomRight,
                                            UiColor colorTopLeft, UiColor colorTopRight, int vpWidth, int vpHeight) {
        if (!isAvailable()) return;
        Blaze3DCore.enqueue(() -> drawGradientRect2D(x0, y0, x1, y1, radius, colorBottomLeft, colorBottomRight, colorTopLeft, colorTopRight, vpWidth, vpHeight));
    }

    /**
     * Dégradé multi-stop (2 à 8 couleurs) linéaire/radial/conique — voir
     * {@code UiRenderer.drawMultiStopGradientRect}/{@link UiGradientType}.
     * Calculé PAR PIXEL dans {@link #GRADIENT_FRAGMENT_SRC} (pipeline shader
     * maison, voir {@code ShaderPipelineFactory}) — plus d'approximation par
     * grille CPU (ancienne limite : ce pipeline n'avait aucun hook shader
     * custom à l'origine, remplacée depuis, voir project_home_shader_pipeline
     * en mémoire projet). {@code colors}/{@code positions} DOIVENT déjà être
     * complétés à 8 entrées (stops au-delà du nombre réel dupliqués depuis le
     * dernier stop réel — même convention que le GLSL legacy/modern) — voir
     * {@code UiPrimitiveRenderer.drawMultiStopGradientRect}, seul appelant.
     */
    public static void queueMultiStopGradientRect(float x0, float y0, float x1, float y1, float radius,
                                                    UiGradientType type, float startX, float startY, float endX, float endY,
                                                    UiColor[] colors, float[] positions, int vpWidth, int vpHeight) {
        if (!isAvailable()) return;
        Blaze3DCore.enqueue(() -> drawMultiStopGradientRect(x0, y0, x1, y1, radius, type, startX, startY, endX, endY, colors, positions, vpWidth, vpHeight));
    }

    private static boolean drawGradientRect(float x0, float y0, float x1, float y1, float radius,
                                             UiColor colorBottom, UiColor colorTop, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve()) return false;
        try {
            currentStage = "minecraftClient(gradrect)";
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            currentStage = "getFramebuffer(gradrect)";
            Object fb = getFramebuffer(mc);
            if (fb == null || mGetColorAttachmentView == null) return false;
            currentStage = "getColorAttachmentView(gradrect)";
            Object colorView = mGetColorAttachmentView.invoke(fb);
            if (colorView == null) return false;

            float r = Math.max(0f, Math.min(radius, Math.min((x1 - x0) / 2f, (y1 - y0) / 2f)));

            currentStage = "ensureCornerMaskTexture(gradrect)";
            Object[] mask = ensureCornerMaskTexture(r);
            Object maskView = mask[1], maskSampler = mask[2];
            currentStage = "ensureWhiteTexture(gradrect)";
            Object[] white = ensureWhiteTexture();

            currentStage = "getDevice(gradrect)";
            Object device = mGetDevice.invoke(null);
            currentStage = "createCommandEncoder(gradrect)";
            Object encoder = mCreateCommandEncoder.invoke(device);
            short light0 = 0, light1 = 0;

            ByteBuffer verts = ensureStagingBuffer(9 * 4 * 28);
            int vertexCount;
            if (r < 0.5f) {
                putSolidQuadGradient(verts, x0, x1, y0, y1, colorBottom, colorTop, y0, y1, light0, light1);
                vertexCount = 4;
            } else {
                putRectQuadGradient(verts, x0, x0 + r, y0, y0 + r, false, false, colorBottom, colorTop, y0, y1, light0, light1);
                putRectQuadGradient(verts, x1 - r, x1, y0, y0 + r, true, false, colorBottom, colorTop, y0, y1, light0, light1);
                putRectQuadGradient(verts, x0, x0 + r, y1 - r, y1, false, true, colorBottom, colorTop, y0, y1, light0, light1);
                putRectQuadGradient(verts, x1 - r, x1, y1 - r, y1, true, true, colorBottom, colorTop, y0, y1, light0, light1);
                putSolidQuadGradient(verts, x0 + r, x1 - r, y0, y0 + r, colorBottom, colorTop, y0, y1, light0, light1);
                putSolidQuadGradient(verts, x0 + r, x1 - r, y1 - r, y1, colorBottom, colorTop, y0, y1, light0, light1);
                putSolidQuadGradient(verts, x0, x0 + r, y0 + r, y1 - r, colorBottom, colorTop, y0, y1, light0, light1);
                putSolidQuadGradient(verts, x1 - r, x1, y0 + r, y1 - r, colorBottom, colorTop, y0, y1, light0, light1);
                putSolidQuadGradient(verts, x0 + r, x1 - r, y0 + r, y1 - r, colorBottom, colorTop, y0, y1, light0, light1);
                vertexCount = 9 * 4;
            }
            verts.flip();

            currentStage = "ensureVertexBuffer(gradrect)";
            Object vbo = ensureVertexBuffer(device, verts.remaining());
            currentStage = "bufferSlice(gradrect)";
            Object slice = mBufferSlice.invoke(vbo, 0L, (long) verts.remaining());
            currentStage = "writeToBuffer(gradrect)";
            mWriteToBuffer.invoke(encoder, slice, verts);

            currentStage = "dynamicUniformsWrite(gradrect)";
            Object identity4 = clsMatrix4f.getConstructor().newInstance();
            Object neutralColor = ctorVector4f.newInstance(1f, 1f, 1f, 1f); // couleur déjà dans les sommets
            Object zero3 = ctorVector3f.newInstance(0f, 0f, 0f);
            Object dynUniforms = mGetDynamicUniforms.invoke(null);
            Object dynSlice = mDynamicUniformsWrite.invoke(dynUniforms, identity4, neutralColor, zero3, identity4);

            currentStage = "ensureProjectionBuffer(gradrect)";
            Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
            Object projectionSlice = mBufferSlice.invoke(projectionBuf, 0L, 64L);

            currentStage = "createRenderPass(gradrect)";
            java.util.function.Supplier<String> passLabel = () -> "yuyuframe_gradrect";
            Object pass = mCreateRenderPass.invoke(encoder, passLabel, colorView, OptionalInt.empty());
            try {
                currentStage = "setPipeline(gradrect)";
                // Vérifié contre UniversalCraft (URenderPipeline.kt) : re-précompiler
                // À CHAQUE draw, pas une seule fois — no-op si déjà en cache, mais
                // nécessaire après un rechargement de ressources (F3+T, resource
                // pack) qui vide le cache de pipelines du device.
                ShaderPipelineFactory.precompile(device, homePipeline, homeShaderSource);
                mSetPipeline.invoke(pass, homePipeline);
                if (mDisableScissor != null) { currentStage = "disableScissor(gradrect)"; mDisableScissor.invoke(pass); }
                currentStage = "bindDefaultUniforms(gradrect)";
                mBindDefaultUniforms.invoke(null, pass);
                currentStage = "setUniform(Projection)(gradrect)";
                mSetUniformSlice.invoke(pass, "Projection", projectionSlice);
                currentStage = "setUniform(DynamicTransforms)(gradrect)";
                mSetUniformSlice.invoke(pass, "DynamicTransforms", dynSlice);
                currentStage = "bindTexture(Sampler0)(gradrect)";
                mBindTexture.invoke(pass, "Sampler0", maskView, maskSampler);
                currentStage = "bindTexture(Sampler2)(gradrect)";
                mBindTexture.invoke(pass, "Sampler2", white[1], white[2]);
                currentStage = "setVertexBuffer(gradrect)";
                mSetVertexBuffer.invoke(pass, 0, vbo);

                currentStage = "shapeIndexBuffer(gradrect)";
                if (sharedSequentialQuad == null) sharedSequentialQuad = fieldSharedSequentialQuad.get(null);
                int indexCount = (vertexCount / 4) * 6;
                Object indexBuffer = mShapeIndexBufferGetBuffer.invoke(sharedSequentialQuad, indexCount);
                Object indexType = mShapeIndexBufferGetType.invoke(sharedSequentialQuad);
                currentStage = "setIndexBuffer(gradrect)";
                mSetIndexBuffer.invoke(pass, indexBuffer, indexType);
                currentStage = "drawIndexed(gradrect)";
                mDrawIndexed.invoke(pass, 0, 0, indexCount, 1);
            } finally {
                currentStage = "closePass(gradrect)";
                mClosePass.invoke(pass);
            }
            return true;
        } catch (Throwable t) {
            if (failureLogCount < 5) {
                failureLogCount++;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                LauncherLog.err("[UiRenderer] UiTextBlaze3D.drawGradientRect a échoué #" + failureLogCount + " à l'étape '" + currentStage + "' : " + t + " | cause réelle : " + cause);
            }
            return false;
        }
    }

    private static boolean drawGradientRect2D(float x0, float y0, float x1, float y1, float radius,
                                               UiColor bl, UiColor br, UiColor tl, UiColor tr, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve()) return false;
        try {
            currentStage = "minecraftClient(gradrect2d)";
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            currentStage = "getFramebuffer(gradrect2d)";
            Object fb = getFramebuffer(mc);
            if (fb == null || mGetColorAttachmentView == null) return false;
            currentStage = "getColorAttachmentView(gradrect2d)";
            Object colorView = mGetColorAttachmentView.invoke(fb);
            if (colorView == null) return false;

            float r = Math.max(0f, Math.min(radius, Math.min((x1 - x0) / 2f, (y1 - y0) / 2f)));

            currentStage = "ensureCornerMaskTexture(gradrect2d)";
            Object[] mask = ensureCornerMaskTexture(r);
            Object maskView = mask[1], maskSampler = mask[2];
            currentStage = "ensureWhiteTexture(gradrect2d)";
            Object[] white = ensureWhiteTexture();

            currentStage = "getDevice(gradrect2d)";
            Object device = mGetDevice.invoke(null);
            currentStage = "createCommandEncoder(gradrect2d)";
            Object encoder = mCreateCommandEncoder.invoke(device);
            short light0 = 0, light1 = 0;

            ByteBuffer verts = ensureStagingBuffer(9 * 4 * 28);
            int vertexCount;
            if (r < 0.5f) {
                putSolidQuadGradient2D(verts, x0, x1, y0, y1, bl, br, tl, tr, x0, x1, y0, y1, light0, light1);
                vertexCount = 4;
            } else {
                putRectQuadGradient2D(verts, x0, x0 + r, y0, y0 + r, false, false, bl, br, tl, tr, x0, x1, y0, y1, light0, light1);
                putRectQuadGradient2D(verts, x1 - r, x1, y0, y0 + r, true, false, bl, br, tl, tr, x0, x1, y0, y1, light0, light1);
                putRectQuadGradient2D(verts, x0, x0 + r, y1 - r, y1, false, true, bl, br, tl, tr, x0, x1, y0, y1, light0, light1);
                putRectQuadGradient2D(verts, x1 - r, x1, y1 - r, y1, true, true, bl, br, tl, tr, x0, x1, y0, y1, light0, light1);
                putSolidQuadGradient2D(verts, x0 + r, x1 - r, y0, y0 + r, bl, br, tl, tr, x0, x1, y0, y1, light0, light1);
                putSolidQuadGradient2D(verts, x0 + r, x1 - r, y1 - r, y1, bl, br, tl, tr, x0, x1, y0, y1, light0, light1);
                putSolidQuadGradient2D(verts, x0, x0 + r, y0 + r, y1 - r, bl, br, tl, tr, x0, x1, y0, y1, light0, light1);
                putSolidQuadGradient2D(verts, x1 - r, x1, y0 + r, y1 - r, bl, br, tl, tr, x0, x1, y0, y1, light0, light1);
                putSolidQuadGradient2D(verts, x0 + r, x1 - r, y0 + r, y1 - r, bl, br, tl, tr, x0, x1, y0, y1, light0, light1);
                vertexCount = 9 * 4;
            }
            verts.flip();

            currentStage = "ensureVertexBuffer(gradrect2d)";
            Object vbo = ensureVertexBuffer(device, verts.remaining());
            currentStage = "bufferSlice(gradrect2d)";
            Object slice = mBufferSlice.invoke(vbo, 0L, (long) verts.remaining());
            currentStage = "writeToBuffer(gradrect2d)";
            mWriteToBuffer.invoke(encoder, slice, verts);

            currentStage = "dynamicUniformsWrite(gradrect2d)";
            Object identity4 = clsMatrix4f.getConstructor().newInstance();
            Object neutralColor = ctorVector4f.newInstance(1f, 1f, 1f, 1f); // couleur déjà dans les sommets
            Object zero3 = ctorVector3f.newInstance(0f, 0f, 0f);
            Object dynUniforms = mGetDynamicUniforms.invoke(null);
            Object dynSlice = mDynamicUniformsWrite.invoke(dynUniforms, identity4, neutralColor, zero3, identity4);

            currentStage = "ensureProjectionBuffer(gradrect2d)";
            Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
            Object projectionSlice = mBufferSlice.invoke(projectionBuf, 0L, 64L);

            currentStage = "createRenderPass(gradrect2d)";
            java.util.function.Supplier<String> passLabel = () -> "yuyuframe_gradrect2d";
            Object pass = mCreateRenderPass.invoke(encoder, passLabel, colorView, OptionalInt.empty());
            try {
                currentStage = "setPipeline(gradrect2d)";
                // Vérifié contre UniversalCraft (URenderPipeline.kt) : re-précompiler
                // À CHAQUE draw, pas une seule fois — no-op si déjà en cache, mais
                // nécessaire après un rechargement de ressources (F3+T, resource
                // pack) qui vide le cache de pipelines du device.
                ShaderPipelineFactory.precompile(device, homePipeline, homeShaderSource);
                mSetPipeline.invoke(pass, homePipeline);
                if (mDisableScissor != null) { currentStage = "disableScissor(gradrect2d)"; mDisableScissor.invoke(pass); }
                currentStage = "bindDefaultUniforms(gradrect2d)";
                mBindDefaultUniforms.invoke(null, pass);
                currentStage = "setUniform(Projection)(gradrect2d)";
                mSetUniformSlice.invoke(pass, "Projection", projectionSlice);
                currentStage = "setUniform(DynamicTransforms)(gradrect2d)";
                mSetUniformSlice.invoke(pass, "DynamicTransforms", dynSlice);
                currentStage = "bindTexture(Sampler0)(gradrect2d)";
                mBindTexture.invoke(pass, "Sampler0", maskView, maskSampler);
                currentStage = "bindTexture(Sampler2)(gradrect2d)";
                mBindTexture.invoke(pass, "Sampler2", white[1], white[2]);
                currentStage = "setVertexBuffer(gradrect2d)";
                mSetVertexBuffer.invoke(pass, 0, vbo);

                currentStage = "shapeIndexBuffer(gradrect2d)";
                if (sharedSequentialQuad == null) sharedSequentialQuad = fieldSharedSequentialQuad.get(null);
                int indexCount = (vertexCount / 4) * 6;
                Object indexBuffer = mShapeIndexBufferGetBuffer.invoke(sharedSequentialQuad, indexCount);
                Object indexType = mShapeIndexBufferGetType.invoke(sharedSequentialQuad);
                currentStage = "setIndexBuffer(gradrect2d)";
                mSetIndexBuffer.invoke(pass, indexBuffer, indexType);
                currentStage = "drawIndexed(gradrect2d)";
                mDrawIndexed.invoke(pass, 0, 0, indexCount, 1);
            } finally {
                currentStage = "closePass(gradrect2d)";
                mClosePass.invoke(pass);
            }
            return true;
        } catch (Throwable t) {
            if (failureLogCount < 5) {
                failureLogCount++;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                LauncherLog.err("[UiRenderer] UiTextBlaze3D.drawGradientRect2D a échoué #" + failureLogCount + " à l'étape '" + currentStage + "' : " + t + " | cause réelle : " + cause);
            }
            return false;
        }
    }

    /**
     * Dégradé multi-stop VRAIMENT calculé par pixel dans {@link
     * #GRADIENT_FRAGMENT_SRC} (roadmap Phase 5.1, remplace l'ancienne
     * approximation par grille CPU — voir project_home_shader_pipeline) —
     * même squelette 9-slice que {@link #drawRect} (géométrie identique,
     * {@code putRectQuad}/{@code putSolidQuad} réutilisés tels quels, couleur
     * de sommet blanche opaque ignorée par ce shader) au lieu d'une
     * subdivision en grille : la couleur ne dépend plus du sommet, juste de
     * {@code fragPos} interpolé par le GPU et lu par le fragment shader.
     */
    private static boolean drawMultiStopGradientRect(float x0, float y0, float x1, float y1, float radius,
                                                       UiGradientType type, float startX, float startY, float endX, float endY,
                                                       UiColor[] colors, float[] positions, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve()) return false;
        try {
            currentStage = "minecraftClient(msgrad)";
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            currentStage = "getFramebuffer(msgrad)";
            Object fb = getFramebuffer(mc);
            if (fb == null || mGetColorAttachmentView == null) return false;
            currentStage = "getColorAttachmentView(msgrad)";
            Object colorView = mGetColorAttachmentView.invoke(fb);
            if (colorView == null) return false;

            float r = Math.max(0f, Math.min(radius, Math.min((x1 - x0) / 2f, (y1 - y0) / 2f)));

            currentStage = "ensureCornerMaskTexture(msgrad)";
            Object[] mask = ensureCornerMaskTexture(r);
            Object maskView = mask[1], maskSampler = mask[2];

            currentStage = "getDevice(msgrad)";
            Object device = mGetDevice.invoke(null);
            currentStage = "createCommandEncoder(msgrad)";
            Object encoder = mCreateCommandEncoder.invoke(device);

            int rgba = 0xFFFFFFFF; // couleur réelle calculée par pixel dans le fragment shader
            short light0 = 0, light1 = 0;

            ByteBuffer verts = ensureStagingBuffer(9 * 4 * 28);
            int vertexCount;
            if (r < 0.5f) {
                putSolidQuad(verts, x0, x1, y0, y1, rgba, light0, light1);
                vertexCount = 4;
            } else {
                putRectQuad(verts, x0, x0 + r, y0, y0 + r, false, false, rgba, light0, light1);
                putRectQuad(verts, x1 - r, x1, y0, y0 + r, true, false, rgba, light0, light1);
                putRectQuad(verts, x0, x0 + r, y1 - r, y1, false, true, rgba, light0, light1);
                putRectQuad(verts, x1 - r, x1, y1 - r, y1, true, true, rgba, light0, light1);
                putSolidQuad(verts, x0 + r, x1 - r, y0, y0 + r, rgba, light0, light1);
                putSolidQuad(verts, x0 + r, x1 - r, y1 - r, y1, rgba, light0, light1);
                putSolidQuad(verts, x0, x0 + r, y0 + r, y1 - r, rgba, light0, light1);
                putSolidQuad(verts, x1 - r, x1, y0 + r, y1 - r, rgba, light0, light1);
                putSolidQuad(verts, x0 + r, x1 - r, y0 + r, y1 - r, rgba, light0, light1);
                vertexCount = 9 * 4;
            }
            verts.flip();

            currentStage = "ensureVertexBuffer(msgrad)";
            Object vbo = ensureVertexBuffer(device, verts.remaining());
            currentStage = "bufferSlice(msgrad)";
            Object slice = mBufferSlice.invoke(vbo, 0L, (long) verts.remaining());
            currentStage = "writeToBuffer(msgrad)";
            mWriteToBuffer.invoke(encoder, slice, verts);

            currentStage = "dynamicUniformsWrite(msgrad)";
            Object identity4 = clsMatrix4f.getConstructor().newInstance();
            Object neutralColor = ctorVector4f.newInstance(1f, 1f, 1f, 1f); // couleur réelle vient de GradientParams, pas de ColorModulator
            Object zero3 = ctorVector3f.newInstance(0f, 0f, 0f);
            Object dynUniforms = mGetDynamicUniforms.invoke(null);
            Object dynSlice = mDynamicUniformsWrite.invoke(dynUniforms, identity4, neutralColor, zero3, identity4);

            currentStage = "ensureProjectionBuffer(msgrad)";
            Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
            Object projectionSlice = mBufferSlice.invoke(projectionBuf, 0L, 64L);

            currentStage = "writeGradientParams(msgrad)";
            Object gradientSlice = writeGradientParams(device, encoder, type, startX, startY, endX, endY, colors, positions);

            currentStage = "createRenderPass(msgrad)";
            java.util.function.Supplier<String> passLabel = () -> "yuyuframe_msgradrect";
            Object pass = mCreateRenderPass.invoke(encoder, passLabel, colorView, OptionalInt.empty());
            try {
                currentStage = "setPipeline(msgrad)";
                // Vérifié contre UniversalCraft (URenderPipeline.kt) : re-précompiler
                // À CHAQUE draw, pas une seule fois — no-op si déjà en cache, mais
                // nécessaire après un rechargement de ressources (F3+T, resource
                // pack) qui vide le cache de pipelines du device.
                ShaderPipelineFactory.precompile(device, gradientPipeline, gradientShaderSource);
                mSetPipeline.invoke(pass, gradientPipeline);
                if (mDisableScissor != null) { currentStage = "disableScissor(msgrad)"; mDisableScissor.invoke(pass); }
                currentStage = "bindDefaultUniforms(msgrad)";
                mBindDefaultUniforms.invoke(null, pass);
                currentStage = "setUniform(Projection)(msgrad)";
                mSetUniformSlice.invoke(pass, "Projection", projectionSlice);
                currentStage = "setUniform(DynamicTransforms)(msgrad)";
                mSetUniformSlice.invoke(pass, "DynamicTransforms", dynSlice);
                currentStage = "setUniform(GradientParams)(msgrad)";
                mSetUniformSlice.invoke(pass, "GradientParams", gradientSlice);
                currentStage = "bindTexture(Sampler0)(msgrad)";
                mBindTexture.invoke(pass, "Sampler0", maskView, maskSampler);
                currentStage = "setVertexBuffer(msgrad)";
                mSetVertexBuffer.invoke(pass, 0, vbo);

                currentStage = "shapeIndexBuffer(msgrad)";
                if (sharedSequentialQuad == null) sharedSequentialQuad = fieldSharedSequentialQuad.get(null);
                int indexCount = (vertexCount / 4) * 6;
                Object indexBuffer = mShapeIndexBufferGetBuffer.invoke(sharedSequentialQuad, indexCount);
                Object indexType = mShapeIndexBufferGetType.invoke(sharedSequentialQuad);
                currentStage = "setIndexBuffer(msgrad)";
                mSetIndexBuffer.invoke(pass, indexBuffer, indexType);
                currentStage = "drawIndexed(msgrad)";
                mDrawIndexed.invoke(pass, 0, 0, indexCount, 1);
            } finally {
                currentStage = "closePass(msgrad)";
                mClosePass.invoke(pass);
            }
            return true;
        } catch (Throwable t) {
            if (failureLogCount < 5) {
                failureLogCount++;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                LauncherLog.err("[UiRenderer] UiTextBlaze3D.drawMultiStopGradientRect a échoué #" + failureLogCount + " à l'étape '" + currentStage + "' : " + t + " | cause réelle : " + cause);
            }
            return false;
        }
    }

    /** Couleur interpolée linéairement entre {@code bottom} (à {@code y0}) et {@code top} (à {@code y1}) pour une position {@code y} donnée — reproduit le dégradé du shader legacy, mais PAR SOMMET (interpolé ensuite par le GPU à travers le triangle). */
    private static int lerpRgba(UiColor bottom, UiColor top, float y, float y0, float y1) {
        float t = (y1 - y0) < 1e-6f ? 0f : Math.max(0f, Math.min(1f, (y - y0) / (y1 - y0)));
        float r = bottom.r + (top.r - bottom.r) * t;
        float g = bottom.g + (top.g - bottom.g) * t;
        float b = bottom.b + (top.b - bottom.b) * t;
        float a = bottom.a + (top.a - bottom.a) * t;
        int ri = Math.round(r * 255f), gi = Math.round(g * 255f), bi = Math.round(b * 255f), ai = Math.round(a * 255f);
        return (ai << 24) | (bi << 16) | (gi << 8) | ri;
    }

    /** Comme {@link #putRectQuad}, mais couleur PAR SOMMET (interpolée entre colorBottom/colorTop selon la position Y de ce sommet dans le rectangle global {@code [rectY0,rectY1]}) au lieu d'un rgba fixe. */
    private static void putRectQuadGradient(ByteBuffer buf, float xLeft, float xRight, float yBottom, float yTop,
                                             boolean flipU, boolean flipV, UiColor colorBottom, UiColor colorTop,
                                             float rectY0, float rectY1, short light0, short light1) {
        float uLeft = flipU ? 1f : 0f, uRight = flipU ? 0f : 1f;
        float vBottom = flipV ? 1f : 0f, vTop = flipV ? 0f : 1f;
        int rgbaTop = lerpRgba(colorBottom, colorTop, yTop, rectY0, rectY1);
        int rgbaBottom = lerpRgba(colorBottom, colorTop, yBottom, rectY0, rectY1);
        putVertexPCTL(buf, xLeft, yTop, rgbaTop, uLeft, vTop, light0, light1);
        putVertexPCTL(buf, xLeft, yBottom, rgbaBottom, uLeft, vBottom, light0, light1);
        putVertexPCTL(buf, xRight, yBottom, rgbaBottom, uRight, vBottom, light0, light1);
        putVertexPCTL(buf, xRight, yTop, rgbaTop, uRight, vTop, light0, light1);
    }

    /** Comme {@link #putSolidQuad}, mais couleur PAR SOMMET (voir {@link #putRectQuadGradient}). */
    private static void putSolidQuadGradient(ByteBuffer buf, float xLeft, float xRight, float yBottom, float yTop,
                                              UiColor colorBottom, UiColor colorTop, float rectY0, float rectY1,
                                              short light0, short light1) {
        float u = 0.95f, v = 0.95f;
        int rgbaTop = lerpRgba(colorBottom, colorTop, yTop, rectY0, rectY1);
        int rgbaBottom = lerpRgba(colorBottom, colorTop, yBottom, rectY0, rectY1);
        putVertexPCTL(buf, xLeft, yTop, rgbaTop, u, v, light0, light1);
        putVertexPCTL(buf, xLeft, yBottom, rgbaBottom, u, v, light0, light1);
        putVertexPCTL(buf, xRight, yBottom, rgbaBottom, u, v, light0, light1);
        putVertexPCTL(buf, xRight, yTop, rgbaTop, u, v, light0, light1);
    }

    /** Couleur bilinéaire (4 coins indépendants) pour une position {@code (x,y)} donnée dans le rectangle global {@code [rectX0,rectX1]×[rectY0,rectY1]} — généralisation de {@link #lerpRgba} à 2 axes (interpolation le long de X pour obtenir les couleurs "basse"/"haute", puis le long de Y entre ces deux résultats, exactement l'algèbre d'un dégradé bilinéaire standard). */
    private static int lerpRgba2D(UiColor bl, UiColor br, UiColor tl, UiColor tr,
                                   float x, float y, float rectX0, float rectX1, float rectY0, float rectY1) {
        float u = (rectX1 - rectX0) < 1e-6f ? 0f : Math.max(0f, Math.min(1f, (x - rectX0) / (rectX1 - rectX0)));
        float v = (rectY1 - rectY0) < 1e-6f ? 0f : Math.max(0f, Math.min(1f, (y - rectY0) / (rectY1 - rectY0)));
        float rBot = bl.r + (br.r - bl.r) * u, rTop = tl.r + (tr.r - tl.r) * u;
        float gBot = bl.g + (br.g - bl.g) * u, gTop = tl.g + (tr.g - tl.g) * u;
        float bBot = bl.b + (br.b - bl.b) * u, bTop = tl.b + (tr.b - tl.b) * u;
        float aBot = bl.a + (br.a - bl.a) * u, aTop = tl.a + (tr.a - tl.a) * u;
        float r = rBot + (rTop - rBot) * v;
        float g = gBot + (gTop - gBot) * v;
        float b = bBot + (bTop - bBot) * v;
        float a = aBot + (aTop - aBot) * v;
        int ri = Math.round(r * 255f), gi = Math.round(g * 255f), bi = Math.round(b * 255f), ai = Math.round(a * 255f);
        return (ai << 24) | (bi << 16) | (gi << 8) | ri;
    }

    /** Comme {@link #putRectQuadGradient}, mais bilinéaire (voir {@link #lerpRgba2D}) — chaque sommet interpole sur SES DEUX coordonnées, pas seulement Y. */
    private static void putRectQuadGradient2D(ByteBuffer buf, float xLeft, float xRight, float yBottom, float yTop,
                                               boolean flipU, boolean flipV, UiColor bl, UiColor br, UiColor tl, UiColor tr,
                                               float rectX0, float rectX1, float rectY0, float rectY1, short light0, short light1) {
        float uLeft = flipU ? 1f : 0f, uRight = flipU ? 0f : 1f;
        float vBottom = flipV ? 1f : 0f, vTop = flipV ? 0f : 1f;
        int cTL = lerpRgba2D(bl, br, tl, tr, xLeft, yTop, rectX0, rectX1, rectY0, rectY1);
        int cBL = lerpRgba2D(bl, br, tl, tr, xLeft, yBottom, rectX0, rectX1, rectY0, rectY1);
        int cBR = lerpRgba2D(bl, br, tl, tr, xRight, yBottom, rectX0, rectX1, rectY0, rectY1);
        int cTR = lerpRgba2D(bl, br, tl, tr, xRight, yTop, rectX0, rectX1, rectY0, rectY1);
        putVertexPCTL(buf, xLeft, yTop, cTL, uLeft, vTop, light0, light1);
        putVertexPCTL(buf, xLeft, yBottom, cBL, uLeft, vBottom, light0, light1);
        putVertexPCTL(buf, xRight, yBottom, cBR, uRight, vBottom, light0, light1);
        putVertexPCTL(buf, xRight, yTop, cTR, uRight, vTop, light0, light1);
    }

    /** Comme {@link #putSolidQuad}, mais bilinéaire (voir {@link #putRectQuadGradient2D}). */
    private static void putSolidQuadGradient2D(ByteBuffer buf, float xLeft, float xRight, float yBottom, float yTop,
                                                UiColor bl, UiColor br, UiColor tl, UiColor tr,
                                                float rectX0, float rectX1, float rectY0, float rectY1, short light0, short light1) {
        float u = 0.95f, v = 0.95f;
        int cTL = lerpRgba2D(bl, br, tl, tr, xLeft, yTop, rectX0, rectX1, rectY0, rectY1);
        int cBL = lerpRgba2D(bl, br, tl, tr, xLeft, yBottom, rectX0, rectX1, rectY0, rectY1);
        int cBR = lerpRgba2D(bl, br, tl, tr, xRight, yBottom, rectX0, rectX1, rectY0, rectY1);
        int cTR = lerpRgba2D(bl, br, tl, tr, xRight, yTop, rectX0, rectX1, rectY0, rectY1);
        putVertexPCTL(buf, xLeft, yTop, cTL, u, v, light0, light1);
        putVertexPCTL(buf, xLeft, yBottom, cBL, u, v, light0, light1);
        putVertexPCTL(buf, xRight, yBottom, cBR, u, v, light0, light1);
        putVertexPCTL(buf, xRight, yTop, cTR, u, v, light0, light1);
    }
}
