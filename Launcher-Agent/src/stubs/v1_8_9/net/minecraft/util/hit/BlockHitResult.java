package net.minecraft.util.hit;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code auh}).
 *
 * <p>Sur cette version la classe sert AUSSI aux entités et au vide : le genre
 * de cible est le champ public {@code type}, pas une sous-classe.
 */
public class BlockHitResult {

    public Type type;

    private BlockHitResult() {
    }

    /** {@code auh$a} : {@code MISS}, {@code BLOCK}, {@code ENTITY}. */
    public enum Type {
        MISS,
        BLOCK,
        ENTITY
    }
}
