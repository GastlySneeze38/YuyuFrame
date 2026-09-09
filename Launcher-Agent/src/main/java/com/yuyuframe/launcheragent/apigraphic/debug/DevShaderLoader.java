package com.yuyuframe.launcheragent.apigraphic.debug;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Hot-reload des sources GLSL en dev (ROADMAP-agent.md Phase 4.5) — recharger
 * un {@code .frag}/{@code .vert} sans relancer Minecraft. Accélère
 * directement l'itération de la Phase 5 (blur, dégradés, clip), où chaque
 * essai de shader coûterait sinon un relaunch complet.
 *
 * Fonctionnement : si la system property {@code launcheragent.shaderDevDir}
 * est posée (répertoire contenant des fichiers {@code <name>.vert}/{@code
 * <name>.frag}), {@link #load} lit CE fichier à chaque appel (pas de cache —
 * c'est tout l'intérêt, l'itération doit être instantanée) ; sinon (défaut,
 * comportement de production), renvoie {@code embeddedFallback} tel quel —
 * ZÉRO changement de comportement pour un utilisateur normal, cette classe
 * reste inerte tant que la property n'est pas posée manuellement par un dev.
 *
 * VOLONTAIREMENT SÉPARÉ de la recompilation des programmes GL — cette classe
 * ne fait QUE la lecture de texte. Le rebranchement des ~18 constantes de
 * shader existantes (UiRenderer/UiPrimitiveRenderer/UiTextRenderer) sur ce
 * loader, et l'invalidation des programmes GL déjà compilés/en cache, est
 * laissé au rework de la Phase 5 — les extraire maintenant reviendrait à
 * retoucher un moteur sur le point d'être repris de zéro (voir audit
 * ROADMAP-agent.md §3.3). Utilisable dès aujourd'hui pour tout NOUVEAU
 * shader écrit pendant la Phase 5 elle-même.
 */
public final class DevShaderLoader {
    private DevShaderLoader() {}

    private static final String PROP_DEV_DIR = "launcheragent.shaderDevDir";

    /** {@code true} si un répertoire de dev est configuré — voir {@link #load}. */
    public static boolean isDevModeActive() {
        String dir = System.getProperty(PROP_DEV_DIR);
        return dir != null && !dir.isEmpty();
    }

    /**
     * @param name             nom du shader SANS extension (ex: "rect_modern").
     * @param extension        "vert" ou "frag".
     * @param embeddedFallback source Java en dur (constante existante) — utilisée
     *                         telle quelle si le mode dev n'est pas actif, ou si
     *                         le fichier attendu est introuvable/illisible.
     */
    public static String load(String name, String extension, String embeddedFallback) {
        String devDir = System.getProperty(PROP_DEV_DIR);
        if (devDir == null || devDir.isEmpty()) return embeddedFallback;
        try {
            Path file = Paths.get(devDir, name + "." + extension);
            if (!Files.isRegularFile(file)) return embeddedFallback;
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException t) {
            LauncherLog.err("[DevShaderLoader] lecture " + name + "." + extension + " échouée, repli sur la source embarquée : " + t);
            return embeddedFallback;
        }
    }
}
