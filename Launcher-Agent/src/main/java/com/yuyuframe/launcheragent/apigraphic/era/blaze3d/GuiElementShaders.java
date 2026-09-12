package com.yuyuframe.launcheragent.apigraphic.era.blaze3d;

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
}
