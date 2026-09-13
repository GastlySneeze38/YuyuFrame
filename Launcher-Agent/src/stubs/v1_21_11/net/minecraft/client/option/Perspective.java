package net.minecraft.client.option;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code ges}) — pendant de
 * {@code CameraType} en 26.1.2.
 *
 * <p>Même forme que {@code Hand}/{@code Arm} : classe plutôt qu'{@code enum},
 * constantes de type OBJET (aucun risque d'inlining par javac).
 */
public final class Perspective {

    public static final Perspective FIRST_PERSON;
    public static final Perspective THIRD_PERSON_BACK;
    public static final Perspective THIRD_PERSON_FRONT;

    static {
        FIRST_PERSON = stub();
        THIRD_PERSON_BACK = stub();
        THIRD_PERSON_FRONT = stub();
    }

    private Perspective() {
    }

    private static Perspective stub() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
