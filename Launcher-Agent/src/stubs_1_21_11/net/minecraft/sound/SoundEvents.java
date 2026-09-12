package net.minecraft.sound;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code bda}) — pendant de
 * {@code SoundEvents} en 26.1.2.
 *
 * <p>Seuls les deux sons servis par {@code AccessorBindings1211.soundById} sont
 * déclarés : on ne stube que ce qui est réellement utilisé, et chaque nom a été
 * vérifié dans les mappings.
 *
 * <p>Préfixes à ne pas deviner : Yarn nomme ces champs par leur catégorie de
 * registre ({@code BLOCK_}…, {@code ENTITY_}…) là où 26.1.2 les abrège
 * ({@code AMETHYST_BLOCK_CHIME}, {@code EXPERIENCE_ORB_PICKUP}). Constantes de
 * type OBJET, donc aucun risque d'inlining par javac.
 */
public final class SoundEvents {

    public static final SoundEvent BLOCK_AMETHYST_BLOCK_CHIME;
    public static final SoundEvent ENTITY_EXPERIENCE_ORB_PICKUP;

    static {
        BLOCK_AMETHYST_BLOCK_CHIME = stub();
        ENTITY_EXPERIENCE_ORB_PICKUP = stub();
    }

    private SoundEvents() {
    }

    private static SoundEvent stub() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
