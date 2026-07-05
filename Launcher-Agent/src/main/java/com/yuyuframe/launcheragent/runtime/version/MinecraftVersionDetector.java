package com.yuyuframe.launcheragent.runtime.version;

/**
 * Détecte la version Minecraft au runtime, avant tout chargement de Mixin.
 *
 * Ordre de priorité :
 *  1. -Dminecraft.version (posé par le launcher vanilla depuis 1.6)
 *  2. -Dminecraft.gameVersion (LaunchWrapper / ancien Forge)
 *  3. Sonde de classes bien connues (fallback, moins fiable)
 */
public final class MinecraftVersionDetector {

    private MinecraftVersionDetector() {}

    public static String detect() {
        String prop = System.getProperty("minecraft.version");
        if (prop != null && !prop.isEmpty()) return prop;

        String lwProp = System.getProperty("minecraft.gameVersion");
        if (lwProp != null && !lwProp.isEmpty()) return lwProp;

        // Sonde de classes — fonctionne seulement si MC est déjà partiellement chargé
        if (classExists("net.minecraft.client.gui.screen.TitleScreen")) return "1.14+";
        if (classExists("net.minecraft.client.gui.GuiMainMenu"))        return "1.8.9";

        return "unknown";
    }

    /** Vrai si la version correspond à Minecraft 1.8.x (legacy). */
    public static boolean isLegacy189(String version) {
        return version != null && version.startsWith("1.8");
    }

    /**
     * Vrai si cette version expose ENCORE un contexte OpenGL "à fonctions
     * fixes" (glBegin/glMatrixMode, profil de compatibilité — en réalité GL
     * 2.x, qui n'a même pas la notion de profil Core/Compatibility, propre à
     * GL 3.2+) — par opposition au Core Profile OpenGL 3.2 obligatoire depuis
     * la 1.17 (pile de matrices supprimée, tout doit passer par des shaders,
     * confirmé par tests réels : glMatrixMode plante NATIVEMENT sur 1.21.11).
     *
     * INDÉPENDANT de {@link #isLegacy189} : une version 1.13-1.16.x utilise
     * DÉJÀ LWJGL3/GLFW (donc {@code UiInputPollerModern}, pas
     * {@code UiInputPollerLegacy}) mais peut ENCORE dessiner en immédiat comme
     * la 1.8.9 — voir {@code UiRenderer.modern}, qui ne pilote QUE le style de
     * dessin (legacy vs shader/VAO), jamais le choix de l'input poller (décidé
     * séparément par CHAQUE classe Mixin selon son propre bracket).
     *
     * Version inconnue → false (suppose Core Profile, le choix le plus sûr :
     * un faux positif ici tenterait glMatrixMode sur un contexte qui ne le
     * supporte pas, risque de crash natif confirmé par le passé sur ce projet).
     */
    public static boolean supportsFixedFunctionDrawing(String version) {
        if (isLegacy189(version)) return true;
        int[] v = parseMajorMinor(version);
        if (v == null) return false;
        return v[0] == 1 && v[1] >= 13 && v[1] < 17;
    }

    /** Parse "1.16.5" → {1, 16} ; {@code null} si le format ne suit pas ce schéma "X.Y[.Z]". */
    private static int[] parseMajorMinor(String version) {
        if (version == null) return null;
        String[] parts = version.split("\\.");
        if (parts.length < 2) return null;
        try {
            return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Vrai si Forge est sur le classpath (1.8.9 + Forge = classes déjà remappées MCP). */
    public static boolean isForgePresent() {
        return classExists("net.minecraftforge.fml.common.Loader")
            || classExists("net.minecraftforge.common.MinecraftForge");
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
