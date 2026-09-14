package net.minecraft.entity.effect;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code pe}).
 *
 * <p>Pas de registre : un effet se désigne par son id NUMÉRIQUE, et ces ids
 * (1 = speed … 23 = saturation) sont ceux que les versions modernes ont
 * gardés comme ids bruts — la table vers « minecraft:… » est donc fixe.
 */
public class StatusEffect {

    /** Table publique indexée par id (trous à {@code null}) — {@code final} omis : sans effet sur le {@code getstatic}. */
    public static StatusEffect[] STATUS_EFFECTS;

    private StatusEffect() {
    }

    public int getId() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public String getTranslationKey() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean isNegative() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getColor() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
