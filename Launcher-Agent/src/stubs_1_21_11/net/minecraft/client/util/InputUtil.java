package net.minecraft.client.util;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code fyc}) — pendant de
 * {@code InputConstants} en 26.1.2.
 *
 * <p>Seule {@link #fromTranslationKey} est déclarée : c'est le chemin PUBLIC
 * qui évite un accessor Mixin sur le champ privé {@code KeyBinding.boundKey}.
 * On part de la clé de traduction ({@code "key.keyboard.w"}), rendue elle aussi
 * par une méthode publique, et on en tire l'objet touche puis son code.
 */
public final class InputUtil {

    private InputUtil() {
    }

    /** Touche correspondant à une clé de traduction, ex. {@code "key.keyboard.w"}. */
    public static Key fromTranslationKey(String translationKey) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /**
     * Nom Yarn {@code fyc$a} — pendant d'{@code InputConstants$Key}.
     *
     * <p>{@code getCode()} ici, {@code getValue()} en 26.1.2 : c'est justement
     * cet écart que le point d'accès {@code KEYBIND_KEY_CODE} absorbe, en
     * rendant l'entier plutôt que l'objet.
     */
    public static class Key {

        protected Key() {
        }

        public int getCode() {
            throw new UnsupportedOperationException("stub compile-only");
        }
    }
}
