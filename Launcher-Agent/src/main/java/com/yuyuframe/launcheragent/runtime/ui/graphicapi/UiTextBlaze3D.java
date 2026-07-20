package com.yuyuframe.launcheragent.runtime.ui.graphicapi;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
        mWriteToTexture, mWriteToTextureMip, mWriteToBuffer, mBufferSlice, mCreateRenderPass, mSetPipeline, mBindTexture, mSetUniformSlice,
        mSetVertexBuffer, mDraw, mClosePass, mBindDefaultUniforms, mGetDynamicUniforms,
        mDynamicUniformsWrite, mGetColorAttachmentView, mNativeImageSetColor,
        mShapeIndexBufferGetBuffer, mShapeIndexBufferGetType, mSetIndexBuffer, mDrawIndexed,
        mGetProjectionMatrixBuffer, mDisableScissor;

    private static java.lang.reflect.Field fieldSharedSequentialQuad;
    private static Object sharedSequentialQuad;

    private static Constructor<?> ctorNativeImage, ctorVector4f, ctorVector3f;

    private static Object fieldFilterModeLinear, fieldTextureFormatRgba8,
        fieldNativeImageFormatRgba, fieldRenderPipelineGuiText;

    private static int usageTextureBinding, usageTextureCopyDst, usageBufferVertex, usageBufferCopyDst, usageBufferUniform;

    private static Method mMatrixSetOrtho, mMatrixGetFloatArray;

    private static boolean resolveAttempted, resolveOk;

    /**
     * Résout une classe via Yarn si chargé (obfuscation classique,
     * comportement INCHANGÉ), SINON (bracket 26.1+, jeu non obfusqué, Yarn
     * jamais chargé — voir VersionBracketRegistry) directement par le nom
     * réel fourni — chaque nom ici vérifié par {@code javap} sur le jar
     * client 26.1.2 réel (jamais deviné par simple renommage de convention,
     * voir le commentaire de classe pour la même exigence appliquée au
     * pipeline lui-même).
     */
    private static Class<?> resolveYarnOrReal(String yarnName, String realBinaryName) {
        Class<?> c = McReflect.yarnClass(yarnName);
        if (c != null) return c;
        return McReflect.rawClass(realBinaryName);
    }

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
            // resolveYarnOrReal (pas McReflect.yarnClass seul) : sur le bracket
            // 26.1+ (Yarn jamais chargé), ces classes ont TOUTES changé de
            // package par rapport à la convention Yarn "net/minecraft/client/gl/*"
            // — confirmé par javap sur le jar client 26.1.2 réel :
            //   SamplerCache/GpuSampler  → com.mojang.blaze3d.{systems,textures}
            //   RenderPipelines/DynamicUniforms → net.minecraft.client.renderer
            //   Framebuffer → com.mojang.blaze3d.pipeline.RenderTarget (renommée aussi)
            //   NativeImage(.Format) → com.mojang.blaze3d.platform
            clsSamplerCache = resolveYarnOrReal("net/minecraft/client/gl/SamplerCache", "com.mojang.blaze3d.systems.SamplerCache");
            clsGpuSampler = resolveYarnOrReal("net/minecraft/client/gl/GpuSampler", "com.mojang.blaze3d.textures.GpuSampler");
            clsRenderPipelines = resolveYarnOrReal("net/minecraft/client/gl/RenderPipelines", "net.minecraft.client.renderer.RenderPipelines");
            clsDynamicUniforms = resolveYarnOrReal("net/minecraft/client/gl/DynamicUniforms", "net.minecraft.client.renderer.DynamicUniforms");
            clsFramebuffer = resolveYarnOrReal("net/minecraft/client/gl/Framebuffer", "com.mojang.blaze3d.pipeline.RenderTarget");
            clsNativeImage = resolveYarnOrReal("net/minecraft/client/texture/NativeImage", "com.mojang.blaze3d.platform.NativeImage");
            clsNativeImageFormat = resolveYarnOrReal("net/minecraft/client/texture/NativeImage$Format", "com.mojang.blaze3d.platform.NativeImage$Format");
            if (clsSamplerCache == null || clsGpuSampler == null || clsRenderPipelines == null
                    || clsDynamicUniforms == null || clsFramebuffer == null || clsNativeImage == null
                    || clsNativeImageFormat == null) {
                throw new ClassNotFoundException("une classe net.minecraft requise est introuvable (voir logs Yarn)");
            }

            mGetDevice = clsRenderSystem.getMethod("getDevice");
            mGetSamplerCache = clsRenderSystem.getMethod("getSamplerCache");
            mBindDefaultUniforms = clsRenderSystem.getMethod("bindDefaultUniforms", clsRenderPass);
            mGetDynamicUniforms = clsRenderSystem.getMethod("getDynamicUniforms");

            // BUG TROUVÉ (utilisateur : mipmaps générés — voir ensureTexture —
            // mais texte "toujours pixelisé", quasi aucun effet visible) :
            // désassemblage de SamplerCache.init() (pas deviné) — le
            // paramètre booléen "defaultLineOfDetail" de la surcharge
            // get(FilterMode,boolean) décide, à la construction du VRAI
            // GpuSampler (GpuDevice.createSampler(...,maxAniso,lodOption)) :
            // false → OptionalDouble.of(0.0) = LOD FIGÉ à 0 (ignore TOUS les
            // niveaux de mip, quels qu'ils soient) ; true →
            // OptionalDouble.empty() = plage de LOD dynamique COMPLÈTE
            // (sélection automatique du mip selon l'empreinte écran — ce
            // qu'on veut). La surcharge à 1 argument get(FilterMode), qu'on
            // utilisait, appelle EN INTERNE get(FilterMode, false) (confirmé
            // par désassemblage direct de son bytecode) — notre sampler
            // était donc VERROUILLÉ sur le mip 0, rendant TOUTE la chaîne de
            // mips générée totalement inutilisée par le GPU. Fix : résoudre
            // ET utiliser la surcharge à 2 arguments avec `true`.
            // 26.1+ (Yarn non chargé) : SamplerCache.get(FilterMode,boolean)
            // N'EXISTE PLUS DU TOUT — API redessinée (vérifié par javap sur
            // le jar client 26.1.2 réel) : getSampler(AddressMode,AddressMode,
            // FilterMode,FilterMode,boolean), getClampToEdge(FilterMode[,boolean]),
            // getRepeat(FilterMode[,boolean]). getClampToEdge(FilterMode,boolean)
            // est l'équivalent le plus proche pour une texture d'atlas non
            // répétée (même forme FilterMode+boolean, sémantique "clamp"
            // cohérente avec un atlas 2D) — INFÉRÉ par correspondance de
            // forme/nom, PAS vérifié comportementalement (le booléen
            // active-t-il bien la même plage de LOD dynamique complète que
            // sur l'ancienne API ? à confirmer en jeu, voir le BUG TROUVÉ
            // juste au-dessus pour l'enjeu si jamais ce n'était pas le cas).
            if (MappingsRegistry.isLoaded()) {
                String getSamplerName = MappingsRegistry.getObfMethodName(
                    "net/minecraft/client/gl/SamplerCache", "get", "(Lcom/mojang/blaze3d/textures/FilterMode;Z)Lfzf;");
                mSamplerCacheGet = clsSamplerCache.getMethod(getSamplerName, clsFilterMode, boolean.class);
            } else {
                mSamplerCacheGet = clsSamplerCache.getMethod("getClampToEdge", clsFilterMode, boolean.class);
            }

            mGetProjectionMatrixBuffer = clsRenderSystem.getMethod("getProjectionMatrixBuffer");

            mCreateCommandEncoder = clsGpuDevice.getMethod("createCommandEncoder");
            mCreateTexture = clsGpuDevice.getMethod("createTexture",
                java.util.function.Supplier.class, int.class, clsTextureFormat, int.class, int.class, int.class, int.class);
            mCreateTextureView = clsGpuDevice.getMethod("createTextureView", clsGpuTexture);
            mCreateBuffer = clsGpuDevice.getMethod("createBuffer", java.util.function.Supplier.class, int.class, ByteBuffer.class);
            mCreateBufferSized = clsGpuDevice.getMethod("createBuffer", java.util.function.Supplier.class, int.class, long.class);
            mBufferSlice = clsGpuBuffer.getMethod("slice", long.class, long.class);

            mWriteToTexture = clsCommandEncoder.getMethod("writeToTexture", clsGpuTexture, clsNativeImage);
            // Variante détaillée (mipLevel explicite) — vérifiée dans les
            // mappings Yarn officiels (pas devinée) : writeToTexture(target,
            // source, mipLevel, depth, offsetX, offsetY, width, height,
            // skipPixels, skipRows). Nécessaire pour uploader les niveaux de
            // mipmap de l'atlas de police (voir BUG TROUVÉ dans ensureTexture).
            mWriteToTextureMip = clsCommandEncoder.getMethod("writeToTexture", clsGpuTexture, clsNativeImage,
                int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class);
            mWriteToBuffer = clsCommandEncoder.getMethod("writeToBuffer", clsGpuBufferSlice, ByteBuffer.class);
            mCreateRenderPass = clsCommandEncoder.getMethod("createRenderPass",
                java.util.function.Supplier.class, clsGpuTextureView, OptionalInt.class);

            mSetPipeline = clsRenderPass.getMethod("setPipeline", clsRenderPipeline);
            mBindTexture = clsRenderPass.getMethod("bindTexture", String.class, clsGpuTextureView, clsGpuSampler);
            mSetUniformSlice = clsRenderPass.getMethod("setUniform", String.class, clsGpuBufferSlice);
            mSetVertexBuffer = clsRenderPass.getMethod("setVertexBuffer", int.class, clsGpuBuffer);
            mDraw = clsRenderPass.getMethod("draw", int.class, int.class);
            mClosePass = clsRenderPass.getMethod("close");
            // DIAGNOSTIC (26.1+, tout le reste — pipeline/format/coords/couleur —
            // vérifié sain sans expliquer l'invisibilité totale) : RenderPass
            // expose désormais enableScissor(IIII)/disableScissor() (absents de
            // l'ancien bracket) — si le scissor par défaut d'une passe fraîche
            // n'est plus "plein cadre" sur ce backend, tout serait découpé à
            // zéro sans la moindre exception. Appelé explicitement (optionnel :
            // null si absent, degrade proprement) juste après setPipeline.
            try {
                mDisableScissor = clsRenderPass.getMethod("disableScissor");
            } catch (NoSuchMethodException noScissorApi) {
                mDisableScissor = null;
            }

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
            // Champ déclaré directement sur RenderSystem (classe de PREMIER
            // NIVEAU, stable/inchangée dans les 3 espaces de noms — voir
            // commentaire ci-dessus) : nom littéral direct, pas de remapping
            // nécessaire ici (contrairement aux classes imbriquées).
            fieldSharedSequentialQuad = clsRenderSystem.getDeclaredField("sharedSequentialQuad");
            fieldSharedSequentialQuad.setAccessible(true);

            Class<?> clsShapeIndexBuffer;
            Class<?> clsIndexType;
            if (MappingsRegistry.isLoaded()) {
                // Mêmes méthodes que désassemblées ("b(int)"/"a()", noms OFFICIELS
                // bruts) — mais ce sont des méthodes DÉCLARÉES SUR une classe
                // imbriquée elle-même remappée : leurs noms le sont probablement
                // AUSSI sous Fabric (pas juste le nom de la classe conteneuse).
                // Résolus via runtimeMethod() (official→runtime), même mécanisme
                // que runtimeClass() ci-dessus.
                clsShapeIndexBuffer = Class.forName(
                    MappingsRegistry.runtimeClass("com/mojang/blaze3d/systems/RenderSystem$a"), false, clsRenderPass.getClassLoader());
                String getBufferRuntime = MappingsRegistry.runtimeMethod(
                    "com/mojang/blaze3d/systems/RenderSystem$a", "b", "(I)Lcom/mojang/blaze3d/buffers/GpuBuffer;");
                String getTypeRuntime = MappingsRegistry.runtimeMethod(
                    "com/mojang/blaze3d/systems/RenderSystem$a", "a", "()Lcom/mojang/blaze3d/vertex/VertexFormat$a;");
                mShapeIndexBufferGetBuffer = clsShapeIndexBuffer.getMethod(getBufferRuntime, int.class); // getIndexBuffer(int) — assure la capacité et renvoie le GpuBuffer
                mShapeIndexBufferGetType = clsShapeIndexBuffer.getMethod(getTypeRuntime); // getIndexType() -> VertexFormat.IndexType
                clsIndexType = Class.forName(
                    MappingsRegistry.runtimeClass("com/mojang/blaze3d/vertex/VertexFormat$a"), false, clsRenderPass.getClassLoader());
            } else {
                // 26.1+ : "RenderSystem$a"/"VertexFormat$a" sont des noms
                // OBFUSQUÉS 1.21.11, sans aucun sens pour un jeu non obfusqué
                // (aucune classe de ce nom n'existe). Plutôt que deviner le
                // VRAI nom de la classe imbriquée (confirmé par javap :
                // "RenderSystem$AutoStorageIndexBuffer", mais ce nom n'est en
                // rien garanti stable version à version), on le DÉRIVE
                // directement du champ déjà résolu ci-dessus par son nom
                // littéral stable "sharedSequentialQuad" — fonctionne quel
                // que soit le nom réel de la classe imbriquée, aujourd'hui ET
                // sur une future version. Méthodes "getBuffer(int)"/"type()"
                // confirmées par javap (noms réels stables, pas obfusqués).
                clsShapeIndexBuffer = fieldSharedSequentialQuad.getType();
                mShapeIndexBufferGetBuffer = clsShapeIndexBuffer.getMethod("getBuffer", int.class);
                mShapeIndexBufferGetType = clsShapeIndexBuffer.getMethod("type");
                clsIndexType = mShapeIndexBufferGetType.getReturnType();
            }
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

            // 26.1+ : Framebuffer.getColorAttachmentView() renommée
            // getColorTextureView() sur RenderTarget (vérifié par javap sur
            // le jar client 26.1.2 réel — même méthode, no-arg, retourne
            // GpuTextureView).
            mGetColorAttachmentView = MappingsRegistry.isLoaded()
                ? clsFramebuffer.getMethod(MappingsRegistry.getObfMethodName("net/minecraft/client/gl/Framebuffer", "getColorAttachmentView"))
                : clsFramebuffer.getMethod("getColorTextureView");

            // Même chemin, déjà éprouvé, que UiRenderer.ensureNativeTextureApiResolved()
            // (createFontTextureViaNativeImage) — constructeur (Format,int,int,boolean)
            // et méthode "setColor" (nom Yarn stable, PAS "setColorArgb").
            ctorNativeImage = clsNativeImage.getDeclaredConstructor(clsNativeImageFormat, int.class, int.class, boolean.class);
            ctorNativeImage.setAccessible(true);
            // 26.1+ : NativeImage.setColor(int,int,int) N'EXISTE PLUS (vérifié
            // par javap — absent de la classe entière) — remplacée par
            // setPixel(int,int,int) ET setPixelABGR(int,int,int), deux
            // conventions d'octets différentes. PAS une supposition : le
            // format ABGR packé construit plus bas
            // (bufferedImageToNativeImage : "(a<<24)|(b<<16)|(g<<8)|r") est
            // EXACTEMENT ce que le nom "setPixelABGR" décrit — confirmé par
            // le nom de la méthode elle-même, pas deviné par élimination.
            mNativeImageSetColor = MappingsRegistry.isLoaded()
                ? McReflect.method(clsNativeImage, "net/minecraft/client/texture/NativeImage", "setColor", int.class, int.class, int.class)
                : clsNativeImage.getMethod("setPixelABGR", int.class, int.class, int.class);

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
                    || mShapeIndexBufferGetType == null || mSetIndexBuffer == null || mDrawIndexed == null
                    || mWriteToTextureMip == null) {
                throw new NoSuchMethodException("setColor/RGBA/GUI_TEXT/sharedSequentialQuad/writeToTextureMip introuvable (voir logs)");
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
        Object nativeImage = bufferedImageToNativeImage(img, w, h);

        // BUG TROUVÉ (utilisateur : texte des cartes/titres "un peu pixelisé")
        // : texture créée avec UN SEUL niveau de mip (dernier paramètre de
        // createTexture = mipLevels, vérifié dans les mappings Yarn
        // officiels — pas deviné). La plupart des tailles de texte de l'UI
        // (échelle ~0.4-0.55) minifient FORTEMENT depuis la résolution native
        // de l'atlas (RASTER_PX=64) — un filtrage LINEAR sans mipmap ne peut
        // pas moyenner assez de texels source lors d'une réduction de cette
        // ampleur, d'où l'aliasing visible. Fix : chaîne de mipmaps complète,
        // chaque niveau généré par downscale bilinéaire Java2D DEPUIS
        // L'IMAGE PLEINE RÉSOLUTION (jamais mip-sur-mip, pour éviter
        // d'accumuler l'erreur de filtrage), uploadée via la variante
        // détaillée de writeToTexture (mipLevel explicite).
        // BUG TROUVÉ (utilisateur, une fois le LOD dynamique activé : "c'est
        // encore pire") : générer des mipmaps sur l'ATLAS ENTIER (pas
        // glyphe par glyphe) fait rétrécir le padding inter-glyphes
        // (UiFont.ATLAS_PADDING=16px, à RASTER_PX) PROPORTIONNELLEMENT à
        // chaque niveau — exactement le risque déjà anticipé dans le
        // commentaire d'origine de UiFont ("un mipmap ... pourrait mélanger
        // deux glyphes différents"), jamais respecté ici : 6 niveaux (÷64)
        // réduisent 16px de marge à 0.25px — bien EN DESSOUS du rayon de
        // flou d'un filtrage bilinéaire, les glyphes voisins se mélangent
        // dans les mips grossiers. Avec le LOD figé à 0 (avant le fix
        // précédent), ces mips corrompus n'étaient JAMAIS échantillonnés —
        // d'où "aucun effet" ; avec le LOD dynamique désormais actif, le
        // petit texte sélectionne justement CES mips corrompus, d'où "pire
        // qu'avant". Fix : arrêter de générer des niveaux dès que le
        // padding restant descend sous un seuil de sécurité (marge pour le
        // flou bilinéaire + tampon).
        int mipLevels = 1;
        {
            int mw = w, mh = h;
            float paddingAtLevel = UiFont.ATLAS_PADDING;
            final float MIN_SAFE_PADDING = 3f;
            while (mw > 4 && mh > 4 && mipLevels < 6) {
                float nextPadding = paddingAtLevel / 2f;
                if (nextPadding < MIN_SAFE_PADDING) break;
                mw /= 2; mh /= 2; paddingAtLevel = nextPadding; mipLevels++;
            }
        }

        Object device = mGetDevice.invoke(null);
        final String label = "yuyuframe_font_" + System.identityHashCode(font);
        java.util.function.Supplier<String> labelSupplier = () -> label;
        Object texture = mCreateTexture.invoke(device, labelSupplier, usageTextureBinding | usageTextureCopyDst, fieldTextureFormatRgba8, w, h, 1, mipLevels);

        Object encoder = mCreateCommandEncoder.invoke(device);
        mWriteToTexture.invoke(encoder, texture, nativeImage); // mip 0 (résolution native)

        for (int level = 1; level < mipLevels; level++) {
            int mw = Math.max(1, w >> level), mh = Math.max(1, h >> level);
            BufferedImage scaled = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(img, 0, 0, mw, mh, null);
            g2.dispose();
            Object mipImage = bufferedImageToNativeImage(scaled, mw, mh);
            mWriteToTextureMip.invoke(encoder, texture, mipImage, level, 0, 0, 0, mw, mh, 0, 0);
        }

        Object textureView = mCreateTextureView.invoke(device, texture);
        Object sampler = mSamplerCacheGet.invoke(mGetSamplerCache.invoke(null), fieldFilterModeLinear, true);

        Object[] result = {texture, textureView, sampler};
        TEXTURES.put(font, result);
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: atlas '" + label + "' créé via GpuDevice.createTexture (w=" + w + " h=" + h + " mipLevels=" + mipLevels + ")");
        return result;
    }

    /** ARGB (BufferedImage) → RGBA petit-boutiste (NativeImage) — même conversion que ci-dessus, factorisée pour être réutilisée par chaque niveau de mip. */
    private static Object bufferedImageToNativeImage(BufferedImage img, int w, int h) throws Exception {
        Object nativeImage = ctorNativeImage.newInstance(fieldNativeImageFormatRgba, w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                int nativeColor = (a << 24) | (b << 16) | (g << 8) | r;
                mNativeImageSetColor.invoke(nativeImage, x, y, nativeColor);
            }
        }
        return nativeImage;
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
        Object sampler = mSamplerCacheGet.invoke(mGetSamplerCache.invoke(null), fieldFilterModeLinear, true);
        whiteTexture = new Object[]{texture, textureView, sampler};
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: texture blanche 1x1 (Sampler2) créée");
        return whiteTexture;
    }

    // ── Texture "masque coin arrondi" (fonds de panneau HUD, voir drawRect) ──

    // BUG TROUVÉ (retour utilisateur : "les arrondis des cards sont
    // pixelisés" PUIS, sur les tout petits rayons (badge cœur), "la courbe
    // en 180° est mal calculée") — cette texture représentait un coin de
    // rayon FIXE (128 texels), mappée PAR UV COMPLET (0..1) sur le coin
    // RÉELLEMENT dessiné (voir putRectQuad) dont le rayon écran va de ~2px
    // (badge cœur) à ~16px (cartes) — un facteur de réduction allant jusqu'à
    // ~35× à l'affichage. Un sampler LINEAR (pas de mipmap) ne peut pas
    // réduire une texture 128px vers un quad de 2-3px proprement, quelle que
    // soit sa résolution source : le problème n'est pas la texture, c'est
    // l'écart entre sa taille et celle réellement utilisée au rendu.
    //
    // Fix : UN masque PAR RAYON ÉCRAN (arrondi au pixel, mis en cache),
    // généré à une taille PROCHE du rayon réel (léger sur-échantillonnage
    // ×3 pour l'antialiasing, jamais plus de {@link #CORNER_MASK_SIZE}) au
    // lieu d'une texture fixe toujours réduite à l'extrême — le facteur de
    // réduction au rendu reste alors proche de 1:1 à 3:1 quel que soit le
    // rayon, au lieu de jusqu'à 35:1. La bande de lissage (voir smoothstep
    // ci-dessous) est recalculée PROPORTIONNELLEMENT à CHAQUE taille de
    // masque (plus une fraction fixe d'une texture 128px sans rapport avec
    // le rayon réel) — c'est ce qui corrige le "180° mal calculé" sur les
    // petits rayons (la bande ne peut plus finir par couvrir tout le coin).
    private static final int CORNER_MASK_SIZE = 128;
    private static final Map<Integer, Object[]> cornerMaskCache = new HashMap<>();

    /**
     * Alpha = 1 (opaque) là où texel(tx,ty) est à distance <= N du point
     * "intérieur" (N,N) (coin bas-droite du carré NxN, voir drawRect pour la
     * correspondance écran) ; alpha = 0 au-delà, avec un lissage
     * (smoothstep) sur une bande proportionnelle à N — pré-calculée dans une
     * texture au lieu d'un shader dédié (RenderPipelines.GUI_TEXT n'en a
     * pas) : image = "un coin haut-gauche arrondi" — les 3 autres coins sont
     * obtenus par retournement UV (voir putRectQuad), pas 4 textures séparées.
     *
     * @param screenRadiusPx rayon écran RÉEL (en pixels) du coin sur le
     *                        point d'appeler — détermine la taille (et donc
     *                        la clé de cache) du masque généré, voir
     *                        commentaire de classe ci-dessus.
     */
    private static Object[] ensureCornerMaskTexture(float screenRadiusPx) throws Exception {
        int bucket = Math.max(1, Math.round(screenRadiusPx));
        Object[] cached = cornerMaskCache.get(bucket);
        if (cached != null) return cached;

        // ×3 : reste net (pas de flou d'agrandissement) tout en gardant un
        // facteur de réduction modéré au rendu — 8 = plancher (même un
        // rayon de 1px garde une bande de lissage exploitable) ; jamais plus
        // que CORNER_MASK_SIZE (128, valeur d'origine — largement assez pour
        // les gros rayons, donc AUCUN changement de qualité par rapport à
        // avant sur ce cas, qui n'a jamais posé problème).
        int n = Math.max(8, Math.min(CORNER_MASK_SIZE, bucket * 3));
        float falloffTexels = Math.max(1.5f, n / 10f);

        Object nativeImage = ctorNativeImage.newInstance(fieldNativeImageFormatRgba, n, n, false);
        for (int ty = 0; ty < n; ty++) {
            for (int tx = 0; tx < n; tx++) {
                float dx = tx - n, dy = ty - n;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float t = Math.max(0f, Math.min(1f, (dist - (n - falloffTexels)) / falloffTexels));
                float alpha = 1f - (t * t * (3f - 2f * t)); // smoothstep
                int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
                int nativeColor = (a << 24) | 0x00FFFFFF; // petit-boutiste RGBA — blanc, alpha calculé
                mNativeImageSetColor.invoke(nativeImage, tx, ty, nativeColor);
            }
        }
        Object device = mGetDevice.invoke(null);
        java.util.function.Supplier<String> label = () -> "yuyuframe_corner_mask_" + bucket;
        Object texture = mCreateTexture.invoke(device, label, usageTextureBinding | usageTextureCopyDst, fieldTextureFormatRgba8, n, n, 1, 1);
        Object encoder = mCreateCommandEncoder.invoke(device);
        mWriteToTexture.invoke(encoder, texture, nativeImage);
        Object textureView = mCreateTextureView.invoke(device, texture);
        Object sampler = mSamplerCacheGet.invoke(mGetSamplerCache.invoke(null), fieldFilterModeLinear, true);
        Object[] result = {texture, textureView, sampler};
        cornerMaskCache.put(bucket, result);
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: texture masque coin arrondi créée (rayon=" + bucket + "px, taille=" + n + "x" + n + ")");
        return result;
    }

    // ── Textures d'icônes arbitraires (pastilles de mod/pack, une par cacheKey) ──
    // Même mécanisme que ensureTexture (police) — image RGBA quelconque au lieu
    // d'un atlas de glyphes, jamais de mip (icônes 32-96px, jamais minifiées
    // aussi violemment que le texte le plus petit de l'UI qui a motivé les mips).

    private static final Map<String, Object[]> ICON_TEXTURES = new HashMap<>(); // cacheKey -> [GpuTexture, GpuTextureView, GpuSampler]

    private static Object[] ensureIconTexture(String cacheKey, BufferedImage img) throws Exception {
        Object[] cached = ICON_TEXTURES.get(cacheKey);
        if (cached != null) return cached;

        int w = img.getWidth(), h = img.getHeight();
        Object nativeImage = bufferedImageToNativeImage(img, w, h);

        Object device = mGetDevice.invoke(null);
        final String label = "yuyuframe_icon_" + cacheKey;
        java.util.function.Supplier<String> labelSupplier = () -> label;
        Object texture = mCreateTexture.invoke(device, labelSupplier, usageTextureBinding | usageTextureCopyDst, fieldTextureFormatRgba8, w, h, 1, 1);
        Object encoder = mCreateCommandEncoder.invoke(device);
        mWriteToTexture.invoke(encoder, texture, nativeImage);
        Object textureView = mCreateTextureView.invoke(device, texture);
        Object sampler = mSamplerCacheGet.invoke(mGetSamplerCache.invoke(null), fieldFilterModeLinear, true);

        Object[] result = {texture, textureView, sampler};
        ICON_TEXTURES.put(cacheKey, result);
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: icône '" + cacheKey + "' créée (w=" + w + " h=" + h + ")");
        return result;
    }

    // ── Buffer de sommets persistant (un seul, partagé, jamais recréé sauf agrandissement) ──

    private static Object vertexBuffer;
    private static long vertexBufferCapacity;

    // ── Buffer de sommets CÔTÉ CPU (staging) PERSISTANT — même esprit que
    // ensureVertexBuffer juste en dessous (côté GPU), pour le buffer côté
    // CPU qu'on remplit AVANT de l'y copier. AUDIT PERF (demandé
    // explicitement par l'utilisateur, "gratter des fps 26.1.2") :
    // ByteBuffer.allocateDirect(...) était appelé À CHAQUE drawText/drawRect/
    // drawIcon/drawGradientRect — CHAQUE chaîne de texte ET CHAQUE rectangle
    // dessinés CHAQUE FRAME (tout le HUD, même menu fermé) allouaient un
    // NOUVEAU buffer direct (mémoire native hors-tas, PAS un objet Java
    // ordinaire) — pas aussi grave que le bug déjà corrigé plus haut (VBO GPU
    // recréé/détruit à chaque appel, 12 FPS constatés), mais même famille de
    // problème à plus petite échelle : de l'ordre de 10-30+ allocations
    // natives par frame rien que pour le HUD (une par ligne de texte, une par
    // fond de panneau...), strictement inutiles puisque le contenu est
    // entièrement RÉÉCRIT (jamais lu entre deux appels) et la taille needed
    // ne dépasse quasiment jamais celle de l'appel précédent. Un seul buffer
    // direct partagé, agrandi seulement quand nécessaire (jamais réduit,
    // jamais libéré entre deux appels), vidé (clear()) avant chaque
    // réécriture — élimine ces allocations sans changer le contenu écrit.
    private static ByteBuffer stagingBuffer;
    private static int stagingBufferCapacity;

    private static ByteBuffer ensureStagingBuffer(int neededBytes) {
        if (stagingBuffer != null && neededBytes <= stagingBufferCapacity) {
            stagingBuffer.clear();
            return stagingBuffer;
        }
        // Même croissance généreuse (x2 + marge) que ensureVertexBuffer —
        // évite de réallouer à chaque légère variation de longueur de texte.
        int newCapacity = Math.max(4096, Math.max(neededBytes, stagingBufferCapacity * 2));
        stagingBuffer = ByteBuffer.allocateDirect(newCapacity).order(java.nio.ByteOrder.nativeOrder());
        stagingBufferCapacity = newCapacity;
        return stagingBuffer;
    }

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
    // Empile n'importe quel type de dessin (texte OU rectangle, voir
    // queueRect ci-dessous) dans UN SEUL ordre d'insertion — flushQueued()
    // les exécute dans CET ordre, ce qui garantit le bon z-order (un fond de
    // panneau, empilé avant son texte par HudPanelRenderer.draw(), est donc
    // TOUJOURS dessiné en premier, exactement comme l'ancien pipeline
    // immédiat) sans avoir besoin de deux files séparées.
    private interface QueuedDraw { void execute(); }

    private static final java.util.List<QueuedDraw> queued = new java.util.ArrayList<>();

    /** Appelé depuis {@code UiRenderer.drawTextModern} — empile au lieu de dessiner immédiatement, voir commentaire ci-dessus. */
    public static void queueDraw(UiFont font, String text, float x, float y, UiColor color, float scale, int vpWidth, int vpHeight) {
        if (!isAvailable() || text == null || text.isEmpty()) return;
        queued.add(() -> drawText(font, text, x, y, color, scale, vpWidth, vpHeight));
    }

    /**
     * Appelé depuis {@code UiRenderer.drawRoundedRectHud} — même file que le
     * texte (voir plus haut), pour un ordre de composition GARANTI correct :
     * fond DERRIÈRE, texte DEVANT, exactement l'ordre d'appel d'origine, au
     * lieu du GL brut (TAIL) qui composait TOUJOURS par-dessus le texte déjà
     * présenté (HEAD), assombrissant le texte sous un fond semi-transparent.
     */
    public static void queueRect(float x0, float y0, float x1, float y1, float radius, UiColor color, int vpWidth, int vpHeight) {
        if (!isAvailable()) return;
        queued.add(() -> drawRect(x0, y0, x1, y1, radius, color, vpWidth, vpHeight));
    }

    /**
     * Empile une icône RGBA quelconque (pastille de mod/pack) — même file que
     * texte/rect (voir plus haut), même garantie de z-order. {@code img} est
     * DÉCODÉE/REDIMENSIONNÉE par l'appelant (voir UiRenderer.drawIcon) ;
     * {@code cacheKey} identifie la TEXTURE GPU (créée une seule fois, voir
     * ensureIconTexture) — jamais l'image Java elle-même, qui peut être
     * regénérée/re-décodée sans repayer le coût GPU si la clé ne change pas.
     */
    public static void queueIcon(String cacheKey, BufferedImage img, float x0, float y0, float x1, float y1, int vpWidth, int vpHeight) {
        queueIcon(cacheKey, img, x0, y0, x1, y1, 1f, vpWidth, vpHeight);
    }

    /**
     * Variante avec opacité — voir {@code UiRenderer#drawIcon(..., alpha, ...)}
     * pour le pourquoi (fondu d'entrée sur contenu asynchrone). {@code alpha}
     * remplace le 4ᵉ composant du ColorModulator (voir {@link #drawIcon}),
     * resté fixe à 1.0 partout ailleurs (couleurs réelles de l'image
     * inchangées, seule l'opacité globale varie).
     */
    public static void queueIcon(String cacheKey, BufferedImage img, float x0, float y0, float x1, float y1, float alpha, int vpWidth, int vpHeight) {
        if (!isAvailable() || img == null) return;
        queued.add(() -> drawIcon(cacheKey, img, x0, y0, x1, y1, alpha, vpWidth, vpHeight));
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
        queued.add(() -> drawGradientRect(x0, y0, x1, y1, radius, colorBottom, colorTop, vpWidth, vpHeight));
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
        queued.add(() -> drawGradientRect2D(x0, y0, x1, y1, radius, colorBottomLeft, colorBottomRight, colorTopLeft, colorTopRight, vpWidth, vpHeight));
    }

    /** Appelé depuis {@code GlobalUiPresentMixin} à la HEAD de blitToScreen (avant presentTexture) — dessine tout ce qui a été empilé la frame précédente. */
    public static void flushQueued() {
        if (queued.isEmpty()) return;
        // Copie + clear immédiat : si un dessin relance une exception, on ne
        // rejoue jamais indéfiniment le même lot en boucle.
        QueuedDraw[] batch = queued.toArray(new QueuedDraw[0]);
        queued.clear();
        for (QueuedDraw q : batch) q.execute();
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

            ByteBuffer verts = ensureStagingBuffer(text.length() * 4 * 28);
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
                if (mDisableScissor != null) { currentStage = "disableScissor"; mDisableScissor.invoke(pass); }
                currentStage = "bindDefaultUniforms";
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

    // ── Rectangle arrondi (fond de panneau HUD) — MÊME pipeline GUI_TEXT que
    // le texte, réutilise TOUT (device/encoder/pass/projection/DynamicTransforms/
    // buffer de sommets/index partagé) — seule la texture Sampler0 change
    // (masque de coin, voir ensureCornerMaskTexture) et la géométrie (9 quads
    // "9-slice" au lieu de 4 sommets par glyphe).
    private static boolean drawRect(float x0, float y0, float x1, float y1, float radius, UiColor color, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve()) return false;
        try {
            currentStage = "minecraftClient(rect)";
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            currentStage = "getFramebuffer(rect)";
            Object fb = getFramebuffer(mc);
            if (fb == null || mGetColorAttachmentView == null) return false;
            currentStage = "getColorAttachmentView(rect)";
            Object colorView = mGetColorAttachmentView.invoke(fb);
            if (colorView == null) return false;

            // Rayon jamais plus grand que la moitié du plus petit côté —
            // sinon les 4 coins se chevaucheraient (quads dégénérés/inversés).
            // Calculé AVANT ensureCornerMaskTexture (contrairement à avant) :
            // le masque est désormais dimensionné selon le rayon RÉEL, voir
            // son commentaire de classe.
            float r = Math.max(0f, Math.min(radius, Math.min((x1 - x0) / 2f, (y1 - y0) / 2f)));

            currentStage = "ensureCornerMaskTexture";
            Object[] mask = ensureCornerMaskTexture(r);
            Object maskView = mask[1], maskSampler = mask[2];
            currentStage = "ensureWhiteTexture(rect)";
            Object[] white = ensureWhiteTexture();

            currentStage = "getDevice(rect)";
            Object device = mGetDevice.invoke(null);
            currentStage = "createCommandEncoder(rect)";
            Object encoder = mCreateCommandEncoder.invoke(device);

            int rgba = 0xFFFFFFFF; // couleur réelle appliquée via DynamicTransforms/ColorModulator, comme le texte
            short light0 = 0, light1 = 0;

            ByteBuffer verts = ensureStagingBuffer(9 * 4 * 28);
            int vertexCount;
            if (r < 0.5f) {
                putSolidQuad(verts, x0, x1, y0, y1, rgba, light0, light1);
                vertexCount = 4;
            } else {
                // 4 coins — texture du masque, UV variable (retournée par coin).
                putRectQuad(verts, x0, x0 + r, y0, y0 + r, false, false, rgba, light0, light1); // bas-gauche
                putRectQuad(verts, x1 - r, x1, y0, y0 + r, true, false, rgba, light0, light1);  // bas-droite
                putRectQuad(verts, x0, x0 + r, y1 - r, y1, false, true, rgba, light0, light1);  // haut-gauche
                putRectQuad(verts, x1 - r, x1, y1 - r, y1, true, true, rgba, light0, light1);   // haut-droite
                // 4 bords + centre — MÊME texture (masque), UV constante loin
                // du bord (toujours opaque) : pas besoin d'un second binding
                // Sampler0/pass séparé pour une texture blanche unie.
                putSolidQuad(verts, x0 + r, x1 - r, y0, y0 + r, rgba, light0, light1);   // bas
                putSolidQuad(verts, x0 + r, x1 - r, y1 - r, y1, rgba, light0, light1);   // haut
                putSolidQuad(verts, x0, x0 + r, y0 + r, y1 - r, rgba, light0, light1);   // gauche
                putSolidQuad(verts, x1 - r, x1, y0 + r, y1 - r, rgba, light0, light1);   // droite
                putSolidQuad(verts, x0 + r, x1 - r, y0 + r, y1 - r, rgba, light0, light1); // centre
                vertexCount = 9 * 4;
            }
            verts.flip();

            currentStage = "ensureVertexBuffer(rect)";
            Object vbo = ensureVertexBuffer(device, verts.remaining());
            currentStage = "bufferSlice(rect)";
            Object slice = mBufferSlice.invoke(vbo, 0L, (long) verts.remaining());
            currentStage = "writeToBuffer(rect)";
            mWriteToBuffer.invoke(encoder, slice, verts);

            currentStage = "dynamicUniformsWrite(rect)";
            Object identity4 = clsMatrix4f.getConstructor().newInstance();
            Object colorMod = ctorVector4f.newInstance(color.r, color.g, color.b, color.a);
            Object zero3 = ctorVector3f.newInstance(0f, 0f, 0f);
            Object dynUniforms = mGetDynamicUniforms.invoke(null);
            Object dynSlice = mDynamicUniformsWrite.invoke(dynUniforms, identity4, colorMod, zero3, identity4);

            currentStage = "ensureProjectionBuffer(rect)";
            Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
            Object projectionSlice = mBufferSlice.invoke(projectionBuf, 0L, 64L);

            currentStage = "createRenderPass(rect)";
            java.util.function.Supplier<String> passLabel = () -> "yuyuframe_rect";
            Object pass = mCreateRenderPass.invoke(encoder, passLabel, colorView, OptionalInt.empty());
            try {
                currentStage = "setPipeline(rect)";
                mSetPipeline.invoke(pass, fieldRenderPipelineGuiText);
                if (mDisableScissor != null) { currentStage = "disableScissor(rect)"; mDisableScissor.invoke(pass); }
                currentStage = "bindDefaultUniforms(rect)";
                mBindDefaultUniforms.invoke(null, pass);
                currentStage = "setUniform(Projection)(rect)";
                mSetUniformSlice.invoke(pass, "Projection", projectionSlice);
                currentStage = "setUniform(DynamicTransforms)(rect)";
                mSetUniformSlice.invoke(pass, "DynamicTransforms", dynSlice);
                currentStage = "bindTexture(Sampler0)(rect)";
                mBindTexture.invoke(pass, "Sampler0", maskView, maskSampler);
                currentStage = "bindTexture(Sampler2)(rect)";
                mBindTexture.invoke(pass, "Sampler2", white[1], white[2]);
                currentStage = "setVertexBuffer(rect)";
                mSetVertexBuffer.invoke(pass, 0, vbo);

                currentStage = "shapeIndexBuffer(rect)";
                if (sharedSequentialQuad == null) sharedSequentialQuad = fieldSharedSequentialQuad.get(null);
                int indexCount = (vertexCount / 4) * 6;
                Object indexBuffer = mShapeIndexBufferGetBuffer.invoke(sharedSequentialQuad, indexCount);
                Object indexType = mShapeIndexBufferGetType.invoke(sharedSequentialQuad);
                currentStage = "setIndexBuffer(rect)";
                mSetIndexBuffer.invoke(pass, indexBuffer, indexType);
                currentStage = "drawIndexed(rect)";
                mDrawIndexed.invoke(pass, 0, 0, indexCount, 1);
            } finally {
                currentStage = "closePass(rect)";
                mClosePass.invoke(pass);
            }
            return true;
        } catch (Throwable t) {
            if (failureLogCount < 5) {
                failureLogCount++;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                LauncherLog.err("[UiRenderer] UiTextBlaze3D.drawRect a échoué #" + failureLogCount + " à l'étape '" + currentStage + "' : " + t + " | cause réelle : " + cause);
            }
            return false;
        }
    }

    // ── Icône RGBA quelconque (pastille de mod/pack) — MÊME structure que
    // drawRect, mais un SEUL quad plein (pas de 9-slice/coins arrondis — une
    // icône rectangulaire simple) échantillonnant la VRAIE texture de
    // l'icône (Sampler0), pas le masque de coin. ColorModulator reste blanc,
    // seul son alpha varie (paramètre {@code alpha} — fondu d'entrée sur
    // contenu asynchrone) : les couleurs RGB réelles de l'image sont
    // préservées telles quelles, comme pour un rect à dégradé (couleur
    // portée par la texture ici, pas par sommet).
    private static boolean drawIcon(String cacheKey, BufferedImage img, float x0, float y0, float x1, float y1, float alpha, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve()) return false;
        try {
            currentStage = "minecraftClient(icon)";
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            currentStage = "getFramebuffer(icon)";
            Object fb = getFramebuffer(mc);
            if (fb == null || mGetColorAttachmentView == null) return false;
            currentStage = "getColorAttachmentView(icon)";
            Object colorView = mGetColorAttachmentView.invoke(fb);
            if (colorView == null) return false;

            currentStage = "ensureIconTexture";
            Object[] icon = ensureIconTexture(cacheKey, img);
            Object iconView = icon[1], iconSampler = icon[2];
            currentStage = "ensureWhiteTexture(icon)";
            Object[] white = ensureWhiteTexture();

            currentStage = "getDevice(icon)";
            Object device = mGetDevice.invoke(null);
            currentStage = "createCommandEncoder(icon)";
            Object encoder = mCreateCommandEncoder.invoke(device);

            int rgba = 0xFFFFFFFF;
            short light0 = 0, light1 = 0;
            ByteBuffer verts = ensureStagingBuffer(4 * 28);
            // UV pleine image (0,0)-(1,1) — même correspondance top/bottom↔v0/v1
            // que le texte (yTop↔v0 haut de l'image, yBottom↔v1 bas), voir
            // putVertexPCTL/putRectQuad pour la convention Y-UP déjà établie.
            putVertexPCTL(verts, x0, y1, rgba, 0f, 0f, light0, light1);
            putVertexPCTL(verts, x0, y0, rgba, 0f, 1f, light0, light1);
            putVertexPCTL(verts, x1, y0, rgba, 1f, 1f, light0, light1);
            putVertexPCTL(verts, x1, y1, rgba, 1f, 0f, light0, light1);
            verts.flip();

            currentStage = "ensureVertexBuffer(icon)";
            Object vbo = ensureVertexBuffer(device, verts.remaining());
            currentStage = "bufferSlice(icon)";
            Object slice = mBufferSlice.invoke(vbo, 0L, (long) verts.remaining());
            currentStage = "writeToBuffer(icon)";
            mWriteToBuffer.invoke(encoder, slice, verts);

            currentStage = "dynamicUniformsWrite(icon)";
            Object identity4 = clsMatrix4f.getConstructor().newInstance();
            // RGB pass-through (vraies couleurs de l'image) — seul le composant
            // alpha varie (voir queueIcon(..., alpha, ...) / UiRenderer#drawIcon).
            Object white4 = ctorVector4f.newInstance(1f, 1f, 1f, alpha);
            Object zero3 = ctorVector3f.newInstance(0f, 0f, 0f);
            Object dynUniforms = mGetDynamicUniforms.invoke(null);
            Object dynSlice = mDynamicUniformsWrite.invoke(dynUniforms, identity4, white4, zero3, identity4);

            currentStage = "ensureProjectionBuffer(icon)";
            Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
            Object projectionSlice = mBufferSlice.invoke(projectionBuf, 0L, 64L);

            currentStage = "createRenderPass(icon)";
            java.util.function.Supplier<String> passLabel = () -> "yuyuframe_icon";
            Object pass = mCreateRenderPass.invoke(encoder, passLabel, colorView, OptionalInt.empty());
            try {
                currentStage = "setPipeline(icon)";
                mSetPipeline.invoke(pass, fieldRenderPipelineGuiText);
                if (mDisableScissor != null) { currentStage = "disableScissor(icon)"; mDisableScissor.invoke(pass); }
                currentStage = "bindDefaultUniforms(icon)";
                mBindDefaultUniforms.invoke(null, pass);
                currentStage = "setUniform(Projection)(icon)";
                mSetUniformSlice.invoke(pass, "Projection", projectionSlice);
                currentStage = "setUniform(DynamicTransforms)(icon)";
                mSetUniformSlice.invoke(pass, "DynamicTransforms", dynSlice);
                currentStage = "bindTexture(Sampler0)(icon)";
                mBindTexture.invoke(pass, "Sampler0", iconView, iconSampler);
                currentStage = "bindTexture(Sampler2)(icon)";
                mBindTexture.invoke(pass, "Sampler2", white[1], white[2]);
                currentStage = "setVertexBuffer(icon)";
                mSetVertexBuffer.invoke(pass, 0, vbo);

                currentStage = "shapeIndexBuffer(icon)";
                if (sharedSequentialQuad == null) sharedSequentialQuad = fieldSharedSequentialQuad.get(null);
                Object indexBuffer = mShapeIndexBufferGetBuffer.invoke(sharedSequentialQuad, 6);
                Object indexType = mShapeIndexBufferGetType.invoke(sharedSequentialQuad);
                currentStage = "setIndexBuffer(icon)";
                mSetIndexBuffer.invoke(pass, indexBuffer, indexType);
                currentStage = "drawIndexed(icon)";
                mDrawIndexed.invoke(pass, 0, 0, 6, 1);
            } finally {
                currentStage = "closePass(icon)";
                mClosePass.invoke(pass);
            }
            return true;
        } catch (Throwable t) {
            if (failureLogCount < 5) {
                failureLogCount++;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                LauncherLog.err("[UiRenderer] UiTextBlaze3D.drawIcon a échoué #" + failureLogCount + " à l'étape '" + currentStage + "' : " + t + " | cause réelle : " + cause);
            }
            return false;
        }
    }

    // ── Rectangle arrondi À DÉGRADÉ (fond de sidebar, pastille d'icône...) —
    // MÊME structure que drawRect, mais couleur portée par sommet (interpolée
    // par le GPU à travers chaque quad) au lieu d'un ColorModulator uniforme :
    // DynamicTransforms.ColorModulator reste neutre (blanc opaque), la
    // couleur RÉELLE vient de rgba dans putVertexPCTL, voir putRectQuadGradient/
    // putSolidQuadGradient.
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
                mSetPipeline.invoke(pass, fieldRenderPipelineGuiText);
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

    /** Comme {@link #drawGradientRect} mais 4 coins indépendants (voir {@link #queueGradientRect2D}) — même pipeline, mêmes textures (masque de coin + blanc), seule la couleur par sommet change (bilinéaire au lieu d'un axe unique). */
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
                mSetPipeline.invoke(pass, fieldRenderPipelineGuiText);
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
     * Un des 4 coins arrondis : échantillonne {@link #ensureCornerMaskTexture()}
     * avec l'UV retourné selon le coin (flipU pour les coins DROITE, flipV
     * pour les coins HAUT — la texture ne représente qu'UN coin haut-gauche,
     * réutilisé par retournement pour les 3 autres). Dérivation complète
     * (pourquoi ces retournements précisément) : le "point intérieur" du
     * masque (alpha=1, coin bas-droite de la texture, voir
     * ensureCornerMaskTexture) doit toujours correspondre au coin de CE quad
     * le plus proche du CENTRE du rectangle réel — flipU si ce centre est à
     * GAUCHE de xLeft/xRight (coins droits), flipV si ce centre est en
     * DESSOUS de yBottom/yTop (coins hauts, Y-UP : le "haut" du rectangle a
     * son centre EN DESSOUS de lui).
     */
    private static void putRectQuad(ByteBuffer buf, float xLeft, float xRight, float yBottom, float yTop,
                                     boolean flipU, boolean flipV, int rgba, short light0, short light1) {
        float uLeft = flipU ? 1f : 0f, uRight = flipU ? 0f : 1f;
        float vBottom = flipV ? 1f : 0f, vTop = flipV ? 0f : 1f;
        // Même ordre/winding que le texte (CCW confirmé visible) :
        // (xLeft,yTop)→(xLeft,yBottom)→(xRight,yBottom)→(xRight,yTop).
        putVertexPCTL(buf, xLeft, yTop, rgba, uLeft, vTop, light0, light1);
        putVertexPCTL(buf, xLeft, yBottom, rgba, uLeft, vBottom, light0, light1);
        putVertexPCTL(buf, xRight, yBottom, rgba, uRight, vBottom, light0, light1);
        putVertexPCTL(buf, xRight, yTop, rgba, uRight, vTop, light0, light1);
    }

    /** Bord/centre — UV CONSTANTE loin du bord du masque (toujours opaque, voir ensureCornerMaskTexture), donc un simple remplissage plein. */
    private static void putSolidQuad(ByteBuffer buf, float xLeft, float xRight, float yBottom, float yTop, int rgba, short light0, short light1) {
        float u = 0.95f, v = 0.95f;
        putVertexPCTL(buf, xLeft, yTop, rgba, u, v, light0, light1);
        putVertexPCTL(buf, xLeft, yBottom, rgba, u, v, light0, light1);
        putVertexPCTL(buf, xRight, yBottom, rgba, u, v, light0, light1);
        putVertexPCTL(buf, xRight, yTop, rgba, u, v, light0, light1);
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

    /** POSITION(float×3) + COLOR(ubyte×4) + UV0(float×2) + UV2/light(short×2) — 28 octets, ordre EXACT vérifié par désassemblage de VertexFormats.POSITION_COLOR_TEXTURE_LIGHT. */
    private static void putVertexPCTL(ByteBuffer buf, float x, float y, int rgba, float u, float v, short light0, short light1) {
        buf.putFloat(x).putFloat(y).putFloat(0f);
        buf.put((byte) (rgba & 0xFF)).put((byte) ((rgba >> 8) & 0xFF)).put((byte) ((rgba >> 16) & 0xFF)).put((byte) ((rgba >> 24) & 0xFF));
        buf.putFloat(u).putFloat(v);
        buf.putShort(light0).putShort(light1);
    }

    private static Method mGetFramebuffer;
    private static boolean getFramebufferErrorLogged;
    private static Object getFramebuffer(Object mc) {
        try {
            if (mGetFramebuffer == null) {
                // 26.1+ : MinecraftClient.getFramebuffer() renommée
                // Minecraft.getMainRenderTarget() (vérifié par javap — voir
                // aussi GlobalUiRenderBridge261, même correspondance déjà
                // établie pour le portage des Mixins).
                String name = MappingsRegistry.isLoaded()
                    ? MappingsRegistry.getObfMethodName("net/minecraft/client/MinecraftClient", "getFramebuffer")
                    : "getMainRenderTarget";
                mGetFramebuffer = mc.getClass().getMethod(name);
            }
            return mGetFramebuffer.invoke(mc);
        } catch (Throwable t) {
            // BUG TROUVÉ (26.1+) : ce catch avalait l'exception SANS LOGGER,
            // retournant null silencieusement — drawText/drawRect/drawIcon
            // font alors juste "if (fb == null) return false;" et abandonnent
            // sans la moindre trace. Explique un dessin qui "démarre" (log de
            // géométrie déjà émis) mais n'atteint jamais la moindre commande
            // GPU, sans exception ni flash visible nulle part.
            if (!getFramebufferErrorLogged) {
                getFramebufferErrorLogged = true;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                LauncherLog.err("[LauncherAgent] getFramebuffer: échec (mc.getClass()=" + mc.getClass()
                    + ") : " + t + " | cause réelle : " + cause);
            }
            return null;
        }
    }
}
