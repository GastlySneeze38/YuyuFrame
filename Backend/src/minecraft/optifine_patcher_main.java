package com.yuyuframe.optifinepatcher;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Pont minimal vers {@code optifine.Patcher.process(File, File, File)}.
 *
 * L'installeur officiel d'OptiFine n'a pas de mode CLI/headless documenté
 * pour l'installation "in-place" (pré-1.13, notre cas 1.8.9) : double-clic
 * dessus ouvre toujours une fenêtre Swing demandant de choisir le dossier
 * .minecraft. Cette classe utilitaire interne (non documentée officiellement
 * par OptiFine, mais son usage par réflexion est une pratique bien établie
 * dans l'écosystème des launchers tiers open source comme HMCL/PCL) fait
 * exactement ce que fait le bouton "Install" de cette fenêtre — fusionner
 * les classes OptiFine dans une copie du jar client vanilla — sans jamais
 * afficher quoi que ce soit. Impossible à réimplémenter nous-mêmes sans ce
 * pont : OptiFine modifie aussi des classes vanilla existantes via un format
 * de patch binaire propriétaire non documenté, connu uniquement de son
 * propre code (voir Launcher-Client/Backend/src/minecraft/optifine.rs pour
 * le contexte complet côté Rust, qui compile et invoque ce fichier).
 *
 * AUCUN jar OptiFine n'est distribué avec ce projet : le chemin fourni en
 * argument [2] pointe vers un fichier que l'UTILISATEUR a téléchargé
 * lui-même depuis optifine.net (licence OptiFine, https://optifine.net/copyright,
 * interdit toute redistribution).
 *
 * Signature confirmée par désassemblage (javap) d'un vrai jar OptiFine_1.8.9_HD_U_M5 :
 * {@code public static void main(String[])} affiche l'usage
 * "Patcher <base.jar> <diff.jar> <mod.jar>" et délègue à
 * {@code process(File base, File diff, File mod)} — donc des {@link File},
 * pas des {@link String}, et le jar de sortie ("mod") est le
 * TROISIÈME argument, "diff" (le jar OptiFine lui-même, qui embarque le
 * patch binaire) étant le second.
 *
 * Args (ordre de CE pont, ré-ordonnés en interne vers l'ordre réel de
 * Patcher.process avant l'appel) : [0] chemin du jar client vanilla source,
 * [1] chemin du jar de sortie (patché), [2] chemin du jar OptiFine fourni
 * par l'utilisateur.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: Main <vanillaJar> <outputJar> <optifineJar>");
            System.exit(1);
        }
        String vanillaJar = args[0];
        String outputJar = args[1];
        String optifineJarPath = args[2];

        File optifineJarFile = new File(optifineJarPath);
        if (!optifineJarFile.isFile()) {
            System.err.println("Jar OptiFine introuvable : " + optifineJarPath);
            System.exit(2);
        }

        try (URLClassLoader loader = new URLClassLoader(new URL[]{optifineJarFile.toURI().toURL()})) {
            Class<?> patcherClass;
            try {
                patcherClass = Class.forName("optifine.Patcher", true, loader);
            } catch (ClassNotFoundException e) {
                System.err.println("Classe optifine.Patcher introuvable dans " + optifineJarPath
                    + " — le jar fourni n'est peut-être pas un installeur OptiFine valide, ou le nom de classe a changé selon la version.");
                System.exit(3);
                return;
            }

            Method process;
            try {
                process = patcherClass.getMethod("process", File.class, File.class, File.class);
            } catch (NoSuchMethodException e) {
                System.err.println("Méthode optifine.Patcher.process(File,File,File) introuvable — signature différente sur cette version d'OptiFine.");
                System.exit(4);
                return;
            }

            // Ordre réel de Patcher.process : (base=vanilla, diff=jar OptiFine, mod=sortie)
            Object result = process.invoke(null, new File(vanillaJar), optifineJarFile, new File(outputJar));
            System.out.println("OK: " + result);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // La vraie erreur vient de Patcher.process() lui-même — la cause,
            // pas l'InvocationTargetException (simple enveloppe de réflexion).
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("Échec du patch OptiFine (dans optifine.Patcher.process) : " + cause);
            cause.printStackTrace();
            System.exit(5);
        } catch (Throwable t) {
            System.err.println("Échec du patch OptiFine : " + t);
            t.printStackTrace();
            System.exit(5);
        }
    }
}
