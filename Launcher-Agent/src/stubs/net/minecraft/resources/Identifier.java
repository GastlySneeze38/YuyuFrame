package net.minecraft.resources;

/**
 * Stub compile-only (26.1+, jamais remappé — nom réel Mojang déjà littéral,
 * pas de traduction Yarn/ScreenStubPatcher nécessaire ici, contrairement à
 * Click/KeyEvent) : juste assez pour typer un paramètre capturé par un
 * {@code @Inject}/{@code @Redirect} de {@code ClearOverlaysMixin261} — la
 * VRAIE classe du jeu est chargée au runtime, ce stub n'est jamais exécuté
 * (voir build.bat : "compile-only, non inclus dans le JAR final").
 */
public final class Identifier {
    public String getPath() { return ""; }
    public String getNamespace() { return ""; }

    /**
     * Visibilité NON confirmée dans le code existant (contrairement au
     * reste de ce stub) — tenté en méthode statique publique par analogie
     * avec {@code Component.literal(String)}/{@code Identifier.of(...)}
     * (motif habituel des factories vanilla). À vérifier en jeu avant de
     * s'appuyer dessus (voir audit ROADMAP-agent.md §3.3, moteur graphique).
     */
    public static Identifier withDefaultNamespace(String path) { return null; }
}
