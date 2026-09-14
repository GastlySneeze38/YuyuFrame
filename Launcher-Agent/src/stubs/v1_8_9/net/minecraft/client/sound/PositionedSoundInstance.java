package net.minecraft.client.sound;

import net.minecraft.util.Identifier;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code bpf}).
 *
 * <p>Pas de {@code ui(sound, volume, pitch)} : {@code master(id, pitch)} fixe
 * le volume à 0,25, et le seul constructeur public prend une POSITION
 * (atténuation linéaire). Le super-type réel est {@code AbstractSoundInstance}
 * ({@code bpa}) ; le stub implémente directement l'interface, seule
 * l'assignabilité compte à la compilation.
 */
public class PositionedSoundInstance implements SoundInstance {

    public PositionedSoundInstance(Identifier id, float volume, float pitch, float x, float y, float z) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public static PositionedSoundInstance master(Identifier id, float pitch) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
