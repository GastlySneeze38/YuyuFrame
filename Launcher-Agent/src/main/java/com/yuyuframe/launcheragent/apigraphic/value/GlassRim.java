package com.yuyuframe.launcheragent.apigraphic.value;

/**
 * Le relief des surfaces de verre, en trois effets qui se complètent :
 *
 * <ul>
 *   <li><b>la réfraction</b> ({@code glassBend}) : près du bord, un verre
 *       épais dévie la lumière — ce qu'on voit à travers est décalé, de plus
 *       en plus fort en approchant de la tranche, et les couleurs ne dévient
 *       pas toutes autant (un léger liseré coloré). C'est l'effet qui dit
 *       « ceci a une épaisseur » même sur un panneau très transparent ;</li>
 *   <li><b>le liseré de lumière</b> ({@code glassRim}) : la tranche éclairée
 *       de biais, vive en haut à gauche et en bas à droite, presque éteinte
 *       sur les deux autres coins, doublée d'un biseau doux ;</li>
 *   <li><b>l'ombre du biseau</b> ({@code glassShadow}) : là où la tranche ne
 *       prend pas la lumière, elle s'assombrit un peu. Sans ce contraste le
 *       liseré seul se lit comme un contour.</li>
 * </ul>
 *
 * <p>Écrit UNE fois, en GLSL, et recopié tel quel par les trois composites de
 * verre (écrans Blaze3D, HUD dans l'état de GUI vanilla, 1.8.9) : trois
 * réglages séparés finiraient par donner trois verres différents. Ce n'est
 * qu'une constante de chaîne — javac la recopie dans chaque classe qui la
 * cite, aucune ne charge celle-ci à l'exécution.
 *
 * <p>Compatible GLSL 150 : aucune dérivée ici, l'appelant donne la taille d'un
 * pixel. Paramètres communs aux trois fonctions :
 *
 * <ul>
 *   <li>{@code p} : position par rapport au centre du panneau, <b>y vers le haut</b> ;</li>
 *   <li>{@code q} : {@code abs(p) - demiTaille + rayon}, le terme de la distance signée d'une boîte arrondie ;</li>
 *   <li>{@code dist} : cette distance signée (négative à l'intérieur) ;</li>
 *   <li>{@code px} : taille d'un pixel d'écran dans l'unité de {@code p} ;</li>
 *   <li>{@code halfMin} : la plus petite demi-dimension du panneau, dans l'unité de {@code p}.</li>
 * </ul>
 */
public final class GlassRim {
    private GlassRim() {}

    /**
     * Portée de la réfraction hors du panneau, en pixels d'écran : le bord lit
     * le fond jusqu'à cette distance au-delà de lui. Qui ne floute que
     * derrière les panneaux (le HUD) doit élargir ses zones d'autant.
     */
    public static final float BEND_REACH_PX = 32f;

    public static final String GLSL =
        // Normale du bord le plus proche (y vers le haut) : radiale dans un coin arrondi, sinon celle du côté.
        "vec2 glassNormal(vec2 p, vec2 q) {\n" +
        "    vec2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q)\n" +
        "        : ((q.x > q.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0));\n" +
        "    return n * vec2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);\n" +
        "}\n" +
        // La lumière vient d'en haut à gauche ; le bord opposé la renvoie aussi
        // (valeurs absolues). Deux termes :
        //  - l'ORIENTATION du bord : 1 sur l'arc du coin éclairé, 0.7 sur les
        //    deux côtés qui le touchent, 0 sur les coins à l'ombre ;
        //  - la POSITION le long du panneau : 1 au coin éclairé, 0.5 au milieu
        //    d'un côté éclairé, 0 au coin suivant.
        // L'orientation seule est constante sur tout un côté droit : il était
        // soit éclairé d'un bout à l'autre, soit pas du tout. Avec la position,
        // le clair part du coin, court sur une partie du côté et s'éteint un
        // peu après son milieu — un reflet, pas un trait.
        "float glassLight(vec2 p, vec2 q, vec2 hs) {\n" +
        "    vec2 toLight = vec2(-0.7071, 0.7071);\n" +
        "    float facing = abs(dot(glassNormal(p, q), toLight));\n" +
        "    float along = abs(dot(p / max(hs, vec2(0.0001)), toLight)) * 0.7071;\n" +
        "    return facing * smoothstep(0.25, 0.85, along);\n" +
        "}\n" +
        // Épaisseur du verre, de 0.25 à 1 : un panneau dont la demi-largeur
        // (ou demi-hauteur) fait moins de 28 pixels est un verre plus MINCE.
        // Sans ça, le biseau et l'ombre pensés pour une carte mangent toute la
        // surface d'un petit élément (une touche, un compteur) et le salissent.
        "float glassDepth(float halfMin, float px) {\n" +
        "    return clamp(halfMin / (28.0 * px), 0.25, 1.0);\n" +
        "}\n" +
        // Décalage du fond vu à travers le bord, en PIXELS D'ÉCRAN (y vers le
        // haut). Verre épais : nul au-delà de 16 pixels du bord, 24 sur la tranche.
        "vec2 glassBend(vec2 p, vec2 q, float dist, float px, float halfMin) {\n" +
        "    float k = glassDepth(halfMin, px);\n" +
        "    float t = clamp(1.0 - max(-dist, 0.0) / (16.0 * k * px), 0.0, 1.0);\n" +
        "    return glassNormal(p, q) * (t * t * 24.0 * k);\n" +
        "}\n" +
        // Part de lumière à ajouter, de 0 à 1 : liseré net + biseau doux. Le
        // liseré fait TOUT le tour, mais en GRIS discret (0.08) : il n'est
        // clair que là où la lumière tape (voir glassLight), et même là il
        // reste loin du blanc (0.36) — un liseré blanc partout se lit comme
        // un contour, pas comme un reflet. Baissés le 2026-10-11 avec toute la
        // palette (0.14 et 0.66 avant, biseau 0.14) : le clair était trop
        // prononcé. hs : demi-taille du panneau.
        "float glassRim(vec2 p, vec2 q, float dist, float px, vec2 hs) {\n" +
        "    float k = glassDepth(min(hs.x, hs.y), px);\n" +
        "    float lit = glassLight(p, q, hs);\n" +
        "    float inside = max(-dist, 0.0);\n" +
        // Plein sur les 0.75 premiers pixels, puis fondu sur deux pixels. Le
        // liseré tombait d'abord à zéro en un pixel et demi DEPUIS le bord :
        // son maximum se trouvait donc pile dans la frange d'anticrénelage du
        // panneau (à moitié transparente), et il ne restait presque rien sur
        // les pixels pleins — d'où des coins éclairés en escalier. Avec ce
        // plateau, ce sont des pixels opaques qui portent la lumière, et elle
        // s'éteint en douceur vers l'intérieur.
        "    float edge = 1.0 - smoothstep(0.75 * px, 2.75 * px, inside);\n" +
        "    float bevel = 1.0 - smoothstep(0.0, 10.0 * k * px, inside);\n" +
        "    return clamp(edge * mix(0.08, 0.36, lit) + bevel * bevel * lit * 0.08 * k, 0.0, 1.0);\n" +
        "}\n" +
        // Part d'ombre, de 0 à 1 : le biseau là où il ne prend pas la lumière.
        "float glassShadow(vec2 p, vec2 q, float dist, float px, vec2 hs) {\n" +
        "    float k = glassDepth(min(hs.x, hs.y), px);\n" +
        "    float inside = max(-dist, 0.0);\n" +
        "    float bevel = 1.0 - smoothstep(0.0, 10.0 * k * px, inside);\n" +
        // Rien sur le liseré lui-même : l'ombre commence derrière lui.
        "    float behindEdge = smoothstep(2.0 * px, 3.75 * px, inside);\n" +
        "    return bevel * bevel * behindEdge * (1.0 - glassLight(p, q, hs)) * 0.26 * k;\n" +
        "}\n";
}
