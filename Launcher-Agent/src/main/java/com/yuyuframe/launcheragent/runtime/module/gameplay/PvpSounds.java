package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.runtime.game.ClientData;

/**
 * Les sons du Score PvP : ceux du jeu lui-même (blocs musicaux, orbe
 * d'expérience, montée de niveau, défi accompli), joués comme des sons
 * d'interface. Un seul interrupteur, le réglage « Sons » du module.
 *
 * <p>Les identifiants existent dans toutes les versions où le module tourne
 * (vérifiés dans le {@code sounds.json} de la 26.x) ; un son introuvable ne
 * joue simplement pas. Le réglage « Volume des sons » les règle tous.
 */
final class PvpSounds {

    static final String PLING = "minecraft:block.note_block.pling";
    static final String BELL = "minecraft:block.note_block.bell";
    static final String CHIME = "minecraft:block.note_block.chime";
    static final String HAT = "minecraft:block.note_block.hat";
    static final String BASS = "minecraft:block.note_block.bass";
    static final String DRUM = "minecraft:block.note_block.basedrum";
    static final String ORB = "minecraft:entity.experience_orb.pickup";
    static final String LEVEL_UP = "minecraft:entity.player.levelup";
    static final String CHALLENGE = "minecraft:ui.toast.challenge_complete";
    /** Le « ding » d'une flèche qui touche un joueur : le son du coup réussi en PvP, base des mots. */
    static final String DING = "minecraft:entity.arrow.hit_player";
    /** Une division perdue : grave et discret. */
    static final String DIDGERIDOO = "minecraft:block.note_block.didgeridoo";
    /** Les duels de placement, un cran plus haut à chacun. */
    static final String XYLOPHONE = "minecraft:block.note_block.iron_xylophone";
    /** La touche compétitif / casual. */
    static final String CLICK = "minecraft:ui.button.click";
    /** Le coffre de la cérémonie : son cliquetis quand le couvercle saute, son ouverture à l'éclat. */
    static final String CHEST_LOCKED = "minecraft:block.chest.locked";
    static final String CHEST_OPEN = "minecraft:block.chest.open";
    /** La lueur qui monte dans le coffre. */
    static final String RESONATE = "minecraft:block.amethyst_block.resonate";
    /** La tuile qui sort du coffre. */
    static final String TWINKLE = "minecraft:entity.firework_rocket.twinkle";
    /** En plus pour Champion. */
    static final String BEACON = "minecraft:block.beacon.activate";

    /** Le réglage « Sons » du module. */
    static volatile boolean enabled = true;
    /** Le réglage « Volume des sons », de 0 à 1. */
    static volatile float volume = 1f;

    private PvpSounds() {
    }

    /** {@code pitch} de 0,5 à 2 (le jeu borne), {@code volume} de 0 à 1, multiplié par le réglage. */
    static void play(String id, float pitch, float volume) {
        if (!enabled) return;
        float level = volume * Math.max(0f, Math.min(1f, PvpSounds.volume));
        if (level <= 0.001f) return;
        ClientData.playUiSound(id, Math.max(0.5f, Math.min(2f, pitch)), level);
    }
}
