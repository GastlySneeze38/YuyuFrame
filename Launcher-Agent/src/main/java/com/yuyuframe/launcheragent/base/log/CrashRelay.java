package com.yuyuframe.launcheragent.base.log;

/**
 * Rend visibles, dans la console du launcher, les plantages que le jeu garde
 * pour lui — installé en tout début de {@code premain}.
 *
 * <h2>Pourquoi (2026-09-15)</h2>
 *
 * Premier lancement de la 1.8.9 dégelée : le jeu s'arrête juste après
 * « Created: 512x512 textures-atlas », sans rien d'autre en console. La cause
 * ({@code NoSuchMethodError} sur {@code GL20.glShaderSource}) n'existait que
 * dans {@code crash-reports/crash-….txt} : Minecraft 1.8.9 redirige
 * {@code System.out}/{@code err} vers log4j, et son rapport n'atteint ni le
 * flux lu par le launcher ni {@code launcher-agent.log}.
 *
 * <h2>Deux filets, aucune dépendance à une version</h2>
 * <ul>
 *   <li><b>rapports de crash</b> — à l'arrêt de la JVM (le jeu fait
 *       {@code System.exit} après avoir écrit son rapport), tout fichier de
 *       {@code crash-reports/} écrit PENDANT cette session est recopié ligne à
 *       ligne en erreur. Le dossier est relatif au répertoire courant, que le
 *       launcher fixe au dossier de jeu de l'instance ;</li>
 *   <li><b>exceptions non attrapées</b> — sur n'importe quel thread, journalisées
 *       avec leur pile, puis transmises au gestionnaire précédent.</li>
 * </ul>
 * Passe par {@link LauncherLog#err}, donc toujours affiché quel que soit le
 * seuil des catégories.
 */
public final class CrashRelay {

    private CrashRelay() {
    }

    /** Au-delà, le reste du rapport (détails système, liste des classes) est dans le fichier. */
    private static final int MAX_LINES = 300;

    private static volatile boolean installed;

    public static void install() {
        if (installed) return;
        installed = true;

        // Une seconde de marge : la résolution de lastModified est grossière
        // sur certains systèmes de fichiers.
        final long sessionStart = System.currentTimeMillis() - 1000L;
        final java.io.File crashDir = new java.io.File("crash-reports").getAbsoluteFile();

        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            LauncherLog.err("[CrashRelay] exception non attrapée sur le thread « " + thread.getName() + " »", error);
            if (previous != null) previous.uncaughtException(thread, error);
        });

        LauncherLog.addShutdownTask(() -> relayCrashReports(crashDir, sessionStart));
    }

    private static void relayCrashReports(java.io.File crashDir, long sessionStart) {
        java.io.File[] reports = crashDir.listFiles(
            f -> f.isFile() && f.getName().endsWith(".txt") && f.lastModified() >= sessionStart);
        if (reports == null || reports.length == 0) return;
        java.util.Arrays.sort(reports, java.util.Comparator.comparingLong(java.io.File::lastModified));

        for (java.io.File report : reports) {
            LauncherLog.err("[CrashRelay] ===== rapport de crash Minecraft : " + report + " =====");
            try (java.io.BufferedReader in = java.nio.file.Files.newBufferedReader(
                    report.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                String line;
                int count = 0;
                while ((line = in.readLine()) != null) {
                    if (count++ >= MAX_LINES) {
                        LauncherLog.err("[CrashRelay] … rapport tronqué à " + MAX_LINES + " lignes, suite dans le fichier");
                        break;
                    }
                    LauncherLog.err("[CrashRelay] " + line);
                }
            } catch (Throwable t) {
                LauncherLog.err("[CrashRelay] lecture impossible de " + report, t);
            }
        }
    }
}
