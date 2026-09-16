package net.minecraft.util.hit;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code auh}).
 *
 * <p>Sur cette version la classe sert AUSSI aux entités et au vide : le genre
 * de cible est le champ public {@code type}, pas une sous-classe.
 */
public class BlockHitResult {

    public Type type;

    /** Face touchée ({@code auh.b}). */
    public net.minecraft.util.math.Direction direction;

    private BlockHitResult() {
    }

    /** Bloc visé, {@code null} hors cible bloc ({@code auh.a()Lcj;}). */
    public net.minecraft.util.math.BlockPos getBlockPos() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code auh$a} : {@code MISS}, {@code BLOCK}, {@code ENTITY}. */
    public enum Type {
        MISS,
        BLOCK,
        ENTITY
    }
}
