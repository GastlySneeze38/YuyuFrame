package net.minecraft.sounds;

/** Stub compile-only (26.1+) — champs statiques publics de {@code SoundEvents}. */
public abstract class SoundEvents {
    /** Voir {@code ChatEnhancementsModule} (ping de mention). */
    public static final SoundEvent EXPERIENCE_ORB_PICKUP = null;

    /**
     * Carillon net et bref — alerte de durabilité basse d'
     * {@code ArmorDurabilityModule}.
     *
     * <p>Choisi parce qu'il est directement un {@code SoundEvent} : beaucoup
     * d'entrées de cette classe (dont {@code ITEM_BREAK} et
     * {@code NOTE_BLOCK_PLING}) sont des {@code Holder$Reference}, qui
     * demanderaient la surcharge {@code forUI(Holder, float)} et un stub de
     * Holder de plus.
     */
    public static final SoundEvent AMETHYST_BLOCK_CHIME = null;
}
