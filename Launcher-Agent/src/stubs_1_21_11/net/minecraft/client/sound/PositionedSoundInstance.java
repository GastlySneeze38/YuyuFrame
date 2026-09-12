package net.minecraft.client.sound;

import net.minecraft.sound.SoundEvent;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code iph}) — pendant de
 * {@code SimpleSoundInstance} en 26.1.2 ; la fabrique d'un son d'interface s'y
 * appelle {@code forUI(...)} et ici {@code ui(...)}.
 *
 * <p>Ordre des paramètres, à ne pas intervertir : HAUTEUR puis VOLUME, comme
 * en 26.1.2 (vérifié dans les mappings : {@code ui(Lbcz;FF)Liph;}).
 */
public class PositionedSoundInstance extends SoundInstance {

    protected PositionedSoundInstance() {
    }

    public static PositionedSoundInstance ui(SoundEvent sound, float pitch, float volume) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
