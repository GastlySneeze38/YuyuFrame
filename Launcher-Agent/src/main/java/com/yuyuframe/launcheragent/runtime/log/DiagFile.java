package com.yuyuframe.launcheragent.runtime.log;

/**
 * TEMPORAIRE (diagnostic pipeline 1.8.9) — écrit directement dans un fichier,
 * indépendamment de System.out/err (dont la capture via le launcher Rust
 * s'est avérée peu fiable pendant ce diagnostic : lignes manquantes/perdues
 * sans rapport avec l'exécution réelle du code). Fichier à chemin fixe,
 * lisible directement depuis le disque après un test. À retirer une fois le
 * pipeline 1.8.9 validé en jeu.
 */
public final class DiagFile {

    private DiagFile() {}

    private static final String PATH =
        System.getenv("APPDATA") != null
            ? System.getenv("APPDATA") + "\\YuyuFrame\\agent\\diag.log"
            : "diag.log";

    public static synchronized void log(String msg) {
        try (java.io.FileWriter fw = new java.io.FileWriter(PATH, true)) {
            fw.write("[" + System.currentTimeMillis() + "] " + msg + "\n");
        } catch (Throwable ignored) {}
    }
}
