package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.vanillagui;

import com.yuyuframe.launcheragent.apigraphic.era.blaze3d.pass.Blaze3DText;

/**
 * GLSL des éléments que l'agent soumet à l'état de GUI de vanilla — PARTAGÉ
 * entre les versions.
 *
 * <h2>Pourquoi un porteur à part</h2>
 *
 * Le rendu diffère d'une version à l'autre (interfaces d'élément, noms du
 * {@code VertexConsumer}, façon d'ajouter à l'état — voir {@link
 * VanillaGuiSink}), mais le SHADER, lui, est identique : même format de sommet
 * déclaré, même algèbre. Le dupliquer dans chaque unité de compilation aurait
 * garanti la divergence au premier correctif.
 *
 * <p>Ce sont des constantes de compilation ({@code static final String}) :
 * javac les recopie dans chaque appelant, y compris l'unité 1.21.11 — aucune
 * classe de ce paquet n'est donc chargée au runtime à cause d'elles.
 */
public final class GuiElementShaders {

    private GuiElementShaders() {
    }

    /**
     * Rect arrondi — format de sommet MAISON (voir {@code Blaze3DGuiRoundedRect}
     * pour la disposition : {@code UV0} = position locale, {@code UV1} =
     * demi-taille, {@code UV2} = 4 rayons empaquetés 2 par entier).
     */
    public static final String ROUNDED_RECT_VERTEX =
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
        "in vec4 Color;\n" +
        "in vec2 UV0;\n" +
        "in ivec2 UV1;\n" +
        "in ivec2 UV2;\n" +
        "out vec4 vertexColor;\n" +
        "out vec2 localPos;\n" +
        "flat out vec2 halfSize;\n" +
        "flat out vec4 radii;\n" +
        "void main() {\n" +
        "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n" +
        "    vertexColor = Color;\n" +
        "    localPos = UV0;\n" +
        "    halfSize = vec2(UV1);\n" +
        "    radii = vec4(float((UV2.x >> 8) & 255), float(UV2.x & 255),\n" +
        "                 float((UV2.y >> 8) & 255), float(UV2.y & 255));\n" +
        "}\n";

    /** SDF de boîte arrondie (formule d'Inigo Quilez), antialiasée sur un pixel. */
    public static final String ROUNDED_RECT_FRAGMENT =
        "#version 330\n" +
        "layout(std140) uniform DynamicTransforms {\n" +
        "    mat4 ModelViewMat;\n" +
        "    vec4 ColorModulator;\n" +
        "    vec3 ModelOffset;\n" +
        "    mat4 TextureMat;\n" +
        "};\n" +
        "in vec4 vertexColor;\n" +
        "in vec2 localPos;\n" +
        "flat in vec2 halfSize;\n" +
        "flat in vec4 radii;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    float radius = (localPos.y < 0.0)\n" +
        "        ? ((localPos.x < 0.0) ? radii.x : radii.y)\n" +
        "        : ((localPos.x < 0.0) ? radii.z : radii.w);\n" +
        "    vec2 q = abs(localPos) - halfSize + radius;\n" +
        "    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n" +
        "    float alpha = 1.0 - smoothstep(-0.5, 0.5, d);\n" +
        "    if (alpha <= 0.001) discard;\n" +
        "    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;\n" +
        "}\n";

    /**
     * Texte SDF — MÊMES sources que la file Blaze3D ({@code Blaze3DText}) : un
     * seul rendu de texte dans tout le moteur, quel que soit le chemin.
     */
    public static final String TEXT_VERTEX = Blaze3DText.TEXT_VERTEX_SRC;

    public static final String TEXT_FRAGMENT = Blaze3DText.TEXT_FRAGMENT_SRC;

    // ── Icône RGBA ────────────────────────────────────────────────────────
    //
    // Format {Position, Color, UV0} — le MÊME que le texte : seul le fragment
    // change, il rend la couleur réelle de l'image là où le texte lit un champ
    // de distance signée. La texture est l'atlas 2048² partagé avec la file
    // Blaze3D, donc une icône déjà packée pour celle-ci sert ici sans seconde
    // copie GPU.

    public static final String ICON_VERTEX =
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
        "in vec4 Color;\n" +
        "in vec2 UV0;\n" +
        "out vec4 vertexColor;\n" +
        "out vec2 texCoord;\n" +
        "void main() {\n" +
        "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n" +
        "    vertexColor = Color;\n" +
        "    texCoord = UV0;\n" +
        "}\n";

    public static final String ICON_FRAGMENT =
        "#version 330\n" +
        "layout(std140) uniform DynamicTransforms {\n" +
        "    mat4 ModelViewMat;\n" +
        "    vec4 ColorModulator;\n" +
        "    vec3 ModelOffset;\n" +
        "    mat4 TextureMat;\n" +
        "};\n" +
        "uniform sampler2D Sampler0;\n" +
        "in vec4 vertexColor;\n" +
        "in vec2 texCoord;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec4 texel = texture(Sampler0, texCoord);\n" +
        // Les textures d'effets vanilla sont largement transparentes ; jeter
        // les fragments vides évite de les blender pour rien.
        "    if (texel.a < 0.01) discard;\n" +
        // Couleur RÉELLE de l'image, modulée par la couleur de sommet — qui
        // sert d'opacité (blanc opaque = image telle quelle).
        "    fragColor = texel * vertexColor * ColorModulator;\n" +
        "}\n";

    // ── Vignette plein écran ──────────────────────────────────────────────
    //
    // Format {Position, Color, UV0, UV1, UV2} : UV0 = position locale, UV1 =
    // demi-taille, UV2.x = largeur du dégradé. UV2.y est INUTILISÉ mais doit
    // quand même être écrit à chaque sommet — un attribut déclaré et non écrit
    // fait échouer la construction du maillage.

    public static final String VIGNETTE_VERTEX =
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
        "in vec4 Color;\n" +
        "in vec2 UV0;\n" +
        "in ivec2 UV1;\n" +
        "in ivec2 UV2;\n" +
        "out vec4 vertexColor;\n" +
        "out vec2 localPos;\n" +
        // flat : constants par primitive — seule localPos doit varier.
        "flat out vec2 halfSize;\n" +
        "flat out float vSize;\n" +
        "void main() {\n" +
        "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n" +
        "    vertexColor = Color;\n" +
        "    localPos = UV0;\n" +
        "    halfSize = vec2(UV1);\n" +
        "    vSize = float(UV2.x);\n" +
        "}\n";

    /**
     * Reprend TRAIT POUR TRAIT la courbe de l'ancien shader GL brut — même
     * smootherstep, même dithering : le portage change le chemin de rendu, pas
     * l'apparence. Les commentaires d'origine sont des conclusions durement
     * acquises, ils restent ici.
     */
    public static final String VIGNETTE_FRAGMENT =
        "#version 330\n" +
        "layout(std140) uniform DynamicTransforms {\n" +
        "    mat4 ModelViewMat;\n" +
        "    vec4 ColorModulator;\n" +
        "    vec3 ModelOffset;\n" +
        "    mat4 TextureMat;\n" +
        "};\n" +
        "in vec4 vertexColor;\n" +
        "in vec2 localPos;\n" +
        "flat in vec2 halfSize;\n" +
        "flat in float vSize;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        // Profondeur dans la bande, NORMALISÉE par axe puis combinée en
        // longueur (2026-09-16). L'ancienne distance au bord le plus proche,
        // min(d.x, d.y), découpait l'écran en quatre trapèzes raccordés en
        // diagonale : pente discontinue sur les diagonales, d'où l'effet
        // « 4 rectangles sur les bords ». Ici u vaut 0 dans le rectangle
        // intérieur, 1 sur un bord ; sa longueur donne un dégradé continu aux
        // coins arrondis. Bande bornée à la demi-taille par axe : au-delà de
        // 50 % de l'écran, le côté court garde un bord plein au lieu de
        // dégénérer.
        "    vec2 band = min(vec2(vSize), halfSize);\n" +
        "    vec2 u = max(abs(localPos) - (halfSize - band), 0.0) / max(band, vec2(1.0));\n" +
        // smootherstep (Ken Perlin, 6t^5-15t^4+10t^3) : dérivée première ET
        // seconde nulles aux deux bornes, la référence pour ce type de
        // dégradé. Une tentative d'"ease-out" (1-t)^3 avait été essayée à
        // l'époque du chemin GL brut ; elle ne servait à rien, le bord net
        // venait d'un GL_ALPHA_TEST resté actif — problème qui ne peut plus
        // se poser ici, le pipeline déclarant lui-même son état.
        "    float t = clamp(1.0 - length(u), 0.0, 1.0);\n" +
        "    float eased = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);\n" +
        "    float alpha = 1.0 - eased;\n" +
        // Le framebuffer ne code que 256 niveaux par canal — même une courbe
        // parfaitement lisse en maths QUANTIFIE en paliers visibles sur une
        // zone large. Un bruit d'environ 1 LSB les casse (technique standard
        // contre le banding des dégradés plein écran).
        "    float dither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;\n" +
        "    alpha = clamp(alpha + dither / 128.0, 0.0, 1.0);\n" +
        "    if (alpha <= 0.001) discard;\n" +
        "    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;\n" +
        "}\n";

    // ── Verre dépoli ──────────────────────────────────────────────────────
    //
    // Géométrie et empaquetage IDENTIQUES au rect arrondi : seul le fragment
    // change, il échantillonne la texture floutée au lieu de peindre un aplat.

    /**
     * Force de teinte, figée faute de créneau libre par sommet. Reprend
     * {@code UiTheme.GLASS_STRENGTH_FIELD}, la seule valeur que le HUD
     * utilisait ({@code HudPanelRenderer} n'en passe pas d'autre).
     *
     * <p>Déclarée ici et non dans {@code Blaze3DGuiGlass} pour que ce porteur
     * reste autonome : il ne doit dépendre d'aucune classe qu'un chargement en
     * 1.21.11 déclencherait.
     */
    public static final float GLASS_TINT_STRENGTH = 0.35f;

    /**
     * Verre « dense » (les panneaux de HUD) : force de teinte = densité × ce
     * facteur. À l'opacité de HUD par défaut (≈ 0.47) cela redonne la teinte
     * d'avant ({@link #GLASS_TINT_STRENGTH}). Voir
     * {@code UiRenderer.drawDenseGlassPanel}, qui applique la même règle aux
     * autres rendus.
     */
    public static final float GLASS_DENSITY_TO_TINT = 0.75f;

    /** Densité sous laquelle un verre dense commence à s'effacer. */
    public static final float GLASS_DENSITY_FADE = 0.15f;

    /**
     * Combien de fois la texture floutée est plus petite que la fenêtre, par
     * côté. Doit valoir {@code 2^(Blaze3DBlur.COMPOSITE_LEVEL + 1)} — le quart
     * d'écran, donc 4 ; {@code Blaze3DBlur} le vérifie à son chargement.
     * L'arrondi des tailles de niveaux laisse un écart de quelques pixels au
     * plus sur toute la largeur de l'écran, invisible sur une image floue.
     */
    public static final int GLASS_BLUR_DOWNSCALE = 4;

    public static final String GLASS_VERTEX =
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
        "in vec4 Color;\n" +
        "in vec2 UV0;\n" +
        "in ivec2 UV1;\n" +
        "in ivec2 UV2;\n" +
        "out vec4 vertexColor;\n" +
        "out vec2 localPos;\n" +
        "flat out vec2 halfSize;\n" +
        "flat out vec4 radii;\n" +
        "void main() {\n" +
        "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n" +
        "    vertexColor = Color;\n" +
        "    localPos = UV0;\n" +
        "    halfSize = vec2(UV1);\n" +
        "    radii = vec4(float((UV2.x >> 8) & 255), float(UV2.x & 255),\n" +
        "                 float((UV2.y >> 8) & 255), float(UV2.y & 255));\n" +
        "}\n";

    public static final String GLASS_FRAGMENT =
        "#version 330\n" +
        "layout(std140) uniform DynamicTransforms {\n" +
        "    mat4 ModelViewMat;\n" +
        "    vec4 ColorModulator;\n" +
        "    vec3 ModelOffset;\n" +
        "    mat4 TextureMat;\n" +
        "};\n" +
        "uniform sampler2D Sampler0;\n" +
        "in vec4 vertexColor;\n" +
        "in vec2 localPos;\n" +
        "flat in vec2 halfSize;\n" +
        "flat in vec4 radii;\n" +
        "out vec4 fragColor;\n" +
        com.yuyuframe.launcheragent.apigraphic.value.GlassRim.GLSL +
        "void main() {\n" +
        // Coordonnée d'échantillonnage déduite du fragment, sans attribut ni
        // uniforme : gl_FragCoord est en pixels de FENÊTRE, et la texture
        // floutée couvre toute la fenêtre à une fraction de sa résolution.
        //
        // BUG CORRIGÉ (2026-10-10, « souvent un flou sur du noir ») : la
        // division se faisait par la taille de la texture SEULE. Or cette
        // texture est le quart d'écran : la coordonnée valait donc quatre fois
        // trop, et tout panneau hors du seizième bas-gauche de l'écran lisait
        // hors de l'image — le bord étiré, pas ce qu'il y a derrière lui.
        "    vec2 screen = vec2(textureSize(Sampler0, 0)) * " + GLASS_BLUR_DOWNSCALE + ".0;\n" +
        "    float radius = (localPos.y < 0.0)\n" +
        "        ? ((localPos.x < 0.0) ? radii.x : radii.y)\n" +
        "        : ((localPos.x < 0.0) ? radii.z : radii.w);\n" +
        "    vec2 q = abs(localPos) - halfSize + radius;\n" +
        "    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n" +
        "    float mask = 1.0 - smoothstep(-0.5, 0.5, d);\n" +
        "    if (mask <= 0.001) discard;\n" +
        // Relief du verre (voir GlassRim). localPos est en unités de GUI, y
        // vers le BAS : retourné, puisque la lumière et gl_FragCoord comptent
        // y vers le haut ; la taille d'un pixel d'écran dans cette unité vient
        // de la dérivée. Le fond est lu DÉCALÉ près du bord, chaque couleur un
        // peu différemment — la réfraction d'un verre épais.
        "    vec2 up = vec2(localPos.x, -localPos.y);\n" +
        "    float px = max(fwidth(localPos.x), 0.0001);\n" +
        "    float halfMin = min(halfSize.x, halfSize.y);\n" +
        "    vec2 bend = glassBend(up, q, d, px, halfMin);\n" +
        "    vec3 blurred = texture(Sampler0, (gl_FragCoord.xy + bend) / screen).rgb;\n" +
        "    if (dot(bend, bend) > 0.01) {\n" +
        "        blurred.r = texture(Sampler0, (gl_FragCoord.xy + bend * 1.30) / screen).r;\n" +
        "        blurred.b = texture(Sampler0, (gl_FragCoord.xy + bend * 0.70) / screen).b;\n" +
        "    }\n" +
        // L'alpha du sommet n'est PAS une transparence : c'est la DENSITÉ du
        // verre (le réglage « Opacité du HUD »), qui dose la teinte. Le verre
        // lui-même est opaque — s'il laissait passer le décor net à travers
        // lui, le flou ne se verrait qu'à moitié (c'était le cas : « le flou
        // n'est pas assez prononcé »). Il ne s'efface que quand la densité
        // tombe vers zéro.
        "    float density = vertexColor.a;\n" +
        "    vec3 rgb = mix(blurred, vertexColor.rgb, min(0.9, density * " + GLASS_DENSITY_TO_TINT + "));\n" +
        "    rgb = mix(rgb, vec3(0.0), glassShadow(up, q, d, px, halfSize));\n" +
        "    rgb = mix(rgb, vec3(1.0), glassRim(up, q, d, px, halfSize));\n" +
        "    float a = smoothstep(0.0, " + GLASS_DENSITY_FADE + ", density);\n" +
        "    fragColor = vec4(rgb, a * mask) * ColorModulator;\n" +
        "}\n";
}
