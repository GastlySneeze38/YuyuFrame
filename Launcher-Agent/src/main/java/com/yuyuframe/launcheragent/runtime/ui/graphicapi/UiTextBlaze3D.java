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
final class UiTextBlaze3D {

    private UiTextBlaze3D() {}

    private static Boolean available;

    /** {@code true} seulement si les classes Blaze3D (GpuDevice etc.) existent sur ce bracket — sinon, laisser {@link UiRenderer} retomber sur son pipeline SDF existant. */
    static boolean isAvailable() {
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
        mCreateTexture, mCreateTextureView, mCreateBuffer, mCreateCommandEncoder,
        mWriteToTexture, mCreateRenderPass, mSetPipeline, mBindTexture, mSetUniformSlice,
        mSetVertexBuffer, mDraw, mClosePass, mBindDefaultUniforms, mGetDynamicUniforms,
        mDynamicUniformsWrite, mGetColorAttachmentView, mNativeImageSetColor;

    private static Constructor<?> ctorNativeImage, ctorVector4f, ctorVector3f;

    private static Object fieldFilterModeLinear, fieldTextureFormatRgba8,
        fieldNativeImageFormatRgba, fieldRenderPipelineGuiText;

    private static int usageTextureBinding, usageBufferVertex, usageBufferCopyDst;

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

            mCreateCommandEncoder = clsGpuDevice.getMethod("createCommandEncoder");
            mCreateTexture = clsGpuDevice.getMethod("createTexture",
                java.util.function.Supplier.class, int.class, clsTextureFormat, int.class, int.class, int.class, int.class);
            mCreateTextureView = clsGpuDevice.getMethod("createTextureView", clsGpuTexture);
            mCreateBuffer = clsGpuDevice.getMethod("createBuffer", java.util.function.Supplier.class, int.class, ByteBuffer.class);

            mWriteToTexture = clsCommandEncoder.getMethod("writeToTexture", clsGpuTexture, clsNativeImage);
            mCreateRenderPass = clsCommandEncoder.getMethod("createRenderPass",
                java.util.function.Supplier.class, clsGpuTextureView, OptionalInt.class);

            mSetPipeline = clsRenderPass.getMethod("setPipeline", clsRenderPipeline);
            mBindTexture = clsRenderPass.getMethod("bindTexture", String.class, clsGpuTextureView, clsGpuSampler);
            mSetUniformSlice = clsRenderPass.getMethod("setUniform", String.class, clsGpuBufferSlice);
            mSetVertexBuffer = clsRenderPass.getMethod("setVertexBuffer", int.class, clsGpuBuffer);
            mDraw = clsRenderPass.getMethod("draw", int.class, int.class);
            mClosePass = clsRenderPass.getMethod("close");

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
            if (mNativeImageSetColor == null || fieldNativeImageFormatRgba == null || fieldRenderPipelineGuiText == null) {
                throw new NoSuchMethodException("setColor/RGBA/GUI_TEXT introuvable (voir logs)");
            }

            usageTextureBinding = clsGpuTexture.getField("USAGE_TEXTURE_BINDING").getInt(null);
            usageBufferVertex = clsGpuBuffer.getField("USAGE_VERTEX").getInt(null);
            usageBufferCopyDst = clsGpuBuffer.getField("USAGE_COPY_DST").getInt(null);

            ctorVector4f = clsVector4f.getConstructor(float.class, float.class, float.class, float.class);
            ctorVector3f = clsVector3f.getConstructor(float.class, float.class, float.class);

            resolveOk = true;
            LauncherLog.info("[LauncherAgent] UiTextBlaze3D: résolution OK, pipeline GUI_TEXT natif prêt");
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

        BufferedImage img = font.atlasImage();
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
        Object texture = mCreateTexture.invoke(device, labelSupplier, usageTextureBinding, fieldTextureFormatRgba8, w, h, 1, 1);

        Object encoder = mCreateCommandEncoder.invoke(device);
        mWriteToTexture.invoke(encoder, texture, nativeImage);

        Object textureView = mCreateTextureView.invoke(device, texture);
        Object sampler = mSamplerCacheGet.invoke(mGetSamplerCache.invoke(null), fieldFilterModeLinear);

        Object[] result = {texture, textureView, sampler};
        TEXTURES.put(font, result);
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: atlas '" + label + "' créé via GpuDevice.createTexture (w=" + w + " h=" + h + ")");
        return result;
    }

    // ── Dessin ───────────────────────────────────────────────────────────────

    /**
     * Dessine {@code text} avec {@code font} via le pipeline natif
     * {@code RenderPipelines.GUI_TEXT} — retourne {@code false} si quoi que ce
     * soit échoue (classe absente, résolution ratée, exception au dessin),
     * auquel cas l'appelant ({@link UiRenderer#drawTextModern}) doit retomber
     * sur son propre pipeline SDF existant.
     */
    static boolean drawText(UiFont font, String text, float x, float y, UiColor color, float scale, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve()) return false;
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return false;
            Object fb = getFramebuffer(mc);
            if (fb == null || mGetColorAttachmentView == null) return false;
            Object colorView = mGetColorAttachmentView.invoke(fb);
            if (colorView == null) return false;

            Object[] tex = ensureTexture(font);
            Object textureView = tex[1], sampler = tex[2];

            Object device = mGetDevice.invoke(null);
            Object encoder = mCreateCommandEncoder.invoke(device);
            java.util.function.Supplier<String> passLabel = () -> "yuyuframe_text";
            Object pass = mCreateRenderPass.invoke(encoder, passLabel, colorView, OptionalInt.empty());
            try {
                mSetPipeline.invoke(pass, fieldRenderPipelineGuiText);
                mBindDefaultUniforms.invoke(null, pass);

                Object identity4 = clsMatrix4f.getConstructor().newInstance();
                Object white4 = ctorVector4f.newInstance(color.r, color.g, color.b, color.a);
                Object zero3 = ctorVector3f.newInstance(0f, 0f, 0f);
                Object dynUniforms = mGetDynamicUniforms.invoke(null);
                Object dynSlice = mDynamicUniformsWrite.invoke(dynUniforms, identity4, white4, zero3, identity4);
                mSetUniformSlice.invoke(pass, "DynamicTransforms", dynSlice);

                mBindTexture.invoke(pass, "Sampler0", textureView, sampler);
                // Sampler2 (lightmap) : lié à la même texture/sampler que Sampler0 — la
                // valeur UV2 (light) de nos sommets n'est JAMAIS lue de façon à en
                // dépendre visuellement (voir shader core/rendertype_text : Sampler2 sert
                // uniquement à l'atténuation lumineuse, neutralisée ici en écrivant une
                // couleur déjà pleine via DynamicTransforms) — évite une dépendance
                // supplémentaire envers LightmapTextureManager pour un unique overlay
                // sans effet perceptible sur un texte toujours plein-jour (UI).
                mBindTexture.invoke(pass, "Sampler2", textureView, sampler);

                float cs = scale * UiFont.SIZE_CORRECTION;
                float penX = Math.round(x);
                float yTop = Math.round(y + font.ascent * cs);
                float yBottom = Math.round(y - font.descent * cs);
                int rgba = 0xFFFFFFFF; // couleur déjà appliquée via DynamicTransforms — sommets en blanc neutre
                // Plein-jour = 0xF000F0 (LightmapTextureManager.MAX_LIGHT_COORDINATE),
                // 16 bits faibles = 0x00F0, 16 bits forts = 0xF000 — PAS 0xFFF0 (erreur
                // corrigée : les deux moitiés d'un même entier empaqueté, pas deux
                // valeurs indépendantes).
                short light0 = (short) 0x00F0, light1 = (short) 0xF000;

                ByteBuffer verts = ByteBuffer.allocateDirect(text.length() * 4 * 28).order(java.nio.ByteOrder.nativeOrder());
                int vertexCount = 0;
                for (int i = 0; i < text.length(); i++) {
                    UiFont.Glyph g = font.glyph(text.charAt(i));
                    float gw = Math.round(g.width * cs);
                    float x0 = penX, x1 = penX + gw;
                    putVertexPCTL(verts, x0, yTop, rgba, g.u0, g.v0, light0, light1);
                    putVertexPCTL(verts, x0, yBottom, rgba, g.u0, g.v1, light0, light1);
                    putVertexPCTL(verts, x1, yBottom, rgba, g.u1, g.v1, light0, light1);
                    putVertexPCTL(verts, x1, yTop, rgba, g.u1, g.v0, light0, light1);
                    vertexCount += 4;
                    penX += Math.round(g.advance * cs);
                }
                verts.flip();

                java.util.function.Supplier<String> vboLabel = () -> "yuyuframe_text_vbo";
                Object vbo = mCreateBuffer.invoke(device, vboLabel, usageBufferVertex | usageBufferCopyDst, verts);
                try {
                    mSetVertexBuffer.invoke(pass, 0, vbo);
                    mDraw.invoke(pass, 0, vertexCount);
                } finally {
                    try { ((AutoCloseable) vbo).close(); } catch (Throwable ignored) {}
                }
            } finally {
                mClosePass.invoke(pass);
            }
            return true;
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] UiTextBlaze3D.drawText a échoué, repli SDF : " + t);
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
