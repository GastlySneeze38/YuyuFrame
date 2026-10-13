package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3.UiInputPollerModern;
import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.runtime.combat.CombatTracker;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * Score PvP (docs/scoring/) — pour l'instant, seulement la RÉCOLTE : allumé,
 * le module relève le combat et le transmet au noyau natif, qui écrit le
 * journal dans {@code yuyuframe/matches/} du dossier de l'instance.
 *
 * <p><b>C'est le joueur qui dit où un duel commence et finit</b> : une touche
 * l'ouvre, la même le ferme, et rien n'est écrit en dehors. Reconnaître seul
 * un duel dépend de chaque serveur ; ce sera fait quand le calcul du score
 * sera avancé (docs/scoring/match-1v1.md). Un son court confirme l'appui,
 * aigu à l'ouverture, grave à la fermeture.
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

    private static final String SOUND = "minecraft:block.note_block.pling";

    /** La touche qui ouvre et ferme un duel. */
    public String matchKey = "K";

    private boolean keyWasDown;

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
    protected void settings(SettingList s) {
        s.keybind("matchKey", "Touche du duel",
            "Un appui ouvre le duel, un second le ferme. Rien n'est enregistré en dehors.",
            "Réglages", null, () -> matchKey, v -> matchKey = v);
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        CombatTracker.setActive(enabled);
    }

    /**
     * La touche, lue à chaque image et non à chaque tick du jeu : un appui
     * bref tient moins d'un vingtième de seconde et serait manqué. Ignorée
     * quand un écran est ouvert — sinon taper la lettre dans le chat
     * ouvrirait un duel.
     */
    @Override
    public void onTick() {
        UiInputPollerModern input = UiInputPollerModern.ACTIVE;
        boolean down = input != null && input.isKeyDownByName(matchKey)
            && AccessorRegistry.get(AccessPoint.CLIENT_SCREEN, null) == null;
        if (down && !keyWasDown) {
            boolean before = CombatTracker.recording();
            boolean after = CombatTracker.toggleMatch();
            if (after != before) {
                ClientData.playUiSound(SOUND, after ? 1.8f : 0.7f, 1f);
            }
        }
        keyWasDown = down;
    }
}
