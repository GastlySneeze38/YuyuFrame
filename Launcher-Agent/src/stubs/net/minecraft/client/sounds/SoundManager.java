package net.minecraft.client.sounds;

import net.minecraft.client.resources.sounds.SoundInstance;

/**
 * Stub compile-only (26.1+) — {@code play(SoundInstance)} publique, voir
 * {@code ChatEnhancementsModule}.
 *
 * <p>Signature vérifiée sur le jar client 26.1.2 réel :
 * {@code play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;}.
 * Le type de retour n'est PAS décoratif — il fait partie du descripteur
 * d'appel, voir la javadoc de {@link SoundEngine} pour le
 * {@code NoSuchMethodError} que sa version {@code void} provoquait.
 */
public abstract class SoundManager {
    public SoundEngine.PlayResult play(SoundInstance instance) { return null; }
}
