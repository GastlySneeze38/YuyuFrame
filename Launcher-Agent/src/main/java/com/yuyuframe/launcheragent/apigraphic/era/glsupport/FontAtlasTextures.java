package com.yuyuframe.launcheragent.apigraphic.era.glsupport;

import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.HashMap;
import java.util.Map;

/**
 * Atlas de police → texture GPU. Extrait de {@code UiTextRenderer} le
 * 2026-09-09, où il occupait la MOITIÉ du fichier (353 lignes sur 729) sans
 * avoir quoi que ce soit à voir avec « dessiner du texte ».
 *
 * <h2>Pourquoi ici et pas dans une ère</h2>
 *
 * Ce bloc ne dépend d'aucune ère : il ne connaît que {@link GlBridge}. C'est
 * du support GL partagé, comme {@code GlBridge} lui-même — d'où
 * {@code era/glsupport/}.
 *
 * <h2>Un seul chemin</h2>
 *
 * Upload par {@code glTexImage2D} direct. Le chemin par
 * {@code NativeImage}/{@code TextureManager} du jeu, résolu par réflexion, a
 * été supprimé le 2026-09-16 : il ne servait qu'aux versions 1.17 – 1.21.x en
 * ère gl3, abandonnées. La 1.8.9 (seule version gl3 restante) n'a pas
 * {@code NativeImage} et passait déjà par l'upload direct ; les versions
 * Blaze3D n'utilisent pas cette classe.
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

        try {
            int texId = createFontTextureRaw(font);
            syncAfterFontUpload(texId);
            fontTextures.put(font, texId);
            LauncherLog.ui(1, "[UiRenderer] atlas police uploadé, texId=" + texId);
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

    /** Upload GL direct de l'atlas — seul chemin depuis le 2026-09-16 (voir la javadoc de classe). */
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
