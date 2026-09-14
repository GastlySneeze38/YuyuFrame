package net.minecraft.entity.effect;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code pf}).
 *
 * <p>L'instance porte l'id NUMÉRIQUE de son effet, pas l'effet lui-même ;
 * {@code isPermanent()} est le « ambiant » de 1.8.9 (balise), pas une durée
 * infinie — il n'existe pas d'effet infini sur cette version.
 */
public class StatusEffectInstance {

    private StatusEffectInstance() {
    }

    public int getEffectId() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getDuration() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public int getAmplifier() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
