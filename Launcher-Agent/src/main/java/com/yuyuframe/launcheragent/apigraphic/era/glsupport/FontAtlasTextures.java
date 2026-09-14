package com.yuyuframe.launcheragent.apigraphic.era.glsupport;

import com.yuyuframe.launcheragent.apimixin.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Atlas de police → texture GPU. Extrait de {@code UiTextRenderer} le
 * 2026-09-09, où il occupait la MOITIÉ du fichier (353 lignes sur 729) sans
 * avoir quoi que ce soit à voir avec « dessiner du texte ».
 *
 * <h2>Pourquoi ici et pas dans une ère</h2>
 *
 * Ce bloc ne dépend d'AUCUNE des deux ères GL : il ne connaît que
 * {@link GlBridge}, plus la résolution réflexive de l'API de texture du jeu
 * ({@code NativeImage}/{@code TextureManager}). C'est du support GL partagé,
 * comme {@code GlBridge} lui-même — d'où {@code era/glsupport/}.
 *
 * <h2>Deux chemins, un repli</h2>
 *
 * L'upload passe par {@code NativeImage}/{@code TextureManager} du jeu quand
 * l'API est résolvable (le texture manager connaît alors notre atlas et le
 * gère comme les siens), et retombe sur un {@code glTexImage2D} brut sinon.
 * Ce repli n'est pas décoratif : il est le seul chemin quand la résolution
 * réflexive échoue, et il a servi.
 *
 * <p>Le cache est par {@link UiFont} : une valeur {@code -1} mémorisée signifie
 * « échec déjà constaté », pour ne pas réessayer l'upload à chaque frame.
 */
public final class FontAtlasTextures {

    private final GlBridge gl;

    public FontAtlasTextures(GlBridge gl) {
        this.gl = gl;
    }

    private final Map<UiFont, Integer> fontTextures = new HashMap<>();

    public int ensureFontTexture(UiFont font) {
        Integer cached = fontTextures.get(font);
        if (cached != null) return cached;

        ensureNativeTextureApiResolved();
        if (nativeTextureApiAvailable) {
            try {
                int texId = createFontTextureViaNativeImage(font);
                syncAfterFontUpload(texId);
                fontTextures.put(font, texId);
                LauncherLog.ui(1, "[UiRenderer] atlas police uploadé via NativeImage/TextureManager, texId=" + texId);
                return texId;
            } catch (Throwable t) {
                LauncherLog.err("[UiRenderer] createFontTextureViaNativeImage a échoué, repli sur glTexImage2D brut : " + t);
                // repli ci-dessous
            }
        }
        try {
            int texId = createFontTextureRaw(font);
            syncAfterFontUpload(texId);
            fontTextures.put(font, texId);
            LauncherLog.ui(1, "[UiRenderer] atlas police uploadé (repli brut), texId=" + texId);
            return texId;
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] ensureFontTexture: " + t);
            fontTextures.put(font, -1);
            return -1;
        }
    }

    /**
     * BUG TROUVÉ (era E, texte REGULAR corrompu un lancement sur deux,
     * confirmé par capture d'écran : deux sessions IDENTIQUES de la même
     * instance, mêmes réglages, l'une nette et l'autre corrompue — donc pas
     * un bug déterministe de code, un vrai résultat différent produit par le
     * pilote GPU d'un lancement à l'autre) : le log de démarrage confirme
     * explicitement que CETTE machine a un contournement Sodium actif pour
     * "NVIDIA_THREADED_OPTIMIZATIONS_BROKEN" — la soumission de commandes en
     * thread séparé du pilote NVIDIA est documentée bogguée ICI. Sodium
     * applique ses propres contournements pour SES draws, mais notre
     * `glTexImage2D`/`glGenerateMipmap` (injectés via Mixin, hors du contrôle
     * de Sodium) n'en bénéficient pas : rien n'empêche le pilote de renvoyer
     * la main avant d'avoir RÉELLEMENT terminé l'upload/la génération des
     * mipmaps en arrière-plan, laissant échantillonner une texture
     * partiellement écrite (garbage) — l'atlas n'étant créé qu'une seule
     * fois par lancement, ce résultat de course reste figé pour toute la
     * session, cohérent avec TOUT ce qui a été observé. Fix : `glFinish()`
     * juste après l'upload, une seule fois par police par lancement (aucun
     * risque de perf) — force le pilote à réellement terminer avant qu'on
     * ne considère la texture prête à être échantillonnée.
     */
    private void syncAfterFontUpload(int texId) {
        if (texId < 0) return;
        try {
            gl.glFinish();
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] syncAfterFontUpload (texId=" + texId + "): " + t);
        }
    }

    private static Class<?> nativeImageClass, nativeImageBackedTextureClass, textureManagerClass, abstractTextureClass, identifierClass;
    private static Object nativeImageFormatRgba;
    private static java.lang.reflect.Constructor<?> nativeImageCtor, nativeImageBackedTextureCtor;
    private static boolean nativeImageBackedTextureNeedsLabel;
    private static java.lang.reflect.Field nativeImagePointerField;
    private static Method nativeImageSetColor, nativeImageCloseMethod, textureUploadMethod, textureGetGlIdMethod,
        textureBindTextureMethod, textureManagerRegisterTextureMethod, identifierOfMethod, mcGetTextureManagerMethod;
    private static boolean nativeTextureApiResolveAttempted, nativeTextureApiAvailable, bulkCopyAvailable;
    private static int fontTextureCounter;

    /**
     * BUG TROUVÉ (1.21.4, crash natif confirmé par bissection — voir
     * historique de session) : créer notre PROPRE texture GL brute
     * (glGenTextures/glTexImage2D/glGenerateMipmap/glTexParameteri via
     * réflexion) pour l'atlas de police plantait le process de façon
     * imprévisible (parfois REGULAR, parfois BOLD, jamais un point de code
     * fixe) — le déplacement de l'ordre de création n'a fait que déplacer le
     * crash, pas le résoudre. Recherche sur les mods Fabric open-source
     * confirmée : AUCUN mod sérieux ne crée de texture dynamique en GL brut
     * — tous passent par {@code NativeImage} + {@code
     * NativeImageBackedTexture} + {@code TextureManager.registerTexture()},
     * le chemin de création de texture SUIVI par le système de gestion
     * d'état interne de Minecraft ({@code GlStateManager}/{@code
     * RenderSystem}). Notre ancien code contournait entièrement ce suivi —
     * hypothèse retenue : ça désynchronisait l'état GL que le rendu vanilla
     * (qui tourne dans la même frame) suppose cohérent, plantage
     * imprévisible selon ce qui se dessine à côté. Résolu dynamiquement
     * (aucune classe Minecraft compilée en dur) ; repli sur l'ancien chemin
     * brut UNIQUEMENT si cette résolution échoue entièrement (ex: signature
     * qui aurait changé sur une future version).
     */
    private void ensureNativeTextureApiResolved() {
        if (nativeTextureApiResolveAttempted) return;
        nativeTextureApiResolveAttempted = true;
        try {
            nativeImageClass = McReflect.yarnClass("net/minecraft/client/texture/NativeImage");
            nativeImageBackedTextureClass = McReflect.yarnClass("net/minecraft/client/texture/NativeImageBackedTexture");
            textureManagerClass = McReflect.yarnClass("net/minecraft/client/texture/TextureManager");
            abstractTextureClass = McReflect.yarnClass("net/minecraft/client/texture/AbstractTexture");
            identifierClass = McReflect.yarnClass("net/minecraft/util/Identifier");
            Class<?> formatClass = McReflect.yarnClass("net/minecraft/client/texture/NativeImage$Format");
            if (nativeImageClass == null || nativeImageBackedTextureClass == null || textureManagerClass == null
                    || abstractTextureClass == null || identifierClass == null || formatClass == null) {
                LauncherLog.warn("[UiRenderer] résolution NativeImage/TextureManager : une classe introuvable, repli brut");
                return;
            }

            String rgbaObf = MappingsRegistry.getObfFieldName("net/minecraft/client/texture/NativeImage$Format", "RGBA");
            java.lang.reflect.Field rgbaField = formatClass.getDeclaredField(rgbaObf);
            rgbaField.setAccessible(true);
            nativeImageFormatRgba = rgbaField.get(null);

            nativeImageCtor = nativeImageClass.getDeclaredConstructor(formatClass, int.class, int.class, boolean.class);
            nativeImageCtor.setAccessible(true);
            // BUG TROUVÉ (era E, 1.21.11) : NativeImageBackedTexture(NativeImage)
            // (le seul constructeur utilisé jusqu'à la 1.21.4) n'existe plus —
            // Blaze3D ajoute un label de debug obligatoire en 1er paramètre
            // (Supplier<String>), confirmé via mappings 1.21.11 :
            // "(Ljava/util/function/Supplier;Lfyh;)V <init>" (fyh=NativeImage) —
            // AUCUN constructeur 1-arg NativeImage-seul n'existe plus du tout sur
            // cette version. Essaie l'ancien d'abord (1.20.4/1.21.4), puis le
            // nouveau (Supplier<String>, NativeImage) en repli.
            try {
                nativeImageBackedTextureCtor = nativeImageBackedTextureClass.getDeclaredConstructor(nativeImageClass);
                nativeImageBackedTextureNeedsLabel = false;
            } catch (NoSuchMethodException e) {
                nativeImageBackedTextureCtor = nativeImageBackedTextureClass.getDeclaredConstructor(java.util.function.Supplier.class, nativeImageClass);
                nativeImageBackedTextureNeedsLabel = true;
            }
            nativeImageBackedTextureCtor.setAccessible(true);

            nativeImageSetColor = McReflect.method(nativeImageClass, "net/minecraft/client/texture/NativeImage", "setColor", int.class, int.class, int.class);
            nativeImageCloseMethod = McReflect.noArgMethod(nativeImageClass, "net/minecraft/client/texture/NativeImage", "close");

            // BUG DE PERFORMANCE TROUVÉ (v347, signalé par l'utilisateur : "je
            // lag à 8 FPS") : remplir un atlas 1024x2048 (2 millions de pixels)
            // via setColor() UN PAR UN — chaque appel étant une réflexion Java
            // (Method.invoke, avec autoboxing) — coûte des millions
            // d'invocations réflexives par police créée. Repli : accès direct
            // à la mémoire native de NativeImage (champ "pointer", un long
            // pointant vers le buffer hors-tas) via MemoryUtil.memCopy — une
            // SEULE copie mémoire brute au lieu de 2 millions d'appels
            // individuels. Notre ByteBuffer existant (octets R,G,B,A
            // consécutifs) a EXACTEMENT le même agencement mémoire que le
            // format "RGBA petit-boutiste" de NativeImage (petit-boutiste :
            // R d'abord en mémoire, A en dernier) — aucune conversion
            // supplémentaire nécessaire, juste une copie brute octet à octet.
            try {
                String pointerObf = MappingsRegistry.getObfFieldName("net/minecraft/client/texture/NativeImage", "pointer");
                nativeImagePointerField = nativeImageClass.getDeclaredField(pointerObf);
                nativeImagePointerField.setAccessible(true);
                // MemoryUtil appelé en typé (plus de gl.rawMethod) : LWJGL 3
                // est une dépendance de compilation, voir GlBridge.
                bulkCopyAvailable = true;
            } catch (Throwable t) {
                LauncherLog.warn("[UiRenderer] copie mémoire en bloc (MemoryUtil) indisponible, repli sur setColor() pixel par pixel (lent) : " + t);
                bulkCopyAvailable = false;
            }
            textureUploadMethod = McReflect.noArgMethod(nativeImageBackedTextureClass, "net/minecraft/client/texture/NativeImageBackedTexture", "upload");
            textureGetGlIdMethod = McReflect.noArgMethod(abstractTextureClass, "net/minecraft/client/texture/AbstractTexture", "getGlId");
            textureBindTextureMethod = McReflect.noArgMethod(abstractTextureClass, "net/minecraft/client/texture/AbstractTexture", "bindTexture");
            textureManagerRegisterTextureMethod = McReflect.method(textureManagerClass, "net/minecraft/client/texture/TextureManager",
                "registerTexture", identifierClass, abstractTextureClass);
            identifierOfMethod = McReflect.method(identifierClass, "net/minecraft/util/Identifier", "of", String.class, String.class);
            Object mc = McReflect.minecraftClient();
            mcGetTextureManagerMethod = mc != null
                ? McReflect.noArgMethod(mc.getClass(), "net/minecraft/client/MinecraftClient", "getTextureManager")
                : null;

            nativeTextureApiAvailable = nativeImageSetColor != null && textureUploadMethod != null
                && textureGetGlIdMethod != null && textureBindTextureMethod != null
                && textureManagerRegisterTextureMethod != null && identifierOfMethod != null
                && mcGetTextureManagerMethod != null && nativeImageFormatRgba != null;
            LauncherLog.info("[UiRenderer] API NativeImage/TextureManager résolue : disponible=" + nativeTextureApiAvailable);
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] résolution API NativeImage/TextureManager échouée, repli brut : " + t);
            nativeTextureApiAvailable = false;
        }
    }

    private int createFontTextureViaNativeImage(UiFont font) throws Exception {
        java.awt.image.BufferedImage img = font.atlasImage();
        int w = img.getWidth(), h = img.getHeight();

        Object nativeImage = nativeImageCtor.newInstance(nativeImageFormatRgba, w, h, false);
        try {
            if (bulkCopyAvailable) {
                // Chemin rapide : une seule copie mémoire brute (voir
                // ensureNativeTextureApiResolved pour le pourquoi détaillé).
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
                long srcAddr = org.lwjgl.system.MemoryUtil.memAddress(buf);
                long dstAddr = (long) nativeImagePointerField.get(nativeImage);
                org.lwjgl.system.MemoryUtil.memCopy(srcAddr, dstAddr, (long) buf.remaining());
            } else {
                // Repli lent (voir ensureNativeTextureApiResolved) — évite
                // juste de ne RIEN dessiner si MemoryUtil ne se résout pas.
                int[] row = new int[w];
                for (int y = 0; y < h; y++) {
                    img.getRGB(0, y, w, 1, row, 0, w);
                    for (int x = 0; x < w; x++) {
                        int argb = row[x];
                        int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                        // NativeImage.setColor attend du RGBA petit-boutiste, donc
                        // ABGR en notation big-endian habituelle (alpha=octet le
                        // plus significatif, rouge=le moins significatif) — voir
                        // sa javadoc Yarn ("little-endian RGBA, or big-endian ABGR").
                        int nativeColor = (a << 24) | (b << 16) | (g << 8) | r;
                        nativeImageSetColor.invoke(nativeImage, x, y, nativeColor);
                    }
                }
            }

            Object texture = nativeImageBackedTextureNeedsLabel
                ? nativeImageBackedTextureCtor.newInstance((java.util.function.Supplier<String>) () -> "yuyuframe_font", nativeImage)
                : nativeImageBackedTextureCtor.newInstance(nativeImage);
            textureUploadMethod.invoke(texture); // fait le VRAI glTexImage2D, via le chemin suivi par Minecraft
            int texId = (int) textureGetGlIdMethod.invoke(texture);

            // Filtre trilinéaire + clamp-to-edge (voir historique de session
            // pour le pourquoi) — appliqués APRÈS coup sur une texture déjà
            // créée par la voie sûre : bind via AbstractTexture.bindTexture()
            // (passe par GlStateManager, pas notre glBindTexture brut) avant
            // ces quelques réglages, qui eux restent des appels GL directs
            // mais bien plus anodins qu'une création de texture complète.
            textureBindTextureMethod.invoke(texture);
            gl.glGenerateMipmap(0x0DE1);
            gl.glTexParameteri(0x0DE1, 0x2801, 0x2703); // GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR
            gl.glTexParameteri(0x0DE1, 0x2800, 0x2601); // GL_TEXTURE_MAG_FILTER, GL_LINEAR
            gl.glTexParameteri(0x0DE1, 0x2802, 0x812F); // GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE
            gl.glTexParameteri(0x0DE1, 0x2803, 0x812F); // GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE

            Object textureManager = mcGetTextureManagerMethod.invoke(McReflect.minecraftClient());
            Object id = identifierOfMethod.invoke(null, "yuyuframe", "font_" + (fontTextureCounter++));
            textureManagerRegisterTextureMethod.invoke(textureManager, id, texture);
            return texId;
        } finally {
            // NativeImage possède de la mémoire HORS-TAS (native) — doit être
            // explicitement libérée, contrairement au ByteBuffer direct de
            // l'ancien chemin (géré par le GC, voir Cleaner de
            // ByteBuffer.allocateDirect) : celui-ci ne l'est pas.
            if (nativeImageCloseMethod != null) {
                try { nativeImageCloseMethod.invoke(nativeImage); } catch (Throwable ignored) {}
            }
        }
    }

    /** Ancien chemin (GL brut via réflexion) — conservé UNIQUEMENT en repli si la résolution NativeImage échoue. */
    private int createFontTextureRaw(UiFont font) throws Exception {
        java.awt.image.BufferedImage img = font.atlasImage();
        int w = img.getWidth(), h = img.getHeight();

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

        // DIAG-FONTCORRUPT (era E, bug "texte corrompu une fois sur deux" —
        // stable sur TOUTE une session, jamais un flicker en cours de route,
        // donc lié à CETTE création unique/mise en cache, pas au dessin par
        // frame) : cette méthode n'est appelée QU'UNE SEULE FOIS par police
        // par lancement (voir ensureFontTexture, résultat mis en cache) — un
        // seul appel de log ici, aucun risque de perf, même motif que DIAG7
        // (voir historique de session) mais volontairement gardé cette fois
        // (pas par frame).
        int activeUnitBefore = gl.glGetInteger(0x84E0); // GL_ACTIVE_TEXTURE
        int boundTexBefore = gl.glGetInteger(0x8069);   // GL_TEXTURE_BINDING_2D
        int errBefore = gl.drainGlErrors();

        int texId = gl.glGenTextures();
        gl.glBindTexture(0x0DE1, texId); // GL_TEXTURE_2D
        int errAfterBind = gl.drainGlErrors();
        gl.glTexImage2D(0x0DE1, 0, 0x1908, w, h, 0, 0x1908, 0x1401, buf); // GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE
        int errAfterTexImage = gl.drainGlErrors();
        gl.glGenerateMipmap(0x0DE1);
        int errAfterMipmap = gl.drainGlErrors();
        gl.glTexParameteri(0x0DE1, 0x2801, 0x2703); // GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR
        gl.glTexParameteri(0x0DE1, 0x2800, 0x2601); // GL_TEXTURE_MAG_FILTER, GL_LINEAR
        gl.glTexParameteri(0x0DE1, 0x2802, 0x812F); // GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE
        gl.glTexParameteri(0x0DE1, 0x2803, 0x812F); // GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE
        int errAfterParams = gl.drainGlErrors();

        // BUG TROUVÉ (era E, texte REGULAR corrompu de façon non-déterministe
        // — confirmé toujours présent MÊME en vanilla sans aucun mod, donc
        // rien à voir avec Sodium/le threading NVIDIA, hypothèse infirmée) :
        // `buf` est un ByteBuffer DIRECT (mémoire hors-tas, libérée par un
        // Cleaner quand l'objet Java devient inatteignable) — la JVM tourne
        // ici avec ZGC (voir JVM Arguments dans les logs de lancement,
        // -XX:+UseZGC -XX:+ZGenerational), un collecteur concurrent
        // particulièrement agressif. Rien n'empêche la JIT/le GC de
        // considérer `buf` "mort" dès la dernière ligne qui le RÉFÉRENCE
        // explicitement (le `glTexImage2D` juste au-dessus) : si le pilote
        // NVIDIA ne copie pas les octets de façon strictement synchrone
        // pendant cet appel (contrairement à ce qu'exige la spec OpenGL, mais
        // des bugs de pilote de ce type existent), un GC concurrent
        // déclenché entre-temps peut libérer cette mémoire AVANT que le
        // pilote ait fini de la lire — le pilote lit alors de la mémoire déjà
        // réutilisée/libérée = texture corrompue, sans qu'aucune erreur GL ne
        // soit levée (cohérent avec `DIAG-FONTCORRUPT` : jamais un seul
        // `glErr` observé). Un `glFinish()` seul (tenté juste avant, sans
        // effet) n'empêche PAS ça : il attend la fin d'une commande qui a
        // DÉJÀ lu la mauvaise mémoire, trop tard pour corriger le résultat.
        // Fix : `glFinish()` ICI (PAS seulement dans syncAfterFontUpload,
        // appelé APRÈS le retour de cette méthode — trop tard, `buf` ne
        // serait alors déjà plus protégé) suivi de
        // `Reference.reachabilityFence(buf)` — l'ordre est capital : glFinish
        // garantit que le pilote a RÉELLEMENT fini de lire `buf`, et la
        // reachabilityFence juste après garantit que la JVM n'a PAS pu
        // libérer sa mémoire native PENDANT cette attente, quelle que soit
        // l'agressivité du GC.
        gl.glFinish();
        GlBridge.reachabilityFence(buf);

        // BUG TROUVÉ (era E) : le diagnostic glGetTexImage tenté ici en v362
        // a provoqué un CRASH JVM natif (EXCEPTION_ACCESS_VIOLATION, écriture
        // hors bornes côté pilote NVIDIA+DSA — voir hs_err_pid*.log,
        // confirmé pointer exactement sur cet appel). Retiré définitivement
        // — ne JAMAIS réintroduire un glGetTexImage ici sans un moyen plus
        // sûr de vérifier au préalable la taille réellement allouée côté
        // pilote (ex: glGetTexLevelParameteriv AVANT de dimensionner le
        // buffer de lecture, jamais en supposant que w/h côté Java
        // correspondent forcément à ce que le pilote a alloué).
        gl.glBindTexture(0x0DE1, 0);

        LauncherLog.info("[LauncherAgent] DIAG-FONTCORRUPT: texId=" + texId + " atlasW=" + w + " atlasH=" + h
            + " activeUnitBefore=0x" + Integer.toHexString(activeUnitBefore)
            + " boundTex2DBefore=" + boundTexBefore
            + " glErr(before=" + errBefore + ", afterBind=" + errAfterBind
            + ", afterTexImage=" + errAfterTexImage + ", afterMipmap=" + errAfterMipmap
            + ", afterParams=" + errAfterParams + ")");
        return texId;
    }
}
