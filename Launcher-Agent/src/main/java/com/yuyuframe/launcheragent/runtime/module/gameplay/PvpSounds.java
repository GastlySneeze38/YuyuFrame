package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.runtime.game.ClientData;

/**
 * Les sons du Score PvP : ceux du jeu lui-même (blocs musicaux, orbe
 * d'expérience, montée de niveau, défi accompli), joués comme des sons
 * d'interface. Un seul interrupteur, le réglage « Sons » du module.
 *
 * <p>Les identifiants existent dans toutes les versions où le module tourne ;
 * un son introuvable ne joue simplement pas.
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
    /** Le coffre qui s'ouvre, à l'éclat de la cérémonie. */
    static final String CHEST_OPEN = "minecraft:block.chest.open";

    /** Le réglage « Sons » du module. */
    static volatile boolean enabled = true;

    private PvpSounds() {
    }

    /** {@code pitch} de 0,5 à 2 (le jeu borne), {@code volume} de 0 à 1. */
    static void play(String id, float pitch, float volume) {
        if (!enabled) return;
        ClientData.playUiSound(id, Math.max(0.5f, Math.min(2f, pitch)), volume);
    }
}
