package net.minecraft.client.option;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code gfh}).
 *
 * <p>{@code boundKey} est privé, mais cette version n'a PAS besoin d'accessor
 * pour autant : {@code getBoundKeyTranslationKey()} est publique et donne la
 * clé de traduction, qu'{@code InputUtil.fromTranslationKey} retransforme en
 * objet touche. C'est ce chemin que sert {@code KEYBIND_KEY_CODE}.
 */
public class KeyBinding {

    private KeyBinding() {
    }

    public boolean isPressed() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /**
     * Clé de traduction de la touche liée, ex. {@code "key.keyboard.w"} —
     * chemin public vers {@code boundKey}, qui est privé.
     */
    public String getBoundKeyTranslationKey() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
