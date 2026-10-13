package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.runtime.combat.CombatTracker;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

/**
 * Score PvP (docs/scoring/) — pour l'instant, seulement la RÉCOLTE : allumé,
 * le module relève le combat et le transmet au noyau natif, qui écrit le
 * journal dans {@code yuyuframe/matches/} du dossier de l'instance. Aucun
 * réglage, aucun affichage : l'interface viendra quand le calcul existera et
 * qu'on saura ce qu'il y a à montrer.
 *
 * <p>Éteint par défaut : tant que le score n'est pas livré, personne ne doit
 * se retrouver avec des journaux écrits sans l'avoir demandé.
 *
 * <p>Le module ne contient aucune logique. Java relève ({@link CombatTracker}),
 * Rust décide et calcule ({@code content-core/src/combat/}).
 */
public final class PvpScoreModule extends LauncherModule {

    /** Les versions dont les mixins de combat existent ({@code apimixin/<version>/combat}). */
    public static final java.util.Set<String> VERSIONS = java.util.Set.of("26.1.2", "26.3");

    public PvpScoreModule() {
        super("pvp-score", "Score PvP",
            "Enregistre vos duels à la masse pour les noter. En cours de développement : seul le journal du combat est écrit pour l'instant.",
            "Journal des duels à la masse", false,
            // DÉCLARÉS, sinon les mixins correspondants ne sont pas tissés du
            // tout (voir MixinHookPointRegistry).
            HookPoint.CLIENT_TICK, HookPoint.SERVER_CONNECT, HookPoint.CLIENT_LEVEL_LOAD,
            HookPoint.ENTITY_ATTACK, HookPoint.DAMAGE_EVENT, HookPoint.ENTITY_EVENT, HookPoint.ITEM_USE);
        iconUrl = icons8("sword");
        CombatTracker.install();
        CombatTracker.setActive(isEnabled());
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        CombatTracker.setActive(enabled);
    }
}
