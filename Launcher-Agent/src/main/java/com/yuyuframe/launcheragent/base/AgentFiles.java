package com.yuyuframe.launcheragent.base;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Le dossier de l'agent : celui qui contient {@code launcher-agent.jar},
 * {@code content_core.dll}, {@code launcher-agent.properties} et
 * {@code logs/}.
 *
 * <p>Ce n'est pas toujours {@code %APPDATA%\YuyuFrame\agent} : le launcher
 * installe l'agent dans son dossier de stockage ({@code paths::root()}), que
 * l'utilisateur peut déplacer. Tout le monde l'écrivait en dur jusqu'au
 * 2026-10-18 — un dossier déplacé, et le jeu ne trouvait plus ni la DLL ni
 * les options. Le dossier est donc lu là où le jeu a vraiment trouvé l'agent :
 * à côté du jar.
 *
 * <p>La première copie de cette classe (celle du classloader système, appelée
 * en tête de {@code premain}) le relève et le publie dans une propriété
 * système. Les autres copies (classloader isolé, celui d'un loader qui ne
 * donne pas d'adresse {@code file:} pour ses classes) la relisent.
 */
public final class AgentFiles {

    private static final String PROPERTY = "launcheragent.dir";

    private static final Path DIR = locate();

    private AgentFiles() {}

    /** Le dossier de l'agent, ou {@code null} si rien ne permet de le connaître. */
    public static Path dir() {
        return DIR;
    }

    /** Un fichier du dossier de l'agent, ou {@code null} si le dossier est inconnu. */
    public static Path file(String first, String... more) {
        if (DIR == null) return null;
        return DIR.resolve(Path.of(first, more));
    }

    /** {@code content_core.dll}, ou {@code null} si le dossier est inconnu. */
    public static Path contentCore() {
        return file("content_core.dll");
    }

    private static Path locate() {
        String published = System.getProperty(PROPERTY);
        if (published != null && !published.isEmpty()) return Path.of(published);

        Path found = besideJar();
        if (found == null) {
            // Classes lues hors d'un jar (tests) : l'emplacement par défaut du launcher.
            String appData = System.getenv("APPDATA");
            if (appData != null) found = Path.of(appData, "YuyuFrame", "agent");
        }
        if (found != null) System.setProperty(PROPERTY, found.toString());
        return found;
    }

    private static Path besideJar() {
        try {
            java.security.CodeSource source = AgentFiles.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            java.net.URI uri = source.getLocation().toURI();
            if (!"file".equals(uri.getScheme())) return null;
            Path jar = Path.of(uri);
            return Files.isRegularFile(jar) ? jar.getParent() : null;
        } catch (Exception e) {
            // LauncherLog lit son chemin ici : il ne peut pas encore servir.
            System.err.println("[LauncherAgent] dossier de l'agent introuvable à côté du jar : " + e);
            return null;
        }
    }
}
