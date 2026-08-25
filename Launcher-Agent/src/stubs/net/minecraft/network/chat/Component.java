package net.minecraft.network.chat;

/** Stub compile-only — obfusqué en yh dans client-mappings-1.21.11. */
public interface Component {
    static Component literal(String text) { return null; }
    static Component translatable(String key) { return null; }
    /** {@code getString()} — méthode par défaut PUBLIQUE (vérifiée javap), voir {@code ChatEnhancementsModule}. */
    default String getString() { return null; }
}
