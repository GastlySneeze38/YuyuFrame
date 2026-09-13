package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass;

import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import static com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass.Blaze3DCore.*;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Texte era E (Blaze3D) — VRAI rendu SDF (champ de distance signée, bord
 * anticrénelé) pour {@link com.yuyuframe.launcheragent.apigraphic.value.UiFont},
 * comme legacy/modern GL ({@code UiTextRenderer.TEXT_FRAGMENT_SRC}), au lieu
 * du seuillage dur copié de vanilla ({@code Blaze3DCore.HOME_FRAGMENT_SRC},
 * toujours utilisé par {@link Blaze3DRect} pour rect/icône). Pipeline dédié
 * ({@link #TEXT_VERTEX_SRC}/{@link #TEXT_FRAGMENT_SRC}) construit via {@link
 * #resolveTextPipeline()}, appelé depuis {@code Blaze3DCore.resolve()} —
 * voir project_home_shader_pipeline en mémoire projet. Scindé depuis l'ancien
 * {@code UiTextBlaze3D.java} — voir {@link Blaze3DCore} pour l'infra
 * partagée (réflexion, buffers, file de dessin).
 */
public final class Blaze3DText {
    private Blaze3DText() {}

    /**
     * Sans {@code Color}/{@code UV2} (attributs du format hérité de GUI_TEXT
     * qui restent bindés dans le buffer mais volontairement pas déclarés ici,
     * inutiles — même principe que {@link Blaze3DGradient}) : le texte envoie
     * toujours un sommet blanc neutre, la vraie couleur vient de {@code
     * ColorModulator} (voir {@link #TEXT_FRAGMENT_SRC}), pas besoin non plus
     * du lightmap (Sampler2) qu'un pipeline texte dédié n'a plus à neutraliser.
     */
    // Public : partagé avec Blaze3DGuiText (v26_1/vanillagui/pipeline) et
    // GuiElementShaders (vanillagui), qui construisent le MÊME shader pour la
    // voie "état de GUI vanilla" (voir rendering-pipeline.md). Package-private
    // tant que tout vivait dans le même paquet ; rangement du 2026-09-13.
    public static final String TEXT_VERTEX_SRC =
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
        // Attribut DÉJÀ présent dans le format de sommet commun (POSITION_
        // COLOR_TEXTURE_LIGHT) mais jusqu'ici jamais déclaré, donc ignoré.
        // C'est lui qui permet de mettre PLUSIEURS couleurs dans une SEULE
        // passe (voir beginBatch) : sans couleur par sommet, une passe = une
        // couleur, donc une passe par chaîne de texte.
        "in vec4 Color;\n" +
        "in vec2 UV0;\n" +
        "out vec2 texCoord0;\n" +
        "out vec4 vertexColor;\n" +
        "void main() {\n" +
        "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n" +
        "    texCoord0 = UV0;\n" +
        "    vertexColor = Color;\n" +
        "}\n";

    /**
     * Même algèbre EXACTE que {@code UiTextRenderer.TEXT_FRAGMENT_SRC}
     * (legacy)/{@code TEXT_FRAGMENT_SRC_MODERN} — {@code BIAS=0.06} pour
     * garder les traits fins (barres de "i"/"l"/"j") visibles en petit texte,
     * {@code fwidth}+{@code smoothstep} pour le bord anticrénelé — juste
     * portée en GLSL 330 core profile ({@code texture()} pas {@code
     * texture2D()}, {@code ColorModulator} pas {@code gl_Color}).
     */
    public static final String TEXT_FRAGMENT_SRC =
        "#version 330\n" +
        "uniform sampler2D Sampler0;\n" +
        "layout(std140) uniform DynamicTransforms {\n" +
        "    mat4 ModelViewMat;\n" +
        "    vec4 ColorModulator;\n" +
        "    vec3 ModelOffset;\n" +
        "    mat4 TextureMat;\n" +
        "};\n" +
        "in vec2 texCoord0;\n" +
        "in vec4 vertexColor;\n" +
        "out vec4 fragColor;\n" +
        "const float BIAS = 0.06;\n" +
        "void main() {\n" +
        "    float dist = texture(Sampler0, texCoord0).a + BIAS;\n" +
        "    float w = fwidth(dist);\n" +
        "    float alpha = smoothstep(0.5 - w, 0.5 + w, dist);\n" +
        // MULTIPLIE les deux sources au lieu de n'utiliser que ColorModulator.
        // RÉTRO-COMPATIBLE : le chemin non batché pose des sommets BLANCS, et
        // blanc x ColorModulator redonne EXACTEMENT l'ancien résultat. Le
        // chemin batché fait l'inverse (couleur par sommet, ColorModulator
        // laissé blanc), ce qui autorise plusieurs couleurs dans une passe.
        "    fragColor = vec4(vertexColor.rgb * ColorModulator.rgb, vertexColor.a * ColorModulator.a * alpha);\n" +
        "}\n";

    private static Object textPipeline, textShaderSource;

    /** Construit le pipeline texte — voir {@link Blaze3DGradient#resolveGradientPipeline()} pour le même principe (ce fichier possède son GLSL, donc son code de compilation). */
    static boolean resolveTextPipeline() throws Exception {
        Object vertexId = gpu.identifier("yuyuframe", "shader/ui_blaze3d_text.vsh");
        Object fragmentId = gpu.identifier("yuyuframe", "shader/ui_blaze3d_text.fsh");
        textPipeline = gpu.buildPipeline("ui_blaze3d_text", vertexId, fragmentId,
            new String[]{ "Sampler0" }, new String[]{ "DynamicTransforms", "Projection" }, null, null);
        textShaderSource = gpu.shaderSource(vertexId, TEXT_VERTEX_SRC, fragmentId, TEXT_FRAGMENT_SRC);
        return textPipeline != null;
    }

    // ── Textures (une par UiFont, mises en cache — jamais recréées) ─────────

    private static final Map<UiFont, Object[]> TEXTURES = new HashMap<>(); // [GpuTexture, GpuTextureView, GpuSampler]

    /**
     * Public (package-private avant le rangement du 2026-09-13) :
     * Blaze3DGuiText, désormais dans {@code v26_1/vanillagui/pipeline}, en a
     * besoin pour bâtir son TextureSetup.
     *
     * <p>{@code null} si le moteur n'est pas résolu (l'appelant se rabat alors
     * proprement). POINT D'ENTRÉE PUBLIC de la voie « état de GUI vanilla » :
     * contrairement aux dessins de la file, personne n'a appelé {@link
     * Blaze3DCore#resolve()} avant nous sur ce chemin — d'où la résolution ici
     * (idempotente). Régression v1064 : {@code Blaze3DGui*.ensureCompiled}
     * appelait {@code resolve()} pour obtenir l'appareil ; en passant par
     * {@code ShaderPipelineFactory.device()}, ce seul déclencheur a disparu et
     * {@code Blaze3DCore.gpu} restait nul (NPE à chaque chaîne de texte).
     */
    public static Object[] ensureTexture(UiFont font) throws Exception {
        Object[] cached = TEXTURES.get(font);
        if (cached != null) return cached;
        if (!isAvailable() || !resolve()) return null;

        // Depuis le pipeline texte dédié (voir TEXT_FRAGMENT_SRC, SDF réel
        // avec BIAS+fwidth+smoothstep — même algèbre que legacy/modern GL) :
        // font.atlasImage() (APRÈS transformation en champ de distance
        // signée, voir UiFont.buildSignedDistanceField) est maintenant le
        // BON atlas — c'est exactement ce que ce shader attend. Historique :
        // tant que ce fichier réutilisait le shader vanilla à seuillage dur
        // (Blaze3DCore.HOME_FRAGMENT_SRC, texture.rgba × couleur, aucun
        // seuillage SDF), il fallait atlasImagePlain() (couverture classique,
        // capturée AVANT la transformation SDF) — sinon rendu quasi invisible
        // (alpha ~128 partout sauf loin du bord). Ce n'est plus le cas ici.
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

        Object device = gpu.device();
        final String label = "yuyuframe_font_" + System.identityHashCode(font);
        Object texture = gpu.createTexture(device, label,
            gpu.usageTextureBinding() | gpu.usageTextureCopyDst(), w, h, mipLevels);

        Object encoder = gpu.encoder(device);
        gpu.uploadImage(encoder, texture, nativeImage); // mip 0 (résolution native)

        for (int level = 1; level < mipLevels; level++) {
            int mw = Math.max(1, w >> level), mh = Math.max(1, h >> level);
            BufferedImage scaled = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(img, 0, 0, mw, mh, null);
            g2.dispose();
            Object mipImage = bufferedImageToNativeImage(scaled, mw, mh);
            gpu.uploadImageRegion(encoder, texture, mipImage, level, 0, 0, 0, mw, mh, 0, 0);
        }

        Object textureView = gpu.createTextureView(device, texture);
        Object sampler = gpu.linearSampler();

        Object[] result = {texture, textureView, sampler};
        TEXTURES.put(font, result);
        LauncherLog.ui(1, "[UiRenderer] UiTextBlaze3D: atlas '" + label + "' créé via GpuDevice.createTexture (w=" + w + " h=" + h + " mipLevels=" + mipLevels + ")");
        return result;
    }


    /**
     * Atlas de police pour un chemin de rendu HORS de ce paquet — renvoie
     * {@code [GpuTexture, GpuTextureView, GpuSampler]}, ou {@code null} si le
     * moteur n'est pas résolu (l'appelant se rabat alors proprement).
     *
     * <p>Existe pour l'unité de compilation 1.21.11 ({@code VanillaGuiSink1211}),
     * qui bâtit son propre {@code TextureSetup} à partir de la vue et de
     * l'échantillonneur : {@code ensureTexture} est package-private, et le
     * reste — c'est un détail d'implémentation du chemin Blaze3D.
     */
    public static Object[] fontAtlasEntry(UiFont font) {
        try {
            return ensureTexture(font);
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DText] fontAtlasEntry: " + t);
            return null;
        }
    }

    /** Appelé depuis {@code UiRenderer.drawTextModern} — empile au lieu de dessiner immédiatement, voir commentaire ci-dessus. */
    public static void queueDraw(UiFont font, String text, float x, float y, UiColor color, float scale, int vpWidth, int vpHeight) {
        if (!isAvailable() || text == null || text.isEmpty()) return;
        if (batching) {
            // Accumulé plutôt qu'empilé — voir beginBatch/endBatch.
            BATCH.add(new BatchEntry(font, text, x, y, scale, color));
            return;
        }
        Blaze3DCore.enqueue(() -> drawText(font, text, x, y, color, scale, vpWidth, vpHeight));
    }


    /**
     * Émet les quads d'une chaîne dans {@code verts} et renvoie le nombre de
     * sommets ajoutés — extrait de {@link #drawText} pour être PARTAGÉ avec le
     * chemin batché ({@link #beginBatch}), qui empile plusieurs chaînes dans
     * un même tampon avant de tout envoyer en une passe.
     *
     * <p>{@code rgba} est la couleur par SOMMET : blanc neutre pour le chemin
     * classique (la couleur vient alors de {@code ColorModulator}), couleur
     * réelle pour le chemin batché. Voir {@code TEXT_FRAGMENT_SRC}.
     */
    private static int appendGlyphs(ByteBuffer verts, UiFont font, String text, float x, float y, float scale, int rgba) {
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
        short light0 = 0, light1 = 0;

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
        return vertexCount;
    }


    // ── Mode BATCH : plusieurs chaînes en une seule passe ───────────────────
    //
    // AUDIT PERF : chaque drawText ouvrait SA propre passe de rendu
    // (createCommandEncoder + createRenderPass). Sur le HUD, le texte produit
    // plus de passes que les fonds (multi-lignes + suffixe d'accent dessiné à
    // part), c'était donc le premier poste de coût.
    //
    // OPT-IN et non automatique : fusionner des dessins consécutifs sans que
    // l'appelant le demande casserait l'ordre Z durement acquis de ce moteur
    // (même raison que pour les rects batchés, voir Blaze3DRect). Ici
    // l'appelant DÉCLARE que son texte peut être regroupé.
    //
    // ORDRE Z À CONNAÎTRE : tout le texte du lot est empilé au moment du
    // endBatch(), donc APRÈS tout ce qui a été dessiné entre begin et end. Sur
    // le HUD c'est exactement ce qu'on veut (le texte passe au-dessus de tous
    // les fonds) ; pour un contenu où du texte doit passer SOUS un élément
    // dessiné après lui, ne pas utiliser le batch.

    private static final class BatchEntry {
        final UiFont font; final String text; final float x, y, scale; final UiColor color;
        BatchEntry(UiFont font, String text, float x, float y, float scale, UiColor color) {
            this.font = font; this.text = text; this.x = x; this.y = y; this.scale = scale; this.color = color;
        }
    }

    private static final java.util.List<BatchEntry> BATCH = new java.util.ArrayList<>();
    private static boolean batching;

    /**
     * Ouvre un lot — tout {@link #queueText} suivant y est accumulé au lieu
     * d'ouvrir sa propre passe. À refermer par {@link #endBatch}.
     *
     * <p>Un lot resté OUVERT (exception entre begin et end) serait le pire cas
     * possible : tout le texte de l'application, écrans compris, y tomberait
     * sans jamais être dessiné — une interface entièrement muette, sans la
     * moindre erreur. D'où la remise à zéro défensive ci-dessous plutôt qu'une
     * confiance au bon appariement des appels.
     */
    public static void beginBatch() {
        if (!isAvailable()) return;
        if (batching && !BATCH.isEmpty() && failureLogCount < 5) {
            failureLogCount++;
            LauncherLog.err("[UiRenderer] Blaze3DText: lot de texte non refermé (" + BATCH.size()
                + " entrées perdues) — endBatch() manquant chez l'appelant précédent");
        }
        batching = true;
        BATCH.clear();
    }

    /**
     * Ferme le lot et empile UNE passe par police (les polices ont chacune leur
     * atlas, donc leur propre texture à lier — impossible de les mélanger dans
     * une même passe). En pratique : REGULAR + BOLD, soit 2 passes au lieu
     * d'une par chaîne.
     */
    public static void endBatch(int vpWidth, int vpHeight) {
        if (!batching) return;
        batching = false;
        if (BATCH.isEmpty()) return;
        java.util.LinkedHashMap<UiFont, java.util.List<BatchEntry>> byFont = new java.util.LinkedHashMap<>();
        for (BatchEntry e : BATCH) byFont.computeIfAbsent(e.font, k -> new java.util.ArrayList<>()).add(e);
        BATCH.clear();
        for (java.util.Map.Entry<UiFont, java.util.List<BatchEntry>> group : byFont.entrySet()) {
            UiFont font = group.getKey();
            java.util.List<BatchEntry> entries = group.getValue();
            Blaze3DCore.enqueue(() -> drawTextBatch(font, entries, vpWidth, vpHeight));
        }
    }

    /** Empaquette une couleur en ABGR (même convention que {@code Blaze3DRect} — little-endian RGBA). */
    private static int packRgba(UiColor c) {
        int r = Math.round(c.r * 255f), g = Math.round(c.g * 255f);
        int b = Math.round(c.b * 255f), a = Math.round(c.a * 255f);
        return (a << 24) | (b << 16) | (g << 8) | r;
    }


    /**
     * Dessine TOUTES les chaînes d'une même police en UNE passe.
     *
     * <p>Calqué sur {@link #drawText} — mêmes précautions, notamment l'ordre
     * imposé : toutes les écritures de tampons (sommets, DynamicTransforms,
     * projection) AVANT {@code createRenderPass}, jamais pendant (voir le
     * commentaire "dynamicUniformsWrite" de drawText : une écriture en pleine
     * passe fait échouer close()).
     *
     * <p>Différence essentielle : {@code ColorModulator} est laissé à BLANC et
     * la couleur voyage PAR SOMMET, ce qui permet de mélanger des chaînes de
     * couleurs différentes dans la même passe (voir TEXT_FRAGMENT_SRC, qui
     * multiplie les deux sources).
     */
    private static boolean drawTextBatch(UiFont font, java.util.List<BatchEntry> entries, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve() || entries.isEmpty()) return false;
        try {
            currentStage = "mainColorView(textbatch)";
            Object colorView = gpu.mainColorView();
            if (colorView == null) return false;

            currentStage = "ensureTexture(textbatch)";
            Object[] tex = ensureTexture(font);
            Object textureView = tex[1], sampler = tex[2];

            currentStage = "device(textbatch)";
            Object device = gpu.device();
            currentStage = "encoder(textbatch)";
            Object encoder = gpu.encoder(device);

            int totalChars = 0;
            for (BatchEntry e : entries) totalChars += e.text.length();
            ByteBuffer verts = ensureStagingBuffer(totalChars * 4 * 28);
            int vertexCount = 0;
            for (BatchEntry e : entries) {
                // Couleur PAR SOMMET ici (ColorModulator restera blanc).
                vertexCount += appendGlyphs(verts, font, e.text, e.x, e.y, e.scale, packRgba(e.color));
            }
            if (vertexCount == 0) return true;
            verts.flip();

            currentStage = "ensureVertexBuffer(textbatch)";
            Object vbo = ensureVertexBuffer(device, verts.remaining());
            gpu.write(encoder, gpu.slice(vbo, 0L, verts.remaining()), verts);

            // BLANC : la couleur réelle est déjà dans les sommets.
            currentStage = "dynamicTransforms(textbatch)";
            Object dynSlice = gpu.dynamicTransforms(1f, 1f, 1f, 1f);

            currentStage = "ensureProjectionBuffer(textbatch)";
            Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
            Object projectionSlice = gpu.slice(projectionBuf, 0L, 64L);

            currentStage = "openPass(textbatch)";
            Object pass = gpu.openPass(encoder, "yuyuframe_text_batch", colorView);
            try {
                gpu.precompile(device, textPipeline, textShaderSource);
                gpu.setPipeline(pass, textPipeline);
                gpu.disableScissor(pass);
                gpu.bindDefaultUniforms(pass);
                gpu.setUniform(pass, "Projection", projectionSlice);
                gpu.setUniform(pass, "DynamicTransforms", dynSlice);
                gpu.bindTexture(pass, "Sampler0", textureView, sampler);
                gpu.setVertexBuffer(pass, 0, vbo);
                gpu.drawQuads(pass, vertexCount / 4);
            } finally {
                gpu.closePass(pass);
            }
            return true;
        } catch (Throwable t) {
            if (failureLogCount < 5) {
                failureLogCount++;
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                LauncherLog.err("[UiRenderer] Blaze3DText.drawTextBatch a échoué #" + failureLogCount
                    + " à l'étape '" + currentStage + "' : " + t + " | cause réelle : " + cause);
            }
            return false;
        }
    }

    private static boolean drawText(UiFont font, String text, float x, float y, UiColor color, float scale, int vpWidth, int vpHeight) {
        if (!isAvailable() || !resolve()) return false;
        try {
            currentStage = "mainColorView";
            Object colorView = gpu.mainColorView();
            if (colorView == null) return false;

            currentStage = "ensureTexture";
            Object[] tex = ensureTexture(font);
            Object textureView = tex[1], sampler = tex[2];

            currentStage = "device";
            Object device = gpu.device();
            currentStage = "encoder";
            Object encoder = gpu.encoder(device);

            ByteBuffer verts = ensureStagingBuffer(text.length() * 4 * 28);
            // Sommets BLANCS : la couleur passe par ColorModulator sur ce
            // chemin (voir TEXT_FRAGMENT_SRC, qui multiplie les deux).
            int vertexCount = appendGlyphs(verts, font, text, x, y, scale, 0xFFFFFFFF);
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
            currentStage = "writeToBuffer";
            gpu.write(encoder, gpu.slice(vbo, 0L, verts.remaining()), verts);

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
            currentStage = "dynamicTransforms";
            Object dynSlice = gpu.dynamicTransforms(color.r, color.g, color.b, color.a);

            // Notre propre "Projection" (voir resolve() pour le pourquoi) — même
            // règle que les deux écritures ci-dessus : AVANT createRenderPass.
            currentStage = "ensureProjectionBuffer";
            Object projectionBuf = ensureProjectionBuffer(device, encoder, vpWidth, vpHeight);
            Object projectionSlice = gpu.slice(projectionBuf, 0L, 64L);

            currentStage = "openPass";
            Object pass = gpu.openPass(encoder, "yuyuframe_text", colorView);
            try {
                currentStage = "setPipeline";
                // Vérifié contre UniversalCraft (URenderPipeline.kt) : re-précompiler
                // À CHAQUE draw, pas une seule fois — no-op si déjà en cache, mais
                // nécessaire après un rechargement de ressources (F3+T, resource
                // pack) qui vide le cache de pipelines du device.
                gpu.precompile(device, textPipeline, textShaderSource);
                gpu.setPipeline(pass, textPipeline);
                currentStage = "disableScissor";
                gpu.disableScissor(pass);
                currentStage = "bindDefaultUniforms";
                gpu.bindDefaultUniforms(pass);

                // Écrase le "Projection" ambiant repris par bindDefaultUniforms
                // avec le nôtre (voir resolve() + ensureProjectionBuffer) — un
                // setUniform() APRÈS un autre sur le même nom remplace le
                // binding précédent (juste une liaison, pas une accumulation).
                currentStage = "setUniform(Projection)";
                gpu.setUniform(pass, "Projection", projectionSlice);

                currentStage = "setUniform(DynamicTransforms)";
                gpu.setUniform(pass, "DynamicTransforms", dynSlice);

                currentStage = "bindTexture(Sampler0)";
                gpu.bindTexture(pass, "Sampler0", textureView, sampler);
                // Plus de Sampler2/lightmap sur ce pipeline dédié (voir
                // TEXT_FRAGMENT_SRC, ne le déclare même pas) — inutile
                // maintenant que texte/rect ne partagent plus le même shader.

                currentStage = "setVertexBuffer";
                gpu.setVertexBuffer(pass, 0, vbo);

                // BUG TROUVÉ (draw() "réussissait" sans exception mais AUCUN
                // texte jamais visible) : le pipeline GUI_TEXT utilise le mode
                // QUADS (voir VertexFormats.POSITION_COLOR_TEXTURE_LIGHT) —
                // draw(int,int) non-indexé ne convertit PAS les quads en
                // triangles tout seul. Il faut passer par un draw INDEXÉ avec
                // le buffer d'indices de triangulation PARTAGÉ que Minecraft
                // utilise lui-même pour ça (RenderSystem.sharedSequentialQuad)
                // — c'est ce que fait drawQuads (6 indices par quad, et l'ordre
                // de paramètres réel baseVertex/firstIndex/count/instanceCount,
                // autre bug historique : une inversion ne soumettait ZÉRO
                // géométrie, sans la moindre exception).
                currentStage = "drawQuads";
                gpu.drawQuads(pass, vertexCount / 4);
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
                gpu.closePass(pass);
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
}
