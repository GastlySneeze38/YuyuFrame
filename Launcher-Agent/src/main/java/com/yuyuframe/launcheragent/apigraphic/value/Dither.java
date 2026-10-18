package com.yuyuframe.launcheragent.apigraphic.value;

/**
 * Tramage des couleurs calculées par pixel, contre les paliers des dégradés.
 *
 * <p>L'écran ne code que 256 niveaux par canal : un dégradé parfaitement lisse
 * dans le shader se quantifie en bandes visibles dès qu'il s'étale sur une
 * grande zone, surtout dans les tons sombres où deux niveaux voisins
 * s'écartent le plus à l'œil. Un bruit d'au plus un demi-niveau, différent à
 * chaque pixel, casse ces bandes. Une couleur unie n'en change pas : un
 * niveau exact plus moins d'un demi-niveau s'arrondit au même niveau.
 *
 * <p>Bruit à gradient entrelacé (Jimenez, « Next Generation Post Processing
 * in Call of Duty: Advanced Warfare », 2014) : stable d'une image à l'autre,
 * sans motif répété visible, et sans {@code sin} dont la précision varie
 * d'un pilote à l'autre.
 *
 * <p>Comme {@link GlassRim} : une constante de chaîne, recopiée par javac dans
 * chaque shader qui la cite, aucune classe chargée à l'exécution. Écrite une
 * fois pour tous les shaders de couleur (rects, dégradés, verre, 1.8.9
 * comprise) — compatible GLSL 150.
 */
public final class Dither {
    private Dither() {}

    /** {@code vec4 dither(vec4 color)} — à appliquer sur la couleur finale, juste avant de l'écrire. */
    public static final String GLSL =
        "vec4 dither(vec4 color) {\n" +
        "    float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5;\n" +
        "    return clamp(color + vec4(n / 255.0), 0.0, 1.0);\n" +
        "}\n";
}
