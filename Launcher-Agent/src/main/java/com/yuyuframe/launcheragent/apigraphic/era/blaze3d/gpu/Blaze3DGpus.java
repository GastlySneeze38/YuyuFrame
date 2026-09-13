package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.gpu;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.ServiceLoader;

/**
 * Point d'accès à l'implémentation {@link Blaze3DGpu} de la version en cours.
 *
 * <p>Découverte par {@link ServiceLoader} — mécanisme standard du JDK, sans
 * réflexion de notre part. C'est le seul moyen propre : l'implémentation d'une
 * version est compilée APRÈS le moteur (unité séparée, voir {@code build.bat}),
 * le moteur ne peut donc pas la référencer statiquement.
 *
 * <p>Résolue une fois. Sans implémentation typée pour la version : {@code null}
 * (journalisé), et {@code Blaze3DCore.resolve()} échoue franchement. Il y avait
 * ici, jusqu'à la v1106, un repli sur un adaptateur réflexif écrit pour la
 * 26.1.2 ; cette version ayant désormais son implémentation typée
 * ({@code Blaze3DGpu261}), le repli n'avait plus d'autre effet que de masquer
 * l'absence d'un fournisseur derrière un moteur qui semble démarrer.
 */
public final class Blaze3DGpus {

    private Blaze3DGpus() {}

    private static boolean resolved;
    private static Blaze3DGpu active;

    public static synchronized Blaze3DGpu active() {
        if (resolved) return active;
        resolved = true;
        String version = System.getProperty("launcheragent.mcVersion", "");
        try {
            // Classloader du moteur lui-même : sous Fabric c'est Knot, qui voit
            // notre jar (FabricKnotExposer) et donc le fichier de services.
            ServiceLoader<Blaze3DGpuProvider> providers =
                ServiceLoader.load(Blaze3DGpuProvider.class, Blaze3DGpuProvider.class.getClassLoader());
            for (Blaze3DGpuProvider provider : providers) {
                if (!provider.supports(version)) continue;
                active = provider.create();
                LauncherLog.agent(3, "[Blaze3DGpus] implémentation typée active : " + active.id()
                    + " (version " + version + ")");
                return active;
            }
        } catch (Throwable t) {
            LauncherLog.err("[Blaze3DGpus] découverte des implémentations échouée : " + t);
        }
        LauncherLog.err("[Blaze3DGpus] aucune implémentation Blaze3DGpu pour la version \"" + version
            + "\" — le moteur graphique ne peut pas démarrer. Une version supportée doit déclarer"
            + " son fournisseur dans META-INF/services (voir Blaze3DGpuProvider261/1211).");
        return null;
    }
}
