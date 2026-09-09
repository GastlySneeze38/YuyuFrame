package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

import java.lang.reflect.Field;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.game.GameOptions;

/**
 * Fullbright — force {@code GameOptions.gamma} bien au-delà du maximum
 * vanilla (le slider en jeu plafonne à 1.0/100%, mais le champ brut accepte
 * n'importe quelle valeur — technique standard, indépendante de la version)
 * à CHAQUE frame tant que le module est actif, restaure la valeur d'origine
 * à la désactivation. Même découpage que {@link FovModule} (onTick force +
 * onEnabledChanged restaure).
 *
 * 26.1.2 sans réflexion (2026-08-26, §22 — audit modules) —
 * {@code AccessPoint.OPTIONS_GAMMA}/{@code OPTION_VALUE} via
 * {@link GameOptions}, qui route vers l'accessor de la tranche active
 * (architecture apimixin, même famille que {@code ZoomModule}.fov/sensitivity :
 * {@code OptionInstance<Double>}, vérifié javap). Écrit DIRECTEMENT le champ
 * {@code .value} via l'accessor, jamais {@code OptionInstance.set()} — même
 * raison que {@code ZoomModule} (validation vanilla qui clampe la valeur,
 * voir {@link McReflect#simpleOptionSetValue} pour l'historique complet de
 * ce piège). Repli réflexion multi-bracket sinon, comportement inchangé.
 */
public final class FullbrightModule extends LauncherModule {

    private static final float GAMMA_VALUE = 1000f;

    private float savedVanillaGamma = Float.NaN;

    public FullbrightModule() {
        super("fullbright", "Fullbright", "Éclaire toute la scène au maximum, ignore l'obscurité", false);
        iconUrl = icons8("sun");
        // Favori PAR DÉFAUT (2026-08-30, demande explicite) : ce module vit
        // désormais dans le groupe "Confort visuel" (voir ModuleRegistry), où
        // il n'aurait plus de carte à lui — le favori lui en redonne une sur
        // l'accueil, qui rouvre l'écran du groupe positionné sur ses réglages.
        //
        // Simple valeur de départ, pas un forçage : HudConfigStore.applyTo()
        // n'écrase ce champ que si la clé existe déjà dans le fichier de
        // config, donc un utilisateur qui décoche le cœur garde son choix.
        favorite = true;
    }

    @Override
    public void onTick() {
        try {
            Object options = optionsInstance();
            if (options == null) return;
            Object handle = gammaHandle(options);
            if (handle == null) return;
            if (Float.isNaN(savedVanillaGamma)) savedVanillaGamma = readGamma(handle, options);
            writeGamma(handle, options, GAMMA_VALUE);
        } catch (Throwable t) {
            // Journalisé une fois : sans ça, un fullbright qui n'éclaire rien
            // ne laissait AUCUNE trace — le mode d'échec le plus coûteux de ce
            // projet (voir la norme sur les catch muets).
            reportOnce("application du gamma : " + t);
        }
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (enabled) return;
        try {
            if (Float.isNaN(savedVanillaGamma)) return;
            Object options = optionsInstance();
            Object handle = options != null ? gammaHandle(options) : null;
            if (handle != null) writeGamma(handle, options, savedVanillaGamma);
        } catch (Throwable t) {
            reportOnce("restauration du gamma : " + t);
        } finally {
            savedVanillaGamma = Float.NaN;
        }
    }

    /**
     * BUG TROUVÉ (audit modules, voir historique de session) : {@code
     * GameOptions.gamma} est un {@code float} en 1.8.9 mais un {@code double}
     * en 1.13-1.16.5 (mappings 1.16.5 : {@code f D aR field_1840 gamma}) —
     * {@code getFloat}/{@code setFloat} levait {@code IllegalArgumentException}
     * sur ce dernier, avalée silencieusement, fullbright totalement
     * inopérant. Lit le VRAI type du champ au lieu de supposer — repli
     * réflexion UNIQUEMENT (voir {@link #gammaHandle}) : sur une tranche liée,
     * la valeur passe désormais par {@link GameOptions}, qui gère la
     * réencapsulation (voir {@link #writeGamma}).
     *
     * BUG TROUVÉ #2 (1.20.4, même refonte "SimpleOption" que ZoomModule.fov) :
     * {@code gamma} n'est plus un float/double DU TOUT ici — objet {@code
     * SimpleOption} FINAL (vérifié : {@code f Levl; cb field_1840 gamma}) —
     * voir {@link McReflect#simpleOptionGetValue}/{@link McReflect#simpleOptionSetValue}.
     */
    private float readGamma(Object handle, Object options) throws Exception {
        return (float) GameOptions.value(handle, Float.NaN);
    }

    private void writeGamma(Object handle, Object options, float value) throws Exception {
        // La réencapsulation (gamma est un OptionInstance<Double> sur 26.1.2)
        // et l'écriture DIRECTE du champ value — plutôt que setValue(), qui
        // déclenche la validation vanilla et clampe — vivent désormais dans
        // GameOptions, partagées avec ZoomModule/FovModule qui portaient
        // chacun leur copie de la même règle.
        GameOptions.setValue(handle, value);
    }

    /** Options par l'accessor Mixin, via {@code ClientData} — zéro réflexion (repli multi-bracket supprimé le 2026-08-27). */
    private Object optionsInstance() throws Exception {
        return ClientData.options();
    }

    /**
     * @return la poignée d'option de {@code Options.gamma}, ou {@code null}
     * hors tranche liée — même forme que {@code ZoomModule#fovHandle}. Le
     * paramètre {@code options} n'est plus lu : la poignée se demande
     * directement à {@link GameOptions}, qui repart du client courant.
     */
    private Object gammaHandle(Object options) throws Exception {
        return GameOptions.gammaHandle();
    }

    /** Journalise une raison d'échec UNE fois par raison distincte — ces chemins tournent à chaque tick. */
    private static String lastReport;

    private static void reportOnce(String reason) {
        if (reason.equals(lastReport)) return;
        lastReport = reason;
        com.yuyuframe.launcheragent.base.log.LauncherLog.err("[FullbrightModule] " + reason);
    }
}
