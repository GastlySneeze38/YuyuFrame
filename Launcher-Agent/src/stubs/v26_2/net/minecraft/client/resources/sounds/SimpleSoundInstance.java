package net.minecraft.client.resources.sounds;

import net.minecraft.sounds.SoundEvent;

/** Stub compile-only (26.1+) — {@code forUI(SoundEvent, float)} publique statique, voir {@code ChatEnhancementsModule}. */
public abstract class SimpleSoundInstance implements SoundInstance {
    public static SimpleSoundInstance forUI(SoundEvent event, float pitch) { return null; }

    /** Surcharge avec VOLUME — vérifiée sur le jar 26.1.2 ({@code forUI(SoundEvent,F,F)}). Permet une alerte discrète plutôt qu'un son à pleine puissance. */
    public static SimpleSoundInstance forUI(SoundEvent event, float pitch, float volume) { return null; }
}
