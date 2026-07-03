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
