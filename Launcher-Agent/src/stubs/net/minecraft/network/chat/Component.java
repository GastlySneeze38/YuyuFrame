package net.minecraft.network.chat;

/**
 * Stub compile-only — obfusqué en yh dans client-mappings-1.21.11.
 *
 * <p>{@code literal}/{@code translatable} renvoient {@link MutableComponent},
 * PAS {@code Component} — vérifié sur le jar client 26.1.2. Voir la javadoc
 * de {@code MutableComponent} pour le {@code NoSuchMethodError} que la
 * version précédente provoquait.
 */
public interface Component {
    static MutableComponent literal(String text) { return null; }
    static MutableComponent translatable(String key) { return null; }
    /** {@code getString()} — méthode par défaut PUBLIQUE (vérifiée javap), voir {@code ChatEnhancementsModule}. */
    default String getString() { return null; }
}
