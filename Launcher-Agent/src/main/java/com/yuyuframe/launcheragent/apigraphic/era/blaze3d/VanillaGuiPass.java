package com.yuyuframe.launcheragent.apigraphic.era.blaze3d;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;

/**
 * Installe le rendu du HUD DANS la passe GUI de vanilla, sur
 * {@link HookPoint#HUD_EXTRACT_CHAT} — indépendant de la version.
 *
 * <h2>Pourquoi ici et plus dans {@code VanillaGuiLayer}</h2>
 *
 * {@code VanillaGuiLayer} est compilée contre les types 26.1.2 de l'état de
 * GUI : y laisser l'enregistrement du hook aurait fait échouer la liaison dès
 * que le handler s'exécute sur une autre version. Cette classe-ci ne manipule
 * que des poignées opaques et passe par {@link VanillaGuiSinks} — elle
 * fonctionne donc sur toutes les versions qui ont une sink.
 *
 * <h2>Le point d'accroche</h2>
 *
 * En tête de l'extraction du CHAT : à cet instant, tout le HUD vanilla est déjà
 * dans l'état de GUI et le chat ne l'est pas encore. Nos panneaux et nos icônes
 * d'item atterrissent donc exactement entre les deux — c'est ce qui corrige le
 * z-order (voir {@code docs/LauncherAgent/rendering-pipeline.md}).
 */
public final class VanillaGuiPass {

    private VanillaGuiPass() {
    }

    /**
     * Rendu du HUD à exécuter depuis la passe GUI, injecté par l'appelant.
     *
     * <p>Découplage volontaire : {@code apigraphic} ne doit pas dépendre de
     * {@code runtime.ui.hud}, qui porte la POLITIQUE d'affichage (quels
     * éléments, visibles quand). C'est {@code ModuleRegistry} qui fournit
     * l'implémentation au moment de l'installation.
     */
    public interface HudPass {
        void run(Object hookContext);
    }

    private static HudPass hudRenderer = ctx -> {};

    private static boolean installed;

    /** Voir {@link HudPass}. À appeler AVANT {@link #install()}. */
    public static void setHudPass(HudPass pass) {
        if (pass != null) hudRenderer = pass;
    }

    public static void install() {
        if (installed) return;
        installed = true;
        VanillaHookRegistry.register(HookPoint.HUD_EXTRACT_CHAT, ctx -> {
            // ORDRE VOLONTAIRE : le HUD d'abord (ses panneaux atterrissent dans
            // l'état, ses icônes d'item vont dans la file), le flush ENSUITE —
            // les icônes se retrouvent donc au-dessus des panneaux, et
            // l'ensemble sous le chat, pas encore extrait.
            hudRenderer.run(ctx);
            VanillaGuiSink sink = VanillaGuiSinks.active();
            if (sink != null) sink.flushItemIcons(ctx);
            // TOUJOURS false : on s'insère dans la frame, on n'annule jamais le chat.
            return false;
        });
    }
}
