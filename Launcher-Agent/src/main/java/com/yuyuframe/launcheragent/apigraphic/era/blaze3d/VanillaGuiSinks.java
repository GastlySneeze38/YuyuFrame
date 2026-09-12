package com.yuyuframe.launcheragent.apigraphic.era.blaze3d;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.ServiceLoader;

/**
 * Point d'accès à la {@link VanillaGuiSink} de la version en cours.
 *
 * <p>Découverte par {@link ServiceLoader}, comme {@code Blaze3DGpus} et pour la
 * même raison : l'implémentation d'une version est compilée APRÈS le moteur
 * (unité séparée, voir {@code build.bat}), qui ne peut donc pas la référencer.
 *
 * <p>Sans implémentation déclarée pour la version, repli sur
 * {@link VanillaGuiSink261} — inoffensif ailleurs qu'en 26.1.2, où son
 * {@code accepts()} répond {@code false} (le contexte du hook n'y est pas un
 * {@code GuiGraphicsExtractor}) et où l'appelant garde son chemin habituel.
 */
public final class VanillaGuiSinks {

    private VanillaGuiSinks() {
    }

    private static boolean resolved;
    private static VanillaGuiSink active;

    public static synchronized VanillaGuiSink active() {
        if (resolved) return active;
        resolved = true;
        String version = System.getProperty("launcheragent.mcVersion", "");
        try {
            ServiceLoader<VanillaGuiSinkProvider> providers =
                ServiceLoader.load(VanillaGuiSinkProvider.class, VanillaGuiSinkProvider.class.getClassLoader());
            for (VanillaGuiSinkProvider provider : providers) {
                if (!provider.supports(version)) continue;
                active = provider.create();
                LauncherLog.agent(3, "[VanillaGuiSinks] état de GUI vanilla : " + active.id()
                    + " (version " + version + ")");
                return active;
            }
        } catch (Throwable t) {
            LauncherLog.err("[VanillaGuiSinks] découverte des implémentations échouée : " + t);
        }
        active = new VanillaGuiSink261();
        LauncherLog.agent(3, "[VanillaGuiSinks] aucune implémentation déclarée pour \"" + version
            + "\" — repli sur l'état de GUI 26.1.2");
        return active;
    }
}
