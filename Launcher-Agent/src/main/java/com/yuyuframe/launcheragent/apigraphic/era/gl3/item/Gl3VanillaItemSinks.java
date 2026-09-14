package com.yuyuframe.launcheragent.apigraphic.era.gl3.item;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.ServiceLoader;

/**
 * Récepteur d'items vanilla de la version en cours, ou {@code null} — même
 * découverte que {@code VanillaGuiSinks} : l'implémentation d'une version est
 * compilée APRÈS le moteur (unité séparée, voir {@code build.bat}), qui ne peut
 * donc pas la référencer.
 *
 * <p>Sans implémentation pour la version, {@code null} : la file garde son
 * plafond ({@code VanillaItemQueue}) et le dit une fois.
 */
public final class Gl3VanillaItemSinks {

    private Gl3VanillaItemSinks() {
    }

    private static boolean resolved;
    private static Gl3VanillaItemSink active;

    public static synchronized Gl3VanillaItemSink active() {
        if (resolved) return active;
        resolved = true;
        String version = System.getProperty("launcheragent.mcVersion", "");
        try {
            ServiceLoader<Gl3VanillaItemSinkProvider> providers =
                ServiceLoader.load(Gl3VanillaItemSinkProvider.class, Gl3VanillaItemSinkProvider.class.getClassLoader());
            for (Gl3VanillaItemSinkProvider provider : providers) {
                if (!provider.supports(version)) continue;
                active = provider.create();
                LauncherLog.agent(3, "[Gl3VanillaItemSinks] rendu d'items vanilla : " + active.id()
                    + " (version " + version + ")");
                return active;
            }
        } catch (Throwable t) {
            LauncherLog.err("[Gl3VanillaItemSinks] découverte des implémentations échouée : " + t);
        }
        LauncherLog.warn("[Gl3VanillaItemSinks] aucune implémentation pour \"" + version
            + "\" — icônes d'item vanilla non dessinées");
        return null;
    }
}
