package com.yuyuframe.launcheragent.runtime.ui.graphicapi;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Rendu de texte era E (Blaze3D, 1.21.6+) via le VRAI pipeline du moteur
 * (GpuDevice/GpuTexture/RenderPipeline/RenderPass), au lieu de contourner
 * Blaze3D avec des appels GL bruts (glTexImage2D/glBindTexture/glActiveTexture)
 * — voir historique de session : rastérisation AWT/SDF confirmée propre
 * (dump PNG), aucune erreur GL observée à l'upload, glFinish()/
 * reachabilityFence(buf) sans effet, routage GlStateManager (bindTexture/
 * activeTexture) sans effet non plus — la corruption persistait malgré TOUS
 * ces correctifs ciblés. Plutôt que de continuer à deviner quel état GL
 * interne à Blaze3D nous échappe encore, ce fichier utilise la même API que
 * Minecraft utilise LUI-MÊME pour tout son propre texte (vérifié par
 * désassemblage direct du jar client 1.21.11, jamais supposé) :
 * {@code net.minecraft.client.font.GlyphAtlasTexture} crée sa texture via
 * {@code GpuDevice.createTexture(...)}, et {@code RenderPipelines.GUI_TEXT}
 * (vertex format {@code POSITION_COLOR_TEXTURE_LIGHT}, mode QUADS) est LE
 * pipeline utilisé par TOUT texte GUI vanilla.
 *
 * Toutes les classes {@code com.mojang.blaze3d.*} référencées ici sont NON
 * obfusquées (bibliothèque fournie telle quelle par Mojang, jamais remappée
 * par Yarn/Fabric) — résolues via {@link McReflect#rawClass}. Les classes
 * {@code net.minecraft.*} (Framebuffer, RenderPipelines, DynamicUniforms,
 * NativeImage, GpuSampler, SamplerCache, MinecraftClient) restent obfusquées
 * — résolues via {@link MappingsRegistry}/{@link McReflect#yarnClass}, comme
 * partout ailleurs dans ce projet.
 *
 * Compile en pure réflexion (pas d'import direct {@code com.mojang.blaze3d.*})
 * : le classpath de compilation de ce module (voir build.bat) ne contient
 * aucun jar Minecraft (le même .class doit rester chargeable sur TOUS les
 * brackets 1.8.9→1.21.11, où ces classes n'existent simplement pas avant
 * Blaze3D) — cohérent avec la convention 100% réflexion déjà utilisée
 * partout ailleurs dans ce fichier/ce projet pour toute interaction avec le
 * jeu, jamais de compilation directe contre du code non garanti présent.
 */
public final class UiTextBlaze3D {

    private UiTextBlaze3D() {}

    private static Boolean available;

    /** {@code true} seulement si les classes Blaze3D (GpuDevice etc.) existent sur ce bracket — sinon, laisser {@link UiRenderer} retomber sur son pipeline SDF existant. */
    public static boolean isAvailable() {
        if (available == null) {
            available = McReflect.rawClass("com.mojang.blaze3d.systems.GpuDevice") != null;
        }
        return available;
    }

    // ── Classes/méthodes résolues paresseusement, mises en cache ────────────

    private static Class<?> clsRenderSystem, clsGpuDevice, clsCommandEncoder, clsRenderPass,
        clsGpuTexture, clsGpuTextureView, clsTextureFormat, clsGpuBuffer, clsGpuBufferSlice,
        clsFilterMode, clsRenderPipeline, clsSamplerCache, clsGpuSampler,
        clsRenderPipelines, clsDynamicUniforms, clsFramebuffer, clsNativeImage, clsNativeImageFormat,
        clsMatrix4f, clsVector4f, clsVector3f;

    private static Method mGetDevice, mGetSamplerCache, mSamplerCacheGet,
        mCreateTexture, mCreateTextureView, mCreateBuffer, mCreateBufferSized, mCreateCommandEncoder,
        mWriteToTexture, mWriteToBuffer, mBufferSlice, mCreateRenderPass, mSetPipeline, mBindTexture, mSetUniformSlice,
        mSetVertexBuffer, mDraw, mClosePass, mBindDefaultUniforms, mGetDynamicUniforms,
        mDynamicUniformsWrite, mGetColorAttachmentView, mNativeImageSetColor,
        mShapeIndexBufferGetBuffer, mShapeIndexBufferGetType, mSetIndexBuffer, mDrawIndexed,
        mGetProjectionMatrixBuffer;

    private static java.lang.reflect.Field fieldSharedSequentialQuad;
    private static Object sharedSequentialQuad;

    private static Constructor<?> ctorNativeImage, ctorVector4f, ctorVector3f;

    private static Object fieldFilterModeLinear, fieldTextureFormatRgba8,
        fieldNativeImageFormatRgba, fieldRenderPipelineGuiText;

    private static int usageTextureBinding, usageTextureCopyDst, usageBufferVertex, usageBufferCopyDst, usageBufferUniform;

    private static Method mMatrixSetOrtho, mMatrixGetFloatArray;

    private static boolean resolveAttempted, resolveOk;

    private static synchronized boolean resolve() {
        if (resolveAttempted) return resolveOk;
        resolveAttempted = true;
        try {
            clsRenderSystem = McReflect.rawClass("com.mojang.blaze3d.systems.RenderSystem");
            clsGpuDevice = McReflect.rawClass("com.mojang.blaze3d.systems.GpuDevice");
            clsCommandEncoder = McReflect.rawClass("com.mojang.blaze3d.systems.CommandEncoder");
            clsRenderPass = McReflect.rawClass("com.mojang.blaze3d.systems.RenderPass");
            clsGpuTexture = McReflect.rawClass("com.mojang.blaze3d.textures.GpuTexture");
            clsGpuTextureView = McReflect.rawClass("com.mojang.blaze3d.textures.GpuTextureView");
            clsTextureFormat = McReflect.rawClass("com.mojang.blaze3d.textures.TextureFormat");
            clsGpuBuffer = McReflect.rawClass("com.mojang.blaze3d.buffers.GpuBuffer");
            clsGpuBufferSlice = McReflect.rawClass("com.mojang.blaze3d.buffers.GpuBufferSlice");
            clsFilterMode = McReflect.rawClass("com.mojang.blaze3d.textures.FilterMode");
            clsRenderPipeline = McReflect.rawClass("com.mojang.blaze3d.pipeline.RenderPipeline");
            clsMatrix4f = McReflect.rawClass("org.joml.Matrix4f");
            clsVector4f = McReflect.rawClass("org.joml.Vector4f");
            clsVector3f = McReflect.rawClass("org.joml.Vector3f");

            // Classes obfusquées (net.minecraft) — résolution Yarn dynamique, jamais de nom obf codé en dur.
            // McReflect.yarnClass (pas MappingsRegistry.loadClass directement) : même
            // convention que le chemin NativeImage déjà éprouvé dans UiRenderer
            // (ensureNativeTextureApiResolved) — évite toute divergence de comportement.
            clsSamplerCache = McReflect.yarnClass("net/minecraft/client/gl/SamplerCache");
            clsGpuSampler = McReflect.yarnClass("net/minecraft/client/gl/GpuSampler");
            clsRenderPipelines = McReflect.yarnClass("net/minecraft/client/gl/RenderPipelines");
            clsDynamicUniforms = McReflect.yarnClass("net/minecraft/client/gl/DynamicUniforms");
            clsFramebuffer = McReflect.yarnClass("net/minecraft/client/gl/Framebuffer");
            clsNativeImage = McReflect.yarnClass("net/minecraft/client/texture/NativeImage");
            clsNativeImageFormat = McReflect.yarnClass("net/minecraft/client/texture/NativeImage$Format");
            if (clsSamplerCache == null || clsGpuSampler == null || clsRenderPipelines == null
                    || clsDynamicUniforms == null || clsFramebuffer == null || clsNativeImage == null
                    || clsNativeImageFormat == null) {
                throw new ClassNotFoundException("une classe net.minecraft requise est introuvable (voir logs Yarn)");
            }

            mGetDevice = clsRenderSystem.getMethod("getDevice");
            mGetSamplerCache = clsRenderSystem.getMethod("getSamplerCache");
            mBindDefaultUniforms = clsRenderSystem.getMethod("bindDefaultUniforms", clsRenderPass);
            mGetDynamicUniforms = clsRenderSystem.getMethod("getDynamicUniforms");

            // SamplerCache.get(FilterMode) — un seul argument FilterMode parmi 4 surcharges "get" ;
            // résolu par NOM+DESCRIPTEUR OFFICIEL (jamais par nom seul, ambigu ici) — voir
            // MappingsRegistry.getObfMethodName, même motif que tout le reste du projet.
            String getSamplerName = MappingsRegistry.getObfMethodName(
                "net/minecraft/client/gl/SamplerCache", "get", "(Lcom/mojang/blaze3d/textures/FilterMode;)Lfzf;");
            mSamplerCacheGet = clsSamplerCache.getMethod(getSamplerName, clsFilterMode);

            mGetProjectionMatrixBuffer = clsRenderSystem.getMethod("getProjectionMatrixBuffer");

            mCreateCommandEncoder = clsGpuDevice.getMethod("createCommandEncoder");
            mCreateTexture = clsGpuDevice.getMethod("createTexture",
                java.util.function.Supplier.class, int.class, clsTextureFormat, int.class, int.class, int.class, int.class);
            mCreateTextureView = clsGpuDevice.getMethod("createTextureView", clsGpuTexture);
            mCreateBuffer = clsGpuDevice.getMethod("createBuffer", java.util.function.Supplier.class, int.class, ByteBuffer.class);
            mCreateBufferSized = clsGpuDevice.getMethod("createBuffer", java.util.function.Supplier.class, int.class, long.class);
            mBufferSlice = clsGpuBuffer.getMethod("slice", long.class, long.class);

            mWriteToTexture = clsCommandEncoder.getMethod("writeToTexture", clsGpuTexture, clsNativeImage);
            mWriteToBuffer = clsCommandEncoder.getMethod("writeToBuffer", clsGpuBufferSlice, ByteBuffer.class);
            mCreateRenderPass = clsCommandEncoder.getMethod("createRenderPass",
                java.util.function.Supplier.class, clsGpuTextureView, OptionalInt.class);

            mSetPipeline = clsRenderPass.getMethod("setPipeline", clsRenderPipeline);
            mBindTexture = clsRenderPass.getMethod("bindTexture", String.class, clsGpuTextureView, clsGpuSampler);
            mSetUniformSlice = clsRenderPass.getMethod("setUniform", String.class, clsGpuBufferSlice);
            mSetVertexBuffer = clsRenderPass.getMethod("setVertexBuffer", int.class, clsGpuBuffer);
            mDraw = clsRenderPass.getMethod("draw", int.class, int.class);
            mClosePass = clsRenderPass.getMethod("close");

            // BUG TROUVÉ (texte invisible malgré draw() qui "réussit" sans
            // exception) : RenderPipelines.GUI_TEXT utilise le mode
            // VertexFormat.DrawMode.QUADS (vérifié par désassemblage,
            // VertexFormats.POSITION_COLOR_TEXTURE_LIGHT + mode "h"=QUADS) —
            // RenderPass.draw(int,int) (non indexé) ne fait PAS la conversion
            // QUADS→triangles automatiquement ; il faut passer par
            // drawIndexed() avec un buffer d'indices de triangulation, fourni
            // par l'utilitaire PARTAGÉ que Minecraft utilise lui-même pour ça
            // (RenderSystem.sharedSequentialQuad, champ STATIC PRIVATE — pas
            // d'accesseur public, d'où la réflexion sur le champ directement).
            // BUG TROUVÉ (NoSuchMethodException malgré une signature identique
            // caractère pour caractère à celle désassemblée) : contrairement
            // aux classes Blaze3D de PREMIER NIVEAU (com.mojang.blaze3d.*,
            // stables/identiques dans les 3 espaces de noms official/
            // intermediary/named — jamais remappées par Fabric), leurs classes
            // IMBRIQUÉES (VertexFormat$a, RenderSystem$a...) restent, elles,
            // remappées comme le reste du jeu obfusqué : confirmé par
            // introspection directe (getMethods() sur RenderPass réellement
            // chargé) que le VRAI paramètre runtime est nommé
            // "VertexFormat$class_5595" (intermediary), PAS "VertexFormat$a"
            // (official) — le classloader n'était PAS le problème (déjà
            // KnotClassLoader des deux côtés), c'était le NOM utilisé qui
            // était faux sous Fabric. Fix : passer par
            // MappingsRegistry.runtimeClass() (même mécanisme official→
            // runtime déjà utilisé PARTOUT ailleurs dans ce projet pour les
            // classes net.minecraft.* obfusquées) au lieu de supposer ces
            // classes imbriquées stables comme leurs classes conteneuses.
            Class<?> clsShapeIndexBuffer = Class.forName(
                MappingsRegistry.runtimeClass("com/mojang/blaze3d/systems/RenderSystem$a"), false, clsRenderPass.getClassLoader());
            // Champ déclaré directement sur RenderSystem (classe de PREMIER
            // NIVEAU, stable/inchangée dans les 3 espaces de noms — voir
            // commentaire ci-dessus) : nom littéral direct, pas de remapping
            // nécessaire ici (contrairement aux classes imbriquées).
            fieldSharedSequentialQuad = clsRenderSystem.getDeclaredField("sharedSequentialQuad");
            fieldSharedSequentialQuad.setAccessible(true);
            // Mêmes méthodes que désassemblées ("b(int)"/"a()", noms OFFICIELS
            // bruts) — mais ce sont des méthodes DÉCLARÉES SUR une classe
            // imbriquée elle-même remappée : leurs noms le sont probablement
            // AUSSI sous Fabric (pas juste le nom de la classe conteneuse).
            // Résolus via runtimeMethod() (official→runtime), même mécanisme
            // que runtimeClass() ci-dessus.
            String getBufferRuntime = MappingsRegistry.runtimeMethod(
                "com/mojang/blaze3d/systems/RenderSystem$a", "b", "(I)Lcom/mojang/blaze3d/buffers/GpuBuffer;");
            String getTypeRuntime = MappingsRegistry.runtimeMethod(
                "com/mojang/blaze3d/systems/RenderSystem$a", "a", "()Lcom/mojang/blaze3d/vertex/VertexFormat$a;");
            mShapeIndexBufferGetBuffer = clsShapeIndexBuffer.getMethod(getBufferRuntime, int.class); // getIndexBuffer(int) — assure la capacité et renvoie le GpuBuffer
            mShapeIndexBufferGetType = clsShapeIndexBuffer.getMethod(getTypeRuntime); // getIndexType() -> VertexFormat.IndexType
            Class<?> clsIndexType = Class.forName(
                MappingsRegistry.runtimeClass("com/mojang/blaze3d/vertex/VertexFormat$a"), false, clsRenderPass.getClassLoader());
            mSetIndexBuffer = clsRenderPass.getMethod("setIndexBuffer", clsGpuBuffer, clsIndexType);
            mDrawIndexed = clsRenderPass.getMethod("drawIndexed", int.class, int.class, int.class, int.class);

            // DynamicUniforms.write(Matrix4fc, Vector4fc, Vector3fc, Matrix4fc) — nom Yarn
            // "write" (obf "a") — un seul candidat 4-arg, sûr par nom seul ici.
            for (Method m : clsDynamicUniforms.getMethods()) {
                if (m.getParameterCount() == 4 && clsGpuBufferSlice.isAssignableFrom(m.getReturnType())) {
                    mDynamicUniformsWrite = m;
                    break;
                }
            }

            mGetColorAttachmentView = MappingsRegistry.isLoaded()
                ? clsFramebuffer.getMethod(MappingsRegistry.getObfMethodName("net/minecraft/client/gl/Framebuffer", "getColorAttachmentView"))
                : null;

            // Même chemin, déjà éprouvé, que UiRenderer.ensureNativeTextureApiResolved()
            // (createFontTextureViaNativeImage) — constructeur (Format,int,int,boolean)
            // et méthode "setColor" (nom Yarn stable, PAS "setColorArgb").
            ctorNativeImage = clsNativeImage.getDeclaredConstructor(clsNativeImageFormat, int.class, int.class, boolean.class);
            ctorNativeImage.setAccessible(true);
            mNativeImageSetColor = McReflect.method(clsNativeImage, "net/minecraft/client/texture/NativeImage", "setColor", int.class, int.class, int.class);

            String rgbaObf = MappingsRegistry.getObfFieldName("net/minecraft/client/texture/NativeImage$Format", "RGBA");
            java.lang.reflect.Field rgbaField = clsNativeImageFormat.getDeclaredField(rgbaObf);
            rgbaField.setAccessible(true);
            fieldNativeImageFormatRgba = rgbaField.get(null);

            fieldFilterModeLinear = clsFilterMode.getField("LINEAR").get(null);
            fieldTextureFormatRgba8 = clsTextureFormat.getField("RGBA8").get(null);
            fieldRenderPipelineGuiText = clsRenderPipelines.getField(
                MappingsRegistry.getObfFieldName("net/minecraft/client/gl/RenderPipelines", "GUI_TEXT")).get(null);
            if (mNativeImageSetColor == null || fieldNativeImageFormatRgba == null || fieldRenderPipelineGuiText == null
                    || fieldSharedSequentialQuad == null || mShapeIndexBufferGetBuffer == null
                    || mShapeIndexBufferGetType == null || mSetIndexBuffer == null || mDrawIndexed == null) {
                throw new NoSuchMethodException("setColor/RGBA/GUI_TEXT/sharedSequentialQuad introuvable (voir logs)");
            }

            // BUG TROUVÉ (premier test v373/v374, IllegalStateException) : notre
            // texture d'atlas était créée avec USAGE_TEXTURE_BINDING seul —
            // CommandEncoder.writeToTexture() exige AUSSI USAGE_COPY_DST sur la
            // texture cible ("Color texture must have USAGE_COPY_DST to be a
            // destination for a write"). GpuTexture.USAGE_COPY_DST est une
            // constante DISTINCTE de GpuBuffer.USAGE_COPY_DST (classes
            // différentes, même nom de champ) — bien résoudre celle de GpuTexture ici.
            usageTextureBinding = clsGpuTexture.getField("USAGE_TEXTURE_BINDING").getInt(null);
            usageTextureCopyDst = clsGpuTexture.getField("USAGE_COPY_DST").getInt(null);
            usageBufferVertex = clsGpuBuffer.getField("USAGE_VERTEX").getInt(null);
            usageBufferCopyDst = clsGpuBuffer.getField("USAGE_COPY_DST").getInt(null);
            usageBufferUniform = clsGpuBuffer.getField("USAGE_UNIFORM").getInt(null);

            ctorVector4f = clsVector4f.getConstructor(float.class, float.class, float.class, float.class);
            ctorVector3f = clsVector3f.getConstructor(float.class, float.class, float.class);

            // BUG SUSPECTÉ (aucune exception nulle part dans TOUTE la chaîne —
            // texture/buffer/pass/uniforms/draw indexé tous OK, DIAG-PROJ non-
            // null — mais 0 pixel dessiné, confirmé par capture d'écran) : le
            // "Projection" repris par bindDefaultUniforms(pass) est celui de
            // l'état AMBIANT de Minecraft à notre point d'accroche (fin de
            // frame) — calibré pour SES propres appels (avec un modelView qui
            // inclut probablement un décalage Z que nous ne reproduisons pas,
            // nos sommets utilisant systématiquement z=0 avec un modelView
            // identité). Si le near/far de ce projection ambiant ne couvre pas
            // z=0 (rien ne garantit qu'il le fasse), nos sommets sont
            // silencieusement clippés par le GPU — AUCUNE exception Java ne
            // peut jamais détecter un clipping géométrique, c'est un
            // comportement GPU normal, pas une erreur. Fix : ne plus dépendre
            // de cet état ambiant emprunté pour "Projection" — construire NOTRE
            // PROPRE matrice orthographique 2D (via JOML Matrix4f.setOrtho,
            // near=-1000/far=1000 SYMÉTRIQUE autour de 0 : garantit
            // mathématiquement que z=0 → z_ndc=0, toujours dans [-1,1]) et
            // l'imposer nous-mêmes après bindDefaultUniforms (qui reste utile
            // pour Fog/Globals/Lighting, non touchés ici).
            mMatrixSetOrtho = clsMatrix4f.getMethod("setOrtho",
                float.class, float.class, float.class, float.class, float.class, float.class);
            mMatrixGetFloatArray = clsMatrix4f.getMethod("get", float[].class);

            resolveOk = true;
            LauncherLog.info("[LauncherAgent] UiTextBlaze3D: résolution OK, pipeline GUI_TEXT natif prêt");

            // DIAGNOSTIC (aucune exception nulle part, Sampler2/UV2 déjà
            // corrigé via lecture directe du shader réel, TOUJOURS 0 pixel) :
            // RenderPipeline (com.mojang.blaze3d.pipeline.RenderPipeline, non
            // obfusquée) expose isCull()/getDepthTestFunction()/
            // getBlendFunction()/isWriteColor()/isWriteAlpha() — plutôt que de
            // deviner l'état de culling/blend/write réellement configuré pour
            // GUI_TEXT, on le LIT directement (un mauvais winding de nos 4
            // sommets par quad serait, comme le shader, silencieux : aucune
            // exception, juste des triangles back-face culled).
            try {
                Method mIsCull = clsRenderPipeline.getMethod("isCull");
                Method mGetDepthTestFunction = clsRenderPipeline.getMethod("getDepthTestFunction");
                Method mGetBlendFunction = clsRenderPipeline.getMethod("getBlendFunction");
                Method mIsWriteColor = clsRenderPipeline.getMethod("isWriteColor");
                Method mIsWriteAlpha = clsRenderPipeline.getMethod("isWriteAlpha");
                LauncherLog.info("[LauncherAgent] DIAG-PIPELINE: GUI_TEXT cull=" + mIsCull.invoke(fieldRenderPipelineGuiText)
                    + " depthTest=" + mGetDepthTestFunction.invoke(fieldRenderPipelineGuiText)
                    + " blend=" + mGetBlendFunction.invoke(fieldRenderPipelineGuiText)
                    + " writeColor=" + mIsWriteColor.invoke(fieldRenderPipelineGuiText)
                    + " writeAlpha=" + mIsWriteAlpha.invoke(fieldRenderPipelineGuiText));
            } catch (Throwable diagT) {
                LauncherLog.err("[LauncherAgent] DIAG-PIPELINE: introspection échouée : " + diagT);
            }
        } catch (Throwable t) {
            resolveOk = false;
            LauncherLog.err("[LauncherAgent] UiTextBlaze3D: résolution échouée, repli sur le pipeline SDF existant : " + t);
        }
        return resolveOk;
    }

    // ── Textures (une par UiFont, mises en cache — jamais recréées) ─────────

    private static final Map<UiFont, Object[]> TEXTURES = new HashMap<>(); // [GpuTexture, GpuTextureView, GpuSampler]

    private static Object[] ensureTexture(UiFont font) throws Exception {
        Object[] cached = TEXTURES.get(font);
        if (cached != null) return cached;

        // BUG TROUVÉ (draw() réussissait sans exception, projection valide
        // (DIAG-PROJ), mais AUCUN texte visible) : font.atlasImage() est
        // l'atlas APRÈS transformation en champ de distance signée (SDF, voir
        // UiFont.buildSignedDistanceField) — alpha ~128 sur toute la zone de
        // transition, saturé (0/255) seulement loin du bord. Le shader vanilla
        // de RenderPipelines.GUI_TEXT (core/rendertype_text) ne fait AUCUN
        // seuillage SDF, juste texture.rgba × couleur : lui donner cette
        // texture produit un rendu quasi invisible. Fix : atlasImagePlain()
        // (copie antialiasée classique, capturée AVANT la transformation SDF)
        // — exactement le format que le shader vanilla attend (confirmé par
        // désassemblage : GlyphAtlasTexture vanilla est lui-même un bitmap de
        // couverture classique, jamais une SDF).
        BufferedImage img = font.atlasImagePlain();
        int w = img.getWidth(), h = img.getHeight();

        // NativeImage(Format, w, h, useStbImage=false) puis remplissage pixel par pixel
        // via setColor(x,y,color) — NativeImage attend du RGBA "petit-boutiste" (R en
        // premier octet, A en dernier), l'INVERSE de BufferedImage.getRGB() (ARGB, A en
        // premier) — même conversion déjà validée dans
        // UiRenderer.createFontTextureViaNativeImage, reprise à l'identique ici.
        // Remplissage un pixel à la fois (lent, ~2M appels réflexifs pour un atlas
        // 1024x2048) — accepté pour cette première version fonctionnelle, à optimiser
        // plus tard via copie mémoire brute (voir memCopy/memAddress dans UiRenderer)
        // une fois le pipeline confirmé correct de bout en bout.
        Object nativeImage = ctorNativeImage.newInstance(fieldNativeImageFormatRgba, w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                int nativeColor = (a << 24) | (b << 16) | (g << 8) | r;
                mNativeImageSetColor.invoke(nativeImage, x, y, nativeColor);
            }
        }

        Object device = mGetDevice.invoke(null);
        final String label = "yuyuframe_font_" + System.identityHashCode(font);
        java.util.function.Supplier<String> labelSupplier = () -> label;
        Object texture = mCreateTexture.invoke(device, labelSupplier, usageTextureBinding | usageTextureCopyDst, fieldTextureFormatRgba8, w, h, 1, 1);

        Object encoder = mCreateCommandEncoder.invoke(device);
        mWriteToTexture.invoke(encoder, texture, nativeImage);

        Object textureView = mCreateTextureView.invoke(device, texture);
        Object sampler = mSamplerCacheGet.invoke(mGetSamplerCache.invoke(null), fieldFilterModeLinear);

        Object[] result = {texture, textureView, sampler};
        TEXTURES.put(font, result);
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: atlas '" + label + "' créé via GpuDevice.createTexture (w=" + w + " h=" + h + ")");
        return result;
    }

    // ── Texture blanche 1×1 dédiée à Sampler2 (voir drawText) ───────────────

    private static Object[] whiteTexture; // [GpuTexture, GpuTextureView, GpuSampler]

    // BUG TROUVÉ (aucune exception nulle part, projection maison déjà
    // corrigée, TOUJOURS 0 pixel dessiné) : shader RÉEL extrait du jar client
    // 1.21.11 (assets/minecraft/shaders/core/rendertype_text.vsh, jamais
    // deviné) — `vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);`.
    // UV2 (attribut ivec2, PAS une simple donnée décorative "neutralisée
    // ailleurs" comme supposé à tort dans un commentaire précédent) sert à
    // indexer le lightmap via Sampler2. Avec light1=(short)0xF000 (= -4096 en
    // short SIGNÉ), UV2/16 sort de la plage valide (texelFetch hors-limites =
    // comportement indéfini, en pratique (0,0,0,0) sur la plupart des
    // drivers) → vertexColor devient transparent NOIR → le fragment shader
    // (`if (color.a < 0.1) discard;`) rejette CHAQUE fragment. Aucune
    // exception possible : c'est un calcul GPU pur, silencieux par nature.
    // MAX_LIGHT_COORDINATE (0x00F000F0) se décompose RÉELLEMENT en deux
    // moitiés IDENTIQUES 0x00F0/0x00F0 (vérifié par calcul, pas 0x00F0/0xF000
    // comme l'ancien commentaire l'affirmait à tort — l'erreur de "fix"
    // précédente est la cause directe de ce bug). Plutôt que de reproduire
    // l'exacte sémantique du lightmap vanilla (nécessiterait de résoudre et
    // lier SA texture réelle, complexité inutile pour de l'UI toujours
    // plein-jour), on neutralise complètement : Sampler2 lié à une texture
    // DÉDIÉE 1×1 blanc opaque (jamais notre atlas — un texel à l'intérieur du
    // padding de l'atlas n'a AUCUNE garantie d'être opaque) + UV2=(0,0)
    // (texelFetch(..., (0,0), 0) toujours valide, quelle que soit la taille
    // de la texture liée) → `texelFetch(...) = blanc opaque` → `vertexColor =
    // Color * blanc = Color`, inchangé, exactement le comportement voulu.
    private static Object[] ensureWhiteTexture() throws Exception {
        if (whiteTexture != null) return whiteTexture;
        Object nativeImage = ctorNativeImage.newInstance(fieldNativeImageFormatRgba, 1, 1, false);
        mNativeImageSetColor.invoke(nativeImage, 0, 0, 0xFFFFFFFF); // petit-boutiste RGBA — blanc opaque quel que soit l'ordre des octets
        Object device = mGetDevice.invoke(null);
        java.util.function.Supplier<String> label = () -> "yuyuframe_text_white1x1";
        Object texture = mCreateTexture.invoke(device, label, usageTextureBinding | usageTextureCopyDst, fieldTextureFormatRgba8, 1, 1, 1, 1);
        Object encoder = mCreateCommandEncoder.invoke(device);
        mWriteToTexture.invoke(encoder, texture, nativeImage);
        Object textureView = mCreateTextureView.invoke(device, texture);
        Object sampler = mSamplerCacheGet.invoke(mGetSamplerCache.invoke(null), fieldFilterModeLinear);
        whiteTexture = new Object[]{texture, textureView, sampler};
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: texture blanche 1x1 (Sampler2) créée");
        return whiteTexture;
    }

    // ── Buffer de sommets persistant (un seul, partagé, jamais recréé sauf agrandissement) ──

    private static Object vertexBuffer;
    private static long vertexBufferCapacity;

    private static Object ensureVertexBuffer(Object device, int neededBytes) throws Exception {
        if (vertexBuffer != null && neededBytes <= vertexBufferCapacity) return vertexBuffer;
        if (vertexBuffer != null) {
            try { ((AutoCloseable) vertexBuffer).close(); } catch (Throwable ignored) {}
        }
        // Arrondi généreux (x2 + marge) pour éviter de réallouer à chaque légère
        // variation de longueur de texte — même logique qu'une croissance
        // classique de liste/buffer dynamique (jamais un agrandissement pile-poil).
        long newCapacity = Math.max(4096L, Math.max(neededBytes, vertexBufferCapacity * 2));
        java.util.function.Supplier<String> label = () -> "yuyuframe_text_vbo";
        vertexBuffer = mCreateBufferSized.invoke(device, label, usageBufferVertex | usageBufferCopyDst, newCapacity);
        vertexBufferCapacity = newCapacity;
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: buffer de sommets (re)créé, capacité=" + newCapacity + " octets");
        return vertexBuffer;
    }

    // ── Buffer de projection persistant (mat4x4, 64 octets, jamais recréé) ──
    // Voir commentaire dans resolve() : notre propre matrice orthographique,
    // indépendante de l'état ambiant emprunté par bindDefaultUniforms — évite
    // tout clipping GPU silencieux si le near/far ambiant ne couvre pas z=0.

    private static Object projectionBuffer;
    private static int projectionVpWidth = -1, projectionVpHeight = -1;

    private static Object ensureProjectionBuffer(Object device, Object encoder, int vpWidth, int vpHeight) throws Exception {
        if (projectionBuffer != null && vpWidth == projectionVpWidth && vpHeight == projectionVpHeight) {
            return projectionBuffer;
        }
        if (projectionBuffer == null) {
            java.util.function.Supplier<String> label = () -> "yuyuframe_text_projection";
            projectionBuffer = mCreateBufferSized.invoke(device, label, usageBufferUniform | usageBufferCopyDst, 64L);
        }
        Object ortho = clsMatrix4f.getConstructor().newInstance();
        // BUG TROUVÉ (utilisateur : glisser le module bouge le texte en
        // miroir, même après synchronisation du timing rects/texte — la
        // vraie cause n'était donc PAS le timing) : drawRoundedRect
        // (Legacy/Modern, voir leurs commentaires "cohérent avec gl_FragCoord
        // et le reste du pipeline (mouse/UI), pas de flip") utilise
        // glOrtho(0,w,0,h,...) — origine BAS-GAUCHE, Y CROISSANT VERS LE HAUT
        // (comme gl_FragCoord/la souris/tout le reste du moteur). Notre
        // projection Blaze3D faisait l'INVERSE (bottom=vpHeight, top=0 — Y
        // croissant vers le BAS) : pour un même "y", rects et texte se
        // positionnaient donc en miroir vertical l'un de l'autre. Fix :
        // bottom=0, top=vpHeight — MÊME convention que le reste du moteur.
        // near=-1000/far=1000 toujours symétrique autour de 0 (évite tout
        // clipping GPU silencieux sur z=0, inchangé).
        mMatrixSetOrtho.invoke(ortho, 0f, (float) vpWidth, 0f, (float) vpHeight, -1000f, 1000f);
        float[] cols = (float[]) mMatrixGetFloatArray.invoke(ortho, (Object) new float[16]);
        ByteBuffer data = ByteBuffer.allocateDirect(64).order(java.nio.ByteOrder.nativeOrder());
        for (float v : cols) data.putFloat(v);
        data.flip();
        Object slice = mBufferSlice.invoke(projectionBuffer, 0L, 64L);
        mWriteToBuffer.invoke(encoder, slice, data);
        projectionVpWidth = vpWidth;
        projectionVpHeight = vpHeight;
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: projection orthographique (re)écrite, " + vpWidth + "x" + vpHeight);
        return projectionBuffer;
    }

    // ── Dessin ───────────────────────────────────────────────────────────────

    /**
     * Dessine {@code text} avec {@code font} via le pipeline natif
     * {@code RenderPipelines.GUI_TEXT}. {@link UiRenderer#drawTextModern} NE
     * retombe PLUS sur le pipeline SDF en cas d'échec (celui-ci est corrompu
     * de façon non-déterministe sur ce bracket, voir historique — un texte
     * absent vaut mieux qu'un texte parfois illisible) : un échec ici est
     * donc silencieux (rien ne se dessine cette frame), jamais un repli.
     */
    // BUG TROUVÉ (IllegalStateException "Close the existing render pass
    // before performing additional commands", apparu de façon INTERMITTENTE
    // — un lancement a marché, le suivant a échoué dès le premier appel, SANS
    // AUCUN changement de ce fichier entre les deux) : semble être un état de
    // render pass Blaze3D résiduel au point d'injection précis
    // (Framebuffer.blitToScreen() TAIL) — pas encore élucidé avec certitude
    // (blitToScreen() lui-même ne fait qu'appeler CommandEncoder.presentTexture,
    // vérifié par désassemblage, qui pourrait laisser un état de pass
    // implicite selon le backend). PAS de désactivation permanente ici (un
    // premier essai avait ce comportement — a transformé un problème
    // possiblement transitoire en "plus jamais de texte du tout" pour le
    // reste du lancement) : retente CHAQUE frame (auto-guérison si transitoire),
    // seul le PREMIER échec est loggé (jamais par frame — coût I/O disque
    // synchrone déjà identifié comme anti-pattern ailleurs dans ce projet).
    private static int failureLogCount;
    private static boolean projLogged;

    // DIAGNOSTIC (IllegalStateException "Close the existing render pass" —
    // jamais localisé précisément QUEL appel la lève, tout est capturé par un
    // seul catch global) : nom de l'étape en cours, mis à jour avant CHAQUE
    // appel réflexif risqué, inclus dans le log d'erreur — permet de savoir
    // enfin EXACTEMENT où ça casse (writeToBuffer ? createRenderPass ?
    // bindTexture ? autre chose ?) sans deviner davantage.
    private static volatile String currentStage = "?";

    // ── File d'attente d'un frame (rendu différé) ───────────────────────────
    //
    // BUG TROUVÉ (chaîne API 100% sans exception — texture plate déjà
    // corrigée — texte TOUJOURS invisible) : notre pass Blaze3D dessine dans
    // mc.getFramebuffer().getColorAttachmentView(), la texture INTERMÉDIAIRE
    // que blitToScreen() copie ENSUITE vers le vrai backbuffer de la fenêtre
    // via CommandEncoder.presentTexture() (désassemblage confirmé : le corps
    // de blitToScreen() ne fait QUE ça). Notre hook est à la TAIL de
    // blitToScreen(), donc APRÈS ce présent — on écrit bien dans la texture,
    // mais TROP TARD pour être inclus dans le présent de CETTE frame ; ce
    // contenu est ensuite écrasé par le rendu normal de la frame SUIVANTE
    // avant même d'avoir jamais été présenté. Voir la javadoc de
    // GlobalUiPresentMixin : le dessin GL BRUT (rects/toggles), lui, VEUT
    // être après presentTexture() car celui-ci semble re-binder le FBO par
    // défaut de la fenêtre (FBO 0) — les deux mécanismes ont donc des
    // exigences de timing OPPOSÉES, d'où l'impossibilité de les dessiner
    // au même point.
    //
    // Fix : au lieu de dessiner immédiatement, drawText() EMPILE ses
    // paramètres (queueDraw) — GlobalUiPresentMixin appelle flushQueued() à
    // la HEAD de blitToScreen (donc AVANT presentTexture, alors que
    // getColorAttachmentView() est encore la texture qui VA être présentée
    // CETTE frame). Comme les appels queueDraw() de la frame N ont lieu à la
    // TAIL de blitToScreen (uiDraw), ils ne sont flushés qu'à la HEAD de
    // blitToScreen de la frame N+1 : un retard d'UNE frame (~16 ms à 60
    // FPS), imperceptible pour de l'UI, en échange d'un texte enfin RÉELLEMENT
    // visible. Le dessin GL brut (TAIL, inchangé) reste, lui, synchrone.
    private static final class QueuedText {
        final UiFont font; final String text; final float x, y, scale; final UiColor color; final int vpWidth, vpHeight;
        QueuedText(UiFont font, String text, float x, float y, UiColor color, float scale, int vpWidth, int vpHeight) {
            this.font = font; this.text = text; this.x = x; this.y = y; this.color = color; this.scale = scale;
            this.vpWidth = vpWidth; this.vpHeight = vpHeight;
        }
    }

    private static final java.util.List<QueuedText> queued = new java.util.ArrayList<>();

    /** Appelé depuis {@code UiRenderer.drawTextModern} — empile au lieu de dessiner immédiatement, voir commentaire ci-dessus. */
    public static void queueDraw(UiFont font, String text, float x, float y, UiColor color, float scale, int vpWidth, int vpHeight) {
        if (!isAvailable() || text == null || text.isEmpty()) return;
        queued.add(new QueuedText(font, text, x, y, color, scale, vpWidth, vpHeight));
    }

    private static int flushLogCount;

    /** Appelé depuis {@code GlobalUiPresentMixin} à la HEAD de blitToScreen (avant presentTexture) — dessine tout ce qui a été empilé la frame précédente. */
    public static void flushQueued() {
        if (queued.isEmpty()) return;
        // DIAGNOSTIC : confirme que flushQueued() tourne bien EN CONTINU
        // (chaque frame tant qu'un écran custom est ouvert), pas juste une
        // fois au premier frame — les logs de création (atlas/buffer) sont
        // mis en cache après le premier appel et ne le prouvent pas.
        if (flushLogCount < 10) {
            flushLogCount++;
            LauncherLog.info("[LauncherAgent] DIAG-FLUSH #" + flushLogCount + ": " + queued.size() + " texte(s) en attente");
        }
        // Copie + clear immédiat : si drawText() relance une exception, on ne
        // rejoue jamais indéfiniment le même lot en boucle.
        QueuedText[] batch = queued.toArray(new QueuedText[0]);
        queued.clear();
        for (QueuedText q : batch) {
            drawText(q.font, q.text, q.x, q.y, q.color, q.scale, q.vpWidth, q.vpHeight);
        }
    }

    private static boolean drawText(UiFont font, String text, float x, float y, UiColor color, float scale, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve()) return false;
        try {
            currentStage = "minecraftClient";
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            currentStage = "getFramebuffer";
            Object fb = getFramebuffer(mc);
            if (fb == null || mGetColorAttachmentView == null) return false;
            currentStage = "getColorAttachmentView";
            Object colorView = mGetColorAttachmentView.invoke(fb);
            if (colorView == null) return false;

            currentStage = "ensureTexture";
            Object[] tex = ensureTexture(font);
            Object textureView = tex[1], sampler = tex[2];

            currentStage = "ensureWhiteTexture";
            Object[] white = ensureWhiteTexture();
            Object whiteTextureView = white[1], whiteSampler = white[2];

            currentStage = "getDevice";
            Object device = mGetDevice.invoke(null);
            currentStage = "createCommandEncoder";
            Object encoder = mCreateCommandEncoder.invoke(device);

            float cs = scale * UiFont.SIZE_CORRECTION;
            float penX = Math.round(x);
            // Cohérent avec la projection Y-UP ci-dessus (ensureProjectionBuffer) :
            // au-dessus de la ligne de base (ascent) = Y PLUS GRAND (Y croît
            // vers le HAUT, comme drawRoundedRect/gl_FragCoord/la souris), en
            // dessous (descent) = Y PLUS PETIT. Le pairage UV (yTop↔g.v0,
            // yBottom↔g.v1, plus bas) et l'ordre d'émission des sommets
            // restent corrects tels quels avec ce signe — vérifié par calcul
            // direct du winding en espace NDC réel (pas juste "en théorie") :
            // la relation NDC(yTop) > NDC(yBottom) est identique à celle de
            // l'ancienne projection Y-DOWN, donc le MÊME winding (CCW, le
            // seul confirmé visible) en résulte — aucun autre changement requis.
            float yTop = Math.round(y + font.ascent * cs);
            float yBottom = Math.round(y - font.descent * cs);
            int rgba = 0xFFFFFFFF; // couleur déjà appliquée via DynamicTransforms — sommets en blanc neutre
            // UV2=(0,0) : voir ensureWhiteTexture() — texelFetch(Sampler2, (0,0)/16, 0)
            // = (0,0), toujours valide, sur notre texture 1×1 blanche dédiée
            // (JAMAIS 0xF000, qui vaut -4096 en short signé et provoquait un
            // texelFetch hors-limites → vertexColor totalement transparent).
            short light0 = 0, light1 = 0;

            ByteBuffer verts = ByteBuffer.allocateDirect(text.length() * 4 * 28).order(java.nio.ByteOrder.nativeOrder());
            int vertexCount = 0;
            for (int i = 0; i < text.length(); i++) {
                UiFont.Glyph g = font.glyph(text.charAt(i));
                float gw = Math.round(g.width * cs);
                float x0 = penX, x1 = penX + gw;
                // BUG TROUVÉ (v403 : texte totalement disparu après la
                // correction du signe ascent/descent ci-dessus, cull=true
                // confirmé par DIAG-PIPELINE) : jusqu'ici (v399-v402), yTop
                // était calculé AVEC LE MAUVAIS SIGNE (y+ascent), donc
                // NUMÉRIQUEMENT PLUS GRAND que yBottom (y-descent) — "yTop"
                // désignait en réalité le point géométriquement BAS, et
                // vice-versa. Mon analyse de winding en v399 ("top-left→
                // top-right→bottom-right→bottom-left = CW, à inverser") était
                // donc calculée sur une géométrie MAL ÉTIQUETÉE : l'ordre
                // RÉELLEMENT produit par le code v399-v402 était bottom-left→
                // bottom-right→top-right→top-left, soit CCW en vrai — c'est
                // CE winding (CCW) que le culling acceptait (texte visible en
                // v400-v402), pas CW comme je le croyais. Maintenant que
                // yTop/yBottom ont le bon signe (yTop réellement plus petit),
                // le MÊME ordre d'émission (x0,yTop)→(x1,yTop)→(x1,yBottom)→
                // (x0,yBottom) produit RÉELLEMENT du CW cette fois → rejeté
                // par le culling → texte disparu. Fix : ordre d'émission
                // restauré à top-left→bottom-left→bottom-right→top-right
                // (CCW, le SEUL qui ait jamais été confirmé visible), avec le
                // pairage UV correct (yTop↔g.v0 haut d'atlas, yBottom↔g.v1
                // bas d'atlas).
                putVertexPCTL(verts, x0, yTop, rgba, g.u0, g.v0, light0, light1);
                putVertexPCTL(verts, x0, yBottom, rgba, g.u0, g.v1, light0, light1);
                putVertexPCTL(verts, x1, yBottom, rgba, g.u1, g.v1, light0, light1);
                putVertexPCTL(verts, x1, yTop, rgba, g.u1, g.v0, light0, light1);
                vertexCount += 4;
                penX += Math.round(g.advance * cs);
            }
            verts.flip();

            // BUG TROUVÉ (12 FPS constatés après le premier test fonctionnel) :
            // créer PUIS FERMER un GpuBuffer à CHAQUE appel de drawText (HUD
            // compris, redessiné en continu même menu fermé) force le pilote à
            // allouer/libérer une ressource GPU des dizaines de fois par frame —
            // exactement l'anti-pattern déjà rencontré (DIAG7 v348) sous une forme
            // différente. Fix : un seul buffer PERSISTANT, agrandi seulement quand
            // nécessaire (jamais réduit), dont le contenu est simplement RÉÉCRIT
            // (writeToBuffer) à chaque appel au lieu d'être recréé — même esprit
            // que le VBO partagé unique de l'ancien pipeline SDF
            // (modernVbo/modernVao dans UiRenderer). Écrit AVANT l'ouverture de la
            // render pass (pas pendant) — par prudence, sans garantie contraire
            // trouvée dans l'API que ces deux opérations puissent s'entrelacer.
            currentStage = "ensureVertexBuffer";
            Object vbo = ensureVertexBuffer(device, verts.remaining());
            currentStage = "bufferSlice";
            Object slice = mBufferSlice.invoke(vbo, 0L, (long) verts.remaining());
            currentStage = "writeToBuffer";
            mWriteToBuffer.invoke(encoder, slice, verts);

            // BUG TROUVÉ (bissection par paliers depuis GlobalUiHudTestMixin —
            // testPass(2) réussit, testPass(3) échoue à closePass) :
            // DynamicUniforms.write(...) ÉCRIT dans un buffer partagé (probable
            // writeToBuffer interne sur le MÊME encoder) — exactement comme
            // notre propre écriture de sommets ci-dessus, cette écriture DOIT
            // se faire AVANT l'ouverture de notre render pass, jamais pendant.
            // L'ancien code appelait ça APRÈS createRenderPass (juste avant
            // setUniform) — c'est cette écriture EN PLEINE PASS qui provoquait
            // le conflit détecté (trop tard) par close(). setUniform lui-même
            // (juste BINDER un slice déjà écrit) reste, lui, à l'intérieur de
            // la pass — seule l'ÉCRITURE doit sortir.
            currentStage = "dynamicUniformsWrite";
            Object identity4 = clsMatrix4f.getConstructor().newInstance();
            Object white4 = ctorVector4f.newInstance(color.r, color.g, color.b, color.a);
            Object zero3 = ctorVector3f.newInstance(0f, 0f, 0f);
            Object dynUniforms = mGetDynamicUniforms.invoke(null);
            Object dynSlice = mDynamicUniformsWrite.invoke(dynUniforms, identity4, white4, zero3, identity4);

            // Notre propre "Projection" (voir resolve() pour le pourquoi) — même
            // règle que les deux écritures ci-dessus : AVANT createRenderPass.
            currentStage = "ensureProjectionBuffer";
            Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
            Object projectionSlice = mBufferSlice.invoke(projectionBuf, 0L, 64L);

            currentStage = "createRenderPass";
            java.util.function.Supplier<String> passLabel = () -> "yuyuframe_text";
            Object pass = mCreateRenderPass.invoke(encoder, passLabel, colorView, OptionalInt.empty());
            try {
                currentStage = "setPipeline";
                mSetPipeline.invoke(pass, fieldRenderPipelineGuiText);
                currentStage = "bindDefaultUniforms";
                Object projBuf = mGetProjectionMatrixBuffer.invoke(null);
                if (!projLogged) {
                    projLogged = true;
                    LauncherLog.info("[LauncherAgent] DIAG-PROJ: RenderSystem.getProjectionMatrixBuffer() = " + projBuf);
                }
                mBindDefaultUniforms.invoke(null, pass);

                // Écrase le "Projection" ambiant repris par bindDefaultUniforms
                // avec le nôtre (voir resolve() + ensureProjectionBuffer) — un
                // setUniform() APRÈS un autre sur le même nom remplace le
                // binding précédent (juste une liaison, pas une accumulation),
                // même pattern que Sampler0/Sampler2 déjà liés séparément plus bas.
                currentStage = "setUniform(Projection)";
                mSetUniformSlice.invoke(pass, "Projection", projectionSlice);

                currentStage = "setUniform(DynamicTransforms)";
                mSetUniformSlice.invoke(pass, "DynamicTransforms", dynSlice);

                currentStage = "bindTexture(Sampler0)";
                mBindTexture.invoke(pass, "Sampler0", textureView, sampler);
                // Sampler2 : texture 1×1 blanche DÉDIÉE (voir ensureWhiteTexture) —
                // PAS l'atlas de police (celui-ci n'a AUCUNE garantie d'opacité au
                // texel entier (0,0), à l'intérieur du padding). UV2=(0,0) (voir
                // plus haut) garantit un texelFetch valide et opaque.
                currentStage = "bindTexture(Sampler2)";
                mBindTexture.invoke(pass, "Sampler2", whiteTextureView, whiteSampler);

                currentStage = "setVertexBuffer";
                mSetVertexBuffer.invoke(pass, 0, vbo);

                // BUG TROUVÉ (draw() "réussissait" sans exception mais AUCUN
                // texte jamais visible) : le pipeline GUI_TEXT utilise le mode
                // QUADS (voir VertexFormats.POSITION_COLOR_TEXTURE_LIGHT) —
                // draw(int,int) non-indexé ne convertit PAS les quads en
                // triangles tout seul. Il faut passer par drawIndexed() avec
                // le buffer d'indices de triangulation PARTAGÉ que Minecraft
                // utilise lui-même pour ça (RenderSystem.sharedSequentialQuad).
                currentStage = "shapeIndexBuffer";
                if (sharedSequentialQuad == null) sharedSequentialQuad = fieldSharedSequentialQuad.get(null);
                int indexCount = (vertexCount / 4) * 6; // 6 indices (2 triangles) par quad de 4 sommets
                Object indexBuffer = mShapeIndexBufferGetBuffer.invoke(sharedSequentialQuad, indexCount);
                Object indexType = mShapeIndexBufferGetType.invoke(sharedSequentialQuad);
                currentStage = "setIndexBuffer";
                mSetIndexBuffer.invoke(pass, indexBuffer, indexType);
                // BUG TROUVÉ (aucune exception, cull=true/false testés tous
                // les deux sans différence — LA vraie cause) : signature
                // RÉELLE de drawIndexed vérifiée dans les mappings Yarn
                // officiels eux-mêmes (noms de paramètres embarqués dans
                // mappings.tiny, jamais devinés) — (int baseVertex, int
                // firstIndex, int count, int instanceCount), PAS (count,
                // instanceCount, firstIndex, baseVertex) comme supposé à tort
                // (convention Vulkan/D3D typique, mais fausse ici). On
                // envoyait donc baseVertex=indexCount, firstIndex=1,
                // count=0, instanceCount=0 — ZÉRO géométrie soumise à
                // chaque appel, sans la moindre exception (des int valides,
                // juste sémantiquement absurdes).
                currentStage = "drawIndexed";
                mDrawIndexed.invoke(pass, 0, 0, indexCount, 1);
            } finally {
                // DIAGNOSTIC (v382) : sauter close() entièrement (fuite GPU
                // assumée) n'a PAS rendu le texte visible — donc close() est
                // bien nécessaire pour que draw() ait un effet réel (probable
                // sémantique "enregistrer puis soumettre au GPU à la
                // fermeture", standard pour ce type d'API) : draw() seul ne
                // suffit jamais, close() DOIT réussir. Retour à un close()
                // systématique (plus de fuite volontaire), le vrai problème
                // reste entier.
                currentStage = "closePass";
                mClosePass.invoke(pass);
            }
            return true;
        } catch (Throwable t) {
            // InvocationTargetException masque la VRAIE exception derrière
            // getCause() — logger juste "t" ne montrait jamais la cause réelle
            // (juste "java.lang.reflect.InvocationTargetException" sans détail),
            // rendant tout diagnostic impossible depuis les logs. Un seul log par
            // appel (pas de limite), acceptable : n'arrive QUE si drawText échoue
            // vraiment, jamais dans le cas nominal.
            // DIAGNOSTIC : les 5 premiers échecs sont loggés (pas juste le 1er) —
            // le tout premier échec de la session est arrivé à l'étape
            // "closePass" (draw() avait donc RÉUSSI juste avant) ; voir si les
            // échecs SUIVANTS restent à "closePass" ou basculent plus tôt (ex:
            // "createRenderPass") — ça confirmerait une pass jamais correctement
            // refermée qui bloque tous les appels suivants en cascade.
            if (failureLogCount < 5) {
                failureLogCount++;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                LauncherLog.err("[UiRenderer] UiTextBlaze3D.drawText a échoué #" + failureLogCount + " à l'étape '" + currentStage + "' : " + t + " | cause réelle : " + cause);
            }
            return false;
        }
    }

    /** POSITION(float×3) + COLOR(ubyte×4) + UV0(float×2) + UV2/light(short×2) — 28 octets, ordre EXACT vérifié par désassemblage de VertexFormats.POSITION_COLOR_TEXTURE_LIGHT. */
    private static void putVertexPCTL(ByteBuffer buf, float x, float y, int rgba, float u, float v, short light0, short light1) {
        buf.putFloat(x).putFloat(y).putFloat(0f);
        buf.put((byte) (rgba & 0xFF)).put((byte) ((rgba >> 8) & 0xFF)).put((byte) ((rgba >> 16) & 0xFF)).put((byte) ((rgba >> 24) & 0xFF));
        buf.putFloat(u).putFloat(v);
        buf.putShort(light0).putShort(light1);
    }

    private static Method mGetFramebuffer;
    private static Object getFramebuffer(Object mc) {
        try {
            if (mGetFramebuffer == null) {
                String name = MappingsRegistry.getObfMethodName("net/minecraft/client/MinecraftClient", "getFramebuffer");
                mGetFramebuffer = mc.getClass().getMethod(name);
            }
            return mGetFramebuffer.invoke(mc);
        } catch (Throwable t) {
            return null;
        }
    }
}
