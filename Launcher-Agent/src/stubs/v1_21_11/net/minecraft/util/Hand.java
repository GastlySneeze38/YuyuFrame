package net.minecraft.util;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code cdb}) — pendant de
 * {@code InteractionHand} en 26.1.2.
 *
 * <p>Déclaré en CLASSE et non en {@code enum} : seules les deux constantes
 * comptent, et un vrai {@code enum} imposerait un constructeur privé plus les
 * membres synthétiques {@code values()}/{@code valueOf()} — du code qui ne sert
 * à rien ici et que le remappeur devrait traverser. Constantes de type OBJET,
 * donc aucun risque d'inlining par javac.
 *
 * <p>À savoir : cette notion n'existe pas avant la 1.9 ; la main est un
 * ARGUMENT de {@code getStackInHand(Hand)}, jamais un appel sans paramètre.
 */
public final class Hand {

    public static final Hand MAIN_HAND;
    public static final Hand OFF_HAND;

    static {
        MAIN_HAND = stub();
        OFF_HAND = stub();
    }

    private Hand() {
    }

    private static Hand stub() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
